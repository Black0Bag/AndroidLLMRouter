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
 * 核心流程：
 * 1. 解析 model → 路由引擎选渠道 → 选 Key
 * 2. OkHttp 转发到上游
 * 3. 成功透传；失败判断是否重试（shouldRetry）
 * 4. 重试时用递增 retry 选更低优先级渠道（Fallback）
 * 5. 命中错误码时 AutoBan（禁用 Key 或渠道）
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
     * @param requestBody 请求体 JSON 字符串
     * @param model 请求的模型名
     * @param stream 是否流式
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

            try {
                val startTime = System.currentTimeMillis()
                val url = buildUrl(channel.baseUrl, "/v1/chat/completions")

                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $apiKey")
                    .header("Content-Type", "application/json")
                    .post(requestBody.toRequestBody(jsonMedia))
                    .build()

                if (stream) {
                    val response = httpClient.newCall(request).execute()
                    val elapsed = (System.currentTimeMillis() - startTime).toInt()

                    if (response.isSuccessful && response.body != null) {
                        logSuccess(model, channel, elapsed)
                        channelRepository.incrementQuota(channel.id)
                        return@withContext RelayResult.Stream(response.body!!.byteStream())
                    } else {
                        val errorBody = response.body?.string() ?: ""
                        val code = response.code
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
                        logSuccess(model, channel, elapsed)
                        channelRepository.incrementQuota(channel.id)
                        return@withContext RelayResult.Json(body)
                    } else {
                        lastError = "上游错误 ${response.code}: ${body.take(200)}"
                        handleUpstreamError(channel, keyIndex, response.code, body, settings)
                        continue
                    }
                }
            } catch (e: java.net.SocketTimeoutException) {
                lastError = "请求超时: ${e.message}"
                handleException(channel, keyIndex, e, settings)
                continue
            } catch (e: java.net.ConnectException) {
                lastError = "连接失败: ${e.message}"
                handleException(channel, keyIndex, e, settings)
                continue
            } catch (e: Exception) {
                lastError = "请求异常: ${e.message ?: e.javaClass.simpleName}"
                handleException(channel, keyIndex, e, settings)
                continue
            }
        }

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

            try {
                val url = buildUrl(channel.baseUrl, "/v1/embeddings")
                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $apiKey")
                    .header("Content-Type", "application/json")
                    .post(requestBody.toRequestBody(jsonMedia))
                    .build()

                val response = httpClient.newCall(request).execute()
                val body = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    channelRepository.incrementQuota(channel.id)
                    return@withContext RelayResult.Json(body)
                } else {
                    lastError = "上游错误 ${response.code}"
                    handleUpstreamError(channel, keyIndex, response.code, body, settings)
                    continue
                }
            } catch (e: Exception) {
                lastError = "请求异常: ${e.message}"
                handleException(channel, keyIndex, e, settings)
                continue
            }
        }

        RelayResult.Error(lastError)
    }

    // === 内部方法 ===

    private suspend fun selectKeyWithIndex(channel: ChannelEntity): Pair<String, Int>? {
        val keys = channel.keyList()
        if (keys.isEmpty()) return null

        // 委托给路由引擎的 selectKey，但需要追踪索引
        val key = routerEngine.selectKey(channel) ?: return null
        val keyIndex = keys.indexOf(key)
        return Pair(key, keyIndex)
    }

    private fun buildUrl(baseUrl: String, path: String): String {
        val base = baseUrl.trimEnd('/')
        val p = if (path.startsWith("/")) path else "/$path"
        // 去重 /v1 后缀（与 ViewModel.buildApiUrl 逻辑一致）
        if (base.endsWith("/v1") && p.startsWith("/v1/")) {
            return base + p.substring(3)
        }
        return "$base$p"
    }

    /** 判断是否应该重试 */
    private fun shouldRetry(statusCode: Int): Boolean {
        // 参照 NEW API：1xx/3xx/401-407/409-499/500-503/505-523/525-599 重试
        // 504/524/400/408 不重试
        return when (statusCode) {
            400, 408, 504, 524 -> false
            in 100..199, in 300..399 -> true
            in 401..407, in 409..499 -> true
            in 500..503, in 505..523, in 525..599 -> true
            else -> true
        }
    }

    /** 处理上游错误 — 判断是否 AutoBan */
    private suspend fun handleUpstreamError(
        channel: ChannelEntity,
        keyIndex: Int,
        statusCode: Int,
        errorBody: String,
        settings: SettingsSnapshot
    ) {
        if (!shouldRetry(statusCode)) return
        if (!channel.autoBan) return

        // 命中错误码即禁用该 Key
        val reason = "HTTP $statusCode: ${errorBody.take(100)}"
        routerEngine.disableKey(channel, keyIndex, reason)
    }

    /** 处理异常 — 超时/连接失败等 */
    private suspend fun handleException(
        channel: ChannelEntity,
        keyIndex: Int,
        e: Exception,
        settings: SettingsSnapshot
    ) {
        if (!channel.autoBan) return
        val reason = "${e.javaClass.simpleName}: ${e.message?.take(100)}"
        routerEngine.disableKey(channel, keyIndex, reason)
    }

    /** 记录成功日志 */
    private suspend fun logSuccess(model: String, channel: ChannelEntity, responseTime: Int) {
        routeLogDao.insert(
            com.llmrouter.data.model.RouteLogEntity(
                model = model,
                channelName = channel.name,
                success = true,
                responseTime = responseTime,
                retryCount = 0
            )
        )
        // 更新渠道响应时间
        channelRepository.updateTestResult(channel.id, responseTime, System.currentTimeMillis())
    }
}
