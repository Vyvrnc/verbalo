# Roadmap – Transcribro NPU

_Založeno 2026-10-09._

## Hotovo
- 0.1.0: Whisper large-v3-turbo float16 na NPU (QNN HTP, SM8750), rozpoznávací služba,
  hlasová klávesnice, RECOGNIZE_SPEECH, stažení modelu v aplikaci, čeština jako výchozí jazyk.

## Fáze 1 – kvalita a použitelnost
- Ověřit na Foldu: latence prvního a dalších diktátů, Samsung Keyboard, Gboard, HeliBoard.
- Český fine-tune `mikr/whisper-large-v3-czech-cv13` na NPU (kompilace přes AI Hub běží),
  srovnat s turbem na stejné nahrávce (DI zadání).
- Slovníček pojmů (DI, Flowbox, Flowbot, Claude, Confluence…) jako prompt Whisperu
  (`<|startofprev|>`), nad tím náhradový slovník.
- Řezání dlouhého diktátu v tichu (Silero VAD) místo natvrdo po 30 s.
- Nastavení jazyka v aplikaci (dnes jen z volající aplikace, jinak čeština).

## Fáze 2 – plovoucí okno (jako Wispr Flow)
- Plovoucí tlačítko nad všemi aplikacemi (overlay, `SYSTEM_ALERT_WINDOW`), diktování jedním
  klepnutím nebo podržením, bez přepínání klávesnice.
- Vložení textu do aktivního pole: přes AccessibilityService (`ACTION_SET_TEXT`/vložení ze schránky),
  záloha přes schránku.
- Ověřit na Androidu 16: mikrofon z overlaye (foreground service `microphone`, výjimky
  „while-in-use“), Samsung omezení na pozadí.

## Fáze 3 – AI úprava textu na zařízení
- Malý jazykový model (např. Gemma) po přepisu: opravy, přeřeky, interpunkce, odrážky,
  režimy (e-mail, poznámka, seznam).
- Kandidáti běhu: Google AI Edge / LiteRT-LM (GPU), Qualcomm AI Hub + Genie (NPU). Vybrat podle
  češtiny, latence a paměti (Whisper ~2 GB + LLM v 11 GB RAM).

## Komerční produkt – otevřené otázky
- Licence: kód Transcribro ISC a Whisper MIT jsou v pořádku. QNN runtime (AI Stack License)
  dovoluje šířit knihovny jen jako součást aplikace, je zdarma, ale Qualcomm ji může kdykoli
  odvolat. Licenci zkompilovaných modelů z AI Hub je potřeba přečíst. Český fine-tune je Apache-2.0.
- Pokrytí zařízení: NPU cesta jen Snapdragon (binárka pro každý čipset zvlášť); pro ostatní
  telefony záloha (CPU/GPU, menší model).
- Konkurence a odlišení: Gboard (zdarma, offline), Wispr Flow (cloud). Hlavní odlišení: menší
  jazyky (čeština, slovenština, polština, maďarština…), kde velcí hráči ladí méně – fine-tune
  Whisperu pro jazyk + slovníček oboru. K tomu offline, soukromí, AI úprava na zařízení.
- Důkaz odlišení: změřit WER na stejných českých nahrávkách proti Gboardu a Wispr Flow.
