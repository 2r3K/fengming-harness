package io.github.r2r3k.fengmingharness.core.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * 语音输入（系统自带的语音识别，不用花钱、不经过我们的服务器）。
 *
 * 用法：按住麦克风 → start()；松手 → stop()（会回调 onFinal）。
 * 只能在主线程创建和调用。
 */
class VoiceInput(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null
    private var active = false

    var onPartial: ((String) -> Unit)? = null
    var onFinal: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(language: String = "zh-CN") {
        if (active) return
        if (!isAvailable()) {
            onError?.invoke("这台手机没有可用的语音识别服务")
            return
        }
        val engine = recognizer ?: runCatching { SpeechRecognizer.createSpeechRecognizer(context) }
            .getOrNull()
            ?: run {
                onError?.invoke("语音识别启动失败")
                return
            }
        recognizer = engine

        engine.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                active = false
                onError?.invoke(describe(error))
            }

            override fun onResults(results: Bundle?) {
                active = false
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (text.isBlank()) onError?.invoke("没听清，再说一次") else onFinal?.invoke(text)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (text.isNotBlank()) onPartial?.invoke(text)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        active = true
        runCatching { engine.startListening(intent) }.onFailure {
            active = false
            onError?.invoke("语音识别启动失败：${it.message ?: "未知原因"}")
        }
    }

    /** 松手：让它把最后的结果交出来 */
    fun stop() {
        if (!active) return
        active = false
        runCatching { recognizer?.stopListening() }
    }

    fun destroy() {
        active = false
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun describe(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "录音出错了"
        SpeechRecognizer.ERROR_CLIENT -> "识别被中断"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "没给录音权限"
        SpeechRecognizer.ERROR_NETWORK -> "识别要用网络，网络不太好"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "识别网络超时"
        SpeechRecognizer.ERROR_NO_MATCH -> "没听清，再说一次"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别服务忙，等一下再按"
        SpeechRecognizer.ERROR_SERVER -> "识别服务器出错"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没听到声音"
        else -> "识别失败（$code）"
    }
}
