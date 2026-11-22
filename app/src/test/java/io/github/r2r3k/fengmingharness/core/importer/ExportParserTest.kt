package io.github.r2r3k.fengmingharness.core.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 导出包解析器的单元测试（跑在电脑的 JVM 上，不需要手机）。
 *
 * 跑法：
 * ```
 * .\gradlew.bat test --tests "io.github.r2r3k.fengmingharness.core.importer.ExportParserTest.*" --info
 * ```
 * 报告在 `app/build/reports/tests/testDebugUnitTest/index.html`。
 *
 * 真实包用 `-DdeepseekExportZip=...` 或环境变量 `DEEPSEEK_EXPORT_ZIP` 指定，
 * 也可以直接放到 `sample/deepseek_data.zip`（见 sample/README.md）。
 * 文件不存在时"真包测试"会自动跳过（不会让构建失败）。
 */
class ExportParserTest {

    /** 只数图片张数的假落盘器：不真的解码图片，方便在电脑上跑 */
    private class CountingSink : ImageSink {
        var count = 0
        var bytesTotal = 0L
        override fun store(entryName: String, bytes: ByteArray): StoredImage? {
            count++
            bytesTotal += bytes.size
            return StoredImage("mem:/$entryName", "mem:/$entryName.thumb")
        }
    }

    private fun realZip(): File {
        val configured = System.getProperty("deepseekExportZip")
            ?: System.getenv("DEEPSEEK_EXPORT_ZIP")
            ?: "sample/deepseek_data.zip"
        return File(configured)
    }

    private fun parseSingleJson(text: String, sink: ImageSink? = null): ImportScan {
        val parser = ExportParser(sink)
        parser.readJsonDocument({ ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)) }, "test.json")
        return parser.finish()
    }

    /** 把内存里的 zip 字节写到临时文件再解析（解析器走 ZipFile，要真实文件） */
    private fun parseZip(zip: ByteArray, sink: ImageSink? = null): ImportScan {
        val temp = File.createTempFile("export-test", ".zip")
        try {
            temp.writeBytes(zip)
            val parser = ExportParser(sink)
            parser.readZip(temp, "test.zip")
            return parser.finish()
        } finally {
            runCatching { temp.delete() }
        }
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    // =================================================================
    // 1. 拿真实的 124MB 包跑
    // =================================================================

    @Test
    fun realExportZipParsesCompletely() {
        val zip = realZip()
        assumeTrue("没找到真实导出包，跳过这个测试：${zip.absolutePath}", zip.isFile)

        val sink = CountingSink()
        val parser = ExportParser(sink)
        parser.readZip(zip, zip.name)
        val scan = parser.finish()

        // 打一份人能看的报告
        println("================ 真实导出包解析结果 ================")
        scan.sessions.forEach { session ->
            println(
                "%-26s  消息 %4d  你问 %4d  图 %3d  来源 %3d".format(
                    session.title,
                    session.messages.size,
                    session.messages.count { it.role == "user" },
                    session.messages.sumOf { it.imageCount },
                    session.messages.sumOf { it.sourceCount },
                )
            )
        }
        println("合计：${scan.sessions.size} 个对话 / ${scan.totalMessages} 条消息 / " +
            "你问 ${scan.userMessages} 轮 / 图片 ${sink.count} 张(${sink.bytesTotal / 1024 / 1024}MB) / " +
            "来源 ${scan.sourceCount} 条 / 跳过 ${scan.skipped}")
        scan.notes.forEach { println("提示：$it") }
        println("==================================================")

        // 这份包是我们手里固定的样本，数字是硬指标
        assertEquals("对话个数不对", 6, scan.sessions.size)
        assertEquals("消息总数不对", 2820, scan.totalMessages)
        assertEquals("用户提问条数不对", 1410, scan.userMessages)
        assertTrue("一张图都没取出来", sink.count > 900)
        assertTrue("来源一条都没有", scan.sourceCount > 0)

        scan.sessions.forEach { session ->
            assertTrue("标题空了：${session.sourceId}", session.title.isNotBlank())
            assertTrue("时间戳没解析出来：${session.sourceId}", session.updatedAt > 1_700_000_000_000L)
            session.messages.forEach { message ->
                assertTrue("角色不对：${message.role}", message.role == "user" || message.role == "assistant")
                assertTrue(
                    "这条消息什么都没有：${session.title}",
                    message.text.isNotBlank() || !message.reasoning.isNullOrBlank() || message.imageCount > 0,
                )
                // 思考过程必须是"有折叠区"的那种：reasoning 非空并且不是正文的复制
                if (!message.reasoning.isNullOrBlank()) {
                    assertTrue(
                        "思考过程长度异常：${message.reasoning!!.length}",
                        message.reasoning!!.length in 1..2_000_000,
                    )
                }
                // 来源 json 必须能被 App 那边解析（格式和 sourcesToJson 一致）
                message.sourcesJson?.let { raw ->
                    assertTrue("来源 json 不像数组：$raw", raw.trimStart().startsWith("["))
                    assertTrue("来源里没有 url：$raw", raw.contains("\"url\""))
                }
                // 图片 json 必须是 local: 前缀（只在本机显示，不发给模型）
                message.imagesJson?.let { raw ->
                    assertTrue("图片标记不对：$raw", raw.contains("\"local:"))
                }
            }
        }
    }

    // =================================================================
    // 2. 未知分片类型 / 坏数据：一条都不许崩
    // =================================================================

    @Test
    fun unknownFragmentTypesAndGarbageDoNotCrash() {
        val json = """
        {
          "id": "s1",
          "title": "脏数据测试",
          "current_message_id": 2,
          "inserted_at": "2026-11-20T18:17:13.658Z",
          "messages": [
            {"id": 1, "parent_id": null, "role": "USER", "status": "FINISHED",
             "inserted_at": "2026-11-20T18:17:13.658Z",
             "fragments": [
               {"type": "REQUEST", "content": "你好"},
               {"type": "SOME_FUTURE_TYPE_V9", "content": "官方以后新加的类型"},
               {"type": "REQUEST"},
               123, null, {}, "字符串也会出现在数组里"
             ]},
            {"id": 2, "parent_id": 1, "role": "ASSISTANT", "status": "FINISHED",
             "inserted_at": "2026-11-20T18:17:20.000Z",
             "fragments": [
               {"type": "THINK", "content": "先想一下"},
               {"type": "RESPONSE", "content": "答复正文"},
               {"type": "TOOL_SEARCH", "queries": ["q"], "results": [
                  {"url": "https://example.com/a", "title": "示例 A", "site_name": "示例站", "snippet": "摘要"},
                  {"title": "只有标题没有网址"},
                  "坏结果", null, {}
               ]},
               {"type": "TOOL_SEARCH"},
               {"type": "TIP", "content": "小提示，不该进正文"}
             ]},
            {"id": 3, "parent_id": 99, "role": "ASSISTANT", "status": "FINISHED",
             "inserted_at": "这不是时间", "fragments": "不是数组"}
          ]
        }
        """.trimIndent()

        val scan = parseSingleJson(json)
        assertEquals(1, scan.sessions.size)
        val messages = scan.sessions[0].messages
        assertEquals(2, messages.size)
        assertEquals("user", messages[0].role)
        assertEquals("你好", messages[0].text)
        assertEquals("assistant", messages[1].role)
        assertEquals("答复正文", messages[1].text)
        assertEquals("先想一下", messages[1].reasoning)
        // 坏结果被跳过，好结果留下：1 个有 url 的 + 1 个只有标题的
        assertEquals(2, messages[1].sourceCount)
        assertNotNull(messages[1].sourcesJson)
        assertTrue(messages[1].sourcesJson!!.contains("https://example.com/a"))
        assertTrue(messages[1].sourcesJson!!.contains("示例站"))
        // TIP 不进正文
        assertTrue(!messages[1].text.contains("小提示"))
    }

    @Test
    fun brokerJsonAndEmptyFilesDoNotCrash() {
        assertTrue(parseSingleJson("{ 这不是 json ").sessions.isEmpty())
        assertTrue(parseSingleJson("").sessions.isEmpty())
        assertTrue(parseSingleJson("null").sessions.isEmpty())
        assertTrue(parseSingleJson("[]").sessions.isEmpty())
        assertTrue(parseSingleJson("""{"id":"x"}""").sessions.isEmpty())
        // messages 不是数组
        assertTrue(parseSingleJson("""{"id":"x","messages":"坏了"}""").sessions.isEmpty())
        // 空消息数组
        assertTrue(parseSingleJson("""{"id":"x","messages":[]}""").sessions.isEmpty())
    }

    @Test
    fun bomPrefixedJsonIsAccepted() {
        val json = "\uFEFF" + """
        {"id":"bom","title":"带 BOM 的文件","messages":[
          {"id":1,"parent_id":null,"role":"USER","fragments":[{"type":"REQUEST","content":"在吗"}]}
        ]}
        """.trimIndent()
        val scan = parseSingleJson(json)
        assertEquals(1, scan.sessions.size)
        assertEquals("带 BOM 的文件", scan.sessions[0].title)
        assertEquals("在吗", scan.sessions[0].messages[0].text)
    }

    // =================================================================
    // 3. 官方结构：目录 + 会话文件按 id 合并、分支、撤回
    // =================================================================

    @Test
    fun indexAndSessionFileMergeById() {
        val zip = zipOf(
            "manifest.json" to """{"export_format_version":3}""",
            "user.json" to """{"name":"tester"}""",
            "chat_session_index.json" to """
                [{"id":"s1","title":"目录里的标题","pinned":false,"message_count":2,
                  "inserted_at":"2026-11-20T18:17:13.658Z","updated_at":"2026-11-21T00:00:00.000Z"}]
            """.trimIndent(),
            "chat_sessions/s1.json" to """
                {"id":"s1","title":null,"current_message_id":2,
                 "inserted_at":"2026-11-20T18:17:13.658Z","messages":[
                   {"id":1,"parent_id":null,"role":"USER","fragments":[{"type":"REQUEST","content":"问题"}]},
                   {"id":2,"parent_id":1,"role":"ASSISTANT","status":"FINISHED",
                    "fragments":[{"type":"RESPONSE","content":"回答"}]}
                 ]}
            """.trimIndent(),
        )

        val scan = parseZip(zip)
        assertEquals(1, scan.sessions.size)
        val session = scan.sessions[0]
        // 标题来自目录文件（会话文件里是 null）
        assertEquals("目录里的标题", session.title)
        assertEquals(2, session.messages.size)
        assertEquals("问题", session.messages[0].text)
        assertEquals("回答", session.messages[1].text)
        // 时间取目录里的 updated_at
        assertTrue(session.updatedAt >= 1_763_000_000_000L)
    }

    @Test
    fun onlyCurrentBranchIsImported() {
        // 一个对话里有两个分支（重新生成过回答），只该导 current_message_id 那条线
        val json = """
        {"id":"branch","title":"分支","current_message_id":4,
         "messages":[
           {"id":1,"parent_id":null,"role":"USER","fragments":[{"type":"REQUEST","content":"问题"}]},
           {"id":2,"parent_id":1,"role":"ASSISTANT","status":"FINISHED","fragments":[{"type":"RESPONSE","content":"旧回答"}]},
           {"id":3,"parent_id":2,"role":"USER","fragments":[{"type":"REQUEST","content":"追问"}]},
           {"id":4,"parent_id":3,"role":"ASSISTANT","status":"FINISHED","fragments":[{"type":"RESPONSE","content":"新回答"}]},
           {"id":5,"parent_id":1,"role":"ASSISTANT","status":"FINISHED","fragments":[{"type":"RESPONSE","content":"另一条分支，不要"}]}
         ]}
        """.trimIndent()

        val scan = parseSingleJson(json)
        val texts = scan.sessions[0].messages.map { it.text }
        assertEquals(listOf("问题", "旧回答", "追问", "新回答"), texts)
        assertTrue("另一条分支被导进来了", texts.none { it.contains("另一条分支") })
    }

    @Test
    fun contentFilteredTurnIsDroppedEntirely() {
        val json = """
        {"id":"cf","title":"被撤回的一轮","current_message_id":4,
         "messages":[
           {"id":1,"parent_id":null,"role":"USER","fragments":[{"type":"REQUEST","content":"被拦的问题"}]},
           {"id":2,"parent_id":1,"role":"ASSISTANT","status":"CONTENT_FILTER","fragments":[{"type":"RESPONSE","content":""}]},
           {"id":3,"parent_id":2,"role":"USER","fragments":[{"type":"REQUEST","content":"正常的问题"}]},
           {"id":4,"parent_id":3,"role":"ASSISTANT","status":"FINISHED","fragments":[{"type":"RESPONSE","content":"正常的回答"}]}
         ]}
        """.trimIndent()

        val scan = parseSingleJson(json)
        val texts = scan.sessions[0].messages.map { it.text }
        assertEquals(listOf("正常的问题", "正常的回答"), texts)
    }

    @Test
    fun cyclicParentsDoNotHang() {
        // 1 → 2 → 1 成环：不能死循环，也不能崩
        val json = """
        {"id":"cycle","title":"环形","messages":[
          {"id":1,"parent_id":2,"role":"USER","fragments":[{"type":"REQUEST","content":"甲"}]},
          {"id":2,"parent_id":1,"role":"ASSISTANT","status":"FINISHED","fragments":[{"type":"RESPONSE","content":"乙"}]}
        ]}
        """.trimIndent()
        val scan = parseSingleJson(json)
        // 重点：没卡死、没抛异常就通过
        assertTrue(scan.skipped >= 0)
    }

    // =================================================================
    // 4. 图片：从压缩包里按 export_path 取出来
    // =================================================================

    @Test
    fun imagesAreReadFromTheZipByExportPath() {
        val fakeImage = ByteArray(64) { it.toByte() }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("chat_sessions/s2.json"))
            zip.write(
                """
                {"id":"s2","title":"带图","current_message_id":2,"messages":[
                  {"id":1,"parent_id":null,"role":"USER","fragments":[
                    {"type":"REQUEST","content":"看图"},
                    {"type":"FILE","files":[
                      {"id":"f1","file_name":"1000029105_833.webp","file_size":120082,"is_image":true,
                       "export_path":"chat_sessions/s2_files/aaa_f1.webp"},
                      {"id":"f2","file_name":"report.pdf","file_size":10,"is_image":false,"export_path":null}
                    ]}
                  ]},
                  {"id":2,"parent_id":1,"role":"ASSISTANT","status":"FINISHED",
                   "fragments":[{"type":"RESPONSE","content":"看到了"}]}
                ]}
                """.trimIndent().toByteArray(Charsets.UTF_8)
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("chat_sessions/s2_files/aaa_f1.webp"))
            zip.write(fakeImage)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("chat_sessions/s2_files/unused_zzz.webp"))
            zip.write(fakeImage)
            zip.closeEntry()
        }

        val sink = CountingSink()
        val scan = parseZip(out.toByteArray(), sink)

        assertEquals(1, scan.sessions.size)
        val messages = scan.sessions[0].messages
        assertEquals("看图", messages[0].text)
        assertEquals(1, messages[0].imageCount)
        assertNotNull(messages[0].imagesJson)
        assertTrue(messages[0].imagesJson!!.contains("local:f1"))
        // 只取了被引用的那张，没被引用的不取
        assertEquals(1, sink.count)
    }

    @Test
    fun imagesAreSkippedWhenSinkIsNull() {
        val json = """
        {"id":"s3","title":"不导图","messages":[
          {"id":1,"parent_id":null,"role":"USER","fragments":[
            {"type":"REQUEST","content":"看图"},
            {"type":"FILE","files":[{"id":"f1","file_name":"a.webp","is_image":true,
              "export_path":"chat_sessions/s3_files/a.webp"}]}
          ]}
        ]}
        """.trimIndent()
        val scan = parseSingleJson(json) // sink = null
        assertEquals(0, scan.imageCount)
        assertEquals(0, scan.sessions[0].messages[0].imageCount)
        // 文字还在
        assertEquals("看图", scan.sessions[0].messages[0].text)
    }

    // =================================================================
    // 5. 已导过的对话直接跳过
    // =================================================================

    @Test
    fun alreadyImportedConversationsAreSkipped() {
        val json = """
        {"id":"s4","title":"导过的","messages":[
          {"id":1,"parent_id":null,"role":"USER","fragments":[{"type":"REQUEST","content":"再来一次"}]}
        ]}
        """.trimIndent()
        val parser = ExportParser(shouldSkip = { it == "imp-s4" })
        parser.readJsonDocument({ ByteArrayInputStream(json.toByteArray(Charsets.UTF_8)) }, "s4.json")
        assertTrue(parser.finish().sessions.isEmpty())
    }
}
