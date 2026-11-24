[English](README.md) · Deutsch

# Plektra-QA-Harness: ein Mensch tippt, der Computer schaut zu

Dieser Ordner testet Plektra auf einem echten Telefon mit echter Hardware-Tastatur. Ein Mensch tippt auf der Tastatur des Telefons. Der Computer bereitet jedes Textfeld vor, sagt, was getippt werden soll, liest, was angekommen ist, und bewertet es (bestanden oder nicht, Zeichen-Diff, Wörter pro Minute).

Der Computer tippt nie für dich. Plektras Aufgabe ist es, physische Tastendrücke in Text zu verwandeln, also testen nur echte Tastendrücke diese Aufgabe. Der Computer steuert den Bildschirm über [FIRERPA](https://github.com/firerpa/lamda) (lamda) per adb und rührt die Eingabemethode nie an.

Stand: nur im Fork, nicht Teil von Upstream-Plektra. Erprobt auf einem Unihertz Titan 2 Elite (Android 16, ohne Root). Jedes Android-Telefon, auf dem FIRERPA 10.12 über adb läuft, sollte funktionieren.

## Inhalt

1. [Wie ein Durchlauf aussieht und klingt](#1-wie-ein-durchlauf-aussieht-und-klingt)
2. [Was du brauchst](#2-was-du-brauchst)
3. [Einmalige Einrichtung](#3-einmalige-einrichtung)
4. [Eine Suite ausführen](#4-eine-suite-ausführen)
5. [Ergebnisse lesen](#5-ergebnisse-lesen)
6. [Eine eigene Suite schreiben](#6-eine-eigene-suite-schreiben)
7. [Tasten benennen](#7-tasten-benennen)
8. [Nutzung durch einen KI-Agenten (MCP)](#8-nutzung-durch-einen-ki-agenten-mcp)
9. [Wenn etwas schiefgeht](#9-wenn-etwas-schiefgeht)
10. [Am Harness entwickeln](#10-am-harness-entwickeln)
11. [Erfahrungen mit dem Titan 2 Elite](#11-erfahrungen-mit-dem-titan-2-elite)

## 1. Wie ein Durchlauf aussieht und klingt

| Du siehst oder hörst | Bedeutung | Du tust |
| --- | --- | --- |
| Invertierte Farben, Benachrichtigung `Hands off` | Der Computer benutzt den Bildschirm (öffnet die App, leert das Feld). | Das Telefon nicht berühren. |
| Normale Farben. Die Benachrichtigungsleiste zeigt 4 Sekunden lang die Aktion, schließt sich, dann piept der Computer und spricht die Aktion aus. | Du bist dran. | Diese Aktion auf der Tastatur des Telefons tippen. |
| `Still waiting.` und noch einmal die Aktion | 10 ruhige Sekunden lang kam nichts im Feld an. | Anfangen zu tippen. Nach 3 Erinnerungen hält der Durchlauf an, weil er annimmt, dass du weg bist. |
| `Testing finished.` | Der Durchlauf ist vorbei, die Einstellungen des Telefons sind wiederhergestellt. | Die Tabelle am Computer lesen. |
| Die Benachrichtigung `Swipe away to stop testing` | Während eines Durchlaufs immer da. | Nur zum Beenden wegwischen. Das Telefon stellt dann seine Einstellungen selbst wieder her, auch wenn der Computer die Verbindung verloren hat. |

Die Sprachansagen und die Texte auf dem Telefon sind englisch.

Der Harness hält den Bildschirm während eines Durchlaufs an. Am Ende (normal, nach Strg-C oder nach dem Wegwischen) stellt er Farbinvertierung, Bildschirm-Timeout, Wachbleiben und Heads-up-Benachrichtigungen wieder her.

## 2. Was du brauchst

1. Einen Mac oder Linux-Rechner mit [`uv`](https://docs.astral.sh/uv/), [`just`](https://just.systems/) und `adb` (Android Platform-Tools). `uv` lädt Python 3.14 selbst herunter.
2. Ein Android-Telefon mit Hardware-Tastatur, Entwickleroptionen und USB-Debugging, auf dem Plektra installiert und als Tastatur gewählt ist.
3. FIRERPA 10.12 auf dem Telefon, als Benutzer `shell` mit einem Client-Zertifikat (`lamda.pem`) gestartet. Kein Root, keine APK.
4. Optional eine Stimme. [Kokoro](https://huggingface.co/hexgrad/Kokoro-82M) über [kokoro-cli](https://pypi.org/project/kokoro-cli/) klingt am besten und läuft offline. Ohne Kokoro nutzt der Harness `say` unter macOS, `spd-say` / `espeak-ng` unter Linux, oder bleibt stumm.
5. Eine Notiz-App mit einfachem Textfeld. Die Beispiel-Suite nutzt Pinnote (`branislav667.pinnote`).

## 3. Einmalige Einrichtung

1. FIRERPA per adb auf dem Telefon installieren. [firerpa-hitl.md, Abschnitt 1](firerpa-hitl.md#1-install-and-configure-the-users-device-over-adb) (englisch) beschreibt jeden Schritt: den geprüften Server-Build, das Zertifikat, den gepatchten Treiber, mit dem die Tastatur weiterarbeitet, während FIRERPA verbunden ist, und den Startbefehl. `lamda.pem` außerhalb des Repositorys aufbewahren: Es ist ein privater Schlüssel.
2. Optional die Stimme installieren (etwa 121 MB, einmalig):

   ```bash
   uv tool install --python 3.13 kokoro-cli && kokoro setup && kokoro doctor
   ```

3. `qa/.env` anlegen. Die Datei steht in `.gitignore`; nie committen. Eigene Werte eintragen:

   ```dotenv
   FIRERPA_HOST=192.0.2.10          # Adresse des Telefons, oder 127.0.0.1 mit adb forward
   FIRERPA_PORT=65000
   FIRERPA_CERT=/absoluter/pfad/zu/lamda.pem
   FIRERPA_VERSION=10.12
   ADB_TARGET=SERIENNUMMER_ODER_IP:5555  # ein Gerät, das `adb devices` gerade listet
   # PLEKTRA_QA_VOICE=auto          # auto, kokoro, say, espeak oder off
   # PLEKTRA_QA_VOICE_NAME=af_heart # eine Kokoro-Stimme; `kokoro voices` listet sie
   # PLEKTRA_QA_KEYMAP=/pfad/zum/profil.yaml  # Tastenprofil, Standard keymaps/titan2-elite-baseline.yaml (Abschnitt 7)
   ```

   `ADB_TARGET` muss eine Verbindung nennen, die `adb devices` jetzt listet: die USB-Seriennummer oder `IP:5555` für adb über WLAN. Ein Durchlauf, der den Bildschirm steuert, startet ohne diesen Wert nicht.

4. Die Verbindung prüfen:

   ```bash
   cd qa && just qa-connect
   ```

   Das gibt FIRERPA-Version, Laufzeit und Antwortzeit aus und schlägt fehl, wenn die Version nicht `FIRERPA_VERSION` ist.

`just qa-prep` setzt die drei Animationsfaktoren des Telefons auf 0 und schaltet FIRERPAs Dialog-Watcher ein. Es ändert Einstellungen am Telefon, also nur ausführen, wenn du das willst.

## 4. Eine Suite ausführen

Das Telefon in die Hand nehmen, dann:

```bash
cd qa && just qa-run suites/smoke_chords.yaml
```

Der Computer zeigt den verwendeten Keymap (`keymap: live ...` oder `keymap: profile ...`, Abschnitt 7), eine Live-Tabelle und die verwendete Stimme (`voice: kokoro`). Den Signalen aus Abschnitt 1 folgen. Strg-C am Computer beendet den Durchlauf und stellt das Telefon wieder her.

### Einen Durchlauf zum Teilen aufnehmen (optional, standardmäßig aus)

```bash
cd qa && just qa-run suites/smoke_chords.yaml --record
```

`--record` speichert ein mp4 in `~/plektra-qa-recordings/` (`--record-dir` oder `PLEKTRA_QA_RECORD_DIR` ändern den Ort). Nötig sind `scrcpy`, `ffmpeg` und das Telefon an adb (`ADB_TARGET`). Das steckt drin:

- Der Bildschirm des Telefons (eine reine Ansichtskopie; scrcpy läuft mit `--no-control`) und die gesprochenen Ansagen des Computers, zu den Zeitpunkten ihres Sprechens in die Tonspur gelegt (die Stimme wird nicht von den Lautsprechern abgenommen).
- Ein Balken über dem Bildschirm: links ein laufender Timecode; rechts die gerade gehaltenen Tasten (`L-Shift + A`, Kernel-Namen wie `ENTER`, Steuerkreuz-Tasten eingeschlossen) und die Berührungen (`TOUCH (x, y)`, dann `TAP`, `LONG PRESS` oder `SWIPE up (x1, y1) to (x2, y2)`; das Trackpad der Tastatur trägt die Marke `PAD`). Die Punkte der Android-Funktion „Fingertipps anzeigen“ sind während der Aufnahme an und werden danach zurückgesetzt.
- Kapitel, eines je Versuch (der nächste Satz Tastendrücke): im mp4 und als `plektra-qa-*.chapters.txt` zum Einfügen in eine YouTube-Beschreibung (YouTube braucht 0:00 zuerst und Kapitel von mindestens 10 s; kürzere werden dort zusammengelegt).

Tasten und Berührungen kommen aus `getevent` des Telefons; der Gleichlauf mit dem Bild stimmt auf etwa eine Zehntelsekunde. Die Aufnahme zeigt den ganzen Bildschirm, die Statusleiste und jede Taste, die die Person drückt; sie wird nur mit deren Einverständnis geteilt. Nicht für Privates verwenden.

## 5. Ergebnisse lesen

Jeder Versuch ist eine Zeile: Aufforderung, erwarteter Text, Inhalt des Felds, bestanden oder nicht, Genauigkeit, Wörter pro Minute und Abstand zum vorigen Versuch. Ein fehlgeschlagener Durchlauf endet mit Code 1 und gibt pro fehlgeschlagenem Versuch eine `fail`-Zeile aus.

Mögliche Fehler:

| Fehler | Bedeutung |
| --- | --- |
| `stopped from the phone` | Die Stopp-Benachrichtigung war zu sehen und wurde dann weggewischt. Spätere Versuche starten nicht. |
| `never started after the spoken prompts` | Nach der Aktion und allen Erinnerungen wurde nichts getippt. Spätere Versuche starten nicht. |
| `stop card did not appear` | Das Telefon hat die Stopp-Benachrichtigung nie gelistet. Ein Fehler des Harness, nicht dein Wischen. |
| `click target not found: …` / `input field not found` | Die App war nicht auf dem erwarteten Bildschirm. `package`, `activity` und `resource_id` der Suite prüfen. |
| `Unlock the phone once. The harness will not enter a PIN.` | Der Sperrbildschirm ist aktiv. Entsperren und erneut starten. |

Die Tippzeit (und die WPM) läuft von der ersten Taste bis zum Treffer. Ein langsamer Start senkt die WPM also nicht.

## 6. Eine eigene Suite schreiben

Eine Suite ist YAML. Einstellungen oben gelten für jeden Versuch; eine Phase oder ein Versuch kann sie überschreiben:

```yaml
name: my_suite
timeout_s: 30          # Sekunden bis zum Ende, sobald getippt wird
sentinel: "\n"         # ein abschließendes Enter zählt auch als fertig
start_wait_s: 10       # ruhige Sekunden, bevor die Aktion wiederholt wird
max_reprompts: 3
phases:
  - name: notes
    resource_id: branislav667.pinnote:id/pin_text
    trials:
      - prompt: "punctuation: a, b."             # am Computer angezeigt
        shade: "Type a, b."                      # auf dem Telefon angezeigt
        speak: "Type: a, comma, space, b, period."  # laut gesprochen
        expect: "a, b."
        package: branislav667.pinnote
        activity: branislav667.pinnote.NotesActivity
        click_resource_id: branislav667.pinnote:id/notes_fab
      - prompt: "<Ctrl+A>, then <Ctrl+C>"      # Tasten sind Token, siehe Abschnitt 7
        shade: "Press <Ctrl+A>, then <Ctrl+C>"
        expect: "a, b."
        verify_mode: clipboard   # die Zwischenablage statt des Felds prüfen
        clear_field: false       # den Text des vorigen Versuchs behalten
```

Alle Felder eines Versuchs: `prompt`, `expect`, `verify_mode` (`text` oder `clipboard`), `sentinel`, `timeout_s`, `shade`, `speak`, `start_wait_s`, `max_reprompts`, `package`, `activity`, `resource_id`, `text_hint`, `click_resource_id`, `clear_field`, `cue_on_device` (das Telefon piept auch).

Tipps:

1. Satzzeichen in `speak` ausschreiben. Eine Stimme liest `,` und `.` als Pausen. Tasten als Token schreiben (`<Ctrl+A>`), nie als bloße Namen: Abschnitt 7.
2. Resource-IDs mit dem MCP-Werkzeug `inspect_ui_fast` oder `adb shell uiautomator dump` bei beendetem FIRERPA (Abschnitt 11, Punkt 6) finden.
3. Die exportierte Start-Activity einer App öffnen und mit `click_resource_id` in den Editor klicken. Nicht exportierte Activities lassen sich nicht direkt starten.
4. Suchfelder als Ziel vermeiden: Enter schickt sie ab.

## 7. Tasten benennen

Auf dem Titan 2 Elite durchläuft ein Tastendruck drei Schichten:

1. Die aufgedruckte Taste, also das, was auf der Taste steht.
2. Das Hersteller-Remapping (Einstellungen, Shortcut keys, Key setting; das Herstellerpaket `com.agui.shortcutsettings`). Es schreibt eine Taste um, bevor eine App sie sieht.
3. Plektras eigenes Remapping (das Hardware-Tasten-Remapping, zum Beispiel "Swap right Shift and Enter").

"Drücke Ctrl+A" ist deshalb mehrdeutig: Die Taste, die Ctrl macht, kann Fn, Alt oder etwas anderes sein, je nach beiden Remappings. Das Harness gibt darum jede Tastenanweisung, die ein Mensch sieht oder hört, in zwei Fassungen:

1. Die "your"-Fassung, `your Ctrl`: die Taste, die nach beiden Remappings gerade Ctrl macht.
2. Eine Tastenkappen-Fassung, `Keycaps: hold Fn, tap A.`: was zu drücken ist, allein nach den aufgedruckten Zeichen beurteilt.

Einfacher Text in Anführungszeichen, etwa `type "42"`, bedeutet weiterhin Zeichen, die auf beliebige Weise erzeugt werden dürfen. Nur Tasten bekommen die Zwei-Fassungen-Form.

**Tasten als Token schreiben.** In `prompt`, `shade` und `speak` einer Suite steht jede Taste oder jeder Akkord in spitzen Klammern, und zwar mit dem Namen der Funktion, nicht der Taste:

| Schreiben | Bedeutung |
| --- | --- |
| `<Ctrl+A>` | Ein Akkord: die Taste halten, die Ctrl macht, und die Taste mit dem Aufdruck A tippen. Buchstaben und Ziffern sind Tasten dieses Zeichens. |
| `<Tab>`, `<Enter>`, `<Backspace>`, `<Shift+Tab>` | Eine einzelne Taste oder ein Akkord. Namen: Ctrl, Shift, Alt, Meta, Sym, Fn, Tab, Enter, Backspace, Delete, Esc, Space, Back, Home, Recent, Media (auch Control, Return, Del und Escape; Groß- und Kleinschreibung egal). |
| `<Ctrl+A>, then <Ctrl+C>` | Eine Abfolge sind getrennte Token, in Reihenfolge. |
| `<Tab\|Enter>` | Eine der beiden Tasten genügt (in der Suite ein einfaches `|` schreiben). |

Aus `Press <Ctrl+A>, then <Ctrl+C>` macht das Harness drei Texte. Mit dem Test-Baseline-Keymap:

| Wo | Text |
| --- | --- |
| Bildschirm (die Tabelle am Computer) | `Press your Ctrl + A, then your Ctrl + C. Keycaps: hold Fn, tap A; then hold Fn, tap C.` |
| Shade (die Benachrichtigung auf dem Telefon, kurz gehalten) | `Press your Ctrl + A, then your Ctrl + C. Keycaps: Fn+A, then Fn+C.` |
| Gesprochen (nur Wörter, weil die Stimme Symbole als Pausen liest) | `Press your Control plus letter A, then your Control plus letter C. By keycaps, hold the key printed F N, tap letter A. Then hold the key printed F N, tap letter C.` |

Der Bildschirm- und der gesprochene Text sagen auch, wo eine Taste sitzt, wenn das Profil es weiß (`left ⇧ (in the bottom left corner of the keyboard)`). Ein Versuch mit Token bekommt alle drei Texte aus `prompt`, `shade` und `speak` (der gesprochene Text kommt aus `speak`, sonst `shade`, sonst `prompt`). Die Keycaps-Zeile nicht von Hand schreiben.

**Das Harness lehnt eine Suite ab** (oder eine MCP-Versuchsliste), wenn:

1. Ein Tastenname ohne Token dasteht: `Ctrl`, `Control`, `Alt`, `Shift`, `Fn`, `Sym`, `Tab`, `Enter`, `Return`, `Backspace`, `Del`, `Delete`, `Esc`, `Escape`, `Meta`, `Home`, `Back` oder `Recent`, in beliebiger Schreibweise. Die Fehlermeldung nennt das Feld und schlägt das Token vor. Einige gewöhnliche Wendungen sind erlaubt (`home screen`, `back to`, `come back`, `return to`, `new tab`; die Liste ist `ALLOWED_PHRASES` in `plektra_qa/keys.py`).
2. Ein Token fehlerhaft ist oder keine Taste benennt (`<Foo>`).
3. Ein Token nicht zu einer Taste aufgelöst werden kann, zum Beispiel `<Esc>` auf einem Telefon ohne Taste, die Escape macht. Die Meldung nennt das Token. Es gibt nie einen Rückfall auf eine einzige Fassung.

**Woher der Keymap kommt.** `plektra-qa run` und `render` geben am Anfang `keymap: ...` aus, wie `voice: kokoro`.

1. Ist `ADB_TARGET` gesetzt, wird der Keymap nur lesend vom Telefon geholt: die Hersteller-Schicht mit `adb shell getprop` (`persist.sys.agui_programmable_<key>_key`) und Plektras Schicht aus seinen Einstellungen (`adb exec-out run-as <package> cat shared_prefs/plektra_prefs.xml`; dafür ist ein debugfähiger Build nötig; das Paket ist `PLEKTRA_QA_PACKAGE`, Standard `rocks.pkb.plektra.djb`). Lässt sich Plektras Schicht nicht lesen, springen die Regeln des Profils ein, und die Ausgabe sagt es.
2. Sonst, oder mit `PLEKTRA_QA_KEYMAP_SOURCE=profile`, kommt er aus einem Profil: [keymaps/titan2-elite-baseline.yaml](keymaps/titan2-elite-baseline.yaml) (die Tasten ab Werk, die Hersteller-Test-Baseline und die Einstellung "Swap right Shift and Enter") oder die Datei aus `PLEKTRA_QA_KEYMAP=/pfad/zum/profil.yaml`. Die Baseline kopieren, um ein anderes Telefon oder ein anderes Remapping zu beschreiben.
3. Weichen das Lesen vom Telefon und das Profil voneinander ab, gewinnt das Lesen vom Telefon, und die Ausgabe listet die Unterschiede. `PLEKTRA_QA_KEYMAP_SOURCE=live` besteht auf dem Telefon und scheitert, wenn es nicht gelesen werden kann.

Der Keymap wird einmal am Anfang gelesen. Wer während eines Durchlaufs eine Tasteneinstellung ändert, hat veraltete Texte; dann neu starten.

**Ausprobieren oder eine Fragekarte schreiben.** `just qa-keys "<Ctrl+A>, then <Ctrl+C>"` (oder `plektra-qa render "..."`; mit `--json` ein JSON-Objekt) gibt die Bildschirm-, Shade- und gesprochene Fassung aus, ohne das Telefon zu bedienen. Ein Agent, der außerhalb einer Suite einen Menschen bittet, Tasten zu drücken (eine Fragekarte für einen Geräte-Rundgang), fügt die Bildschirm-Fassung von hier ein, statt Tastennamen von Hand zu schreiben.

## 8. Nutzung durch einen KI-Agenten (MCP)

`just qa-mcp` stellt den MCP-Server `plektra-qa-mcp` über stdio bereit. Werkzeuge:

| Werkzeug | Tut |
| --- | --- |
| `device_status` | FIRERPA-Version, Laufzeit, Antwortzeit. |
| `teleport_and_prep_input` | Eine Activity öffnen, ein Feld fokussieren und leeren (hinter dem Bildschirm-Gate). |
| `inspect_ui_fast` | Der UI-Baum des Bildschirms, optional mit JPEG-Screenshot. |
| `execute_ui_action` | Ein Klick oder Tastendruck auf einen Selektor (hinter dem Bildschirm-Gate). |
| `request_human_chord_batch` | Versuche (eine JSON-Liste von Versuchsobjekten) mit tippendem Menschen ausführen und den Bericht zurückgeben. Blockiert, bis der Mensch fertig ist. `record=true` nimmt den Durchlauf zusätzlich auf (Abschnitt 4); nur nach einem Ja des Menschen. |

Regeln für Agenten:

1. Einen Durchlauf nur starten, wenn der Mensch darum gebeten hat und das Telefon hält. Vorher sagen, was er sehen und hören wird.
2. Nie für den Menschen tippen (`set_text` tippt über FIRERPA, nicht über die Tastatur).
3. Nie die aktive Eingabemethode ändern und das Plektra-Paket nie mit `am force-stop` oder `am start -S` behandeln. Android würde die Tastatur wechseln.
4. Keine unbekannten Dialoge anklicken, nicht Zurück drücken, „Nicht stören“ nicht ändern.
5. Tasten in Versuchslisten und in Fragen an den Menschen als Token schreiben (`<Ctrl+A>`): `just qa-keys "<Ctrl+A>"` gibt den Text in beiden Fassungen zum Einfügen aus (Abschnitt 7).
6. Aufnahme (Abschnitt 4) ist aus, solange der Mensch sie nicht wünscht. Zu Beginn jedes geführten Durchlaufs fragen („Bildschirm und Stimme aufnehmen, um es mit der Gruppe zu teilen?“) und `--record` (CLI) oder `record=true` (`request_human_chord_batch`) nur bei einem Ja angeben. Die Antwort gilt nur für diesen Durchlauf. Sagen, wo die Datei liegt.

[firerpa-hitl.md](firerpa-hitl.md) (englisch) ist die vollständige Spezifikation, geschrieben als Bau-Anleitung für einen Agenten.

## 9. Wenn etwas schiefgeht

Das Telefon von Hand wiederherstellen, wenn ein Durchlauf bei invertierten Farben abgebrochen ist. Das sind die Standardwerte des Titan 2 Elite; die eigenen Werte deines Telefons verwenden:

```bash
adb -s "$ADB_TARGET" shell settings put secure accessibility_display_inversion_enabled 0
adb -s "$ADB_TARGET" shell settings put system screen_off_timeout 120000
adb -s "$ADB_TARGET" shell svc power stayon false
adb -s "$ADB_TARGET" shell settings put global heads_up_notifications_enabled 1
```

1. Kein Ton: `kokoro doctor` ausführen, oder `PLEKTRA_QA_VOICE=say` (Mac) bzw. `espeak` (Linux) setzen.
2. Die Stimme spricht ein Zeichen falsch: dem Versuch eine `speak`-Zeile geben.
3. Gesprochene Sätze liegen im Cache `~/.cache/plektra-qa/voice/`. Den Ordner löschen, damit sie neu erzeugt werden.

## 10. Am Harness entwickeln

```bash
cd qa && uv run --python 3.14 pytest tests -q
```

Die Tests nutzen Attrappen und brauchen kein Telefon.

| Datei | Aufgabe |
| --- | --- |
| `plektra_qa/hitl_runner.py` | Suite laden, Versuchsschleife, Bewertung, Erinnerungen. |
| `plektra_qa/screen_gate.py` | Invertierte Farben, Benachrichtigungsleiste, Wachbleiben, Stopp-Benachrichtigung und der Wächter auf dem Telefon. Nur adb. |
| `plektra_qa/speech.py` | Die Sprach-Backends und der Satz-Cache. |
| `plektra_qa/recording.py` | Optionale Bildschirm- und Sprachaufnahme (scrcpy, ffmpeg), die Tonspur und die Kapitel. |
| `plektra_qa/inputlog.py` | Liest `getevent` des Telefons und macht daraus gehaltene Tasten sowie Tipp-, Langdruck- und Wisch-Text. |
| `plektra_qa/overlay.py` | Der Balken über dem Video (Pillow) und die Kapiteldateien. |
| `plektra_qa/keys.py` | Tasten-Token, der Keymap (live oder Profil), der Renderer und die Prüfung. |
| `plektra_qa/controller.py` | Activities öffnen, Felder fokussieren und leeren, Text lesen. |
| `plektra_qa/connection.py` | Die per Zertifikat authentifizierte FIRERPA-Verbindung. |
| `plektra_qa/mcp_server.py` / `cli.py` | Der MCP-Server und der Befehl `plektra-qa`. |
| `suites/` | Versuchs-Suiten. |
| `keymaps/` | Tastenprofile: Tasten ab Werk plus Hersteller- und Plektra-Remapping. |
| [titan2-elite-facts.md](titan2-elite-facts.md) (englisch) | Fakten zum Testtelefon: Tastencodes, Einstellungen, Speicherpfade. |

## 11. Erfahrungen mit dem Titan 2 Elite

1. Dieses Android-Build hat kein `cmd notification cancel`. Der Harness entfernt eine Benachrichtigung per Snooze und hebt den Snooze vor dem nächsten Posten auf. Ein gesnoozter Tag bleibt auch beim erneuten Posten verborgen.
2. `adb shell` teilt einen mehrwortigen Benachrichtigungstitel in Tag und Text, außer der ganze Befehl ist ein einziges gequotetes Argument.
3. `svc power stayon true` liest unter Android 16 als `15` zurück (Netzteil, USB, kabellos, Dock), nicht als `7`. Jeder Wert ungleich null zählt.
4. Ein Overlay-Fenster taugt nicht als „Du bist dran“-Signal: Es braucht eine Installation, kann den Tastaturfokus nehmen, und ab Android 12 blockiert Android Berührungen durch das Overlay. Die Benachrichtigungsleiste ist die vertrauenswürdige Oberfläche.
5. Leere Pinnote-Felder melden ihren Hinweistext (`Title`, `Start writing…`) als Inhalt. Der Harness behandelt einen Hinweistext als leer.
6. `adb shell uiautomator dump` und jedes Werkzeug darauf schlägt fehl, solange FIRERPA läuft. Android erlaubt nur einen UiAutomation-Client zugleich, und FIRERPA hält ihn; der Dump gibt dann `Killed` und kein XML aus. Solange FIRERPA läuft, `inspect_ui_fast` benutzen; für einen einfachen `uiautomator dump` FIRERPA zuerst beenden.
7. Geprüfte Alternativen (24.11.2026), keine übernommen. [Mobile MCP](https://github.com/mobile-next/mobile-mcp) 1.0.19 (Apache-2.0): Beide Modi lesen den Bildschirm mit `uiautomator dump` und scheitern neben FIRERPA genauso; der Standardmodus legt zudem `mobilecli.dex` in `/data/local/tmp` ab und kann einen DeviceKit-Agenten auf dem Telefon installieren. Sein Texteingabe-Werkzeug würde außerdem die getestete Tastatur umgehen. [Vercel UI SDK](https://github.com/vercel-labs/ui): nur Web (React, Vue, Svelte, Solid, Web Components), kein Android. [Mobilerun](https://github.com/droidrun/mobilerun) (MIT): ein LLM-Agenten-Framework, das seine Schritte selbst plant, kein skriptgesteuerter Tipp-Harness. [uiautomator2](https://github.com/openatx/uiautomator2) wurde nicht ausprobiert: Es braucht denselben einzigen UiAutomation-Platz wie FIRERPA und bietet nichts, was FIRERPA fehlt. Bei beendetem FIRERPA konnten beide Mobile-MCP-Modi den Bildschirm auflisten (etwa 4 s im Legacy-Modus, etwa 12 s im Standardmodus; ein einfacher `uiautomator dump` lieferte 59 Knoten); das einzige Hindernis ist also der einzige UiAutomation-Platz.
8. Nach dem Beenden von FIRERPA kann `just start` mit `FIRERPA UIAutomation helpers did not start` scheitern, obwohl der Server läuft. Unter Android 16 findet die Hilfsprozess-Prüfung des Lifecycle-Controllers nie etwas, weil `/proc/PID/maps` den geladenen Treiber `[anon:dalvik-classes.dex extracted in memory from @aab.zip]` nennt und nicht den Dateipfad. Den Wechsel von Hand abschließen: `overrides/aab.zip.patched` über das aktive `aab.zip` kopieren (temporäre Datei, dann `mv -f`), nur die `lamda`-Prozesse beenden, deren maps `@aab.zip` enthalten, und auf ihr Neustarten warten. Danach besteht `just status`.
