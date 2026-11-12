# Verbalo design language

Verbalo belongs to the Lexona family: the same palette, type, shapes and motion as Lexona
(`projects/DroidDesk/docs/design-language.md`), with its own mascot and voice. Colours, type and
shapes come from `app/src/main/kotlin/dev/soupslurpr/transcribro/ui/theme/` (Color.kt, Type.kt,
Theme.kt, Motion.kt). A screen that needs a colour or size not listed here adds it to the theme
and to this document first.

## Identity
- **Name**: Verbalo (from *verbum*, word). Subtitle where there is room:
  "Verbalo – speech to text, on your phone".
- **Mascot**: a teal parrot. It repeats what it hears, as the app turns speech into text.
  - App icon: the parrot on a phone, its words turning into lines of text.
  - Small places (notification, themed icon, floating button): a one-colour silhouette.
  - Never recoloured, stretched or put on a busy photo; it never speaks (no speech bubbles).
- **Voice**:
  - plain and specific: say what happens ("Downloads the model, 2.0 GB, once");
  - no hype words, no exclamation marks;
  - full Czech diacritics in Czech texts;
  - privacy is a fact, not a slogan: "Runs on the phone's NPU; nothing is sent".

## Colour
Dark is the default look; light follows the same roles. The app follows the system setting;
"pitch black" replaces the ink background with black (OLED).

| Role | Dark | Light | Use |
|---|---|---|---|
| `background` | `#0B132B` ink | `#F5F9F8` | screen background |
| `surface` | `#111B36` | `#FFFFFF` | cards, sheets, dialogs |
| `surfaceRaised` | `#17233F` | `#EEF5F3` | icon tiles, inputs, selected rows, tonal buttons |
| `border` | `#22304D` | `#D7E3E0` | 1 px card and divider lines |
| `primary` | `#14B8A6` teal | `#0D9488` | main action, recording, active state, progress |
| `primarySoft` | `#5EEAD4` | `#0F766E` | teal text and icons on the background |
| `onPrimary` | `#04211D` | `#FFFFFF` | text and icons on a teal fill |
| `accent` | `#FB7185` coral | `#E11D48` | rare highlight: new, the mascot |
| `success` | `#34D399` | `#059669` | done, model ready |
| `warning` | `#FBBF24` | `#B45309` | works with limits |
| `error` | `#F87171` | `#DC2626` | failed, destructive action |
| `textPrimary` | `#EEF4FF` | `#0B132B` | titles, transcripts |
| `textSecondary` | `#A7B4CC` | `#3D4B66` | body text |
| `textMuted` | `#7584A3` | `#5B6982` | captions, labels, timings |

On Material's slots (Theme.kt): `surface` = background, `surfaceContainer*` = surface or
surfaceRaised, `secondaryContainer` = surfaceRaised with teal content, `outline` = border,
`tertiary` = coral, no tonal tint.

Rules:
- **Teal means "this is the action, or this is on"**: one filled teal button per screen; the
  microphone while recording is teal.
- **Coral is spice**: at most one coral element per screen, never a status or an error.
- **Every panel is opaque**: no translucent cards, dialogs, sheets, overlays or floating button.
- Status has an icon and a word as well as a colour (✓ ready, ⚠ limits, ✗ failed).
- Contrast: body text at least 4.5:1, large text and icons at least 3:1. Teal fill takes
  `onPrimary`, not white.

## Type
UI **Inter**; logs, timings, versions, paths **JetBrains Mono** (both bundled, SIL OFL 1.1).

| Style | Size / weight | Material slot | Use |
|---|---|---|---|
| display | 34 / 800, −1.0 | `displaySmall` | name on the welcome screen only |
| title | 24 / 700 | `headlineSmall` | screen titles |
| app bar | 20 / 600 | `titleLarge` | top bar |
| heading | 18 / 600 | `titleMedium` | card and section headings |
| subheading | 15 / 600 | `titleSmall` | list row titles |
| transcript | 16 / 400, 1.5 | `bodyLarge` | transcribed text |
| body | 14 / 400, 1.5 | `bodyMedium` | text |
| caption | 12 / 400 | `bodySmall` | secondary row text, notes |
| button | 15 / 600 | `labelLarge` | buttons |
| label | 11 / 600, +1.2, UPPERCASE | `labelSmall` | section labels ("NAHRÁVKY") |
| mono | 13 / 400 | `VerbaloType.mono` | values, timings |
| monoSmall | 11 / 400 | `VerbaloType.monoSmall` | log lines |

- Sentence case for titles and buttons.
- Numbers with units and a space ("2,0 GB", "3 min 12 s").

## Space and shape
- 4 dp grid: 4, 8, 12, 16, 24, 32, 48.
- Screen padding 24 (16 under 400 dp width). Between cards 12, inside a card 16, between
  sections 24.
- Radii: 8 chips, inputs, small tiles; 12 cards and buttons; 16 dialogs and sheets; full for
  pills and the record button.
- Touch targets at least 48 × 48 dp.
- No shadows on dark; separation by surface colour and a 1 px border.

## Components
- **Primary button**: teal fill, `onPrimary` text, radius 12, height 52 on setup screens.
  **Secondary**: 1 px teal outline, teal text. **Text button**: teal text. **Destructive**:
  error outline; error fill only in the confirmation dialog.
- **Card**: `surface`, 1 px `border`, radius 12, padding 16.
- **Action row**: 40 × 40 icon tile (`surfaceRaised`), title subheading, one caption line,
  chevron in `textMuted`; the whole row is the target.
- **Section label**: label style, 24 above, 8 below.
- **Transcript block**: bodyLarge in `textPrimary`, selectable; speaker labels ("Já",
  "Protistrana") in titleSmall teal / textSecondary, the time before them in mono `textMuted`.
- **Record button**: round, teal fill while recording, `surfaceRaised` with a teal icon when idle.
- **Floating button and its text bubble** (over other apps) are the exception: neutral and
  quiet, not in the app colours (slate when idle, red while recording, white icon). Decided by
  Matěj on 12. 11. 2026 after trying the teal version on the phone.
- **Progress**: teal bar or ring on `surfaceRaised`; the line above names what happens
  ("Přepisuji 3 min 12 s – 45 %").
- **Dialogs**: opaque `surface`, radius 16, at most two actions, the safe one on the left.
- **Log view**: background colour, monoSmall, `textSecondary`; errors in `error`.

## Icons
Material Symbols, rounded, 24 in rows and 20 in status lines. Filled = active or selected.

## Motion
- 150–250 ms for state changes (fade, colour); 300–400 ms for entering screens
  (fade + 8 dp rise, `VerbaloMotion.screenEnter`). The screen underneath only fades.
- Cards of a screen enter one after another (`Modifier.enterOnce(index)`, 60 ms apart), once.
- No bounce or elastic effects. Nothing moves on its own while the user reads; the recording
  level and progress indicators are the only loops.

## Checklist for a new or changed screen
1. Colours, type and sizes come from the theme.
2. One filled teal button at most.
3. At most one coral element.
4. Status has an icon and a word.
5. Nothing translucent.
6. Sentence case, full Czech diacritics.
7. Targets at least 48 dp.
8. Works in dark, light and pitch black.
9. Checked on the cover screen and the open Fold.
