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
 * v0.6.0 改进：
 * - /v1/completions 独立路由到上游 /v1/completions（不再复用 chat/completions）
 * - 新增 /v1/moderations 端点
 * - 新增 /v1/images/generations 端点（透传）
 * - 新增 /v1/audio/transcriptions 端点（透传）
 * - 新增 /v1/audio/speech 端点（透传）
 * - 多协议鉴权：Bearer / x-api-key / ?key=
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

                // v0.6.0: /v1/completions 独立路由
                uri == "/v1/completions" && method == Method.POST -> {
                    handleCompletions(session)
                }

                uri == "/v1/embeddings" && method == Method.POST -> {
                    handleEmbeddings(session)
                }

                // v0.6.0: 新增透传端点
                uri == "/v1/moderations" && method == Method.POST -> {
                    handleGenericRelay(session, "/v1/moderations")
                }

                uri == "/v1/images/generations" && method == Method.POST -> {
                    handleGenericRelay(session, "/v1/images/generations")
                }

                uri == "/v1/audio/transcriptions" && method == Method.POST -> {
                    handleGenericRelay(session, "/v1/audio/transcriptions")
                }

                uri == "/v1/audio/speech" && method == Method.POST -> {
                    handleGenericRelay(session, "/v1/audio/speech")
                }

                uri == "/v1/rerank" && method == Method.POST -> {
                    handleGenericRelay(session, "/v1/rerank")
                }

                else -> jsonError(404, "端点不存在: $uri")
            }
        } catch (e: Exception) {
            jsonError(500, "服务器内部错误: ${e.message}")
        }
    }

    /** v0.6.0: 多协议鉴权 — Bearer / x-api-key / ?key= */
    private fun checkAuth(session: IHTTPSession): Boolean {
        val settings = runBlocking { settingsRepository.getSnapshot() }
        if (!settings.authEnabled) return true
        if (settings.authToken.isBlank()) return true

        val headers = session.headers ?: return false

        // 1. Authorization: Bearer <token>
        val authHeader = headers.entries
            .firstOrNull { it.key.equals("authorization", true) }?.value
        if (authHeader != null) {
            val token = authHeader.removePrefix("Bearer ").trim()
            if (token == settings.authToken) return true
        }

        // 2. x-api-key: <token> (Claude/Gemini 兼容)
        val apiKeyHeader = headers.entries
            .firstOrNull { it.key.equals("x-api-key", true) }?.value
        if (apiKeyHeader != null && apiKeyHeader == settings.authToken) return true

        // 3. ?key=<token> (Gemini 兼容)
        val queryParam = session.parameters?.get("key")
        if (queryParam != null && queryParam == settings.authToken) return true

        return false
    }

    /** 处理 /v1/models */
    private fun handleModels(): Response {
        val result = runBlocking { relayHandler.handleListModels() }
        return when (result) {
            is RelayResult.Json -> jsonOk(result.body)
            is RelayResult.Error -> jsonError(result.statusCode, result.message)
            else -> jsonError(500, "意外的响应类型")
        }
    }

    /** 处理 /v1/chat/completions */
    private fun handleChatCompletions(session: IHTTPSession): Response {
        val body = parseBody(session) ?: return jsonError(400, "请求体为空")

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
                newChunkedResponse(Response.Status.OK, "text/event-stream", result.inputStream)
            }
            is RelayResult.Error -> jsonError(result.statusCode, result.message)
        }
    }

    /** v0.6.0: 处理 /v1/completions — 独立路由 */
    private fun handleCompletions(session: IHTTPSession): Response {
        val body = parseBody(session) ?: return jsonError(400, "请求体为空")

        val json = try { JSONObject(body) } catch (e: Exception) {
            return jsonError(400, "无效的 JSON 请求体")
        }

        val model = json.optString("model", "")
        if (model.isBlank()) return jsonError(400, "缺少 model 参数")

        val result = runBlocking {
            relayHandler.handleCompletions(body, model)
        }

        return when (result) {
            is RelayResult.Json -> jsonOk(result.body)
            is RelayResult.Error -> jsonError(result.statusCode, result.message)
            else -> jsonError(500, "意外的响应类型")
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

    /**
     * v0.6.0: 通用透传 — 用于 moderations/images/audio/rerank 等端点
     * 这些端点直接转发到上游，不走路由引擎的模型映射
     * （因为它们可能不是标准 JSON，或不需要模型路由）
     */
    private fun handleGenericRelay(session: IHTTPSession, endpoint: String): Response {
        val body = parseBody(session) ?: return jsonError(400, "请求体为空")
        val json = try { JSONObject(body) } catch (e: Exception) {
            return jsonError(400, "无效的 JSON 请求体")
        }
        val model = json.optString("model", "")

        // 尝试路由
        if (model.isNotBlank()) {
            val result = runBlocking {
                relayHandler.handleGenericRelay(body, model, endpoint)
            }
            return when (result) {
                is RelayResult.Json -> jsonOk(result.body)
                is RelayResult.Error -> jsonError(result.statusCode, result.message)
                else -> jsonError(500, "意外的响应类型")
            }
        }

        jsonError(400, "缺少 model 参数")
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
