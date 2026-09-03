package com.isro.itantra.stt

import com.isro.itantra.core.LanguageCode
import com.whispercpp.whisper.WhisperContext
import java.io.File

/**
 * High-level STT engine. Loads a ggml whisper model (quantized, e.g.
 * ggml-tiny or a fine-tuned/distilled Indic checkpoint) per language and
 * runs transcription on VAD-segmented audio from [AudioRecorder].
 *
 * Model files are not bundled - see docs/MODEL_SETUP.md. Recommended
 * starting point per PS 26173's size/efficiency constraints: ggml-tiny.bin
 * (~75MB, multilingual) as a baseline, upgrading per-language to
 * distilled/quantized Indic-tuned checkpoints as accuracy tuning proceeds
 * (Accuracy is the highest-weighted criterion at 40%).
 */
class WhisperEngine(private val modelsRootDir: File) : AutoCloseable {

    private var context: WhisperContext? = null
    private var loadedModelPath: String? = null

    /**
     * Loads the model for [lang] if not already loaded. Whisper is
     * multilingual so in practice one model may serve several languages;
     * this still tracks per-language paths so per-language fine-tuned
     * checkpoints can be dropped in later without code changes.
     */
    suspend fun ensureLoaded(lang: LanguageCode): Boolean {
        val modelFile = resolveModelFile(lang)
        if (!modelFile.exists()) return false
        if (loadedModelPath == modelFile.absolutePath && context != null) return true

        context?.release()
        context = try {
            WhisperContext.createContextFromFile(modelFile.absolutePath)
        } catch (e: Exception) {
            null
        }
        loadedModelPath = modelFile.absolutePath
        return context != null
    }

    /** Transcribes one VAD-segmented utterance. Returns null on failure/no model loaded. */
    suspend fun transcribe(pcmFloat: FloatArray, languageCode: String = "en"): String? {
        val ctx = context ?: return null
        return try {
            ctx.transcribeData(pcmFloat, printTimestamp = false, language = languageCode).trim()
        } catch (e: Exception) {
            null
        }
    }

    private fun resolveModelFile(lang: LanguageCode): File {
        // Per-language override, falling back to a shared multilingual model.
        val perLang = File(modelsRootDir, "stt/${lang.code}/model.bin")
        if (perLang.exists()) return perLang
        return File(modelsRootDir, "stt/shared/ggml-model.bin")
    }

    override fun close() {
        kotlinx.coroutines.runBlocking { context?.release() }
        context = null
    }
}
