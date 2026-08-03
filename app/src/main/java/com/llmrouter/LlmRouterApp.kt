package com.llmrouter

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
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
