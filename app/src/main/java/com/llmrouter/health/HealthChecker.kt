package com.llmrouter.health

import com.llmrouter.data.model.ChannelEntity
import com.llmrouter.data.repo.ChannelRepository
import com.llmrouter.data.repo.SettingsRepository
import com.llmrouter.router.RouterEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** 测试结果 */
data class ChannelTestResult(
    val success: Boolean,
    val responseTime: Int = 0,
    val errorMessage: String? = null
)

/**
 * 健康检查器 — 用 /v1/models GET 请求检测（不消耗额度，不会触发 529）
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

    /** 执行一轮完整健康检查 */
    suspend fun runHealthCheck() = withContext(Dispatchers.IO) {
        val settings = settingsRepository.getSnapshot()
        if (!settings.healthCheckEnabled) return@withContext

        val channels = routerEngine.getAllEnabledChannels()
        for (channel in channels) {
            checkChannel(channel)
        }
    }

    /** 检查单个渠道（用 /v1/models GET） */
    private suspend fun checkChannel(channel: ChannelEntity) {
        val key = routerEngine.selectKey(channel) ?: return

        try {
            val startTime = System.currentTimeMillis()
            val url = buildApiUrl(channel.baseUrl, "/v1/models")

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $key")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            val elapsed = (System.currentTimeMillis() - startTime).toInt()

            if (response.isSuccessful) {
                channelRepository.updateTestResult(channel.id, elapsed, System.currentTimeMillis())
                if (channel.status == ChannelEntity.STATUS_AUTO_BANNED) {
                    routerEngine.recoverChannel(channel)
                }
            }
            response.close()
        } catch (e: Exception) {
            // 健康检查失败不直接禁用
        }
    }

    /** 单次测试指定渠道（供 UI 调用，逐个测试所有 Key） */
    suspend fun testChannel(channel: ChannelEntity): ChannelTestResult = withContext(Dispatchers.IO) {
        val keys = channel.keyList()
        if (keys.isEmpty()) return@withContext ChannelTestResult(
            false, errorMessage = "渠道未配置密钥"
        )

        var bestResult: ChannelTestResult? = null

        for ((index, key) in keys.withIndex()) {
            try {
                val startTime = System.currentTimeMillis()
                val url = buildApiUrl(channel.baseUrl, "/v1/models")

                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $key")
                    .get()
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

    /** 构建完整 API URL：自动处理 baseUrl 中已有的 /v1 */
    private fun buildApiUrl(baseUrl: String, path: String): String {
        val base = baseUrl.trimEnd('/')
        val p = if (path.startsWith("/")) path else "/$path"
        if (base.endsWith("/v1") && p.startsWith("/v1/")) {
            return base + p.substring(3)
        }
        return "$base$p"
    }
}
