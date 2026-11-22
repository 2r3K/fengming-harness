package io.github.r2r3k.fengmingharness.core.mcp

import android.content.Context
import io.github.r2r3k.fengmingharness.core.DiagLog
import io.github.r2r3k.fengmingharness.core.SecretStore
import io.github.r2r3k.fengmingharness.core.model.ToolSpec
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 一台 MCP 服务器（自己电脑上的那个） */
data class McpServerConfig(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
)

/**
 * MCP 服务器的配置，存在本机。
 * 令牌单独走 SecretStore（加密），不跟配置混在一起。
 */
class McpStore(private val ctx: Context) {

    private val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun list(): List<McpServerConfig> {
        val raw = prefs.getString("mcpServers", null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                McpServerConfig(
                    id = obj.optString("id").ifBlank { return@mapNotNull null },
                    name = obj.optString("name"),
                    url = obj.optString("url"),
                    enabled = obj.optBoolean("enabled", true),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun save(config: McpServerConfig) {
        val current = list().toMutableList()
        val index = current.indexOfFirst { it.id == config.id }
        if (index >= 0) current[index] = config else current += config
        persist(current)
    }

    fun delete(id: String) {
        persist(list().filterNot { it.id == id })
        SecretStore.put(ctx, tokenKey(id), "")
    }

    fun newId(): String = UUID.randomUUID().toString().take(8)

    fun token(id: String): String = SecretStore.get(ctx, tokenKey(id)).orEmpty()

    fun saveToken(id: String, token: String) {
        SecretStore.put(ctx, tokenKey(id), token.trim())
    }

    private fun tokenKey(id: String) = "mcp:$id"

    private fun persist(items: List<McpServerConfig>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("name", item.name)
                    put("url", item.url)
                    put("enabled", item.enabled)
                }
            )
        }
        prefs.edit().putString("mcpServers", array.toString()).apply()
    }
}

/**
 * 拉好的"可调用工具集合"：工具定义 + 每个工具怎么调回去。
 * 只在一次请求里有效，不缓存（服务器可能刚重启过）。
 */
class McpToolset internal constructor(
    val specs: List<ToolSpec>,
    private val routes: Map<String, Route>,
) {
    internal class Route(
        val serverName: String,
        val toolName: String,
        val client: McpClient,
    )

    fun displayName(toolName: String): String? {
        val route = routes[toolName] ?: return null
        return "${route.serverName} · ${route.toolName}"
    }

    fun call(toolName: String, arguments: String): String {
        val route = routes[toolName] ?: throw IllegalArgumentException("没有这个工具：$toolName")
        route.client.connect().getOrElse {
            throw IllegalStateException("连不上 ${route.serverName}：${it.message ?: "网络错误"}")
        }
        return route.client.callTool(route.toolName, arguments).getOrElse {
            throw IllegalStateException(it.message ?: "调用失败")
        }
    }

    companion object {
        val EMPTY = McpToolset(emptyList(), emptyMap())

        // 缓存：连着发好几句话时不用每次都重新握手。
        // 连不上的结果只缓存 30 秒，免得电脑一开机就要等下一轮才认。
        private var cachedKey: String? = null
        private var cachedAt: Long = 0
        private var cached: McpToolset? = null

        fun invalidate() {
            cachedKey = null
            cached = null
            cachedAt = 0
        }

        /**
         * 挨个连服务器、拉工具列表。连不上的直接跳过（记日志），
         * 绝不让一台服务器坏了导致整次提问发不出去。
         */
        fun load(configs: List<McpServerConfig>, tokenOf: (String) -> String): McpToolset {
            val targets = configs.filter { it.enabled && it.url.isNotBlank() }
            if (targets.isEmpty()) return EMPTY

            val key = targets.joinToString("|") { "${it.id}:${it.url}" }
            val now = System.currentTimeMillis()
            val previous = cached
            if (previous != null && key == cachedKey) {
                val ttl = if (previous.specs.isEmpty()) 30_000L else 120_000L
                if (now - cachedAt < ttl) return previous
            }

            val fresh = build(targets, tokenOf)
            cachedKey = key
            cachedAt = now
            cached = fresh
            return fresh
        }

        private fun build(configs: List<McpServerConfig>, tokenOf: (String) -> String): McpToolset {            val specs = mutableListOf<ToolSpec>()
            val routes = mutableMapOf<String, Route>()
            val usedNames = mutableSetOf<String>()

            configs.forEach { config ->
                val client = McpClient(config.url, tokenOf(config.id))
                val info = client.connect().getOrElse { error ->
                    DiagLog.log("mcp", "连不上 ${config.name}（${config.url}）：${error.message}")
                    return@forEach
                }
                val tools = client.listTools().getOrElse { error ->
                    DiagLog.log("mcp", "${config.name} 列工具失败：${error.message}")
                    return@forEach
                }
                DiagLog.log("mcp", "${config.name} 连上了（${info.name} ${info.version}），${tools.size} 个工具")

                val slug = McpClient.slug(config.name.ifBlank { info.name })
                tools.forEach { tool ->
                    val base = "mcp_${slug}_${McpClient.slug(tool.name)}"
                    var exposed = safeToolName(base)
                    var suffix = 2
                    while (!usedNames.add(exposed)) {
                        exposed = safeToolName("${base}_$suffix")
                        suffix++
                    }
                    specs += ToolSpec(
                        name = exposed,
                        description = "（来自电脑上的 ${config.name}）" + tool.description,
                        parameters = tool.inputSchema,
                    )
                    routes[exposed] = Route(config.name.ifBlank { info.name }, tool.name, client)
                }
            }
            return McpToolset(specs, routes)
        }

        private val TOOL_NAME_OK = Regex("^[a-zA-Z0-9_-]{1,128}$")

        /**
         * 兜底：不管上游给出什么名字，交到模型手里之前必须是 a-z A-Z 0-9 _ -
         * （DeepSeek 会严格校验，中文/空格/点号会让整个请求 400）
         */
        private fun safeToolName(candidate: String): String {
            val trimmed = candidate.take(64)
            if (TOOL_NAME_OK.matches(trimmed)) return trimmed
            val ascii = trimmed.map { ch ->
                if ((ch in 'a'..'z') || (ch in 'A'..'Z') || (ch in '0'..'9') || ch == '_' || ch == '-') ch else '_'
            }.joinToString("").trim('_')
            return ascii.ifBlank { "mcp_tool" }.take(64)
        }
    }
}
