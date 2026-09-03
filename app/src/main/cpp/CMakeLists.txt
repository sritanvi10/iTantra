cmake_minimum_required(VERSION 3.22.1)
project(itantra_native)

# ---------------------------------------------------------------------------
# iTantra native build
#
# Two independent shared libraries are produced:
#   1. libwhisper.so / libwhisper_v8fp16_va.so  -> STT (whisper.cpp, vendored
#      unmodified from ggml-org/whisper.cpp examples/whisper.android). This
#      builds out of the box with the NDK, no external deps.
#
#   2. libpiper_jni.so -> TTS (OHF-Voice/piper1-gpl libpiper). This one needs
#      two prebuilt dependencies that are NOT vendored in this repo because
#      they must be cross-compiled / downloaded for Android specifically:
#         - onnxruntime Android AAR  (libonnxruntime.so + headers)
#         - espeak-ng cross-compiled for Android (libespeak-ng.a + headers)
#      See docs/NATIVE_BUILD.md for exact steps. Until those are provided,
#      this target is SKIPPED (guarded below) so the STT half of the app
#      still builds and the app falls back to Android's built-in
#      TextToSpeech engine (see tts/AndroidTtsFallbackEngine.kt).
# ---------------------------------------------------------------------------

add_subdirectory(whisper)

if(DEFINED ONNXRUNTIME_AAR_DIR AND DEFINED ESPEAK_NG_NDK_DIR)
    add_subdirectory(piper)
else()
    message(WARNING
        "iTantra: ONNXRUNTIME_AAR_DIR / ESPEAK_NG_NDK_DIR not set - "
        "skipping native Piper TTS build. See docs/NATIVE_BUILD.md. "
        "App will use the Android TextToSpeech fallback until this is set.")
endif()
