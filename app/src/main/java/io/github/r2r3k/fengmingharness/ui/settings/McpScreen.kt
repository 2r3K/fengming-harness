package io.github.r2r3k.fengmingharness.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.r2r3k.fengmingharness.core.mcp.McpClient
import io.github.r2r3k.fengmingharness.core.mcp.McpServerConfig
import io.github.r2r3k.fengmingharness.core.mcp.McpStore
import io.github.r2r3k.fengmingharness.core.mcp.McpToolset
import io.github.r2r3k.fengmingharness.core.util.NetGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * MCP 服务器配置页。
 *
 * 只认 network 传输（streamable-http）：填一个 http://电脑IP:端口/mcp 就能用。
 * 安卓上跑不了 stdio（没法起本地进程），所以这里不提供 stdio 选项。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpScreen(onClose: () -> Unit) {
    // 手机上的返回手势先关这一页，别直接退出 App
    androidx.activity.compose.BackHandler(enabled = true) { onClose() }
    val ctx = LocalContext.current
    val store = remember { McpStore(ctx) }
    val scope = rememberCoroutineScope()

    var servers by remember { mutableStateOf(store.list()) }
    var editing by remember { mutableStateOf<McpServerConfig?>(null) }
    var testing by remember { mutableStateOf<String?>(null) }
    val results = remember { mutableStateMapOf<String, String>() }

    fun refresh() {
        servers = store.list()
    }

    fun test(config: McpServerConfig) {
        testing = config.id
        results[config.id] = "正在连…"
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                val client = McpClient(config.url, store.token(config.id))
                val info = client.connect().getOrElse { return@withContext "❌ " + friendly(it) }
                val tools = client.listTools().getOrElse { return@withContext "❌ 连上了，但列工具失败：" + friendly(it) }
                val names = tools.joinToString("、") { it.name }
                if (tools.isEmpty()) {
                    "✅ ${info.name} ${info.version} 连上了，但它一个工具都没暴露"
                } else {
                    "✅ ${info.name} ${info.version} · ${tools.size} 个工具：$names"
                }
            }
            results[config.id] = outcome
            testing = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MCP 工具服务器", fontSize = 16.sp) },
                navigationIcon = {
                    TextButton(onClick = onClose) { Text("← 返回") }
                },
                actions = {
                    TextButton(onClick = {
                        editing = McpServerConfig(id = store.newId(), name = "", url = "", enabled = true)
                    }) { Text("添加") }
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
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "MCP 是让模型调用外部工具的通用协议。填上你电脑上服务器的地址，" +
                    "聊天时打开「🔧 工具」，模型就能调用它。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "地址长这样：http://192.168.1.10:8765/mcp\n" +
                    "手机和电脑要在同一个 WiFi（电脑连手机热点也算）。安卓跑不了 stdio，只能用网络地址。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "改完服务器（增删或开关）后，聊天里马上生效；" +
                    "如果是服务器那边重启、加了新工具，最多 2 分钟后自动跟上。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            if (servers.isEmpty()) {
                Text("还没有服务器，点右上「添加」。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            servers.forEach { config ->
                Surface(
                    tonalElevation = 2.dp,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(config.name.ifBlank { "（没名字）" }, fontSize = 14.sp)
                                Text(config.url, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = config.enabled,
                                onCheckedChange = { on ->
                                    store.save(config.copy(enabled = on))
                                    McpToolset.invalidate()
                                    refresh()
                                },
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = { test(config) },
                                enabled = testing != config.id,
                            ) { Text(if (testing == config.id) "连接中…" else "测试连接") }
                            TextButton(onClick = { editing = config }) { Text("编辑") }
                            TextButton(onClick = {
                                store.delete(config.id)
                                McpToolset.invalidate()
                                refresh()
                            }) { Text("删除") }
                        }
                        results[config.id]?.let {
                            Text(
                                it,
                                fontSize = 11.sp,
                                color = if (it.startsWith("✅")) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
        }
    }

    editing?.let { config ->
        var name by remember(config.id) { mutableStateOf(config.name) }
        var url by remember(config.id) { mutableStateOf(config.url) }
        var token by remember(config.id) { mutableStateOf(store.token(config.id)) }
        var error by remember(config.id) { mutableStateOf<String?>(null) }
        var showToken by remember(config.id) { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (config.name.isBlank()) "添加 MCP 服务器" else "编辑 MCP 服务器") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it; error = null },
                        label = { Text("名字（随便起，例如 我的电脑）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it; error = null },
                        label = { Text("地址") },
                        placeholder = { Text("http://192.168.1.10:8765/mcp") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // 明文 http 只在局域网里能接受，公网地址要提醒（但允许保存，不硬拦）
                    NetGuard.cleartextWarning(url, "这个地址")?.let {
                        Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                    }
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        label = { Text("令牌（服务器没要求就留空）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (showToken) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        trailingIcon = {
                            TextButton(onClick = { showToken = !showToken }) {
                                Text(if (showToken) "隐藏" else "显示", fontSize = 11.sp)
                            }
                        },
                    )
                    error?.let {
                        Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val cleanUrl = url.trim()
                        when {
                            cleanUrl.isBlank() -> error = "地址不能空。填 http://电脑IP:端口/mcp"
                            cleanUrl.startsWith("sk-") -> error = "这看着是 API Key，不是 MCP 地址"
                            else -> {
                                val saved = config.copy(
                                    name = name.trim().ifBlank { "MCP 服务器" },
                                    url = cleanUrl,
                                )
                                store.save(saved)
                                store.saveToken(saved.id, token)
                                McpToolset.invalidate()
                                editing = null
                                refresh()
                            }
                        }
                    },
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } },
        )
    }
}

/** 把网络异常翻成人话（OkHttp 的原始提示是英文） */
private fun friendly(error: Throwable): String = when (error) {
    is java.net.ConnectException ->
        "连不上这台电脑。检查：服务器开着没？地址和端口对不对？防火墙放行了没？"
    is java.net.SocketTimeoutException ->
        "等太久了。手机和电脑是不是同一个 WiFi？"
    is java.net.UnknownHostException ->
        "地址解析不了，检查 IP 有没有写错"
    is java.net.SocketException ->
        "连接被拒绝了：${error.message ?: "网络中断"}"
    else -> error.message ?: error.javaClass.simpleName
}
