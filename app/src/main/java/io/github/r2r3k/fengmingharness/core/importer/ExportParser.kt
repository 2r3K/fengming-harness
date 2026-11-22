package io.github.r2r3k.fengmingharness.core.importer

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.time.OffsetDateTime
import java.util.zip.ZipFile

// ============================ 结果类型 ============================

/** 导入进来的一条消息 */
data class ImportMessage(
    val role: String,
    val text: String,
    val reasoning: String? = null,
    /** 官方历史里带的图片（已存到手机本地），格式和聊天里的一致 */
    val imagesJson: String? = null,
    /** 官方历史里联网搜索到的网页，格式和聊天里的一致（显示成「来源（N）」） */
    val sourcesJson: String? = null,
    val time: Long = 0L,
    val imageCount: Int = 0,
    val sourceCount: Int = 0,
)

/** 导入进来的一个对话 */
data class ImportSession(
    val sourceId: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<ImportMessage>,
)

/** 解析结果：找到了哪些对话、跳过了多少条坏数据 */
data class ImportScan(
    val sessions: List<ImportSession>,
    val skipped: Int,
    val notes: List<String>,
    val imageCount: Int = 0,
    val sourceCount: Int = 0,
) {
    val totalMessages: Int get() = sessions.sumOf { it.messages.size }
    val userMessages: Int get() = sessions.sumOf { s -> s.messages.count { it.role == "user" } }
}

/** 一张已经落盘的图（路径由调用方决定怎么存） */
data class StoredImage(val path: String, val thumbPath: String)

/** 图片来源：给一段图片字节，返回存好的路径。Android 那边用 Bitmap 存，测试里可以只计数 */
fun interface ImageSink {
    fun store(entryName: String, bytes: ByteArray): StoredImage?
}

// ============================ 解析器 ============================

/**
 * DeepSeek 官方导出（`export_format_version: 3`）的解析器。
 *
 * **这个类不依赖任何 android.* 类**，所以能在电脑上跑 JVM 单元测试
 * （`src/test/java/.../ExportParserTest.kt` 拿真包跑）。
 *
 * 官方结构：
 * ```
 * manifest.json                      {"export_format_version":3}
 * chat_session_index.json            [{id,title,pinned,message_count,inserted_at,updated_at}]
 * chat_sessions/<id>.json            {id,title,pinned,current_message_id,inserted_at,updated_at,messages:[...]}
 *   一条消息: {id, parent_id, role:"USER"|"ASSISTANT", status, inserted_at, fragments:[...]}
 *   *** 正文不在 content 里，在 fragments 里按类型分片 ***
 * chat_sessions/<id>_files/<文件名>   对话里的图片（webp）
 * ```
 *
 * 分片类型 → App 里的对应关系：
 * | fragment          | 映射                                                            |
 * |-------------------|-----------------------------------------------------------------|
 * | REQUEST           | 用户的提问（text），多个就换行拼起来                              |
 * | RESPONSE          | 助手的回答（text，走 Markdown 渲染）                              |
 * | THINK             | reasoning → UI 上的「💭 思考过程（点开看）」折叠区                 |
 * | TOOL_SEARCH       | sources → UI 上的「来源（N）」，带 url/标题/站点/摘要              |
 * | READ_LINK/TOOL_OPEN | 同上，当成来源（打开过的网页）                                  |
 * | FILE              | 图片 → imagesJson（从压缩包里取出来存本地）                       |
 * | TEMPLATE_RESPONSE | 当回答，末尾注明这是官网标准回复                                   |
 * | TIP / CONTENT_RISK / 未知类型 | 忽略（不崩）                                          |
 *
 * 容错原则：
 *   - 全程 `opt*` / `runCatching`，**没有任何一处会抛出去**；
 *   - 未知分片类型直接 continue；
 *   - `fragments` 不是数组、里面混着 null/数字/字符串 → 跳过那一条；
 *   - 分支指针坏了（parent 不存在 / 成环 / 重复 id）→ 能走多远走多远；
 *   - 单个 json 限 64MB、单图限 12MB，防压缩炸弹。
 */
class ExportParser(
    private val imageSink: ImageSink? = null,
    private val shouldSkip: (dbId: String) -> Boolean = { false },
    private val onProgress: (String) -> Unit = {},
) {

    private val notes = mutableListOf<String>()
    private var skipped = 0

    /** 目录：id → 标题和时间 */
    private val index = LinkedHashMap<String, IndexRow>()

    /** 对话：id → 内容（目录行和会话文件按 id 合并到这里） */
    private val convs = LinkedHashMap<String, PConv>()

    /** 取出来的图片：条目名 / 文件名 → 本地路径 */
    private val images = HashMap<String, StoredImage>()

    private var nonImageFiles = 0
    private var missingImages = 0
    private var skippedImages = 0
    private var manifestVersion: Int? = null

    // ---------------------------------------------------------------
    // 入口
    // ---------------------------------------------------------------

    /**
     * 读一个 .zip 导出包。
     *
     * **为什么用 ZipFile 而不是 ZipInputStream**：官方这个包里的图片条目是
     * "STORED + 数据描述符"（uncompressed + bit3），流式读第 10 个条目时
     * 会抛 `only DEFLATED entries can have EXT descriptor`，图片一张都读不出来。
     * ZipFile 走中央目录随机访问，没有这个毛病，也不用把包读两遍。
     */
    fun readZip(file: File, label: String) {
        val zip = try {
            ZipFile(file)
        } catch (e: Throwable) {
            notes += "打不开压缩包：${e.message ?: e.javaClass.simpleName}"
            return
        }
        zip.use { archive ->
            try {
                passJson(archive, label)
            } catch (e: Throwable) {
                notes += "读对话那一步出错：${e.message ?: e.javaClass.simpleName}"
            }

            if (imageSink != null) {
                val wanted = wantedImageNames()
                if (wanted.isNotEmpty()) {
                    onProgress("正在取图片…（共 ${wanted.size} 个）")
                    try {
                        passImages(archive, wanted)
                    } catch (e: Throwable) {
                        notes += "取图片那一步出错：${e.message ?: e.javaClass.simpleName}"
                    }
                }
            }
        }
    }

    /** 读单个 json 文件（用户只选了里面的某个 json，或者别的格式的导出） */
    fun readJsonDocument(open: () -> InputStream?, label: String) {
        try {
            val bytes = open()?.use { readLimited(it, MAX_JSON_BYTES) }
            if (bytes == null) {
                notes += "打不开：$label"
                return
            }
            dispatch(parseJson(bytes), kind = Kind.ANY, idHint = idHintFrom(label))
        } catch (e: Throwable) {
            skipped++
            notes += "读不了这个文件：$label（${e.message ?: e.javaClass.simpleName}）"
        }
    }

    /** 合并、排序、出结果 */
    fun finish(): ImportScan {
        val sessions = ArrayList<ImportSession>()
        var totalImages = 0
        var totalSources = 0

        // 目录里有、会话文件里没有的，也先建出来（后面按 id 合并）
        index.forEach { (id, row) ->
            val conv = convs.getOrPut(id) { PConv(id) }
            if (conv.title.isBlank()) conv.title = row.title
            if (conv.createdAt <= 0) conv.createdAt = row.insertedAt
            if (conv.updatedAt <= 0) conv.updatedAt = row.updatedAt
        }

        convs.values.forEach { conv ->
            if (conv.msgs.isEmpty()) return@forEach
            var convImages = 0
            var convSources = 0
            val messages = ArrayList<ImportMessage>()
            conv.msgs.forEach { msg ->
                val imageJson = imagesJson(msg.files)
                convImages += imageJson.second
                val sourceJson = sourcesJson(msg.sources)
                convSources += sourceJson.second

                // 有的提问只有一个截图、没有一个字：给一句说明，别让这轮问答凭空少掉
                var text = msg.text
                if (text.isBlank()) {
                    text = when {
                        msg.role != "user" -> ""
                        imageJson.first != null -> ""
                        msg.files.isNotEmpty() -> "（这一轮你发的是附件，导出包里没有文字）"
                        else -> "（这一轮没有文字）"
                    }
                }
                if (text.isBlank() && msg.reasoning.isNullOrBlank() && imageJson.first == null) {
                    return@forEach // 什么都没剩下的一条，不写进数据库
                }
                messages += ImportMessage(
                    role = msg.role,
                    text = text,
                    reasoning = msg.reasoning,
                    imagesJson = imageJson.first,
                    sourcesJson = sourceJson.first,
                    time = msg.time,
                    imageCount = imageJson.second,
                    sourceCount = sourceJson.second,
                )
            }
            if (messages.isEmpty()) return@forEach

            totalImages += convImages
            totalSources += convSources

            val title = conv.title.ifBlank {
                messages.firstOrNull { it.role == "user" }?.text
                    ?.replace('\n', ' ')?.trim()?.take(24)
                    ?: "导入的对话"
            }
            val firstTime = messages.firstOrNull { it.time > 0 }?.time ?: System.currentTimeMillis()
            val lastTime = messages.lastOrNull { it.time > 0 }?.time ?: firstTime

            sessions += ImportSession(
                sourceId = conv.id,
                title = title.take(40),
                createdAt = if (conv.createdAt > 0) conv.createdAt else firstTime,
                updatedAt = if (conv.updatedAt > 0) conv.updatedAt else lastTime,
                messages = messages,
            )
        }

        val sorted = sessions.sortedByDescending { it.updatedAt }

        if (sorted.isEmpty()) {
            notes += if (index.isNotEmpty() || convs.isNotEmpty()) {
                "找到了 ${index.size} 个标题、${convs.size} 个会话文件，但没读出消息正文"
            } else {
                "没读到任何对话。请用右上角「选文件」一次选中那个 .zip"
            }
        }
        if (nonImageFiles > 0) notes += "有 $nonImageFiles 个非图片附件（导出包里没有内容），没导入"
        if (missingImages > 0) notes += "有 $missingImages 张图在压缩包里没找到，跳过了"
        if (skippedImages > 0) notes += "按设置跳过了 $skippedImages 张图片"
        manifestVersion?.let { notes += "导出格式版本：$it" }

        return ImportScan(sorted, skipped, notes.takeLast(6), totalImages, totalSources)
    }

    // ---------------------------------------------------------------
    // zip：第一遍读 json
    // ---------------------------------------------------------------

    private fun passJson(zip: ZipFile, label: String) {
        var entries = 0
        var jsonEntries = 0
        var lastSessionCount = 0

        val list = zip.entries()
        while (list.hasMoreElements()) {
            val entry = runCatching { list.nextElement() }.getOrNull() ?: break
            if (entry.isDirectory) continue
            val name = entry.name.orEmpty().replace('\\', '/').trimStart('.', '/')
            if (name.isBlank()) continue
            entries++
            val lower = name.lowercase()
            if (!lower.endsWith(".json")) continue // 图片留给第二遍
            jsonEntries++

            try {
                if (entry.size > MAX_JSON_BYTES) {
                    notes += "${shortName(name)} 太大（${entry.size / 1024 / 1024}MB），跳过了"
                    skipped++
                    continue
                }
                val bytes = zip.getInputStream(entry)?.use { readLimited(it, MAX_JSON_BYTES) }
                if (bytes == null) {
                    notes += "${shortName(name)} 读出来是空的，跳过了"
                    skipped++
                    continue
                }
                val value = parseJson(bytes)
                when {
                    lower.endsWith("manifest.json") -> dispatch(value, Kind.MANIFEST, null)
                    lower.endsWith("chat_session_index.json") -> dispatch(value, Kind.INDEX, null)
                    lower.startsWith("chat_sessions/") && !lower.contains("_files/") ->
                        dispatch(value, Kind.SESSION, idHintFrom(name))
                    else -> dispatch(value, Kind.ANY, idHintFrom(name))
                }
            } catch (e: Throwable) {
                // 单个文件坏掉：只跳过它，绝不中断整个压缩包
                skipped++
                notes += "跳过 ${shortName(name)}（${e.message ?: e.javaClass.simpleName}）"
            }

            if (convs.size != lastSessionCount) {
                lastSessionCount = convs.size
                onProgress("已读出 ${convs.size} 个对话…")
            }
        }

        if (entries == 0) {
            notes += "$label 里没读到任何文件"
            return
        }
        notes += "$label：$entries 个文件，其中 json $jsonEntries 个" +
            "（会话 ${convs.size} 个，目录条目 ${index.size} 个）"
    }

    // ---------------------------------------------------------------
    // zip：第二遍取图片
    // ---------------------------------------------------------------

    private fun passImages(zip: ZipFile, wanted: Set<String>) {
        val sink = imageSink ?: return
        var taken = 0
        var stored = 0L

        val list = zip.entries()
        while (list.hasMoreElements()) {
            val entry = runCatching { list.nextElement() }.getOrNull() ?: break
            if (entry.isDirectory) continue
            val name = entry.name.orEmpty().replace('\\', '/').trimStart('.', '/')
            if (name.isBlank() || !name.contains("_files/")) continue

            val lower = name.lowercase()
            val base = name.substringAfterLast('/').lowercase()
            if (lower !in wanted && base !in wanted) continue
            if (taken >= MAX_IMAGES || stored >= MAX_IMAGE_TOTAL_BYTES) {
                notes += "图片太多，只取了前 $taken 张"
                break
            }
            if (entry.size > MAX_IMAGE_BYTES) {
                missingImages++
                continue
            }

            val bytes = runCatching {
                zip.getInputStream(entry)?.use { readLimited(it, MAX_IMAGE_BYTES) }
            }.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) {
                val image = runCatching { sink.store(name, bytes) }.getOrNull()
                if (image != null) {
                    images[name] = image
                    images[base] = image
                    taken++
                    stored += bytes.size.toLong()
                    if (taken % 25 == 0) onProgress("已取 $taken 张图片…")
                } else {
                    missingImages++
                }
            } else {
                missingImages++
            }
        }
        if (taken > 0) onProgress("图片取好了：$taken 张")
    }

    /** 这次要取哪些图片：待导入对话里 FILE 分片给出的路径/文件名 */
    private fun wantedImageNames(): Set<String> {
        val wanted = HashSet<String>()
        convs.values.forEach { conv ->
            conv.msgs.forEach { msg ->
                msg.files.forEach { file ->
                    if (!file.isImage) return@forEach
                    file.exportPath?.let { path ->
                        val clean = cleanPath(path)
                        if (clean.isNotBlank()) {
                            wanted += clean.lowercase()
                            wanted += clean.substringAfterLast('/').lowercase()
                        }
                    }
                    if (file.name.isNotBlank()) {
                        wanted += file.name.lowercase()
                        wanted += file.name.substringAfterLast('/').lowercase()
                    }
                }
            }
        }
        return wanted
    }

    // ---------------------------------------------------------------
    // json 分派
    // ---------------------------------------------------------------

    private enum class Kind { MANIFEST, INDEX, SESSION, ANY }

    private fun dispatch(value: Any?, kind: Kind, idHint: String?) {
        when (value) {
            is JSONArray -> handleArray(value, kind)
            is JSONObject -> handleObject(value, kind, idHint)
            else -> Unit // null / 字符串 / 数字：不是对话数据，忽略
        }
    }

    private fun handleArray(array: JSONArray, kind: Kind) {
        for (i in 0 until array.length()) {
            runCatching {
                val item = array.optJSONObject(i) ?: return@runCatching
                when (kind) {
                    Kind.INDEX -> readIndexRow(item)
                    Kind.SESSION -> parseSession(item, null)
                    else -> if (item.optJSONArray("messages") != null) {
                        parseSession(item, null)
                    } else {
                        readIndexRow(item)
                    }
                }
            }
        }
    }

    private fun handleObject(obj: JSONObject, kind: Kind, idHint: String?) {
        if (kind == Kind.MANIFEST || (kind == Kind.ANY && obj.has("export_format_version"))) {
            val version = obj.optInt("export_format_version", 0)
            if (version > 0) {
                manifestVersion = version
                if (version != 3) notes += "这个包是第 $version 版格式，我按已知结构尽量读"
            }
            return
        }

        if (obj.optJSONArray("messages") != null) {
            parseSession(obj, idHint)
            return
        }

        // 包了一层：{"sessions":[...]} / {"data":[...]}
        FIRST_OF_KEYS.forEach { key ->
            val wrapper = obj.optJSONArray(key) ?: return@forEach
            handleArray(wrapper, if (kind == Kind.ANY) Kind.ANY else kind)
            return
        }

        if (obj.has("id")) {
            readIndexRow(obj)
            return
        }
        if (kind == Kind.SESSION) skipped++
    }

    private fun readIndexRow(row: JSONObject) {
        val id = str(row, "id").ifBlank { str(row, "session_id") }
        if (id.isBlank()) {
            skipped++
            return
        }
        val old = index[id]
        index[id] = IndexRow(
            id = id,
            title = str(row, "title").ifBlank { old?.title.orEmpty() },
            insertedAt = parseTime(row.opt("inserted_at")).takeIf { it > 0 }
                ?: parseTime(row.opt("created_at")).takeIf { it > 0 }
                ?: old?.insertedAt ?: 0L,
            updatedAt = parseTime(row.opt("updated_at")).takeIf { it > 0 }
                ?: parseTime(row.opt("update_time")).takeIf { it > 0 }
                ?: old?.updatedAt ?: 0L,
        )
    }

    private fun parseSession(obj: JSONObject, idHint: String?): PConv? {
        val id = str(obj, "id").ifBlank { idHint.orEmpty() }
        if (id.isBlank()) {
            skipped++
            return null
        }
        if (shouldSkip("imp-" + id.take(40))) return null // 已经导过了

        val conv = convs.getOrPut(id) { PConv(id) }
        str(obj, "title").takeIf { it.isNotBlank() }?.let { conv.title = it }
        parseTime(obj.opt("inserted_at")).takeIf { it > 0 }?.let { conv.createdAt = it }

        val array = obj.optJSONArray("messages")
        if (array == null || array.length() == 0) {
            val fallback = fallbackMessages(obj)
            if (fallback.isNotEmpty()) {
                conv.msgs.addAll(fallback)
            } else if (conv.msgs.isEmpty()) {
                skipped++
            }
            return conv
        }

        val chain = selectChain(array, obj)
        val built = buildTurns(chain)
        if (built.isNotEmpty()) {
            conv.msgs.addAll(built)
        } else {
            val loose = chainFallback(chain)
            if (loose.isEmpty()) skipped++ else conv.msgs.addAll(loose)
        }
        return conv
    }

    // ---------------------------------------------------------------
    // 分支选择：current_message_id + parent_id
    // ---------------------------------------------------------------

    private fun selectChain(array: JSONArray, root: JSONObject): List<JSONObject> {
        val byId = LinkedHashMap<Long, JSONObject>()
        var maxId: Long? = null
        for (i in 0 until array.length()) {
            val message = array.optJSONObject(i) ?: continue
            val id = numOf(message, "id") ?: (i + 1).toLong()
            if (byId.containsKey(id)) {
                notes += "有重复的消息 id=$id，按先出现的算"
                continue
            }
            byId[id] = message
            if (maxId == null || id > maxId) maxId = id
        }
        if (byId.isEmpty()) return emptyList()

        val current = numOf(root, "current_message_id")
        var cursor: JSONObject? = current?.let { byId[it] } ?: maxId?.let { byId[it] }
        val chain = ArrayList<JSONObject>()
        var guard = 0
        while (cursor != null && guard++ <= byId.size + 4) {
            chain += cursor
            val parent = numOf(cursor, "parent_id") ?: break
            cursor = byId[parent] ?: break // 父节点不在（数据坏了）：到此为止
        }
        chain.reverse()
        return chain
    }

    // ---------------------------------------------------------------
    // 一条 USER + 紧跟的 ASSISTANT = 一轮
    // ---------------------------------------------------------------

    private fun buildTurns(chain: List<JSONObject>): List<PMsg> {
        val out = ArrayList<PMsg>()
        var i = 0
        while (i < chain.size) {
            val message = chain[i]
            if (roleOf(message) != "USER") {
                i++
                continue
            }
            val reply = chain.getOrNull(i + 1)?.takeIf { roleOf(it) == "ASSISTANT" }
            i += if (reply != null) 2 else 1

            // 官网把这轮撤回了（内容被平台过滤）：整轮跳过，和官网看到的一致
            if (reply != null && str(reply, "status").uppercase() == "CONTENT_FILTER") continue

            out += userMsg(message)
            if (reply != null) {
                val assistant = assistantMsg(reply)
                if (assistant != null) out += assistant
            }
        }
        return out
    }

    /** USER 消息：REQUEST 分片拼正文，FILE 分片收图片 */
    private fun userMsg(message: JSONObject): PMsg {
        val lines = mutableListOf<String>()
        val files = mutableListOf<PFile>()
        forEachFragment(message) { fragment ->
            when (typeOf(fragment)) {
                "REQUEST" -> str(fragment, "content").takeIf { it.isNotBlank() }?.let { lines += it }
                "FILE" -> fragment.optJSONArray("files")?.let { array ->
                    for (i in 0 until array.length()) {
                        val entry = array.optJSONObject(i) ?: continue
                        val file = PFile(
                            id = str(entry, "id"),
                            name = str(entry, "file_name").ifBlank { "附件" },
                            exportPath = str(entry, "export_path").takeIf { it.isNotBlank() },
                            isImage = entry.optBoolean("is_image", false),
                        )
                        if (!file.isImage) nonImageFiles++ else files += file
                    }
                }
            }
        }
        return PMsg(
            role = "user",
            text = lines.joinToString("\n").trim(),
            reasoning = null,
            time = parseTime(message.opt("inserted_at")),
            files = files,
        )
    }

    /** ASSISTANT 消息：RESPONSE → 正文，THINK → 折叠的思考过程，搜索 → 来源 */
    private fun assistantMsg(message: JSONObject): PMsg? {
        val answers = mutableListOf<String>()
        val thinks = mutableListOf<String>()
        val sources = mutableListOf<SourceRef>()
        var template = false

        forEachFragment(message) { fragment ->
            when (typeOf(fragment)) {
                "RESPONSE" -> str(fragment, "content").takeIf { it.isNotBlank() }?.let { answers += it }
                "THINK" -> str(fragment, "content").takeIf { it.isNotBlank() }?.let { thinks += it }
                "TEMPLATE_RESPONSE" -> {
                    template = true
                    str(fragment, "content").takeIf { it.isNotBlank() }?.let { answers += it }
                }
                // 联网搜索到的网页 → 「来源（N）」
                "TOOL_SEARCH" -> readSearchResults(fragment.optJSONArray("results"), sources)
                // 打开过的网页 / 读过的链接 → 也当来源
                "TOOL_OPEN" -> {
                    val url = str(fragment, "url")
                    if (url.isNotBlank()) {
                        sources += SourceRef(
                            url = url,
                            title = str(fragment, "title").ifBlank { url },
                            site = hostOf(url),
                            snippet = "",
                        )
                    }
                }
                "READ_LINK" -> readLinkResults(fragment.optJSONArray("results"), sources)
                // TIP / CONTENT_RISK / TOOL_FIND / TOOL_IMAGE_SEARCH / PARTIAL_FILE_READ / 未知类型：
                // 官网界面上也不显示成正文，忽略
                else -> Unit
            }
        }

        val status = str(message, "status").uppercase()
        val suffix = when {
            status.isBlank() || status == "FINISHED" || status == "SUCCESS" -> ""
            status == "WIP" -> "（这条回复当时没写完）"
            else -> "（原回复没写完，状态：$status）"
        }
        val templateNote = if (template) "（这条是官网给的标准回复，不是模型现答的）" else ""

        val text = buildString {
            append(answers.joinToString("\n\n").trim())
            listOf(templateNote, suffix).forEach { note ->
                if (note.isNotBlank()) {
                    if (isNotEmpty()) append("\n\n")
                    append(note)
                }
            }
        }.trim()

        val reasoning = thinks.joinToString("\n\n").trim()
        if (text.isBlank() && reasoning.isBlank() && sources.isEmpty()) return null

        return PMsg(
            role = "assistant",
            text = text,
            reasoning = reasoning.takeIf { it.isNotBlank() },
            time = parseTime(message.opt("inserted_at")),
            files = emptyList(),
            sources = sources,
        )
    }

    private fun readSearchResults(array: JSONArray?, into: MutableList<SourceRef>) {
        if (array == null) return
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val url = str(item, "url")
            val title = str(item, "title")
            if (url.isBlank() && title.isBlank()) continue
            into += SourceRef(
                url = url,
                title = title.ifBlank { url },
                site = str(item, "site_name").ifBlank { hostOf(url) },
                snippet = str(item, "snippet"),
            )
        }
    }

    private fun readLinkResults(array: JSONArray?, into: MutableList<SourceRef>) {
        if (array == null) return
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val url = str(item, "url")
            val title = str(item, "title")
            if (url.isBlank() && title.isBlank()) continue
            into += SourceRef(
                url = url,
                title = title.ifBlank { url },
                site = hostOf(url),
                snippet = "",
            )
        }
    }

    /** 兜底：连一轮都拼不出来时，链上每条消息的文字原样捞出来 */
    private fun chainFallback(chain: List<JSONObject>): List<PMsg> {
        val out = mutableListOf<PMsg>()
        chain.forEach { message ->
            val texts = mutableListOf<String>()
            val thinks = mutableListOf<String>()
            forEachFragment(message) { fragment ->
                val content = str(fragment, "content")
                if (content.isNotBlank()) {
                    if (typeOf(fragment) == "THINK") thinks += content else texts += content
                }
            }
            val text = texts.joinToString("\n\n").trim()
            val reasoning = thinks.joinToString("\n\n").trim()
            if (text.isBlank() && reasoning.isBlank()) return@forEach
            out += PMsg(
                role = roleOf(message)?.lowercase() ?: "assistant",
                text = text,
                reasoning = reasoning.takeIf { it.isNotBlank() },
                time = parseTime(message.opt("inserted_at")),
                files = emptyList(),
            )
        }
        return out
    }

    /** 兜底：不是官方结构时，退一步找 role + content / text 的线性数组 */
    private fun fallbackMessages(obj: JSONObject): List<PMsg> {
        val array = MESSAGE_KEYS.firstNotNullOfOrNull { obj.optJSONArray(it) } ?: return emptyList()
        val out = mutableListOf<PMsg>()
        for (i in 0 until array.length()) {
            runCatching {
                val item = array.optJSONObject(i) ?: return@runCatching
                val message = item.optJSONObject("message") ?: item
                val role = roleOf(message) ?: return@runCatching
                val text = extractText(firstValueOf(message, "content", "text", "parts", "value", "body"))
                if (text.isBlank()) return@runCatching
                out += PMsg(
                    role = role.lowercase(),
                    text = text.trim(),
                    reasoning = str(message, "reasoning_content").ifBlank { str(message, "thinking") }
                        .takeIf { it.isNotBlank() },
                    time = parseTime(message.opt("inserted_at")),
                    files = emptyList(),
                )
            }
        }
        return out
    }

    // ---------------------------------------------------------------
    // fragments 遍历（这里就是"极致容错"的核心）
    // ---------------------------------------------------------------

    /**
     * 遍历 fragments。任何奇怪的东西都不会抛出去：
     * `fragments` 不是数组、元素不是对象、类型缺失、字段缺失，全部静默跳过。
     */
    private fun forEachFragment(message: JSONObject, action: (JSONObject) -> Unit) {
        val fragments = message.optJSONArray("fragments") ?: return
        for (i in 0 until fragments.length()) {
            val fragment = fragments.optJSONObject(i) ?: continue
            try {
                action(fragment)
            } catch (e: Throwable) {
                // 单个分片出错：跳过它，别的分片照读
            }
        }
    }

    private fun typeOf(fragment: JSONObject): String =
        str(fragment, "type").uppercase().ifBlank { str(fragment, "fragment_type").uppercase() }

    // ---------------------------------------------------------------
    // 图片 / 来源 → 存进数据库的 json
    // ---------------------------------------------------------------

    private fun imagesJson(files: List<PFile>): Pair<String?, Int> {
        if (files.isEmpty()) return null to 0
        val picked = ArrayList<Pair<PFile, StoredImage>>()
        files.forEach { file ->
            if (!file.isImage) return@forEach
            if (imageSink == null) {
                skippedImages++
                return@forEach
            }
            val stored = resolveImage(file)
            if (stored == null) {
                missingImages++
                return@forEach
            }
            picked += file to stored
        }
        if (picked.isEmpty()) return null to 0
        val json = JSONArray().apply {
            picked.forEach { (file, image) ->
                put(
                    JSONObject().apply {
                        // local: 前缀 = 只在本机显示，不发给模型（没有服务器端文件号）
                        put("fileId", "local:" + file.id.ifBlank { file.name }.take(60))
                        put("path", image.path)
                        put("thumb", image.thumbPath)
                        put("kind", "image")
                        put("name", file.name)
                    }
                )
            }
        }.toString()
        return json to picked.size
    }

    private fun resolveImage(file: PFile): StoredImage? {
        val candidates = buildList {
            file.exportPath?.let { path ->
                val clean = cleanPath(path)
                add(clean)
                add(clean.substringAfterLast('/'))
            }
            if (file.name.isNotBlank()) {
                add(file.name)
                add(file.name.substringAfterLast('/'))
            }
        }
        candidates.forEach { candidate ->
            if (candidate.isBlank()) return@forEach
            images[candidate]?.let { return it }
            images[candidate.lowercase()]?.let { return it }
        }
        return null
    }

    private fun sourcesJson(sources: List<SourceRef>): Pair<String?, Int> {
        if (sources.isEmpty()) return null to 0
        val seen = HashSet<String>()
        val picked = ArrayList<SourceRef>()
        sources.forEach { source ->
            val key = source.url.ifBlank { source.title }
            if (key.isBlank() || !seen.add(key)) return@forEach
            picked += source
        }
        if (picked.isEmpty()) return null to 0
        val limited = picked.take(MAX_SOURCES)
        val json = JSONArray().apply {
            limited.forEach { source ->
                put(
                    JSONObject().apply {
                        put("url", source.url)
                        put("title", source.title.ifBlank { source.url })
                        if (source.site.isNotBlank()) put("site", source.site)
                        if (source.snippet.isNotBlank()) put("snippet", source.snippet.take(300))
                    }
                )
            }
        }.toString()
        return json to limited.size
    }

    private fun hostOf(url: String): String {
        if (url.isBlank()) return ""
        return runCatching {
            val afterScheme = url.substringAfter("://", url)
            afterScheme.substringBefore('/').substringBefore('?').removePrefix("www.")
        }.getOrDefault("")
    }

    // ---------------------------------------------------------------
    // 小工具
    // ---------------------------------------------------------------

    private class IndexRow(
        val id: String,
        val title: String,
        val insertedAt: Long,
        val updatedAt: Long,
    )

    private class PConv(val id: String) {
        var title: String = ""
        var createdAt: Long = 0L
        var updatedAt: Long = 0L
        val msgs = mutableListOf<PMsg>()
    }

    private class PMsg(
        val role: String,
        val text: String,
        val reasoning: String?,
        val time: Long,
        val files: List<PFile>,
        val sources: List<SourceRef> = emptyList(),
    )

    private class PFile(
        val id: String,
        val name: String,
        val exportPath: String?,
        val isImage: Boolean,
    )

    private class SourceRef(
        val url: String,
        val title: String,
        val site: String,
        val snippet: String,
    )

    private companion object {
        /** 单个 json 最多读这么多字节 */
        const val MAX_JSON_BYTES = 64L * 1024 * 1024

        /** 单张图片最多这么大 */
        const val MAX_IMAGE_BYTES = 12L * 1024 * 1024

        /** 一次最多取这么多张图 */
        const val MAX_IMAGES = 3000

        /** 图片总量上限 */
        const val MAX_IMAGE_TOTAL_BYTES = 300L * 1024 * 1024

        /** 一条回答最多留这么多个来源 */
        const val MAX_SOURCES = 12

        val FIRST_OF_KEYS = listOf(
            "conversations", "sessions", "chat_sessions", "data", "items", "list", "history",
        )

        val MESSAGE_KEYS = listOf(
            "messages", "chat_messages", "chat_messages_list", "items", "history", "records",
        )

        val UUID_PATTERN =
            Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

        /** 读一段字节，超上限返回 null */
        fun readLimited(input: InputStream, limit: Long): ByteArray? {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val read = try {
                    input.read(buffer)
                } catch (e: Throwable) {
                    break
                }
                if (read <= 0) break
                total += read
                if (total > limit) return null
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }

        /** 字节 → JSON。坏数据返回 null，绝不抛 */
        fun parseJson(bytes: ByteArray): Any? {
            if (bytes.isEmpty()) return null
            val text = String(bytes, Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r', '\t')
            if (text.isEmpty()) return null
            return runCatching { JSONTokener(text).nextValue() }.getOrNull()
        }

        fun str(obj: JSONObject, key: String): String {
            if (!obj.has(key) || obj.isNull(key)) return ""
            return when (val value = obj.opt(key)) {
                is String -> value.trim().let { if (it == "null") "" else it }
                is Number, is Boolean -> value.toString()
                else -> ""
            }
        }

        fun numOf(obj: JSONObject, key: String): Long? {
            if (!obj.has(key) || obj.isNull(key)) return null
            return when (val value = obj.opt(key)) {
                is Number -> value.toLong()
                is String -> value.trim().toLongOrNull()
                else -> null
            }
        }

        fun firstValueOf(obj: JSONObject, vararg keys: String): Any? {
            keys.forEach { key ->
                val value = obj.opt(key)
                if (value != null && value != JSONObject.NULL) return value
            }
            return null
        }

        fun extractText(value: Any?): String {
            if (value == null || value == JSONObject.NULL) return ""
            return when (value) {
                is String -> value
                is JSONArray -> (0 until value.length()).joinToString("") { i -> extractText(value.opt(i)) }
                is JSONObject -> {
                    val parts = value.optJSONArray("parts")
                    when {
                        parts != null -> (0 until parts.length()).joinToString("") { i ->
                            extractText(parts.opt(i))
                        }
                        value.has("text") -> str(value, "text")
                        value.has("content") -> extractText(value.opt("content"))
                        else -> ""
                    }
                }
                else -> ""
            }
        }

        fun roleOf(message: JSONObject): String? {
            val raw = str(message, "role").ifBlank {
                message.optJSONObject("author")?.let { str(it, "role") }.orEmpty()
            }.ifBlank { str(message, "sender") }.uppercase()
            return when {
                raw.contains("USER") || raw.contains("HUMAN") || raw == "0" -> "USER"
                raw.contains("ASSISTANT") || raw.contains("BOT") || raw.contains("MODEL") ||
                    raw.contains("AI") || raw == "1" -> "ASSISTANT"
                else -> if (message.has("is_user")) {
                    if (message.optBoolean("is_user")) "USER" else "ASSISTANT"
                } else {
                    null
                }
            }
        }

        fun parseTime(value: Any?): Long = try {
            when (value) {
                is Number -> normalizeEpoch(value.toLong())
                is String -> if (value.isBlank()) 0L else {
                    runCatching { Instant.parse(value).toEpochMilli() }
                        .recoverCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
                        .getOrElse { value.toLongOrNull()?.let { normalizeEpoch(it) } ?: 0L }
                }
                else -> 0L
            }
        } catch (e: Throwable) {
            0L
        }

        fun normalizeEpoch(value: Long): Long =
            if (value <= 0) 0L else if (value < 10_000_000_000L) value * 1000 else value

        fun cleanPath(path: String): String = path.replace('\\', '/').trim().trimStart('.', '/')

        fun shortName(name: String): String =
            if (name.length <= 42) name else "…" + name.takeLast(42)

        /** chat_sessions/<uuid>.json → uuid */
        fun idHintFrom(name: String): String? {
            UUID_PATTERN.find(name)?.value?.let { return it }
            val base = name.substringAfterLast('/').substringBeforeLast('.').trim()
            return base.ifBlank { null }?.take(40)
        }
    }
}
