package io.github.r2r3k.fengmingharness.ui.kb

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import io.github.r2r3k.fengmingharness.core.SettingsStore
import io.github.r2r3k.fengmingharness.core.kb.KbIndexer
import io.github.r2r3k.fengmingharness.core.kb.KbStats
import io.github.r2r3k.fengmingharness.core.kb.KnowledgeBase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeBaseScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val store = remember { SettingsStore(ctx) }
    val kb = remember { KnowledgeBase(ctx) }
    val scope = rememberCoroutineScope()

    var treeUri by remember { mutableStateOf(store.kbTreeUri()) }
    var stats by remember { mutableStateOf(KbStats()) }
    var indexing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var progressText by remember { mutableStateOf<String?>(null) }

    fun refreshStats() {
        scope.launch {
            stats = withContext(Dispatchers.IO) { kb.stats() }
        }
    }

    LaunchedEffect(Unit) { refreshStats() }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            store.saveKbTreeUri(uri.toString())
            treeUri = uri.toString()
            status = "已选择文件夹。点「重建索引」开始扫描。"
        } catch (e: Exception) {
            status = "授权失败：${e.message ?: "系统没给权限"}"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("本地知识库") },
                navigationIcon = {
                    TextButton(onClick = onClose) { Text("返回") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("当前文件夹", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = treeUri?.let { describeUri(it) } ?: "还没有选择文件夹",
                fontSize = 15.sp,
            )

            OutlinedButton(
                onClick = { picker.launch(null) },
                enabled = !indexing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (treeUri == null) "选择文件夹（例如 0001）" else "重新选择文件夹")
            }

            HorizontalDivider()

            Text(
                "已索引：${stats.docs} 个文件 · ${stats.chunks} 段 · 约 ${stats.chars} 字",
                fontSize = 14.sp,
            )
            if (stats.indexedAt > 0) {
                Text(
                    "上次索引：${formatTime(stats.indexedAt)}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Button(
                onClick = {
                    val uri = treeUri ?: return@Button
                    scope.launch {
                        indexing = true
                        status = null
                        progressText = "正在扫描…"
                        val result = runCatching {
                            KbIndexer.rebuild(ctx, Uri.parse(uri)) { p ->
                                progressText = if (p.current == "完成") {
                                    "完成"
                                } else {
                                    "已扫描 ${p.scanned} · 已索引 ${p.indexed} · 跳过 ${p.skipped}｜${p.current}"
                                }
                            }
                        }
                        indexing = false
                        result
                            .onSuccess {
                                stats = it
                                status = "索引完成：${it.docs} 个文件，${it.chunks} 段"
                            }
                            .onFailure { status = "索引失败：${it.message ?: "未知错误"}" }
                    }
                },
                enabled = treeUri != null && !indexing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (indexing) "正在建索引…" else "重建索引")
            }

            if (indexing) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                progressText?.let { Text(it, fontSize = 12.sp, maxLines = 2) }
                Text(
                    "第一次扫几百个文件要一会儿，别关 App。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            status?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary) }

            OutlinedButton(
                onClick = {
                    if (treeUri != null) {
                        runCatching {
                            ctx.contentResolver.releasePersistableUriPermission(
                                Uri.parse(treeUri),
                                Intent.FLAG_GRANT_READ_URI_PERMISSION,
                            )
                        }
                    }
                    store.saveKbTreeUri(null)
                    treeUri = null
                    scope.launch {
                        withContext(Dispatchers.IO) { kb.clearAll() }
                        refreshStats()
                        status = "已取消授权并清空索引（你的文件没动）"
                    }
                },
                enabled = treeUri != null && !indexing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("取消授权并清空索引")
            }

            HorizontalDivider()

            Text("说明", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "· 只能读你选的那个文件夹，不会动你的文件，也不会申请整个存储权限。\n" +
                    "· 现在只收纯文本类：txt / md / json / csv / 代码文件。PDF、Word 请先在电脑上转成 md/txt 再放进来。\n" +
                    "· 单文件超过 2MB、全库超过约 1200 万字的部分会被跳过。\n" +
                    "· 检索在手机上做，但被挑中的片段会随问题一起发给 DeepSeek。",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun describeUri(raw: String): String {
    val marker = "/tree/"
    val index = raw.indexOf(marker)
    if (index < 0) return raw
    val id = raw.substring(index + marker.length).substringBefore('/')
    return id.substringAfterLast(':').ifBlank { id }
}

private fun formatTime(millis: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
