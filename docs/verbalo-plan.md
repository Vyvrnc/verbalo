# Verbalo: new name and look (plan, 2026-11-12)

## Decisions (Matěj, 12. 11. 2026)
- **Name: Verbalo** (from *verbum*, word). Own app, not a fork in appearance; Transcribro
  (soupslurpr, ISC) stays in the credits and in LICENSE.
  - Checked 12. 11.: no app of that name on Google Play or in web search; GitHub user/org
    `verbalo` free, no repo of that name (nearest: Verbalog, verbaloom); no trademark found in
    web search (nearest: EU mark "VERBAL"). Still to do by hand: EUIPO eSearch + USPTO.
  - Rejected for collisions: Verbit, Verbio, Voxara, Voxera, Voxaro, Voxer, Voxly, Scribe/Scriba,
    Polly, Kea. Other free candidates: Voxbird, Voxlet, Scribo, Voxona, Verbona, Scribona, Dictona.
- **Look: the Lexona family.** Same design language as Lexona
  (`projects/DroidDesk/docs/design-language.md`): ink background, teal for actions and "on",
  coral as rare spice, Inter + JetBrains Mono, opaque cards with a 1 px border, 4 px grid,
  radii 8/12/16, fade + 8 px slide when screens and cards enter. Verbalo's own copy:
  `docs/design-language.md`.
- **Mascot: a parrot** (repeats what it hears = transcription). Same rules as the chameleon:
  one character, never speaks, flat style, one-colour silhouette for small places.
- **Release on GitHub with the QNN libraries inside the APK.** Allowed by the QNN runtime
  licence (Maven `com.qualcomm.qti:qnn-runtime`, LICENSE.pdf, clause 1(iv): "distribute and
  sublicense the Software solely in object code format and as incorporated in Your software
  application"; standalone distribution is not allowed; NOTICE.txt must stay). The models
  (Whisper 2 GB from Qualcomm AI Hub, Gemma from Hugging Face) are downloaded in the app, as now.

## Phases
| # | What | State |
|---|---|---|
| A | `docs/design-language.md`, Compose theme (colours, Inter/JetBrains Mono, shapes, type), motion helpers | done 12. 11. (builds; not yet seen on the phone) |
| B | Screens: start (floating button first), settings, history (collapsible), file transcription, first run, policy and licence restyled; Donate removed; checked end to end on a Galaxy A16 (12. 11.). Voice keyboard, RECOGNIZE_SPEECH dialog and credits only take the theme colours | mostly done |
| C | Mascot: parrot generated with Codex (image_generation, Lexona hero as style reference), brand/verbalo-*.png; adaptive launcher icon + monochrome in mipmap-*; mascot on the start screen | done 12. 11. (v1; the silhouette may get a second round) |
| D | Rename: label Verbalo, version 1.0.0 (done 12. 11.). applicationId stays `cz.vyvrnc.transcribronpu` for now: a new one would install as a new app and lose the downloaded 2 GB model, history and settings on the Fold. Kotlin namespaces stay (internal; JNI names bound to them) | done |
| E | Moving from Transcribro NPU: vocabulary, replacements and prompt via the existing JSON export/import; the model is downloaded again (or copied) | |
| F | README for Verbalo, credits (Transcribro ISC, whisper.cpp, Silero, OpenAI Whisper, QNN NOTICE), GitHub repo `Vyvrnc/verbalo`, signed APK on Releases | |
| G | Build check: warn when Maven has a newer `qnn-runtime` than the pinned one | |

## Language (decided 12. 11.)
English (default) + Czech, by the phone's language. Strings in res/values*/strings.xml (upstream),
strings_ui.xml (screens), strings_core.xml (services, notifications, export), strings_settings.xml
(settings, keyboard). Diagnostic log lines, LLM prompts and spoken-command words stay as they are.

## Mascot prompt (for the same image tool as the Lexona chameleon)
Flat vector illustration, same style as the Lexona chameleon: thick dark outlines, rounded shapes,
no gradients, dark ink background #0B132B. A friendly parrot in teal #14B8A6 with lighter teal
#5EEAD4 feather stripes and a single coral #FB7185 accent (cheek patch), big round eye, perched on a
smartphone. Above the phone, three short horizontal text lines appear, as if the parrot's words
turn into text. No speech bubble, no letters, no logos. Variants needed: (1) hero 16:9 with the
parrot on the right half, (2) square app icon foreground on transparent, parrot head and the phone,
(3) one-colour silhouette of the parrot head for the monochrome icon.
