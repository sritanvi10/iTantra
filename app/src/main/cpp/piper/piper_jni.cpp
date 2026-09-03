// JNI bridge between Kotlin (com.isro.itantra.tts.PiperNative) and the
// libpiper C API (third_party/piper1-gpl/libpiper/include/piper.h).
//
// Kotlin-side declarations live in
// app/src/main/java/com/isro/itantra/tts/PiperNative.kt - method
// names/signatures must stay in sync with the Java_..._PiperNative_
// symbols below.

#include <jni.h>
#include <android/log.h>
#include <vector>
#include <cstring>

#include "piper.h"

#define TAG "PiperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" {

// Creates a synthesizer for one voice model. Returns a native handle
// (pointer to piper_synthesizer, cast to jlong) or 0 on failure.
JNIEXPORT jlong JNICALL
Java_com_isro_itantra_tts_PiperNative_nativeCreate(
        JNIEnv *env, jobject /*thiz*/,
        jstring jModelPath, jstring jConfigPath, jstring jEspeakDataPath) {

    const char *modelPath = env->GetStringUTFChars(jModelPath, nullptr);
    const char *configPath = jConfigPath ? env->GetStringUTFChars(jConfigPath, nullptr) : nullptr;
    const char *espeakDataPath = jEspeakDataPath ? env->GetStringUTFChars(jEspeakDataPath, nullptr) : nullptr;

    piper_create_options options;
    piper_init_create_options(&options);
    options.model_path = modelPath;
    options.config_path = configPath;
    options.espeak_data_path = espeakDataPath;

    piper_synthesizer *synth = piper_create_with_options(&options);

    env->ReleaseStringUTFChars(jModelPath, modelPath);
    if (jConfigPath) env->ReleaseStringUTFChars(jConfigPath, configPath);
    if (jEspeakDataPath) env->ReleaseStringUTFChars(jEspeakDataPath, espeakDataPath);

    if (synth == nullptr) {
        LOGE("piper_create_with_options failed for model %s", modelPath);
        return 0;
    }
    LOGI("Piper synthesizer created (libpiper %s)", piper_version());
    return reinterpret_cast<jlong>(synth);
}

// Begins synthesis of one sentence/utterance. Must be followed by repeated
// nativeSynthesizeNext() calls (Kotlin side) until it returns null.
JNIEXPORT jint JNICALL
Java_com_isro_itantra_tts_PiperNative_nativeSynthesizeStart(
        JNIEnv *env, jobject /*thiz*/, jlong handle, jstring jText,
        jint speakerId, jfloat lengthScale, jfloat noiseScale, jfloat noiseWScale) {

    auto *synth = reinterpret_cast<piper_synthesizer *>(handle);
    if (synth == nullptr) return PIPER_ERR_GENERIC;

    const char *text = env->GetStringUTFChars(jText, nullptr);

    piper_synthesize_options options = piper_default_synthesize_options(synth);
    if (speakerId >= 0) options.speaker_id = speakerId;
    if (lengthScale > 0) options.length_scale = lengthScale;
    if (noiseScale >= 0) options.noise_scale = noiseScale;
    if (noiseWScale >= 0) options.noise_w_scale = noiseWScale;

    int rc = piper_synthesize_start(synth, text, &options);
    env->ReleaseStringUTFChars(jText, text);
    return rc;
}

// Pulls the next audio chunk. Returns a float[] of PCM samples, or an
// empty array once is_last was reached (Kotlin checks a separate
// nativeIsLastChunk() flag set as a side effect of this call via a static
// thread-local — simplified here by returning sample_rate encoded in a
// paired out-param array of size 2: [0]=sampleRate, [1]=isLast(0/1),
// caller then reads the actual audio via nativeLastChunkSamples()).
//
// NOTE: For simplicity/robustness across JNI boundaries this function
// copies samples directly into a jfloatArray; metadata is returned via the
// int[] outMeta{sampleRate, isLast}.
JNIEXPORT jfloatArray JNICALL
Java_com_isro_itantra_tts_PiperNative_nativeSynthesizeNext(
        JNIEnv *env, jobject /*thiz*/, jlong handle, jintArray outMeta) {

    auto *synth = reinterpret_cast<piper_synthesizer *>(handle);
    if (synth == nullptr) return nullptr;

    piper_audio_chunk chunk;
    int rc = piper_synthesize_next(synth, &chunk);

    jint meta[2] = {0, 1}; // default: sampleRate=0, isLast=true
    if (rc == PIPER_ERR_GENERIC) {
        env->SetIntArrayRegion(outMeta, 0, 2, meta);
        return nullptr;
    }

    meta[0] = chunk.sample_rate;
    meta[1] = chunk.is_last ? 1 : 0;
    env->SetIntArrayRegion(outMeta, 0, 2, meta);

    jfloatArray result = env->NewFloatArray(static_cast<jsize>(chunk.num_samples));
    if (chunk.num_samples > 0) {
        env->SetFloatArrayRegion(result, 0, static_cast<jsize>(chunk.num_samples), chunk.samples);
    }
    return result;
}

JNIEXPORT void JNICALL
Java_com_isro_itantra_tts_PiperNative_nativeFree(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto *synth = reinterpret_cast<piper_synthesizer *>(handle);
    if (synth != nullptr) {
        piper_free(synth);
    }
}

JNIEXPORT jstring JNICALL
Java_com_isro_itantra_tts_PiperNative_nativeVersion(JNIEnv *env, jobject /*thiz*/) {
    return env->NewStringUTF(piper_version());
}

} // extern "C"
