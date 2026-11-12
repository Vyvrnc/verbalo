<p align="center">
  <img src="brand/verbalo-hero.png" alt="Verbalo: a teal parrot on a phone whose words turn into lines of text" width="720">
</p>

# Verbalo

**Speech to text, on your phone.** Verbalo runs OpenAI's Whisper (large-v3-turbo) on the
phone's NPU. Nothing is sent anywhere: no account, no ads, no analytics.

It started as a fork of [Transcribro](https://github.com/soupslurpr/Transcribro) by soupslurpr
and has since become its own app: a new engine, new features and a new look.

## What it does
- **Floating button**: a microphone over every app. Tap it to dictate, tap again to stop, and
  the text is typed into the field you are in (or copied to the clipboard).
- **Voice keyboard**: switch to Verbalo from any keyboard and dictate, with a live preview
  while you speak.
- **Transcribe a recording**: a voice message, a recorded call or a video, picked in the app
  or shared from Telegram, WhatsApp and others. Long recordings are cut at pauses and transcribed
  one window at a time.
- **Calls split by speaker**: a stereo call recording (your microphone left, the other side
  right) comes out as a dialogue, `[03:12] Me: …` / `[03:20] Other side: …`, each turn transcribed
  from its own channel.
- **Optional cleanup** with Gemma 4 E2B, also on the phone: fixes misheard words, punctuation and
  self-corrections, and shapes the text for the app it goes into (a list, an e-mail, a chat
  message). Per-app rules and an editable prompt.
- **Vocabulary and replacements**: names and terms the model should prefer, and fixes for words
  it hears wrong. Exported and imported as JSON.
- **History**: recordings and transcripts, kept on the phone, with playback; share any of them
  as text, `.md` or `.txt`.
- English and Czech UI. Czech is the default recognition language; others can be chosen.

## Requirements
- A phone with a **Snapdragon 8 Elite (SM8750)**, for example Galaxy S25, S25 Ultra or
  Z Fold7. The Whisper model is compiled for its Hexagon NPU and does not run on other chips;
  on other phones the app opens and says so.
- Android 12 or newer and about 2.5 GB of free space for the Whisper model (another 2.6 GB for
  the optional Gemma cleanup).

## Install
1. Download the APK from [Releases](../../releases) and install it.
2. Open Verbalo → Settings → **NPU model** and download it (2.0 GB, straight from Qualcomm
   AI Hub; Wi-Fi recommended).
3. On the home screen, turn on the **floating button**. To type straight into fields, turn on
   Verbalo in Settings → Accessibility; without it the text goes to the clipboard.

## Privacy
Audio is processed on the phone. The network is used only to download the models when you tap
Download (Whisper from Qualcomm AI Hub, Gemma from Hugging Face). The full policy is shown on
first start and in Settings.

## Build
```bash
bash tools/build-apk.sh          # Docker; writes a signed APK outside the repo
```
The signing key is generated on the first run outside the repository and is never committed.
Design rules: [docs/design-language.md](docs/design-language.md).

## Credits and licenses
- Verbalo: [ISC](LICENSE.txt), © 2024–2025 soupslurpr (Transcribro), © 2026 Vyvrnc.
- [OpenAI Whisper](LICENSE.openai-whisper.txt) (MIT). The NPU build of large-v3-turbo comes
  from Qualcomm AI Hub and is downloaded in the app.
- Qualcomm QNN runtime (`com.qualcomm.qti:qnn-runtime`), shipped inside the app as its license
  allows; its NOTICE and license are in the APK under `assets/qnn/`.
- [Silero VAD](LICENSE.silero-vad.txt) (MIT), [whisper.cpp](LICENSE.whisper.cpp.txt) (MIT),
  Gemma 4 E2B through LiteRT-LM (Apache 2.0, downloaded in the app).
- Fonts: [Inter](LICENSE.inter.txt) and [JetBrains Mono](LICENSE.jetbrains-mono.txt)
  (SIL OFL 1.1).
