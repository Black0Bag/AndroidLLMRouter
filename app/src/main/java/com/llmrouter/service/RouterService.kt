package com.llmrouter.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.llmrouter.LlmRouterApp
import com.llmrouter.R
import com.llmrouter.data.repo.SettingsSnapshot
import com.llmrouter.health.HealthChecker
import com.llmrouter.relay.RelayHandler
import com.llmrouter.router.RouterEngine
import com.llmrouter.server.HttpApiServer
import com.llmrouter.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.net.NetworkInterface

class RouterService : Service() {

    /**
     * 关键修复：协程异常处理器。
     * 之前的 scope 没有异常处理器，任何协程内部未捕获异常（如端口被占用时
     * HTTP 服务器启动失败）都会传播到主线程导致整个 App 闪退。
     * 现在所有协程异常只记录日志，绝不影响进程存活。
     */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
            android.util.Log.e("RouterService", "协程异常（已捕获，不影响服务）", e)
        }
    )

    private var httpServer: HttpApiServer? = null
    private var healthCheckJob: Job? = null
    private var notificationUpdateJob: Job? = null

    private val app get() = application as LlmRouterApp

    /** 最近一次已知端口，用于避免在主线程 runBlocking 读 DataStore */
    @Volatile
    private var lastKnownPort: Int = 8080

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // v0.6.3: 前台服务启动加固
        // Android 14+ 必须声明 FGS 类型（已在 Manifest 中声明 dataSync）；
        // Android 13+ 未授予通知权限时部分 ROM 直接杀进程。
        // 任何失败都要记录 lastStartError 并继续启动 HTTP 服务器。
        var foregroundOk = false
        try {
            startForeground(NOTIFICATION_ID, buildNotification(0, 0, lastKnownPort))
            foregroundOk = true
            lastStartError = null
        } catch (e: Exception) {
            android.util.Log.e("RouterService", "startForeground 失败", e)
            lastStartError = "startForeground 失败：${e.stackTraceToString()}\n可能原因：未授予通知权限/系统后台限制"
            // 即使 startForeground 失败，仍继续尝试启动 HTTP 服务器
        }

        scope.launch {
            startRouter()
        }

        return START_STICKY
    }

    private suspend fun startRouter() {
        // 整个启动流程包在 try-catch 中：任何一步失败都不能让 App 崩溃
        try {
            app.channelRepository.let { repo ->
                val routerEngine = RouterEngine(repo, app.settingsRepository)
                routerEngine.refreshCache()

                val settings = app.settingsRepository.getSnapshot()
                lastKnownPort = settings.serverPort

                // 创建转发处理器
                val relayHandler = RelayHandler(
                    routerEngine, repo, app.settingsRepository,
                    app.database.routeLogDao()
                )

                // 创建健康检查器
                val healthChecker = HealthChecker(routerEngine, repo, app.settingsRepository)

                // 启动 HTTP 服务器（带重试与兜底）
                var serverStarted = false
                try {
                    httpServer = HttpApiServer(settings.serverPort, relayHandler, app.settingsRepository)
                    httpServer?.start(SOCKET_READ_TIMEOUT)
                    serverStarted = true
                    lastStartError = null
                } catch (e1: Exception) {
                    android.util.Log.w("RouterService", "HTTP 服务器首次启动失败，重试", e1)
                    lastStartError = "首次启动 HTTP 服务器失败：${e1.stackTraceToString()}"
                    try {
                        httpServer?.stop()
                        httpServer = null
                        httpServer = HttpApiServer(settings.serverPort, relayHandler, app.settingsRepository)
                        httpServer?.start(SOCKET_READ_TIMEOUT)
                        serverStarted = true
                        lastStartError = null
                    } catch (e2: Exception) {
                        // 重试仍失败：记录日志，服务继续运行（健康检查/通知仍可用）
                        android.util.Log.e("RouterService", "HTTP 服务器启动失败：端口 ${settings.serverPort} 可能被占用", e2)
                        lastStartError = "HTTP 服务器启动失败（端口 ${settings.serverPort} 可能被占用）：\n${e2.stackTraceToString()}"
                    }
                }

                // 启动健康检查定时任务（内部自带异常保护）
                startHealthCheck(healthChecker, settings)

                // 启动通知更新
                startNotificationUpdate()
            }
        } catch (e: Exception) {
            android.util.Log.e("RouterService", "服务启动流程异常（已捕获）", e)
            lastStartError = "服务启动流程异常：\n${e.stackTraceToString()}"
        }
    }

    private fun startHealthCheck(healthChecker: HealthChecker, settings: SettingsSnapshot) {
        healthCheckJob?.cancel()
        if (!settings.healthCheckEnabled) return

        val intervalMs = (settings.healthCheckInterval.coerceAtLeast(10)) * 1000L
        healthCheckJob = scope.launch {
            while (isActive) {
                delay(intervalMs)
                try {
                    healthChecker.runHealthCheck()
                } catch (e: Exception) {
                    // 健康检查异常不影响服务运行
                    android.util.Log.w("RouterService", "健康检查异常", e)
                }
            }
        }
    }

    private fun startNotificationUpdate() {
        notificationUpdateJob?.cancel()
        notificationUpdateJob = scope.launch {
            try {
                app.database.routeLogDao().getTotalCount().collectLatest { total ->
                    try {
                        val activeChannels = app.channelRepository.getEnabledChannels().size
                        val notification = buildNotification(total, activeChannels, lastKnownPort)
                        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
                        nm.notify(NOTIFICATION_ID, notification)
                    } catch (e: Exception) {
                        android.util.Log.w("RouterService", "通知更新失败", e)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("RouterService", "通知收集失败", e)
            }
        }
    }

    private fun buildNotification(totalRequests: Int, activeChannels: Int, port: Int): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val text = getString(R.string.notification_text, totalRequests, activeChannels)
        val endpoint = "http://${getLocalIpAddress()}:$port"

        return NotificationCompat.Builder(this, LlmRouterApp.CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setSubText(endpoint)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            httpServer?.stop()
        } catch (e: Exception) {
            // ignore
        }
        httpServer = null
        healthCheckJob?.cancel()
        notificationUpdateJob?.cancel()
        scope.cancel()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val SOCKET_READ_TIMEOUT = 10000

        /** v0.6.2: 最近一次启动失败详情（供 UI 诊断弹窗展示/复制），成功启动后清空 */
        @Volatile
        var lastStartError: String? = null

        fun start(context: Context) {
            val intent = Intent(context, RouterService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RouterService::class.java))
        }

        fun getLocalIpAddress(): String {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val iface = interfaces.nextElement()
                    if (iface.isLoopback || !iface.isUp) continue
                    val addresses = iface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val addr = addresses.nextElement()
                        if (!addr.isLoopbackAddress && addr.hostAddress?.contains(':') == false) {
                            return addr.hostAddress
                        }
                    }
                }
            } catch (e: Exception) {
                // ignore
            }
            return "127.0.0.1"
        }
    }
}
