package com.llmrouter.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.llmrouter.data.model.ModelGroupEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelGroupListScreen(
    modelGroups: List<ModelGroupEntity>,
    onAddGroup: () -> Unit,
    onEditGroup: (Long) -> Unit,
    onDeleteGroup: (ModelGroupEntity) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("模型组管理") },
                actions = {
                    IconButton(onClick = onAddGroup) {
                        Icon(Icons.Filled.Add, contentDescription = "添加")
                    }
                }
            )
        }
    ) { padding ->
        if (modelGroups.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("暂无模型组", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text("点击右上角 + 创建自定义模型组", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(modelGroups) { group ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onEditGroup(group.id) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = group.name,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                if (group.displayName.isNotEmpty() && group.displayName != group.name) {
                                    Text(
                                        text = group.displayName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                val members = group.memberList()
                                Text(
                                    text = "${members.size} 个成员",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (members.isNotEmpty()) {
                                    Text(
                                        text = members.take(3).joinToString(" → ") { "${it.channelName}:${it.model}" } +
                                                if (members.size > 3) " …" else "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                            }
                            IconButton(onClick = { onDeleteGroup(group) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "删除")
                            }
                        }
                    }
                }
            }
        }
    }
}
