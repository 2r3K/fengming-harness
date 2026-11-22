package io.github.r2r3k.fengmingharness.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/* ------------------------------------------------------------------ *
 *  一个"够用就好"的 Markdown 渲染器：不引库，纯 Kotlin + Compose 自己画。
 *  支持：# 标题、``` 代码块、- 列表、1. 有序列表、> 引用、--- 分隔线、
 *        **加粗**、*斜体*、`行内代码`、[文字](链接)
 *  不支持的语法一律当普通文字显示，不会崩、不会丢字。
 * ------------------------------------------------------------------ */

sealed interface MdBlock {
    data class Code(val lang: String, val code: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Bullet(val items: List<String>) : MdBlock
    data class Ordered(val items: List<String>) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data object Rule : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
}

/** 把 | a | b | 拆成 ["a", "b"]（去掉首尾的空竖线） */
private fun splitTableRow(line: String): List<String> {
    var text = line.trim()
    if (text.startsWith("|")) text = text.substring(1)
    if (text.endsWith("|")) text = text.dropLast(1)
    return text.split('|').map { it.trim() }
}

private val BULLET = Regex("^[-*+]\\s+")
private val ORDERED = Regex("^\\d+[.)]\\s+")
private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
private val RULE = Regex("^(-{3,}|\\*{3,})$")
private val FENCE = "```"

fun parseMarkdown(source: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = source.replace("\r\n", "\n").split('\n')
    val paragraph = mutableListOf<String>()
    var i = 0

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks += MdBlock.Paragraph(paragraph.joinToString("\n").trim())
            paragraph.clear()
        }
    }

    while (i < lines.size) {
        val raw = lines[i]
        val line = raw.trimStart()

        when {
            line.startsWith(FENCE) -> {
                flushParagraph()
                val lang = line.removePrefix(FENCE).trim()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith(FENCE)) {
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(lines[i])
                    i++
                }
                if (i < lines.size) i++   // 跳过结尾的 ```
                blocks += MdBlock.Code(lang, code.toString())
            }

            HEADING.matches(line) -> {
                flushParagraph()
                val m = HEADING.find(line)!!
                blocks += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2].trim())
                i++
            }

            RULE.matches(line) -> {
                flushParagraph()
                blocks += MdBlock.Rule
                i++
            }

            line.startsWith(">") -> {
                flushParagraph()
                val quoted = mutableListOf<String>()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                    quoted += lines[i].trimStart().removePrefix(">").trimStart()
                    i++
                }
                blocks += MdBlock.Quote(quoted.joinToString("\n"))
            }

            BULLET.containsMatchIn(line) -> {
                flushParagraph()
                val items = mutableListOf<String>()
                while (i < lines.size && BULLET.containsMatchIn(lines[i].trimStart())) {
                    items += lines[i].trimStart().replaceFirst(BULLET, "")
                    i++
                }
                blocks += MdBlock.Bullet(items)
            }

            ORDERED.containsMatchIn(line) -> {
                flushParagraph()
                val items = mutableListOf<String>()
                while (i < lines.size && ORDERED.containsMatchIn(lines[i].trimStart())) {
                    items += lines[i].trimStart().replaceFirst(ORDERED, "")
                    i++
                }
                blocks += MdBlock.Ordered(items)
            }

            line.startsWith("|") -> {
                flushParagraph()
                // 第一行是表头，第二行是 |---|---| 分隔线，后面是数据行
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && lines[i].trimStart().startsWith("|")) {
                    val cells = splitTableRow(lines[i].trimStart())
                    // 分隔线（只含 - : 和空格）不当数据
                    val isSeparator = cells.isNotEmpty() &&
                        cells.all { cell -> cell.all { it == '-' || it == ':' || it == ' ' } }
                    if (!isSeparator) rows += cells
                    i++
                }
                if (rows.isNotEmpty()) {
                    blocks += MdBlock.Table(rows.first(), rows.drop(1))
                }
            }

            line.isBlank() -> {
                flushParagraph()
                i++
            }

            else -> {
                paragraph += raw
                i++
            }
        }
    }
    flushParagraph()
    return blocks
}

private val INLINE = Regex(
    "\\*\\*(.+?)\\*\\*" +          // 1 加粗
        "|`([^`]+)`" +              // 2 行内代码
        "|\\*(.+?)\\*" +            // 3 斜体
        "|\\[([^\\]]+)\\]\\(([^)]+)\\)"   // 4 文字 / 5 链接
)

private fun inline(text: String, linkColor: Color): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    for (m in INLINE.findAll(text)) {
        if (m.range.first > cursor) append(text.substring(cursor, m.range.first))
        val g = m.groupValues
        when {
            g[1].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(g[1]) }
            g[2].isNotEmpty() -> withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = Color(0x22888888),
                )
            ) { append(g[2]) }
            g[3].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[3]) }
            g[4].isNotEmpty() -> withLink(
                LinkAnnotation.Url(
                    url = g[5],
                    styles = TextLinkStyles(
                        SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
                    ),
                )
            ) { append(g[4]) }
        }
        cursor = m.range.last + 1
    }
    if (cursor < text.length) append(text.substring(cursor))
}

fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    manager.setPrimaryClip(ClipData.newPlainText("回答", text))
    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
}

/** 表格：能左右滑，表头加粗，行与行之间有细线 */
@Composable
private fun TableBlock(
    block: MdBlock.Table,
    linkColor: Color,
    bodyColor: Color,
) {
    val scroll = rememberScrollState()
    val border = MaterialTheme.colorScheme.outlineVariant
    Column(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .border(1.dp, border, RoundedCornerShape(6.dp)),
    ) {
        Row(Modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
            block.header.forEach { cell ->
                Text(
                    text = inline(cell, linkColor),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = bodyColor,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
        block.rows.forEach { row ->
            HorizontalDivider(color = border)
            Row {
                row.forEach { cell ->
                    Text(
                        text = inline(cell, linkColor),
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = bodyColor,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {    val blocks = remember(text) { parseMarkdown(text) }
    val linkColor = MaterialTheme.colorScheme.primary
    val bodyColor = MaterialTheme.colorScheme.onSurface

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Code -> CodeBlock(block)
                is MdBlock.Heading -> Text(
                    text = inline(block.text, linkColor),
                    fontSize = when (block.level) {
                        1 -> 20.sp
                        2 -> 18.sp
                        else -> 16.sp
                    },
                    fontWeight = FontWeight.Bold,
                    color = bodyColor,
                )

                is MdBlock.Bullet -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    block.items.forEach { item ->
                        Row {
                            Text("•", color = bodyColor)
                            Spacer(Modifier.width(6.dp))
                            Text(inline(item, linkColor), fontSize = 15.sp, lineHeight = 22.sp, color = bodyColor)
                        }
                    }
                }

                is MdBlock.Ordered -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    block.items.forEachIndexed { index, item ->
                        Row {
                            Text("${index + 1}.", color = bodyColor)
                            Spacer(Modifier.width(6.dp))
                            Text(inline(item, linkColor), fontSize = 15.sp, lineHeight = 22.sp, color = bodyColor)
                        }
                    }
                }

                is MdBlock.Quote -> Row {
                    Text("▎", color = MaterialTheme.colorScheme.primary)
                    Text(
                        text = inline(block.text, linkColor),
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                MdBlock.Rule -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                is MdBlock.Table -> TableBlock(block, linkColor, bodyColor)

                is MdBlock.Paragraph -> Text(
                    text = inline(block.text, linkColor),
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    color = bodyColor,
                )
            }
        }
    }
}

@Composable
private fun CodeBlock(block: MdBlock.Code) {
    val context = LocalContext.current
    val background = Color(0xFF1E1E22)
    val foreground = Color(0xFFE8E8EA)

    Surface(
        color = background,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(8.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    text = block.lang.ifBlank { "代码" },
                    fontSize = 11.sp,
                    color = Color(0xFF9AA0A6),
                    modifier = Modifier.padding(start = 2.dp),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "复制",
                    fontSize = 11.sp,
                    color = Color(0xFF9AA0A6),
                    modifier = Modifier
                        .clickable { copyToClipboard(context, block.code) }
                        .padding(horizontal = 4.dp),
                )
            }
            Spacer(Modifier.width(4.dp))
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                Text(
                    text = block.code,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = foreground,
                )
            }
        }
    }
}
