package com.llmrouter.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.llmrouter.R
import com.llmrouter.data.model.ChannelEntity
import com.llmrouter.ui.MainViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelEditScreen(
    viewModel: MainViewModel,
    channelId: Long?,
    onSaved: () -> Unit,
    onCancel: () -> Unit
) {
    val channels by viewModel.channels.collectAsState()
    val existingChannel = remember(channelId) {
        channels.find { it.id == channelId }
    }

    var name by remember { mutableStateOf(existingChannel?.name ?: "") }
    var baseUrl by remember { mutableStateOf(existingChannel?.baseUrl ?: "") }
    var apiKeys by remember { mutableStateOf(existingChannel?.apiKeys ?: "") }
    var models by remember { mutableStateOf(existingChannel?.models ?: "") }
    var priority by remember { mutableStateOf((existingChannel?.priority ?: 0).toString()) }
    var weight by remember { mutableStateOf((existingChannel?.weight ?: 1).toString()) }
    var autoBan by remember { mutableStateOf(existingChannel?.autoBan ?: true) }
    var keyMode by remember { mutableStateOf(existingChannel?.keyMode ?: "random") }
    var testModel by remember { mutableStateOf(existingChannel?.testModel ?: "") }

    var nameError by remember { mutableStateOf(false) }
    var baseUrlError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            if (existingChannel != null) stringResource(R.string.edit_channel) else stringResource(R.string.add_channel),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(16.dp))

        // 渠道名称
        OutlinedTextField(
            value = name,
            onValueChange = { name = it; nameError = false },
            label = { Text(stringResource(R.string.channel_name)) },
            isError = nameError,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(12.dp))

        // 基础 URL
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it; baseUrlError = false },
            label = { Text(stringResource(R.string.base_url)) },
            isError = baseUrlError,
            placeholder = { Text("https://api.openai.com") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(12.dp))

        // API 密钥
        OutlinedTextField(
            value = apiKeys,
            onValueChange = { apiKeys = it },
            label = { Text(stringResource(R.string.api_keys)) },
            placeholder = { Text("sk-xxx,sk-yyy") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2
        )
        Spacer(Modifier.height(12.dp))

        // 模型列表
        OutlinedTextField(
            value = models,
            onValueChange = { models = it },
            label = { Text(stringResource(R.string.models)) },
            placeholder = { Text("gpt-4o,gpt-4o-mini,claude-3-opus") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2
        )
        Spacer(Modifier.height(12.dp))

        // 优先级
        OutlinedTextField(
            value = priority,
            onValueChange = { priority = it.filter { c -> c.isDigit() } },
            label = { Text(stringResource(R.string.priority)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(12.dp))

        // 权重
        OutlinedTextField(
            value = weight,
            onValueChange = { weight = it.filter { c -> c.isDigit() } },
            label = { Text(stringResource(R.string.weight)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(12.dp))

        // 测试模型
        OutlinedTextField(
            value = testModel,
            onValueChange = { testModel = it },
            label = { Text(stringResource(R.string.test_model)) },
            placeholder = { Text("留空则使用第一个模型") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(12.dp))

        // 密钥轮换模式
        Text(stringResource(R.string.key_mode), style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = keyMode == "random",
                onClick = { keyMode = "random" },
                label = { Text(stringResource(R.string.key_mode_random)) }
            )
            FilterChip(
                selected = keyMode == "polling",
                onClick = { keyMode = "polling" },
                label = { Text(stringResource(R.string.key_mode_polling)) }
            )
        }
        Spacer(Modifier.height(12.dp))

        // 自动禁用
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.auto_ban), style = MaterialTheme.typography.bodyLarge)
            Switch(checked = autoBan, onCheckedChange = { autoBan = it })
        }
        Spacer(Modifier.height(24.dp))

        // 保存/取消按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.cancel)) }

            Button(
                onClick = {
                    if (name.isBlank()) { nameError = true; return@Button }
                    if (baseUrl.isBlank()) { baseUrlError = true; return@Button }

                    val channel = (existingChannel ?: ChannelEntity(
                        name = name,
                        baseUrl = baseUrl,
                        apiKeys = apiKeys,
                        models = models
                    )).copy(
                        name = name,
                        baseUrl = baseUrl,
                        apiKeys = apiKeys,
                        models = models,
                        priority = priority.toIntOrNull() ?: 0,
                        weight = weight.toIntOrNull() ?: 1,
                        autoBan = autoBan,
                        keyMode = keyMode,
                        testModel = testModel
                    )

                    scope.launch {
                        if (existingChannel != null) {
                            viewModel.updateChannel(channel) { onSaved() }
                        } else {
                            viewModel.addChannel(
                                name, baseUrl, apiKeys, models,
                                priority.toIntOrNull() ?: 0,
                                weight.toIntOrNull() ?: 1,
                                autoBan, keyMode, testModel
                            ) { onSaved() }
                        }
                    }
                },
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.save)) }
        }
    }
}
