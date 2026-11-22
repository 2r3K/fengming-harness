package io.github.r2r3k.fengmingharness.core.kb

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * 扫描用户授权目录（用系统 SAF 授权，不申请整个存储权限），
 * 把纯文本文件切块塞进 KnowledgeBase。
 *
 * 刻意不做 PDF：手机端解析慢、要额外库、扫描版还读不出字。
 * 建议在电脑上先把 PDF 转成 md/txt 再放进来。
 */
object KbIndexer {

    /** 能读的扩展名 */
    private val TEXT_EXTENSIONS = setOf(
        "txt", "md", "markdown", "json", "csv", "tsv", "log", "ini", "cfg", "conf",
        "yml", "yaml", "xml", "html", "htm",
        "kt", "kts", "java", "py", "js", "ts", "c", "h", "cpp", "hpp", "go", "rs", "sh", "ps1", "sql",
    )

    private const val MAX_FILE_BYTES = 2L * 1024 * 1024      // 单文件 2MB 上限
    private const val MAX_TOTAL_CHARS = 12_000_000L          // 全库 1200 万字上限
    private const val CHUNK_CHARS = 900

    data class Progress(
        val scanned: Int,
        val indexed: Int,
        val skipped: Int,
        val current: String,
    )

    suspend fun rebuild(
        context: Context,
        treeUri: Uri,
        onProgress: (Progress) -> Unit,
    ): KbStats = withContext(Dispatchers.IO) {
        val kb = KnowledgeBase(context)
        val startedAt = kb.startIndex()
        var scanned = 0
        var indexed = 0
        var skipped = 0
        var totalChars = 0L

        fun walk(parentDocId: String) {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
            val cursor: Cursor = context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                ),
                null, null, null,
            ) ?: return

            cursor.use { c ->
                while (c.moveToNext()) {
                    if (totalChars >= MAX_TOTAL_CHARS) return

                    val docId = c.getString(0) ?: continue
                    val name = c.getString(1) ?: ""
                    val mime = c.getString(2) ?: ""
                    val size = if (c.isNull(3)) 0L else c.getLong(3)

                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        walk(docId)
                        continue
                    }

                    scanned++
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (ext !in TEXT_EXTENSIONS) {
                        skipped++
                        continue
                    }
                    if (size > MAX_FILE_BYTES) {
                        skipped++
                        continue
                    }

                    onProgress(Progress(scanned, indexed, skipped, name))

                    val text = runCatching { readText(context, treeUri, docId) }.getOrNull()
                    if (text.isNullOrBlank()) {
                        skipped++
                        continue
                    }

                    val docRowId = kb.addDoc(name, docId, size, System.currentTimeMillis())
                    val chunks = chunk(text)
                    chunks.forEachIndexed { ord, piece -> kb.addChunk(docRowId, ord, piece) }
                    totalChars += text.length
                    indexed++
                }
            }
        }

        walk(DocumentsContract.getTreeDocumentId(treeUri))
        kb.finishIndex(startedAt)
        onProgress(Progress(scanned, indexed, skipped, "完成"))
        kb.stats()
    }

    private fun readText(context: Context, treeUri: Uri, docId: String): String? {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        val stream: InputStream = context.contentResolver.openInputStream(uri) ?: return null
        return stream.use { input ->
            val buffer = ByteArray(64 * 1024)
            val out = StringBuilder()
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                total += read
                if (total > MAX_FILE_BYTES) break
                out.append(String(buffer, 0, read, Charsets.UTF_8))
            }
            out.toString()
        }
    }

    /** 按段落拼到接近 CHUNK_CHARS 再切，尽量不把一句话切断 */
    fun chunk(text: String): List<String> {
        val normalized = text.replace("\r\n", "\n").replace('\u0000', ' ')
        val paragraphs = normalized.split(Regex("\n{2,}")).map { it.trim() }.filter { it.isNotEmpty() }
        val pieces = mutableListOf<String>()
        val builder = StringBuilder()

        for (p in paragraphs) {
            if (builder.length + p.length + 1 > CHUNK_CHARS) {
                if (builder.isNotEmpty()) {
                    pieces += builder.toString()
                    builder.setLength(0)
                }
                // 单段本身就超长：硬切
                if (p.length > CHUNK_CHARS) {
                    var index = 0
                    while (index < p.length) {
                        val end = minOf(index + CHUNK_CHARS, p.length)
                        pieces += p.substring(index, end)
                        index = end
                    }
                    continue
                }
            }
            if (builder.isNotEmpty()) builder.append('\n')
            builder.append(p)
        }
        if (builder.isNotEmpty()) pieces += builder.toString()

        // 太短的碎片（<40 字）并到上一块，减少噪声
        val merged = mutableListOf<String>()
        for (piece in pieces) {
            if (piece.length < 40 && merged.isNotEmpty() && merged.last().length + piece.length <= CHUNK_CHARS * 2) {
                merged[merged.size - 1] = merged.last() + "\n" + piece
            } else {
                merged += piece
            }
        }
        return merged.ifEmpty { listOf(normalized.take(CHUNK_CHARS)) }
    }
}
