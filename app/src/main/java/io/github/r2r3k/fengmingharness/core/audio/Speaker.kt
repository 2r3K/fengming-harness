package io.github.r2r3k.fengmingharness.core.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * 朗读：用手机自带的 TTS 引擎把回答念出来（不联网更好，联网引擎音质更好）。
 *
 * 只在主线程创建和调用。用完记得 shutdown()，否则会占着音频通道。
 */
class Speaker(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false
    private var failure: String? = null

    /** true = 开始念，false = 念完/出错/被停 */
    var onState: ((Boolean) -> Unit)? = null

    fun init() {
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            if (status != TextToSpeech.SUCCESS) {
                failure = "这台手机没有可用的朗读引擎"
                ready = false
                return@TextToSpeech
            }
            val engine = tts ?: return@TextToSpeech
            val result = runCatching { engine.setLanguage(Locale.CHINA) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                failure = "朗读引擎不支持中文，去手机设置里装个中文语音包"
                ready = false
                return@TextToSpeech
            }
            runCatching { engine.setSpeechRate(1.0f) }
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    onState?.invoke(true)
                }

                override fun onDone(utteranceId: String?) {
                    onState?.invoke(false)
                }

                @Deprecated("兼容旧接口")
                override fun onError(utteranceId: String?) {
                    onState?.invoke(false)
                }
            })
            ready = true
            failure = null
        }
    }

    val isReady: Boolean get() = ready
    val problem: String? get() = failure

    /** 念一段话；返回 false 表示没念成（会给出原因） */
    fun speak(raw: String): String? {
        val engine = tts
        if (engine == null || !ready) return failure ?: "朗读还没准备好，再点一次"
        val text = readable(raw)
        if (text.isBlank()) return "这条没有可念的文字"
        return runCatching {
            engine.stop()
            val code = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "fengming-" + System.currentTimeMillis())
            if (code == TextToSpeech.ERROR) "朗读启动失败" else null
        }.getOrElse { "朗读出错：${it.message ?: "未知原因"}" }
    }

    fun stop() {
        runCatching { tts?.stop() }
        onState?.invoke(false)
    }

    fun shutdown() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
    }

    /** 把 Markdown 洗成适合听的文字：去掉符号、代码块念个提示 */
    private fun readable(raw: String): String {
        var text = raw
        text = text.replace(Regex("```[\\s\\S]*?```"), "（这里有一段代码，跳过）")
        text = text.replace(Regex("`([^`]*)`"), "$1")
        text = text.replace(Regex("^#{1,6}\\s*", RegexOption.MULTILINE), "")
        text = text.replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
        text = text.replace(Regex("\\*([^*]+)\\*"), "$1")
        text = text.replace(Regex("^\\s*[-*+]\\s+", RegexOption.MULTILINE), "")
        text = text.replace(Regex("^\\s*\\d+[.)]\\s+", RegexOption.MULTILINE), "")
        text = text.replace(Regex("^>\\s?", RegexOption.MULTILINE), "")
        text = text.replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1")
        text = text.replace(Regex("\\|"), "，")
        text = text.replace(Regex("-{3,}"), "")
        text = text.replace(Regex("\n{2,}"), "\n")
        return text.trim().take(4000)
    }
}
