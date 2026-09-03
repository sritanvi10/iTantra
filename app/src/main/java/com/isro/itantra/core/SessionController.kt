package com.isro.itantra.core

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import com.isro.itantra.stt.AudioRecorder
import com.isro.itantra.stt.WhisperEngine
import com.isro.itantra.transport.BluetoothTransportManager
import com.isro.itantra.transport.ItantraMessage
import com.isro.itantra.tts.AndroidTtsFallbackEngine
import com.isro.itantra.tts.PiperEngine
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.atomic.AtomicLong

enum class OperatingMode {
    /** Push-to-talk: hold a button to speak, release to send. Mirrors a walkie-talkie. */
    WALKIE_TALKIE,
    /** Continuous duplex: mic is always hot (subject to VAD), like a normal phone call. */
    PHONE_CALL
}

/**
 * Orchestrates: AudioRecorder -> (VAD sentence boundary) -> WhisperEngine (STT)
 * -> BluetoothTransportManager (send text) ... and on receive:
 * BluetoothTransportManager (recv text) -> PiperEngine/AndroidTtsFallbackEngine (TTS) -> speaker.
 *
 * One instance per device; both phones run the identical class, the only
 * difference is which one calls startListening() (host) vs connectTo() (joiner).
 */
class SessionController(
    modelsRootDir: File,
    private val adapter: BluetoothAdapter,
    context: android.content.Context
) {
    private val recorder = AudioRecorder()
    private val whisper = WhisperEngine(modelsRootDir)
    private val piper = PiperEngine(modelsRootDir)
    private val androidTtsFallback = AndroidTtsFallbackEngine(context)
    private val transport = BluetoothTransportManager(adapter)

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val sequenceCounter = AtomicLong(0)

    var mode: OperatingMode = OperatingMode.PHONE_CALL
        private set
    var myLanguage: LanguageCode = LanguageCode.ENGLISH
    var peerPlaybackLanguage: LanguageCode = LanguageCode.ENGLISH
    var pushToTalkHeld: Boolean = false
        private set

    var onTranscript: ((text: String, isOutgoing: Boolean) -> Unit)? = null
    var onConnectionState: ((BluetoothTransportManager.ConnectionState) -> Unit)? = null
    var onLatencyMeasured: ((millis: Long) -> Unit)? = null
    var onEnsureSpeakerRouting: (() -> Unit)? = null

    private data class TtsTask(val text: String, val lang: LanguageCode, val isAlert: Boolean)
    private val ttsChannel = kotlinx.coroutines.channels.Channel<TtsTask>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    private var recording = false

    init {
        transport.setListeners(
            onStateChanged = { state -> onConnectionState?.invoke(state) },
            onMessage = { msg -> handleIncoming(msg) }
        )

        // Dedicated sequential TTS worker: plays every single incoming sentence to 100% completion without cutoffs
        scope.launch(Dispatchers.Default) {
            for (task in ttsChannel) {
                if (recording) recorder.pauseCapture()
                try {
                    if (piper.nativeAvailable) {
                        val completion = CompletableDeferred<Unit>()
                        piper.speak(task.text, task.lang, alertVolume = task.isAlert) {
                            completion.complete(Unit)
                        }
                        completion.await()
                    } else {
                        androidTtsFallback.speak(task.text, task.lang, alertVolume = task.isAlert)
                    }
                } catch (_: Exception) {
                } finally {
                    if (recording) recorder.resumeCapture()
                }
            }
        }
    }

    fun startAsHost() = transport.startListening()
    fun connectToDevice(device: BluetoothDevice) = transport.connectTo(device)
    fun disconnect() = transport.disconnect()

    fun setMode(newMode: OperatingMode) {
        mode = newMode
        if (newMode == OperatingMode.PHONE_CALL) {
            beginListening()
        } else {
            // Walkie-talkie: mic only runs while PTT is held (see onPttDown/Up).
            stopListening()
        }
    }

    fun onLanguageChanged(newLang: LanguageCode) {
        myLanguage = newLang
        peerPlaybackLanguage = newLang
        scope.launch {
            prepareEngines()
        }
    }

    /** Call once, after RECORD_AUDIO permission is granted and models are ready. */
    suspend fun prepareEngines(): Boolean {
        val sttReady = whisper.ensureLoaded(myLanguage)
        val ttsReady = piper.loadVoice(peerPlaybackLanguage)
        return sttReady || ttsReady // allow partial readiness; UI surfaces which half is missing
    }

    // ---- Walkie-talkie push-to-talk ----

    fun onPttDown() {
        if (mode != OperatingMode.WALKIE_TALKIE) return
        pushToTalkHeld = true
        transport.send(ItantraMessage(type = ItantraMessage.MessageType.PTT_START, langCode = myLanguage.code))
        beginListening()
    }

    fun onPttUp() {
        if (mode != OperatingMode.WALKIE_TALKIE) return
        pushToTalkHeld = false
        stopListening(flush = true)
        transport.send(ItantraMessage(type = ItantraMessage.MessageType.PTT_END, langCode = myLanguage.code))
    }

    // ---- Mic / STT pipeline (shared by both modes) ----

    // Holds the latest PCM chunk waiting to be transcribed.
    // AtomicReference ensures only the NEWEST chunk is ever transcribed:
    // if inference is still running when a new SentenceReady arrives,
    // the stale (old) chunk is atomically swapped out and discarded.
    // This eliminates both the queue backlog AND the repeated-phrase
    // hallucination caused by overlapping audio context.
    private val pendingChunk = java.util.concurrent.atomic.AtomicReference<FloatArray?>(null)
    private var transcribeJob: Job? = null

    private var lastSentText = ""
    private var lastSentTimeMs = 0L

    @android.annotation.SuppressLint("MissingPermission")
    private fun beginListening() {
        if (recording) return
        recording = true
        lastSentText = ""
        lastSentTimeMs = 0L
        recorder.start { event ->
            when (event) {
                is AudioRecorder.Event.SentenceReady -> {
                    // Always replace with the newest audio — drop the stale chunk
                    pendingChunk.set(event.pcmFloat)

                    // If no transcription loop is running, start one
                    if (transcribeJob?.isActive != true) {
                        transcribeJob = scope.launch(Dispatchers.Default) {
                            while (isActive) {
                                val pcm = pendingChunk.getAndSet(null) ?: break
                                if (!whisper.ensureLoaded(myLanguage)) break
                                val rawText = whisper.transcribe(pcm, myLanguage.code) ?: continue
                                var text = cleanTranscript(rawText)
                                if (text.isBlank()) continue

                                val now = System.currentTimeMillis()
                                // Skip identical duplicate phrase sent within the last 2 seconds
                                if (text.equals(lastSentText, ignoreCase = true) && (now - lastSentTimeMs < 2000L)) {
                                    continue
                                }
                                // If the new text starts with the previous phrase, strip the already-sent prefix
                                if (lastSentText.isNotEmpty() && (now - lastSentTimeMs < 2000L) &&
                                    text.startsWith(lastSentText, ignoreCase = true) && text.length > lastSentText.length) {
                                    text = text.substring(lastSentText.length).trim()
                                    if (text.isBlank()) continue
                                }

                                lastSentText = text
                                lastSentTimeMs = now

                                val seq = sequenceCounter.incrementAndGet()
                                withContext(Dispatchers.Main) { onTranscript?.invoke(text, true) }
                                transport.send(
                                    ItantraMessage(
                                        type = ItantraMessage.MessageType.TEXT,
                                        text = text,
                                        langCode = myLanguage.code,
                                        isAlert = detectAlertKeyword(text),
                                        sequenceId = seq
                                    )
                                )
                            }
                        }
                    }
                }
                is AudioRecorder.Event.SpeechStarted -> {
                    // Barge-in interruption: Stop TTS playback immediately if user begins speaking
                    piper.stopSpeaking()
                }
                is AudioRecorder.Event.Error -> { /* surface to UI if needed */ }
            }
        }
    }

    private fun cleanTranscript(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ""
        // 1. Tokenize by whitespace
        val tokens = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return ""

        // 2. Remove consecutive duplicate words
        val singleDeduped = ArrayList<String>()
        var lastWord = ""
        for (t in tokens) {
            val w = t.trim()
            if (!w.equals(lastWord, ignoreCase = true)) {
                singleDeduped.add(w)
                lastWord = w
            }
        }
        if (singleDeduped.isEmpty()) return ""

        // 3. Remove repeated full phrase (e.g., "A B A B" -> "A B")
        val n = singleDeduped.size
        val finalText = if (n >= 2 && n % 2 == 0) {
            val half = n / 2
            val firstHalf = singleDeduped.subList(0, half)
            val secondHalf = singleDeduped.subList(half, n)
            if (firstHalf == secondHalf) {
                firstHalf.joinToString(" ")
            } else {
                singleDeduped.joinToString(" ")
            }
        } else {
            singleDeduped.joinToString(" ")
        }

        // 4. Reject pure punctuation/symbols without any alphanumeric characters
        if (finalText.all { !it.isLetterOrDigit() }) return ""
        return finalText
    }

    private fun stopListening(flush: Boolean = false) {
        if (!recording) return
        recording = false
        if (flush) {
            recorder.stopAndFlush()
        } else {
            recorder.stop()
        }
    }

    // ---- Receive path: text -> TTS ----

    private fun handleIncoming(msg: ItantraMessage) {
        when (msg.type) {
            ItantraMessage.MessageType.TEXT -> {
                val recvAt = System.currentTimeMillis()
                onLatencyMeasured?.invoke(recvAt - msg.sentAtEpochMs)
                val cleanText = cleanTranscript(msg.text)
                if (cleanText.isBlank()) return
                onTranscript?.invoke(cleanText, false)

                val lang = LanguageCode.fromCode(msg.langCode) ?: LanguageCode.ENGLISH
                // Alert messages: highest volume, non-interruptible (per spec).
                if (msg.isAlert) piper.stopSpeaking()

                // Enforce speakerphone routing on UI thread before playback starts
                onEnsureSpeakerRouting?.invoke()

                // Queue to dedicated sequential TTS worker so nothing is ever cut off
                ttsChannel.trySend(TtsTask(cleanText, lang, msg.isAlert))
            }
            ItantraMessage.MessageType.PTT_START, ItantraMessage.MessageType.PTT_END,
            ItantraMessage.MessageType.HEARTBEAT, ItantraMessage.MessageType.MODE_SYNC -> {
                // Reserved for UI indicators (e.g. "peer is talking...") - not required for MVP loop.
            }
        }
    }

    private fun detectAlertKeyword(text: String): Boolean {
        val alertWords = listOf("help", "emergency", "danger", "sos", "मदद", "खतरा")
        val lower = text.lowercase()
        return alertWords.any { lower.contains(it) }
    }

    fun shutdown() {
        stopListening()
        transport.disconnect()
        whisper.close()
        piper.close()
        androidTtsFallback.destroy()
        scope.cancel()
    }
}
