package com.llmrouter.ui

import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.llmrouter.LlmRouterApp
import com.llmrouter.data.model.ChannelEntity
import com.llmrouter.data.repo.SettingsSnapshot
import com.llmrouter.health.ChannelTestResult
import com.llmrouter.health.HealthChecker
import com.llmrouter.router.RouterEngine
import com.llmrouter.service.RouterService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class RouteStats(
    val totalRequests: Int = 0,
    val successCount: Int = 0,
    val avgResponseTime: Float = 0f,
    val activeChannels: Int = 0
)

data class KeyTestResult(
    val success: Boolean,
    val responseTime: Int = 0,
    val errorMessage: String? = null
)

data class FetchModelsResult(
    val success: Boolean,
    val models: List<String> = emptyList(),
    val errorMessage: String? = null
)

class MainViewModel(private val app: LlmRouterApp) : AndroidViewModel(app) {

    private val routerEngine = RouterEngine(app.channelRepository, app.settingsRepository)
    private val healthChecker = HealthChecker(routerEngine, app.channelRepository, app.settingsRepository)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val channels: StateFlow<List<ChannelEntity>> =
        app.channelRepository.getAllChannels()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val settingsGroup1 = combine(
        app.settingsRepository.serverPort,
        app.settingsRepository.authEnabled,
        app.settingsRepository.authToken,
        app.settingsRepository.retryTimes
    ) { port, authEn, authTok, retry ->
        arrayOf(port, authEn, authTok, retry)
    }

    private val settingsGroup2 = combine(
        app.settingsRepository.healthCheckEnabled,
        app.settingsRepository.healthCheckInterval,
        app.settingsRepository.autoStart,
        app.settingsRepository.routeMode
    ) { hcEn, hcInt, autoSt, rMode ->
        arrayOf(hcEn, hcInt, autoSt, rMode)
    }

    val settings: StateFlow<SettingsSnapshot> = combine(settingsGroup1, settingsGroup2) { g1, g2 ->
        SettingsSnapshot(
            serverPort = g1[0] as Int,
            authEnabled = g1[1] as Boolean,
            authToken = g1[2] as String,
            retryTimes = g1[3] as Int,
            healthCheckEnabled = g2[0] as Boolean,
            healthCheckInterval = g2[1] as Int,
            autoStart = g2[2] as Boolean,
            routeMode = g2[3] as String
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsSnapshot())

    val routeStats: StateFlow<RouteStats> = combine(
        app.database.routeLogDao().getTotalCount(),
        app.database.routeLogDao().getSuccessCount(),
        app.database.routeLogDao().getAvgResponseTime()
    ) { total, success, avgTime ->
        RouteStats(
            totalRequests = total,
            successCount = success,
            avgResponseTime = avgTime ?: 0f,
            activeChannels = channels.value.count { it.status == ChannelEntity.STATUS_ENABLED }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RouteStats())

    val recentLogs = app.database.routeLogDao().getRecentLogs(50)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning

    val apiEndpoint: String
        get() = "http://${RouterService.getLocalIpAddress()}:${settings.value.serverPort}"

    // === URL 标准化：自动处理 /v1 后缀 ===
    private fun normalizeBaseUrl(baseUrl: String): String {
        val trimmed = baseUrl.trimEnd('/')
        // 如果 URL 已经以 /v1 结尾，不再拼接 /v1
        return trimmed
    }

    /** 构建完整 API URL：自动处理 baseUrl 中已有的 /v1 */
    private fun buildApiUrl(baseUrl: String, path: String): String {
        val base = baseUrl.trimEnd('/')
        // path 形如 "/v1/models" 或 "/v1/chat/completions"
        val p = if (path.startsWith("/")) path else "/$path"
        // 如果 baseUrl 已以 /v1 结尾，且 path 也以 /v1 开头，去重
        if (base.endsWith("/v1") && p.startsWith("/v1/")) {
            return base + p.substring(3) // 去掉 path 中的 /v1
        }
        return "$base$p"
    }

    // === 拉取模型列表 ===

    fun fetchModels(baseUrl: String, apiKey: String, onResult: (FetchModelsResult) -> Unit) {
        viewModelScope.launch {
            val result = fetchModelsFromServer(baseUrl, apiKey)
            onResult(result)
        }
    }

    private suspend fun fetchModelsFromServer(baseUrl: String, apiKey: String): FetchModelsResult =
        withContext(Dispatchers.IO) {
            try {
                val url = buildApiUrl(baseUrl, "/v1/models")
                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $apiKey")
                    .get()
                    .build()

                val response = httpClient.newCall(request).execute()
                val body = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    val json = JSONObject(body)
                    val data = json.optJSONArray("data")
                    val models = mutableListOf<String>()
                    if (data != null) {
                        for (i in 0 until data.length()) {
                            val id = data.getJSONObject(i).optString("id", "")
                            if (id.isNotEmpty()) models.add(id)
                        }
                    }
                    FetchModelsResult(success = true, models = models.sorted())
                } else {
                    FetchModelsResult(false, errorMessage = "HTTP ${response.code}: ${body.take(200)}")
                }
            } catch (e: Exception) {
                FetchModelsResult(false, errorMessage = "${e.javaClass.simpleName}: ${e.message}")
            }
        }

    // === 检测单个 Key（用 /v1/models GET，不消耗额度） ===

    fun testSingleKey(baseUrl: String, apiKey: String, model: String, onResult: (KeyTestResult) -> Unit) {
        viewModelScope.launch {
            val result = testKeyFromServer(baseUrl, apiKey)
            onResult(result)
        }
    }

    private suspend fun testKeyFromServer(baseUrl: String, apiKey: String): KeyTestResult =
        withContext(Dispatchers.IO) {
            try {
                val url = buildApiUrl(baseUrl, "/v1/models")
                val startTime = System.currentTimeMillis()
                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $apiKey")
                    .get()
                    .build()

                val response = httpClient.newCall(request).execute()
                val elapsed = (System.currentTimeMillis() - startTime).toInt()

                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    response.close()
                    // 验证返回体有效
                    val json = JSONObject(body)
                    val data = json.optJSONArray("data")
                    val modelCount = data?.length() ?: 0
                    KeyTestResult(success = true, responseTime = elapsed)
                } else {
                    val errorBody = response.body?.string() ?: ""
                    response.close()
                    KeyTestResult(false, errorMessage = "HTTP ${response.code}: ${errorBody.take(150)}")
                }
            } catch (e: Exception) {
                KeyTestResult(false, errorMessage = "${e.javaClass.simpleName}: ${e.message}")
            }
        }

    // === 渠道操作 ===

    fun addChannel(
        name: String,
        baseUrl: String,
        apiKeys: List<String>,
        models: List<String>,
        disabledModels: Set<String>,
        priority: Int,
        weight: Int,
        autoBan: Boolean,
        keyMode: String,
        testModel: String,
        onDone: () -> Unit
    ) {
        viewModelScope.launch {
            app.channelRepository.insert(
                ChannelEntity(
                    name = name,
                    baseUrl = baseUrl,
                    apiKeys = apiKeys.joinToString(","),
                    models = models.joinToString(","),
                    disabledModels = disabledModels.joinToString(","),
                    priority = priority,
                    weight = weight,
                    autoBan = autoBan,
                    keyMode = keyMode,
                    testModel = testModel
                )
            )
            routerEngine.refreshCache()
            onDone()
        }
    }

    fun updateChannel(channel: ChannelEntity, onDone: () -> Unit) {
        viewModelScope.launch {
            app.channelRepository.update(channel)
            routerEngine.refreshCache()
            onDone()
        }
    }

    fun deleteChannel(channel: ChannelEntity, onDone: () -> Unit) {
        viewModelScope.launch {
            app.channelRepository.delete(channel)
            routerEngine.refreshCache()
            onDone()
        }
    }

    fun toggleChannelStatus(channel: ChannelEntity) {
        viewModelScope.launch {
            val newStatus = if (channel.status == ChannelEntity.STATUS_ENABLED) {
                ChannelEntity.STATUS_MANUAL_DISABLED
            } else {
                ChannelEntity.STATUS_ENABLED
            }
            app.channelRepository.updateStatus(channel.id, newStatus)
            routerEngine.refreshCache()
        }
    }

    fun testChannel(channel: ChannelEntity, onResult: (ChannelTestResult) -> Unit) {
        viewModelScope.launch {
            val result = healthChecker.testChannel(channel)
            onResult(result)
        }
    }

    // === 服务操作 ===

    fun startService() {
        RouterService.start(app)
        _isServiceRunning.value = true
    }

    fun stopService() {
        RouterService.stop(app)
        _isServiceRunning.value = false
    }

    // === 设置操作 ===

    fun updateSettings(snapshot: SettingsSnapshot, onDone: () -> Unit) {
        viewModelScope.launch {
            app.settingsRepository.setServerPort(snapshot.serverPort)
            app.settingsRepository.setAuthEnabled(snapshot.authEnabled)
            app.settingsRepository.setAuthToken(snapshot.authToken)
            app.settingsRepository.setRetryTimes(snapshot.retryTimes)
            app.settingsRepository.setHealthCheckEnabled(snapshot.healthCheckEnabled)
            app.settingsRepository.setHealthCheckInterval(snapshot.healthCheckInterval)
            app.settingsRepository.setAutoStart(snapshot.autoStart)
            app.settingsRepository.setRouteMode(snapshot.routeMode)
            onDone()
        }
    }
}
