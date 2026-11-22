package io.github.r2r3k.fengmingharness.core.tool

import io.github.r2r3k.fengmingharness.core.kb.KnowledgeBase
import io.github.r2r3k.fengmingharness.core.mcp.McpToolset
import io.github.r2r3k.fengmingharness.core.model.ToolCall
import io.github.r2r3k.fengmingharness.core.model.ToolSpec
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 出现这些词的工具，一律当成"会改动东西"的危险工具：
 * 每次都得问，不能记成"以后不用问"。
 * 宁可多问一句，也别让模型悄悄删了东西。
 */
private val DANGER_WORDS = listOf(
    // 英文
    "delete", "remove", "write", "save", "append", "rename", "move", "copy", "mkdir", "touch",
    "exec", "shell", "command", "cmd", "powershell", "bash", "script", "python",
    "shutdown", "restart", "reboot", "kill", "format", "install", "uninstall",
    "upload", "send", "payment", "transfer", "chmod", "sudo", "registry", "regedit",
    "open_file", "set_", "update_", "create_",
    // 中文
    "删除", "移除", "写入", "保存", "追加", "重命名", "移动", "复制", "新建",
    "执行", "运行", "命令", "脚本", "关机", "重启", "格式化", "安装", "卸载",
    "上传", "发送", "支付", "转账", "修改", "创建",
)

/**
 * 手机这边能用的工具合集：自带的几个 + 电脑上 MCP 服务器提供的。
 *
 * 规矩：
 * - 参数一律当"不可信"处理，先解析再校验，绝不 eval；
 * - 结果统一转成一段文字回给模型（模型只认文字）；
 * - 出错也回文字（"工具出错：……"），让模型自己决定怎么办，不要把整轮请求搞崩。
 */
class ToolBox(
    private val knowledge: KnowledgeBase?,
    private val mcp: McpToolset? = null,
) {

    val specs: List<ToolSpec> = buildList {
        add(
            ToolSpec(
                name = "get_current_time",
                description = "查手机此刻的日期、星期、时间和时区。用户问今天几号、现在几点、星期几、今年是哪年时用。不需要参数。",
                parameters = JSONObject("""{"type":"object","properties":{},"required":[]}"""),
            )
        )
        add(
            ToolSpec(
                name = "calc",
                description = "算一个数学算式，支持 + - * / % ^ 和括号，例如 (1234*0.15+8)/3。凡是需要准确算数就用它，不要自己心算。",
                parameters = JSONObject(
                    """{"type":"object","properties":{"expression":{"type":"string","description":"要算的算式，只能有数字和 + - * / % ^ ( )"}},"required":["expression"]}"""
                ),
            )
        )
        if (knowledge != null) {
            add(
                ToolSpec(
                    name = "search_local_docs",
                    description = "在我手机的本地资料库（我选的文件夹里的 txt/md 等文档）里搜相关片段。用户提到'我的资料''之前存的笔记''本地文件'时用。",
                    parameters = JSONObject(
                        """{"type":"object","properties":{"query":{"type":"string","description":"要找的关键词或问题"}},"required":["query"]}"""
                    ),
                )
            )
        }
        // 电脑上 MCP 服务器提供的工具，一并交给模型
        mcp?.specs?.let { addAll(it) }
    }

    /** 给人看的工具名 */
    fun label(name: String): String = when (name) {
        "get_current_time" -> "看时间"
        "calc" -> "算数"
        "search_local_docs" -> "搜本地资料"
        else -> mcp?.displayName(name) ?: name
    }

    /**
     * 这个工具是不是"会改动东西"的危险工具。
     *
     * 保守原则：只要名字或说明里出现写/删/执行/关机/联网发送之类的词，就算危险；
     * 甚至压根没找到定义（说明来源不可信）也算危险。
     * 危险工具的后果：每次都必须弹窗确认，不能勾"以后不用问"。
     */
    fun isDangerous(name: String): Boolean {
        val spec = specs.firstOrNull { it.name == name } ?: return true
        val text = (spec.name + " " + spec.description).lowercase()
        return DANGER_WORDS.any { text.contains(it) }
    }

    /** 参数的简短说明，给人看 */
    fun describeArgs(call: ToolCall): String {
        val obj = runCatching { JSONObject(call.arguments.ifBlank { "{}" }) }.getOrNull() ?: return ""
        val keys = obj.keys()
        if (!keys.hasNext()) return ""
        val sb = StringBuilder()
        while (keys.hasNext()) {
            val key = keys.next()
            if (sb.isNotEmpty()) sb.append("，")
            sb.append(key).append(" = ").append(obj.optString(key).take(60))
        }
        return sb.toString()
    }

    /** 真正干活。抛异常由调用方兜住 */
    fun run(call: ToolCall): String {
        if (call.name !in specs.map { it.name }) throw IllegalArgumentException("没有这个工具：${call.name}")
        val args = runCatching { JSONObject(call.arguments.ifBlank { "{}" }) }
            .getOrElse { throw IllegalArgumentException("参数不是合法的 JSON") }

        return when (call.name) {
            "get_current_time" -> currentTime()
            "calc" -> {
                val expression = args.optString("expression").trim()
                if (expression.isEmpty()) throw IllegalArgumentException("没给算式")
                if (expression.length > 200) throw IllegalArgumentException("算式太长")
                val value = Calculator(expression).evaluate()
                "$expression = ${formatNumber(value)}"
            }
            "search_local_docs" -> {
                val query = args.optString("query").trim()
                if (query.isEmpty()) throw IllegalArgumentException("没给关键词")
                val kb = knowledge ?: throw IllegalArgumentException("本地资料库还没建索引")
                val hits = kb.search(query, 4)
                if (hits.isEmpty()) {
                    "本地资料里没找到和「$query」相关的片段。"
                } else {
                    hits.mapIndexed { index, hit ->
                        "[${index + 1}] 文件：${hit.docName}\n${hit.text.take(800)}"
                    }.joinToString("\n\n")
                }
            }
            else -> {
                // 不是自带的，就转给电脑上的 MCP 服务器
                val remote = mcp ?: throw IllegalArgumentException("没有这个工具：${call.name}")
                remote.call(call.name, call.arguments)
            }
        }
    }

    private fun currentTime(): String {
        val now = ZonedDateTime.now()
        val formatter = DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE HH:mm", Locale.CHINA)
        return now.format(formatter) + "（时区 " + now.zone.id + "，UTC" + now.offset + "）"
    }

    private fun formatNumber(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return "算不出来（不是有效数字）"
        if (value == value.toLong().toDouble()) return value.toLong().toString()
        return BigDecimal(value)
            .setScale(8, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
    }
}

/**
 * 递归下降算算式。只认数字、括号和 + - * / % ^，别的字符一律报错
 */internal class Calculator(private val src: String) {

    private var pos = 0

    fun evaluate(): Double {
        val value = expression()
        skipSpaces()
        if (pos < src.length) throw IllegalArgumentException("算式里有看不懂的字符：'${src[pos]}'")
        return value
    }

    private fun skipSpaces() {
        while (pos < src.length && src[pos].isWhitespace()) pos++
    }

    private fun expression(): Double {
        var value = term()
        while (true) {
            skipSpaces()
            when {
                take('+') -> value += term()
                take('-') -> value -= term()
                else -> return value
            }
        }
    }

    private fun term(): Double {
        var value = power()
        while (true) {
            skipSpaces()
            when {
                take('*') -> value *= power()
                take('/') -> {
                    val divisor = power()
                    if (divisor == 0.0) throw IllegalArgumentException("除数不能是 0")
                    value /= divisor
                }
                take('%') -> {
                    val divisor = power()
                    if (divisor == 0.0) throw IllegalArgumentException("除数不能是 0")
                    value %= divisor
                }
                else -> return value
            }
        }
    }

    private fun power(): Double {
        val base = unary()
        skipSpaces()
        return if (take('^')) Math.pow(base, power()) else base
    }

    private fun unary(): Double {
        skipSpaces()
        return when {
            take('-') -> -unary()
            take('+') -> unary()
            else -> atom()
        }
    }

    private fun atom(): Double {
        skipSpaces()
        if (take('(')) {
            val value = expression()
            skipSpaces()
            if (!take(')')) throw IllegalArgumentException("括号没配对")
            return value
        }
        val start = pos
        while (pos < src.length && (src[pos].isDigit() || src[pos] == '.')) pos++
        if (pos == start) throw IllegalArgumentException("这里应该是个数字")
        return src.substring(start, pos).toDoubleOrNull()
            ?: throw IllegalArgumentException("这串不是数字：${src.substring(start, pos)}")
    }

    private fun take(c: Char): Boolean {
        if (pos < src.length && src[pos] == c) {
            pos++
            return true
        }
        return false
    }
}
