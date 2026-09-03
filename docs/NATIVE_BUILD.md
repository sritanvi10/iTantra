# Finishing the native Piper TTS build

This is the single biggest remaining engineering task. Everything else in
the app (Bluetooth transport, both modes, whisper.cpp STT, UI) works
without this - but TTS falls back to Android's system voice until it's
done.

## Why this is needed

`third_party/piper1-gpl/libpiper` (vendored from `OHF-Voice/piper1-gpl`) is
plain, portable C++ - it will compile for Android with the NDK with no
changes. The problem is its two dependencies, whose upstream CMake config
(`ExternalProject_Add` for espeak-ng, `find_package`/downloaded tarball for
onnxruntime) targets **desktop** platforms (Windows/Linux/macOS) only, not
Android:

1. **onnxruntime** - runs the actual VITS-family neural net.
2. **espeak-ng** - does grapheme-to-phoneme conversion (text → phonemes)
   before the neural net runs.

Both need to be obtained/built specifically *for Android*, then pointed at
via `local.properties` (see below) - our
`app/src/main/cpp/piper/CMakeLists.txt` and `piper_jni.cpp` are already
written against the real `libpiper` C API and ready to link against them
once available.

## Step 1 — onnxruntime for Android

Easiest path: Microsoft publishes prebuilt Android AAR releases.

1. Download `onnxruntime-android-<version>.aar` from Maven Central
   (`com.microsoft.onnxruntime:onnxruntime-android`) or the
   [onnxruntime GitHub releases](https://github.com/microsoft/onnxruntime/releases).
2. An `.aar` is a zip - extract it:
   ```
   unzip onnxruntime-android-1.22.0.aar -d onnxruntime-android-1.22.0
   ```
   You need `headers/` (C/C++ API headers) and `jni/<abi>/libonnxruntime.so`
   inside the extracted folder - if the AAR only ships the `.so` (no
   headers), pull `onnxruntime_c_api.h`/`onnxruntime_cxx_api.h` from the
   matching tag in the onnxruntime repo's `include/onnxruntime/core/session/`
   into a `headers/` folder next to it.
3. Note the absolute path to this extracted folder for Step 3.

## Step 2 — espeak-ng cross-compiled for Android

espeak-ng supports CMake + Android NDK toolchain files directly.

1. Clone: `git clone https://github.com/espeak-ng/espeak-ng.git`
2. Configure with the NDK's CMake toolchain file, once per ABI you need
   (`arm64-v8a` is the priority - most low/mid-range phones from the last
   few years are arm64):
   ```
   cmake -B build-arm64 -S espeak-ng \
     -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
     -DANDROID_ABI=arm64-v8a \
     -DANDROID_PLATFORM=android-24 \
     -DBUILD_SHARED_LIBS=OFF \
     -DUSE_ASYNC=OFF \
     -DCMAKE_INSTALL_PREFIX=espeak-ng-android-install
   cmake --build build-arm64 --target install
   ```
   Repeat with `-DANDROID_ABI=armeabi-v7a` into the same install prefix
   (CMake will place `.a` files under `lib/<abi>/` if you pass
   `-DCMAKE_INSTALL_LIBDIR=lib/arm64-v8a` etc, or just run two separate
   installs into `espeak-ng-android-install-arm64` /
   `espeak-ng-android-install-armv7` and adjust the path per-ABI in
   `piper/CMakeLists.txt` if you need both).
3. You also need the espeak-ng **data files** (phoneme dictionaries) on the
   device at runtime - copy `espeak-ng-data/` from the espeak-ng build
   output to `docs/MODEL_SETUP.md`'s on-device models folder
   (`.../models/espeak-ng-data/`), pushed via `adb push` like the voice
   models.

## Step 3 — wire it into Gradle

Create (don't commit) `local.properties` at the project root with:

```properties
sdk.dir=/path/to/Android/sdk
onnxruntime.aar.dir=/absolute/path/to/onnxruntime-android-1.22.0
espeakng.ndk.dir=/absolute/path/to/espeak-ng-android-install
```

Re-sync Gradle. `app/src/main/cpp/CMakeLists.txt` will now build
`piper_jni` instead of skipping it, and `PiperNative.isAvailable` will be
`true` at runtime, so `SessionController` automatically prefers
`PiperEngine` over the Android TTS fallback (see the `if (piper.nativeAvailable)`
branch in `SessionController.handleIncoming()`).

## Step 4 — sanity check before full integration

Before testing through the whole app, it's worth a quick standalone native
test (a small `main.cpp` linking libpiper + onnxruntime + espeak-ng,
compiled with the Android NDK and pushed/run via `adb shell` against one
voice model) to confirm the three libraries actually link and produce
audio, before debugging through JNI/Kotlin as well. Not included in this
drop - worth writing first thing next session if the AAR/espeak-ng
combination gives link errors, since it isolates whether the problem is in
the native build or the JNI bridge.

## Known risk / fallback plan

If cross-compiling espeak-ng or linking onnxruntime turns out to eat more
time than the SIH timeline allows, two fallback options, in order of
preference:
1. **sherpa-onnx** wraps VITS/Piper-compatible voices *and* ships an
   official Android AAR + Kotlin API with prebuilt onnxruntime already
   inside it - trading a bit of binary size for a much shorter integration
   path. (This was the stack used in an earlier iteration of this project
   per prior notes - it remains the pragmatic fallback if native libpiper
   linking stalls.)
2. Keep `AndroidTtsFallbackEngine` as the shipped TTS path for the SIH
   demo, and note the native Piper integration as "in progress, offline
   architecture proven, model loading path complete" in the submission
   writeup - the STT half, transport, and both modes are fully real either
   way.

## Also worth doing here: VAD upgrade

Once onnxruntime is linked in for Piper, Silero VAD (a small ONNX model)
becomes a "free" upgrade to replace the energy-based VAD in
`AudioRecorder.kt` if the energy threshold proves too noise-sensitive in
real testing - same onnxruntime dependency, no new native library needed.
