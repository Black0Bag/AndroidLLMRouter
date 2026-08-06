package com.llmrouter.relay

import org.json.JSONObject
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * v0.8.0: Token 捕获流 — 包装 SSE 输入流，边透传边解析 usage
 *
 * OpenAI 流式响应格式：
 * data: {"id":"...","choices":[...],"usage":null}
 * ...
 * data: {"id":"...","choices":[],"usage":{"prompt_tokens":10,"completion_tokens":20,"total_tokens":30}}
 * data: [DONE]
 *
 * 本类在 read() 过程中将原始字节透传给调用方，
 * 同时用行缓冲解析 SSE 数据帧中的 usage 对象，
 * 流结束或 close() 时通过回调返回 Token 统计结果。
 */
class TokenCapturingInputStream(
    private val source: InputStream,
    private val onComplete: (Triple<Int, Int, Int>) -> Unit
) : InputStream() {

    private var lineBuffer = StringBuilder()
    private var lastUsage: Triple<Int, Int, Int> = Triple(0, 0, 0)
    private var callbackFired = false
    private var closed = false

    override fun read(): Int {
        val b = source.read()
        if (b == -1) {
            fireCallback()
            return -1
        }
        processByte(b)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = source.read(b, off, len)
        if (n == -1) {
            fireCallback()
            return -1
        }
        // 逐字节解析行（对性能影响极小，因为只检测换行符）
        for (i in 0 until n) {
            processByte(b[off + i].toInt() and 0xFF)
        }
        return n
    }

    private fun processByte(b: Int) {
        if (b == '\n'.code) {
            val line = lineBuffer.toString()
            lineBuffer = StringBuilder()
            tryParseSSELine(line)
        } else if (b != '\r'.code) {
            lineBuffer.append(b.toChar())
        }
    }

    private fun tryParseSSELine(line: String) {
        val trimmed = line.trim()
        if (!trimmed.startsWith("data:")) return
        if (trimmed == "data: [DONE]") return

        val jsonStr = trimmed.removePrefix("data:").trim()
        if (jsonStr.isEmpty() || jsonStr.startsWith("[")) return

        try {
            val json = JSONObject(jsonStr)
            val usage = json.optJSONObject("usage")
            if (usage != null) {
                val prompt = usage.optInt("prompt_tokens", 0)
                val completion = usage.optInt("completion_tokens", 0)
                val total = usage.optInt("total_tokens", 0)
                if (total > 0 || prompt > 0 || completion > 0) {
                    lastUsage = Triple(prompt, completion, total)
                }
            }
        } catch (e: Exception) {
            // 非 JSON 行，忽略
        }
    }

    private fun fireCallback() {
        if (!callbackFired) {
            // 处理可能剩余的最后一行
            if (lineBuffer.isNotEmpty()) {
                tryParseSSELine(lineBuffer.toString())
                lineBuffer = StringBuilder()
            }
            callbackFired = true
            try {
                onComplete(lastUsage)
            } catch (e: Exception) {
                // 回调异常不影响流关闭
            }
        }
    }

    override fun close() {
        if (!closed) {
            fireCallback()
            closed = true
        }
        try {
            source.close()
        } catch (e: Exception) {
            // ignore
        }
    }

    override fun available(): Int {
        return try {
            source.available()
        } catch (e: Exception) {
            0
        }
    }
}
