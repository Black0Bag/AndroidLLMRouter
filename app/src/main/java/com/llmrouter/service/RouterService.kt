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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var httpServer: HttpApiServer? = null
    private var healthCheckJob: Job? = null
    private var notificationUpdateJob: Job? = null

    private val app get() = application as LlmRouterApp

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification(0, 0))

        scope.launch {
            startRouter()
        }

        return START_STICKY
    }

    private suspend fun startRouter() {
        // 刷新路由引擎缓存
        app.channelRepository.let { repo ->
            val routerEngine = RouterEngine(repo, app.settingsRepository)
            routerEngine.refreshCache()

            val settings = app.settingsRepository.getSnapshot()

            // 创建转发处理器
            val relayHandler = RelayHandler(
                routerEngine, repo, app.settingsRepository,
                app.database.routeLogDao()
            )

            // 创建健康检查器
            val healthChecker = HealthChecker(routerEngine, repo, app.settingsRepository)

            // 启动 HTTP 服务器
            try {
                httpServer = HttpApiServer(settings.serverPort, relayHandler, app.settingsRepository)
                httpServer?.start(SOCKET_READ_TIMEOUT)
            } catch (e: Exception) {
                // 端口可能被占用，尝试重试
                httpServer?.stop()
                httpServer = HttpApiServer(settings.serverPort, relayHandler, app.settingsRepository)
                httpServer?.start(SOCKET_READ_TIMEOUT)
            }

            // 启动健康检查定时任务
            startHealthCheck(healthChecker, settings)

            // 启动通知更新
            startNotificationUpdate()
        }
    }

    private fun startHealthCheck(healthChecker: HealthChecker, settings: SettingsSnapshot) {
        healthCheckJob?.cancel()
        if (!settings.healthCheckEnabled) return

        val intervalMs = settings.healthCheckInterval * 1000L
        healthCheckJob = scope.launch {
            while (isActive) {
                delay(intervalMs)
                try {
                    healthChecker.runHealthCheck()
                } catch (e: Exception) {
                    // 健康检查异常不影响服务运行
                }
            }
        }
    }

    private fun startNotificationUpdate() {
        notificationUpdateJob?.cancel()
        notificationUpdateJob = scope.launch {
            app.database.routeLogDao().getTotalCount().collectLatest { total ->
                val activeChannels = app.channelRepository.getEnabledChannels().size
                val notification = buildNotification(total, activeChannels)
                val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
                nm.notify(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun buildNotification(totalRequests: Int, activeChannels: Int): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val text = getString(R.string.notification_text, totalRequests, activeChannels)
        val endpoint = "http://${getLocalIpAddress()}:${app.settingsRepository.let { runBlocking { it.getSnapshot().serverPort } }}"

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
        httpServer?.stop()
        httpServer = null
        healthCheckJob?.cancel()
        notificationUpdateJob?.cancel()
        scope.cancel()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val SOCKET_READ_TIMEOUT = 10000

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
