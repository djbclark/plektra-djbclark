[English](README.md) · Deutsch

# Plektra-QA-Harness: ein Mensch tippt, der Computer schaut zu

Dieser Ordner testet Plektra auf einem echten Telefon mit echter Hardware-Tastatur. Ein Mensch tippt auf der Tastatur des Telefons. Der Computer bereitet jedes Textfeld vor, sagt, was getippt werden soll, liest, was angekommen ist, und bewertet es (bestanden oder nicht, Zeichen-Diff, Wörter pro Minute).

Der Computer tippt nie für dich. Plektras Aufgabe ist es, physische Tastendrücke in Text zu verwandeln, also testen nur echte Tastendrücke diese Aufgabe. Der Computer steuert den Bildschirm über [FIRERPA](https://github.com/firerpa/lamda) (lamda) per adb und rührt die Eingabemethode nie an.

Stand: nur im Fork, nicht Teil von Upstream-Plektra. Erprobt auf einem Unihertz Titan 2 Elite (Android 16, ohne Root). Jedes Android-Telefon, auf dem FIRERPA 10.12 über adb läuft, sollte funktionieren.

[Einrichtung mit einem KI-Agenten (Englisch)](SETUP.md) · [Demo-Video (2:03, MP4)](media/harness-demo.mp4) · [Screenshots](README.md#see-it-in-action)

## Schnellstart: Harness-Demo

Nach der [Einrichtung](#3-einmalige-einrichtung) und Installation der Overlay-App (Version 0.4.1 oder neuer):

```bash
cd qa
just qa-connect
just qa-demo
```

Die [Demo](suites/harness_demo.yaml) verwendet nur den eingebauten Prüfstand und Beispieldaten. Sie zeigt die verschiebbare Leiste, die Blase, **Redo**, den Wechsel der Tastaturansicht, den Eingabefokus und den Abschluss. Die vollständige Aufnahme soll etwa zwei bis sechs Minuten dauern.

Starte am Handy mit **Done** für eine Aufnahme oder **No rec** ohne Aufnahme. In T2 probierst du **Redo** einmal aus. Tippe auf der Schlusskarte erneut auf **Done**. Das Terminal zeigt den Pfad zur vollständigen MP4-Datei und zum Zusammenschnitt der bestandenen Versuche. Prüfe in der vollständigen Datei Bild, Ton, Kapitel, Tasten- und Berührungsspur sowie mögliche private Inhalte. Die [englische Anleitung](README.md#check-the-video) enthält einen `ffprobe`-Befehl zur Prüfung.

Die Sprachausgabe läuft auf dem Host-Computer. Schalte andere Ansagen für den Durchlauf stumm, wenn sie sich mit der Demo überschneiden. In Paseo Herald gilt **Mute here** für das aktuelle Gerät.

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
12. [Der Prüfstand](#12-der-prüfstand)

## 1. Wie ein Durchlauf aussieht und klingt

| Du siehst oder hörst | Bedeutung | Du tust |
| --- | --- | --- |
| Invertierte Farben, Benachrichtigung `Hands off` | Der Computer benutzt den Bildschirm (öffnet die App, leert das Feld). | Das Telefon nicht berühren. |
| Die Leiste zeigt `Wait a few seconds...` ohne Schaltflächen | Der Computer bereitet den nächsten Schritt vor. | Auf die nächste Karte warten. |
| Normale Farben. Die Benachrichtigungsleiste zeigt 4 Sekunden lang die Aktion, schließt sich, dann piept der Computer und spricht die Aktion aus. | Du bist dran. | Diese Aktion auf der Tastatur des Telefons tippen. |
| `Still waiting.` und noch einmal die Aktion | 10 ruhige Sekunden lang kam nichts im Feld an. | Anfangen zu tippen. Nach 3 Erinnerungen hält der Durchlauf an, weil er annimmt, dass du weg bist. |
| `Done. No more batches: the phone may be used normally.` (Karte am Ende eines Durchlaufs, Abschnitt 4) | Der Durchlauf ist vorbei, eine Aufnahme ist beendet, die Einstellungen des Telefons sind wiederhergestellt. | Das Telefon normal benutzen. Die Tabelle am Computer lesen. |
| `Wait. Another batch is coming: please leave the phone for now.` (Karte am Ende eines Durchlaufs, Abschnitt 4) | Dieser Durchlauf ist vorbei, ein weiterer folgt. | Das Telefon in Ruhe lassen, bis dessen Startkarte erscheint. |
| Die Benachrichtigung `Swipe away to stop testing` | Während eines Durchlaufs immer da. | Nur zum Beenden wegwischen. Das Telefon stellt dann seine Einstellungen selbst wieder her, auch wenn der Computer die Verbindung verloren hat. |

Die Sprachansagen und die Texte auf dem Telefon sind englisch.

Der Harness hält den Bildschirm während eines Durchlaufs an. Am Ende (normal, nach Strg-C oder nach dem Wegwischen) stellt er Farbinvertierung, Bildschirm-Timeout, Wachbleiben und Heads-up-Benachrichtigungen wieder her.

## 2. Was du brauchst

1. Einen Mac oder Linux-Rechner mit [`uv`](https://docs.astral.sh/uv/), [`just`](https://just.systems/) und `adb` (Android Platform-Tools). `uv` lädt Python 3.14 selbst herunter.
2. Ein Android-Telefon mit Hardware-Tastatur, Entwickleroptionen und USB-Debugging, auf dem Plektra installiert und als Tastatur gewählt ist.
3. FIRERPA 10.12 auf dem Telefon, als Benutzer `shell` mit einem Client-Zertifikat (`lamda.pem`) gestartet. Kein Root, keine APK.
4. Optional eine Stimme. [Kokoro](https://huggingface.co/hexgrad/Kokoro-82M) über [kokoro-cli](https://pypi.org/project/kokoro-cli/) klingt am besten und läuft offline. Ohne Kokoro nutzt der Harness `say` unter macOS, `spd-say` / `espeak-ng` unter Linux, oder bleibt stumm.
5. Eine Notiz-App mit einfachem Textfeld. Die Beispiel-Suite nutzt Pinnote (`branislav667.pinnote`).
6. `scrcpy` und `ffmpeg` auf dem Computer, weil Durchläufe standardmäßig aufgenommen werden (macOS: `brew install scrcpy ffmpeg`). Um ohne sie zu laufen, die Aufnahme abschalten (Abschnitt 4).

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
   # PLEKTRA_QA_PACKAGE=rocks.pkb.plektra.djb  # App-ID des aktiven Debug-Builds, falls sie abweicht
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

### Einen Durchlauf zum Teilen aufnehmen (standardmäßig an)

```bash
cd qa && just qa-run suites/smoke_chords.yaml              # mit Aufnahme
cd qa && just qa-run suites/smoke_chords.yaml --no-record  # ohne Aufnahme
```

Jeder Durchlauf wird aufgenommen, solange du es nicht abschaltest: `--record` ist der Standard, `--no-record` schaltet die Aufnahme ab (über MCP ist `record=true` der Standard; `record=false` übergeben). Die Startkarte sagt, dass der Durchlauf aufgenommen wird, und bietet den Start ohne Aufnahme an: **No rec** auf der Leiste drücken oder `norec` im Terminal tippen. **Done** oder `go` startet den Durchlauf mit Aufnahme. Auf der Leiste lautet die Karte `Recorded: screen, voice, keys. Done starts. No rec starts without recording.`

Ein aufgenommener Durchlauf speichert ein mp4 in `~/plektra-qa-recordings/` (`--record-dir` oder `PLEKTRA_QA_RECORD_DIR` ändern den Ort). Nötig sind `scrcpy`, `ffmpeg` und das Telefon an adb (`ADB_TARGET`); ohne `scrcpy` und `ffmpeg` startet `run` nur mit `--no-record`. Das steckt drin:

- Der Bildschirm des Telefons (eine reine Ansichtskopie; scrcpy läuft mit `--no-control`) und die gesprochenen Ansagen des Computers, zu den Zeitpunkten ihres Sprechens in die Tonspur gelegt (die Stimme wird nicht von den Lautsprechern abgenommen).
- Ein Balken über dem Bildschirm: links ein laufender Timecode; rechts die gerade gehaltenen Tasten (`L-Shift + A`, Kernel-Namen wie `ENTER`, Steuerkreuz-Tasten eingeschlossen) und die Berührungen (`TOUCH (x, y)`, dann `TAP`, `LONG PRESS` oder `SWIPE up (x1, y1) to (x2, y2)`; das Trackpad der Tastatur trägt die Marke `PAD`). Die Punkte der Android-Funktion „Fingertipps anzeigen“ sind während der Aufnahme an und werden danach zurückgesetzt.
- Ein Log-Feld rechts neben dem Telefon: was das Terminal zeigt, im Takt mit dem Bild (Versuchsaufgaben, gesprochene Zeilen, Bestanden oder Fehlgeschlagen sowie Tasten und Berührungen, wenn sie passieren). Dieselben Zeilen sind auch eine Untertitelspur „Terminal log“ im mp4, die ein Player ausschalten kann. `--no-panel` lässt beides weg (nur CLI).
- Kapitel, eines je Versuch (der nächste Satz Tastendrücke): im mp4 und als `plektra-qa-*.chapters.txt` zum Einfügen in eine YouTube-Beschreibung (YouTube braucht 0:00 zuerst und Kapitel von mindestens 10 s; kürzere werden dort zusammengelegt).

Tasten und Berührungen kommen aus `getevent` des Telefons; der Gleichlauf mit dem Bild stimmt auf etwa eine Zehntelsekunde. Die Aufnahme zeigt den ganzen Bildschirm, die Statusleiste und jede Taste, die die Person drückt; sie wird nur mit deren Einverständnis geteilt. Nicht für Privates verwenden: dafür die Aufnahme abschalten.

### Das Ende eines Durchlaufs: Ist das Telefon frei?

Jeder von dir gestartete Durchlauf endet mit einer von zwei Meldungen, egal wie er endete (fertig, gestoppt oder nach deinem Start fehlgeschlagen):

| Meldung | Wann |
| --- | --- |
| `Done. No more batches: the phone may be used normally.` (fertig, keine weiteren Durchläufe, das Telefon darf normal benutzt werden) | Der Standard. Außerdem immer, wenn du **Stop** drückst oder die Stopp-Benachrichtigung wegwischst. |
| `Wait. Another batch is coming: please leave the phone for now.` (warten, ein weiterer Durchlauf kommt, das Telefon vorerst liegen lassen) | Der Durchlauf lief mit `--more-coming` (über MCP `more_coming=true`) und du hast ihn nicht gestoppt. |

Die Meldung kommt, nachdem das Telefon nicht mehr gefilmt wird und seine Einstellungen wiederhergestellt sind; der Computer schreibt dann vielleicht noch das Video. Sie steht 10 Sekunden lang ohne Schaltflächen auf der Leiste, Berührungen gehen also hindurch (ohne Leisten-App öffnet sich stattdessen 10 Sekunden lang die Benachrichtigungsleiste); sie bleibt dort als Benachrichtigung (Tag `plektra-qa-state`); und sie wird ausgesprochen. Sie ersetzt das gesprochene `Testing finished.` früherer Versionen.

Sobald der nächste Durchlauf seine Startkarte zeigt, ändert sich die alte Benachrichtigung in `A test batch is starting. Follow the cards on the screen.` Ein Durchlauf, der nie gestartet wird, setzt die Benachrichtigung ohne Ton wieder auf **Done** oder **Wait** (nach seinem eigenen `--more-coming`).

Läuft der angekündigte Durchlauf doch nicht, gib das Telefon frei. Das sendet die **Done**-Meldung:

```bash
cd qa && just qa-run suites/smoke_chords.yaml --more-coming   # ein weiterer Durchlauf folgt: „Wait“
cd qa && just qa-release                                      # er läuft doch nicht: „Done“
```

`just qa-release` ist `plektra-qa release`; ein Agent ruft das MCP-Werkzeug `release_phone` auf. Es wird verweigert, solange ein Lauf das Telefon steuert, und endet mit Exit-Code 1, wenn sich die Benachrichtigung nicht posten ließ. Ein Lauf, der schon vor seiner Startkarte abgelehnt wird (fehlerhafte Suite, kein Rekorder, gesperrtes Telefon), ändert eine frühere **Wait**-Karte nicht; gib das Telefon frei, wenn du ihn aufgibst.

### Gemerkte Erfolge, ein Durchlauf pro Telefon, Abbrechen

- **Ledger.** Ein Versuch, der mit dem installierten Plektra-Build bestanden wurde, läuft nicht noch einmal (`run --redo` führt alles aus). Jeder Erfolg wird in `~/.local/state/plektra-qa/ledger/<Suite>.json` geschrieben, sobald der Versuch besteht; ein Kill, Strg-C oder Absturz verliert nichts. In einem aufgenommenen Durchlauf wird der Clip danach angehängt; ein aufgenommener Erfolg, dessen Clip sich nicht schneiden lässt, zählt nicht. **◀ Prev** auf der Leiste (siehe unten) nimmt einen Erfolg wieder zurück, ebenso **Redo**, wo es einen früheren Versuch wiederholt. Eine Phase wird nur übersprungen, wenn alle ihre Versuche bestanden sind; sonst laufen die offenen Versuche, der erste mit dem Setup der Phase (App, Activity, Klick und das Startfeld; ein Versuch, der kein Feld nennt, liest weiterhin das Feld mit dem Fokus) und, bei einem Versuch, der das Feld behält (`clear_field: false`), auch der Versuch davor. Versuchsnummern (Log, Tabelle, Kapitel, `import --passed`) sind die Nummern in der Suite. Der Schlüssel hasht den Prompt wie im YAML geschrieben; ältere Einträge werden weiter gefunden. Der Lauf bricht ab, wenn der Keyboard-Build nicht lesbar ist oder nicht Plektra die aktive Tastatur ist. Am Ende steht „still to pass“ und getrennt „passed, no clip“.
- **Ein Durchlauf pro Telefon.** `~/.local/state/plektra-qa/locks/<Seriennummer>.lock` ist eine Kernel-Dateisperre auf `getprop ro.serialno`; LAN, Tailscale und USB teilen sie. Sie enthält die PID und die Kind-PIDs (scrcpy, adb, afplay); der nächste Lauf beendet Reste eines abgebrochenen Laufs. MCP-Werkzeuge, die den Bildschirm steuern, nehmen dieselbe Sperre.
- **Abbrechen.** SIGTERM und SIGHUP wirken wie Strg-C: das Telefon wird wiederhergestellt, die Aufnahme beendet, bisherige Erfolge bleiben (Exit-Code 130).
- **Vorprüfung vor „go“.** Entsperr-Prüfung, Stopp-Karte und Vordergrund-Prüfung laufen, bevor du zum Start aufgefordert wirst. Die Aufnahme beginnt erst nach deinem Start, und gar nicht, wenn du mit **No rec** oder `norec` startest.
- **Optionale Schlüssel pro Versuch:** `expect_ci_first: true` ignoriert die Groß-/Kleinschreibung des ersten Buchstabens (für Versuche, die die Autogroßschreibung nicht testen); `fresh: true` öffnet immer zuerst eine neue Notiz (der eine Autocap-Versuch).

### Schaltflächenleiste (Standard; `--no-bar` nimmt die Benachrichtigungskarte)

`just qa-run SUITE` (wie `--bar`) zeigt eine kleine Leiste auf dem Bildschirm, statt die Benachrichtigungsleiste herunterzuziehen: eine halbtransparente Anweisung mit einer Reihe kleiner halbtransparenter Schaltflächen darunter (40 dp hoch, jede ein Fünftel der Leiste breit, jede in eigener Farbe und immer am selben Platz). Ein passender Text beendet einen Versuch nicht; du beendest ihn mit **Done**, kannst also vorher einen Tippfehler korrigieren. Der Durchlauf startet nur mit **Done** der Leiste (oder **No rec**, ohne Aufnahme, auf der Startkarte eines aufgenommenen Durchlaufs) oder einem getippten `go` (oder `norec`), nie mit einer Taste oder Berührung. Mit `--no-bar` startet nur ein Tastendruck (keine Berührung) oder das getippte Wort den Durchlauf, und ein Versuch ohne `timeout_s` endet nach 20 s ohne Taste, sobald getippt wurde.

Ziehe die Anweisung, um die Leiste zu verschieben. Mit **−** verkleinerst du sie zu einer Sprechblase. Ziehe die Blase an eine andere Stelle oder tippe auf **QA**, um die Leiste wieder zu öffnen. Ziehe die Blase auf das **×** am unteren Bildschirmrand, um sie zu schließen. Wenn ein Versuch läuft, beendet das den Durchlauf. Die Leiste bleibt bei neuen Anweisungen an ihrer Position.

| Schaltfläche | Farbe | Wo | Wirkung |
| --- | --- | --- | --- |
| **Done** | grün | jeder Versuch, Startkarte, Schlusskarte | Beendet den Versuch: das Feld wird gelesen und bewertet. |
| **Redo** | violett | jeder Versuch, Karte „Reset failed“ | Verwirft diesen Anlauf und führt denselben Versuch vom Ausgangszustand an noch einmal aus. |
| **◀ Prev** | blau | jeder Versuch nach dem ersten, Schlusskarte | Geht zum vorigen Versuch zurück und führt ihn erneut aus. |
| **Next** | grau | jeder Versuch, Karte „Reset failed“ | Überspringt den Versuch (wird als übersprungen vermerkt). |
| **Stop** | rot | jeder Versuch, Karte „Reset failed“ | Beendet den Durchlauf. |
| **No rec** | braun | Startkarte eines aufgenommenen Durchlaufs | Startet ohne Aufnahme. |

- **Redo** (dieser Versuch) vermerkt für den verworfenen Anlauf kein Ergebnis, stellt den Ausgangszustand her und führt den Versuch erneut aus. In einem aufgenommenen Durchlauf bleibt der verworfene Anlauf im Video, im Log-Feld als nicht zählend markiert; aus ihm wird kein Clip geschnitten.
- **◀ Prev** nimmt das Ergebnis des vorigen Versuchs zurück (auch einen schon im Ledger stehenden Erfolg), verwirft, was im laufenden Versuch getippt wurde, stellt den Ausgangszustand des vorigen Versuchs her und führt ihn erneut aus.
- **Ausgangszustand.** Das stellt ein Wiederholen wieder her:
  1. App und Bildschirm werden wie beim Start geöffnet. In einer Phase mit „Neue Notiz“-Schaltfläche (`click_resource_id`) bekommt ein Versuch, der sein Feld leert, eine **neue Notiz**; nichts, was irgendwo in der alten getippt wurde, bleibt übrig. Sonst wird das Feld des Versuchs geleert, ebenso jedes andere Textfeld auf dem Bildschirm, das sich während des Durchlaufs geändert hat (etwa der Titel, zu dem Tab gesprungen ist).
  2. Der Fokus kehrt in das Feld zurück, in dem der Versuch begonnen hat (auch bei einem Versuch, der kein Feld nennt).
  3. Die Zwischenablage bekommt den Inhalt zurück, den sie zu Beginn des Versuchs hatte (ein Versuch mit `verify_mode: clipboard` beginnt mit leerer Zwischenablage).
  4. Ein Versuch, der den Text behält (`clear_field: false`) und auf einen anderen Versuch seiner Phase folgt: **beide laufen erneut**, denn der Computer tippt nie. Der Durchlauf geht zu dem Versuch zurück, der das Feld füllt, und die Ergebnisse ab dort werden zurückgenommen. Gibt es keinen früheren Versuch zum Wiederholen, muss das Feld noch so lauten wie beim Start; nur durch Leeren lässt sich das herstellen.
- **Karte „Reset failed“.** Lässt sich der Ausgangszustand nicht herstellen, zeigt die Leiste `Reset failed: …. Fix the screen by hand, then Redo. Next skips this trial.` mit **Redo**, **Next** und **Stop**.
- **Auch ein normaler Versuchsstart** räumt alten Text weg. In einer Phase mit „Neue Notiz“-Schaltfläche bekommt ein Versuch, der sein Feld leert, eine neue Notiz, wenn er der erste seiner Phase im Durchlauf ist, wenn ein anderes Feld verändert zurückgeblieben ist, oder mit `fresh: true`. Sonst werden veränderte Felder nach Möglichkeit geleert.
- **Wartekarte.** Ein Tipp auf irgendeine Schaltfläche macht die Karte sofort zu `Wait a few seconds...` ohne Schaltflächen, sodass keine zweimal gedrückt werden kann. Sie bleibt bis zur nächsten Karte, lässt Berührungen zur App dahinter durch und erscheint auch, wenn ein Versuch ohne Tipp endet. Die Leiste wird erst nach der Karte am Ende des Durchlaufs (oben) ausgeblendet, oder wenn der Durchlauf vor seinem Start abgebrochen wird.
- **Schlusskarte.** Nach dem letzten Versuch zeigt die Leiste `All trials done. ◀ Prev redoes the last one. Done finishes.` mit **Done** und **◀ Prev**, bis zu 60 s lang. **Done** oder Stille beendet den Durchlauf. Ein gestoppter Durchlauf, oder einer, bei dem nie getippt wurde, endet ohne diese Karte. Die Karte am Ende des Durchlaufs (oben) folgt in jedem Fall.
- **Ein Feld, das sich nicht lesen lässt** (zum Beispiel während der Fokus wandert), beendet mit der Leiste keinen Versuch; das Terminal meldet `field unreadable (…), still waiting`. Ist das Feld beim Druck auf **Done** noch immer unlesbar, schlägt der Versuch mit `field lost` fehl; **◀ Prev** auf der nächsten Karte wiederholt ihn, oder du drückst **Redo** vor **Done**. Ohne Leiste schlägt ein Versuch mit `field lost` fehl, wenn das Feld 10 s lang unlesbar war (sofort, wenn es schon beim Start des Versuchs unlesbar ist).

Die Leiste ist eine Begleit-App (`--bar-package`, Standard `dev.hitlbar.overlay`; sie braucht die Berechtigung „über anderen Apps einblenden“). Der Host steuert sie per `am broadcast -a <package>.SHOW|HIDE` und liest die Antworten aus Logcat-Zeilen mit dem Tag `QAOVERLAY` (`shown`, `hidden`, `button=done|norec|retry|redo|next|stop`; `retry` ist **Redo**, `redo` ist **◀ Prev**). Meldet die Leiste nicht innerhalb von 5 s `shown`, blendet der Host sie aus und versucht es noch einmal (Android kann die untätige App neu gestartet haben), danach schlägt der Versuch mit einer klaren Fehlermeldung fehl. Jede App kann in Logcat schreiben; der Host vertraut deshalb nur Zeilen der eigenen UID der Leisten-App (gelesen mit `pm list packages -U --show-versioncode`, was auch die App-Version liefert; ohne installierte App startet er nicht), nur Zeilen ab der aktuellen Uhrzeit des Telefons und nur Zeilen, deren `seq` höher ist als jedes zuvor angenommene (nach einem Hide darf der Zähler wieder bei 1 beginnen, wenn Android die App neu gestartet hat). Die App liegt in `qa/overlay-app/` (siehe deren README); die Host-Seite steht in `plektra_qa/button_bar.py`.

**Installieren** (einmal je Telefon, und erneut nach Änderungen an der App; nur auf einem Entwicklergerät, denn die App zeichnet über anderen Apps und nimmt Broadcasts von adb an): die APK auf GitHub Actions bauen (Workflow `djb-overlay-app.yml` auf `ci/base`, Start mit `scripts/github-build-overlay-app --release plk/main`), `hitl-button-bar-<sha8>.apk` aus dem Release `overlay-app-<UTC-Zeit>-<sha8>` laden, dann:

```bash
adb install -r hitl-button-bar-<sha8>.apk
adb shell appops set dev.hitlbar.overlay SYSTEM_ALERT_WINDOW allow   # oder: Einstellungen, Apps, HITL button bar, Über anderen Apps einblenden
```

**Eine ältere App** (vor Version 0.2.0) funktioniert mit diesem Harness weiter, mit weniger Umfang: Es gibt keine Schaltfläche **Redo** (die Karte „Reset failed“ hat dann nur **Next** und **Stop**), **◀ Prev** heißt **Redo last**, die Wartekarte erscheint erst, wenn der Computer sie schickt, und sie blockiert Berührungen auf dem Streifen, den sie verdeckt. Die aktuelle App installieren.

**Rauchtest** (ein Mensch mit dem Telefon in der Hand): In einem Terminal `adb logcat -s QAOVERLAY:I` laufen lassen, in einem zweiten:

```bash
adb shell am broadcast -a dev.hitlbar.overlay.SHOW -p dev.hitlbar.overlay --es text "Rauchtest: Done druecken" --es buttons done,next,stop
adb shell am broadcast -a dev.hitlbar.overlay.HIDE -p dev.hitlbar.overlay
```

Erwartet: Nach dem ersten Befehl erscheint die Leiste oben unter der Statusleiste, die Tastatur eines offenen Textfelds bleibt oben, und Logcat zeigt `QAOVERLAY shown seq=1`. Done drücken: `QAOVERLAY button=done seq=2` (Next und Stop ergeben `button=next` und `button=stop`). Nach dem zweiten Befehl ist die Leiste weg, und Logcat zeigt `QAOVERLAY hidden seq=3`. Ohne die Berechtigung erscheint nach dem ersten Befehl stattdessen `QAOVERLAY error=no-permission seq=1`, und es erscheint nichts.

## 5. Ergebnisse lesen

Jeder Versuch ist eine Zeile: Aufforderung, erwarteter Text, Inhalt des Felds, bestanden oder nicht, Genauigkeit, Wörter pro Minute und Abstand zum vorigen Versuch. Ein fehlgeschlagener Durchlauf endet mit Code 1 und gibt pro fehlgeschlagenem Versuch eine `fail`-Zeile aus. Versuche auf dem Prüfstand melden außerdem die gedrückten Tasten und wer eine Abweichung verursacht hat; ein Tippfehler der Person wird getrennt gezählt (Abschnitt 12).

Mögliche Fehler:

| Fehler | Bedeutung |
| --- | --- |
| `stopped from the phone` | Die Stopp-Benachrichtigung war zu sehen und wurde dann weggewischt. Spätere Versuche starten nicht. |
| `never started after the spoken prompts` | Nach der Aktion und allen Erinnerungen wurde nichts getippt. Spätere Versuche starten nicht. |
| `stop card did not appear` | Das Telefon hat die Stopp-Benachrichtigung nie gelistet. Ein Fehler des Harness, nicht dein Wischen. |
| `click target not found: …` / `input field not found` | Die App war nicht auf dem erwarteten Bildschirm. `package`, `activity` und `resource_id` der Suite prüfen. |
| `could not leave the open note: …` | Zurück hat die alte Notiz vor der neuen nicht geschlossen. Von Hand schließen, dann **Redo**. |
| `field not restored: …` / `the start field needs the text …` | Ein Wiederholen konnte ein Feld nicht zurücksetzen (der Computer leert nur, er tippt nie). Von Hand richten, dann **Redo**, oder **Next**. |
| `Unlock the phone once. The harness will not enter a PIN.` | Der Sperrbildschirm ist aktiv. Entsperren und erneut starten. |

Die Tippzeit (und die WPM) läuft von der ersten Taste bis zum Treffer. Ein langsamer Start senkt die WPM also nicht.

## 6. Eine eigene Suite schreiben

Eine Suite ist YAML. Einstellungen oben gelten für jeden Versuch; eine Phase oder ein Versuch kann sie überschreiben:

```yaml
name: my_suite
# timeout_s: 60      # optional: Sekunden Ruhe bis ein Versuch endet; ohne Angabe wartet ein Versuch ohne Limit
sentinel: "\n"         # ein abschließendes Enter zählt auch als fertig
start_wait_s: 10       # ruhige Sekunden, bevor die Aktion wiederholt wird
# max_reprompts: 3   # optional; ohne Angabe wird die Aktion wiederholt, bis die Person anfängt
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

Alle Felder eines Versuchs: `prompt`, `expect`, `expect_ci_first`, `verify_mode` (`text` oder `clipboard`), `sentinel`, `timeout_s`, `shade`, `speak`, `start_wait_s`, `max_reprompts`, `package`, `activity`, `resource_id`, `text_hint`, `start_resource_id`, `click_resource_id`, `clear_field`, `fresh`, `cue_on_device` (das Telefon piept auch) und für den Prüfstand `probe_field` und `prefill` (Abschnitt 12).

`resource_id` ist das Feld, das ein Versuch liest. `start_resource_id` ist das Feld, das zu Beginn eines Versuchs fokussiert (und geleert) wird, wenn der Versuch keines nennt: Ein solcher Versuch liest das Feld, das gerade den Fokus hat, funktioniert also auch, nachdem eine Taste wie Tab ihn verschoben hat. `suites/tab_first_letter.yaml` ist ein Beispiel: drei Anläufe Tab und dann Tippen auf dem Prüfstand (Abschnitt 12).

**Höchstens dreimal dieselbe Aktion.** Ein Mensch wird nicht immer wieder um dasselbe gebeten (djbclark, 2026-11-25, im Wortlaut: „10 times is too many times to have a human do the same thing unless we really think that is neccisary. Limit to 3 max on a regular basis.“). Eine Suite, die dieselbe Aktion mehr als dreimal verlangt, wird abgelehnt, bevor irgendetwas das Telefon berührt. Dieselbe Aktion ist dieselbe Anweisung auf dem Telefon (`shade`, sonst `prompt`) oder dieselbe gesprochene Anweisung (`speak`, sonst diese) mit demselben `expect`. Eine Nummerierung wie `try 2 of 10` macht daraus keine neue Aktion, eine andere Phase auch nicht.

Streiche die Wiederholungen, oder lass ein Skript das Timing steuern. Nur wenn mehr Anläufe wirklich nötig sind, begründe das oben in der Suite mit höchstens 80 Zeichen. Die Startkarte zeigt dann `Repeats on purpose: …` auf dem Telefon und im Terminal:

```yaml
repeat_reason: "a timing race that hits about one try in five"
```

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
| `request_human_chord_batch` | Versuche (eine JSON-Liste von Versuchsobjekten) mit tippendem Menschen ausführen und den Bericht zurückgeben. Blockiert, bis der Mensch fertig ist. Der Durchlauf wird standardmäßig aufgenommen (`record=true`, Abschnitt 4); vorher dem Menschen sagen und `record=false` übergeben, wenn er ablehnt. Dieselbe Aktion höchstens dreimal, außer `repeat_reason` begründet es (Abschnitt 6). `more_coming=true` sagt dem Menschen am Ende, dass er auf einen weiteren Durchlauf warten soll (Abschnitt 4). |
| `release_phone` | Dem Menschen sagen, dass keine Durchläufe mehr kommen und das Telefon normal benutzt werden darf (Abschnitt 4). |

Regeln für Agenten:

1. Einen Durchlauf nur starten, wenn der Mensch darum gebeten hat und das Telefon hält. Vorher sagen, was er sehen und hören wird.
2. Nie für den Menschen tippen (`set_text` tippt über FIRERPA, nicht über die Tastatur).
3. Nie die aktive Eingabemethode ändern und das Plektra-Paket nie mit `am force-stop` oder `am start -S` behandeln. Android würde die Tastatur wechseln.
4. Keine unbekannten Dialoge anklicken, nicht Zurück drücken, „Nicht stören“ nicht ändern.
5. Tasten in Versuchslisten und in Fragen an den Menschen als Token schreiben (`<Ctrl+A>`): `just qa-keys "<Ctrl+A>"` gibt den Text in beiden Fassungen zum Einfügen aus (Abschnitt 7).
6. Die Aufnahme (Abschnitt 4) ist standardmäßig an. Vor jedem Durchlauf der Person sagen, dass Bildschirm, Stimme und jede Taste aufgenommen werden, und anbieten, die Aufnahme abzuschalten; lehnt sie ab, `--no-record` (CLI) oder `record=false` (`request_human_chord_batch`) übergeben. Mit `human_said_go=true` gibt es keinen Start am Telefon, deshalb die Aufnahme vorher im Chat klären. Die Antwort gilt nur für diesen Durchlauf. Sagen, wo die Datei liegt.
7. Ein Durchlauf mit Menschen startet nie von allein. Jeder neue Durchlauf zeigt seine Anweisung auf dem Telefon (auf der Schaltflächenleiste, ohne sie in der Benachrichtigungsleiste), gibt sie im Terminal aus und spricht sie, und wartet dann auf den Start der Person: **Done** auf der Leiste (ohne Leiste ein Tastendruck auf dem Telefon; eine Berührung startet nie) oder `go` im Terminal getippt. Ein aufgenommener Durchlauf lässt sich auch ohne Aufnahme starten: **No rec** auf der Leiste oder `norec` im Terminal. Die Startanweisung wird mit 1,5-facher Geschwindigkeit vorgelesen, und niemand muss sie abwarten: Der Start durch die Person bricht die Stimme sofort ab. Auch **Done**, **Next** oder **Stop** während eines Versuchs sowie **Done** oder **◀ Prev** auf der Abschlusskarte brechen den gesprochenen Satz ab. Eine Zeitüberschreitung beendet den Lauf mit `refused: not started`; sie startet ihn nie. Die Aufnahme beginnt erst nach dem Start. Hat die Person im Chat gerade „go“ gesagt, `human_said_go=true` übergeben; Karte und Stimme laufen trotzdem zuerst. Kein Lauf, keine Overlay-Prüfung und kein Smoke-Skript umgeht dieses Tor (`plektra_qa/start_gate.py`).
8. Bevor die Tastatur beschuldigt wird, einen Fehler in einer **anderen App** wiederholen, um einen Fehler der App selbst auszuschließen (djbclark, 2026-11-25, im Wortlaut: „In cases like this we should always try again in a seperate app to make sure we are not running into an app bug.“). Bis dahin nur als in dieser einen App beobachtet melden. `suites/tab_first_letter.yaml` wiederholt einen nach Tab verlorenen ersten Buchstaben auf dem Prüfstand (Abschnitt 12). Der Prüfstand zeigt als Messgerät, wohin ein Buchstabe ging; er ersetzt nicht die Wiederholung in einer echten App. Ein Ergebnis mit `fault: typo` ist ein Tippfehler der Person: nie als Fehler der Tastatur melden.
9. Einen Menschen in einem Durchlauf höchstens dreimal um dieselbe Aktion bitten (Abschnitt 6). `repeat_reason` ist für den seltenen Fall, der wirklich mehr braucht, und kein Weg an der Prüfung vorbei.
10. Dem Menschen die Wahrheit darüber sagen, was als Nächstes kommt: `--more-coming` (CLI) oder `more_coming=true` nur übergeben, wenn direkt danach wirklich ein weiterer Durchlauf startet, und `just qa-release` ausführen oder `release_phone` aufrufen, wenn er dann doch ausfällt.
11. Jeden Durchlauf auf etwa 10 Minuten zusammenhängende menschliche Arbeit auslegen: die offenen Prüfungen in einer Suite sammeln, statt für kurze Läufe mehrmals um einen Start zu bitten (djbclark, 2026-11-25). Regel 9 begrenzt weiterhin die Wiederholungen derselben Aktion. Einen neuen Schritt möglichst vorher ohne den Menschen prüfen, damit ein Entwurfsfehler nicht den Durchlauf verschwendet.

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
| `plektra_qa/probe.py` | Der Prüfstand: Zurücksetzen per Intent, seine Logzeilen, wer die Abweichung verursacht hat, wohin ein Buchstabe ging. |
| `plektra_qa/start_gate.py` | Das Starttor: Karte in der Benachrichtigungsleiste, Terminaltext und Stimme, dann Warten auf den Start der Person: **Done** oder **No rec** auf der Leiste (ohne Leiste ein Tastendruck) oder getipptes `go` bzw. `norec`. |
| `plektra_qa/end_state.py` | Das Ende eines Durchlaufs: die Karte „Telefon ist frei“ oder „ein weiterer Durchlauf kommt“ (Leiste, Benachrichtigung, Stimme) und `release`. |
| `plektra_qa/screen_gate.py` | Invertierte Farben, Benachrichtigungsleiste, Wachbleiben, Stopp-Benachrichtigung und der Wächter auf dem Telefon. Nur adb. |
| `plektra_qa/speech.py` | Die Sprach-Backends und der Satz-Cache. |
| `plektra_qa/recording.py` | Bildschirm- und Sprachaufnahme, standardmäßig an (scrcpy, ffmpeg), die Tonspur und die Kapitel. |
| `plektra_qa/inputlog.py` | Liest `getevent` des Telefons und macht daraus gehaltene Tasten sowie Tipp-, Langdruck- und Wisch-Text. |
| `plektra_qa/overlay.py` | Der Balken über dem Video (Pillow) und die Kapiteldateien. |
| `plektra_qa/keys.py` | Tasten-Token, der Keymap (live oder Profil), der Renderer und die Prüfung. |
| `plektra_qa/controller.py` | Activities öffnen, Felder fokussieren und leeren, Text lesen. |
| `plektra_qa/connection.py` | Die per Zertifikat authentifizierte FIRERPA-Verbindung. |
| `plektra_qa/mcp_server.py` / `cli.py` | Der MCP-Server und der Befehl `plektra-qa`. |
| `suites/` | Versuchs-Suiten. |
| `overlay-app/` | Die Android-App: Schaltflächenleiste und Prüfstand. |
| `keymaps/` | Tastenprofile: Tasten ab Werk plus Hersteller- und Plektra-Remapping. |
| [titan2-elite-facts.md](titan2-elite-facts.md) (englisch) | Fakten zum Testtelefon: Tastencodes, Einstellungen, Speicherpfade. |

## 11. Erfahrungen mit dem Titan 2 Elite

1. Dieses Android-Build hat kein `cmd notification cancel`. Der Harness entfernt eine Benachrichtigung per Snooze und hebt den Snooze vor dem nächsten Posten auf. Ein gesnoozter Tag bleibt auch beim erneuten Posten verborgen. Die Karte am Ende des Durchlaufs (`plektra-qa-state`) wird deshalb vom Harness nie gesnoozt: Der nächste Durchlauf ersetzt sie, indem er denselben Tag neu postet.
2. `adb shell` teilt einen mehrwortigen Benachrichtigungstitel in Tag und Text, außer der ganze Befehl ist ein einziges gequotetes Argument.
3. `svc power stayon true` liest unter Android 16 als `15` zurück (Netzteil, USB, kabellos, Dock), nicht als `7`. Jeder Wert ungleich null zählt.
4. Ein Overlay-Fenster taugt nicht als „Du bist dran“-Signal: Es braucht eine Installation, kann den Tastaturfokus nehmen, und ab Android 12 blockiert Android Berührungen durch das Overlay. Die Benachrichtigungsleiste ist die vertrauenswürdige Oberfläche.
5. Leere Pinnote-Felder melden ihren Hinweistext (`Title`, `Start writing…`) als Inhalt. Der Harness behandelt einen Hinweistext als leer.
6. `adb shell uiautomator dump` und jedes Werkzeug darauf schlägt fehl, solange FIRERPA läuft. Android erlaubt nur einen UiAutomation-Client zugleich, und FIRERPA hält ihn; der Dump gibt dann `Killed` und kein XML aus. Solange FIRERPA läuft, `inspect_ui_fast` benutzen; für einen einfachen `uiautomator dump` FIRERPA zuerst beenden.
7. Geprüfte Alternativen (24.11.2026), keine übernommen. [Mobile MCP](https://github.com/mobile-next/mobile-mcp) 1.0.19 (Apache-2.0): Beide Modi lesen den Bildschirm mit `uiautomator dump` und scheitern neben FIRERPA genauso; der Standardmodus legt zudem `mobilecli.dex` in `/data/local/tmp` ab und kann einen DeviceKit-Agenten auf dem Telefon installieren. Sein Texteingabe-Werkzeug würde außerdem die getestete Tastatur umgehen. [Vercel UI SDK](https://github.com/vercel-labs/ui): nur Web (React, Vue, Svelte, Solid, Web Components), kein Android. [Mobilerun](https://github.com/droidrun/mobilerun) (MIT): ein LLM-Agenten-Framework, das seine Schritte selbst plant, kein skriptgesteuerter Tipp-Harness. [uiautomator2](https://github.com/openatx/uiautomator2) wurde nicht ausprobiert: Es braucht denselben einzigen UiAutomation-Platz wie FIRERPA und bietet nichts, was FIRERPA fehlt. Bei beendetem FIRERPA konnten beide Mobile-MCP-Modi den Bildschirm auflisten (etwa 4 s im Legacy-Modus, etwa 12 s im Standardmodus; ein einfacher `uiautomator dump` lieferte 59 Knoten); das einzige Hindernis ist also der einzige UiAutomation-Platz.
8. Nach dem Beenden von FIRERPA kann `just start` mit `FIRERPA UIAutomation helpers did not start` scheitern, obwohl der Server läuft. Unter Android 16 findet die Hilfsprozess-Prüfung des Lifecycle-Controllers nie etwas, weil `/proc/PID/maps` den geladenen Treiber `[anon:dalvik-classes.dex extracted in memory from @aab.zip]` nennt und nicht den Dateipfad. Den Wechsel von Hand abschließen: `overrides/aab.zip.patched` über das aktive `aab.zip` kopieren (temporäre Datei, dann `mv -f`), nur die `lamda`-Prozesse beenden, deren maps `@aab.zip` enthalten, und auf ihr Neustarten warten. Danach besteht `just status`.

## 12. Der Prüfstand

Die Leisten-App (ab Version 0.3.0, [overlay-app/README.de.md](overlay-app/README.de.md)) hat einen Testbildschirm, `dev.hitlbar.overlay.BenchActivity`: neun Felder zum Durchtabben (body, title, plain, email, url, number, password, search, nosuggest), Kopfzeilen mit der aktuellen Tastatur-App und den gedrückten Tasten und eine laufende Anzeige aller Ereignisse. Er funktioniert mit jeder Tastatur. Was dort getippt wird, auch Passwörter, landet im Log: keine echten Geheimnisse eintippen.

**In einer Suite.** Ein Versuch auf dem Prüfstand setzt ihn per Intent zurück, ohne den Bildschirm zu berühren; ein **Redo** beginnt deshalb genau wie der erste Anlauf:

```yaml
phases:
  - name: bench_example
    package: dev.hitlbar.overlay
    activity: dev.hitlbar.overlay.BenchActivity
    start_resource_id: dev.hitlbar.overlay:id/probe_body   # das Feld mit Fokus zu Beginn
    probe_field: title                                     # das Feld, um das es in den Versuchen geht
    prefill: {title: "al"}                                 # optional: Anfangstext je Feld
    trials:
      - prompt: "<Tab>, then type pha"
        expect: "alpha"                                    # der ganze Text des Feldes am Ende
```

`probe_field` und `prefill` verwenden die Feldnamen `body`, `title`, `plain`, `email`, `url`, `number`, `password`, `search`, `nosuggest`. Ohne `probe_field` geht es im Bericht um das am Ende fokussierte Feld. Ein Versuch mit `clear_field: false` behält, was in den Feldern steht. Das Harness liest ein Passwortfeld nur als Punkte, ein Passwort-Versuch kann also nicht bestehen; sein Bericht zeigt trotzdem den echten Text.

**Was jeder Anlauf meldet** (Terminal, `probe` im Ergebnis und eine Datei je Anlauf, auch für wiederholte, in `probe/<Suite>-<Zeit>/trial-NN-attempt-M.jsonl` unter dem Aufnahmeordner; die letzte Zeile einer Datei ist das Urteil, darüber stehen die Zeilen des Prüfstands):

```text
trial 3 typing: expected 'beta' | box 'eta' | keys ⇥ b e t a | missing 'b' at 1 | KEYBOARD OR APP
trial 3 why: the right keys were pressed, the text differs: changed or lost after the keys
trial 3 layer: 'b' reached the app (seen before the keyboard) and the keyboard sent nothing for it
```

**Wer die Abweichung verursacht hat** (`fault` eines nicht bestandenen Versuchs). Bestanden oder nicht bleibt das rohe Ergebnis.

| `fault` | Wird nur gesagt, wenn |
| --- | --- |
| `typo`: ein Tippfehler der Person, **kein Fehler der Tastatur** | Das Feld genau das zeigt, was die gedrückten Tasten ergeben, und das nicht der erwartete Text ist: eine andere, eine zusätzliche oder eine vertauschte Taste. |
| `keyboard-or-app` | Die gedrückten Tasten genau den erwarteten Text ergeben und das Feld abweicht: nach den Tasten geändert oder verloren (Autokorrektur, automatische Großschreibung, ein verlorener Buchstabe). |
| `unknown` | In allen anderen Fällen, zum Beispiel: für einen Buchstaben kam keine Taste in der App an (nicht gedrückt oder vor der App verloren); Alt, Sym, Strg oder Fn, eine gehaltene Taste oder eine Cursortaste wurde benutzt; Zeichen wurden getippt, während ein anderes Feld den Fokus hatte; nur die Groß-/Kleinschreibung weicht ab und Umschalt wurde benutzt; dieselbe Taste zweimal innerhalb von 40 ms (vielleicht eine prellende Taste); Logzeilen fehlen; der Versuch wurde übersprungen. |

Die Tasten werden nach der Tastenbelegung des Systems gelesen. Eine Tastaturbelegung, die Tasten eigen zuordnet (QWERTZ auf QWERTY-Tastenkappen, Zeichen durch langes Drücken), endet deshalb in `unknown` und nicht in einer Schuldzuweisung. **Korrekturen** (jede Folge von Rücktaste, was sie entfernt hat und was danach getippt wurde) werden auch aufgeführt, wenn der Text am Ende stimmt, ebenso ein bestandener Versuch, bei dem die Tasten etwas anderes ergeben (die Tastatur hat korrigiert).

**Wie weit ein fehlender Buchstabe kam** (`layer`): Er kam nie in der App an; er kam an und die Tastatur-App sendete nichts oder etwas anderes; die Tastatur-App sendete ihn und die App hat ihn nicht übernommen; die Tastatur-App reichte die Taste weiter und die App verlor ihn; er stand im Feld und die Tastatur-App, eine Taste oder die App hat ihn entfernt; er landete in einem anderen Feld; oder `unknown`.

**Der Prüfstand ist ein Messgerät, kein Beweis.** Er ist eine einzige App. Ein vermuteter Tastaturfehler wird weiterhin in einer echten zweiten App wiederholt (Abschnitt 8, Regel 8).
