package com.isro.itantra.tts

/**
 * Thin JNI wrapper around libpiper (third_party/piper1-gpl/libpiper).
 *
 * Only loads/links successfully once the native module `piper_jni` has been
 * built - see docs/NATIVE_BUILD.md. Until then, [isAvailable] returns false
 * and callers should fall back to [AndroidTtsFallbackEngine].
 */
object PiperNative {

    var isAvailable: Boolean = false
        private set

    init {
        isAvailable = try {
            System.loadLibrary("piper_jni")
            true
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }

    external fun nativeCreate(modelPath: String, configPath: String?, espeakDataPath: String?): Long
    external fun nativeSynthesizeStart(
        handle: Long,
        text: String,
        speakerId: Int,
        lengthScale: Float,
        noiseScale: Float,
        noiseWScale: Float
    ): Int

    /** outMeta must be an IntArray(2); [0]=sampleRate, [1]=isLast(0/1) after the call. */
    external fun nativeSynthesizeNext(handle: Long, outMeta: IntArray): FloatArray?

    external fun nativeFree(handle: Long)
    external fun nativeVersion(): String

    const val PIPER_OK = 0
    const val PIPER_DONE = 1
    const val PIPER_ERR_GENERIC = -1
}
