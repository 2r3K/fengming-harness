package io.github.r2r3k.fengmingharness.core.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 官方账户余额里的一种货币 */
data class BalanceLine(
    val currency: String,
    val total: String,
    val granted: String,
    val toppedUp: String,
)

/** 官方账户余额（查一次不花钱，这个接口不消耗 token） */
data class BalanceResult(
    val available: Boolean,
    val lines: List<BalanceLine>,
    val fetchedAt: Long = System.currentTimeMillis(),
) {
    val cny: BalanceLine? get() = lines.firstOrNull { it.currency.equals("CNY", ignoreCase = true) }
    val usd: BalanceLine? get() = lines.firstOrNull { it.currency.equals("USD", ignoreCase = true) }
    val primary: BalanceLine? get() = cny ?: usd ?: lines.firstOrNull()
}

/**
 * 查官方账户余额。
 *
 * 官方接口：`GET https://api.deepseek.com/user/balance`
 * 注意这条路**不在 /v1 下面**，所以这里会把 baseUrl 末尾的 /v1 去掉。
 *
 * 查这个接口不消耗 token，可以放心点刷新；但也没必要一直查，5 分钟内复用缓存。
 */
object BalanceApi {

    private const val CACHE_MS = 5 * 60 * 1000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var cached: BalanceResult? = null

    /** 5 分钟内查过的结果 */
    fun cached(): BalanceResult? =
        cached?.takeIf { System.currentTimeMillis() - it.fetchedAt < CACHE_MS }

    private fun remember(result: BalanceResult) {
        cached = result
    }

    fun invalidate() {
        cached = null
    }

    suspend fun fetch(baseUrl: String, apiKey: String): Result<BalanceResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val key = apiKey.trim()
                require(key.isNotBlank()) { "还没填 API Key" }

                val request = Request.Builder()
                    .url(endpoint(baseUrl))
                    .get()
                    .header("Authorization", "Bearer $key")
                    .header("Accept", "application/json")
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw IllegalStateException(friendly(response.code, body))
                    }
                    val result = parse(body)
                    remember(result)
                    result
                }
            }
        }

    /** baseUrl 可能是 https://api.deepseek.com 或 .../v1，余额接口在根目录 */
    fun endpoint(baseUrl: String): String {
        var base = baseUrl.trim().trimEnd('/')
        if (base.isBlank()) base = "https://api.deepseek.com"
        if (base.endsWith("/v1")) base = base.removeSuffix("/v1")
        return "$base/user/balance"
    }

    private fun parse(body: String): BalanceResult {
        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: throw IllegalStateException("返回的不是 JSON，这个地址可能不是官方 API")
        val available = root.optBoolean("is_available", true)
        val array = root.optJSONArray("balance_infos")
        val lines = mutableListOf<BalanceLine>()
        if (array != null) {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                lines += BalanceLine(
                    currency = item.optString("currency").ifBlank { "CNY" },
                    total = item.optString("total_balance").ifBlank { "0.00" },
                    granted = item.optString("granted_balance").ifBlank { "0.00" },
                    toppedUp = item.optString("topped_up_balance").ifBlank { "0.00" },
                )
            }
        }
        if (lines.isEmpty()) throw IllegalStateException("接口没返回余额数据，可能是 Key 不对或地址不对")
        return BalanceResult(available, lines)
    }

    private fun friendly(code: Int, body: String): String = when (code) {
        400 -> "请求被拒（400）。填的可能是 Key 或地址不对"
        401 -> "Key 不对或已被停用（401）。去设置里核对一下 API Key"
        402 -> "账户没钱了（402）"
        403 -> "这个 Key 没有查余额的权限（403）"
        404 -> "这个地址没有余额接口（404）。可能不是官方 API，或改过地址"
        422 -> "参数不对（422）"
        429 -> "查得太频繁了（429），等一下再试"
        in 500..599 -> "DeepSeek 服务端出问题（$code），等一会儿再试"
        else -> "查询失败（HTTP $code）${body.take(120).takeIf { it.isNotBlank() }?.let { "：$it" } ?: ""}"
    }
}
