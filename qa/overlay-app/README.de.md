# HITL button bar

[English](README.md)

Eine winzige Android-Overlay-App für Testläufe mit Mensch im Ablauf. Sie zeichnet eine schmale Leiste am oberen Bildschirmrand (unter der Statusleiste) mit einem Anweisungstext und den Schaltflächen **Done**, **Next** und **Stop**. Sie nimmt nie den Fokus: Tastatur und die getestete App behalten den Eingabefokus, und der Tippbereich bleibt frei. Sie enthält keinen projektspezifischen Code.

**Dies ist ein Testwerkzeug für Entwicklergeräte.** Es zeichnet über anderen Apps und nimmt Broadcasts von adb an. Nur auf einem Testgerät installieren.

- Paket `dev.hitlbar.overlay`, Name "HITL button bar", Android 11 oder neuer (minSdk 30).
- Einzige Berechtigung: über anderen Apps einblenden (`SYSTEM_ALERT_WINDOW`). Kein Netzwerk, kein Speicher, `allowBackup=false`.

## Installieren

Der Bau läuft in der CI (der Workflow `djb-overlay-app.yml` auf dem Branch `ci/base` des Forks lädt die APK als Artefakt hoch; ein Release-Lauf hängt sie an ein GitHub-Release namens `overlay-app-<UTC-Zeit>-<sha8>`). Dann:

```bash
adb install -r hitl-button-bar-<sha8>.apk
adb shell appops set dev.hitlbar.overlay SYSTEM_ALERT_WINDOW allow   # oder: App öffnen und deren Schaltfläche nutzen
```

## Protokoll

Host an App (nur der adb-shell-Benutzer: der Empfänger verlangt die Berechtigung `DUMP`, die keine normale App hat):

```bash
adb shell am broadcast -a dev.hitlbar.overlay.SHOW -p dev.hitlbar.overlay --es text "Tippe: hallo" --es buttons done,next,stop
adb shell am broadcast -a dev.hitlbar.overlay.HIDE -p dev.hitlbar.overlay
```

`buttons` nennt die anzuzeigenden Schaltflächen (immer in der Reihenfolge Done, Next, Stop; fehlt das Extra, erscheinen alle drei). `text` wird bei 200 Zeichen abgeschnitten.

App an Host: `Log.i`-Zeilen mit dem Tag `QAOVERLAY` (lesen mit `adb logcat -s QAOVERLAY:I`):

| Zeile | Bedeutung |
| --- | --- |
| `QAOVERLAY shown seq=N` | Die Leiste ist auf dem Bildschirm (Antwort auf SHOW). |
| `QAOVERLAY hidden seq=N` | Die Leiste ist weg (Antwort auf HIDE, auch wenn keine da war). |
| `QAOVERLAY button=done seq=N` | Die Person hat Done gedrückt (ebenso `next`, `stop`). |
| `QAOVERLAY error=no-permission seq=N` | SHOW kam ohne die Berechtigung zum Einblenden. |
| `QAOVERLAY error=window-failed seq=N` | Das System hat das Fenster abgelehnt. |

`seq` zählt je Zeile hoch, solange der Prozess lebt (nach einem Neustart des Prozesses durch Android beginnt es wieder bei 1). Der Anweisungstext wird nie protokolliert.

## Aufbau

- Ein im Manifest eingetragener Empfänger und ein prozessweiter Fensterhalter. Kein Vordergrunddienst: Solange ein Overlay-Fenster sichtbar ist, hält das System den Prozess am Leben; ohne Leiste gibt es nichts zu halten.
- Fenster: `TYPE_APPLICATION_OVERLAY`, Flags `NOT_FOCUSABLE | NOT_TOUCH_MODAL | LAYOUT_IN_SCREEN`, oben, volle Breite, um die Höhe der Statusleiste nach unten versetzt.
- Der reine Protokollcode steht in `Protocol.kt` und ist getestet (`ProtocolTest.kt`).
- Erreicht ein Absender den Empfänger nicht, im `adb logcat` nach "Permission Denial ... android.permission.DUMP" suchen: Gibt ein Gerät dem Shell-Benutzer diese Berechtigung nicht, muss das Attribut `android:permission` am Empfänger in `AndroidManifest.xml` entfernt werden.
