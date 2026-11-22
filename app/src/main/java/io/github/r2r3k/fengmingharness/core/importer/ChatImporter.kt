package io.github.r2r3k.fengmingharness.core.importer

import android.content.Context
import android.net.Uri
import io.github.r2r3k.fengmingharness.core.DiagLog
import io.github.r2r3k.fengmingharness.core.util.ImagePrep
import java.io.File
import java.io.InputStream

/**
 * 导入 DeepSeek 官方导出的历史对话（Android 这边的"胶水层"）。
 *
 * 真正的解析逻辑在 [ExportParser] 里，那一层**不依赖任何 android.* 类**，
 * 所以能在电脑上用真包跑单元测试（见 `app/src/test/.../ExportParserTest.kt`）。
 *
 * 这一层只负责三件事：
 *   1. 用系统的 ContentResolver 打开用户选的文件（压缩包或 json）；
 *   2. 压缩包先复制到临时文件再解析（`ZipFile` 需要真实文件，而且它走中央目录，
 *      不会像 `ZipInputStream` 那样在"STORED + 数据描述符"的条目上炸）；
 *   3. 提供 [AndroidImageSink]：把压缩包里的 webp 解码、缩小、存到手机里。
 */
object ChatImporter {

    /**
     * @param withImages 要不要把对话里的图片也取出来存好
     * @param skipAlready 已经导过的对话直接跳过（传 db.conversationExists）
     * @param onProgress 边读边报进度（在 IO 线程回调）
     */
    fun scan(
        context: Context,
        uris: List<Uri>,
        withImages: Boolean = true,
        skipAlready: (String) -> Boolean = { false },
        onProgress: (String) -> Unit = {},
    ): ImportScan {
        val sink: ImageSink? = if (withImages) AndroidImageSink(context) else null
        val parser = ExportParser(sink, skipAlready, onProgress)

        uris.forEach { uri ->
            val label = displayName(context, uri) ?: uri.lastPathSegment ?: "文件"
            try {
                if (looksLikeZip(context, uri)) {
                    val temp = copyToTemp(context, uri, label, onProgress)
                    if (temp == null) {
                        onProgress("复制「$label」失败，跳过了")
                    } else {
                        try {
                            parser.readZip(temp, label)
                        } finally {
                            runCatching { temp.delete() }
                        }
                    }
                } else {
                    val open: () -> InputStream? = {
                        runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
                    }
                    parser.readJsonDocument(open, label)
                }
            } catch (e: Throwable) {
                // 一个文件出问题不影响别的文件
                DiagLog.log("import", "读取 $label 失败：${e.message}")
            }
        }
        return parser.finish()
    }

    /** 看头两个字节是不是 PK（zip） */
    private fun looksLikeZip(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val head = ByteArray(2)
            var read = 0
            while (read < 2) {
                val step = stream.read(head, read, 2 - read)
                if (step <= 0) break
                read += step
            }
            read == 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
        } ?: false
    }.getOrDefault(false)

    /**
     * 把用户选的压缩包复制到缓存目录。
     * 为什么要复制：`ZipFile` 只认真实文件路径，而 SAF 只给流；
     * 而且大包（官方导出 100MB+）边读边解析容易在特殊条目上翻车。
     */
    private fun copyToTemp(
        context: Context,
        uri: Uri,
        label: String,
        onProgress: (String) -> Unit,
    ): File? {
        val dir = File(context.cacheDir, "import").apply { mkdirs() }
        val temp = File(dir, "upload-${System.currentTimeMillis()}.zip")
        onProgress("正在把「$label」复制一份（大包要等十几秒）…")
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            input.use { source ->
                temp.outputStream().use { target -> source.copyTo(target, 256 * 1024) }
            }
            if (temp.length() <= 0L) {
                temp.delete()
                null
            } else {
                onProgress("复制好了（${temp.length() / 1024 / 1024}MB），开始读…")
                temp
            }
        } catch (e: Throwable) {
            DiagLog.log("import", "复制 $label 失败：${e.message}")
            runCatching { temp.delete() }
            null
        }
    }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()

    /** 图片落盘：原图（webp 就存 webp，画质不打折）+ 一张小缩略图 */
    private class AndroidImageSink(private val context: Context) : ImageSink {
        override fun store(entryName: String, bytes: ByteArray): StoredImage? {
            val prepared = ImagePrep.saveOriginal(context, bytes, entryName) ?: return null
            return StoredImage(prepared.path, prepared.thumbPath)
        }
    }
}
