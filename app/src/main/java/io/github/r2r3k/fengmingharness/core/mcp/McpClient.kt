package io.github.r2r3k.fengmingharness.core.mcp

import org.json.JSONArray
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** 服务器上拉到的一个工具 */
data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: JSONObject,
)

/** 握手成功后服务器自报的信息 */
data class McpServerInfo(
    val name: String,
    val version: String,
    val protocolVersion: String,
)

/**
 * 最小的 MCP 客户端，走 streamable-http（POST + JSON-RPC）。
 *
 * 只实现三件事：握手 initialize / 列工具 tools/list / 调工具 tools/call。
 * 服务器回 application/json 和 text/event-stream 两种都认（真正的 MCP 服务器两种都会出现）。
 *
 * 注意：全是阻塞调用，必须在 IO 线程上用。
 */
class McpClient(
    private val baseUrl: String,
    private val token: String = "",
) {

    private val http = okhttp3.OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private var sessionId: String? = null
    private var protocolVersion: String? = null
    private var nextId = 1

    /** 1) 握手 + 2) 告诉它初始化好了 */
    fun connect(): Result<McpServerInfo> = runCatching {
        val payload = rpc(
            "initialize",
            JSONObject().apply {
                put("protocolVersion", "2025-06-18")
                put("capabilities", JSONObject())
                put(
                    "clientInfo",
                    JSONObject().apply {
                        put("name", "deepseek-fengming")
                        put("version", "0.9.2")
                    },
                )
            },
        )
        val result = payload.optJSONObject("result")
            ?: throw IllegalStateException(payload.optJSONObject("error")?.optString("message") ?: "服务器没回握手结果")
        val info = result.optJSONObject("serverInfo")
        val server = McpServerInfo(
            name = info?.optString("name").orEmpty().ifBlank { "未命名" },
            version = info?.optString("version").orEmpty(),
            protocolVersion = result.optString("protocolVersion"),
        )
        protocolVersion = server.protocolVersion.takeIf { it.isNotBlank() }
        notify("notifications/initialized")
        server
    }

    /** 3) 列工具 */
    fun listTools(): Result<List<McpTool>> = runCatching {
        val payload = rpc("tools/list", JSONObject())
        val error = payload.optJSONObject("error")
        if (error != null) throw IllegalStateException(error.optString("message"))
        val result = payload.optJSONObject("result") ?: JSONObject()
        val array = result.optJSONArray("tools") ?: JSONArray()
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val name = item.optString("name").ifBlank { return@mapNotNull null }
            McpTool(
                name = name,
                description = item.optString("description").orEmpty(),
                inputSchema = item.optJSONObject("inputSchema")
                    ?: JSONObject("""{"type":"object","properties":{}}"""),
            )
        }
    }

    /** 4) 调工具，结果拼成一段文字 */
    fun callTool(name: String, argumentsJson: String): Result<String> = runCatching {
        val args = runCatching { JSONObject(argumentsJson.ifBlank { "{}" }) }.getOrElse { JSONObject() }
        val payload = rpc(
            "tools/call",
            JSONObject().apply {
                put("name", name)
                put("arguments", args)
            },
        )
        val error = payload.optJSONObject("error")
        if (error != null) throw IllegalStateException(error.optString("message"))

        val result = payload.optJSONObject("result") ?: JSONObject()
        val texts = mutableListOf<String>()
        val content = result.optJSONArray("content")
        if (content != null) {
            for (i in 0 until content.length()) {
                val block = content.optJSONObject(i) ?: continue
                when (block.optString("type")) {
                    "text" -> texts += block.optString("text")
                    else -> {
                        // 图片/资源之类的先不处理，留个提示，别让模型以为拿到了内容
                        texts += "（服务器返回了 ${block.optString("type")} 类型的内容，当前版本还不支持显示）"
                    }
                }
            }
        }
        val joined = texts.joinToString("\n").ifBlank { "（工具没有返回文字内容）" }
        if (result.optBoolean("isError", false)) {
            "工具出错：$joined"
        } else {
            joined
        }
    }

    // ---------- 底下 ----------

    private fun notify(method: String) {
        val body = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("method", method)
        }
        post(body, expectReply = false)
    }

    private fun rpc(method: String, params: JSONObject): JSONObject {
        val id = nextId++
        val body = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        return post(body, expectReply = true, expectedId = id)
    }

    private fun post(body: JSONObject, expectReply: Boolean, expectedId: Int = -1): JSONObject {
        val builder = okhttp3.Request.Builder()
            .url(endpoint())
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        sessionId?.let { builder.header("Mcp-Session-Id", it) }
        protocolVersion?.let { builder.header("MCP-Protocol-Version", it) }
        if (token.isNotBlank()) builder.header("Authorization", "Bearer $token")

        http.newCall(builder.build()).execute().use { response ->
            response.header("Mcp-Session-Id")?.let { sessionId = it }
            if (!response.isSuccessful) {
                val detail = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
                val friendly = when (response.code) {
                    401 -> "服务器要求口令：手机里「令牌」没填，或者填错了"
                    403 -> "服务器拒绝了这次请求（地址不对，或服务器设了来源限制）"
                    404 -> "这个地址上没有 MCP 服务：检查地址是不是以 /mcp 结尾"
                    else -> "服务器返回 ${response.code}" + if (detail.isBlank()) "" else "：${detail.take(160)}"
                }
                throw IllegalStateException(friendly)
            }
            if (!expectReply) {
                response.body?.close()
                return JSONObject()
            }
            return readJson(response, expectedId)
        }
    }

    /** 服务器可能回一段普通 JSON，也可能回 SSE（event: message / data: {...}） */
    private fun readJson(response: okhttp3.Response, expectedId: Int): JSONObject {
        val body = response.body ?: return JSONObject()
        val contentType = response.header("Content-Type").orEmpty()
        if (!contentType.contains("event-stream")) {
            val text = runCatching { body.string() }.getOrDefault("")
            return runCatching { JSONObject(text) }.getOrElse {
                throw IllegalStateException("服务器回了看不懂的内容：${text.take(120)}")
            }
        }

        val source = body.source()
        val buffer = StringBuilder()
        while (true) {
            val line = source.readUtf8Line() ?: break
            if (line.startsWith("data:")) {
                buffer.append(line.removePrefix("data:").trim())
            } else if (line.isBlank() && buffer.isNotEmpty()) {
                val obj = runCatching { JSONObject(buffer.toString()) }.getOrNull()
                buffer.setLength(0)
                if (obj != null && obj.optInt("id", -1) == expectedId) return obj
            }
        }
        val rest = runCatching { JSONObject(buffer.toString()) }.getOrNull()
        if (rest != null) return rest
        throw IllegalStateException("服务器的事件流里没找到这次请求的回复")
    }

    private fun endpoint(): String {
        var url = baseUrl.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://$url"
        url = url.trimEnd('/')
        // 只给了 IP:端口 的话，补上 MCP 默认路径
        return if (url.endsWith("/mcp") || url.contains("/mcp/") || url.count { it == '/' } > 2) {
            url
        } else {
            "$url/mcp"
        }
    }

    companion object {
        /** 工具名里只允许这些字符，别的都换成下划线 */
        fun slug(raw: String): String {
            // 注意：不能用 isLetterOrDigit()，它把中文也算字母，而 DeepSeek 那边只认
            // a-z A-Z 0-9 _ -，中文名会让请求直接 400
            val cleaned = raw.lowercase().map { ch ->
                if ((ch in 'a'..'z') || (ch in '0'..'9') || ch == '_' || ch == '-') ch else '_'
            }
            val text = cleaned.joinToString("").trim('_')
            return text.ifBlank { "server" }.take(24)
        }
    }
}
