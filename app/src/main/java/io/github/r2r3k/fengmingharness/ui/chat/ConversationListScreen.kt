package io.github.r2r3k.fengmingharness.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.r2r3k.fengmingharness.core.store.ConversationRow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(
    conversations: List<ConversationRow>,
    currentId: String?,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onDeleteMany: (List<String>) -> Unit,
    onClose: () -> Unit,
) {
    var renaming by remember { mutableStateOf<ConversationRow?>(null) }
    var deleting by remember { mutableStateOf<ConversationRow?>(null) }
    var renameText by remember { mutableStateOf("") }

    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmDeleteMany by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selecting) "已选 ${selected.size} 条" else "对话记录") },
                navigationIcon = {
                    if (selecting) {
                        TextButton(onClick = { selecting = false; selected = emptySet() }) { Text("取消") }
                    } else {
                        TextButton(onClick = onClose) { Text("返回") }
                    }
                },
                actions = {
                    if (selecting) {
                        TextButton(
                            onClick = { if (selected.isNotEmpty()) confirmDeleteMany = true },
                            enabled = selected.isNotEmpty(),
                        ) { Text("删除") }
                    } else {
                        TextButton(onClick = onNew) { Text("＋ 新对话") }
                        if (conversations.isNotEmpty()) {
                            TextButton(onClick = { selecting = true }) { Text("选择") }
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (conversations.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize()) {
                Text(
                    "还没有记录。\n回到聊天界面问一句，这里就会出现。",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(conversations, key = { it.id }) { item ->
                    Column(
                        Modifier.fillMaxWidth()
                            .clickable {
                                if (selecting) {
                                    selected = if (selected.contains(item.id)) selected - item.id else selected + item.id
                                } else {
                                    onOpen(item.id)
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (selecting) {
                                Checkbox(
                                    checked = selected.contains(item.id),
                                    onCheckedChange = { checked ->
                                        selected = if (checked) selected + item.id else selected - item.id
                                    },
                                )
                            }
                            Text(
                                text = item.title,
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                                color = if (item.id == currentId) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                            Text(
                                text = formatTime(item.updatedAt),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (item.totalTokens > 0) {
                            val rate = if (item.totalTokens <= 0) 0 else (item.totalCached * 100 / item.totalTokens)
                            Text(
                                text = "共 ${item.totalTokens} token · 缓存 $rate% · " +
                                    io.github.r2r3k.fengmingharness.core.util.Pricing.formatCny(item.totalCost),
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!selecting) {
                            Row {
                                Text(
                                    "改名",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .clickable { renaming = item; renameText = item.title }
                                        .padding(vertical = 6.dp, horizontal = 2.dp),
                                )
                                Text("　", fontSize = 12.sp)
                                Text(
                                    "删除",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier
                                        .clickable { deleting = item }
                                        .padding(vertical = 6.dp, horizontal = 2.dp),
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        }
    }

    renaming?.let { target ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("改名") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = renameText.trim()
                    if (name.isNotEmpty()) onRename(target.id, name)
                    renaming = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("取消") } },
        )
    }

    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除这条记录？") },
            text = { Text("删了就找不回来了：${target.title}") },
            confirmButton = {
                TextButton(onClick = { onDelete(target.id); deleting = null }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }

    if (confirmDeleteMany) {
        AlertDialog(
            onDismissRequest = { confirmDeleteMany = false },
            title = { Text("删除选中的 ${selected.size} 条记录？") },
            text = { Text("删了就找不回来了，里面的图片和文档也会一起清掉。") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteMany(selected.toList())
                    selected = emptySet()
                    selecting = false
                    confirmDeleteMany = false
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteMany = false }) { Text("取消") }
            },
        )
    }
}

private fun formatTime(millis: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
