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
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class RouteStats(
    val totalRequests: Int = 0,
    val successCount: Int = 0,
    val avgResponseTime: Float = 0f,
    val activeChannels: Int = 0
)

class MainViewModel(private val app: LlmRouterApp) : AndroidViewModel(app) {

    private val routerEngine = RouterEngine(app.channelRepository, app.settingsRepository)
    private val healthChecker = HealthChecker(routerEngine, app.channelRepository, app.settingsRepository)

    // 渠道列表
    val channels: StateFlow<List<ChannelEntity>> =
        app.channelRepository.getAllChannels()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 设置（Kotlin combine 最多 5 参数，用嵌套方式处理 8 个 Flow）
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

    // 路由统计
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

    // 最近日志
    val recentLogs = app.database.routeLogDao().getRecentLogs(50)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 服务运行状态
    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning

    // API 端点
    val apiEndpoint: String
        get() = "http://${RouterService.getLocalIpAddress()}:${settings.value.serverPort}"

    // === 渠道操作 ===

    fun addChannel(
        name: String,
        baseUrl: String,
        apiKeys: String,
        models: String,
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
                    apiKeys = apiKeys,
                    models = models,
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
