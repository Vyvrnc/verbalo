# Roadmap – Transcribro NPU

## Done
- 0.1.x: Whisper large-v3-turbo float16 on the NPU (QNN HTP, SM8750), recognition service,
  voice input keyboard, RECOGNIZE_SPEECH, in-app model download, Czech as the default language.

## Phase 1 – quality and usability
- Field test on the Fold: latency of first and later dictations; Samsung Keyboard, Gboard, HeliBoard.
- Czech fine-tune `mikr/whisper-large-v3-czech-cv13` on the NPU (compiled with AI Hub) and a
  comparison with turbo on the same recordings.
- Domain vocabulary as a Whisper prompt (`<|startofprev|>`), plus a replacement dictionary.
- Split long dictation at silences (Silero VAD) instead of hard 30 s cuts.
- Language setting in the app (today: from the calling app, Czech otherwise).

## Phase 2 – live partial transcription
- While speaking, re-transcribe the current segment about once a second (on the NPU ~0.35 s
  encoder + 11 ms/token, ~0.7 s for a 10 s sentence) and send `partialResults`.
- The keyboard shows unfinished text as composing text and commits it after the pause.
- Stabilisation: a word is committed once two consecutive passes agree on it.
- Measure battery and temperature; the large Czech model (32 decoder layers) will be much slower.

## Phase 3 – floating dictation button
- A floating button over all apps (overlay), dictation with a tap or hold, no keyboard switch.
- Insert text into the focused field through an AccessibilityService, clipboard as fallback.
- Verify on Android 16: microphone access from the overlay (microphone foreground service,
  while-in-use exemptions) and Samsung background limits.

## Phase 4 – on-device text cleanup
- A small language model (e.g. Gemma) after transcription: corrections, false starts,
  punctuation, bullet lists, modes (e-mail, note, list).
- Runtime candidates: Google AI Edge / LiteRT-LM (GPU), Qualcomm AI Hub + Genie (NPU). Pick by
  language quality, latency and memory (Whisper ~2 GB + LLM within 11 GB RAM).
