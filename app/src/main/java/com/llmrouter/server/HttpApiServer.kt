package com.llmrouter.server

import com.llmrouter.data.repo.SettingsRepository
import com.llmrouter.relay.RelayHandler
import com.llmrouter.relay.RelayResult
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.InputStream

/**
 * 内嵌 HTTP API 服务器 — 暴露 OpenAI 兼容端点
 *
 * 端点：
 * - GET  /v1/models           — 列出所有可用模型
 * - POST /v11/chat/completions — 聊天补全（支持 streaming）
 * - POST /v1/embeddings       — 文本嵌入
 * - POST /v1/completions      — 文本补全
 * - GET  /health              — 健康检查
 * - GET  /                    — 服务信息
 *
 * 鉴权：可选，通过 Authorization: Bearer <token> 头验证
 *
 * 关键修复：NanoHTTPD(port) 默认只绑定 localhost，外部设备连不上。
 * 改用 NanoHTTPD("0.0.0.0", port) 显式绑定所有网卡，确保局域网可访问。
 */
class HttpApiServer(
    private val port: Int,
    private val relayHandler: RelayHandler,
    private val settingsRepository: SettingsRepository
) : NanoHTTPD("0.0.0.0", port) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        val method = session.method

        return try {
            // 鉴权检查
            if (!checkAuth(session)) {
                return jsonError(401, "未授权：缺少或无效的访问令牌")
            }

            when {
                uri == "/" || uri == "/health" -> {
                    jsonOk("""{"status":"ok","service":"LLM Router","port":$port}""")
                }

                uri == "/v1/models" && method == Method.GET -> {
                    handleModels()
                }

                uri == "/v1/chat/completions" && method == Method.POST -> {
                    handleChatCompletions(session)
                }

                uri == "/v1/embeddings" && method == Method.POST -> {
                    handleEmbeddings(session)
                }

                uri == "/v1/completions" && method == Method.POST -> {
                    handleChatCompletions(session)
                }

                else -> jsonError(404, "端点不存在: $uri")
            }
        } catch (e: Exception) {
            jsonError(500, "服务器内部错误: ${e.message}")
        }
    }

    /** 鉴权检查 */
    private fun checkAuth(session: IHTTPSession): Boolean {
        val settings = runBlocking { settingsRepository.getSnapshot() }
        if (!settings.authEnabled) return true
        if (settings.authToken.isBlank()) return true

        // NanoHTTPD headers 是 Map<String, String>
        val headers = session.headers ?: return false
        val authHeader = headers.entries
            .firstOrNull { it.key.equals("authorization", true) }
            ?.value
        val token = authHeader?.removePrefix("Bearer ")?.trim()
        return token == settings.authToken
    }

    /** 处理 /v1/models */
    private fun handleModels(): Response {
        val result = runBlocking { relayHandler.handleListModels() }
        return when (result) {
            is RelayResult.Json -> jsonOk(result.body)
            is RelayResult.Error -> jsonError(502, result.message)
            else -> jsonError(500, "意外的响应类型")
        }
    }

    /** 处理 /v1/chat/completions */
    private fun handleChatCompletions(session: IHTTPSession): Response {
        // 解析请求体
        val body = parseBody(session) ?: return jsonError(400, "请求体为空")

        // 解析 model 和 stream
        val json = try { JSONObject(body) } catch (e: Exception) {
            return jsonError(400, "无效的 JSON 请求体")
        }

        val model = json.optString("model", "")
        if (model.isBlank()) return jsonError(400, "缺少 model 参数")

        val stream = json.optBoolean("stream", false)

        val result = runBlocking {
            relayHandler.handleChatCompletions(body, model, stream)
        }

        return when (result) {
            is RelayResult.Json -> jsonOk(result.body)
            is RelayResult.Stream -> {
                // SSE 流式响应
                newChunkedResponse(Response.Status.OK, "text/event-stream", result.inputStream)
            }
            is RelayResult.Error -> jsonError(result.statusCode, result.message)
        }
    }

    /** 处理 /v1/embeddings */
    private fun handleEmbeddings(session: IHTTPSession): Response {
        val body = parseBody(session) ?: return jsonError(400, "请求体为空")

        val json = try { JSONObject(body) } catch (e: Exception) {
            return jsonError(400, "无效的 JSON 请求体")
        }

        val model = json.optString("model", "")
        if (model.isBlank()) return jsonError(400, "缺少 model 参数")

        val result = runBlocking {
            relayHandler.handleEmbeddings(body, model)
        }

        return when (result) {
            is RelayResult.Json -> jsonOk(result.body)
            is RelayResult.Error -> jsonError(result.statusCode, result.message)
            else -> jsonError(500, "意外的响应类型")
        }
    }

    /** 解析 POST 请求体 */
    private fun parseBody(session: IHTTPSession): String? {
        val files = HashMap<String, String>()
        val headers = session.headers ?: return null
        val size = headers.entries
            .firstOrNull { it.key.equals("content-length", true) }
            ?.value?.toIntOrNull() ?: 0

        if (size == 0) return null

        try {
            session.parseBody(files)
        } catch (e: Exception) {
            return null
        }

        // NanoHTTPD 将 POST body 存在 "postData" key 中
        return files["postData"]
    }

    // === 辅助方法 ===

    private fun jsonOk(body: String): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json", body)

    private fun jsonError(code: Int, message: String): Response {
        val error = JSONObject().apply {
            put("error", JSONObject().apply {
                put("message", message)
                put("type", "router_error")
                put("code", code)
            })
        }
        // 选择 HTTP 状态
        val status: Response.Status = when (code) {
            400 -> Response.Status.BAD_REQUEST
            401 -> Response.Status.UNAUTHORIZED
            404 -> Response.Status.NOT_FOUND
            500 -> Response.Status.INTERNAL_ERROR
            502 -> Response.Status.INTERNAL_ERROR
            else -> Response.Status.INTERNAL_ERROR
        }
        return newFixedLengthResponse(status, "application/json", error.toString())
    }
}
