package com.isro.itantra.stt

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.*
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Continuously listens on the mic, buffers whisper.cpp-ready 16kHz mono
 * float PCM, and uses simple short-term energy VAD to detect "pause and
 * stoppage" (per PS 26173: "STT module when activated after detecting
 * pauses and stoppages should form the sentences detected").
 *
 * This is intentionally a lightweight energy VAD (no extra ML model) to
 * keep idle-listening CPU/battery cost minimal, matching the "Efficiency"
 * evaluation criterion (CPU usage during idle listening, 20%). A
 * WebRTC-VAD or Silero-VAD swap-in is noted in docs/NATIVE_BUILD.md as a
 * quality upgrade path if the energy VAD proves too sensitive to ambient
 * noise in field testing.
 */
class AudioRecorder(
    private val sampleRate: Int = 16000,
    private val silenceThresholdRms: Double = 0.012,
    private val silenceDurationMsToEndSentence: Long = 280L,   // Balanced 280ms pause threshold
    private val minUtteranceMs: Long = 250L,
    private val preRollMaxFrames: Int = 5                      // 100ms pre-roll
) {
    private var audioRecord: AudioRecord? = null
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var activeOnEvent: ((Event) -> Unit)? = null
    private val utteranceBuf = ArrayList<FloatArray>()
    private var speechStartMs = 0L

    sealed class Event {
        data class SpeechStarted(val atMs: Long) : Event()
        /** Fired once a pause/stoppage after speech is detected - this is the "sentence boundary". */
        data class SentenceReady(val pcmFloat: FloatArray, val durationMs: Long) : Event()
        data class Error(val message: String) : Event()
    }

    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(onEvent: (Event) -> Unit) {
        activeOnEvent = onEvent
        val minBufBytes = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufBytes <= 0) {
            onEvent(Event.Error("Unsupported sample rate / device audio config"))
            return
        }

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufBytes * 4
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            onEvent(Event.Error("AudioRecord failed to initialize"))
            return
        }

        // Enable hardware AcousticEchoCanceler and NoiseSuppressor if available
        if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
            try { android.media.audiofx.AcousticEchoCanceler.create(record.audioSessionId)?.enabled = true } catch (_: Exception) {}
        }
        if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
            try { android.media.audiofx.NoiseSuppressor.create(record.audioSessionId)?.enabled = true } catch (_: Exception) {}
        }

        audioRecord = record
        record.startRecording()

        job = scope.launch {
            val frameSize = sampleRate / 50 // 20ms frames
            val shortBuf = ShortArray(frameSize)
            var speaking = false
            var silenceStartMs = 0L
            speechStartMs = 0L
            var noiseFloorRms = 0.008
            val preRollQueue = java.util.ArrayDeque<FloatArray>(preRollMaxFrames)
            synchronized(utteranceBuf) { utteranceBuf.clear() }

            while (isActive) {
                val n = record.read(shortBuf, 0, frameSize)
                if (n <= 0) continue

                val floats = FloatArray(n) { shortBuf[it] / 32768.0f }
                val rms = rms(floats)
                val now = System.currentTimeMillis()
                // Dynamic speech threshold: 2.5x ambient noise floor (clamped between 0.015 and 0.05)
                val activeThreshold = (noiseFloorRms * 2.5).coerceIn(0.015, 0.05)

                if (rms >= activeThreshold) {
                    if (!speaking) {
                        speaking = true
                        speechStartMs = now
                        synchronized(utteranceBuf) {
                            utteranceBuf.clear()
                            utteranceBuf.addAll(preRollQueue)
                        }
                        onEvent(Event.SpeechStarted(now))
                    }
                    silenceStartMs = 0L
                    synchronized(utteranceBuf) { utteranceBuf.add(floats) }
                } else if (speaking) {
                    synchronized(utteranceBuf) { utteranceBuf.add(floats) }
                    if (silenceStartMs == 0L) silenceStartMs = now
                    if (now - silenceStartMs >= silenceDurationMsToEndSentence) {
                        val durationMs = now - speechStartMs
                        speaking = false
                        silenceStartMs = 0L
                        preRollQueue.clear() // Clear pre-roll queue so previous utterance tail is never prepended into next sentence
                        if (durationMs >= minUtteranceMs) {
                            val merged = flushUtteranceBufLocked()
                            if (merged != null && isSpeechAudio(merged)) {
                                onEvent(Event.SentenceReady(merged, durationMs))
                            }
                        } else {
                            synchronized(utteranceBuf) { utteranceBuf.clear() }
                        }
                    }
                } else {
                    // Update ambient background noise floor estimate while idle
                    noiseFloorRms = noiseFloorRms * 0.95 + rms * 0.05
                    if (preRollQueue.size >= preRollMaxFrames) {
                        preRollQueue.removeFirst()
                    }
                    preRollQueue.addLast(floats)
                }

                // Safety timeout for continuous monologues (> 8s) to avoid memory overflow
                if (speaking && speechStartMs > 0 && (now - speechStartMs >= 8000L)) {
                    val durationMs = now - speechStartMs
                    val merged = flushUtteranceBufLocked()
                    speaking = false
                    silenceStartMs = 0L
                    speechStartMs = 0L
                    preRollQueue.clear()
                    if (merged != null && isSpeechAudio(merged)) {
                        onEvent(Event.SentenceReady(merged, durationMs))
                    }
                }
            }
        }
    }

    private fun isSpeechAudio(samples: FloatArray): Boolean {
        if (samples.isEmpty()) return false
        var sumSq = 0.0
        for (s in samples) {
            val sD = s.toDouble()
            sumSq += sD * sD
        }
        val rms = sqrt(sumSq / samples.size)
        // Balanced speech threshold (0.008 RMS)
        return (rms >= 0.008)
    }

    private fun flushUtteranceBufLocked(): FloatArray? {
        synchronized(utteranceBuf) {
            if (utteranceBuf.isEmpty()) return null
            val totalSize = utteranceBuf.sumOf { it.size }
            if (totalSize <= 0) {
                utteranceBuf.clear()
                return null
            }
            val merged = FloatArray(totalSize)
            var pos = 0
            for (chunk in utteranceBuf) {
                System.arraycopy(chunk, 0, merged, pos, chunk.size)
                pos += chunk.size
            }
            utteranceBuf.clear()
            return merged
        }
    }

    fun stopAndFlush() {
        val eventCb = activeOnEvent
        val now = System.currentTimeMillis()
        val durationMs = if (speechStartMs > 0) now - speechStartMs else 0L

        job?.cancel()
        job = null
        audioRecord?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        audioRecord = null

        val merged = flushUtteranceBufLocked()
        if (merged != null && eventCb != null && merged.isNotEmpty()) {
            eventCb(Event.SentenceReady(merged, durationMs))
        }
        activeOnEvent = null
    }

    fun stop() {
        job?.cancel()
        job = null
        audioRecord?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        audioRecord = null
        synchronized(utteranceBuf) { utteranceBuf.clear() }
        activeOnEvent = null
    }

    /**
     * Pauses microphone capture without releasing the AudioRecord hardware handle.
     * Call this before TTS playback begins to prevent full-duplex HAL lockup.
     * Resume with [resumeCapture] once TTS finishes.
     */
    fun pauseCapture() {
        try { audioRecord?.stop() } catch (_: Exception) {}
    }

    /**
     * Resumes microphone capture after TTS playback ends.
     * Only call this if the recorder was started via [start] and not yet [stop]ped.
     */
    fun resumeCapture() {
        try { audioRecord?.startRecording() } catch (_: Exception) {}
    }

    private fun rms(samples: FloatArray): Double {
        var sum = 0.0
        for (s in samples) sum += (s.toDouble() * s.toDouble())
        return sqrt(sum / samples.size)
    }
}
