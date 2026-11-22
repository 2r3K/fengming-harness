package io.github.r2r3k.fengmingharness.core.util

import io.github.r2r3k.fengmingharness.core.model.Usage
import java.util.Calendar
import java.util.TimeZone

/**
 * 按官方价估算花费（人民币）。
 * deepseek-flash：输入（缓存命中）$0.003 / 100万 token，输入（未命中）$0.15，输出 $0.6（非高峰价）
 * 高峰时段是 UTC 01:00–04:00 和 06:00–10:00（周一至周五），高峰价格翻倍。
 * 这里只做估算，界面上要标"约"。
 */
object Pricing {

    private const val USD_TO_CNY = 7.2

    fun isPeak(now: Long = System.currentTimeMillis()): Boolean {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        calendar.timeInMillis = now
        val day = calendar.get(Calendar.DAY_OF_WEEK)
        val isWorkday = day != Calendar.SATURDAY && day != Calendar.SUNDAY
        if (!isWorkday) return false
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        return hour in 1..3 || hour in 6..9
    }

    /** 估算这次请求花了多少人民币 */
    fun estimateCny(usage: Usage, peak: Boolean = isPeak()): Double {
        // 官方定义：input_tokens 就是"未命中缓存"的部分，缓存量单独在 cachedTokens 里
        val missTokens = usage.inputTokens.coerceAtLeast(0)
        val hitPerM = if (peak) 0.006 else 0.003
        val missPerM = if (peak) 0.30 else 0.15
        val outPerM = if (peak) 1.20 else 0.60

        val usd = missTokens / 1_000_000.0 * missPerM +
            usage.cachedTokens / 1_000_000.0 * hitPerM +
            usage.outputTokens / 1_000_000.0 * outPerM
        return usd * USD_TO_CNY
    }

    fun formatCny(value: Double): String = when {
        value <= 0 -> "¥0"
        value < 0.01 -> "¥" + "%.4f".format(value)
        value < 1 -> "¥" + "%.3f".format(value)
        else -> "¥" + "%.2f".format(value)
    }
}
