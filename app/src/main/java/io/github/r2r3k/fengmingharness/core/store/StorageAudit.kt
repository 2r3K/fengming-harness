package io.github.r2r3k.fengmingharness.core.store

import android.content.Context
import org.json.JSONArray
import java.io.File

/** 一次存储体检的结果 */
data class StorageReport(
    /** 数据库文件大小 */
    val dbBytes: Long,
    val conversations: Int,
    val messages: Int,
    /** 本地图片文件数 / 总大小 */
    val imageFiles: Int,
    val imageBytes: Long,
    /** 没被任何消息引用的图片（删了不心疼） */
    val orphanFiles: Int,
    val orphanBytes: Long,
    /** 缓存目录（导入导出的临时文件） */
    val cacheBytes: Long,
    /** 被引用的文件数（用来和实际文件数对比） */
    val referencedFiles: Int,
) {
    val totalBytes: Long get() = dbBytes + imageBytes + cacheBytes
}

/**
 * 存储体检 + 清理。
 *
 * 为什么需要：导入官方历史会塞进上千张图（你那次 998 张、116MB），
 * 而删对话时代码并不会去删图片文件 —— 用久了就会攒一堆"没人用的图"。
 *
 * 清理规则（保守）：
 *   - **只**删 `filesDir/images` 目录里的文件；
 *   - **只**删数据库里没有任何消息引用的那种；
 *   - 删之前逐条比对 path 和 thumb，两个都没人引用才算孤儿。
 */
object StorageAudit {

    private fun imageDir(context: Context): File = File(context.filesDir, "images")

    fun scan(context: Context, db: ChatDatabase): StorageReport {
        val dir = imageDir(context)
        val files = dir.listFiles()?.filter { it.isFile }.orEmpty()
        val referenced = referencedPaths(db)

        var imageBytes = 0L
        var orphanFiles = 0
        var orphanBytes = 0L
        files.forEach { file ->
            val size = runCatching { file.length() }.getOrDefault(0L)
            imageBytes += size
            if (file.absolutePath !in referenced) {
                orphanFiles++
                orphanBytes += size
            }
        }

        val (conversations, messages) = runCatching { db.counts() }.getOrDefault(0 to 0)
        val dbBytes = runCatching { context.getDatabasePath("harness.db").length() }.getOrDefault(0L)

        return StorageReport(
            dbBytes = dbBytes,
            conversations = conversations,
            messages = messages,
            imageFiles = files.size,
            imageBytes = imageBytes,
            orphanFiles = orphanFiles,
            orphanBytes = orphanBytes,
            cacheBytes = dirSize(context.cacheDir),
            referencedFiles = referenced.size,
        )
    }

    /** 删掉没被引用的图片。返回（删了几个, 省了多少字节） */
    fun cleanOrphans(context: Context, db: ChatDatabase): Pair<Int, Long> {
        val dir = runCatching { imageDir(context).canonicalFile }.getOrNull() ?: return 0 to 0L
        if (!dir.isDirectory) return 0 to 0L
        val referenced = referencedPaths(db)
        var count = 0
        var bytes = 0L
        dir.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            val canonical = runCatching { file.canonicalPath }.getOrNull() ?: return@forEach
            // 保险：万一有软链接指到别处，也绝不删
            if (!canonical.startsWith(dir.path)) return@forEach
            if (canonical in referenced) return@forEach
            val size = runCatching { file.length() }.getOrDefault(0L)
            if (runCatching { file.delete() }.getOrDefault(false)) {
                count++
                bytes += size
            }
        }
        return count to bytes
    }

    /** 清缓存目录里的临时文件（导入复制的 zip、导出的临时包） */
    fun cleanCache(context: Context): Pair<Int, Long> {
        var count = 0
        var bytes = 0L
        listOf("import", "export").forEach { name ->
            val dir = File(context.cacheDir, name)
            dir.listFiles()?.forEach { file ->
                bytes += runCatching { file.length() }.getOrDefault(0L)
                if (runCatching { file.deleteRecursively() }.getOrDefault(false)) count++
            }
        }
        return count to bytes
    }

    /** 数据库里所有消息引用过的本地路径（图片原图 + 缩略图） */
    private fun referencedPaths(db: ChatDatabase): Set<String> {
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

    private fun dirSize(dir: File): Long {
        if (!dir.isDirectory) return 0L
        var total = 0L
        dir.walkTopDown().forEach { file ->
            if (file.isFile) total += runCatching { file.length() }.getOrDefault(0L)
        }
        return total
    }

    fun human(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
        bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
