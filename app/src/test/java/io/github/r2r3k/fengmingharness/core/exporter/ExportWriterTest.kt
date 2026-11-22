package io.github.r2r3k.fengmingharness.core.exporter

import io.github.r2r3k.fengmingharness.core.importer.ExportParser
import io.github.r2r3k.fengmingharness.core.importer.ImageSink
import io.github.r2r3k.fengmingharness.core.importer.StoredImage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile

/**
 * 「官方格式导出」的单元测试（跑在电脑上）。
 *
 * 核心是一个完整的往返：**官方真包 → 解析 → 我们重新导出 → 再用解析器读回来 → 逐条比对**。
 * 顺手把导出的包结构逐字段检查一遍（版本号、目录、parent 链、时间格式、图片路径）。
 *
 * 跑法：
 * ```
 * .\gradlew.bat testDebugUnitTest --tests "io.github.r2r3k.fengmingharness.core.exporter.ExportWriterTest.*"
 * ```
 * 导出的样例包会留在 `app/build/test-output/roundtrip.zip`，可以直接解压看。
 */
class ExportWriterTest {

    private fun realZip(): File {
        val configured = System.getProperty("deepseekExportZip")
            ?: System.getenv("DEEPSEEK_EXPORT_ZIP")
            ?: "sample/deepseek_data.zip"
        return File(configured)
    }

    private class CountingSink : ImageSink {
        var count = 0
        override fun store(entryName: String, bytes: ByteArray): StoredImage {
            count++
            return StoredImage("mem:/$entryName", "mem:/$entryName.thumb")
        }
    }

    // ---------------- 小工具：把 json 拆回导出模型 ----------------

    private class ImgRef(val id: String, val path: String, val name: String)

    private fun imagesOf(raw: String?): List<ImgRef> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = JSONArray(raw)
        return (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            ImgRef(
                id = obj.optString("fileId"),
                path = obj.optString("path").removePrefix("mem:/"),
                name = obj.optString("name"),
            )
        }
    }

    private fun sourcesOf(raw: String?): List<ExportSource> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = JSONArray(raw)
        return (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            ExportSource(
                url = obj.optString("url"),
                title = obj.optString("title"),
                snippet = obj.optString("snippet").takeIf { it.isNotBlank() && it != "null" },
                site = obj.optString("site").takeIf { it.isNotBlank() && it != "null" },
            )
        }
    }

    // =================================================================
    // 主测试：真包往返
    // =================================================================

    @Test
    fun realPackageRoundTrip() {
        val zip = realZip()
        assumeTrue("没找到真实导出包，跳过：${zip.absolutePath}", zip.isFile)

        // ---- 1. 解析官方真包（带一个只记路径的 sink，这样 image_json 里会有 export_path） ----
        val parser = ExportParser(CountingSink())
        parser.readZip(zip, zip.name)
        val original = parser.finish()
        assertEquals("真包应该有 6 个对话", 6, original.sessions.size)

        // ---- 2. 转成导出模型（图片字节真的从真包里取） ----
        val zipFile = ZipFile(zip)
        val conversations = try {
            original.sessions.map { session ->
                ExportConversation(
                    id = session.sourceId,
                    title = session.title,
                    createdAt = session.createdAt,
                    updatedAt = session.updatedAt,
                    messages = session.messages.map { message ->
                        ExportMessage(
                            role = message.role,
                            text = message.text,
                            reasoning = message.reasoning,
                            sources = sourcesOf(message.sourcesJson),
                            images = imagesOf(message.imagesJson).map { ref ->
                                val entry = zipFile.getEntry(ref.path)
                                val bytes = entry?.let { zipFile.getInputStream(it).use { s -> s.readBytes() } }
                                ExportImage(
                                    id = ref.id,
                                    fileName = ref.name.ifBlank { ref.path.substringAfterLast('/') },
                                    size = bytes?.size?.toLong() ?: 0L,
                                    width = 0,
                                    height = 0,
                                    open = { bytes },
                                )
                            },
                            time = message.time,
                        )
                    },
                )
            }
        } finally {
            zipFile.close()
        }

        // ---- 3. 写成官方格式 ----
        val outFile = File("build/test-output/roundtrip.zip")
        outFile.parentFile?.mkdirs()
        val result = outFile.outputStream().buffered().use { out ->
            OfficialExportWriter.write(out, conversations, userId = "11111111-2222-3333-4444-555555555555")
        }
        println("=== 导出结果 ===")
        println("对话 ${result.conversations} / 消息 ${result.messages} / 图片 ${result.images} " +
            "(${result.imageBytes / 1024 / 1024}MB) / 空图 ${result.emptyImages}")
        println("文件：${outFile.absolutePath}  ${outFile.length() / 1024 / 1024}MB")
        assertEquals("对话数对不上", 6, result.conversations)
        assertEquals("消息数对不上", 2820, result.messages)
        assertTrue("一张图都没写进去", result.images > 900)
        assertEquals("有图片读不出来", 0, result.emptyImages)

        // ---- 4. 结构检查（不看解析器，直接看文件） ----
        checkStructure(outFile, original.sessions.size)

        // ---- 5. 用解析器读回来逐条比对 ----
        val sink = CountingSink()
        val parser2 = ExportParser(sink)
        parser2.readZip(outFile, outFile.name)
        val again = parser2.finish()
        println("读回来：${again.sessions.size} 个对话 / ${again.totalMessages} 条 / 图片 ${sink.count} 张")
        again.notes.forEach { println("提示：$it") }

        assertEquals(original.sessions.size, again.sessions.size)
        assertEquals(original.totalMessages, again.totalMessages)
        assertEquals("图片数量对不上", result.images, sink.count)

        val originalById = original.sessions.associateBy { it.sourceId }
        again.sessions.forEach { back ->
            val before = originalById[back.sourceId]
            assertTrue("id 变了：${back.sourceId}", before != null)
            before!!
            assertEquals("标题变了", before.title, back.title)
            assertEquals("消息条数变了", before.messages.size, back.messages.size)
            before.messages.forEachIndexed { index, m ->
                val n = back.messages[index]
                assertEquals("角色变了", m.role, n.role)
                assertEquals("正文变了（${before.title} 第 ${index + 1} 条）", m.text, n.text)
                assertEquals("思考过程变了", m.reasoning, n.reasoning)
                assertEquals("图片数量变了", m.imageCount, n.imageCount)
                assertEquals("来源数量变了", m.sourceCount, n.sourceCount)
            }
        }
    }

    /** 逐条按官方规则检查导出文件 */
    private fun checkStructure(file: File, expectSessions: Int) {
        val timePattern = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z""")
        ZipFile(file).use { zip ->
            // manifest
            val manifest = JSONObject(text(zip, "manifest.json"))
            assertEquals("格式版本不对", 3, manifest.getInt("export_format_version"))

            // user.json
            val user = JSONObject(text(zip, "user.json"))
            assertTrue("user.json 没有 user_id", user.optString("user_id").isNotBlank())
            assertTrue("oauth_profiles 应该是数组", user.opt("oauth_profiles") is JSONArray)

            // 目录
            val index = JSONArray(text(zip, "chat_session_index.json"))
            assertEquals("目录条数不对", expectSessions, index.length())
            val sessionIds = mutableSetOf<String>()
            for (i in 0 until index.length()) {
                val row = index.getJSONObject(i)
                val id = row.getString("id")
                sessionIds += id
                assertTrue("id 不是 uuid：$id", id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
                assertTrue("标题空了", row.optString("title").isNotBlank())
                assertTrue("pinned 应该是布尔", row.opt("pinned") is Boolean)
                assertTrue("inserted_at 格式不对：${row.optString("inserted_at")}", timePattern.matches(row.getString("inserted_at")))
                assertTrue("updated_at 格式不对", timePattern.matches(row.getString("updated_at")))
                assertTrue("message_count 应该是数字", row.opt("message_count") is Number)
            }

            // 每个会话文件
            var totalFileRefs = 0
            sessionIds.forEach { id ->
                val sessionEntry = zip.getEntry("chat_sessions/$id.json")
                assertTrue("缺会话文件：chat_sessions/$id.json", sessionEntry != null)
                val session = JSONObject(text(zip, "chat_sessions/$id.json"))
                assertEquals("会话 id 和目录不一致", id, session.getString("id"))
                assertTrue("会话时间格式不对", timePattern.matches(session.getString("inserted_at")))
                val messages = session.getJSONArray("messages")
                assertTrue("消息数组为空", messages.length() > 0)
                assertEquals("current_message_id 应该是最后一条", messages.length(), session.getInt("current_message_id"))
                assertEquals("message_count 和实际条数不一致", messages.length(), session.getInt("current_message_id"))

                for (i in 0 until messages.length()) {
                    val message = messages.getJSONObject(i)
                    assertEquals("消息 id 应该从 1 连续递增", i + 1, message.getInt("id"))
                    if (i == 0) {
                        assertTrue("第一条的 parent_id 应该是 null", message.isNull("parent_id"))
                    } else {
                        assertEquals("parent_id 应该是上一条", i, message.getInt("parent_id"))
                    }
                    val role = message.getString("role")
                    assertTrue("role 只能是 USER/ASSISTANT：$role", role == "USER" || role == "ASSISTANT")
                    assertEquals("status 应该是 FINISHED", "FINISHED", message.getString("status"))
                    assertTrue("thinking_enabled 应该是布尔", message.opt("thinking_enabled") is Boolean)
                    assertTrue("search_enabled 应该是布尔", message.opt("search_enabled") is Boolean)
                    assertTrue("accumulated_token_usage 应该是数字", message.opt("accumulated_token_usage") is Number)
                    assertTrue("消息时间格式不对", timePattern.matches(message.getString("inserted_at")))

                    val fragments = message.getJSONArray("fragments")
                    assertTrue("fragments 不能是空的", fragments.length() > 0)
                    for (j in 0 until fragments.length()) {
                        val fragment = fragments.getJSONObject(j)
                        val type = fragment.getString("type")
                        when (type) {
                            "REQUEST", "RESPONSE" ->
                                assertTrue("$type 缺 content", fragment.has("content"))
                            "THINK" -> {
                                assertTrue("THINK 缺 content", fragment.has("content"))
                                assertTrue("THINK 的 elapsed_secs 应该是数字", fragment.opt("elapsed_secs") is Number)
                            }
                            "TOOL_SEARCH" -> {
                                assertTrue("TOOL_SEARCH 应该有 queries 数组", fragment.opt("queries") is JSONArray)
                                val results = fragment.getJSONArray("results")
                                assertTrue("TOOL_SEARCH 应该有结果", results.length() > 0)
                                for (k in 0 until results.length()) {
                                    val r = results.getJSONObject(k)
                                    assertTrue("搜索结果 url 和 title 不能都空", 
                                        r.optString("url").isNotBlank() || r.optString("title").isNotBlank())
                                    assertTrue("搜索结果应该有 title 字段", r.has("title"))
                                }
                                assertEquals("TOOL_SEARCH 状态应该 FINISHED", "FINISHED", fragment.getString("status"))
                            }
                            "FILE" -> {
                                val files = fragment.getJSONArray("files")
                                for (k in 0 until files.length()) {
                                    val f = files.getJSONObject(k)
                                    val exportPath = f.getString("export_path")
                                    assertTrue("FILE 缺 file_name", f.optString("file_name").isNotBlank())
                                    assertTrue("FILE 的 is_image 应该是 true", f.getBoolean("is_image"))
                                    assertTrue("export_path 应该在 chat_sessions/<id>_files/ 下：$exportPath",
                                        exportPath.startsWith("chat_sessions/${id}_files/"))
                                    assertTrue("压缩包里没有这个图片条目：$exportPath", zip.getEntry(exportPath) != null)
                                    assertTrue("FILE 的 id 不是 uuid：${f.getString("id")}",
                                        f.getString("id").matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
                                    totalFileRefs++
                                }
                            }
                            else -> throw AssertionError("出现了意料之外的分片类型：$type")
                        }
                    }
                }
            }
            println("结构检查通过：$expectSessions 个对话，$totalFileRefs 个图片引用，全部指向真实条目")
        }
    }

    private fun text(zip: ZipFile, name: String): String {
        val entry = zip.getEntry(name) ?: throw AssertionError("缺文件：$name")
        return zip.getInputStream(entry).use { String(it.readBytes(), Charsets.UTF_8) }
    }

    // =================================================================
    // 小单元：不依赖真包，保证永远能跑
    // =================================================================

    @Test
    fun writesOfficialShapeFromScratch() {
        val image = ByteArray(32) { 7 }
        val conversation = ExportConversation(
            id = "my-conversation-1",
            title = "测试对话",
            createdAt = 1_760_000_000_000L,
            updatedAt = 1_760_000_100_000L,
            messages = listOf(
                ExportMessage(
                    role = "user",
                    text = "看看这张图",
                    time = 1_760_000_000_000L,
                    images = listOf(
                        ExportImage(
                            id = "photo-1",
                            fileName = "photo.webp",
                            size = image.size.toLong(),
                            width = 1080,
                            height = 2412,
                            open = { image },
                        )
                    ),
                ),
                ExportMessage(
                    role = "assistant",
                    text = "看到了，是一个终端界面。",
                    reasoning = "先看图，再总结。",
                    time = 1_760_000_100_000L,
                    sources = listOf(ExportSource("https://example.com/a", "示例 A", "摘要", "示例站")),
                ),
            ),
        )

        val bytes = ByteArrayOutputStream()
        val result = OfficialExportWriter.write(bytes, listOf(conversation), userId = "u-1")
        assertEquals(1, result.conversations)
        assertEquals(2, result.messages)
        assertEquals(1, result.images)

        // 读回来
        val file = File("build/test-output/small.zip")
        file.parentFile?.mkdirs()
        file.writeBytes(bytes.toByteArray())
        val sink = CountingSink()
        val parser = ExportParser(sink)
        parser.readZip(file, file.name)
        val scan = parser.finish()

        assertEquals(1, scan.sessions.size)
        val messages = scan.sessions[0].messages
        assertEquals(2, messages.size)
        assertEquals("看看这张图", messages[0].text)
        assertEquals(1, messages[0].imageCount)
        assertEquals("看到了，是一个终端界面。", messages[1].text)
        assertEquals("先看图，再总结。", messages[1].reasoning)
        assertEquals(1, messages[1].sourceCount)
        assertTrue(messages[1].sourcesJson!!.contains("https://example.com/a"))
        assertEquals(1, sink.count)

        // 文件名不合法时也要能兜住
        val weird = ExportConversation(
            id = "c2",
            title = "怪文件名",
            createdAt = 1_760_000_000_000L,
            updatedAt = 1_760_000_000_000L,
            messages = listOf(
                ExportMessage(
                    role = "user",
                    text = "x",
                    images = listOf(
                        ExportImage(id = "i", fileName = "../../etc/passwd", open = { image }),
                        ExportImage(id = "j", fileName = "", open = { image }),
                    ),
                ),
            ),
        )
        val bytes2 = ByteArrayOutputStream()
        OfficialExportWriter.write(bytes2, listOf(weird))
        val file2 = File("build/test-output/weird.zip")
        file2.writeBytes(bytes2.toByteArray())
        ZipFile(file2).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                assertTrue("条目名不能被穿越：${entry.name}", !entry.name.contains(".."))
            }
            // 两张图都该在 _files/ 里
            val fileEntries = zip.entries().asSequence().count { it.name.contains("_files/") }
            assertEquals(2, fileEntries)
        }
    }

    @Test
    fun emptyMessageListProducesEmptyButValidZip() {
        val bytes = ByteArrayOutputStream()
        val result = OfficialExportWriter.write(bytes, emptyList())
        assertEquals(0, result.conversations)
        val file = File("build/test-output/empty.zip")
        file.parentFile?.mkdirs()
        file.writeBytes(bytes.toByteArray())
        ZipFile(file).use { zip ->
            assertTrue(zip.getEntry("manifest.json") != null)
            assertTrue(zip.getEntry("user.json") != null)
            assertTrue(zip.getEntry("chat_session_index.json") != null)
        }
    }

    private fun <T> java.util.Enumeration<T>.asSequence(): Sequence<T> = sequence {
        while (hasMoreElements()) yield(nextElement())
    }
}
