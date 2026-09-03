# Progress log

## This session

Built a full Android Studio Kotlin project skeleton for iTantra from
scratch, based on your two chosen upstream repos:

- **STT**: vendored `ggml-org/whisper.cpp`'s own working Android/Kotlin/JNI
  example unmodified (`app/src/main/java/com/whispercpp/whisper/`,
  `app/src/main/cpp/whisper/`, `third_party/whisper.cpp/` for the C++
  core). This builds with just the NDK, no extra dependencies.
- **TTS**: wrote a new JNI bridge (`app/src/main/cpp/piper/piper_jni.cpp`,
  `tts/PiperNative.kt`, `tts/PiperEngine.kt`) against the real
  `OHF-Voice/piper1-gpl` `libpiper` C API (`piper_create_with_options`,
  `piper_synthesize_start/next`, streaming float PCM → `AudioTrack`).
  `libpiper` source is vendored at `third_party/piper1-gpl/libpiper/`.
  **This does not link yet** - it needs onnxruntime-android + espeak-ng
  cross-compiled for Android, which are external downloads/builds, not
  something vendorable as source. Scoped step-by-step in
  `docs/NATIVE_BUILD.md`.
- **Transport**: Bluetooth Classic RFCOMM (`transport/BluetoothTransportManager.kt`),
  chosen over Wi-Fi Direct for simpler connection lifecycle and smaller
  permission surface - reasoning and the Wi-Fi Direct alternative are in
  `docs/ARCHITECTURE.md`. Length-prefixed JSON framing
  (`transport/TransportProtocol.kt`) carries text + language + alert flag +
  timestamp (for latency measurement) + sequence id.
- **VAD / sentence detection**: energy-based RMS VAD in `stt/AudioRecorder.kt`
  implementing the "activated after detecting pauses and stoppages" /
  "sentences" requirement from the problem statement, without adding a
  second ML model (keeps idle-listening CPU low for the Efficiency
  criterion).
- **Both modes**: `core/SessionController.kt` implements Walkie-Talkie
  (push-to-talk, mic gated by `onPttDown`/`onPttUp`) and Phone Call
  (continuous duplex) sharing one STT→transport→TTS pipeline.
- **Alert handling**: basic keyword detection
  (`SessionController.detectAlertKeyword`) → interrupts current playback,
  routes to `USAGE_ALARM` audio attributes at max volume. Keyword list is
  minimal (English + 2 Hindi words) - needs per-language expansion.
- **Fallback TTS**: `tts/AndroidTtsFallbackEngine.kt` uses the system
  TextToSpeech engine so the *entire app* (both modes, transport, UI,
  latency measurement) is runnable and demoable on real hardware today,
  independent of the native Piper build finishing.
- **UI**: single-activity, view-binding based (`MainActivity.kt` +
  `activity_main.xml`) - language picker, mode toggle, host/join/disconnect,
  PTT button (walkie-talkie mode only), live transcript, live latency
  readout.
- Gradle project set up matching your already-validated AGP 8.2.0 / Gradle
  8.4 combination (`gradle/wrapper/gradle-wrapper.properties`,
  root `build.gradle.kts`).

Everything above is real, non-stub code except where explicitly marked
(the `piper` CMake target is skipped until native deps are supplied, and
model weight files are never bundled - see `docs/MODEL_SETUP.md`).

**Not done this session:** actually compiling this project (no Android
SDK/NDK in this environment to verify a full Gradle build), running it on
a device, sourcing/placing any model files, or the native Piper
onnxruntime/espeak-ng cross-compile.

## Next session — suggested order

1. Open in Android Studio, let Gradle sync, fix any AGP/Kotlin/dependency
   version drift (dependency versions here were current as of this
   session but Android tooling moves fast).
2. Get a `ggml-tiny.bin` STT model on-device (`docs/MODEL_SETUP.md`) and
   confirm STT → Bluetooth → transcript-on-screen works between two phones
   with the Android TTS fallback playing the received text out loud.
   This validates the entire architecture end-to-end before touching the
   harder native Piper build.
3. Start `docs/NATIVE_BUILD.md` for Piper (onnxruntime AAR is the fast
   part; espeak-ng NDK cross-compile is the part most likely to need
   iteration).
4. Once Piper links, get at least one real voice model in and confirm
   `PiperNative.isAvailable` flips the app over from the fallback
   automatically (no code change needed - `SessionController` already
   checks this).
5. Start filling in the Efficiency table in `docs/MODEL_SETUP.md` with
   real numbers from a low/mid-range test device.
6. Expand the alert-keyword list per language, and consider whether
   "non-interruptible" needs UI enforcement (currently only *other*
   speech gets interrupted by an alert, not vice versa).
