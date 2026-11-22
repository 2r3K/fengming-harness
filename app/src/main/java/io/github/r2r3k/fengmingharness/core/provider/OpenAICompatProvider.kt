package io.github.r2r3k.fengmingharness.core.provider

import io.github.r2r3k.fengmingharness.core.model.ChatMsg
import io.github.r2r3k.fengmingharness.core.model.ChatOptions
import io.github.r2r3k.fengmingharness.core.model.SourceItem
import io.github.r2r3k.fengmingharness.core.model.StreamEvent
import io.github.r2r3k.fengmingharness.core.model.ToolCall
import io.github.r2r3k.fengmingharness.core.model.ToolSpec
import io.github.r2r3k.fengmingharness.core.model.Usage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * DeepSeek 官方接口（OpenAI 兼容格式）。
 *
 * 两套工具，二选一（官方明确说不能混用，混了会 400）：
 * 1. 联网：在 tools 里声明 deepseek_web_search / deepseek_read_url，搜索和抓网页都由服务端完成；
 * 2. 函数工具：声明我们自己的工具，模型回 tool_calls，由手机这边执行后把结果喂回去。
 */
class OpenAICompatProvider(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
) : ChatProvider {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var currentCall: Call? = null

    override fun cancel() {
        currentCall?.cancel()
    }

    override fun stream(
        messages: List<ChatMsg>,
        options: ChatOptions,
        tools: List<ToolSpec>,
    ): Flow<StreamEvent> = flow {
        val requestBody = JSONObject().apply {
            put("model", model)
            put("stream", true)
            if (!options.deepThinking) put("temperature", options.temperature)
            put(
                "thinking",
                JSONObject().put("type", if (options.deepThinking) "enabled" else "disabled"),
            )
            if (options.deepThinking) put("reasoning_effort", "high")

            if (options.webSearch) {
                // 联网：服务端工具，客户端什么都不用做
                put(
                    "tools",
                    JSONArray().apply {
                        put(JSONObject().put("type", "deepseek_web_search"))
                        put(JSONObject().put("type", "deepseek_read_url"))
                    },
                )
            } else if (tools.isNotEmpty()) {
                // 函数工具：模型回 tool_calls，我们执行完再把结果发回去
                put(
                    "tools",
                    JSONArray().apply {
                        tools.forEach { spec ->
                            put(
                                JSONObject().apply {
                                    put("type", "function")
                                    put(
                                        "function",
                                        JSONObject().apply {
                                            put("name", spec.name)
                                            put("description", spec.description)
                                            put("parameters", spec.parameters)
                                        },
                                    )
                                }
                            )
                        }
                    },
                )
                put("tool_choice", "auto")
            }

            put(
                "messages",
                JSONArray().apply {
                    messages.forEach { m ->
                        put(
                            JSONObject().apply {
                                put("role", m.role)

                                if (m.role == "tool") {
                                    put("content", m.content)
                                    put("tool_call_id", m.toolCallId)
                                } else if (m.role == "assistant" && m.toolCalls.isNotEmpty() && m.content.isBlank()) {
                                    // 只调工具、还没说话的那一轮，content 给 null（OpenAI 的惯例写法）
                                    put("content", JSONObject.NULL)
                                } else if (m.images.isEmpty()) {
                                    put("content", m.content)
                                } else {
                                    // 有图时必须用数组块结构：文字 + file_id 引用
                                    val blocks = JSONArray()
                                    if (m.content.isNotBlank()) {
                                        blocks.put(
                                            JSONObject().apply {
                                                put("type", "text")
                                                put("text", m.content)
                                            }
                                        )
                                    }
                                    m.images.forEach { image ->
                                        if (image.fileId.startsWith("local:")) {
                                            // 官方历史里导进来的图片：没有服务器端文件号，
                                            // 只在本机显示，不发给模型（发过去会 400）
                                        } else if (image.summary.isNotBlank()) {
                                            // 已经做过摘要：只发摘要文字，省掉整份文档的 token
                                            blocks.put(
                                                JSONObject().apply {
                                                    put("type", "text")
                                                    put(
                                                        "text",
                                                        "[文档《${image.name.ifBlank { "附件" }}》的摘要]\n" +
                                                            image.summary,
                                                    )
                                                }
                                            )
                                        } else {
                                            blocks.put(
                                                JSONObject().apply {
                                                    put("type", "file")
                                                    put("file_id", image.fileId)
                                                }
                                            )
                                        }
                                    }
                                    // 整条消息只剩"本机图片"时不能发空数组
                                    if (blocks.length() == 0) put("content", m.content) else put("content", blocks)
                                }

                                // 模型要求调工具的那一轮：把它要的调用原样带回去
                                if (m.role == "assistant" && m.toolCalls.isNotEmpty()) {
                                    put(
                                        "tool_calls",
                                        JSONArray().apply {
                                            m.toolCalls.forEach { call ->
                                                put(
                                                    JSONObject().apply {
                                                        put("id", call.id)
                                                        put("type", "function")
                                                        put(
                                                            "function",
                                                            JSONObject().apply {
                                                                put("name", call.name)
                                                                put("arguments", call.arguments)
                                                            },
                                                        )
                                                    }
                                                )
                                            }
                                        },
                                    )
                                }

                                // 带了 tools 的请求，官方要求把历史思考内容带回去
                                if (m.role == "assistant" && !m.reasoning.isNullOrEmpty()) {
                                    put("reasoning_content", m.reasoning)
                                }
                            }
                        )
                    }
                },
            )
        }.toString()

        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            .post(requestBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val call = client.newCall(request)
        currentCall = call

        val sources = LinkedHashMap<String, SourceItem>()
        // 模型要求调工具时，参数是分片流过来的，按 index 攒起来
        val toolAcc = LinkedHashMap<Int, CallAcc>()

        call.execute().use { response ->
            if (!response.isSuccessful) {
                val detail = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
                emit(StreamEvent.Failed(httpError(response.code, detail)))
                return@use
            }

            val source = response.body?.source()
            if (source == null) {
                emit(StreamEvent.Failed("服务器返回了空响应"))
                return@use
            }

            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.substring(5).trim()
                if (payload == "[DONE]") break

                val chunk = runCatching { JSONObject(payload) }.getOrNull() ?: continue

                chunk.optJSONObject("error")?.let { err ->
                    emit(StreamEvent.Failed("生成中断：" + (err.optString("message", "").takeIf { it.isNotEmpty() } ?: "服务端错误")))
                    return@use
                }

                chunk.optJSONObject("usage")?.let { usage ->
                    val input = usage.optInt("input_tokens", usage.optInt("prompt_tokens", 0))
                    val cached = usage.optInt("cache_read_input_tokens", 0)
                    val output = usage.optInt("output_tokens", usage.optInt("completion_tokens", 0))
                    if (input > 0 || output > 0) {
                        emit(StreamEvent.Metered(Usage(input, cached, output)))
                    }
                }

                val choice = chunk.optJSONArray("choices")?.optJSONObject(0) ?: continue

                choice.optJSONObject("delta")?.let { delta ->
                    val reasoning = delta.optString("reasoning_content", "")
                    if (reasoning.isNotEmpty() && reasoning != "null") {
                        emit(StreamEvent.Reasoning(reasoning))
                    }
                    val content = delta.optString("content", "")
                    if (content.isNotEmpty() && content != "null") {
                        emit(StreamEvent.Delta(content))
                    }
                    collectCalls(delta.optJSONArray("server_tool_calls"), sources)

                    delta.optJSONArray("tool_calls")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val item = arr.optJSONObject(i) ?: continue
                            val slot = toolAcc.getOrPut(item.optInt("index", i)) { CallAcc() }
                            clean(item.optString("id")).let { if (it.isNotEmpty()) slot.id = it }
                            item.optJSONObject("function")?.let { fn ->
                                clean(fn.optString("name")).let { if (it.isNotEmpty()) slot.name += it }
                                clean(fn.optString("arguments")).let { if (it.isNotEmpty()) slot.args.append(it) }
                            }
                        }
                    }
                }

                choice.optJSONObject("server_tool_event")?.let { event ->
                    when (event.optString("type")) {
                        "call_started" -> {
                            val actionType = event.optJSONObject("call")
                                ?.optJSONObject("action")?.optString("type").orEmpty()
                            emit(
                                StreamEvent.Searching(
                                    if (actionType == "search") "正在联网搜索…" else "正在打开网页…"
                                )
                            )
                        }

                        "call_completed" -> {
                            event.optJSONObject("call")?.let { collectOne(it, sources) }
                        }
                    }
                }
            }
        }

        if (sources.isNotEmpty()) emit(StreamEvent.Sources(sources.values.toList()))
        if (toolAcc.isNotEmpty()) {
            val calls = toolAcc.toSortedMap().values
                .filter { it.name.isNotEmpty() }
                .map {
                    ToolCall(
                        id = it.id.ifEmpty { "call_" + java.util.UUID.randomUUID().toString().take(8) },
                        name = it.name,
                        arguments = it.args.toString().ifBlank { "{}" },
                    )
                }
            if (calls.isNotEmpty()) emit(StreamEvent.ToolCalls(calls))
        }
        emit(StreamEvent.Completed)
    }
        .catch { e -> emit(StreamEvent.Failed(describeException(e))) }
        .flowOn(Dispatchers.IO)

    /** 攒一次工具调用的三个字段（id / 名字 / 参数分片） */
    private class CallAcc {
        var id: String = ""
        var name: String = ""
        val args = StringBuilder()
    }

    // ---------- 结果解析 ----------

    private fun collectCalls(steps: JSONArray?, into: MutableMap<String, SourceItem>) {
        if (steps == null) return
        for (i in 0 until steps.length()) {
            val step = steps.optJSONObject(i) ?: continue
            val calls = step.optJSONArray("calls") ?: continue
            for (j in 0 until calls.length()) {
                calls.optJSONObject(j)?.let { collectOne(it, into) }
            }
        }
    }

    private fun collectOne(call: JSONObject, into: MutableMap<String, SourceItem>) {
        call.optJSONArray("sources")?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val url = clean(s.optString("url"))
                if (url.isEmpty()) continue
                into[url] = SourceItem(
                    url = url,
                    title = clean(s.optString("title")).ifEmpty { url },
                    site = clean(s.optString("site_name")).ifEmpty { null },
                    snippet = clean(s.optString("snippet")).ifEmpty { null },
                )
            }
        }
        call.optJSONObject("page")?.let { page ->
            val url = clean(page.optString("url"))
            if (url.isNotEmpty() && !into.containsKey(url)) {
                into[url] = SourceItem(
                    url = url,
                    title = clean(page.optString("title")).ifEmpty { url },
                )
            }
        }
    }

    /** org.json 的 optString 会把 JSON null 变成字符串 "null"，这里统一洗掉 */
    private fun clean(value: String?): String =
        if (value.isNullOrEmpty() || value == "null") "" else value

    /** 服务端 400 的正文是一坨 JSON，界面上只显示里面的 message，别把整段塞给用户 */
    private fun extractErrorMessage(detail: String): String {
        val message = runCatching {
            JSONObject(detail).optJSONObject("error")?.optString("message").orEmpty()
        }.getOrNull().orEmpty()
        if (message.isNotBlank()) return message.take(300)
        return detail.trim().take(300)
    }

    // ---------- 错误 ----------

    private fun httpError(code: Int, detail: String): String = when (code) {
        400 -> when {
            detail.contains("file", ignoreCase = true) && detail.contains("not found", ignoreCase = true) ->
                "有一张历史图片在服务器上失效了（可能过期或被删）。新建对话，或删掉带图的那条消息再试"

            detail.contains("reasoning_content", ignoreCase = true) ->
                "历史里的思考内容没带回去（400）。先新建一个对话再试"

            detail.contains("tool", ignoreCase = true) && detail.contains("declared", ignoreCase = true) ->
                "联网工具重复声明（400）。把「联网」关一次再开"

            detail.contains("cannot be used together", ignoreCase = true) ->
                "联网和本地工具不能同时开（400）。把「🌐 联网」或「🔧 工具」关掉一个再试"

            detail.contains("tool_choice", ignoreCase = true) ->
                "这个模型不支持强制指定工具（400）。把「🔧 工具」关掉再试"

            detail.contains("does not match pattern", ignoreCase = true) && detail.contains("tool", ignoreCase = true) ->
                "工具名不合规（400）：工具名只能用英文字母、数字、下划线、减号。" +
                    "通常是 MCP 服务器里的工具名/服务器名带了中文"

            else -> "请求被拒（400）\n" + extractErrorMessage(detail)
        }
        401 -> "Key 无效或已过期（401）。去 DeepSeek 后台核对一下，或者重新建一个 Key"
        402 -> "账户余额不足（402），去 platform.deepseek.com 充点钱"
        403 -> "这个 Key 没有权限访问该模型（403）"
        404 -> "网址或模型名不对（404）。网址应为 https://api.deepseek.com，模型看设置里刷新的列表"
        422 -> "参数不对（422），检查模型名和温度设置"
        429 -> "请求太频繁，或者额度用完了（429），歇一会儿再试"
        in 500..599 -> "DeepSeek 服务端忙（$code），过一会儿再试"
        else -> "请求失败（$code）" + if (detail.isBlank()) "" else "\n${detail.trim().take(200)}"
    }

    private fun describeException(e: Throwable): String = when (e) {
        is java.net.SocketTimeoutException -> "等待超时。开着深度联网时搜得久，可以等久一点或把深度关掉"
        is java.net.UnknownHostException -> "域名解析失败，检查 Base URL 或网络"
        is javax.net.ssl.SSLException -> "SSL 握手失败，可能是网络被拦截"
        is java.io.IOException -> "网络中断：${e.message ?: "连接被关闭"}"
        else -> "出错了：${e.message ?: e.javaClass.simpleName}"
    }
}
