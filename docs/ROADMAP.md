# Roadmap – Transcribro NPU

## Done
- 0.1.x: Whisper large-v3-turbo float16 on the NPU (QNN HTP, SM8750), recognition service,
  voice input keyboard, RECOGNIZE_SPEECH, in-app model download, Czech as the default language.
- 0.2.0 (built, field test pending):
  - Phase 1: vocabulary prompt (`<|startofprev|>`), replacement dictionary, splitting long audio at
    pauses, language setting; segments no longer glued together.
  - Phase 2: live partial transcription in the voice keyboard (re-transcribes the current segment about
    every 0.9 s, never delays the final text).
  - Phase 3: floating dictation button (overlay, microphone foreground service, accessibility text
    insertion with clipboard fallback).
  - Phase 4: Gemma 4 E2B cleanup of the floating button's dictation (LiteRT-LM on the GPU).
- 0.2.1: downloads in a dataSync foreground service (survive leaving the app / screen off); cleanup is
  an on/off switch, the format is inferred from the content and the target app (package, field hint,
  text before the cursor), like Wispr Flow's context-aware formatting.

## Next
- Field test on the Fold: first and later dictations, live preview latency, battery and temperature,
  Samsung Keyboard, Gboard, HeliBoard, the floating button in common apps (messengers, browser, mail).
- Czech fine-tune `mikr/whisper-large-v3-czech-cv13` on the NPU vs turbo on the same recordings
  (WhisperBench); offer it as a second model if it is clearly better.
- Live preview: stabilise words (commit text two passes agree on), show it in RECOGNIZE_SPEECH too.
- Cleanup also in the voice keyboard (needs whole-dictation insertion instead of per-segment commits).
- Gemma on the NPU (`gemma-4-E2B-it_qualcomm_sm8750.litertlm`) if GPU latency or battery is a problem;
  check memory next to Whisper (~2 GB DSP-mapped).
- Cleanup: per-app tone preference (casual / formal), like Wispr Flow's styles, if the automatic
  choice is not enough.
- Floating button: start after reboot from a notification action; hold-to-talk mode.
