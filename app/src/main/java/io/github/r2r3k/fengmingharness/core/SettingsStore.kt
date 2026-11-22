package io.github.r2r3k.fengmingharness.core

import android.content.Context
import io.github.r2r3k.fengmingharness.core.model.AppSettings
import io.github.r2r3k.fengmingharness.core.model.contextTierOf

/**
 * 非敏感配置放 SharedPreferences，Key 走 SecretStore。
 */
class SettingsStore(private val ctx: Context) {

    private val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        baseUrl = prefs.getString("baseUrl", null) ?: "https://api.deepseek.com",
        apiKey = SecretStore.get(ctx, "apiKey").orEmpty(),
        model = prefs.getString("model", null) ?: "deepseek-flash",
        temperature = prefs.getFloat("temperature", 0.3f).toDouble(),
        webSearchDefault = prefs.getBoolean("webSearchDefault", true),
        deepThinkingDefault = prefs.getBoolean("deepThinkingDefault", false),
        contextTier = contextTierOf(prefs.getString("contextTier", null)),
        docParseRich = prefs.getBoolean("docParseRich", true),
    )

    fun save(settings: AppSettings) {
        prefs.edit()
            .putString("baseUrl", settings.baseUrl.trim().trimEnd('/'))
            .putString("model", settings.model.trim())
            .putFloat("temperature", settings.temperature.toFloat())
            .putBoolean("webSearchDefault", settings.webSearchDefault)
            .putBoolean("deepThinkingDefault", settings.deepThinkingDefault)
            .putString("contextTier", settings.contextTier.name)
            .putBoolean("docParseRich", settings.docParseRich)
            .apply()
        SecretStore.put(ctx, "apiKey", settings.apiKey.trim())
    }

    fun saveToggles(webSearch: Boolean, deepThinking: Boolean) {
        prefs.edit()
            .putBoolean("webSearchDefault", webSearch)
            .putBoolean("deepThinkingDefault", deepThinking)
            .apply()
    }

    // ---------- 显示 ----------

    /** 字号档位：1 小 / 2 标准 / 3 大 */
    fun fontLevel(): Int = prefs.getInt("fontLevel", 2).coerceIn(1, 3)

    fun saveFontLevel(level: Int) {
        prefs.edit().putInt("fontLevel", level.coerceIn(1, 3)).apply()
    }

    /** 深色模式：system / light / dark */
    fun themeMode(): String = prefs.getString("themeMode", "system") ?: "system"

    fun saveThemeMode(mode: String) {
        prefs.edit().putString("themeMode", mode).apply()
    }

    // ---------- 本地知识库 ----------

    fun kbTreeUri(): String? = prefs.getString("kbTreeUri", null)

    fun saveKbTreeUri(uri: String?) {
        prefs.edit().apply {
            if (uri == null) remove("kbTreeUri") else putString("kbTreeUri", uri)
        }.apply()
    }

    fun kbDefault(): Boolean = prefs.getBoolean("kbDefault", false)

    fun saveKbDefault(enabled: Boolean) {
        prefs.edit().putBoolean("kbDefault", enabled).apply()
    }

    // ---------- 上下文折叠状态 ----------

    fun foldPromptedAt(): Int = prefs.getInt("foldPromptedAt", 0)

    fun saveFoldPromptedAt(value: Int) {
        prefs.edit().putInt("foldPromptedAt", value).apply()
    }

    fun batteryGuideShown(): Boolean = prefs.getBoolean("batteryGuideShown", false)

    fun saveBatteryGuideShown(shown: Boolean) {
        prefs.edit().putBoolean("batteryGuideShown", shown).apply()
    }

    // ---------- 工具（函数调用） ----------

    fun toolsDefault(): Boolean = prefs.getBoolean("toolsDefault", true)

    fun saveToolsDefault(enabled: Boolean) {
        prefs.edit().putBoolean("toolsDefault", enabled).apply()
    }

    /** 用户说过"以后这个工具不用问"的名字 */
    fun autoTools(): Set<String> =
        prefs.getStringSet("autoTools", emptySet())?.toSet() ?: emptySet()

    fun allowTool(name: String) {
        prefs.edit().putStringSet("autoTools", autoTools() + name).apply()
    }

    fun clearAutoTools() {
        prefs.edit().remove("autoTools").apply()
    }
}
