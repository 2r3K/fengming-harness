package io.github.r2r3k.fengmingharness.ui.importer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.r2r3k.fengmingharness.core.importer.ChatImporter
import io.github.r2r3k.fengmingharness.core.importer.ImportScan
import io.github.r2r3k.fengmingharness.core.importer.ImportSession
import io.github.r2r3k.fengmingharness.core.store.ChatDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 导入 DeepSeek 官方导出的历史对话。
 *
 * 用法：直接把下载下来的整个压缩包选上（推荐），或者单独选里面的 json 文件。
 * 解析全程容错，坏数据只跳过、不会崩。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(onClose: () -> Unit) {
    androidx.activity.compose.BackHandler(enabled = true) { onClose() }

    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { ChatDatabase(ctx) }
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }

    var scanning by remember { mutableStateOf(false) }
    var scan by remember { mutableStateOf<ImportScan?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var importing by remember { mutableStateOf(false) }
    var progressDone by remember { mutableStateOf(0) }
    var progressTotal by remember { mutableStateOf(0) }
    var progressText by remember { mutableStateOf("") }
    var withImages by remember { mutableStateOf(true) }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scanning = true
        result = null
        error = null
        progressText = "正在读压缩包…"
        scope.launch {
            val found = withContext(Dispatchers.IO) {
                runCatching {
                    ChatImporter.scan(
                        context = ctx,
                        uris = uris,
                        withImages = withImages,
                        skipAlready = { id -> db.conversationExists(id) },
                        onProgress = { text -> progressText = text },
                    )
                }.getOrElse { e ->
                    error = "解析出错：${e.message ?: "未知原因"}"
                    null
                }
            }
            scanning = false
            if (found != null) {
                scan = found
                selected = found.sessions.map { it.sourceId }.toSet()
                if (found.sessions.isEmpty()) {
                    error = "没解析到有内容的对话。" + found.notes.takeLast(3).joinToString("；")
                }
            }
        }
    }

    fun doImport(sessions: List<ImportSession>) {
        if (sessions.isEmpty() || importing) return
        importing = true
        progressDone = 0
        progressTotal = sessions.size
        scope.launch {
            var ok = 0
            var skipped = 0
            var images = 0
            withContext(Dispatchers.IO) {
                sessions.forEachIndexed { index, session ->
                    val id = "imp-" + session.sourceId.take(40)
                    val inserted = runCatching {
                        db.insertConversationWithMessages(
                            id = id,
                            title = session.title,
                            createdAt = session.createdAt,
                            updatedAt = session.updatedAt,
                            rows = session.messages.map {
                                ChatDatabase.ImportRow(
                                    role = it.role,
                                    content = it.text,
                                    reasoning = it.reasoning,
                                    imagesJson = it.imagesJson,
                                    sourcesJson = it.sourcesJson,
                                    time = it.time,
                                )
                            },
                        )
                    }.getOrDefault(false)
                    if (inserted) {
                        ok++
                        images += session.messages.sumOf { it.imageCount }
                    } else {
                        skipped++
                    }
                    progressDone = index + 1
                }
            }
            importing = false
            result = "导入完成：新增 $ok 个对话" +
                (if (images > 0) "（含 $images 张图片）" else "") +
                "，跳过 $skipped 个（已存在或没内容）"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("导入官方对话", fontSize = 16.sp) },
                navigationIcon = { TextButton(onClick = onClose) { Text("← 返回") } },
                actions = {
                    TextButton(
                        onClick = { picker.launch(arrayOf("*/*")) },
                        enabled = !scanning && !importing,
                    ) { Text(if (scanning) "解析中…" else "选文件") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(12.dp),
        ) {
            Text(
                "选 DeepSeek 导出的那个压缩包（推荐），或者单独选里面的 json 文件。" +
                    "我把「对话目录」和每个对话的消息文件按 id 合起来，对话里的图片也从压缩包里取出来存好。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = withImages, onCheckedChange = { withImages = it }, enabled = !scanning && !importing)
                Text("把对话里的图片也导进来（多花一两分钟）", fontSize = 12.sp)
            }

            when {
                scanning -> Text(
                    progressText.ifBlank { "正在读，文件大的话要等一会儿…" },
                    fontSize = 12.sp,
                )

                importing -> {
                    Text(
                        "正在导入 $progressDone / $progressTotal 个对话…",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                else -> Unit
            }

            error?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }
            result?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            }

            scan?.let { found ->
                if (found.sessions.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "共 ${found.sessions.size} 个对话 · ${found.totalMessages} 条消息" +
                                " · 你问的 ${found.userMessages} 轮" +
                                (if (found.imageCount > 0) " · ${found.imageCount} 张图片" else "") +
                                (if (found.sourceCount > 0) " · ${found.sourceCount} 条来源" else "") +
                                " · 已选 ${selected.size} 个",
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            selected = if (selected.size == found.sessions.size) {
                                emptySet()
                            } else {
                                found.sessions.map { it.sourceId }.toSet()
                            }
                        }) { Text(if (selected.size == found.sessions.size) "全不选" else "全选") }
                    }
                    HorizontalDivider()

                    LazyColumn(Modifier.weight(1f)) {
                        items(found.sessions, key = { it.sourceId }) { session ->
                            val checked = session.sourceId in selected
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selected = if (checked) {
                                            selected - session.sourceId
                                        } else {
                                            selected + session.sourceId
                                        }
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = checked, onCheckedChange = {
                                    selected = if (it) selected + session.sourceId
                                    else selected - session.sourceId
                                })
                                Column(Modifier.weight(1f)) {
                                    Text(session.title, fontSize = 14.sp, maxLines = 1)
                                    Text(
                                        "${session.messages.size} 条 · " +
                                            dateFormat.format(Date(session.updatedAt)) +
                                            " · 其中你问的 " + session.messages.count { it.role == "user" } + " 条" +
                                            (if (session.messages.any { it.imageCount > 0 }) {
                                                " · " + session.messages.sumOf { it.imageCount } + " 张图"
                                            } else {
                                                ""
                                            }),
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }

                    Button(
                        onClick = {
                            doImport(found.sessions.filter { it.sourceId in selected })
                        },
                        enabled = selected.isNotEmpty() && !importing,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (importing) "导入中…" else "导入这 ${selected.size} 个对话") }

                    if (found.notes.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            found.notes.takeLast(4).joinToString("\n"),
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (scan == null && !scanning && !importing) {
                Spacer(Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("怎么导：", fontSize = 12.sp)
                    Text(
                        "1. 在 DeepSeek 网页版/App 里：设置 → 数据管理 → 导出所有历史对话\n" +
                            "2. 等几分钟，点它给的下载链接（只有 7 天有效）\n" +
                            "3. 回到这里点右上角「选文件」，把下载下来的 .zip 直接选上",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
