package com.llmrouter.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.llmrouter.LlmRouterApp
import kotlinx.coroutines.runBlocking

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val app = context.applicationContext as LlmRouterApp
            val autoStart = runBlocking { app.settingsRepository.getSnapshot().autoStart }
            if (autoStart) {
                RouterService.start(context)
            }
        }
    }
}
