package com.llmrouter.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.llmrouter.data.model.ChannelEntity
import com.llmrouter.data.model.ModelGroupMember

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelGroupEditScreen(
    group: com.llmrouter.data.model.ModelGroupEntity?,
    channels: List<ChannelEntity>,
    onSave: (name: String, displayName: String, members: List<ModelGroupMember>) -> Unit,
    onBack: () -> Unit
) {
    var name by remember { mutableStateOf(group?.name ?: "") }
    var displayName by remember { mutableStateOf(group?.displayName ?: "") }

    // 初始化已选成员（保留顺序）
    val initialMembers = remember { group?.memberList() ?: emptyList() }
    var selectedMembers by remember { mutableStateOf(initialMembers) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (group == null) "新建模型组" else "编辑模型组") },
                actions = {
                    IconButton(onClick = {
                        if (name.isNotBlank()) {
                            onSave(name.trim(), displayName.trim(), selectedMembers)
                        }
                    }) {
                        Icon(Icons.Filled.Check, contentDescription = "保存")
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 名称输入区
            Card(modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("模型组名称") },
                        placeholder = { Text("如 smart, fast, cheap") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { displayName = it },
                        label = { Text("展示名称（可选）") },
                        placeholder = { Text("如 智能优选") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // 已选成员排序区
            Text(
                text = "已选成员（上下箭头调整优先级顺序）",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(16.dp, 8.dp, 16.dp, 4.dp)
            )
            if (selectedMembers.isEmpty()) {
                Text(
                    text = "请从下方列表勾选模型",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp, 0.dp, 16.dp, 8.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 240.dp).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(selectedMembers.size) { index ->
                        val member = selectedMembers[index]
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${index + 1}.",
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.width(32.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = member.model,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Text(
                                        text = member.channelName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                // 上移
                                IconButton(
                                    onClick = {
                                        if (index > 0) {
                                            selectedMembers = selectedMembers.toMutableList().apply {
                                                add(index - 1, removeAt(index))
                                            }
                                        }
                                    },
                                    enabled = index > 0
                                ) {
                                    Icon(Icons.Filled.ArrowUpward, contentDescription = "上移")
                                }
                                // 下移
                                IconButton(
                                    onClick = {
                                        if (index < selectedMembers.size - 1) {
                                            selectedMembers = selectedMembers.toMutableList().apply {
                                                add(index + 1, removeAt(index))
                                            }
                                        }
                                    },
                                    enabled = index < selectedMembers.size - 1
                                ) {
                                    Icon(Icons.Filled.ArrowDownward, contentDescription = "下移")
                                }
                                // 取消选中
                                TextButton(onClick = {
                                    selectedMembers = selectedMembers.toMutableList().filter { it != member }
                                    if (selectedMembers is MutableList) {} // 触发 recomposition
                                }) {
                                    Text("取消")
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(16.dp, 8.dp))

            // 全量模型列表
            Text(
                text = "全部渠道模型（勾选参与此模型组的模型）",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(16.dp, 0.dp, 16.dp, 8.dp)
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(channels) { channel ->
                    Text(
                        text = "渠道：${channel.name}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                    )
                    channel.modelList().forEach { model ->
                        val member = ModelGroupMember(channel.id, channel.name, model)
                        val isSelected = selectedMembers.any { it.channelId == member.channelId && it.model == member.model }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedMembers = if (isSelected) {
                                        selectedMembers.filter { !(it.channelId == member.channelId && it.model == member.model) }
                                    } else {
                                        selectedMembers + member
                                    }
                                }
                                .padding(vertical = 4.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = {
                                    selectedMembers = if (it) {
                                        selectedMembers + member
                                    } else {
                                        selectedMembers.filter { !(it.channelId == member.channelId && it.model == member.model) }
                                    }
                                }
                            )
                            Text(
                                text = model,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
