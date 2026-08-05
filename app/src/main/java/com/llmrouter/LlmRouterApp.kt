package com.llmrouter

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.llmrouter.data.db.AppDatabase
import com.llmrouter.data.repo.ChannelRepository
import com.llmrouter.data.repo.SettingsRepository
import com.llmrouter.service.RouterService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class LlmRouterApp : Application() {

    val database by lazy { AppDatabase.getInstance(this) }
    val channelRepository by lazy { ChannelRepository(database.channelDao()) }
    val settingsRepository by lazy { SettingsRepository(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // v0.7.1: 全局日志系统必须在所有其他操作之前初始化
        AppLogger.init(this)
        AppLogger.i("LlmRouterApp", "onCreate 开始, version=0.7.2")

        createNotificationChannel()
        AppLogger.i("LlmRouterApp", "通知渠道已创建")

        // v0.7.2: autoStart 内部自己起后台线程，直接调用即可，不碰主线程
        maybeAutoStartService()
    }

    /**
     * v0.6.3: 检查通知权限（Android 13+ 运行时权限）
     * 启动服务前调用，引导用户授权
     */
    fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else true // Android 12- 无需运行时授权
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "router_service"
        lateinit var instance: LlmRouterApp
            private set
    }

    /**
     * v0.7.2: autoStart 在后台线程执行（绝不在主线程 runBlocking）。
     * 教训：v0.7.1 用 Handler.post 仍跑在主线程，DataStore 锁挂起会卡死 UI 线程，
     * 表现为"首帧可见但底部导航永不出现"。
     * Android 15+ 禁止 BOOT_COMPLETED 启动 dataSync FGS，因此改为"打开 App 时自动续跑"。
     * 日志全程记录到 /sdcard/Android/data/com.llmrouter/files/app_log.txt（adb 可读）。
     */
    private fun maybeAutoStartService() {
        AppLogger.i("LlmRouterApp", "autoStart 检查已派发到后台线程 …")
        val t = Thread {
            try {
                AppLogger.i("LlmRouterApp", "autoStart 线程开始, name=${Thread.currentThread().name}")
                // DataStore 可能挂起（本机已实证），10s 超时兜底，绝不无限等待
                val snap = runBlocking {
                    withTimeout(10_000) { settingsRepository.getSnapshot() }
                }
                AppLogger.i("LlmRouterApp", "autoStart 设置值=${snap.autoStart}, isServerRunning=${RouterService.isServerRunning}")
                if (snap.autoStart && !RouterService.isServerRunning) {
                    AppLogger.i("LlmRouterApp", "autoStart=ON，启动 RouterService")
                    RouterService.start(this)
                } else {
                    AppLogger.i("LlmRouterApp", "autoStart=OFF 或服务已运行，跳过")
                }
            } catch (e: Exception) {
                AppLogger.e("LlmRouterApp", "autoStart 检查异常（已捕获，不影响 UI）", e)
            }
        }
        t.name = "AutoStart"
        t.start()
    }
}
