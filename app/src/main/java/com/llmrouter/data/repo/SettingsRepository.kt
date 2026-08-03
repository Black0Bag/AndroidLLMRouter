package com.llmrouter.data.repo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val SERVER_PORT = intPreferencesKey("server_port")
        val AUTH_ENABLED = booleanPreferencesKey("auth_enabled")
        val AUTH_TOKEN = stringPreferencesKey("auth_token")
        val RETRY_TIMES = intPreferencesKey("retry_times")
        val HEALTH_CHECK_ENABLED = booleanPreferencesKey("health_check_enabled")
        val HEALTH_CHECK_INTERVAL = intPreferencesKey("health_check_interval")
        val AUTO_START = booleanPreferencesKey("auto_start")
        val ROUTE_MODE = stringPreferencesKey("route_mode") // "url" or "model"
    }

    val serverPort: Flow<Int> = context.dataStore.data.map { it[Keys.SERVER_PORT] ?: 8080 }
    val authEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTH_ENABLED] ?: false }
    val authToken: Flow<String> = context.dataStore.data.map { it[Keys.AUTH_TOKEN] ?: "" }
    val retryTimes: Flow<Int> = context.dataStore.data.map { it[Keys.RETRY_TIMES] ?: 3 }
    val healthCheckEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.HEALTH_CHECK_ENABLED] ?: true }
    val healthCheckInterval: Flow<Int> = context.dataStore.data.map { it[Keys.HEALTH_CHECK_INTERVAL] ?: 300 }
    val autoStart: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTO_START] ?: false }
    val routeMode: Flow<String> = context.dataStore.data.map { it[Keys.ROUTE_MODE] ?: "url" }

    suspend fun setServerPort(port: Int) {
        context.dataStore.edit { it[Keys.SERVER_PORT] = port }
    }

    suspend fun setAuthEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTH_ENABLED] = enabled }
    }

    suspend fun setAuthToken(token: String) {
        context.dataStore.edit { it[Keys.AUTH_TOKEN] = token }
    }

    suspend fun setRetryTimes(times: Int) {
        context.dataStore.edit { it[Keys.RETRY_TIMES] = times }
    }

    suspend fun setHealthCheckEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.HEALTH_CHECK_ENABLED] = enabled }
    }

    suspend fun setHealthCheckInterval(seconds: Int) {
        context.dataStore.edit { it[Keys.HEALTH_CHECK_INTERVAL] = seconds }
    }

    suspend fun setAutoStart(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_START] = enabled }
    }

    suspend fun setRouteMode(mode: String) {
        context.dataStore.edit { it[Keys.ROUTE_MODE] = mode }
    }

    /** 同步获取当前设置快照 */
    suspend fun getSnapshot(): SettingsSnapshot {
        val prefs = context.dataStore.data
        var snapshot = SettingsSnapshot()
        prefs.collect { p ->
            snapshot = SettingsSnapshot(
                serverPort = p[Keys.SERVER_PORT] ?: 8080,
                authEnabled = p[Keys.AUTH_ENABLED] ?: false,
                authToken = p[Keys.AUTH_TOKEN] ?: "",
                retryTimes = p[Keys.RETRY_TIMES] ?: 3,
                healthCheckEnabled = p[Keys.HEALTH_CHECK_ENABLED] ?: true,
                healthCheckInterval = p[Keys.HEALTH_CHECK_INTERVAL] ?: 300,
                autoStart = p[Keys.AUTO_START] ?: false,
                routeMode = p[Keys.ROUTE_MODE] ?: "url"
            )
            return@collect
        }
        return snapshot
    }
}

data class SettingsSnapshot(
    val serverPort: Int = 8080,
    val authEnabled: Boolean = false,
    val authToken: String = "",
    val retryTimes: Int = 3,
    val healthCheckEnabled: Boolean = true,
    val healthCheckInterval: Int = 300,
    val autoStart: Boolean = false,
    val routeMode: String = "url"
)
