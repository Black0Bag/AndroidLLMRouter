package com.llmrouter.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.llmrouter.AppLogger
import com.llmrouter.LlmRouterApp
import com.llmrouter.R
import com.llmrouter.data.repo.SettingsSnapshot
import com.llmrouter.health.HealthChecker
import com.llmrouter.relay.RelayHandler
import com.llmrouter.router.RouterEngine
import com.llmrouter.server.HttpApiServer
import com.llmrouter.ui.MainActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.NetworkInterface

/**
 * v0.7.0 彻底重构 — 根治"服务启动后端口永不监听"问题
 *
 * 上一版根因（v0.6.3，设备实测定案）：
 *   onStartCommand 确实被调用、startForeground 成功；但 startRouter() 走
 *   CoroutineScope(Dispatchers.Default).launch { suspend 链 }，其中
 *   SettingsRepository.getSnapshot() → DataStore(lib 1.0.0).data.first()
 *   在 UI 层 StateFlow WhileSubscribed(5000) 反复订阅/取消后触发 DataStore
 *   已知缺陷（读取被取消时读写锁未释放），first() 永久挂起且不抛异常
 *   → lastStartError 永远为 null、端口永不监听、logcat 无任何输出。
 *
 * 本版改动：
 *   1. 启动不再依赖协程派发 —— 直接在专用线程 Thread("RouterStartup") 同步执行，
 *      每步写入 lastStartStep 并打 logcat，任何失败立即落 lastStartError。
 *   2. DataStore 读取套 15s 硬超时 withTimeout 兜底：超时/异常则退回默认端口 8080，
 *      绝不因为设置读取挂起而阻塞 HTTP 服务器启动。
 *   3. 健康检查与通知更新从协程改为 HandlerThread + Handler.postDelayed，
 *      与协程 scope 生命周期彻底解耦，服务运行期稳定心跳。
 *   4. 新增 isServerRunning 静态状态，UI 可直接查询（不再只靠端口探测猜测）。
 */
class RouterService : Service() {

    private var httpServer: HttpApiServer? = null

    private var healthThread: HandlerThread? = null
    private var notificationThread: HandlerThread? = null
    private var notifyHandler: Handler? = null

    private val app get() = application as LlmRouterApp

    /** 最近一次已知端口，用于 startForeground 通知展示（不阻塞读取） */
    @Volatile
    private var lastKnownPort: Int = 8080

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLogger.i(TAG, "onStartCommand called, startId=$startId")

        // 前台服务声明（Manifest 已声明 dataSync 类型 + FOREGROUND_SERVICE_DATA_SYNC 权限）
        try {
            startForeground(NOTIFICATION_ID, buildNotification(0, 0, lastKnownPort))
            lastStartError = null
            AppLogger.i(TAG, "startForeground OK")
        } catch (e: Exception) {
            AppLogger.e(TAG, "startForeground 失败", e)
            lastStartError = "startForeground 失败：\n${e.stackTraceToString()}\n可能原因：未授予通知权限/系统后台限制"
            // startForeground 失败仍继续尝试启动 HTTP 服务器（文件描述符级服务不依赖通知）
        }

        // v0.7.0 核心：专用线程同步启动。NanoHTTPD.start() 非阻塞（内部自有线程），
        // 该线程只在启动初始化阶段存在，服务器起来后自动退出。
        val t = startupThread
        if (t == null || !t.isAlive) {
            startupThread = Thread({ startRouterBlocking() }, "RouterStartup").also { it.start() }
        }
        return START_STICKY
    }

    /**
     * 同步启动 HTTP 服务器（在专用线程上执行）。
     * 全路径 try-catch + 步骤打点：要么几秒内监听端口，要么留下明确 lastStartError。
     */
    private fun startRouterBlocking() {
        lastStartError = null
        isServerRunning = false
        try {
            step("1/6 初始化数据库与渠道仓库")
            val repo = app.channelRepository
            val routerEngine = RouterEngine(repo, app.settingsRepository)

            step("2/6 刷新渠道缓存")
            runBlocking { routerEngine.refreshCache() }

            step("3/6 读取服务设置（DataStore，15s 硬超时兜底）")
            val settings = getSettingsBlocking()
            lastKnownPort = settings.serverPort

            step("4/6 构建转发处理器与健康检查器")
            val relayHandler = RelayHandler(
                routerEngine, repo, app.settingsRepository,
                app.database.routeLogDao()
            )
            val healthChecker = HealthChecker(routerEngine, repo, app.settingsRepository)

            step("5/6 启动 HTTP 服务器 (port=${settings.serverPort})")
            var started = false
            var server: HttpApiServer? = null
            try {
                server = HttpApiServer(settings.serverPort, relayHandler, app.settingsRepository)
                server.start(SOCKET_READ_TIMEOUT)
                started = true
            } catch (e1: Exception) {
                AppLogger.w(TAG, "HTTP 首次启动失败，重试一次", e1)
                lastStartError = "HTTP 首次启动失败：\n${e1.stackTraceToString()}"
                try {
                    server?.stop()
                    server = HttpApiServer(settings.serverPort, relayHandler, app.settingsRepository)
                    server.start(SOCKET_READ_TIMEOUT)
                    started = true
                } catch (e2: Exception) {
                    AppLogger.e(TAG, "HTTP 重试仍失败（端口 ${settings.serverPort} 可能被占用）", e2)
                    lastStartError = "HTTP 服务器启动失败（端口 ${settings.serverPort} 可能被占用）：\n${e2.stackTraceToString()}"
                }
            }
            if (started && server != null) {
                httpServer = server
                isServerRunning = true
                lastStartError = null
                step("6/6 启动完成，端口 ${settings.serverPort} 已监听")
            }

            // 心跳与通知更新（HandlerThread 驱动，独立于协程）
            startHealthCheck(healthChecker, settings)
            startNotificationUpdate()
        } catch (e: Exception) {
            AppLogger.e(TAG, "服务启动流程异常（已捕获）", e)
            lastStartError = "服务启动流程异常：\n${e.stackTraceToString()}"
        }
    }

    /**
     * 读取设置：DataStore 1.0.0 存在"读取协程被取消后 first() 永久挂起"缺陷，
     * 这里用 15s 硬超时兜底 —— 无论 DataStore 发生什么，HTTP 服务器都能用默认端口启动。
     */
    private fun getSettingsBlocking(): SettingsSnapshot {
        return try {
            runBlocking {
                withTimeout(15_000) {
                    app.settingsRepository.getSnapshot()
                }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "读取设置超时/失败，退回默认端口 8080 继续启动", e)
            lastStartError = "读取设置超时（已兜底默认端口 8080）：\n${e.stackTraceToString()}"
            SettingsSnapshot()
        }
    }

    private fun step(s: String) {
        lastStartStep = s
        AppLogger.i(TAG, s)
    }

    // === 健康检查：HandlerThread 周期任务（原协程 while(isActive)+delay 迁移） ===

    private fun startHealthCheck(healthChecker: HealthChecker, settings: SettingsSnapshot) {
        healthThread?.quitSafely()
        healthThread = null
        if (!settings.healthCheckEnabled) return

        val intervalMs = settings.healthCheckInterval.coerceAtLeast(10) * 1000L
        healthThread = HandlerThread("HealthCheck").also { it.start() }
        val handler = Handler(healthThread!!.looper)
        val task = object : Runnable {
            override fun run() {
                try {
                    runBlocking { healthChecker.runHealthCheck() }
                } catch (e: Exception) {
                    AppLogger.w(TAG, "健康检查异常（不影响服务）", e)
                }
                handler.postDelayed(this, intervalMs)
            }
        }
        handler.postDelayed(task, intervalMs)
    }

    // === 通知更新：HandlerThread 周期任务（原协程 collectLatest 迁移） ===

    private fun startNotificationUpdate() {
        notificationThread?.quitSafely()
        notificationThread = null

        notificationThread = HandlerThread("NotificationUpdater").also { it.start() }
        notifyHandler = Handler(notificationThread!!.looper)
        val task = object : Runnable {
            override fun run() {
                try {
                    val total = runBlocking { app.database.routeLogDao().getTotalCount().first() }
                    val activeChannels = runBlocking { app.channelRepository.getEnabledChannels() }.size
                    val notification = buildNotification(total, activeChannels, lastKnownPort)
                    val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
                    nm.notify(NOTIFICATION_ID, notification)
                } catch (e: Exception) {
                    AppLogger.w(TAG, "通知更新失败（不影响服务）", e)
                }
                notifyHandler?.postDelayed(this, 5_000)
            }
        }
        notifyHandler?.post(task)
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
        isServerRunning = false
        healthThread?.quitSafely()
        healthThread = null
        notificationThread?.quitSafely()
        notificationThread = null
        notifyHandler = null
    }

    companion object {
        private const val TAG = "RouterService"
        private const val NOTIFICATION_ID = 1001
        private const val SOCKET_READ_TIMEOUT = 10000

        /** v0.6.2+ : 最近一次启动失败详情（UI 诊断弹窗展示），成功启动后清空 */
        @Volatile
        var lastStartError: String? = null

        /** v0.7.0: 启动流程当前步骤（UI 诊断可展示卡在哪一步） */
        @Volatile
        var lastStartStep: String = "未启动"

        /** v0.7.0: HTTP 服务器是否正在监听（UI 直接查询，不再靠端口探测猜测） */
        @Volatile
        var isServerRunning: Boolean = false

        /** v0.7.0: 启动线程引用（防止重复启动） */
        @Volatile
        private var startupThread: Thread? = null

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