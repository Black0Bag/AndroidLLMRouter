package com.llmrouter.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.llmrouter.R
import com.llmrouter.data.model.ChannelEntity
import com.llmrouter.health.ChannelTestResult
import com.llmrouter.ui.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelListScreen(
    viewModel: MainViewModel,
    onAddChannel: () -> Unit,
    onEditChannel: (Long) -> Unit
) {
    val channels by viewModel.channels.collectAsState()
    var deleteChannel by remember { mutableStateOf<ChannelEntity?>(null) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testingId by remember { mutableStateOf<Long?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 添加按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.channels),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                ExtendedFloatingActionButton(
                    onClick = onAddChannel,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.add_channel)) }
                )
            }

            if (channels.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.no_channels),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(channels, key = { it.id }) { channel ->
                        ChannelCard(
                            channel = channel,
                            isTesting = testingId == channel.id,
                            testResult = if (testingId == channel.id) testResult else null,
                            onEdit = { onEditChannel(channel.id) },
                            onDelete = { deleteChannel = channel },
                            onToggle = { viewModel.toggleChannelStatus(channel) },
                            onTest = {
                                testingId = channel.id
                                testResult = null
                                viewModel.testChannel(channel) { result ->
                                    testResult = if (result.success) {
                                        "测试成功，响应时间 ${result.responseTime}ms"
                                    } else {
                                        "测试失败：${result.errorMessage}"
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // 删除确认对话框
    deleteChannel?.let { channel ->
        AlertDialog(
            onDismissRequest = { deleteChannel = null },
            title = { Text(stringResource(R.string.confirm_delete)) },
            text = { Text("渠道：${channel.name}") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteChannel(channel) { }
                        deleteChannel = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteChannel = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun ChannelCard(
    channel: ChannelEntity,
    isTesting: Boolean,
    testResult: String?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggle: () -> Unit,
    onTest: () -> Unit
) {
    val statusText = when (channel.status) {
        ChannelEntity.STATUS_ENABLED -> stringResource(R.string.status_enabled)
        ChannelEntity.STATUS_MANUAL_DISABLED -> stringResource(R.string.status_disabled)
        ChannelEntity.STATUS_AUTO_BANNED -> stringResource(R.string.status_auto_banned)
        else -> "未知"
    }
    val statusColor = when (channel.status) {
        ChannelEntity.STATUS_ENABLED -> MaterialTheme.colorScheme.primary
        ChannelEntity.STATUS_MANUAL_DISABLED -> MaterialTheme.colorScheme.outline
        ChannelEntity.STATUS_AUTO_BANNED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onEdit
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    channel.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Surface(
                    color = statusColor.copy(alpha = 0.15f),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                ) {
                    Text(
                        statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Text(
                "URL: ${channel.baseUrl}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "模型: ${channel.modelList().joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "密钥: ${channel.keyList().size} 个 · 优先级: ${channel.priority} · 权重: ${channel.weight}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "轮换: ${if (channel.keyMode == "polling") stringResource(R.string.key_mode_polling) else stringResource(R.string.key_mode_random)} · 自动禁用: ${if (channel.autoBan) "是" else "否"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (channel.responseTime > 0) {
                Text(
                    "最近响应: ${channel.responseTime}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // 测试结果
            if (isTesting && testResult != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    testResult,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (testResult.startsWith("测试成功"))
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(8.dp))

            // 操作按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(onClick = onTest, enabled = !isTesting) {
                    Text(if (isTesting) stringResource(R.string.testing) else stringResource(R.string.test_channel))
                }
                TextButton(onClick = onToggle) {
                    Text(if (channel.status == ChannelEntity.STATUS_ENABLED) "禁用" else "启用")
                }
                TextButton(onClick = onEdit) {
                    Text(stringResource(R.string.edit_channel))
                }
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.delete))
                }
            }
        }
    }
}
