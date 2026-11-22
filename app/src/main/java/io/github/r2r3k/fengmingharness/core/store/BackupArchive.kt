package io.github.r2r3k.fengmingharness.core.store

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 备份包的读写（纯 Kotlin，不依赖 android.*，所以能在电脑上单测）。
 *
 * 包里的结构：
 * ```
 * backup.json            {"format":1,"created_at":…,"app_version":"1.3.4","conversations":142,"messages":10842,"images":3412}
 * harness.db             聊天记录数据库
 * images/<文件名>         聊天里用到的图片（只打真被引用的，孤儿图不占体积）
 * ```
 *
 * 为什么不直接备份 .db：图片存在 `files/images/` 里，**不在数据库里**，
 * 只备份 db 的话恢复后文字都在、图片全空。
 *
 * 安全约定：
 *   - 读包时只认 `harness.db`、`backup.json`、`images/<单层文件名>` 三种路径；
 *   - 图片一律按文件名落到指定目录，`..`、绝对路径、多层目录一律忽略（防路径穿越）；
 *   - 单个条目有大小上限，防压缩炸弹。
 */
object BackupArchive {

    const val FORMAT = 1

    const val ENTRY_DB = "harness.db"
    const val ENTRY_META = "backup.json"
    const val IMAGE_PREFIX = "images/"

    /** 单个条目上限：数据库 1GB、图片 32MB */
    private const val MAX_DB_BYTES = 1024L * 1024 * 1024
    private const val MAX_IMAGE_BYTES = 32L * 1024 * 1024

    data class Meta(
        val format: Int = FORMAT,
        val createdAt: Long = System.currentTimeMillis(),
        val appVersion: String = "",
        val conversations: Int = 0,
        val messages: Int = 0,
        val images: Int = 0,
    )

    /** 写完之后的结果 */
    data class Written(val images: Int, val imageBytes: Long, val dbBytes: Long)

    /**
     * 打一个备份包。
     *
     * @param referenced 数据库里引用过的绝对路径集合（不在集合里的图片不打，省体积）
     */
    fun write(
        out: OutputStream,
        dbFile: File,
        imagesDir: File,
        referenced: Set<String>,
        meta: Meta,
        onProgress: (String) -> Unit = {},
    ): Written {
        require(dbFile.isFile) { "本机还没有聊天记录数据库" }
        var images = 0
        var imageBytes = 0L

        ZipOutputStream(out.buffered()).use { zip ->
            // 1. 数据库
            zip.putNextEntry(ZipEntry(ENTRY_DB))
            dbFile.inputStream().use { input -> input.copyTo(zip, 256 * 1024) }
            zip.closeEntry()
            onProgress("数据库打包好了（${dbFile.length() / 1024 / 1024}MB），开始打图片…")

            // 2. 图片（只打被引用的）
            val files = imagesDir.listFiles()?.filter { it.isFile }.orEmpty().sortedBy { it.name }
            files.forEach { file ->
                if (file.absolutePath !in referenced) return@forEach
                if (file.length() > MAX_IMAGE_BYTES) return@forEach
                zip.putNextEntry(ZipEntry(IMAGE_PREFIX + file.name))
                file.inputStream().use { input -> input.copyTo(zip, 256 * 1024) }
                zip.closeEntry()
                images++
                imageBytes += file.length()
                if (images % 200 == 0) onProgress("已打包 $images 张图片（${imageBytes / 1024 / 1024}MB）…")
            }

            // 3. 清单（最后写，这样才能写上真实张数）
            zip.putNextEntry(ZipEntry(ENTRY_META))
            zip.write(metaJson(meta.copy(images = images)).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            onProgress("打包完成：$images 张图片")
        }

        return Written(images, imageBytes, dbFile.length())
    }

    /**
     * 解开一个备份包。
     *
     * @return 包里的清单（没有清单的老包返回 null）
     */
    fun read(
        input: InputStream,
        dbTarget: File,
        imagesDir: File,
        onProgress: (String) -> Unit = {},
    ): Meta? {
        var meta: Meta? = null
        var dbWritten = false
        var images = 0

        val zip = ZipInputStream(input.buffered())
        try {
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.orEmpty().replace('\\', '/')

                if (name == ENTRY_DB) {
                    dbTarget.parentFile?.mkdirs()
                    dbTarget.outputStream().use { out -> copyLimited(zip, out, MAX_DB_BYTES) }
                    dbWritten = true
                    onProgress("数据库恢复好了，正在恢复图片…")
                } else if (name == ENTRY_META) {
                    val text = runCatching { zip.readBytes().toString(Charsets.UTF_8) }.getOrNull()
                    if (text != null) {
                        meta = runCatching { metaFromJson(JSONObject(text)) }.getOrNull()
                    }
                } else if (name.startsWith(IMAGE_PREFIX)) {
                    // 只取单层文件名；整条路径里带 .. 的一律当坏包丢掉（我们自己打的包不会这样）
                    val leaf = name.substringAfterLast('/')
                    if (!name.contains("..") && isSafeName(leaf) && entry.size <= MAX_IMAGE_BYTES) {
                        imagesDir.mkdirs()
                        File(imagesDir, leaf).outputStream().use { out ->
                            copyLimited(zip, out, MAX_IMAGE_BYTES)
                        }
                        images++
                        if (images % 200 == 0) onProgress("已恢复 $images 张图片…")
                    }
                }
                // 其他条目：忽略
                runCatching { zip.closeEntry() }
            }
        } finally {
            runCatching { zip.close() }
        }

        require(dbWritten) { "这个备份里没有数据库文件，可能是别的文件" }
        onProgress("图片恢复完成：$images 张")
        return meta
    }

    /** 老格式（直接是 .db 文件、不是 zip）靠这个判断。调用方负责 mark/reset */
    fun looksLikeZip(input: InputStream): Boolean = runCatching {
        val head = ByteArray(2)
        var read = 0
        while (read < 2) {
            val step = input.read(head, read, 2 - read)
            if (step <= 0) break
            read += step
        }
        read == 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
    }.getOrDefault(false)

    // ---------------------------------------------------------------

    private fun isSafeName(name: String): Boolean {
        if (name.isBlank()) return false
        if (name.contains("..")) return false
        if (name.contains('/') || name.contains('\\')) return false
        if (name.startsWith(".")) return false
        return true
    }

    /** 抄字节，超过上限就停（防压缩炸弹把手机塞爆） */
    private fun copyLimited(input: InputStream, out: OutputStream, limit: Long): Long {
        val buffer = ByteArray(256 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            total += read
            if (total > limit) break
            out.write(buffer, 0, read)
        }
        return total
    }

    private fun metaJson(meta: Meta): String = JSONObject().apply {
        put("format", meta.format)
        put("created_at", meta.createdAt)
        put("app_version", meta.appVersion)
        put("conversations", meta.conversations)
        put("messages", meta.messages)
        put("images", meta.images)
    }.toString()

    private fun metaFromJson(json: JSONObject): Meta = Meta(
        format = json.optInt("format", FORMAT),
        createdAt = json.optLong("created_at", 0L),
        appVersion = json.optString("app_version").orEmpty(),
        conversations = json.optInt("conversations"),
        messages = json.optInt("messages"),
        images = json.optInt("images"),
    )
}
