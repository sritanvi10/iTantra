# Licensing — read before submission/distribution

This matters for an ISRO/SIH submission, so it's called out on its own
rather than buried in ARCHITECTURE.md.

## The short version

- **whisper.cpp + ggml (STT half)**: MIT license. No obligations beyond
  keeping the copyright notice. No issue for any kind of distribution.
- **piper1-gpl (TTS half) + espeak-ng: GNU GPLv3.** The repo name change
  from `piper1` to `piper1-gpl` reflects this — Piper was relicensed to
  GPLv3 specifically because it depends on espeak-ng, which is GPLv3.

## What GPLv3 actually requires here

If `libpiper` (and therefore espeak-ng) is compiled into the app and
distributed — e.g. as an APK handed to ISRO judges or installed on test
devices outside your own team — GPLv3's copyleft terms apply to the
combined work. Practically, that means:

- The complete corresponding source code of the app (not just the GPL
  parts) must be made available to anyone you distribute the APK to, under
  GPLv3-compatible terms.
- This is very likely fine for an open hackathon submission (ISRO/SIH
  submissions are typically expected to include full source anyway, and
  this repo is already structured as an open, documented codebase), but
  it's worth being deliberate about rather than finding out later.
- If distributing under GPLv3 for the whole app is *not* what you want,
  the options are: (a) proceed anyway since it likely doesn't matter for a
  hackathon submission, (b) replace `libpiper` with an MIT/Apache-licensed
  TTS engine (e.g. `sherpa-onnx`, which is Apache-2.0 and already the
  fallback path noted in `docs/NATIVE_BUILD.md`), or (c) keep the Android
  system-TTS fallback (`AndroidTtsFallbackEngine.kt`) as the shipped
  engine instead of native Piper.

## Per-component license summary

| Component | License | Where vendored |
|---|---|---|
| whisper.cpp | MIT | `third_party/whisper.cpp/`, `app/src/main/java/com/whispercpp/` |
| ggml | MIT | `third_party/whisper.cpp/ggml/` |
| libpiper (piper1-gpl) | **GPLv3** | `third_party/piper1-gpl/libpiper/` |
| espeak-ng (not vendored — you build/link it per `docs/NATIVE_BUILD.md`) | **GPLv3** | external |
| onnxruntime (not vendored — AAR you download) | MIT | external |
| g2pW portions used by piper's Chinese phonemizer | Apache-2.0 | `third_party/piper1-gpl/licenses/` (not relevant — you're not using Chinese) |

Full license texts for the vendored code are included as-is:
`third_party/whisper.cpp/LICENSE` (MIT) and
`third_party/piper1-gpl/COPYING` (GPLv3).

## Recommendation

Given the app is already being built openly with full source in this
repo, GPLv3 compliance is likely a non-issue — just make sure whatever you
hand to ISRO/SIH judges includes (or links to) this full source tree, not
just a compiled APK.
