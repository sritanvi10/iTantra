
#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <sys/sysinfo.h>
#include <string.h>
#include <ctype.h>
#include "whisper.h"
#include "ggml.h"

#define UNUSED(x) (void)(x)
#define TAG "JNI"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,     TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,     TAG, __VA_ARGS__)

static inline int min(int a, int b) {
    return (a < b) ? a : b;
}

static inline int max(int a, int b) {
    return (a > b) ? a : b;
}

struct input_stream_context {
    size_t offset;
    JNIEnv * env;
    jobject thiz;
    jobject input_stream;

    jmethodID mid_available;
    jmethodID mid_read;
};

size_t inputStreamRead(void * ctx, void * output, size_t read_size) {
    struct input_stream_context* is = (struct input_stream_context*)ctx;

    jint avail_size = (*is->env)->CallIntMethod(is->env, is->input_stream, is->mid_available);
    jint size_to_copy = read_size < avail_size ? (jint)read_size : avail_size;

    jbyteArray byte_array = (*is->env)->NewByteArray(is->env, size_to_copy);

    jint n_read = (*is->env)->CallIntMethod(is->env, is->input_stream, is->mid_read, byte_array, 0, size_to_copy);

    if (size_to_copy != read_size || size_to_copy != n_read) {
        LOGI("Insufficient Read: Req=%zu, ToCopy=%d, Available=%d", read_size, size_to_copy, n_read);
    }

    jbyte* byte_array_elements = (*is->env)->GetByteArrayElements(is->env, byte_array, NULL);
    memcpy(output, byte_array_elements, size_to_copy);
    (*is->env)->ReleaseByteArrayElements(is->env, byte_array, byte_array_elements, JNI_ABORT);

    (*is->env)->DeleteLocalRef(is->env, byte_array);

    is->offset += size_to_copy;

    return size_to_copy;
}
bool inputStreamEof(void * ctx) {
    struct input_stream_context* is = (struct input_stream_context*)ctx;

    jint result = (*is->env)->CallIntMethod(is->env, is->input_stream, is->mid_available);
    return result <= 0;
}
void inputStreamClose(void * ctx) {

}

JNIEXPORT jlong JNICALL
Java_com_whispercppdemo_whisper_WhisperLib_00024Companion_initContextFromInputStream(
        JNIEnv *env, jobject thiz, jobject input_stream) {
    UNUSED(thiz);

    struct whisper_context *context = NULL;
    struct whisper_model_loader loader = {};
    struct input_stream_context inp_ctx = {};

    inp_ctx.offset = 0;
    inp_ctx.env = env;
    inp_ctx.thiz = thiz;
    inp_ctx.input_stream = input_stream;

    jclass cls = (*env)->GetObjectClass(env, input_stream);
    inp_ctx.mid_available = (*env)->GetMethodID(env, cls, "available", "()I");
    inp_ctx.mid_read = (*env)->GetMethodID(env, cls, "read", "([BII)I");

    loader.context = &inp_ctx;
    loader.read = inputStreamRead;
    loader.eof = inputStreamEof;
    loader.close = inputStreamClose;

    loader.eof(loader.context);

    context = whisper_init(&loader);
    return (jlong) context;
}

static size_t asset_read(void *ctx, void *output, size_t read_size) {
    return AAsset_read((AAsset *) ctx, output, read_size);
}

static bool asset_is_eof(void *ctx) {
    return AAsset_getRemainingLength64((AAsset *) ctx) <= 0;
}

static void asset_close(void *ctx) {
    AAsset_close((AAsset *) ctx);
}

static struct whisper_context *whisper_init_from_asset(
        JNIEnv *env,
        jobject assetManager,
        const char *asset_path
) {
    LOGI("Loading model from asset '%s'\n", asset_path);
    AAssetManager *asset_manager = AAssetManager_fromJava(env, assetManager);
    AAsset *asset = AAssetManager_open(asset_manager, asset_path, AASSET_MODE_STREAMING);
    if (!asset) {
        LOGW("Failed to open '%s'\n", asset_path);
        return NULL;
    }

    whisper_model_loader loader = {
            .context = asset,
            .read = &asset_read,
            .eof = &asset_is_eof,
            .close = &asset_close
    };

    return whisper_init_with_params(&loader, whisper_context_default_params());
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_initContextFromAsset(
        JNIEnv *env, jobject thiz, jobject assetManager, jstring asset_path_str) {
    UNUSED(thiz);
    struct whisper_context *context = NULL;
    const char *asset_path_chars = (*env)->GetStringUTFChars(env, asset_path_str, NULL);
    context = whisper_init_from_asset(env, assetManager, asset_path_chars);
    (*env)->ReleaseStringUTFChars(env, asset_path_str, asset_path_chars);
    return (jlong) context;
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_initContext(
        JNIEnv *env, jobject thiz, jstring model_path_str) {
    UNUSED(thiz);
    struct whisper_context *context = NULL;
    const char *model_path_chars = (*env)->GetStringUTFChars(env, model_path_str, NULL);
    context = whisper_init_from_file_with_params(model_path_chars, whisper_context_default_params());
    (*env)->ReleaseStringUTFChars(env, model_path_str, model_path_chars);
    return (jlong) context;
}

JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_freeContext(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
UNUSED(env);
UNUSED(thiz);
struct whisper_context *context = (struct whisper_context *) context_ptr;
whisper_free(context);
}

static void apply_native_script_prompt(struct whisper_context *ctx, struct whisper_full_params *params, const char *lang) {
    if (lang == NULL || ctx == NULL || params == NULL) return;
    const char *prompt_str = NULL;
    if (strcmp(lang, "te") == 0) prompt_str = "తెలుగు";
    else if (strcmp(lang, "hi") == 0) prompt_str = "हिंदी";
    else if (strcmp(lang, "gu") == 0) prompt_str = "ગુજરાતી";
    else if (strcmp(lang, "mr") == 0) prompt_str = "मराठी";
    else if (strcmp(lang, "kn") == 0) prompt_str = "ಕನ್ನಡ";
    else if (strcmp(lang, "ml") == 0) prompt_str = "മലയാളം";
    else if (strcmp(lang, "ta") == 0) prompt_str = "தமிழ்";
    else if (strcmp(lang, "or") == 0) prompt_str = "ଓଡ଼ିଆ";
    else if (strcmp(lang, "bn") == 0) prompt_str = "বাংলা";

    if (prompt_str != NULL) {
        static whisper_token tokens[16];
        int n_tokens = whisper_tokenize(ctx, prompt_str, tokens, 16);
        if (n_tokens > 0) {
            params->prompt_tokens = tokens;
            params->prompt_n_tokens = n_tokens;
        }
    }
}

JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_fullTranscribe(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint num_threads, jfloatArray audio_data, jstring lang_str) {
UNUSED(thiz);
struct whisper_context *context = (struct whisper_context *) context_ptr;
jfloat *audio_data_arr = (*env)->GetFloatArrayElements(env, audio_data, NULL);
const jsize raw_length = (*env)->GetArrayLength(env, audio_data);
// Cap sample buffer at 30 seconds max (480,000 floats at 16kHz) to avoid NDK out-of-bounds crash in whisper_pcm_to_mel
const jsize max_samples = 30 * 16000;
const jsize safe_length = (raw_length > max_samples) ? max_samples : raw_length;

const char *lang_chars = NULL;
if (lang_str != NULL) {
lang_chars = (*env)->GetStringUTFChars(env, lang_str, NULL);
}

int audio_duration_ms = (safe_length * 1000) / 16000;
if (audio_duration_ms < 500) audio_duration_ms = 500;
if (audio_duration_ms > 30000) audio_duration_ms = 30000;

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;

    // Check if loaded model is multilingual
    if (whisper_is_multilingual(context) == 0) {
        LOGW("Model is English-only (.en model), forcing language='en'");
        params.language = "en";
    } else {
        params.language = (lang_chars != NULL && strlen(lang_chars) > 0) ? lang_chars : "te";
    }

    params.initial_prompt = NULL;
    params.prompt_tokens = NULL;
    params.prompt_n_tokens = 0;
    params.n_threads = num_threads;
    params.offset_ms = 0;
    params.duration_ms = audio_duration_ms;
    params.no_context = true;
    params.single_segment = false; // Allow full multi-segment transcription so long sentences are never cut off
    params.max_tokens = 128;       // Support complete long sentences up to 70+ words

    // Dynamically calculate context window based on actual audio duration (1 frame = 20ms)
    // with 24 frames (~480ms) safety padding, floored at 100 frames (~2.0 seconds context).
    // Accurately attends to all words in both short phrases and long 8-10s sentences without truncation.
    {
        int n_audio_ctx = (audio_duration_ms / 20) + 24;
        if (n_audio_ctx < 100) n_audio_ctx = 100;
        if (n_audio_ctx > 1500) n_audio_ctx = 1500;
        params.audio_ctx = n_audio_ctx;
    }

    params.suppress_blank = true;
    params.suppress_nst = true;
    params.no_speech_thold = 0.5f;
    params.logprob_thold = -1.0f;
    params.temperature = 0.0f;
    params.temperature_inc = 0.0f; // Disable multi-pass temperature retries
    params.greedy.best_of = 1;

whisper_reset_timings(context);

LOGI("About to run whisper_full with lang: %s, duration_ms: %d, samples: %d, audio_ctx: %d", params.language, audio_duration_ms, safe_length, params.audio_ctx);
if (whisper_full(context, params, audio_data_arr, safe_length) != 0) {
LOGI("Failed to run the model");
} else {
whisper_print_timings(context);
}
if (lang_chars != NULL) {
(*env)->ReleaseStringUTFChars(env, lang_str, lang_chars);
}
(*env)->ReleaseFloatArrayElements(env, audio_data, audio_data_arr, JNI_ABORT);
}

JNIEXPORT jint JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getTextSegmentCount(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    return whisper_full_n_segments(context);
}

static bool is_hallucinated_text(const char *text) {
    if (text == NULL || strlen(text) == 0) return true;
    if (strstr(text, "Subtitles by") != NULL) return true;
    if (strstr(text, "Thank you for watching") != NULL) return true;
    if (strstr(text, "see you next time") != NULL) return true;
    if (strstr(text, "See you next time") != NULL) return true;
    if (strstr(text, "Subscribe") != NULL) return true;
    if (strstr(text, "The end") != NULL) return true;
    if (strstr(text, "the end") != NULL) return true;
    if (strstr(text, "Amara.org") != NULL) return true;

    // Reject standalone punctuation/comma hallucinations (e.g. ",", ".", ", ")
    bool has_alpha = false;
    for (size_t i = 0; i < strlen(text); i++) {
        unsigned char c = (unsigned char)text[i];
        if (c > 127 ||  isalnum(c)) {
            has_alpha = true;
            break;
        }
    }
    if (!has_alpha) return true;

    return false;
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getTextSegment(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    const char *text = whisper_full_get_segment_text(context, index);
    if (is_hallucinated_text(text)) {
        return (*env)->NewStringUTF(env, "");
    }
    jstring string = (*env)->NewStringUTF(env, text);
    return string;
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getTextSegmentT0(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    return whisper_full_get_segment_t0(context, index);
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getTextSegmentT1(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    return whisper_full_get_segment_t1(context, index);
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getSystemInfo(
        JNIEnv *env, jobject thiz
) {
    UNUSED(thiz);
    const char *sysinfo = whisper_print_system_info();
    jstring string = (*env)->NewStringUTF(env, sysinfo);
    return string;
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_benchMemcpy(JNIEnv *env, jobject thiz,
                                                                  jint n_threads) {
    UNUSED(thiz);
    const char *bench_ggml_memcpy = whisper_bench_memcpy_str(n_threads);
    jstring string = (*env)->NewStringUTF(env, bench_ggml_memcpy);
    return string;
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_benchGgmlMulMat(JNIEnv *env, jobject thiz,
                                                                      jint n_threads) {
    UNUSED(thiz);
    const char *bench_ggml_mul_mat = whisper_bench_ggml_mul_mat_str(n_threads);
    jstring string = (*env)->NewStringUTF(env, bench_ggml_mul_mat);
    return string;
}