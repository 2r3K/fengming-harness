package io.github.r2r3k.fengmingharness.core.exporter

import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// ============================ 输入模型 ============================

/** 一条来源（对应官方 TOOL_SEARCH 里的一个 result） */
data class ExportSource(
    val url: String,
    val title: String,
    val snippet: String? = null,
    val site: String? = null,
)

/**
 * 一张要打包进去的图片。
 *
 * [open] 是"现取字节"的回调：一张一张读，不把 100MB 图片全塞内存里。
 * 调用方请在构造前先确认文件还在（File.isFile），别到写的时候才发现没了。
 */
data class ExportImage(
    /** 本机的图片标识（fileId），导出时会稳定映射成官方要求的 uuid */
    val id: String,
    /** 文件名，比如 1000029117_5155001980622580612.webp 或 photo.jpg */
    val fileName: String,
    val size: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val tokenUsage: Int = 0,
    val open: () -> ByteArray?,
)

/** 一条消息 */
data class ExportMessage(
    val role: String,               // "user" / "assistant"
    val text: String,
    val reasoning: String? = null,
    val sources: List<ExportSource> = emptyList(),
    val images: List<ExportImage> = emptyList(),
    val time: Long = 0L,            // 毫秒时间戳
    val tokenUsage: Int = 0,
)

/** 一个对话 */
data class ExportConversation(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<ExportMessage>,
)

/** 导出结果 */
data class ExportResult(
    val conversations: Int,
    val messages: Int,
    val images: Int,
    val imageBytes: Long,
    val emptyImages: Int,
    val firstTime: Long,
    val lastTime: Long,
)

/**
 * 按 DeepSeek 官方导出格式（`export_format_version: 3`）写一个 zip。
 *
 * **这个类是纯 Kotlin**（org.json + java.util.zip），不依赖 android.*，
 * 所以单元测试里可以把「真包 → 解析 → 再导出 → 再解析」整个跑一遍。
 *
 * 写出来的文件结构（和官方真包一字节一字节对过）：
 * ```
 * manifest.json                      {"export_format_version":3}
 * user.json                          {"user_id":...,"email":null,"mobile":...,"oauth_profiles":[]}
 * chat_sessions/<uuid>.json          {id,title,pinned,current_message_id,inserted_at,updated_at,messages:[...]}
 *   一条 message：{id,parent_id,role,status,thinking_enabled,search_enabled,
 *                 accumulated_token_usage,inserted_at,fragments:[...]}
 *   fragments：REQUEST/RESPONSE {type,content}
 *              THINK {type,content,elapsed_secs}
 *              TOOL_SEARCH {type,status,queries,results:[{url,title,snippet,site_name,published_at}]}
 *              FILE {type,files:[{id,file_name,file_size,token_usage,inserted_at,
 *                                 export_path,is_image,width,height}]}
 * chat_session_index.json            [{id,title,pinned,message_count,inserted_at,updated_at}]
 * chat_sessions/<uuid>_files/<图片uuid>_<文件名>   图片本体
 * ```
 * 时间统一是 `2026-11-20T18:17:13.658Z`（毫秒精度 + Z）。
 */
object OfficialExportWriter {

    /** 官方当前的格式版本 */
    const val FORMAT_VERSION = 3

    private val TIME_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    private val UUID_PATTERN =
        Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    /**
     * 把对话写进一个 zip。
     *
     * @param out 输出流（调用方负责关闭）
     * @param conversations 要导出的对话
     * @param userId 写进 user.json 的用户 id；不传就随机生成一个
     * @param onProgress 进度回调（在调用线程）
     */
    fun write(
        out: OutputStream,
        conversations: List<ExportConversation>,
        userId: String = UUID.randomUUID().toString(),
        onProgress: (String) -> Unit = {},
    ): ExportResult {
        val usable = conversations.filter { it.messages.isNotEmpty() }

        var messageCount = 0
        var imageCount = 0
        var imageBytes = 0L
        var emptyImages = 0
        var firstTime = Long.MAX_VALUE
        var lastTime = 0L

        ZipOutputStream(out).use { zip ->
            // 1. manifest
            writeEntry(
                zip,
                "manifest.json",
                JSONObject().apply { put("export_format_version", FORMAT_VERSION) }.toString(),
            )

            // 2. user.json（结构照官方：没有的信息给 null / 空数组）
            writeEntry(
                zip,
                "user.json",
                JSONObject().apply {
                    put("user_id", userId)
                    put("email", JSONObject.NULL)
                    put("mobile", JSONObject.NULL)
                    put("oauth_profiles", JSONArray())
                }.toString(),
            )

            // 3. 每个对话一个文件（顺手把图片条目写在后面）
            val indexRows = JSONArray()
            usable.forEachIndexed { number, conv ->
                val conversationId = uuidFor(conv.id)
                val built = buildSession(conv, conversationId)
                writeEntry(zip, "chat_sessions/$conversationId.json", built.json)
                messageCount += built.messages

                indexRows.put(
                    JSONObject().apply {
                        put("id", conversationId)
                        put("title", conv.title.ifBlank { "未命名对话" })
                        put("pinned", false)
                        put("message_count", built.messages)
                        put("inserted_at", isoTime(conv.createdAt))
                        put("updated_at", isoTime(conv.updatedAt))
                    }
                )

                // 图片本体
                built.images.forEach { pending ->
                    val bytes = runCatching { pending.image.open() }.getOrNull()
                    if (bytes == null || bytes.isEmpty()) {
                        emptyImages++
                        return@forEach
                    }
                    zip.putNextEntry(ZipEntry(pending.entryName))
                    zip.write(bytes)
                    zip.closeEntry()
                    imageCount++
                    imageBytes += bytes.size
                }

                if (built.firstTime > 0) firstTime = minOf(firstTime, built.firstTime)
                if (built.lastTime > 0) lastTime = maxOf(lastTime, built.lastTime)
                onProgress("已写入 ${number + 1}/${usable.size} 个对话")
            }

            // 4. 对话目录
            writeEntry(zip, "chat_session_index.json", indexRows.toString())
            zip.finish()
        }

        return ExportResult(
            conversations = usable.size,
            messages = messageCount,
            images = imageCount,
            imageBytes = imageBytes,
            emptyImages = emptyImages,
            firstTime = if (firstTime == Long.MAX_VALUE) 0L else firstTime,
            lastTime = lastTime,
        )
    }

    // ---------------------------------------------------------------

    private class PendingImage(val entryName: String, val image: ExportImage)

    private class BuiltSession(
        val json: String,
        val messages: Int,
        val images: List<PendingImage>,
        val firstTime: Long,
        val lastTime: Long,
    )

    /** 把一个对话拼成官方结构（一条直线：id 1..N，parent_id = 上一条） */
    private fun buildSession(conv: ExportConversation, conversationId: String): BuiltSession {
        val messagesArray = JSONArray()
        val pendingImages = mutableListOf<PendingImage>()
        var firstTime = 0L
        var lastTime = 0L

        val cleaned = conv.messages.filter {
            it.role == "user" || it.role == "assistant"
        }

        cleaned.forEachIndexed { index, message ->
            val id = index + 1
            val time = if (message.time > 0) message.time else conv.updatedAt
            if (firstTime == 0L) firstTime = time
            lastTime = maxOf(lastTime, time)

            val fragments = JSONArray()
            val isUser = message.role == "user"

            if (isUser) {
                // 用户：正文 + 图片（官方也是 REQUEST 在前、FILE 跟着）
                fragments.put(
                    JSONObject().apply {
                        put("type", "REQUEST")
                        put("content", message.text)
                    }
                )
                if (message.images.isNotEmpty()) {
                    val files = JSONArray()
                    message.images.forEach { image ->
                        val imageUuid = uuidFor(image.id)
                        val fileName = safeFileName(image.fileName, imageUuid)
                        val entryName = "chat_sessions/${conversationId}_files/${imageUuid}_$fileName"
                        files.put(
                            JSONObject().apply {
                                put("id", imageUuid)
                                put("file_name", fileName)
                                put("file_size", image.size)
                                put("token_usage", image.tokenUsage)
                                put("inserted_at", isoTime(time))
                                put("export_path", entryName)
                                put("is_image", true)
                                put("width", image.width)
                                put("height", image.height)
                            }
                        )
                        pendingImages += PendingImage(entryName, image)
                    }
                    fragments.put(
                        JSONObject().apply {
                            put("type", "FILE")
                            put("files", files)
                        }
                    )
                }
            } else {
                // 助手：思考 → 联网搜索 → 正文（官方顺序）
                val reasoning = message.reasoning.orEmpty().trim()
                if (reasoning.isNotBlank()) {
                    fragments.put(
                        JSONObject().apply {
                            put("type", "THINK")
                            put("content", reasoning)
                            put("elapsed_secs", 0.0)
                        }
                    )
                }
                if (message.sources.isNotEmpty()) {
                    val results = JSONArray()
                    message.sources.forEach { source ->
                        results.put(
                            JSONObject().apply {
                                put("url", source.url)
                                put("title", source.title.ifBlank { source.url })
                                source.snippet?.takeIf { it.isNotBlank() }?.let { put("snippet", it) }
                                source.site?.takeIf { it.isNotBlank() }?.let { put("site_name", it) }
                            }
                        )
                    }
                    fragments.put(
                        JSONObject().apply {
                            put("type", "TOOL_SEARCH")
                            put("status", "FINISHED")
                            put("queries", JSONArray())
                            put("results", results)
                        }
                    )
                }
                if (message.text.isNotBlank()) {
                    fragments.put(
                        JSONObject().apply {
                            put("type", "RESPONSE")
                            put("content", message.text)
                        }
                    )
                }
                // 助手一个字都没有的情况：给一条空 RESPONSE，别给空数组
                if (fragments.length() == 0) {
                    fragments.put(
                        JSONObject().apply {
                            put("type", "RESPONSE")
                            put("content", "")
                        }
                    )
                }
            }

            messagesArray.put(
                JSONObject().apply {
                    put("id", id)
                    put("parent_id", if (index == 0) JSONObject.NULL else index)
                    put("role", if (isUser) "USER" else "ASSISTANT")
                    put("status", "FINISHED")
                    put("thinking_enabled", !message.reasoning.isNullOrBlank())
                    put("search_enabled", message.sources.isNotEmpty())
                    put("accumulated_token_usage", message.tokenUsage)
                    put("inserted_at", isoTime(time))
                    put("fragments", fragments)
                }
            )
        }

        val json = JSONObject().apply {
            put("id", conversationId)
            put("title", conv.title.ifBlank { "未命名对话" })
            put("pinned", false)
            put("current_message_id", cleaned.size)
            put("inserted_at", isoTime(conv.createdAt))
            put("updated_at", isoTime(conv.updatedAt))
            put("messages", messagesArray)
        }.toString()

        return BuiltSession(json, cleaned.size, pendingImages, firstTime, lastTime)
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    /** 官方的时间格式：2026-11-20T18:17:13.658Z */
    fun isoTime(millis: Long): String =
        TIME_FORMAT.format(Instant.ofEpochMilli(if (millis > 0) millis else System.currentTimeMillis()))

    /**
     * 稳定映射成 uuid：本来就像 uuid 的（含 `local:` 前缀那种）直接用，
     * 不是的用 nameUUID 生成，保证同一个对话/图片每次导出 id 都一样。
     */
    fun uuidFor(raw: String): String {
        val trimmed = raw.removePrefix("local:")
        UUID_PATTERN.find(trimmed)?.value?.let { return it.lowercase() }
        return UUID.nameUUIDFromBytes(raw.toByteArray(Charsets.UTF_8)).toString()
    }

    /** 文件名安全化：去掉路径分隔符和奇怪字符，保证有后缀 */
    private fun safeFileName(name: String, fallbackUuid: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\u0000-\\u001f\\u007f/:*?\"<>|]"), "_")
            .trim()
            .take(120)
        if (base.isBlank()) return "$fallbackUuid.webp"
        val hasExt = base.substringAfterLast('.', "").length in 2..5
        return if (hasExt) base else "$base.webp"
    }
}
