package com.llmrouter.health

import com.llmrouter.data.model.ChannelEntity
import com.llmrouter.data.repo.ChannelRepository
import com.llmrouter.data.repo.SettingsRepository
import com.llmrouter.router.RouterEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 测试结果 */
data class ChannelTestResult(
    val success: Boolean,
    val responseTime: Int = 0,
    val errorMessage: String? = null
)

/**
 * 健康检查器 — 定时探测各渠道/Key 可用性与响应延迟
 *
 * 功能：
 * 1. 定时对所有启用渠道发送测试请求，测量响应延迟
 * 2. 测试成功 → 更新 ResponseTime，恢复被 AutoBan 的渠道/Key
 * 3. 测试失败 → 记录但不直接禁用（AutoBan 由实际请求失败触发）
 * 4. 提供单次渠道测试接口供 UI 调用
 */
class HealthChecker(
    private val routerEngine: RouterEngine,
    private val channelRepository: ChannelRepository,
    private val settingsRepository: SettingsRepository
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /** 执行一轮完整健康检查 */
    suspend fun runHealthCheck() = withContext(Dispatchers.IO) {
        val settings = settingsRepository.getSnapshot()
        if (!settings.healthCheckEnabled) return@withContext

        val channels = routerEngine.getAllEnabledChannels()
        for (channel in channels) {
            checkChannel(channel)
        }
    }

    /** 检查单个渠道 */
    private suspend fun checkChannel(channel: ChannelEntity) {
        val testModel = channel.testModel.ifBlank {
            channel.modelList().firstOrNull() ?: return
        }
        val key = routerEngine.selectKey(channel) ?: return

        try {
            val startTime = System.currentTimeMillis()
            val testBody = buildTestRequest(testModel)
            val url = buildUrl(channel.baseUrl, "/v1/chat/completions")

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $key")
                .header("Content-Type", "application/json")
                .post(testBody.toRequestBody(jsonMedia))
                .build()

            val response = httpClient.newCall(request).execute()
            val elapsed = (System.currentTimeMillis() - startTime).toInt()

            if (response.isSuccessful) {
                channelRepository.updateTestResult(channel.id, elapsed, System.currentTimeMillis())
                // 如果渠道之前被 AutoBan，测试成功则恢复
                if (channel.status == ChannelEntity.STATUS_AUTO_BANNED) {
                    routerEngine.recoverChannel(channel)
                }
            }
            response.close()
        } catch (e: Exception) {
            // 健康检查失败不直接禁用，仅记录
        }
    }

    /** 单次测试指定渠道（供 UI 调用） */
    suspend fun testChannel(channel: ChannelEntity): ChannelTestResult = withContext(Dispatchers.IO) {
        val testModel = channel.testModel.ifBlank {
            channel.modelList().firstOrNull() ?: return@withContext ChannelTestResult(
                false, errorMessage = "渠道未配置模型"
            )
        }

        // 逐个测试所有 Key
        val keys = channel.keyList()
        if (keys.isEmpty()) return@withContext ChannelTestResult(
            false, errorMessage = "渠道未配置密钥"
        )

        var bestResult: ChannelTestResult? = null

        for ((index, key) in keys.withIndex()) {
            try {
                val startTime = System.currentTimeMillis()
                val testBody = buildTestRequest(testModel)
                val url = buildUrl(channel.baseUrl, "/v1/chat/completions")

                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $key")
                    .header("Content-Type", "application/json")
                    .post(testBody.toRequestBody(jsonMedia))
                    .build()

                val response = httpClient.newCall(request).execute()
                val elapsed = (System.currentTimeMillis() - startTime).toInt()

                if (response.isSuccessful) {
                    channelRepository.updateTestResult(channel.id, elapsed, System.currentTimeMillis())
                    response.close()
                    return@withContext ChannelTestResult(success = true, responseTime = elapsed)
                } else {
                    val errorBody = response.body?.string() ?: ""
                    response.close()
                    if (bestResult == null) {
                        bestResult = ChannelTestResult(
                            success = false,
                            errorMessage = "Key #${index + 1}: HTTP ${response.code} - ${errorBody.take(100)}"
                        )
                    }
                }
            } catch (e: Exception) {
                if (bestResult == null) {
                    bestResult = ChannelTestResult(
                        success = false,
                        errorMessage = "Key #${index + 1}: ${e.message}"
                    )
                }
            }
        }

        bestResult ?: ChannelTestResult(false, errorMessage = "所有密钥测试失败")
    }

    private fun buildTestRequest(model: String): String {
        return JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", "hi")
                })
            })
            put("max_tokens", 1)
            put("stream", false)
        }.toString()
    }

    private fun buildUrl(baseUrl: String, path: String): String {
        val base = baseUrl.trimEnd('/')
        val p = if (path.startsWith("/")) path else "/$path"
        return "$base$p"
    }
}
