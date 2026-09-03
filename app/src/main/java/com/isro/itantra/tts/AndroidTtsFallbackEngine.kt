package com.isro.itantra.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.isro.itantra.core.LanguageCode
import java.util.UUID

/**
 * Fallback TTS using Android's system TextToSpeech engine.
 *
 * This is NOT the offline neural Piper pipeline the problem statement asks
 * for, and it is NOT guaranteed to work fully offline for all 10 languages
 * (depends on what voice packs the device/OEM ships) - it exists purely so
 * the walkie-talkie / phone-call loop, Bluetooth transport, and UI can be
 * built, run, and demoed on a real device *today*, while the native Piper
 * build (docs/NATIVE_BUILD.md) is completed in parallel.
 *
 * Swap PiperEngine in for this class once nativeAvailable + all 10 voice
 * models are in place - both expose the same speak()/stop() shape by design.
 */
class AndroidTtsFallbackEngine(context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false
    private val pendingUtterances = mutableMapOf<String, () -> Unit>()

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                utteranceId?.let { pendingUtterances.remove(it)?.invoke() }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                utteranceId?.let { pendingUtterances.remove(it)?.invoke() }
            }
        })
    }

    fun speak(text: String, lang: LanguageCode, alertVolume: Boolean = false, onDone: () -> Unit = {}) {
        val engine = tts
        if (!ready || engine == null) {
            onDone()
            return
        }
        val result = engine.setLanguage(lang.toLocale())
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            // Language pack not installed offline on this device.
            onDone()
            return
        }
        val streamType = if (alertVolume) android.media.AudioManager.STREAM_ALARM
                          else android.media.AudioManager.STREAM_VOICE_CALL
        val params = android.os.Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, streamType)
        }
        val id = UUID.randomUUID().toString()
        pendingUtterances[id] = onDone
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, id)
    }

    fun stopSpeaking() {
        tts?.stop()
    }

    fun destroy() {
        tts?.shutdown()
        tts = null
    }
}
