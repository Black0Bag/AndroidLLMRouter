package com.llmrouter.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.llmrouter.R
import com.llmrouter.data.model.ChannelEntity
import com.llmrouter.ui.KeyTestResult
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
    // 多 Key 动态列表
    var keyList by remember {
        mutableStateOf(existingChannel?.keyList() ?: listOf(""))
    }
    // 每个 Key 的检测结果
    var keyTestResults by remember { mutableStateOf(mutableMapOf<Int, Pair<Boolean, String>>()) }
    var testingKeyIndex by remember { mutableStateOf(-1) }
    // 模型列表（从服务器拉取）
    var allModels by remember { mutableStateOf(existingChannel?.allModelList() ?: emptyList()) }
    var disabledModels by remember {
        mutableStateOf(existingChannel?.disabledModelSet()?.toMutableSet() ?: mutableSetOf())
    }
    var fetchingModels by remember { mutableStateOf(false) }
    var fetchError by remember { mutableStateOf<String?>(null) }

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
        Spacer(Modifier.height(16.dp))

        // === 多 Key 动态输入 ===
        Text("API 密钥", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))

        keyList.forEachIndexed { index, key ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                OutlinedTextField(
                    value = key,
                    onValueChange = { newValue ->
                        keyList = keyList.toMutableList().also { it[index] = newValue }
                    },
                    label = { Text("密钥 #${index + 1}") },
                    placeholder = { Text("sk-xxx") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    trailingIcon = {
                        if (keyList.size > 1) {
                            IconButton(onClick = {
                                keyList = keyList.toMutableList().also { it.removeAt(index) }
                                keyTestResults = keyTestResults.toMutableMap().also { it.remove(index) }
                            }) {
                                Icon(Icons.Filled.Delete, contentDescription = "删除此密钥",
                                    tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                )
                // 检测按钮
                OutlinedButton(
                    onClick = {
                        testingKeyIndex = index
                        val testModelName = testModel.ifBlank {
                            allModels.firstOrNull() ?: "gpt-4o-mini"
                        }
                        viewModel.testSingleKey(baseUrl, key, testModelName) { result ->
                            keyTestResults = keyTestResults.toMutableMap().also {
                                it[index] = if (result.success) {
                                    Pair(true, "✓ ${result.responseTime}ms")
                                } else {
                                    Pair(false, "✗ ${result.errorMessage?.take(40) ?: "失败"}")
                                }
                            }
                            testingKeyIndex = -1
                        }
                    },
                    enabled = key.isNotBlank() && baseUrl.isNotBlank() && testingKeyIndex != index
                ) {
                    if (testingKeyIndex == index) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("检测")
                    }
                }
            }
            // 显示检测结果
            keyTestResults[index]?.let { (success, msg) ->
                Text(
                    msg,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (success) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        // 添加密钥按钮
        OutlinedButton(
            onClick = { keyList = keyList + "" },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("添加密钥")
        }
        Spacer(Modifier.height(16.dp))

        // === 模型列表 ===
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("模型列表", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            // 下载按钮：从服务器拉取
            OutlinedButton(
                onClick = {
                    if (baseUrl.isNotBlank() && keyList.isNotEmpty()) {
                        fetchingModels = true
                        fetchError = null
                        viewModel.fetchModels(baseUrl, keyList.first { it.isNotBlank() }) { result ->
                            fetchingModels = false
                            if (result.success) {
                                allModels = result.models
                                // 清除已不存在的排除项
                                val allModelSet = result.models.map { it.lowercase() }.toSet()
                                disabledModels = disabledModels.filter { it in allModelSet }.toMutableSet()
                            } else {
                                fetchError = result.errorMessage
                            }
                        }
                    }
                },
                enabled = !fetchingModels && baseUrl.isNotBlank()
            ) {
                if (fetchingModels) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(4.dp))
                Text("从服务器拉取")
            }
        }

        fetchError?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }

        if (allModels.isEmpty()) {
            Text(
                "尚未拉取模型列表，请先填写 URL 和密钥后点击「从服务器拉取」",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        } else {
            Text(
                "共 ${allModels.size} 个模型，已排除 ${disabledModels.size} 个",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp)
            )
            // 模型列表：每个模型可勾选/排除
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                items(allModels, key = { it }) { model ->
                    val isDisabled = model.lowercase() in disabledModels
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = !isDisabled,
                            onCheckedChange = { checked ->
                                disabledModels = if (checked) {
                                    disabledModels - model.lowercase()
                                } else {
                                    disabledModels + model.lowercase()
                                }.toMutableSet()
                            }
                        )
                        Text(
                            model,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isDisabled)
                                MaterialTheme.colorScheme.outline
                            else
                                MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))

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
            verticalAlignment = Alignment.CenterVertically
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

                    val nonEmptyKeys = keyList.filter { it.isNotBlank() }
                    val activeModels = allModels.filter { it.lowercase() !in disabledModels }

                    val channel = (existingChannel ?: ChannelEntity(
                        name = name,
                        baseUrl = baseUrl,
                        apiKeys = nonEmptyKeys.joinToString(","),
                        models = allModels.joinToString(",")
                    )).copy(
                        name = name,
                        baseUrl = baseUrl,
                        apiKeys = nonEmptyKeys.joinToString(","),
                        models = allModels.joinToString(","),
                        disabledModels = disabledModels.joinToString(","),
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
                                name, baseUrl, nonEmptyKeys, allModels, disabledModels,
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
