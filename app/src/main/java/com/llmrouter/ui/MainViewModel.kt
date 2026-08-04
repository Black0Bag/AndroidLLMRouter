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
        // 延迟 2 秒后检测端口是否在监听，确保 HTTP 服务器已启动
        viewModelScope.launch {
            kotlinx.coroutines.delay(2000)
            val port = settings.value.serverPort
            val isListening = checkPortListening(port)
            _isServiceRunning.value = isListening
        }
    }

    fun stopService() {
        RouterService.stop(app)
        _isServiceRunning.value = false
    }

    /** 检测本地端口是否在监听 */
    private suspend fun checkPortListening(port: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val socket = java.net.Socket()
            socket.connect(java.net.InetSocketAddress("127.0.0.1", port), 1000)
            socket.close()
            true
        } catch (e: Exception) {
            false
        }
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

    // === 配置导出/导入 ===

    data class ExportResult(val success: Boolean, val json: String = "", val errorMessage: String? = null)

    fun exportConfig(onResult: (ExportResult) -> Unit) {
        viewModelScope.launch {
            try {
                val channels = app.channelRepository.getAllChannelsOnce()
                val settings = app.settingsRepository.getSnapshot()

                val channelsArray = org.json.JSONArray()
                for (ch in channels) {
                    val chJson = org.json.JSONObject()
                    chJson.put("name", ch.name)
                    chJson.put("baseUrl", ch.baseUrl)
                    chJson.put("apiKeys", ch.apiKeys)
                    chJson.put("models", ch.models)
                    chJson.put("disabledModels", ch.disabledModels)
                    chJson.put("priority", ch.priority)
                    chJson.put("weight", ch.weight)
                    chJson.put("autoBan", ch.autoBan)
                    chJson.put("keyMode", ch.keyMode)
                    chJson.put("testModel", ch.testModel)
                    channelsArray.put(chJson)
                }

                val settingsJson = org.json.JSONObject()
                settingsJson.put("serverPort", settings.serverPort)
                settingsJson.put("authEnabled", settings.authEnabled)
                settingsJson.put("authToken", settings.authToken)
                settingsJson.put("retryTimes", settings.retryTimes)
                settingsJson.put("healthCheckEnabled", settings.healthCheckEnabled)
                settingsJson.put("healthCheckInterval", settings.healthCheckInterval)
                settingsJson.put("autoStart", settings.autoStart)
                settingsJson.put("routeMode", settings.routeMode)

                val exportJson = org.json.JSONObject()
                exportJson.put("version", "0.4.0")
                exportJson.put("exportTime", System.currentTimeMillis())
                exportJson.put("channels", channelsArray)
                exportJson.put("settings", settingsJson)

                onResult(ExportResult(success = true, json = exportJson.toString(2)))
            } catch (e: Exception) {
                onResult(ExportResult(success = false, errorMessage = "${e.javaClass.simpleName}: ${e.message}"))
            }
        }
    }

    data class ImportResult(val success: Boolean, val channelCount: Int = 0, val errorMessage: String? = null)

    fun importConfig(jsonString: String, onResult: (ImportResult) -> Unit) {
        viewModelScope.launch {
            try {
                val root = org.json.JSONObject(jsonString)
                val channelsArray = root.optJSONArray("channels")
                    ?: throw IllegalArgumentException("JSON 中缺少 channels 字段")
                val settingsJson = root.optJSONObject("settings")

                val channels = mutableListOf<ChannelEntity>()
                for (i in 0 until channelsArray.length()) {
                    val ch = channelsArray.getJSONObject(i)
                    channels.add(
                        ChannelEntity(
                            name = ch.optString("name", "导入渠道"),
                            baseUrl = ch.optString("baseUrl", ""),
                            apiKeys = ch.optString("apiKeys", ""),
                            models = ch.optString("models", ""),
                            disabledModels = ch.optString("disabledModels", ""),
                            priority = ch.optInt("priority", 0),
                            weight = ch.optInt("weight", 1),
                            autoBan = ch.optBoolean("autoBan", true),
                            keyMode = ch.optString("keyMode", "random"),
                            testModel = ch.optString("testModel", "")
                        )
                    )
                }

                app.channelRepository.importChannels(channels)

                if (settingsJson != null) {
                    val snapshot = SettingsSnapshot(
                        serverPort = settingsJson.optInt("serverPort", 8080),
                        authEnabled = settingsJson.optBoolean("authEnabled", false),
                        authToken = settingsJson.optString("authToken", ""),
                        retryTimes = settingsJson.optInt("retryTimes", 3),
                        healthCheckEnabled = settingsJson.optBoolean("healthCheckEnabled", true),
                        healthCheckInterval = settingsJson.optInt("healthCheckInterval", 300),
                        autoStart = settingsJson.optBoolean("autoStart", false),
                        routeMode = settingsJson.optString("routeMode", "url")
                    )
                    app.settingsRepository.setServerPort(snapshot.serverPort)
                    app.settingsRepository.setAuthEnabled(snapshot.authEnabled)
                    app.settingsRepository.setAuthToken(snapshot.authToken)
                    app.settingsRepository.setRetryTimes(snapshot.retryTimes)
                    app.settingsRepository.setHealthCheckEnabled(snapshot.healthCheckEnabled)
                    app.settingsRepository.setHealthCheckInterval(snapshot.healthCheckInterval)
                    app.settingsRepository.setAutoStart(snapshot.autoStart)
                    app.settingsRepository.setRouteMode(snapshot.routeMode)
                }

                routerEngine.refreshCache()
                onResult(ImportResult(success = true, channelCount = channels.size))
            } catch (e: Exception) {
                onResult(ImportResult(success = false, errorMessage = "${e.javaClass.simpleName}: ${e.message}"))
            }
        }
    }
}
