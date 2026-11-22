package io.github.r2r3k.fengmingharness.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 内存里留一小段运行日志（环形缓冲，最多 400 行）。
 *
 * 目的只有一个：出事的时候你能把日志导出来给我看，不用凭截图猜。
 * 只记关键事件（发请求、重试、出错、工具调用），不记录任何 Key 和正文内容。
 */
object DiagLog {

    private const val MAX_LINES = 400
    private val lines = ArrayDeque<String>()
    private val formatter = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    @Synchronized
    fun log(tag: String, message: String) {
        val time = formatter.format(Date())
        lines.addLast("$time [$tag] $message")
        while (lines.size > MAX_LINES) lines.removeFirst()
    }

    @Synchronized
    fun dump(): String =
        if (lines.isEmpty()) "（还没有日志）" else lines.joinToString("\n")

    @Synchronized
    fun clear() = lines.clear()
}
