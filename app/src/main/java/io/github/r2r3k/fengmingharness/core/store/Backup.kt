package io.github.r2r3k.fengmingharness.core.store

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import java.io.BufferedInputStream
import java.io.File

/**
 * 备份 / 恢复全部聊天记录（**连图片一起**）。
 *
 * 为什么需要：切到正式签名版必须"卸载再装"（签名不同，装不进去），
 * 卸载会把 App 私有目录一起删掉 —— 聊天记录、图片、设置、加密的 Key 全没。
 *
 * 备份包是个 zip（见 [BackupArchive]）：`harness.db` + `images/` 里的图片 + 清单。
 * 1.3.3 及更早的备份是"裸 .db 文件"，恢复时也认（只是没有图片）。
 *
 * 注意：API Key 存在 Android Keystore 里，卸载时会被系统清除，
 * 备份里故意不包含它 —— 恢复后需要重新填一次 Key。
 */
object Backup {

    private const val DB_NAME = "harness.db"

    /** 一次备份/恢复的结果 */
    data class Summary(
        val conversations: Int,
        val messages: Int,
        val images: Int,
        val bytes: Long,
        /** 恢复的是老格式（只有文字、没有图片） */
        val legacy: Boolean = false,
    )

    private fun dbFile(context: Context): File = context.getDatabasePath(DB_NAME)

    private fun imagesDir(context: Context): File = File(context.filesDir, "images")

    /**
     * 备份：数据库 + 聊天里用到的图片，打成一个 zip 写到用户选的位置。
     */
    fun export(
        context: Context,
        uri: Uri,
        onProgress: (String) -> Unit = {},
    ): Result<Summary> = runCatching {
        val source = dbFile(context)
        require(source.exists()) { "本机还没有聊天记录数据库" }

        val db = ChatDatabase(context)
        val counts = runCatching { db.counts() }.getOrDefault(0 to 0)
        val referenced = runCatching { referencedImagePaths(db) }.getOrDefault(emptySet())

        val target = context.contentResolver.openOutputStream(uri)
            ?: error("打不开要写入的位置")

        val meta = BackupArchive.Meta(
            createdAt = System.currentTimeMillis(),
            appVersion = versionName(context),
            conversations = counts.first,
            messages = counts.second,
        )
        val written = target.use { out ->
            BackupArchive.write(out, source, imagesDir(context), referenced, meta, onProgress)
        }

        Summary(
            conversations = counts.first,
            messages = counts.second,
            images = written.images,
            bytes = written.dbBytes + written.imageBytes,
        )
    }

    /**
     * 恢复：用备份覆盖现在的数据库，并把图片放回 `files/images/`。
     *
     * 调用前必须把界面上的 ChatDatabase 关掉（不然数据库文件被占用）。
     */
    fun restore(
        context: Context,
        uri: Uri,
        onProgress: (String) -> Unit = {},
    ): Result<Summary> = runCatching {
        val target = dbFile(context)
        target.parentFile?.mkdirs()

        // 顺手清掉可能的临时文件，否则 SQLite 会读到旧的未提交内容
        File(target.path + "-wal").delete()
        File(target.path + "-shm").delete()
        File(target.path + "-journal").delete()

        val raw = context.contentResolver.openInputStream(uri)
            ?: error("打不开这个备份文件")
        val input = BufferedInputStream(raw)
        input.mark(8)
        val isZip = BackupArchive.looksLikeZip(input)
        input.reset()

        var meta: BackupArchive.Meta? = null
        try {
            if (isZip) {
                meta = BackupArchive.read(input, target, imagesDir(context), onProgress)
            } else {
                // 1.3.3 及更早：裸的 .db 文件，只有文字没有图片
                onProgress("这是老格式备份（只有文字，没有图片）…")
                target.outputStream().use { out -> input.copyTo(out, 256 * 1024) }
            }
        } finally {
            runCatching { input.close() }
        }

        require(target.length() > 0) { "备份文件是空的" }

        // 自检：真的打开数一数，别给一个"看起来成功"的坏备份
        val db = ChatDatabase(context)
        val counts: Pair<Int, Int>
        var fixedPaths = 0
        try {
            // 备份可能来自别的包名（比如改过名的版本）：
            // 图片的绝对路径对不上，这里统一拍回本机的 images 目录
            fixedPaths = runCatching { db.rewriteImagePaths(imagesDir(context)) }.getOrDefault(0)
            counts = runCatching { db.counts() }.getOrElse { error("备份读不出来：${it.message}") }
        } finally {
            runCatching { db.close() }
        }
        if (fixedPaths > 0) onProgress("修正了 $fixedPaths 条消息的图片路径")

        val images = imagesDir(context).listFiles()?.count { it.isFile } ?: 0
        Summary(
            conversations = counts.first,
            messages = counts.second,
            images = images,
            bytes = target.length(),
            legacy = !isZip,
        )
    }

    /** 数据库里引用过的图片绝对路径（原图 + 缩略图） */
    private fun referencedImagePaths(db: ChatDatabase): Set<String> {
        val paths = HashSet<String>()
        db.allImageJson().forEach { raw ->
            runCatching {
                val array = JSONArray(raw)
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    item.optString("path").takeIf { it.isNotBlank() }?.let { paths += it }
                    item.optString("thumb").takeIf { it.isNotBlank() }?.let { paths += it }
                }
            }
        }
        return paths
    }

    private fun versionName(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.versionName.orEmpty()
    }.getOrDefault("")
}
