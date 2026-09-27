# iTantra — Offline Multilingual TTS/STT Neural Transceiver
**SIH 2026 · PS 26173 (ISRO) · Android / Kotlin**

Push-to-talk (walkie-talkie) and always-on (phone call) voice transceiver
that converts speech → text on the sending phone, sends only the *text*
over Bluetooth, and converts text → speech on the receiving phone. This is
what gives the 20–800× bandwidth win over sending raw audio, per the
problem statement.

## What's in this drop

A **buildable Android Studio project skeleton** with:

| Piece | Status |
|---|---|
| App architecture (transport, STT, TTS, session state machine, UI) | ✅ Written, real code |
| Bluetooth RFCOMM transport (host/join, framing, send/recv) | ✅ Written, real code |
| Walkie-Talkie mode (push-to-talk) | ✅ Written, real code |
| Phone Call mode (continuous duplex w/ VAD) | ✅ Written, real code |
| Energy-based VAD (pause/stoppage sentence detection) | ✅ Written, real code |
| Alert keyword detection → non-interruptible max-volume playback | ✅ Written, basic keyword list |
| whisper.cpp STT — Kotlin/JNI bridge | ✅ **Vendored unmodified** from `ggml-org/whisper.cpp`'s own Android example — builds with just the NDK, no extra native deps |
| Piper TTS — Kotlin/JNI bridge | ✅ Written against the real `libpiper` C API, but **cannot link yet** — needs onnxruntime-android + espeak-ng cross-compiled for Android (see `docs/NATIVE_BUILD.md`) |
| Android system-TTS fallback | ✅ Written, real code — lets you run/demo the full loop *today* while the native Piper build is finished |
| Native Piper `.so` build (onnxruntime + espeak-ng for Android) | ❌ Not done — this is the single biggest remaining task, see `docs/NATIVE_BUILD.md` |
| Wi-Fi Direct transport | ❌ Not implemented (Bluetooth chosen first, see `docs/ARCHITECTURE.md` for why + how to add it) |
| Real-device testing / latency & WER measurement | ❌ Not done |

**Bottom line:** you can open this in Android Studio today, build it with just
the NDK (no extra downloads), and run the full walkie-talkie/phone-call loop
between two phones over Bluetooth using whisper.cpp for STT and Android's
built-in TTS as a stand-in for Piper. The two things standing between this
and the final ISRO-spec pipeline are (1) dropping in the actual model files
and (2) finishing the native Piper cross-compile — both scoped precisely in
`docs/`.

⚠ **One thing to read before you submit anything:** `piper1-gpl` (and its
espeak-ng dependency) are GPLv3-licensed, unlike the rest of this stack
(MIT). This has real implications for how the app can be distributed —
see `docs/LICENSING.md`. It's likely a non-issue for an open SIH
submission, but worth being deliberate about rather than surprised by
later.

## Repo layout

```
iTantra-Transceiver/
├── app/src/main/java/com/isro/itantra/
│   ├── MainActivity.kt              UI: mode toggle, connect, PTT, transcript
│   ├── core/
│   │   ├── SessionController.kt     orchestrates STT → transport → TTS
│   │   └── LanguageConfig.kt        the 10 languages
│   ├── transport/
│   │   ├── BluetoothTransportManager.kt   RFCOMM host/join/send/recv
│   │   └── TransportProtocol.kt           wire message format + framing
│   ├── stt/
│   │   ├── AudioRecorder.kt         mic capture + energy VAD (pause detection)
│   │   └── WhisperEngine.kt         wraps vendored whisper.cpp context
│   └── tts/
│       ├── PiperNative.kt           JNI decl for libpiper
│       ├── PiperEngine.kt           streaming synth → AudioTrack
│       └── AndroidTtsFallbackEngine.kt   works today, no native build needed
├── app/src/main/java/com/whispercpp/whisper/   vendored, unmodified whisper.cpp Kotlin/JNI
├── app/src/main/cpp/
│   ├── CMakeLists.txt               ties whisper + piper native modules together
│   ├── whisper/                     vendored, unmodified whisper.cpp jni.c + CMakeLists
│   └── piper/                       our JNI bridge to libpiper (needs deps, see below)
├── third_party/
│   ├── whisper.cpp/                 vendored src/include/ggml from ggml-org/whisper.cpp
│   └── piper1-gpl/libpiper/         vendored libpiper C++ source
└── docs/
    ├── ARCHITECTURE.md              design decisions & data flow
    ├── NATIVE_BUILD.md              exact steps to finish the Piper .so build
    ├── MODEL_SETUP.md               how to get/convert STT+TTS model files
    ├── LICENSING.md                 ⚠ read this — Piper's GPLv3 dependency chain
    └── PROGRESS_LOG.md              what was done this session, what's next
```

## Quick start (today, no extra downloads)

1. Open `iTantra-Transceiver/` in Android Studio (Hedgehog+ recommended, matches
   the AGP 8.2.0 / Gradle 8.4 setup already validated for this project).
   Note: `gradlew`/`gradlew.bat` and the wrapper jar aren't included in this
   drop (the jar is a binary this environment couldn't fetch) — Android
   Studio regenerates them automatically on first open/sync. If you need
   command-line builds before that, run `gradle wrapper --gradle-version 8.4`
   once with a local Gradle install.
2. Let Gradle sync — the whisper.cpp native module builds automatically via
   the NDK (CMake, no external deps). The Piper native module is
   **auto-skipped** until you set `onnxruntime.aar.dir` / `espeakng.ndk.dir`
   in `local.properties` (see `docs/NATIVE_BUILD.md`); the app falls back to
   Android's system TTS in the meantime.
3. Drop STT model file(s) on-device at
   `/Android/data/com.isro.itantra/files/models/stt/shared/ggml-model.bin`
   (see `docs/MODEL_SETUP.md` for exact adb commands and where to get one).
4. Pair the two test phones in Android Bluetooth settings first (OS-level
   pairing is a prerequisite for the RFCOMM connection this app makes).
5. Install the app on both phones. On phone A tap **Host**; on phone B tap
   **Join** and pick phone A from the paired-device list.
6. Pick **Phone Call** mode (always listening) or **Walkie-Talkie** mode
   (hold the button to talk) on each phone independently.
7. Speak — the transcript and round-trip latency show on screen.

## Next session priorities (see `docs/PROGRESS_LOG.md` for detail)

1. Finish the native Piper build (`docs/NATIVE_BUILD.md`) — highest priority,
   this is what makes TTS actually offline/on-spec instead of the fallback.
2. Get STT models in place and run a first real WER pass in one language.
3. Two-device latency measurement (the app already logs it — just needs a
   real run and a place to record results for the evaluation writeup).
4. Tune the VAD thresholds in `AudioRecorder.kt` against real ambient noise.
