# Automatic transcription of call recordings (plan, 2026-11-23)

## Goal
Recordings made by a call recorder (first target: ShizuCallRecorder) land in a folder. Verbalo
picks up new ones, transcribes only calls with contacts the user chose, and writes each
transcript as a text file to an output folder. What happens to the text after that (sync,
sorting) is outside the app, apart from an optional Google Drive upload (phase 4).

Audio never leaves the phone. Only the text goes out, and only for chosen contacts.

## Decisions (2026-11-23)
- Contact filter is an **allowlist**: nothing is transcribed unless the number is on it.
- Output is a Markdown file per call; the sync tool is the user's choice (Syncthing works today).
- Google Drive upload from the app is wanted as an option, after the local path works.
- When to transcribe is a setting: **at once** (default: a new recording is picked up and
  transcribed right away, the app runs anyway for the floating button, and a 1-2 min call is a
  few NPU calls) or **only while charging** (WorkManager constraints).
- The file-name format is a setting with presets (ShizuCallRecorder as the default) and a custom
  pattern for other recorders.

## Recording file name (ShizuCallRecorder)
`20261123_113759.374+0100_in_+420600000000.ogg`

`yyyyMMdd_HHmmss.SSS` + UTC offset, `in` / `out`, number in international form. Regex:
`^(\d{8})_(\d{6})\.(\d{3})([+-]\d{4})_(in|out)_(\+?\d+)\.\w+$`.
A file that does not match is skipped (logged), never guessed. Other recorders: add their pattern
later, the parser is a list of patterns.

## Pieces
| # | What | Notes |
|---|---|---|
| 1 | Settings: input folder, output folder | `ACTION_OPEN_DOCUMENT_TREE` + `takePersistableUriPermission`; no storage permission |
| 2 | Allowlist of contacts | Picked with the system contact picker (`ACTION_PICK` on phone numbers): a one-time grant per pick, no `READ_CONTACTS`. Stored as name + number normalised to E.164 (`PhoneNumberUtils.formatNumberToE164`, default region from SIM/locale). Manual entry of a number too. |
| 3 | Scanner | Periodic WorkManager job (15 min minimum) lists the input folder via `DocumentFile`, parses names, keeps a persistent queue (Room or a small JSON file): `pending` / `done` / `skipped(reason)` / `failed(n)` keyed by document URI + size. Files younger than 60 s or still growing are left for the next run (the recorder may still be writing). |
| 4 | Worker | One `CoroutineWorker` (with `requiresCharging` + `requiresBatteryNotLow` only in the "while charging" mode); runs the existing `FileTranscription` pipeline (stereo → speaker turns) as a foreground job (`dataSync`) so Samsung does not freeze it mid-call. One file at a time; cancel = stays `pending`. |
| 5 | Output | `<yyyy-MM-dd_HHmm>_<in|out>_<name-or-number>.md`, frontmatter below, then the transcript with `[mm:ss] Me:` / `Other side:` turns. Written to a temp name, then renamed, so a sync tool never ships half a file. |
| 6 | Status screen | Queue with state per file, "transcribe now" (ignores the charging constraint), "skip". History entry per call as today. |
| 7 | Google Drive (phase 4) | See below. |

### Output frontmatter
```yaml
---
source: verbalo
kind: call
direction: in            # in | out
number: "+420600000000"
contact: "Name from the allowlist"
started: 2026-11-23T11:37:59+01:00
duration_s: 312
language: cs
recording: 20261123_113759.374+0100_in_+420600000000.ogg
failed_windows: 0
---
```

## Phases
| Phase | Content | Depends on |
|---|---|---|
| 0 | Lower CPU use of file transcription (separate session on the phone) — merge first | — |
| 1 | Folders, allowlist, parser, queue, scanner, worker, `.md` output | 0 |
| 2 | Status screen, notifications ("3 calls transcribed"), retry/skip | 1 |
| 3 | Retention: optionally delete the transcript-side copy / history entry after N days | 1 |
| 4 | Google Drive upload | 1 |

## Phase 4: Google Drive
- Sign-in with Google Identity Services `AuthorizationClient` and the **`drive.file`** scope only
  (the app sees just the files it created). To verify before building: that `drive.file` needs
  no Google app verification, and whether a Workspace admin must allow the app under third-party
  app access.
- OAuth client: Android type, bound to `cz.vyvrnc.transcribronpu` + SHA-1 of the release key.
  A user who builds the app with their own key needs their own client, so the feature is off by
  default in public builds.
- Upload with Drive REST v3 multipart over plain HTTPS (no Google client library), into a folder
  the user picks or a `Verbalo` folder the app creates. Same queue state: `uploaded`.

## Open points
- Calls with numbers withheld (no number in the name): skipped.
- Conference / call waiting: one file per call from the recorder; nothing special.
- The recorder's own retention: Verbalo does not delete recordings.
