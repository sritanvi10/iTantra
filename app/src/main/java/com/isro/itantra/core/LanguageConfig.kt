package com.isro.itantra.core

import java.util.Locale

/**
 * The 10 languages required by PS 26173.
 * `code` is used as the on-disk model directory name (models/tts/<code>/..., models/stt/<code>/...)
 * `bcp47` is used for the Android TextToSpeech fallback engine and for whisper's language hint.
 */
enum class LanguageCode(val displayName: String, val code: String, val bcp47: String) {
    HINDI("Hindi", "hi", "hi-IN"),
    GUJARATI("Gujarati", "gu", "gu-IN"),
    MARATHI("Marathi", "mr", "mr-IN"),
    KANNADA("Kannada", "kn", "kn-IN"),
    MALAYALAM("Malayalam", "ml", "ml-IN"),
    TAMIL("Tamil", "ta", "ta-IN"),
    TELUGU("Telugu", "te", "te-IN"),
    ODIA("Odia", "or", "or-IN"),
    BENGALI("Bengali", "bn", "bn-IN"),
    ENGLISH("English", "en", "en-IN");

    fun toLocale(): Locale = Locale.forLanguageTag(bcp47)

    companion object {
        fun fromCode(code: String): LanguageCode? = entries.find { it.code == code }

        fun getWhisperLanguageCode(selectedLanguage: String): String {
            return entries.firstOrNull { it.displayName.equals(selectedLanguage, ignoreCase = true) }?.code
                ?: entries.firstOrNull { it.code.equals(selectedLanguage, ignoreCase = true) }?.code
                ?: "en"
        }
    }
}
