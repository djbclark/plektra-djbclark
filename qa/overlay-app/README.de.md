# HITL button bar

[English](README.md)

Eine winzige Android-Overlay-App für Testläufe mit Mensch im Ablauf. Sie zeichnet eine schmale Leiste am oberen Bildschirmrand (unter der Statusleiste): einen Anweisungstext über die ganze Breite und darunter eine schmale Reihe kleiner Schaltflächen, **Done**, **No rec** oder **Redo**, **◀ Prev**, **Next** und **Stop**. Sie nimmt nie den Fokus: Tastatur und die getestete App behalten den Eingabefokus. Die Logik des Testlaufs bleibt auf dem Computer; der einzige zusätzliche Bildschirm hier ist ein schlichter Testbildschirm mit zwei Feldern (siehe unten).

**Dies ist ein Testwerkzeug für Entwicklergeräte.** Es zeichnet über anderen Apps und nimmt Broadcasts von adb an. Nur auf einem Testgerät installieren.

- Paket `dev.hitlbar.overlay`, Name "HITL button bar", Android 11 oder neuer (minSdk 30).
- Einzige Berechtigung: über anderen Apps einblenden (`SYSTEM_ALERT_WINDOW`). Kein Netzwerk, kein Speicher, `allowBackup=false`.

## Installieren

Der Bau läuft in der CI (der Workflow `djb-overlay-app.yml` auf dem Branch `ci/base` des Forks lädt die APK als Artefakt hoch; ein Release-Lauf hängt sie an ein GitHub-Release namens `overlay-app-<UTC-Zeit>-<sha8>`). Dann:

```bash
adb install -r hitl-button-bar-<sha8>.apk
adb shell appops set dev.hitlbar.overlay SYSTEM_ALERT_WINDOW allow   # oder: App öffnen und deren Schaltfläche nutzen
```

Ein Build vor Version 0.2.0 hat keine Schaltfläche **Redo**, beschriftet **◀ Prev** als **Redo last**, zeigt von sich aus keine Wartekarte und hat keinen Zwei-Felder-Bildschirm, also einen aktuellen installieren.

## Protokoll

Host an App (nur der adb-shell-Benutzer: der Empfänger verlangt die Berechtigung `DUMP`, die keine normale App hat):

```bash
adb shell am broadcast -a dev.hitlbar.overlay.SHOW -p dev.hitlbar.overlay --es text "Tippe: hallo" --es buttons done,next,stop
adb shell am broadcast -a dev.hitlbar.overlay.HIDE -p dev.hitlbar.overlay
```

`buttons` nennt die anzuzeigenden Schaltflächen als kommagetrennte Liste der Protokollnamen unten. Jede Schaltfläche hat ihren festen Platz in der Reihe und ihre eigene Farbe, egal in welcher Reihenfolge sie genannt wird. Fehlt das Extra, erscheinen Done, Next, Stop. Ein leeres Extra zeigt keine Schaltflächen: nur den Text, und Berührungen gehen zur App dahinter durch. Unbekannte Namen werden verworfen. `text` wird bei 200 Zeichen abgeschnitten.

| Schaltfläche | Protokollname | Platz | Farbe |
| --- | --- | --- | --- |
| **Done** | `done` | 1 | grün `#2E7D32` |
| **No rec** | `norec` | 2 | braun `#6D4C41` |
| **Redo** | `retry` | 2 | violett `#7B1FA2` |
| **◀ Prev** | `redo` | 3 | blau `#1565C0` |
| **Next** | `next` | 4 | grau `#455A64` |
| **Stop** | `stop` | 5 | rot `#C62828` |

Die Schaltflächen sind 40 dp hoch und ein Fünftel der Leiste breit, mit 6 dp Abstand. Werden zwei Schaltflächen zusammen genannt, die denselben Platz haben, enthält die Reihe nur die genannten Schaltflächen.

**Wartekarte.** Ein Tipp protokolliert den Druck und macht die Karte sofort zu `Wait a few seconds...` ohne Schaltflächen, sodass nicht zweimal gedrückt werden kann, bis zum nächsten SHOW oder HIDE. Eine Karte ohne Schaltflächen lässt Berührungen durch (`FLAG_NOT_TOUCHABLE`, Deckkraft 0,8, das Höchste, was Android 12+ dafür erlaubt).

App an Host: `Log.i`-Zeilen mit dem Tag `QAOVERLAY` (lesen mit `adb logcat -s QAOVERLAY:I`):

| Zeile | Bedeutung |
| --- | --- |
| `QAOVERLAY shown seq=N` | Die Leiste ist auf dem Bildschirm (Antwort auf SHOW). |
| `QAOVERLAY hidden seq=N` | Die Leiste ist weg (Antwort auf HIDE, auch wenn keine da war). |
| `QAOVERLAY button=done seq=N` | Die Person hat Done gedrückt (ebenso `norec`, `retry`, `redo`, `next`, `stop`). |
| `QAOVERLAY error=no-permission seq=N` | SHOW kam ohne die Berechtigung zum Einblenden. |
| `QAOVERLAY error=window-failed seq=N` | Das System hat das Fenster abgelehnt. |

`seq` zählt je Zeile hoch, solange der Prozess lebt (nach einem Neustart des Prozesses durch Android beginnt es wieder bei 1, was der Host nur direkt nach einem HIDE annimmt). Der Host liest nur Zeilen, die die UID dieser App geschrieben hat (`logcat -v uid`), und ignoriert jedes seq, das nicht höher ist als das letzte. Die Schaltflächen ignorieren Berührungen, solange ein anderes Fenster sie verdeckt (`filterTouchesWhenObscured`). Der Anweisungstext wird nie protokolliert.

## Bildschirm mit zwei Feldern

`dev.hitlbar.overlay/.TwoFieldsActivity` zeigt zwei leere Textfelder mit den IDs `probe_title` (oben) und `probe_body` (unten). Der Cursor beginnt im unteren, Tab bringt ihn ins obere. Jeder Start leert beide; nichts wird gespeichert. Änderungen der Tastaturkonfiguration verarbeitet er selbst, sodass sie ihn nicht neu starten (auf manchen Telefonen kann schon ein einzelner Tastendruck eine auslösen). Es ist eine zweite App, um einen Tippfehler zu wiederholen und so einen Fehler der Tastatur von einem Fehler der App zu unterscheiden (Suite `qa/suites/tab_first_letter.yaml`).

```bash
adb shell am start -n dev.hitlbar.overlay/.TwoFieldsActivity
```

## Aufbau

- Ein im Manifest eingetragener Empfänger und ein prozessweiter Fensterhalter. Kein Vordergrunddienst: Solange ein Overlay-Fenster sichtbar ist, hält das System den Prozess am Leben; ohne Leiste gibt es nichts zu halten.
- Fenster: `TYPE_APPLICATION_OVERLAY`, Flags `NOT_FOCUSABLE | NOT_TOUCH_MODAL | LAYOUT_IN_SCREEN`, oben, volle Breite, um die Höhe der Statusleiste nach unten versetzt.
- Der reine Protokollcode steht in `Protocol.kt` und ist getestet (`ProtocolTest.kt`).
- Erreicht ein Absender den Empfänger nicht, im `adb logcat` nach "Permission Denial ... android.permission.DUMP" suchen: Gibt ein Gerät dem Shell-Benutzer diese Berechtigung nicht, muss das Attribut `android:permission` am Empfänger in `AndroidManifest.xml` entfernt werden.
