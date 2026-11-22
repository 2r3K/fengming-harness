package io.github.r2r3k.fengmingharness.ui.bill

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.r2r3k.fengmingharness.core.SettingsStore
import io.github.r2r3k.fengmingharness.core.store.ChatDatabase
import io.github.r2r3k.fengmingharness.core.store.MonthUsage
import io.github.r2r3k.fengmingharness.core.util.BalanceApi
import io.github.r2r3k.fengmingharness.core.util.BalanceResult
import io.github.r2r3k.fengmingharness.core.util.Pricing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 账单页：钱花在哪了。
 *
 * 上半部分是**官方账户余额**（查 DeepSeek 接口，不花 token），
 * 下半部分是**本机估算花费**（每条回答服务端返回的 token × 单价）。
 * 两者不是一回事，界面上会分开写清楚。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillScreen(onClose: () -> Unit) {
    androidx.activity.compose.BackHandler(enabled = true) { onClose() }
    val ctx = LocalContext.current
    val db = remember { ChatDatabase(ctx) }
    val store = remember { SettingsStore(ctx) }

    var months by remember { mutableStateOf<List<MonthUsage>>(emptyList()) }
    var days by remember { mutableStateOf<List<MonthUsage>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    // ---- 官方余额 ----
    var balance by remember { mutableStateOf(BalanceApi.cached()) }
    var balanceError by remember { mutableStateOf<String?>(null) }
    var balanceLoading by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        val data = withContext(Dispatchers.IO) {
            runCatching { db.usageByMonth(12) to db.usageByDay(14) }
                .getOrDefault(emptyList<MonthUsage>() to emptyList())
        }
        months = data.first
        days = data.second
        loading = false
    }

    LaunchedEffect(refreshTick) {
        val settings = store.load()
        if (settings.apiKey.isBlank()) {
            balanceError = "还没填 API Key（右上角设置里填）"
            return@LaunchedEffect
        }
        // 刚查过的用缓存，别一进页面就打接口
        if (refreshTick == 0 && BalanceApi.cached() != null) return@LaunchedEffect
        balanceLoading = true
        balanceError = null
        val result = BalanceApi.fetch(settings.baseUrl, settings.apiKey)
        balanceLoading = false
        result.onSuccess { balance = it }
            .onFailure { balanceError = it.message ?: "查询失败" }
    }

    val totalCost = months.sumOf { it.cost }
    val totalTokens = months.sumOf { it.tokens }
    val totalRequests = months.sumOf { it.requests }
    val thisMonth = months.firstOrNull()
    val peakNow = Pricing.isPeak()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("花费账单", fontSize = 16.sp) },
                navigationIcon = { TextButton(onClick = onClose) { Text("← 返回") } },
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
            // ---------- 官方账户余额 ----------
            Surface(
                tonalElevation = 2.dp,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row {
                        Text(
                            "官方账户余额",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = { refreshTick++ },
                            enabled = !balanceLoading,
                        ) { Text(if (balanceLoading) "查询中…" else "刷新", fontSize = 11.sp) }
                    }

                    val error = balanceError
                    when {
                        error != null -> {
                            Text(error, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                            balance?.primary?.let { line ->
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "上次查到的：${symbol(line.currency)}${line.total}（旧数据）",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        balance == null -> Text(
                            if (balanceLoading) "正在查…" else "还没查过，点右上「刷新」查一下",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        else -> {
                            val data = balance!!
                            val line = data.primary
                            if (line != null) {
                                Text(
                                    "${symbol(line.currency)}${line.total}",
                                    fontSize = 26.sp,
                                    color = if (data.available) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "可用余额 · 赠送 ${line.granted} · 充值 ${line.toppedUp}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (!data.available) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "⚠️ 余额不足，API 已经不能用了，去官网充值",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            // 同时有人民币和美元就把另一条也列出来
                            data.lines.filter { it !== line }.forEach { other ->
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "${symbol(other.currency)}${other.total}（赠送 ${other.granted} · 充值 ${other.toppedUp}）",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "查于 ${clockText(data.fetchedAt)} · 这个查询不花 token（5 分钟内不重复查）",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                "↓ 下面是本机估算（按每条回答的 token 算的，和官方账单可能差几分钱）",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))

            if (loading) {
                Text("正在统计…", fontSize = 12.sp)
                return@Column
            }

            // ---------- 本机估算累计 ----------
            Surface(
                tonalElevation = 2.dp,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("本机估算一共花了", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(Pricing.formatCny(totalCost), fontSize = 26.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "共 ${totalRequests} 次回答 · ${formatTokens(totalTokens)} token",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    thisMonth?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "其中 ${it.month} 花了 ${Pricing.formatCny(it.cost)}（${formatTokens(it.tokens)} token）",
                            fontSize = 11.sp,
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            if (peakNow) {
                Text(
                    "⏰ 现在是用量高峰：价格是平时的 2 倍。不急的话，北京时间 18:00 后再问能省一半。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Text(
                    "现在是非高峰时段（半价）。高峰是工作日北京时间 09:00–12:00 和 14:00–18:00。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(14.dp))
            Text("按月（本机估算）", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            if (months.isEmpty()) {
                Text("还没有花钱记录。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            months.forEach { item ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(item.month, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text(
                        "${item.requests} 次 · ${formatTokens(item.tokens)} token",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1.4f),
                    )
                    Text(Pricing.formatCny(item.cost), fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(14.dp))
            Text("最近 14 天（本机估算）", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            if (days.isEmpty()) {
                Text("这 14 天没花钱。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            days.forEach { item ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(item.month, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text(
                        "${item.requests} 次",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(Pricing.formatCny(item.cost), fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "说明：官方余额是从 DeepSeek 接口实时查的（最准，充值后点刷新就变）；" +
                    "下面的 ¥ 是本机按每条回答服务端返回的 token 估算的（含缓存折扣和高峰翻倍），" +
                    "两者对不上是正常的，以官网后台账单为准。",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun symbol(currency: String): String =
    if (currency.equals("USD", ignoreCase = true)) "$" else "¥"

private fun clockText(millis: Long): String =
    runCatching { SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(millis)) }
        .getOrDefault("刚刚")

private fun formatTokens(tokens: Int): String = when {
    tokens >= 1_000_000 -> "%.1fM".format(tokens / 1_000_000.0)
    tokens >= 1_000 -> "%.1fK".format(tokens / 1_000.0)
    else -> tokens.toString()
}
