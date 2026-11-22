package io.github.r2r3k.fengmingharness.core.store

import android.content.Context
import io.github.r2r3k.fengmingharness.core.DiagLog
import java.io.File

/**
 * 每次打开 App 跑一遍的小扫除（在后台线程，几十毫秒）。
 *
 * 干两件事：
 *   1. 上次被强杀/断网时可能留下"正在回答"的消息，界面上会一直转圈 —— 标成已中断；
 *   2. 导入会先把 zip 复制到缓存目录，导出也会写临时包，崩溃时可能没删掉 —— 清掉。
 *
 * 注意：这里**绝不动**聊天记录和图片，只碰状态标记和缓存临时文件。
 */
object Housekeeping {

    fun onStart(context: Context) {
        runCatching {
            val db = ChatDatabase(context)
            val fixed = db.finalizeStaleMessages()
            if (fixed > 0) DiagLog.log("startup", "把 $fixed 条卡住的回答标成已中断")
        }.onFailure { DiagLog.log("startup", "修状态失败：${it.message}") }

        runCatching {
            listOf("import", "export").forEach { name ->
                File(context.cacheDir, name).listFiles()?.forEach { file ->
                    runCatching { file.deleteRecursively() }
                }
            }
        }.onFailure { DiagLog.log("startup", "清缓存失败：${it.message}") }
    }
}
