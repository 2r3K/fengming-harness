package io.github.r2r3k.fengmingharness.core.exporter

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import io.github.r2r3k.fengmingharness.core.DiagLog
import io.github.r2r3k.fengmingharness.core.importer.ExportParser
import io.github.r2r3k.fengmingharness.core.importer.ImageSink
import io.github.r2r3k.fengmingharness.core.importer.StoredImage
import io.github.r2r3k.fengmingharness.core.store.ChatDatabase
import org.json.JSONArray
import java.io.File
import java.time.LocalDate

/**
 * 把本机数据库里的对话，导成 **DeepSeek 官方导出格式** 的 zip，存到手机的「下载」目录。
 *
 * 流程：
 *   1. 读库（会话 → 消息 → 图片路径 / 来源 / 思考过程）
 *   2. [OfficialExportWriter] 写到缓存目录的临时文件（纯 Kotlin，可单测）
 *   3. **自检**：用我们自己的官方格式解析器把刚写出来的包再读一遍，
 *      核对对话数 / 消息数 / 图片数 / 每条 export_path 都真的存在
 *   4. 复制到「下载」目录（Android 10+ 走 MediaStore，不需要任何权限）
 */
object OfficialExporter {

    data class Outcome(
        val ok: Boolean,
        val title: String,
        val lines: List<String>,
        val uri: Uri? = null,
        val localPath: String? = null,
        val fileName: String? = null,
    )

    /** 官方导出文件名的样子：deepseek_data-2026-11-21.zip */
    fun defaultFileName(today: LocalDate = LocalDate.now()): String = "deepseek_data-$today.zip"

    /**
     * @param onProgress 进度文字（在调用线程回调，调用方记得切到主线程更新界面）
     */
    fun exportToDownloads(
        context: Context,
        db: ChatDatabase,
        onProgress: (String) -> Unit = {},
    ): Outcome {
        val state = State()
        val conversations = try {
            collect(db, state, onProgress)
        } catch (e: Throwable) {
            DiagLog.log("export", "读库失败：${e.message}")
            return Outcome(false, "导出失败", listOf("读数据库出错：${e.message ?: "原因不明"}"))
        }
        if (conversations.isEmpty()) {
            return Outcome(false, "没有可导出的对话", listOf("本机还没有对话记录"))
        }

        val dir = File(context.cacheDir, "export").apply { mkdirs() }
        val temp = File(dir, "official-export.zip")
        var result: ExportResult? = null
        try {
            onProgress("正在打包…")
            temp.outputStream().buffered().use { out ->
                result = OfficialExportWriter.write(out, conversations, onProgress = onProgress)
            }
        } catch (e: Throwable) {
            DiagLog.log("export", "打包失败：${e.message}")
            runCatching { temp.delete() }
            return Outcome(false, "打包失败", listOf("${e.message ?: "原因不明"}"))
        }

        // 自检：用官方格式解析器把刚写的包读回来
        onProgress("正在自检…")
        val check = selfCheck(temp, conversations.size, result, state)

        // 复制到「下载」
        onProgress("正在保存到下载目录…")
        val fileName = defaultFileName()
        val saved = try {
            saveToDownloads(context, temp, fileName)
        } catch (e: Throwable) {
            DiagLog.log("export", "保存失败：${e.message}")
            null
        }
        runCatching { temp.delete() }

        if (saved == null) {
            return Outcome(
                ok = false,
                title = "打包好了，但存不进下载目录",
                lines = buildList {
                    result?.let { add("已打包：${it.conversations} 个对话 / ${it.messages} 条消息 / ${it.images} 张图片") }
                    addAll(check)
                    add("（文件在缓存里被清掉了，请再试一次）")
                },
            )
        }

        val lines = buildList {
            result?.let {
                add("对话 ${it.conversations} 个 · 消息 ${it.messages} 条 · 图片 ${it.images} 张（${it.imageBytes / 1024 / 1024}MB）")
                if (it.emptyImages > 0) add("有 ${it.emptyImages} 张图读不出来，没打进去")
            }
            addAll(check)
            saved.path?.let { add("位置：$it") }
        }
        DiagLog.log("export", "导出完成：$fileName")
        return Outcome(true, "导出完成", lines, saved.uri, saved.path, fileName)
    }

    // ---------------------------------------------------------------
    // 读库 → 导出模型
    // ---------------------------------------------------------------

    /** 一次导出里的小统计（不用对象属性，避免两次导出互相串数据） */
    private class State {
        var docsSkipped = 0
        var missingImages = 0
    }

    private fun collect(
        db: ChatDatabase,
        state: State,
        onProgress: (String) -> Unit,
    ): List<ExportConversation> {
        val list = db.listConversations()
        val out = mutableListOf<ExportConversation>()
        list.forEachIndexed { index, conversation ->
            val rows = db.loadMessages(conversation.id)
                .filter { it.role == "user" || it.role == "assistant" }
            if (rows.isEmpty()) return@forEachIndexed

            val messages = rows.mapNotNull { row ->
                val images = parseImages(row.images)
                state.docsSkipped += images.count { it.kind == "doc" }
                val exportImages = images
                    .filter { it.kind != "doc" && it.path.isNotBlank() }
                    .mapNotNull { image -> toExportImage(image, state) }

                val sources = parseSources(row.sources)
                ExportMessage(
                    role = row.role,
                    // 文档类附件只有摘要文字，正文已经拼在消息里了，不假装能导出文件
                    text = row.content,
                    reasoning = row.reasoning,
                    sources = sources,
                    images = exportImages,
                    time = row.createdAt,
                    tokenUsage = row.tokensIn + row.tokensOut,
                )
            }
            if (messages.isEmpty()) return@forEachIndexed

            val updatedAt = conversation.updatedAt
            out += ExportConversation(
                id = conversation.id,
                title = conversation.title.ifBlank { "未命名对话" },
                createdAt = messages.firstOrNull { it.time > 0 }?.time ?: updatedAt,
                updatedAt = messages.lastOrNull { it.time > 0 }?.time ?: updatedAt,
                messages = messages,
            )
            onProgress("已收集 ${index + 1}/${list.size} 个对话")
        }
        return out
    }

    private class LocalImage(
        val fileId: String,
        val path: String,
        val kind: String,
        val name: String,
    )

    private fun parseImages(raw: String?): List<LocalImage> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                LocalImage(
                    fileId = obj.optString("fileId").orEmpty(),
                    path = obj.optString("path").orEmpty(),
                    kind = obj.optString("kind", "image").ifBlank { "image" },
                    name = obj.optString("name").orEmpty(),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun parseSources(raw: String?): List<ExportSource> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val url = obj.optString("url").orEmpty()
                val title = obj.optString("title").orEmpty()
                if (url.isBlank() && title.isBlank()) return@mapNotNull null
                ExportSource(
                    url = url,
                    title = title.ifBlank { url },
                    snippet = obj.optString("snippet").takeIf { it.isNotBlank() && it != "null" },
                    site = obj.optString("site").takeIf { it.isNotBlank() && it != "null" },
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun toExportImage(image: LocalImage, state: State): ExportImage? {
        val file = File(image.path)
        if (!file.isFile || file.length() <= 0L) {
            state.missingImages++
            return null
        }
        // 只读文件头拿宽高，不解整张图
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(image.path, bounds) }
        return ExportImage(
            id = image.fileId.ifBlank { image.path },
            fileName = image.name.ifBlank { file.name },
            size = file.length(),
            width = bounds.outWidth.coerceAtLeast(0),
            height = bounds.outHeight.coerceAtLeast(0),
            open = { runCatching { file.readBytes() }.getOrNull() },
        )
    }

    /** 本机图片文件不在了的张数（放在 State 里） */

    // ---------------------------------------------------------------
    // 自检：把刚写出来的包用官方格式解析器再读一遍
    // ---------------------------------------------------------------

    private fun selfCheck(
        file: File,
        expectConversations: Int,
        result: ExportResult?,
        state: State,
    ): List<String> {
        val lines = mutableListOf<String>()
        val counter = object : ImageSink {
            var count = 0
            override fun store(entryName: String, bytes: ByteArray): StoredImage {
                count++
                return StoredImage("check", "check")
            }
        }
        val scan = try {
            val parser = ExportParser(counter)
            parser.readZip(file, file.name)
            parser.finish()
        } catch (e: Throwable) {
            return listOf("自检失败：${e.message ?: "原因不明"}")
        }

        val okConversations = scan.sessions.size == expectConversations
        val okMessages = result == null || scan.totalMessages == result.messages
        val okImages = result == null || counter.count == result.images
        lines += if (okConversations && okMessages && okImages) {
            "自检通过：按官方结构能完整读回来（${scan.sessions.size} 个对话 / ${scan.totalMessages} 条消息 / ${counter.count} 张图片）"
        } else {
            "自检有差异：读回 ${scan.sessions.size} 个对话 / ${scan.totalMessages} 条消息 / ${counter.count} 张图片，" +
                "应为 $expectConversations / ${result?.messages} / ${result?.images}"
        }
        scan.notes.filter { it.contains("没找到") || it.contains("跳过") }.take(2).forEach { lines += "提示：$it" }
        if (state.docsSkipped > 0) lines += "有 ${state.docsSkipped} 个文档附件没导出（本机只存了摘要，没有原文件）"
        if (state.missingImages > 0) lines += "有 ${state.missingImages} 张图的本地文件不在了，没导出"
        return lines
    }

    // ---------------------------------------------------------------
    // 保存到「下载」
    // ---------------------------------------------------------------

    private class Saved(val uri: Uri?, val path: String?)

    private fun saveToDownloads(context: Context, source: File, fileName: String): Saved {
        // Android 10+ 走 MediaStore.Downloads：不需要任何存储权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("系统没给下载目录的写入权限")
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    source.inputStream().use { input -> input.copyTo(out, 256 * 1024) }
                } ?: error("打不开要写入的文件")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                return Saved(uri, "内部存储/Download/$fileName")
            } catch (e: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
        }

        // Android 8/9：退到 App 自己的外部目录（不需要权限，用户也能用文件管理器找到）
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: error("这台手机没有外部存储")
        val target = File(dir, fileName)
        source.inputStream().use { input ->
            target.outputStream().use { out -> input.copyTo(out, 256 * 1024) }
        }
        return Saved(null, target.absolutePath)
    }
}
