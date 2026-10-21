package com.notifyforward.app.engine

import android.speech.tts.TextToSpeech
import java.util.Locale

/** 通知朗读（TTS）。首次调用时初始化引擎，失败时静默降级。 */
object TtsSpeaker {

    private var tts: TextToSpeech? = null
    private var ready = false
    private var initializing = false

    fun speak(context: android.content.Context, text: String) {
        if (text.isBlank()) return
        if (tts == null && !initializing) {
            initializing = true
            tts = TextToSpeech(context.applicationContext) { status ->
                ready = status == TextToSpeech.SUCCESS
                if (ready) {
                    tts?.language = Locale.getDefault()
                }
            }
        }
        val engine = tts ?: return
        if (!ready) {
            // 引擎尚未就绪，稍后重试一次
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (ready) engine.speak(text, TextToSpeech.QUEUE_ADD, null, "nf-${System.nanoTime()}")
            }, 1200)
            return
        }
        engine.speak(text, TextToSpeech.QUEUE_ADD, null, "nf-${System.nanoTime()}")
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
        initializing = false
    }
}
