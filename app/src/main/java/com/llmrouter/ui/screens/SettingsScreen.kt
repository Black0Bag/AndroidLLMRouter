package com.llmrouter.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.llmrouter.R
import com.llmrouter.data.repo.SettingsSnapshot
import com.llmrouter.ui.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val settings by viewModel.settings.collectAsState()

    var port by remember(settings.serverPort) { mutableStateOf(settings.serverPort.toString()) }
    var authEnabled by remember(settings.authEnabled) { mutableStateOf(settings.authEnabled) }
    var authToken by remember(settings.authToken) { mutableStateOf(settings.authToken) }
    var retryTimes by remember(settings.retryTimes) { mutableStateOf(settings.retryTimes.toString()) }
    var healthCheckEnabled by remember(settings.healthCheckEnabled) { mutableStateOf(settings.healthCheckEnabled) }
    var healthCheckInterval by remember(settings.healthCheckInterval) { mutableStateOf(settings.healthCheckInterval.toString()) }
    var autoStart by remember(settings.autoStart) { mutableStateOf(settings.autoStart) }
    var routeMode by remember(settings.routeMode) { mutableStateOf(settings.routeMode) }

    var showSavedMsg by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // === 服务器设置 ===
        Text(
            stringResource(R.string.server_settings),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = port,
            onValueChange = { port = it.filter { c -> c.isDigit() } },
            label = { Text(stringResource(R.string.server_port)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(16.dp))

        // === 鉴权设置 ===
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.auth_enabled), style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = authEnabled, onCheckedChange = { authEnabled = it })
                }
                if (authEnabled) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = authToken,
                        onValueChange = { authToken = it },
                        label = { Text(stringResource(R.string.auth_token)) },
                        placeholder = { Text("sk-router-xxx") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Text(
                        stringResource(R.string.auth_token_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        // === 路由设置 ===
        Text("路由设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))

        Text(stringResource(R.string.route_mode), style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = routeMode == "url",
                onClick = { routeMode = "url" },
                label = { Text(stringResource(R.string.route_mode_url)) }
            )
            FilterChip(
                selected = routeMode == "model",
                onClick = { routeMode = "model" },
                label = { Text(stringResource(R.string.route_mode_model)) }
            )
        }
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = retryTimes,
            onValueChange = { retryTimes = it.filter { c -> c.isDigit() } },
            label = { Text(stringResource(R.string.retry_times)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(16.dp))

        // === 健康检查 ===
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.health_check_enabled), style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = healthCheckEnabled, onCheckedChange = { healthCheckEnabled = it })
                }
                if (healthCheckEnabled) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = healthCheckInterval,
                        onValueChange = { healthCheckInterval = it.filter { c -> c.isDigit() } },
                        label = { Text(stringResource(R.string.health_check_interval)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        // === 开机自启 ===
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.auto_start), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = autoStart, onCheckedChange = { autoStart = it })
            }
        }
        Spacer(Modifier.height(24.dp))

        // === 保存按钮 ===
        Button(
            onClick = {
                viewModel.updateSettings(
                    SettingsSnapshot(
                        serverPort = port.toIntOrNull() ?: 8080,
                        authEnabled = authEnabled,
                        authToken = authToken,
                        retryTimes = retryTimes.toIntOrNull() ?: 3,
                        healthCheckEnabled = healthCheckEnabled,
                        healthCheckInterval = healthCheckInterval.toIntOrNull() ?: 300,
                        autoStart = autoStart,
                        routeMode = routeMode
                    )
                ) { showSavedMsg = true }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.save))
        }

        if (showSavedMsg) {
            Spacer(Modifier.height(8.dp))
            Text(
                "设置已保存",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(8.dp)
            )
        }

        Spacer(Modifier.height(32.dp))

        // 关于
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))
        Text("关于", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("LLM 路由器 v0.1.0", style = MaterialTheme.typography.bodyMedium)
        Text("将 NEW API 核心功能落地为安卓原生 APP", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("支持 URL 维度/模型维度路由、多 Key 轮换、健康检查、自动故障切换", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
