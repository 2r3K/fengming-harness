package io.github.r2r3k.fengmingharness.core.model

import org.json.JSONObject

/** 一张随消息发出的图片：fileId 是 DeepSeek 那边的编号，localPath 是手机上的副本（用于显示/重发） */
data class ChatImage(
    val fileId: String,
    val localPath: String,
    /** 小缩略图，列表里只解码它，避免几百 MB 的内存 */
    val thumbPath: String = "",
    /** image = 图片；doc = 文档（PDF/Word/PPT 等） */
    val kind: String = "image",
    /** 文档的文件名，用来显示 */
    val name: String = "",
    /**
     * 文档的本地摘要（省钱用）。
     * 有摘要的文档，之后再发请求只发这段文字，不再把整份文档重发一遍。
     */
    val summary: String = "",
)

/** 模型要求调用的一次工具 */
data class ToolCall(
    val id: String,
    val name: String,
    /** 模型给的参数，是 JSON 文本（可能不合法，用之前要自己校验） */
    val arguments: String,
)

/** 一个可以交给模型的工具：名字、干什么用、参数长什么样（JSON Schema） */
data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: JSONObject,
)

/** 界面上显示的一条工具执行记录（也会存进聊天记录） */
data class ToolNote(
    val name: String,
    /** 参数摘要，给人看的 */
    val args: String,
    /** 结果摘要，给人看的 */
    val result: String,
    /** false = 执行出错或被拒绝 */
    val ok: Boolean,
    val rejected: Boolean = false,
)

/** 一条发给模型的对话消息 */
data class ChatMsg(
    val role: String,
    val content: String,
    /** 思考内容：带 tools 时官方要求带回去 */
    val reasoning: String? = null,
    /** 随这条消息发出的图片（历史里全部会带上，用户要求） */
    val images: List<ChatImage> = emptyList(),
    /** role = assistant 且这一轮要调工具时，这里放调用的列表 */
    val toolCalls: List<ToolCall> = emptyList(),
    /** role = tool 时，这是对哪一次调用的答复 */
    val toolCallId: String = "",
)

/** 一次请求的开关 */
data class ChatOptions(
    val temperature: Double = 0.3,
    val webSearch: Boolean = false,
    val deepThinking: Boolean = false,
)

/** 一条引用来源 */
data class SourceItem(
    val url: String,
    val title: String,
    val site: String? = null,
    val snippet: String? = null,
)

/** 一次请求的用量（服务端返回） */
data class Usage(
    val inputTokens: Int = 0,
    val cachedTokens: Int = 0,
    val outputTokens: Int = 0,
) {
    val total: Int get() = inputTokens + cachedTokens + outputTokens
    val cacheRate: Double
        get() = if (inputTokens + cachedTokens <= 0) 0.0 else cachedTokens.toDouble() / (inputTokens + cachedTokens)
}

/** 流式事件 */
sealed interface StreamEvent {
    data class Delta(val text: String) : StreamEvent
    data class Reasoning(val text: String) : StreamEvent

    /** 联网过程中的状态提示，例如"正在联网搜索…" */
    data class Searching(val hint: String) : StreamEvent

    /** 本次回答的引用来源 */
    data class Sources(val items: List<SourceItem>) : StreamEvent

    /** 本次请求的用量 */
    data class Metered(val usage: Usage) : StreamEvent

    /** 模型要求调用工具（这一轮结束时给一次） */
    data class ToolCalls(val calls: List<ToolCall>) : StreamEvent

    data class Failed(val message: String) : StreamEvent
    data object Completed : StreamEvent
}

/** 上下文预算档位 */
enum class ContextTier(val label: String, val limitTokens: Int, val desc: String) {
    LOW("省", 12_000, "只记得最近几轮"),
    STANDARD("标准", 32_768, "日常够用"),
    LARGE("大", 100_000, "长对话、多图"),
    UNLIMITED("无上限", 900_000, "接近模型上限，几乎不折叠（默认）"),
}

fun contextTierOf(id: String?): ContextTier =
    ContextTier.entries.firstOrNull { it.name.equals(id, ignoreCase = true) } ?: ContextTier.UNLIMITED

/** App 设置（Key 不明文存储，见 SecretStore） */
data class AppSettings(
    val baseUrl: String = "https://api.deepseek.com",
    val apiKey: String = "",
    val model: String = "deepseek-flash",
    val temperature: Double = 0.3,
    val webSearchDefault: Boolean = true,
    val deepThinkingDefault: Boolean = false,
    val contextTier: ContextTier = ContextTier.UNLIMITED,
    /** 文档解析方式：true=连图一起给模型（rich），false=只要文字 */
    val docParseRich: Boolean = true,
)
