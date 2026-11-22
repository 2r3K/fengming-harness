package io.github.r2r3k.fengmingharness.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 聊天气泡的语义色：照官方 App 的样子来
 *   - 你发的：淡蓝底
 *   - 我答的：白底纯文字（官方就没有气泡）
 *
 * 深色模式不照抄白色（白底黑字晚上刺眼），换成"暗一档的蓝 + 深底浅字"。
 */
data class ChatPalette(
    val userBubble: Color,
    val onUserBubble: Color,
    val assistantSurface: Color,
    val onAssistant: Color,
)

private val LightChatPalette = ChatPalette(
    userBubble = Color(0xFFE9F2FF),
    onUserBubble = Color(0xFF0B1220),
    assistantSurface = Color(0xFFFFFFFF),
    onAssistant = Color(0xFF14161A),
)

private val DarkChatPalette = ChatPalette(
    userBubble = Color(0xFF1E2A3C),
    onUserBubble = Color(0xFFDCE7FF),
    assistantSurface = Color(0xFF101114),
    onAssistant = Color(0xFFE8EAED),
)

/** 气泡色从这里取，别在界面里硬编码颜色 */
val LocalChatPalette = staticCompositionLocalOf { LightChatPalette }

/**
 * 浅色：纯白背景（M3 默认那个 #FFFBFE 白里透紫，所以显旧）
 * 蓝色是 DeepSeek 那个味道，但压深了一点，白底上看字更清楚。
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF2F5BEA),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE9F2FF),
    onPrimaryContainer = Color(0xFF0B1220),
    secondaryContainer = Color(0xFFEDF1FF),
    onSecondaryContainer = Color(0xFF1B2740),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF14161A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF14161A),
    surfaceVariant = Color(0xFFF4F5F7),
    onSurfaceVariant = Color(0xFF5B6068),
    outline = Color(0xFFDCE0E5),
    outlineVariant = Color(0xFFEBEEF2),
    error = Color(0xFFD93025),
    errorContainer = Color(0xFFFFE9E6),
    onErrorContainer = Color(0xFF7A1B12),
)

/** 深色：接近纯黑的中性底，不带紫 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFF8CA6FF),
    onPrimary = Color(0xFF0B1220),
    primaryContainer = Color(0xFF1E2A3C),
    onPrimaryContainer = Color(0xFFDCE7FF),
    secondaryContainer = Color(0xFF1B2230),
    onSecondaryContainer = Color(0xFFD7E0F5),
    background = Color(0xFF101114),
    onBackground = Color(0xFFE8EAED),
    surface = Color(0xFF101114),
    onSurface = Color(0xFFE8EAED),
    surfaceVariant = Color(0xFF1A1C20),
    onSurfaceVariant = Color(0xFFA9AFB8),
    outline = Color(0xFF33373D),
    outlineVariant = Color(0xFF24262B),
    error = Color(0xFFFF8A80),
    errorContainer = Color(0xFF4A1A14),
    onErrorContainer = Color(0xFFFFDAD5),
)

/**
 * @param darkMode "system" 跟随系统 / "light" 浅色 / "dark" 深色
 */
@Composable
fun PocketHarnessTheme(
    darkMode: String = "system",
    content: @Composable () -> Unit,
) {
    val dark = when (darkMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val scheme = if (dark) DarkColors else LightColors
    val chat = if (dark) DarkChatPalette else LightChatPalette
    CompositionLocalProvider(LocalChatPalette provides chat) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
