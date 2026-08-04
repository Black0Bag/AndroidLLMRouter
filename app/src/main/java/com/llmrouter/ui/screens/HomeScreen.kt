package com.llmrouter.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.llmrouter.R
import com.llmrouter.data.model.ChannelEntity
import com.llmrouter.data.model.RouteLogEntity
import com.llmrouter.ui.MainViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onNavigateToChannels: () -> Unit
) {
    val context = LocalContext.current
    val isRunning by viewModel.isServiceRunning.collectAsState()
    val isStarting by viewModel.isServiceStarting.collectAsState()
    val stats by viewModel.routeStats.collectAsState()
    val channels by viewModel.channels.collectAsState()
    val recentLogs by viewModel.recentLogs.collectAsState()
    val settings by viewModel.settings.collectAsState()

    val apiEndpoint = remember(settings.serverPort) {
        "http://${com.llmrouter.service.RouterService.getLocalIpAddress()}:${settings.serverPort}"
    }

    // OpenAI 兼容 Base URL：客户端必须填 /v1 后缀
    val apiBaseUrl = "$apiEndpoint/v1"
    // 完整 chat completions 端点（用于直接 curl 测试）
    val chatCompletionsUrl = "$apiEndpoint/v1/chat/completions"

    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    fun copyText(label: String, text: String) {
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "$label 已复制", Toast.LENGTH_SHORT).show()
    }

    val enabledChannels = channels.count { it.status == ChannelEntity.STATUS_ENABLED }
    val hasChannels = channels.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // === 启动/停止按钮 ===
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (isRunning)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = when {
                        isStarting -> "正在启动服务…"
                        isRunning -> stringResource(R.string.service_running)
                        else -> stringResource(R.string.service_stopped)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isRunning)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        if (isRunning) viewModel.stopService()
                        else {
                            if (!hasChannels) {
                                Toast.makeText(context, R.string.no_channels, Toast.LENGTH_SHORT).show()
                                onNavigateToChannels()
                            } else {
                                viewModel.startService()
                            }
                        }
                    },
                    enabled = !isStarting,
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (isRunning)
                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    else
                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    AnimatedContent(
                        targetState = isStarting,
                        transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },
                        label = "serviceButton"
                    ) { starting ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            if (starting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "启动中…",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Icon(
                                    if (isRunning) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                                    contentDescription = null
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (isRunning) stringResource(R.string.stop_service)
                                    else stringResource(R.string.start_service),
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // === API 端点（含 /v1） ===
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Base URL（OpenAI 兼容）",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            apiBaseUrl,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "客户端 Base URL 需含 /v1",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = {
                        copyText("Base URL", apiBaseUrl)
                    }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "复制 Base URL")
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Chat 端点（完整 URL）",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            chatCompletionsUrl,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = {
                        copyText("Chat 端点", chatCompletionsUrl)
                    }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "复制 Chat 端点")
                    }
                }

                if (settings.authEnabled && settings.authToken.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Authorization: Bearer ${settings.authToken.take(8)}…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            copyText("Token", settings.authToken)
                        }) {
                            Text("复制 Token")
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // === 统计卡片 ===
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatCard(
                title = stringResource(R.string.total_requests),
                value = stats.totalRequests.toString(),
                modifier = Modifier.weight(1f)
            )
            StatCard(
                title = stringResource(R.string.success_rate),
                value = if (stats.totalRequests > 0)
                    "${(stats.successCount * 100 / stats.totalRequests)}%"
                else "—",
                modifier = Modifier.weight(1f)
            )
            StatCard(
                title = stringResource(R.string.avg_latency),
                value = if (stats.avgResponseTime > 0)
                    "${stats.avgResponseTime.toInt()}ms"
                else "—",
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatCard(
                title = stringResource(R.string.active_channels),
                value = "$enabledChannels / ${channels.size}",
                modifier = Modifier.weight(1f)
            )
            StatCard(
                title = "路由模式",
                value = if (settings.routeMode == "url") "URL 维度" else "模型维度",
                modifier = Modifier.weight(1f)
            )
            StatCard(
                title = "重试次数",
                value = settings.retryTimes.toString(),
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(8.dp))

        // === Token 用量卡片 ===
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatCard(
                title = "输入 Token",
                value = if (stats.totalInputTokens > 0) stats.totalInputTokens.toString() else "—",
                modifier = Modifier.weight(1f)
            )
            StatCard(
                title = "输出 Token",
                value = if (stats.totalOutputTokens > 0) stats.totalOutputTokens.toString() else "—",
                modifier = Modifier.weight(1f)
            )
            StatCard(
                title = "总 Token",
                value = if (stats.totalTokensUsed > 0) stats.totalTokensUsed.toString() else "—",
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(16.dp))

        // === 渠道健康状态 ===
        Text(
            stringResource(R.string.route_status),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))

        if (channels.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.no_channels),
                    modifier = Modifier.padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(channels) { channel ->
                    ChannelStatusCard(channel)
                }
                if (recentLogs.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(8.dp))
                        Text("最近请求", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    }
                    items(recentLogs.take(10)) { log ->
                        LogItem(log)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ChannelStatusCard(channel: ChannelEntity) {
    val statusColor = when (channel.status) {
        ChannelEntity.STATUS_ENABLED -> MaterialTheme.colorScheme.primary
        ChannelEntity.STATUS_MANUAL_DISABLED -> MaterialTheme.colorScheme.outline
        ChannelEntity.STATUS_AUTO_BANNED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }
    val statusText = when (channel.status) {
        ChannelEntity.STATUS_ENABLED -> stringResource(R.string.status_enabled)
        ChannelEntity.STATUS_MANUAL_DISABLED -> stringResource(R.string.status_disabled)
        ChannelEntity.STATUS_AUTO_BANNED -> stringResource(R.string.status_auto_banned)
        else -> "未知"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = statusColor,
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.size(8.dp)
            ) {}
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(channel.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    "优先级 ${channel.priority} · 模型 ${channel.modelList().size} 个 · 密钥 ${channel.keyList().size} 个",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(statusText, style = MaterialTheme.typography.labelSmall, color = statusColor)
                if (channel.responseTime > 0) {
                    Text("${channel.responseTime}ms", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun LogItem(log: RouteLogEntity) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            timeFormat.format(Date(log.timestamp)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            if (log.success) "✓" else "✗",
            style = MaterialTheme.typography.labelSmall,
            color = if (log.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
        Text(
            "${log.channelName} · ${log.model}",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.weight(1f)
        )
        if (log.responseTime > 0) {
            Text(
                "${log.responseTime}ms",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (log.totalTokens > 0) {
            Text(
                "${log.totalTokens} tokens",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
