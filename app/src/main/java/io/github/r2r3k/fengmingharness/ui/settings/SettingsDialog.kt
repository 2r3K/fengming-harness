package io.github.r2r3k.fengmingharness.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.r2r3k.fengmingharness.core.SettingsStore
import io.github.r2r3k.fengmingharness.core.UiText
import io.github.r2r3k.fengmingharness.core.UiTheme
import io.github.r2r3k.fengmingharness.core.model.AppSettings
import io.github.r2r3k.fengmingharness.core.model.ContextTier
import io.github.r2r3k.fengmingharness.core.exporter.OfficialExporter
import io.github.r2r3k.fengmingharness.core.provider.ModelCatalog
import io.github.r2r3k.fengmingharness.core.store.ChatDatabase
import io.github.r2r3k.fengmingharness.core.util.NetGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsDialog(
    initial: AppSettings,
    onDismiss: () -> Unit,
    onSave: (AppSettings) -> Unit,
    onOpenDiagnostics: () -> Unit = {},
    onOpenMcp: () -> Unit = {},
    onOpenBill: () -> Unit = {},
    onExport: () -> Unit = {},
    onOpenTemplates: () -> Unit = {},
    onBackup: () -> Unit = {},
    onRestore: () -> Unit = {},
    onOpenImport: () -> Unit = {},
    onOpenStorage: () -> Unit = {},
) {
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var model by remember { mutableStateOf(initial.model) }
    var temperature by remember { mutableStateOf(initial.temperature.toFloat()) }
    var showKey by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var hint by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }

    var loadingModels by remember { mutableStateOf(false) }
    var modelList by remember { mutableStateOf<List<String>>(emptyList()) }
    var modelError by remember { mutableStateOf<String?>(null) }
    var tier by remember { mutableStateOf(initial.contextTier) }
    var docRich by remember { mutableStateOf(initial.docParseRich) }

    val scope = rememberCoroutineScope()
    val uiContext = LocalContext.current
    val uiStore = remember(uiContext) { SettingsStore(uiContext) }
    var autoCount by remember { mutableStateOf(uiStore.autoTools().size) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("显示字号：${UiText.label}（大字看着舒服，小字一屏能放更多）", fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        UiText.SMALL to "小",
                        UiText.STANDARD to "标准",
                        UiText.LARGE to "大",
                    ).forEach { (value, text) ->
                        FilterChip(
                            selected = UiText.level == value,
                            onClick = { UiText.set(uiStore, value) },
                            label = { Text(text, fontSize = 11.sp) },
                        )
                    }
                }
                Text(
                    "点一下立刻生效，不用按保存。样例：你好，我是奉明Harness。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text("深色模式：${UiTheme.label}", fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        UiTheme.SYSTEM to "跟随系统",
                        UiTheme.LIGHT to "浅色",
                        UiTheme.DARK to "深色",
                    ).forEach { (value, text) ->
                        FilterChip(
                            selected = UiTheme.mode == value,
                            onClick = { UiTheme.set(uiStore, value) },
                            label = { Text(text, fontSize = 11.sp) },
                        )
                    }
                }

                HorizontalDivider()

                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { value ->
                        // 手滑把 Key 粘到网址栏：直接帮他挪到下面那栏
                        if ((value.startsWith("sk-") || value.startsWith("sk_")) && apiKey.isBlank()) {
                            apiKey = value.trim()
                            baseUrl = "https://api.deepseek.com"
                            hint = "看着是 API Key，已经帮好挪到下面那栏了"
                        } else {
                            baseUrl = value
                            hint = null
                        }
                    },
                    label = { Text("Base URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it; hint = null },
                    label = { Text("API Key") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation =
                        if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { showKey = !showKey }) {
                            Text(if (showKey) "隐藏" else "显示")
                        }
                    },
                )

                Column {
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = { Text("模型名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(
                        onClick = {
                            loadingModels = true
                            modelError = null
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    ModelCatalog.fetch(baseUrl, apiKey)
                                }
                                loadingModels = false
                                result
                                    .onSuccess { modelList = it; if (it.isEmpty()) modelError = "服务器没返回任何模型" }
                                    .onFailure { modelError = "拉取失败：${it.message ?: "未知错误"}" }
                            }
                        },
                        enabled = !loadingModels && apiKey.isNotBlank(),
                    ) {
                        Text(if (loadingModels) "正在拉取…" else "刷新可用模型（免费用一次 /models）")
                    }
                    modelError?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                    if (modelList.isNotEmpty()) {
                        Text("点一个切换：", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
                            modelList.forEach { id ->
                                Text(
                                    text = if (id == model) "✓ $id" else id,
                                    fontSize = 13.sp,
                                    color = if (id == model) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { model = id }
                                        .padding(vertical = 6.dp),
                                )
                            }
                        }
                    }
                }

                Text("温度：${"%.2f".format(temperature)}（越低越稳；开思考模式时这个参数会被忽略）", fontSize = 13.sp)
                Slider(
                    value = temperature,
                    onValueChange = { temperature = it },
                    valueRange = 0f..2f,
                )

                HorizontalDivider()

                Text("上下文预算：${tier.label} · ${tier.desc}", fontSize = 13.sp)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    ContextTier.entries.forEach { item ->
                        FilterChip(
                            selected = tier == item,
                            onClick = { tier = item },
                            label = { Text(item.label, fontSize = 11.sp) },
                        )
                    }
                }
                Text(
                    "上限：" + if (tier == ContextTier.UNLIMITED) "90 万 token（模型上限的 90%）" else "${tier.limitTokens} token",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider()

                Text("文档解析方式（PDF / Word / PPT）", fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = docRich, onClick = { docRich = true }, label = { Text("连图一起看", fontSize = 11.sp) })
                    FilterChip(selected = !docRich, onClick = { docRich = false }, label = { Text("只要文字", fontSize = 11.sp) })
                }
                Text(
                    if (docRich) "连图：文档里的图、表格切出来一起给模型，答得准但更贵（图算 token）。适合论文、报告、说明书。"
                    else "只要文字：只提取文字，图全丢，便宜又快。适合合同、纯文字稿、扫描件。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider()

                Text("工具审批", fontSize = 13.sp)
                Text(
                    "工具开在聊天页的「🔧 工具」开关上。每次调用都会先弹窗问好；" +
                        "勾过「以后不用问」的工具才会直接执行。当前记住的：$autoCount 个。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (autoCount > 0) {
                    TextButton(onClick = { uiStore.clearAutoTools(); autoCount = 0 }) {
                        Text("清空免问记录（恢复每次都问我）")
                    }
                }

                HorizontalDivider()

                hint?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary) }
                testResult?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary) }

                TextButton(
                    onClick = {
                        testing = true
                        testResult = null
                        scope.launch {
                            testResult = withContext(Dispatchers.IO) {
                                ConnectionTester.test(baseUrl, apiKey)
                            }
                            testing = false
                        }
                    },
                    enabled = !testing,
                ) {
                    Text(if (testing) "测试中…" else "测试连接（不花钱）")
                }

                TextButton(onClick = onOpenMcp) {
                    Text("MCP 工具服务器（连好电脑上的工具）")
                }

                TextButton(onClick = onOpenBill) {
                    Text("花费账单（钱花在哪了）")
                }

                TextButton(onClick = onExport) {
                    Text("导出当前对话（存成 Markdown）")
                }

                TextButton(onClick = onOpenTemplates) {
                    Text("提示词模板（常用问法一点就填）")
                }

                TextButton(onClick = onOpenImport) {
                    Text("导入官方历史对话（选导出的 zip）")
                }

                TextButton(onClick = onBackup) {
                    Text("备份全部对话（存成一个文件）")
                }

                TextButton(onClick = onRestore) {
                    Text("从备份恢复对话")
                }

                TextButton(
                    onClick = {
                        if (exporting) return@TextButton
                        exporting = true
                        hint = "正在打包成官方格式…"
                        scope.launch {
                            val db = ChatDatabase(uiContext)
                            val outcome = withContext(Dispatchers.IO) {
                                OfficialExporter.exportToDownloads(uiContext, db) { text -> hint = text }
                            }
                            exporting = false
                            hint = buildString {
                                append(if (outcome.ok) "✅ " else "⚠️ ")
                                append(outcome.title)
                                outcome.lines.forEach { append("\n").append(it) }
                            }
                        }
                    },
                    enabled = !exporting,
                ) {
                    Text(if (exporting) "正在导出…" else "导出成官方格式（zip 存到下载目录）")
                }

                TextButton(onClick = onOpenStorage) {
                    Text("存储与清理（谁在占空间）")
                }

                TextButton(onClick = onOpenDiagnostics) {
                    Text("诊断与日志（出问题时看这里）")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val url = ConnectionTester.normalizeBaseUrl(baseUrl)
                val cleartext = NetGuard.cleartextWarning(url, "Base URL")
                when {
                    url.startsWith("sk-") -> hint = "网址栏里是 API Key，请清空并填 https://api.deepseek.com"
                    !url.startsWith("http://") && !url.startsWith("https://") ->
                        hint = "网址要以 http:// 或 https:// 开头"

                    cleartext != null -> hint = cleartext

                    apiKey.isBlank() -> hint = "API Key 还没填"

                    else -> onSave(
                        AppSettings(
                            baseUrl = url,
                            apiKey = apiKey.trim(),
                            model = model.trim().ifEmpty { "deepseek-flash" },
                            temperature = temperature.toDouble(),
                            webSearchDefault = initial.webSearchDefault,
                            deepThinkingDefault = initial.deepThinkingDefault,
                            contextTier = tier,
                            docParseRich = docRich,
                        )
                    )
                }
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
