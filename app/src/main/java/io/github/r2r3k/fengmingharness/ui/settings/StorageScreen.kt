package io.github.r2r3k.fengmingharness.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.r2r3k.fengmingharness.core.store.ChatDatabase
import io.github.r2r3k.fengmingharness.core.store.StorageAudit
import io.github.r2r3k.fengmingharness.core.store.StorageReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 存储体检：用久了到底谁在占空间，以及"没人用的图"能不能清掉。
 *
 * 这个页面只做两件事：
 *   1. 统计（数据库 / 图片 / 缓存 / 孤儿图片）；
 *   2. 清理（只删图片目录里没被任何消息引用的文件 + 缓存临时文件）。
 * 聊天记录一条都不会动。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(onClose: () -> Unit) {
    androidx.activity.compose.BackHandler(enabled = true) { onClose() }
    val ctx = LocalContext.current
    val db = remember { ChatDatabase(ctx) }
    val scope = rememberCoroutineScope()

    var report by remember { mutableStateOf<StorageReport?>(null) }
    var scanning by remember { mutableStateOf(true) }
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmOrphans by remember { mutableStateOf(false) }

    fun refresh() {
        scanning = true
        scope.launch {
            report = withContext(Dispatchers.IO) {
                runCatching { StorageAudit.scan(ctx, db) }.getOrNull()
            }
            scanning = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("存储与清理", fontSize = 16.sp) },
                navigationIcon = { TextButton(onClick = onClose) { Text("← 返回") } },
                actions = {
                    TextButton(onClick = { refresh() }, enabled = !scanning) {
                        Text(if (scanning) "统计中…" else "重新统计")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            val data = report
            if (data == null) {
                Text(if (scanning) "正在统计…" else "统计失败", fontSize = 12.sp)
                return@Column
            }

            // 总览
            Surface(
                tonalElevation = 2.dp,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("这一堆一共占了", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(StorageAudit.human(data.totalBytes), fontSize = 26.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${data.conversations} 个对话 · ${data.messages} 条消息",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("明细", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            Line("数据库（聊天记录本身）", StorageAudit.human(data.dbBytes))
            Line("图片文件", "${StorageAudit.human(data.imageBytes)}（${data.imageFiles} 个）")
            Line("其中没人用的", StorageAudit.human(data.orphanBytes) + "（${data.orphanFiles} 个）")
            Line("缓存（导入导出的临时文件）", StorageAudit.human(data.cacheBytes))

            Spacer(Modifier.height(14.dp))
            Text(
                "说明：删对话的时候，图片文件不会跟着删，用久了会攒下一些没人引用的图。" +
                    "下面这个清理只会删「没有任何消息在用的图片」和临时缓存，聊天记录一条都不动。",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(14.dp))
            Row {
                TextButton(
                    onClick = { confirmOrphans = true },
                    enabled = !working && data.orphanFiles > 0,
                ) {
                    Text(
                        if (data.orphanFiles > 0) "清理没用的图片（省 ${StorageAudit.human(data.orphanBytes)}）"
                        else "没有可清理的图片",
                        fontSize = 12.sp,
                    )
                }
            }
            Row {
                TextButton(
                    onClick = {
                        if (working) return@TextButton
                        working = true
                        scope.launch {
                            val freed = withContext(Dispatchers.IO) {
                                runCatching { StorageAudit.cleanCache(ctx) }.getOrDefault(0 to 0L)
                            }
                            working = false
                            message = "缓存清掉了 ${freed.first} 个文件，省 ${StorageAudit.human(freed.second)}"
                            refresh()
                        }
                    },
                    enabled = !working && data.cacheBytes > 0,
                ) {
                    Text(
                        if (data.cacheBytes > 0) "清理缓存（省 ${StorageAudit.human(data.cacheBytes)}）"
                        else "缓存是空的",
                        fontSize = 12.sp,
                    )
                }
            }

            message?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmOrphans) {
        val data = report
        AlertDialog(
            onDismissRequest = { confirmOrphans = false },
            title = { Text("清理没用的图片") },
            text = {
                Text(
                    "会删掉 ${data?.orphanFiles ?: 0} 个文件，省 ${StorageAudit.human(data?.orphanBytes ?: 0L)}。\n" +
                        "只删「没有任何消息在用」的图片，聊天记录里的图一张都不会动。\n\n确定？",
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmOrphans = false
                    if (working) return@TextButton
                    working = true
                    scope.launch {
                        val freed = withContext(Dispatchers.IO) {
                            runCatching { StorageAudit.cleanOrphans(ctx, db) }.getOrDefault(0 to 0L)
                        }
                        working = false
                        message = "清掉了 ${freed.first} 个文件，省 ${StorageAudit.human(freed.second)}"
                        refresh()
                    }
                }) { Text("就删这些") }
            },
            dismissButton = {
                TextButton(onClick = { confirmOrphans = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(value, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
