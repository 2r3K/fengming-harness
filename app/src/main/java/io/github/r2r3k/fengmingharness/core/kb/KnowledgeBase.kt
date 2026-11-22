package io.github.r2r3k.fengmingharness.core.kb

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class KbDoc(
    val id: Long,
    val name: String,
    val path: String,
    val size: Long,
)

data class KbHit(
    val docName: String,
    val path: String,
    val text: String,
    val score: Int,
)

data class KbStats(
    val docs: Int = 0,
    val chunks: Int = 0,
    val chars: Long = 0,
    val indexedAt: Long = 0,
)

/**
 * 本地资料库：把用户授权目录里的文本文件切块存起来，检索完全在手机上做，不联网。
 *
 * 表结构：
 *   doc(id, name, path, size, modified)
 *   chunk(id, doc_id, ord, text)
 *   meta(key, value)
 */
class KnowledgeBase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE doc(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT NOT NULL," +
                "path TEXT NOT NULL," +
                "size INTEGER NOT NULL," +
                "modified INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE chunk(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "doc_id INTEGER NOT NULL," +
                "ord INTEGER NOT NULL," +
                "text TEXT NOT NULL)"
        )
        db.execSQL("CREATE INDEX idx_chunk_doc ON chunk(doc_id)")
        db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun clearAll() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("chunk", null, null)
            db.delete("doc", null, null)
            db.delete("meta", null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun startIndex(): Long {
        clearAll()
        return System.currentTimeMillis()
    }

    fun addDoc(name: String, path: String, size: Long, now: Long): Long {
        val values = ContentValues().apply {
            put("name", name)
            put("path", path)
            put("size", size)
            put("modified", now)
        }
        return writableDatabase.insert("doc", null, values)
    }

    fun addChunk(docId: Long, ord: Int, text: String) {
        val values = ContentValues().apply {
            put("doc_id", docId)
            put("ord", ord)
            put("text", text)
        }
        writableDatabase.insert("chunk", null, values)
    }

    fun finishIndex(indexedAt: Long) {
        val values = ContentValues().apply {
            put("key", "indexedAt")
            put("value", indexedAt.toString())
        }
        writableDatabase.insertWithOnConflict("meta", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun stats(): KbStats {
        val db = readableDatabase
        var docs = 0
        var chunks = 0
        var chars = 0L
        var indexedAt = 0L

        db.rawQuery("SELECT COUNT(*) FROM doc", null).use { if (it.moveToFirst()) docs = it.getInt(0) }
        db.rawQuery("SELECT COUNT(*), COALESCE(SUM(LENGTH(text)),0) FROM chunk", null).use {
            if (it.moveToFirst()) {
                chunks = it.getInt(0)
                chars = it.getLong(1)
            }
        }
        db.rawQuery("SELECT value FROM meta WHERE key = 'indexedAt'", null).use {
            if (it.moveToFirst()) indexedAt = it.getString(0).toLongOrNull() ?: 0L
        }
        return KbStats(docs, chunks, chars, indexedAt)
    }

    /**
     * 检索：先用 SQL 粗筛（LIKE 命中任一词），再在内存里按命中次数打分。
     * 语料规模（几千个片段）下足够快，也不必依赖 FTS5。
     */
    fun search(rawQuery: String, limit: Int = 4): List<KbHit> {
        val terms = queryTerms(rawQuery)
        if (terms.isEmpty()) return emptyList()

        val where = terms.joinToString(" OR ") { "text LIKE ?" }
        val args = terms.map { "%$it%" }.toTypedArray()

        val candidates = mutableListOf<Triple<String, String, String>>() // 名称 / 路径 / 文本
        readableDatabase.rawQuery(
            "SELECT d.name, d.path, c.text FROM chunk c JOIN doc d ON d.id = c.doc_id " +
                "WHERE $where LIMIT 2000",
            args,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                candidates += Triple(cursor.getString(0), cursor.getString(1), cursor.getString(2))
            }
        }

        return candidates
            .map { (name, path, text) ->
                val lower = text.lowercase()
                var score = 0
                for (term in terms) {
                    var index = lower.indexOf(term)
                    var hits = 0
                    while (index >= 0 && hits < 5) {
                        hits++
                        score += 1
                        index = lower.indexOf(term, index + term.length)
                    }
                }
                KbHit(name, path, text, score)
            }
            .filter { it.score > 0 }
            .sortedByDescending { it.score }
            .take(limit)
    }

    companion object {
        private const val DB_NAME = "kb.db"

        /** 中文按两字切（bigram），英文按单词 */
        fun queryTerms(raw: String): List<String> {
            val terms = mutableListOf<String>()
            Regex("[A-Za-z0-9_]{2,}").findAll(raw).forEach { terms += it.value.lowercase() }

            Regex("[\\u4e00-\\u9fff]+").findAll(raw).forEach { m ->
                val s = m.value
                when {
                    s.length == 1 -> terms += s
                    s.length == 2 -> terms += s
                    else -> for (i in 0..s.length - 2) terms += s.substring(i, i + 2)
                }
            }
            return terms.distinct().take(12)
        }
    }
}
