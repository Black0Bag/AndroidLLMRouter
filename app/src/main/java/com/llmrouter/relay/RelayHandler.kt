package com.llmrouter.relay

import com.llmrouter.data.db.RouteLogDao
import com.llmrouter.data.model.ChannelEntity
import com.llmrouter.data.repo.ChannelRepository
import com.llmrouter.data.repo.SettingsRepository
import com.llmrouter.data.repo.SettingsSnapshot
import com.llmrouter.router.RouterEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** 转发结果 */
sealed class RelayResult {
    data class Json(val body: String, val statusCode: Int = 200) : RelayResult()
    data class Stream(val inputStream: InputStream) : RelayResult()
    data class Error(val message: String, val statusCode: Int = 502) : RelayResult()
}

/**
 * 转发层 — 移植 NEW API 的请求转发/重试/Fallback/AutoBan 逻辑
 *
 * v0.6.0 改进：
 * - AutoBan 仅在 401/403 时触发（与 NEW API 一致），不再对所有错误码禁用
 * - 异常（超时/连接失败）不禁用 Key，只重试
 * - 支持模型映射（model_mapping）
 * - 支持状态码映射（status_code_mapping）
 * - 解析上游响应中的 Token 用量并记录到日志
 * - /v1/completions 独立路由到上游 /v1/completions（不再复用 chat/completions）
 */
class RelayHandler(
    private val routerEngine: RouterEngine,
    private val channelRepository: ChannelRepository,
    private val settingsRepository: SettingsRepository,
    private val routeLogDao: RouteLogDao
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /**
     * 处理 /v1/chat/completions 请求
     */
    suspend fun handleChatCompletions(
        requestBody: String,
        model: String,
        stream: Boolean
    ): RelayResult = withContext(Dispatchers.IO) {
        val settings = settingsRepository.getSnapshot()
        val maxRetries = settings.retryTimes
        var lastError = "未知错误"

        for (retry in 0..maxRetries) {
            val channel = routerEngine.selectChannel(model, retry, settings.routeMode)
            if (channel == null) {
                lastError = "没有可用的渠道来处理模型 $model"
                break
            }

            val keyAndIndex = selectKeyWithIndex(channel)
            if (keyAndIndex == null) {
                lastError = "渠道 ${channel.name} 的所有密钥已被禁用"
                continue
            }

            val (apiKey, keyIndex) = keyAndIndex

            // 应用模型映射
            val actualModel = channel.applyModelMapping(model)

            // 如果模型映射后模型名变了，需要替换请求体中的 model 字段
            val finalBody = if (actualModel != model) {
                try {
                    val json = JSONObject(requestBody)
                    json.put("model", actualModel)
                    json.toString()
                } catch (e: Exception) {
                    requestBody
                }
            } else {
                requestBody
            }

            try {
                val startTime = System.currentTimeMillis()
                val url = buildUrl(channel.baseUrl, "/v1/chat/completions")

                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $apiKey")
                    .header("Content-Type", "application/json")
                    .post(finalBody.toRequestBody(jsonMedia))
                    .build()

                if (stream) {
                    val response = httpClient.newCall(request).execute()
                    val elapsed = (System.currentTimeMillis() - startTime).toInt()

                    if (response.isSuccessful && response.body != null) {
                        logSuccess(model, channel, elapsed, "/v1/chat/completions", 200)
                        channelRepository.incrementQuota(channel.id)
                        return@withContext RelayResult.Stream(response.body!!.byteStream())
                    } else {
                        val errorBody = response.body?.string() ?: ""
                        val code = response.code
                        val mappedCode = channel.applyStatusCodeMapping(code)
                        lastError = "上游错误 $code: ${errorBody.take(200)}"
                        handleUpstreamError(channel, keyIndex, code, errorBody, settings)
                        response.close()
                        continue
                    }
                } else {
                    val response = httpClient.newCall(request).execute()
                    val elapsed = (System.currentTimeMillis() - startTime).toInt()
                    val body = response.body?.string() ?: ""

                    if (response.isSuccessful) {
                        // 解析 Token 用量
                        val tokenInfo = parseTokenUsage(body)
                        logSuccess(model, channel, elapsed, "/v1/chat/completions", 200, tokenInfo)
                        channelRepository.incrementQuota(channel.id)
                        return@withContext RelayResult.Json(body)
                    } else {
                        val mappedCode = channel.applyStatusCodeMapping(response.code)
                        lastError = "上游错误 ${response.code}: ${body.take(200)}"
                        handleUpstreamError(channel, keyIndex, response.code, body, settings)
                        continue
                    }
                }
            } catch (e: java.net.SocketTimeoutException) {
                lastError = "请求超时: ${e.message}"
                // v0.6.0: 异常不禁用 Key，只重试
                continue
            } catch (e: java.net.ConnectException) {
                lastError = "连接失败: ${e.message}"
                continue
            } catch (e: Exception) {
                lastError = "请求异常: ${e.message ?: e.javaClass.simpleName}"
                continue
            }
        }

        // 记录失败日志
        logFailure(model, "/v1/chat/completions", lastError, maxRetries)
        RelayResult.Error(lastError)
    }

    /**
     * 处理 /v1/completions 请求（独立路由，不再复用 chat/completions）
     */
    suspend fun handleCompletions(
        requestBody: String,
        model: String
    ): RelayResult = withContext(Dispatchers.IO) {
        val settings = settingsRepository.getSnapshot()
        val maxRetries = settings.retryTimes
        var lastError = "未知错误"

        for (retry in 0..maxRetries) {
            val channel = routerEngine.selectChannel(model, retry, settings.routeMode)
            if (channel == null) {
                lastError = "没有可用的渠道来处理模型 $model"
                break
            }

            val keyAndIndex = selectKeyWithIndex(channel)
            if (keyAndIndex == null) {
                lastError = "渠道 ${channel.name} 的所有密钥已被禁用"
                continue
            }
            val (apiKey, keyIndex) = keyAndIndex

            val actualModel = channel.applyModelMapping(model)
            val finalBody = if (actualModel != model) {
                try {
                    val json = JSONObject(requestBody)
                    json.put("model", actualModel)
                    json.toString()
                } catch (e: Exception) {
                    requestBody
                }
            } else {
                requestBody
            }

            try {
                val url = buildUrl(channel.baseUrl, "/v1/completions")
                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $apiKey")
                    .header("Content-Type", "application/json")
                    .post(finalBody.toRequestBody(jsonMedia))
                    .build()

                val response = httpClient.newCall(request).execute()
                val body = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    val tokenInfo = parseTokenUsage(body)
                    logSuccess(model, channel, 0, "/v1/completions", 200, tokenInfo)
                    channelRepository.incrementQuota(channel.id)
                    return@withContext RelayResult.Json(body)
                } else {
                    lastError = "上游错误 ${response.code}: ${body.take(200)}"
                    handleUpstreamError(channel, keyIndex, response.code, body, settings)
                    continue
                }
            } catch (e: Exception) {
                lastError = "请求异常: ${e.message}"
                continue
            }
        }

        logFailure(model, "/v1/completions", lastError, maxRetries)
        RelayResult.Error(lastError)
    }

    /**
     * 处理 /v1/models 请求 — 聚合所有渠道的模型列表
     */
    suspend fun handleListModels(): RelayResult = withContext(Dispatchers.IO) {
        val channels = routerEngine.getAllEnabledChannels()
        val models = mutableSetOf<String>()
        for (ch in channels) {
            models.addAll(ch.modelList())
        }

        val data = models.sorted().map { model ->
            JSONObject().apply {
                put("id", model)
                put("object", "model")
                put("owned_by", "llm-router")
            }
        }

        val result = JSONObject().apply {
            put("object", "list")
            put("data", org.json.JSONArray(data))
        }

        RelayResult.Json(result.toString())
    }

    /**
     * 处理 /v1/embeddings 请求
     */
    suspend fun handleEmbeddings(
        requestBody: String,
        model: String
    ): RelayResult = withContext(Dispatchers.IO) {
        val settings = settingsRepository.getSnapshot()
        val maxRetries = settings.retryTimes
        var lastError = "未知错误"

        for (retry in 0..maxRetries) {
            val channel = routerEngine.selectChannel(model, retry, settings.routeMode)
            if (channel == null) {
                lastError = "没有可用的渠道来处理模型 $model"
                break
            }

            val keyAndIndex = selectKeyWithIndex(channel)
            if (keyAndIndex == null) {
                lastError = "渠道 ${channel.name} 的所有密钥已被禁用"
                continue
            }
            val (apiKey, keyIndex) = keyAndIndex

            val actualModel = channel.applyModelMapping(model)
            val finalBody = if (actualModel != model) {
                try {
                    val json = JSONObject(requestBody)
                    json.put("model", actualModel)
                    json.toString()
                } catch (e: Exception) {
                    requestBody
                }
            } else {
                requestBody
            }

            try {
                val url = buildUrl(channel.baseUrl, "/v1/embeddings")
                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $apiKey")
                    .header("Content-Type", "application/json")
                    .post(finalBody.toRequestBody(jsonMedia))
                    .build()

                val response = httpClient.newCall(request).execute()
                val body = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    val tokenInfo = parseTokenUsage(body)
                    logSuccess(model, channel, 0, "/v1/embeddings", 200, tokenInfo)
                    channelRepository.incrementQuota(channel.id)
                    return@withContext RelayResult.Json(body)
                } else {
                    lastError = "上游错误 ${response.code}"
                    handleUpstreamError(channel, keyIndex, response.code, body, settings)
                    continue
                }
            } catch (e: Exception) {
                lastError = "请求异常: ${e.message}"
                continue
            }
        }

        logFailure(model, "/v1/embeddings", lastError, maxRetries)
        RelayResult.Error(lastError)
    }

    // === 内部方法 ===

    /** 选择 Key 并返回索引 */
    private suspend fun selectKeyWithIndex(channel: ChannelEntity): Pair<String, Int>? {
        val keys = channel.keyList()
        if (keys.isEmpty()) return null
        val key = routerEngine.selectKey(channel) ?: return null
        val index = keys.indexOf(key)
        return if (index >= 0) key to index else null
    }

    /** 构建 URL，自动处理 /v1 重复 */
    private fun buildUrl(baseUrl: String, path: String): String {
        val base = baseUrl.trimEnd('/')
        val p = if (path.startsWith("/")) path else "/$path"
        if (base.endsWith("/v1") && p.startsWith("/v1/")) {
            return base + p.substring(3)
        }
        return "$base$p"
    }

    /**
     * 判断是否应该重试
     * NEW API 逻辑：不重试的 HTTP 状态码 = {400, 408, 504, 524}
     */
    private fun shouldRetry(statusCode: Int): Boolean {
        return when (statusCode) {
            400, 408, 504, 524 -> false
            else -> true
        }
    }

    /**
     * v0.6.0: AutoBan 逻辑修复
     *
     * NEW API 行为：
     * - 401/403 = Key 无效 → 禁用 Key（AutoBan）
     * - 429 = 限流 → 重试但不禁用
     * - 5xx = 服务器错误 → 重试但不禁用
     * - 其他可重试错误码 → 重试但不禁用
     *
     * 之前的错误：所有 shouldRetry=true 的错误码都禁用 Key，过于激进
     */
    private suspend fun handleUpstreamError(
        channel: ChannelEntity,
        keyIndex: Int,
        statusCode: Int,
        errorBody: String,
        settings: SettingsSnapshot
    ) {
        if (!channel.autoBan) return

        // 仅 401/403 触发 AutoBan（Key 无效或无权限）
        if (statusCode == 401 || statusCode == 403) {
            val reason = "HTTP $statusCode: ${errorBody.take(100)}"
            routerEngine.disableKey(channel, keyIndex, reason)
        }
        // 其他错误码：重试但不禁用
    }

    /**
     * v0.6.0: 异常处理 — 不再禁用 Key
     *
     * 超时/连接异常是网络问题，不是 Key 问题，禁用 Key 会导致恢复后无法使用
     */
    private suspend fun handleException(
        channel: ChannelEntity,
        keyIndex: Int,
        e: Exception,
        settings: SettingsSnapshot
    ) {
        // 异常不禁用 Key，只用于记录日志
    }

    /**
     * v0.6.0: 从上游响应中解析 Token 用量
     * OpenAI 格式: {"usage": {"prompt_tokens": 10, "completion_tokens": 20, "total_tokens": 30}}
     */
    private fun parseTokenUsage(body: String): Triple<Int, Int, Int> {
        return try {
            val json = JSONObject(body)
            val usage = json.optJSONObject("usage")
            if (usage != null) {
                Triple(
                    usage.optInt("prompt_tokens", 0),
                    usage.optInt("completion_tokens", 0),
                    usage.optInt("total_tokens", 0)
                )
            } else {
                Triple(0, 0, 0)
            }
        } catch (e: Exception) {
            Triple(0, 0, 0)
        }
    }

    /** 记录成功日志（含 Token 详情） */
    private suspend fun logSuccess(
        model: String,
        channel: ChannelEntity,
        responseTime: Int,
        endpoint: String,
        statusCode: Int,
        tokenInfo: Triple<Int, Int, Int> = Triple(0, 0, 0)
    ) {
        routeLogDao.insert(
            com.llmrouter.data.model.RouteLogEntity(
                model = model,
                channelName = channel.name,
                success = true,
                responseTime = responseTime,
                retryCount = 0,
                inputTokens = tokenInfo.first,
                outputTokens = tokenInfo.second,
                totalTokens = tokenInfo.third,
                statusCode = statusCode,
                apiEndpoint = endpoint
            )
        )
        channelRepository.updateTestResult(channel.id, responseTime, System.currentTimeMillis())
    }

    /** 记录失败日志 */
    private suspend fun logFailure(model: String, endpoint: String, error: String, retryCount: Int) {
        routeLogDao.insert(
            com.llmrouter.data.model.RouteLogEntity(
                model = model,
                channelName = "—",
                success = false,
                errorMessage = error.take(300),
                retryCount = retryCount,
                apiEndpoint = endpoint,
                statusCode = 502
            )
        )
    }

    /**
     * v0.6.0: 通用透传 — 用于 moderations/images/audio/rerank 等
     * 走标准路由流程，支持模型映射
     */
    suspend fun handleGenericRelay(
        requestBody: String,
        model: String,
        endpoint: String
    ): RelayResult = withContext(Dispatchers.IO) {
        val settings = settingsRepository.getSnapshot()
        val maxRetries = settings.retryTimes
        var lastError = "未知错误"

        for (retry in 0..maxRetries) {
            val channel = routerEngine.selectChannel(model, retry, settings.routeMode)
            if (channel == null) {
                lastError = "没有可用的渠道来处理模型 $model"
                break
            }

            val keyAndIndex = selectKeyWithIndex(channel)
            if (keyAndIndex == null) {
                lastError = "渠道 ${channel.name} 的所有密钥已被禁用"
                continue
            }
            val (apiKey, keyIndex) = keyAndIndex

            val actualModel = channel.applyModelMapping(model)
            val finalBody = if (actualModel != model) {
                try {
                    val json = JSONObject(requestBody)
                    json.put("model", actualModel)
                    json.toString()
                } catch (e: Exception) {
                    requestBody
                }
            } else {
                requestBody
            }

            try {
                val url = buildUrl(channel.baseUrl, endpoint)
                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $apiKey")
                    .header("Content-Type", "application/json")
                    .post(finalBody.toRequestBody(jsonMedia))
                    .build()

                val response = httpClient.newCall(request).execute()
                val body = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    val tokenInfo = parseTokenUsage(body)
                    logSuccess(model, channel, 0, endpoint, 200, tokenInfo)
                    channelRepository.incrementQuota(channel.id)
                    return@withContext RelayResult.Json(body)
                } else {
                    lastError = "上游错误 ${response.code}"
                    handleUpstreamError(channel, keyIndex, response.code, body, settings)
                    continue
                }
            } catch (e: Exception) {
                lastError = "请求异常: ${e.message}"
                continue
            }
        }

        logFailure(model, endpoint, lastError, maxRetries)
        RelayResult.Error(lastError)
    }
}
