package com.llmrouter.ui

import android.app.Application
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

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val app = app as LlmRouterApp
    private val routerEngine = RouterEngine(app.channelRepository, app.settingsRepository)
    private val healthChecker = HealthChecker(routerEngine, app.channelRepository, app.settingsRepository)

    // 渠道列表
    val channels: StateFlow<List<ChannelEntity>> =
        app.channelRepository.getAllChannels()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 设置
    val settings: StateFlow<SettingsSnapshot> = combine(
        app.settingsRepository.serverPort,
        app.settingsRepository.authEnabled,
        app.settingsRepository.authToken,
        app.settingsRepository.retryTimes,
        app.settingsRepository.healthCheckEnabled,
        app.settingsRepository.healthCheckInterval,
        app.settingsRepository.autoStart,
        app.settingsRepository.routeMode
    ) { values ->
        SettingsSnapshot(
            serverPort = values[0] as Int,
            authEnabled = values[1] as Boolean,
            authToken = values[2] as String,
            retryTimes = values[3] as Int,
            healthCheckEnabled = values[4] as Boolean,
            healthCheckInterval = values[5] as Int,
            autoStart = values[6] as Boolean,
            routeMode = values[7] as String
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
        RouterService.start(getApplication())
        _isServiceRunning.value = true
    }

    fun stopService() {
        RouterService.stop(getApplication())
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
