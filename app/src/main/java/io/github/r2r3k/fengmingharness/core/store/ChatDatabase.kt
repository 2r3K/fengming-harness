package io.github.r2r3k.fengmingharness.core.store

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import java.io.File

data class ConversationRow(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val totalTokens: Int = 0,
    val totalCached: Int = 0,
    val totalCost: Double = 0.0,
)

data class MessageRow(
    val id: String,
    val role: String,
    val content: String,
    val reasoning: String?,
    val status: String,
    val seq: Int,
    val sources: String?,
    val images: String?,
    val tokensIn: Int = 0,
    val tokensCached: Int = 0,
    val tokensOut: Int = 0,
    val cost: Double = 0.0,
    /** 这条消息的时间（毫秒）；导出成官方格式时当 inserted_at 用 */
    val createdAt: Long = 0L,
    /** 这条回答用过的工具，JSON 数组 */
    val tools: String? = null,
)

/** 账单页用：某一个月的用量 */
data class MonthUsage(
    val month: String,
    val tokens: Int,
    val cached: Int,
    val cost: Double,
    val requests: Int,
)

/**
 * 聊天记录存本机 SQLite。不引 Room：注解处理插件和这版 AGP 的内置 Kotlin 容易打架。
 *
 * v1  conversation + message 基础表
 * v2  message.sources（联网引用）
 * v3  message.image_json / tokens_in / tokens_cached / tokens_out / cost
 *     conversation.total_tokens / total_cached / total_cost
 */
class ChatDatabase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        // 这里必须是最新结构（含后加的列）：onUpgrade 只在旧库上跑，
        // 全新安装走的是 onCreate，漏一列就会在查的时候崩
        db.execSQL(
            "CREATE TABLE conversation(" +
                "id TEXT PRIMARY KEY," +
                "title TEXT NOT NULL," +
                "created_at INTEGER NOT NULL," +
                "updated_at INTEGER NOT NULL," +
                "total_tokens INTEGER NOT NULL DEFAULT 0," +
                "total_cached INTEGER NOT NULL DEFAULT 0," +
                "total_cost REAL NOT NULL DEFAULT 0," +
                "folded_count INTEGER NOT NULL DEFAULT 0," +
                "folded_summary TEXT)"
        )
        createMessageTable(db, withExtras = true)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_message_conv ON message(conversation_id, seq)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_message_created ON message(created_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_conversation_updated ON conversation(updated_at)")
    }

    private fun createMessageTable(db: SQLiteDatabase, withExtras: Boolean) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS message(" +
                "id TEXT PRIMARY KEY," +
                "conversation_id TEXT NOT NULL," +
                "role TEXT NOT NULL," +
                "content TEXT NOT NULL," +
                "reasoning TEXT," +
                "status TEXT NOT NULL," +
                "seq INTEGER NOT NULL," +
                "created_at INTEGER NOT NULL," +
                "sources TEXT," +
                "image_json TEXT," +
                "tokens_in INTEGER NOT NULL DEFAULT 0," +
                "tokens_cached INTEGER NOT NULL DEFAULT 0," +
                "tokens_out INTEGER NOT NULL DEFAULT 0," +
                "cost REAL NOT NULL DEFAULT 0," +
                "tools TEXT)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 整个升级包在一个事务里，中途失败整体回滚，避免"半升级"的坏库
        db.beginTransaction()
        try {
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE message ADD COLUMN sources TEXT")
            }
            if (oldVersion < 3) {
                db.execSQL("ALTER TABLE message ADD COLUMN image_json TEXT")
                db.execSQL("ALTER TABLE message ADD COLUMN tokens_in INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE message ADD COLUMN tokens_cached INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE message ADD COLUMN tokens_out INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE message ADD COLUMN cost REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE conversation ADD COLUMN total_tokens INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE conversation ADD COLUMN total_cached INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE conversation ADD COLUMN total_cost REAL NOT NULL DEFAULT 0")
            }
            if (oldVersion < 4) {
                db.execSQL("ALTER TABLE conversation ADD COLUMN folded_count INTEGER NOT NULL DEFAULT 0")
            }
            if (oldVersion < 5) {
                db.execSQL("ALTER TABLE message ADD COLUMN tools TEXT")
            }
            if (oldVersion < 6) {
                db.execSQL("ALTER TABLE conversation ADD COLUMN folded_summary TEXT")
            }
            if (oldVersion < 7) {
                // v7：补索引。v1 建库时只建了 idx_message_conv，
                // 老库（升级上来的）其实一直没索引，消息一多就慢
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_message_conv ON message(conversation_id, seq)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_message_created ON message(created_at)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_conversation_updated ON conversation(updated_at)")
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }    }

    // ---------- conversation ----------

    fun createConversation(id: String, title: String, now: Long) {
        val values = ContentValues().apply {
            put("id", id)
            put("title", title)
            put("created_at", now)
            put("updated_at", now)
        }
        writableDatabase.insert("conversation", null, values)
    }

    fun listConversations(): List<ConversationRow> {
        val result = mutableListOf<ConversationRow>()
        readableDatabase.query(
            "conversation",
            arrayOf("id", "title", "updated_at", "total_tokens", "total_cached", "total_cost"),
            null, null, null, null,
            "updated_at DESC",
            "300",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += ConversationRow(
                    id = cursor.getString(0),
                    title = cursor.getString(1),
                    updatedAt = cursor.getLong(2),
                    totalTokens = cursor.getInt(3),
                    totalCached = cursor.getInt(4),
                    totalCost = cursor.getDouble(5),
                )
            }
        }
        return result
    }

    fun renameConversation(id: String, title: String) {
        val values = ContentValues().apply { put("title", title) }
        writableDatabase.update("conversation", values, "id = ?", arrayOf(id))
    }

    fun deleteConversation(id: String) {
        writableDatabase.delete("message", "conversation_id = ?", arrayOf(id))
        writableDatabase.delete("conversation", "id = ?", arrayOf(id))
    }

    fun touchConversation(id: String, now: Long) {
        val values = ContentValues().apply { put("updated_at", now) }
        writableDatabase.update("conversation", values, "id = ?", arrayOf(id))
    }

    /** 这个会话已经折叠掉多少条最早的消息（切走再回来也要记住） */
    fun foldedCount(id: String): Int {
        readableDatabase.rawQuery("SELECT folded_count FROM conversation WHERE id = ?", arrayOf(id)).use {
            if (it.moveToFirst()) return it.getInt(0)
        }
        return 0
    }

    fun setFoldedCount(id: String, count: Int) {
        val values = ContentValues().apply { put("folded_count", count) }
        writableDatabase.update("conversation", values, "id = ?", arrayOf(id))
    }

    /** 被折叠掉的那部分对话的摘要（折叠不再"直接丢掉"，而是压成几行字） */
    fun foldedSummary(id: String): String {
        readableDatabase.rawQuery("SELECT folded_summary FROM conversation WHERE id = ?", arrayOf(id)).use {
            if (it.moveToFirst()) return it.getString(0).orEmpty()
        }
        return ""
    }

    fun setFoldedSummary(id: String, summary: String) {
        val values = ContentValues().apply { put("folded_summary", summary) }
        writableDatabase.update("conversation", values, "id = ?", arrayOf(id))
    }

    // ---------- 导入官方导出 ----------

    /** 导入用的一行消息；imagesJson/sourcesJson 和聊天里的格式一致 */
    data class ImportRow(
        val role: String,
        val content: String,
        val reasoning: String?,
        val imagesJson: String? = null,
        val sourcesJson: String? = null,
        val time: Long = 0L,
    )

    fun conversationExists(id: String): Boolean {
        readableDatabase.rawQuery("SELECT 1 FROM conversation WHERE id = ? LIMIT 1", arrayOf(id)).use {
            return it.moveToFirst()
        }
    }

    /**
     * 一次性写入一个导入的对话（连消息一起，走事务）。
     * 已经存在同 id 的直接跳过，返回 false。
     */
    fun insertConversationWithMessages(
        id: String,
        title: String,
        createdAt: Long,
        updatedAt: Long,
        rows: List<ImportRow>,
    ): Boolean {
        if (rows.isEmpty()) return false
        if (conversationExists(id)) return false

        val db = writableDatabase
        db.beginTransaction()
        try {
            val conversation = ContentValues().apply {
                put("id", id)
                put("title", title)
                put("created_at", createdAt)
                put("updated_at", updatedAt)
            }
            db.insert("conversation", null, conversation)

            rows.forEachIndexed { index, row ->
                val values = ContentValues().apply {
                    put("id", "$id-$index")
                    put("conversation_id", id)
                    put("role", row.role)
                    put("content", row.content)
                    put("reasoning", row.reasoning)
                    put("status", "done")
                    put("seq", index + 1)
                    val stamp = if (row.time > 0) row.time else createdAt + index * 1000L
                    put("created_at", stamp)
                    if (!row.imagesJson.isNullOrBlank()) put("image_json", row.imagesJson)
                    if (!row.sourcesJson.isNullOrBlank()) put("sources", row.sourcesJson)
                }
                db.insert("message", null, values)
            }
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    // ---------- 账单 ----------

    /** 按月汇总花费（最近 n 个月，新的在前） */
    fun usageByMonth(limit: Int = 12): List<MonthUsage> {
        val result = mutableListOf<MonthUsage>()
        readableDatabase.rawQuery(
            "SELECT strftime('%Y-%m', created_at / 1000, 'unixepoch', 'localtime') AS month, " +
                "SUM(tokens_in + tokens_cached + tokens_out), " +
                "SUM(tokens_cached), SUM(cost), COUNT(*) " +
                "FROM message WHERE role = 'assistant' " +
                "GROUP BY month ORDER BY month DESC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += MonthUsage(
                    month = cursor.getString(0) ?: "未知",
                    tokens = cursor.getInt(1),
                    cached = cursor.getInt(2),
                    cost = cursor.getDouble(3),
                    requests = cursor.getInt(4),
                )
            }
        }
        return result
    }

    /** 最近 n 天花在哪（用来画"每天都在烧钱吗"） */
    fun usageByDay(limit: Int = 14): List<MonthUsage> {
        val result = mutableListOf<MonthUsage>()
        readableDatabase.rawQuery(
            "SELECT strftime('%m-%d', created_at / 1000, 'unixepoch', 'localtime') AS day, " +
                "SUM(tokens_in + tokens_cached + tokens_out), SUM(tokens_cached), SUM(cost), COUNT(*) " +
                "FROM message WHERE role = 'assistant' " +
                "GROUP BY day ORDER BY day DESC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += MonthUsage(
                    month = cursor.getString(0) ?: "?",
                    tokens = cursor.getInt(1),
                    cached = cursor.getInt(2),
                    cost = cursor.getDouble(3),
                    requests = cursor.getInt(4),
                )
            }
        }
        return result
    }

    /** 累加一次请求的用量到会话总计 */
    fun addConversationUsage(id: String, tokens: Int, cached: Int, cost: Double) {
        writableDatabase.execSQL(
            "UPDATE conversation SET total_tokens = total_tokens + ?, " +
                "total_cached = total_cached + ?, total_cost = total_cost + ? WHERE id = ?",
            arrayOf<Any>(tokens, cached, cost, id),
        )
    }

    // ---------- message ----------

    fun appendMessage(
        id: String,
        conversationId: String,
        role: String,
        content: String,
        reasoning: String?,
        status: String,
        seq: Int,
        now: Long,
        imageJson: String? = null,
    ) {
        val values = ContentValues().apply {
            put("id", id)
            put("conversation_id", conversationId)
            put("role", role)
            put("content", content)
            put("reasoning", reasoning)
            put("status", status)
            put("seq", seq)
            put("created_at", now)
            if (imageJson != null) put("image_json", imageJson)
        }
        writableDatabase.insert("message", null, values)
    }

    fun updateMessage(
        id: String,
        content: String,
        reasoning: String?,
        status: String,
        sources: String? = null,
    ) {
        val values = ContentValues().apply {
            put("content", content)
            put("reasoning", reasoning)
            put("status", status)
            if (sources != null) put("sources", sources)
        }
        writableDatabase.update("message", values, "id = ?", arrayOf(id))
    }

    fun finalizeMessage(
        id: String,
        content: String,
        reasoning: String?,
        status: String,
        sources: String?,
        tokensIn: Int,
        tokensCached: Int,
        tokensOut: Int,
        cost: Double,
        toolsJson: String? = null,
    ) {
        val values = ContentValues().apply {
            put("content", content)
            put("reasoning", reasoning)
            put("status", status)
            if (sources != null) put("sources", sources)
            if (toolsJson != null) put("tools", toolsJson)
            put("tokens_in", tokensIn)
            put("tokens_cached", tokensCached)
            put("tokens_out", tokensOut)
            put("cost", cost)
        }
        writableDatabase.update("message", values, "id = ?", arrayOf(id))
    }

    /** 只改一条消息的图片/文档列表（比如用户把某个文档移除） */
    fun updateImages(id: String, imageJson: String?) {
        val values = ContentValues().apply { put("image_json", imageJson) }
        writableDatabase.update("message", values, "id = ?", arrayOf(id))
    }

    fun deleteMessage(id: String) {
        writableDatabase.delete("message", "id = ?", arrayOf(id))
    }

    // ---------- 体检 / 清理 ----------

    /**
     * 上次被强杀、断网、崩溃时，可能有回答还卡在 "streaming" 状态，
     * 界面上会一直转圈。启动时把它标成 "stopped"（已中断），内容不动。
     * @return 修了几条
     */
    fun finalizeStaleMessages(): Int = runCatching {
        writableDatabase.update(
            "message",
            ContentValues().apply { put("status", "stopped") },
            "status = ?",
            arrayOf("streaming"),
        )
    }.getOrDefault(0)

    /** 所有消息里记录过的图片/文档 json（体检时用来判断哪些本地文件没人引用） */
    fun allImageJson(): List<String> {
        val result = mutableListOf<String>()
        readableDatabase.rawQuery(
            "SELECT image_json FROM message WHERE image_json IS NOT NULL AND image_json != ''",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                cursor.getString(0)?.takeIf { it.isNotBlank() }?.let { result += it }
            }
        }
        return result
    }

    /** (对话数, 消息数)，体检页显示用 */
    fun counts(): Pair<Int, Int> {
        var conversations = 0
        var messages = 0
        readableDatabase.rawQuery("SELECT COUNT(*) FROM conversation", null).use {
            if (it.moveToFirst()) conversations = it.getInt(0)
        }
        readableDatabase.rawQuery("SELECT COUNT(*) FROM message", null).use {
            if (it.moveToFirst()) messages = it.getInt(0)
        }
        return conversations to messages
    }

    /**
     * 恢复备份后，把消息里的图片绝对路径改写成**本机**的路径。
     *
     * 为什么需要：`image_json` 里存的是 `/data/user/0/<包名>/files/images/xxx.jpg`。
     * 如果备份来自别的包名（比如换了包名的版本），路径就对不上，会出现"文字在、图是空白"。
     * 这里统一把任何含 `/files/images/` 的路径拍成 `imagesDir/文件名`。
     *
     * @return 改了多少条消息
     */
    fun rewriteImagePaths(imagesDir: File): Int {
        val rows = mutableListOf<Pair<String, String>>()
        readableDatabase.rawQuery(
            "SELECT id, image_json FROM message WHERE image_json IS NOT NULL AND image_json LIKE '%/files/images/%'",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                val json = cursor.getString(1) ?: continue
                rows += id to json
            }
        }
        if (rows.isEmpty()) return 0

        var changed = 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            rows.forEach { (id, json) ->
                val rewritten = runCatching {
                    val array = JSONArray(json)
                    var dirty = false
                    for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i) ?: continue
                        listOf("path", "thumb").forEach { key ->
                            val old = item.optString(key)
                            if (old.contains("/files/images/")) {
                                val name = old.substringAfterLast('/')
                                if (name.isNotBlank()) {
                                    val fresh = File(imagesDir, name).absolutePath
                                    if (fresh != old) {
                                        item.put(key, fresh)
                                        dirty = true
                                    }
                                }
                            }
                        }
                    }
                    if (dirty) array.toString() else null
                }.getOrNull()
                if (rewritten != null) {
                    db.update(
                        "message",
                        ContentValues().apply { put("image_json", rewritten) },
                        "id = ?",
                        arrayOf(id),
                    )
                    changed++
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return changed
    }

    fun loadMessages(conversationId: String): List<MessageRow> {
        val result = mutableListOf<MessageRow>()
        readableDatabase.query(
            "message",
            arrayOf(
                "id", "role", "content", "reasoning", "status", "seq", "sources", "image_json",
                "tokens_in", "tokens_cached", "tokens_out", "cost", "tools", "created_at",
            ),
            "conversation_id = ?",
            arrayOf(conversationId),
            null, null,
            "seq ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += MessageRow(
                    id = cursor.getString(0),
                    role = cursor.getString(1),
                    content = cursor.getString(2),
                    reasoning = cursor.getString(3),
                    status = cursor.getString(4),
                    seq = cursor.getInt(5),
                    sources = cursor.getString(6),
                    images = cursor.getString(7),
                    tokensIn = cursor.getInt(8),
                    tokensCached = cursor.getInt(9),
                    tokensOut = cursor.getInt(10),
                    cost = cursor.getDouble(11),
                    tools = cursor.getString(12),
                    createdAt = cursor.getLong(13),
                )
            }
        }
        return result
    }

    companion object {
        private const val DB_NAME = "harness.db"
        private const val DB_VERSION = 7
    }
}
