package com.isro.itantra.tts

import android.media.AudioFormat
import android.media.AudioTrack
import java.io.File
import com.isro.itantra.core.LanguageCode
import kotlin.math.roundToInt

/**
 * High-level Piper TTS engine: loads one voice model per [LanguageCode] and
 * streams synthesized speech straight to a persistent [AudioTrack].
 *
 * KEY DESIGN: A single AudioTrack instance is reused across utterances.
 * Each utterance calls stop()+flush() to drain the hardware buffer, then
 * play() to restart — avoiding HAL teardown/rebuild which competes with
 * AudioRecord's microphone hardware lock.
 *
 * Model files are NOT bundled in this repo (they are 20-100MB+ each and
 * license-restricted downloads) - see docs/MODEL_SETUP.md for the fetch
 * script + expected on-device layout under
 * /storage/.../Android/data/com.isro.itantra/files/models/tts/<lang>/.
 */
class PiperEngine(private val modelsRootDir: File) : AutoCloseable {

    private val handles = mutableMapOf<String, Long>()

    // Single persistent AudioTrack — reused per utterance to avoid HAL lockup.
    // Rebuilt only when the sample rate changes (different voice model).
    private var audioTrack: AudioTrack? = null
    private var trackSampleRate: Int = -1
    private var trackAlertVolume: Boolean = false

    @Volatile private var stopped = false  // barge-in flag

    val nativeAvailable: Boolean get() = PiperNative.isAvailable

    /** Loads (or reuses) the voice model for [lang]. Returns false if the model files are missing. */
    fun loadVoice(lang: LanguageCode): Boolean {
        if (!PiperNative.isAvailable) return false
        if (handles.containsKey(lang.code)) return true

        val voiceDir = File(modelsRootDir, "tts/${lang.code}")
        val files = voiceDir.listFiles() ?: emptyArray()

        val modelFile = File(voiceDir, "${lang.code}.onnx").let {
            if (it.exists()) it else files.firstOrNull { f -> f.extension.lowercase() == "onnx" }
        }
        val configFile = File(voiceDir, "${lang.code}.onnx.json").let {
            if (it.exists()) it else files.firstOrNull { f -> f.name.lowercase().endsWith(".json") }
        }
        val espeakDataDir = File(modelsRootDir, "espeak-ng-data")

        if (modelFile == null || !modelFile.exists() || configFile == null || !configFile.exists()) {
            return false
        }

        val handle = PiperNative.nativeCreate(
            modelFile.absolutePath,
            configFile.absolutePath,
            if (espeakDataDir.exists()) espeakDataDir.absolutePath else null
        )
        if (handle == 0L) return false
        handles[lang.code] = handle
        return true
    }

    /**
     * Synthesizes [text] in [lang] and plays it immediately through the
     * persistent AudioTrack. Caller must pause AudioRecord before calling
     * this and resume it in [onDone] to avoid full-duplex HAL lockup.
     *
     * [onDone] is called once playback finishes or is interrupted.
     * Call from a background thread — this blocks until all audio is queued.
     */
    fun speak(text: String, lang: LanguageCode, alertVolume: Boolean = false, onDone: () -> Unit = {}) {
        val handle = handles[lang.code]
        if (handle == null) {
            onDone()
            return
        }

        stopped = false

        val rc = PiperNative.nativeSynthesizeStart(
            handle, text,
            speakerId = -1,
            lengthScale = 1.0f,
            noiseScale = -1f,
            noiseWScale = -1f
        )
        if (rc == PiperNative.PIPER_ERR_GENERIC) {
            onDone()
            return
        }

        val meta = IntArray(2)
        try {
            while (!stopped) {
                val samples = PiperNative.nativeSynthesizeNext(handle, meta) ?: break
                val sampleRate = meta[0]
                val isLast = meta[1] == 1

                if (sampleRate > 0) {
                    ensureTrack(sampleRate, alertVolume)
                }

                if (samples.isNotEmpty() && !stopped) {
                    val track = audioTrack ?: continue
                    val pcm16 = floatToPcm16(samples)

                    // Ensure track is PLAYING before every write — it may have
                    // been stopped by a previous utterance's finishUtterance() call.
                    if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                        track.play()
                    }

                    track.write(pcm16, 0, pcm16.size)
                }

                if (isLast) break
            }
        } finally {
            // Stop and flush AFTER Piper finishes — clears residual HAL buffer
            // so the next utterance starts from a clean state.
            finishUtterance()
            onDone()
        }
    }

    /** Stops any in-flight playback immediately (barge-in / alert priority). */
    fun stopSpeaking() {
        stopped = true
        finishUtterance()
    }

    // ---- Internal helpers ----

    /**
     * Ensures a persistent AudioTrack exists for [sampleRate].
     * Rebuilds only when sample rate or alert-volume mode changes.
     */
    private fun ensureTrack(sampleRate: Int, alertVolume: Boolean) {
        if (audioTrack != null && trackSampleRate == sampleRate && trackAlertVolume == alertVolume) {
            return  // reuse existing track
        }
        // Release old track cleanly before rebuilding
        audioTrack?.let {
            try { it.pause(); it.flush(); it.stop() } catch (_: Exception) {}
            it.release()
        }
        audioTrack = buildAudioTrack(sampleRate, alertVolume)
        trackSampleRate = sampleRate
        trackAlertVolume = alertVolume
        // Start immediately — will be in PLAYING state for the first write
        audioTrack?.play()
    }

    /**
     * Stops and flushes the AudioTrack after an utterance ends.
     * Critically: this drains the hardware buffer and puts the track into
     * a clean STOPPED state, ready to play() again on the next utterance —
     * without tearing down the HAL connection.
     */
    private fun finishUtterance() {
        audioTrack?.let { track ->
            try {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.stop()    // Stops playback — drains any remaining buffer
                    track.flush()   // Clears residual audio from hardware buffer
                }
            } catch (_: Exception) {}
        }
    }

    private fun buildAudioTrack(sampleRate: Int, alertVolume: Boolean): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(
                        if (alertVolume) android.media.AudioAttributes.USAGE_ALARM
                        else android.media.AudioAttributes.USAGE_MEDIA
                    )
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)  // Low-latency 2x buffer
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.setVolume(1.0f)
        return track
    }

    private fun floatToPcm16(samples: FloatArray, gainFactor: Float = 2.0f): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val amplified = samples[i] * gainFactor
            val clamped = amplified.coerceIn(-1f, 1f)
            val s = (clamped * 32767f).roundToInt()
            bytes[i * 2] = (s and 0xFF).toByte()
            bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    override fun close() {
        handles.values.forEach { PiperNative.nativeFree(it) }
        handles.clear()
        audioTrack?.let {
            try { it.stop(); it.flush() } catch (_: Exception) {}
            it.release()
        }
        audioTrack = null
    }
}
