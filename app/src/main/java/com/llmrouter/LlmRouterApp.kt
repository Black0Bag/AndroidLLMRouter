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

class LlmRouterApp : Application() {

    val database by lazy { AppDatabase.getInstance(this) }
    val channelRepository by lazy { ChannelRepository(database.channelDao()) }
    val settingsRepository by lazy { SettingsRepository(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
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
}
