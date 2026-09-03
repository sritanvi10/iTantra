# Model setup

Neither STT nor TTS model weights are bundled in this repo (they're large
binary downloads, some with their own licenses to check) - the app expects
them at a fixed on-device path so you can push new/better models later
without touching code:

```
/sdcard/Android/data/com.isro.itantra/files/models/
├── stt/
│   ├── shared/ggml-model.bin          <- fallback used if no per-language model
│   └── <lang-code>/model.bin          <- optional per-language override, e.g. hi/model.bin
├── tts/
│   └── <lang-code>/
│       ├── <lang-code>.onnx
│       └── <lang-code>.onnx.json      <- Piper voice config (required alongside the .onnx)
└── espeak-ng-data/                    <- espeak-ng phoneme data (see docs/NATIVE_BUILD.md)
```

`<lang-code>` is one of: `hi gu mr kn ml ta te or bn en` (see
`core/LanguageConfig.kt`).

## STT (whisper.cpp / ggml)

1. Start with a stock multilingual checkpoint to get the pipeline working
   end to end:
   ```
   # from whisper.cpp's own model download helper, or manually:
   # https://huggingface.co/ggerganov/whisper.cpp  -> ggml-tiny.bin or ggml-base.bin
   ```
   `tiny` (~75MB) is the right starting point for the Efficiency criterion
   (model size / RAM / flash footprint at 20% weight) on low/mid-range
   phones; `base` (~140MB) if `tiny`'s WER is too high in early testing.
2. Push it to the shared fallback slot:
   ```
   adb push ggml-tiny.bin /sdcard/Android/data/com.isro.itantra/files/models/stt/shared/ggml-model.bin
   ```
3. **Accuracy is the highest-weighted criterion (40%)** - stock Whisper's
   Hindi/Indic-language WER is noticeably worse than English out of the
   box. Priority follow-up: look at fine-tuned/distilled Indic checkpoints
   converted to ggml format (search "IndicWhisper ggml" / "whisper ggml
   hindi") and drop them in per-language at
   `stt/<lang-code>/model.bin` - `WhisperEngine` already prefers a
   per-language model over the shared one with no code changes needed.
4. Convert any custom/fine-tuned PyTorch Whisper checkpoint to ggml with
   whisper.cpp's own converter:
   `third_party/whisper.cpp` doesn't include the Python conversion scripts
   (only the C++ runtime was vendored to keep this repo lean) - pull
   `models/convert-pt-to-ggml.py` from the full
   [whisper.cpp repo](https://github.com/ggml-org/whisper.cpp) if you need
   this.

## TTS (Piper)

1. Piper community voices for several of the 10 required languages already
   exist - check
   [rhasspy/piper voices](https://github.com/rhasspy/piper/blob/master/VOICES.md)
   / [Hugging Face: rhasspy/piper-voices](https://huggingface.co/rhasspy/piper-voices)
   for Hindi, Bengali, Tamil, etc. Coverage for all 10 (especially Odia,
   Malayalam, Kannada, Gujarati, Marathi, Telugu) should be checked/tracked
   per-language - some may need training from scratch if no community voice
   exists yet (see `docs/TRAINING.md` in the upstream piper1-gpl repo,
   vendored at `third_party/piper1-gpl/docs/TRAINING.md`, for that path).
2. Each voice download gives you a `<name>.onnx` + `<name>.onnx.json` pair.
   Rename/place them as:
   ```
   adb push hi_voice.onnx      /sdcard/Android/data/com.isro.itantra/files/models/tts/hi/hi.onnx
   adb push hi_voice.onnx.json /sdcard/Android/data/com.isro.itantra/files/models/tts/hi/hi.onnx.json
   ```
3. Push espeak-ng-data alongside (needed for phonemization - see
   `docs/NATIVE_BUILD.md` step 2):
   ```
   adb push espeak-ng-data /sdcard/Android/data/com.isro.itantra/files/models/espeak-ng-data
   ```

## Sizing / efficiency tracking

Once real models are in place, record actual on-device numbers here (or in
a shared sheet) against the Efficiency criterion (20% weight: model size,
app size, idle-listening CPU):

| Language | STT model | Size | TTS voice | Size | Idle CPU % | Notes |
|---|---|---|---|---|---|---|
| en | ggml-tiny.bin | ~75MB | (pending) | | | baseline, not yet Indic-tuned |
| hi | (pending) | | | | | |
| ... | | | | | | |

(Leave this table for the next working session to fill in as models are
actually integrated and measured on a real low/mid-range test device.)
