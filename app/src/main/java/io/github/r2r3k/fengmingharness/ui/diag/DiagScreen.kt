package io.github.r2r3k.fengmingharness.ui.diag

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import io.github.r2r3k.fengmingharness.core.AppInfo
import io.github.r2r3k.fengmingharness.core.DiagLog
import io.github.r2r3k.fengmingharness.core.model.AppSettings
import io.github.r2r3k.fengmingharness.core.store.ChatDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 诊断页：出问题的时候把这一页截图或者导出日志给我看，就不用猜了。
 * 只显示 Key 有没有填，绝不显示 Key 本身。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagScreen(settings: AppSettings, onClose: () -> Unit) {
    // 返回手势先关这一页，别直接退出 App
    androidx.activity.compose.BackHandler(enabled = true) { onClose() }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { ChatDatabase(ctx) }

    val versionName = remember {
        runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrNull().orEmpty().ifBlank { "?" }
    }
    var counts by remember { mutableStateOf(0 to 0) }
    LaunchedEffect(Unit) {
        // 数库里的条数要读数据库，别在界面线程上干
        counts = withContext(Dispatchers.IO) {
            runCatching {
                val convs = db.listConversations()
                val msgs = convs.sumOf { db.loadMessages(it.id).size }
                convs.size to msgs
            }.getOrDefault(0 to 0)
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val text = buildString {
                    appendLine(AppInfo.NAME + " 诊断报告")
                    appendLine("=== 环境 ===")
                    appendLine("App 版本：$versionName")
                    appendLine("手机：${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("系统：Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）")
                    appendLine("=== 配置 ===")
                    appendLine("Base URL：${settings.baseUrl}")
                    appendLine("模型：${settings.model}")
                    appendLine("Key：${if (settings.apiKey.isBlank()) "没填" else "已填（不显示）"}")
                    appendLine("上下文档位：${settings.contextTier.label}")
                    appendLine("文档解析：${if (settings.docParseRich) "连图" else "只要文字"}")
                    appendLine("对话数：${counts.first}，消息数：${counts.second}")
                    appendLine()
                    appendLine("=== 最近日志 ===")
                    appendLine(DiagLog.dump())
                }
                withContext(Dispatchers.IO) {
                    runCatching {
                        ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("诊断", fontSize = 16.sp) },
                navigationIcon = { TextButton(onClick = onClose) { Text("← 返回") } },
                actions = {
                    TextButton(onClick = {
                        exportLauncher.launch("diagnostics-" + System.currentTimeMillis() + ".txt")
                    }) { Text("导出日志") }
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
            Section("手机")
            Kv("型号", "${Build.MANUFACTURER} ${Build.MODEL}")
            Kv("系统", "Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）")
            Kv("App 版本", versionName)

            Spacer(Modifier.height(10.dp))
            Section("配置")
            Kv("Base URL", settings.baseUrl)
            Kv("模型", settings.model)
            Kv("API Key", if (settings.apiKey.isBlank()) "没填" else "已填（不显示）")
            Kv("上下文档位", "${settings.contextTier.label} · ${settings.contextTier.limitTokens} token")
            Kv("文档解析", if (settings.docParseRich) "连图一起看" else "只要文字")
            Kv("联网默认", if (settings.webSearchDefault) "开" else "关")

            Spacer(Modifier.height(10.dp))
            Section("本机数据")
            Kv("对话", "${counts.first} 个")
            Kv("消息", "${counts.second} 条")

            Spacer(Modifier.height(10.dp))
            Section("最近日志（最多 400 行）")
            Text(
                text = DiagLog.dump(),
                fontSize = 10.sp,
                lineHeight = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { DiagLog.clear() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("清空日志") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun Kv(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(key, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(90.dp))
        Text(value, fontSize = 12.sp)
    }
}
