# Transcribro NPU (fork)

Private fork of [soupslurpr/Transcribro](https://github.com/soupslurpr/Transcribro) (ISC). Speech
recognition runs on the Hexagon NPU of Snapdragon 8 Elite phones (SM8750, Galaxy Z Fold 7):
Whisper large-v3-turbo float16 from Qualcomm AI Hub through QNN HTP. The engine comes from
[WhisperBench](https://github.com/Vyvrnc/WhisperBench) (`app/src/main/cpp/npu_whisper.cpp`).

Changes against upstream:
- whisper.cpp tiny.en (English only, CPU) is gone; `cz.vyvrnc.transcribronpu.npu.NpuEngine` transcribes.
- Language from `RecognizerIntent.EXTRA_LANGUAGE`, Czech otherwise.
- The model (2.0 GB) is downloaded in the app (Settings -> NPU model) straight from Qualcomm. Neither
  the model nor the QNN libraries are open source (Qualcomm licences), so this is for private use.
- The model stays loaded between dictations and is released after 10 idle minutes.
- Segments are joined with a space (upstream joined them with ", ").
- Settings -> recognition: language (from the calling app, a fixed one or auto-detect), a vocabulary
  fed to Whisper as a prompt (`<|startofprev|>`), and a replacement dictionary ("heard => written").
- Dictation longer than 30 s is cut at the quietest moment before each window ends.
- Live preview: the voice keyboard shows unfinished text (composing text) while you speak.
- Floating dictation button over all apps (overlay + microphone foreground service, started from the
  settings). An optional accessibility service puts the text into the focused field; without it the
  text goes to the clipboard.
- Optional on-device cleanup of the floating button's dictation with Gemma 4 E2B (LiteRT-LM, GPU,
  Apache 2.0, 2.6 GB downloaded in the app). A single switch: the model fixes recognition errors,
  punctuation and self-corrections and picks the form itself (list, e-mail, chat message) from the
  content and the app the text goes into.
- Model downloads run in a foreground service, so they continue in the background.

Measured on a Galaxy Z Fold 7: model load 3.4 s once, then 7.2 s of Czech speech in 0.87 s and
1.8 s in 0.62 s (the encoder always processes a full 30 s window).

Build: `bash tools/build-apk.sh` (Docker, image derived from WhisperBench's); the APK and the
signing key live outside the repo.

---

# Transcribro

Transcribro is a private and on-device speech recognition keyboard and service for Android.\
It uses whisper.cpp to run the OpenAI Whisper family of models and Silero VAD for voice activity detection.\
It features a voice input keyboard, enabling you to type with speech.\
It can also be used by other apps either explicitly or when set as the user-selected speech to text app which some apps
may use for speech to text.

## Language support

Transcribro currently only supports English. However, supporting other languages is planned and tracked in https://github.com/soupslurpr/Transcribro/issues/18.

## Download

Transcribro is available on the [Accrescent](https://accrescent.app) app store and GitHub releases.\
[Accrescent](https://accrescent.app) is the recommended way to get Transcribro as it is more secure
than GitHub releases.\
Click on the badge below to get it on [Accrescent](https://accrescent.app).

<a href="https://accrescent.app/app/dev.soupslurpr.transcribro">
    <img alt="Get it on Accrescent" src="https://accrescent.app/badges/get-it-on.png" height="80">
</a>

The package name and SHA-256 hash of the signing certificate is below, so if you are downloading the APK, you can
verify Transcribro with [`apksigner`](https://developer.android.com/studio/command-line/apksigner#usage-verify)
using `apksigner verify --print-certs Transcribro-X.Y.Z.apk` and/or
[AppVerifier](https://github.com/soupslurpr/AppVerifier).
If you are downloading from [Accrescent](https://accrescent.app) then you should verify
[Accrescent](https://accrescent.app) itself [here](https://accrescent.app/faq#verifying).

dev.soupslurpr.transcribro\
7D:BC:FB:FA:A1:35:B4:4E:6E:93:91:02:25:DC:B1:4E:05:82:91:DA:8C:2D:36:22:73:49:49:B7:1A:B3:BE:64

It can also be found on a [Bluesky post](https://bsky.app/profile/soupslurpr.dev/post/3kopox4ffl72t)
to distrust the website.
It is encouraged to verify it's the same with other people as well for assurance.

## Community

Join the Matrix space at https://matrix.to/#/#transcribro:matrix.org for the General, Announcements, and
Testing rooms.

## Contributing

Check [CONTRIBUTING.md](https://github.com/soupslurpr/Transcribro/blob/master/CONTRIBUTING.md) for things to know
if you want to contribute.

## Screenshots

<img src="/Screenshot_20250214-223133.png" alt="Screenshot of the keyboard UI, focused on the search bar of Vanadium's incognito tab." width="250">
