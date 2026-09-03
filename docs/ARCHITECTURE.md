# Architecture

## Data flow

```
Phone A (speaker)                                    Phone B (listener)
------------------                                    ------------------
Mic
 │
 ▼
AudioRecorder (16kHz mono PCM, energy VAD)
 │  emits SentenceReady(pcmFloat) on pause/stoppage
 ▼
WhisperEngine.transcribe()  ── whisper.cpp (ggml, on-device) ──▶ text
 │
 ▼
SessionController wraps text in ItantraMessage(TEXT, lang, isAlert, seq)
 │
 ▼
BluetoothTransportManager.send()
 │  (4-byte length prefix + UTF-8 JSON, over RFCOMM socket)
 ▼
   ═══════════════════ Bluetooth Classic RFCOMM ═══════════════════▶
                                                         │
                                          BluetoothTransportManager reader loop
                                                         │  decodes frame → ItantraMessage
                                                         ▼
                                          SessionController.handleIncoming()
                                                         │
                                                         ▼
                                          PiperEngine.speak() (or Android TTS fallback)
                                                         │  streams PCM to AudioTrack
                                                         ▼
                                                       Speaker
```

Only text crosses the wire. This is the entire reason the system beats
raw-audio transmission on bandwidth (Efficiency criterion) — a sentence
that's 2-3KB as 16-bit 16kHz audio is a few dozen bytes as UTF-8 text.

## Why Bluetooth Classic (RFCOMM) and not Wi-Fi Direct

Both were viable per the problem statement ("wifi/Bluetooth connected
embedded device or another phone"). RFCOMM was chosen first because:

- **Simpler connection lifecycle.** `BluetoothSocket`/`BluetoothServerSocket`
  is a plain client/server TCP-like stream once paired. Wi-Fi Direct
  (`WifiP2pManager`) requires peer discovery, group negotiation (who becomes
  group owner), and IP address resolution before you even get a socket -
  more moving parts and more that can fail live during an ISRO judge demo.
- **Permission surface is smaller and better understood**
  (`BLUETOOTH_CONNECT`/`BLUETOOTH_SCAN` on API 31+, legacy `BLUETOOTH*` +
  `ACCESS_FINE_LOCATION` below that) vs. Wi-Fi Direct's additional location
  and network-state permission requirements.
- **Power draw** for a low-bitrate text channel is a non-issue either way,
  but classic BT idle listening draws less than keeping a Wi-Fi radio in
  P2P-group mode - relevant to the Efficiency criterion (CPU/battery during
  idle listening).
- Range is the main trade-off (BT Classic ~10m typical vs Wi-Fi Direct's
  ~50-100m+) - if the SIH demo or later hardening needs more range, that's
  the trigger to add Wi-Fi Direct.

**The transport is fully abstracted behind `BluetoothTransportManager`** with
a small interface surface (`startListening`/`connectTo`/`send`/`disconnect`
+ two callbacks). Swapping in or adding a `WifiDirectTransportManager` with
the same shape is a contained, single-file addition - `SessionController`
doesn't need to change beyond picking which transport to construct.

## Why whisper.cpp for STT

- Already fully working, permissively-licensed (MIT) Android/Kotlin/JNI
  integration ships in the whisper.cpp repo itself
  (`examples/whisper.android`) - vendored here unmodified, so it's a solid,
  proven foundation rather than a from-scratch JNI bridge.
- ggml's quantized models (tiny/base, int4/int8) hit the low/mid-range
  phone CPU/RAM budget the problem statement requires.
- Multilingual out of the box; per-language fine-tuned/distilled checkpoints
  (e.g. IndicWhisper variants) can be dropped in later without code changes
  - `WhisperEngine.resolveModelFile()` already looks for a per-language
    model first, shared model as fallback.

## Why libpiper (piper1-gpl) for TTS

- Purpose-built, lightweight neural TTS (VITS-family) designed for exactly
  this use case (low-resource, offline, embedded-friendly). Real-time
  factor is typically well under 1.0 on phone-class ARM CPUs.
- The problem statement's Accuracy criterion (40% weight) explicitly covers
  "human legibility and flow for TTS" - Piper's voices are specifically
  tuned for natural-sounding output vs. more robotic classical TTS.
- **Trade-off accepted knowingly:** libpiper has two native dependencies
  (onnxruntime, espeak-ng) that don't have off-the-shelf Android
  cross-compiles the way whisper.cpp does. This is real, scoped work -
  see `docs/NATIVE_BUILD.md`. Until it's done, `AndroidTtsFallbackEngine`
  keeps the rest of the app (transport, modes, UI, latency measurement)
  fully testable in parallel.

## Sentence-boundary detection ("pause and stoppage")

`AudioRecorder` uses short-term RMS energy over 20ms frames: speech is
"started" when RMS crosses a threshold, and a "sentence" is emitted once
RMS stays below threshold for `silenceDurationMsToEndSentence` (default
700ms). This is intentionally cheap (no extra ML model) to keep idle
listening CPU near zero, per the Efficiency criterion. If field testing
shows it's too sensitive to background noise, the natural upgrade is
WebRTC VAD (small, C, NDK-friendly) or Silero VAD (ONNX, could literally
reuse the onnxruntime dependency Piper already needs) - noted as a
follow-up in `docs/NATIVE_BUILD.md`.

## Alert/distress messages

Problem statement requires: *"alert type messages will be announced at
highest volume non-interruptible."* Current implementation:
`SessionController.detectAlertKeyword()` flags a message as an alert via a
keyword list (`help`, `emergency`, `danger`, `sos`, plus a couple of Hindi
equivalents as a starting point - this list should grow per-language as a
follow-up). Alert messages: (1) interrupt any TTS currently playing
(`piper.stopSpeaking()` / fallback stop), (2) play at `USAGE_ALARM` audio
attributes so they route to the alarm volume stream instead of voice-call
volume. "Non-interruptible" by the *user* (can't be paused/skipped) is not
yet enforced in the UI - that's a small follow-up in `MainActivity`/
`PiperEngine`.

## Two operating modes

- **Phone Call mode**: mic is continuously open, VAD-segmented, and every
  detected sentence is transcribed + sent immediately. Mirrors a normal
  phone call.
- **Walkie-Talkie mode**: mic only runs while the on-screen PTT button is
  held (`SessionController.onPttDown/onPttUp`), matching a physical
  walkie-talkie's push-to-talk behavior. `PTT_START`/`PTT_END` messages are
  sent so a future UI enhancement can show "peer is talking..." on the
  other phone.

Both modes share the exact same STT → transport → TTS pipeline; only the
mic gating differs.
