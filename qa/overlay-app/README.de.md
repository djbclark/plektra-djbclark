# HITL button bar

[English](README.md)

Die Begleit-App für Plektra-QA zeigt Anweisungen und Schaltflächen in einer verschiebbaren, halbtransparenten Leiste. Ziehe die Anweisung, um die Leiste zu verschieben. Mit **−** wird sie zur QA-Blase. Tippe auf die Blase, um die Leiste zu öffnen, oder ziehe sie auf das **×** unten, um sie zu schließen. Das Eingabefeld behält den Fokus. Die App enthält auch einen Prüfstand zum Tippen. Beides zeigt die [Harness-Demo](../README.de.md#schnellstart-harness-demo).

**Dies ist ein Testwerkzeug für Entwicklergeräte.** Es zeichnet über anderen Apps und nimmt Broadcasts von adb an. Nur auf einem Testgerät installieren.

- Paket `dev.hitlbar.overlay`, Name "HITL button bar", Android 11 oder neuer (minSdk 30).
- Einzige Berechtigung: über anderen Apps einblenden (`SYSTEM_ALERT_WINDOW`). Kein Netzwerk, kein Speicher, `allowBackup=false`.

## Installieren

Der Bau läuft in der CI (der Workflow `djb-overlay-app.yml` auf dem Branch `ci/base` des Forks lädt die APK als Artefakt hoch; ein Release-Lauf hängt sie an ein GitHub-Release namens `overlay-app-<UTC-Zeit>-<sha8>`). Dann:

```bash
adb install -r hitl-button-bar-<sha8>.apk
adb shell appops set dev.hitlbar.overlay SYSTEM_ALERT_WINDOW allow   # oder: App öffnen und deren Schaltfläche nutzen
```

Ein Build vor Version 0.2.0 hat keine Schaltfläche **Redo**, beschriftet **◀ Prev** als **Redo last** und zeigt von sich aus keine Wartekarte. Der Prüfstand braucht Version 0.3.0 (Versionscode 3). Einen aktuellen Build installieren.

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

## Prüfstand

`dev.hitlbar.overlay/.BenchActivity` ist ein Bildschirm zum Testen einer Tastatur (öffnen über die Schaltfläche **Open the test bench** der App oder wie unten). Er funktioniert mit jeder Tastatur und speichert nichts. **Alles, was dort getippt wird, auch im Passwortfeld, wird ins Log geschrieben: keine echten Geheimnisse eintippen.** Von keinem anderen Bildschirm und keiner anderen App wird etwas protokolliert.

- **Neun Felder** in einem Raster 3 mal 3; Tab geht von links nach rechts und von oben nach unten und vom letzten zurück zum ersten (Umschalt+Tab geht zurück). Das Raster liegt unterhalb der Schaltflächenleiste.
- **Kopfzeilen** unter den Feldern: der zu tippende Text (wenn der Host einen mitgegeben hat) und die seit dem letzten Zurücksetzen gedrückten Tasten, dann die verwendete Tastatur mit Version, dann das fokussierte Feld mit `inputType` und `imeOptions`.
- **Anzeige** unten: die letzten Ereignisse, neueste zuerst. Sie nimmt nie den Fokus, und die Felder bewegen sich nicht, wenn sie sich ändert.
- Er verarbeitet Konfigurationsänderungen selbst, sodass das An- oder Abmelden einer Tastatur ihn nicht neu startet.

| Nr. | Feld | Ressourcen-ID | Art |
| --- | --- | --- | --- |
| 1 | Body | `probe_body` | mehrzeilig, Großschreibung am Satzanfang |
| 2 | Title | `probe_title` | einzeilig, Großschreibung am Satzanfang |
| 3 | Plain | `probe_plain` | einfache einzelne Zeile |
| 4 | Email | `probe_email` | E-Mail-Adresse |
| 5 | URL | `probe_url` | Webadresse |
| 6 | Number | `probe_number` | Zahl |
| 7 | Password | `probe_password` | Passwort (als Punkte angezeigt, im Klartext protokolliert) |
| 8 | Search | `probe_search` | Suchaktion; Enter behält den Fokus |
| 9 | No suggestions | `probe_nosuggest` | ohne Vorschläge |

### Zurücksetzen per Intent

Jeder Start, auch bei schon geöffnetem Bildschirm, leert alle Felder (oder füllt sie wie verlangt), fokussiert eines, startet die Eingabeverbindung neu und protokolliert danach eine `reset`-Zeile. Alle Extras sind optionale Zeichenketten. Starten können den Bildschirm nur der adb-shell-Benutzer und die App selbst (er verlangt die Berechtigung `DUMP`).

```bash
adb shell am start -n dev.hitlbar.overlay/.BenchActivity \
  --es qa.trial t1 --es qa.focus title --es qa.expect beta --es qa.text.body "ein Text"
```

| Extra | Bedeutung |
| --- | --- |
| `qa.trial` | Eine Kennung, die in der `reset`-Zeile wiederholt wird. |
| `qa.focus` | Das zu fokussierende Feld: `body`, `title`, `plain`, `email`, `url`, `number`, `password`, `search` oder `nosuggest` (Vorgabe `body`). |
| `qa.expect` | Der Text, den die Person tippen soll; wird oben angezeigt und protokolliert. |
| `qa.text.<Feld>` | Der Anfangstext dieses Feldes. |

### Logzeilen (`QAPROBE`)

Je Ereignis eine `Log.i`-Zeile mit dem Tag `QAPROBE` (`adb logcat -s QAPROBE:I`). Die Meldung ist ein JSON-Objekt: `v` (Formatversion, 1), `seq` (zählt hoch, solange der Prozess lebt; eine Lücke bedeutet eine verlorene Zeile), `t` (Laufzeit seit dem Start in ms), `kind`, dann die Felder dieser Art. Zeichenketten werden bei 200 Zeichen abgeschnitten (`"cut":true`; `text` behält das Ende, `len` die volle Länge), die Texte einer `reset`-Zeile bei 40.

| `kind` | Felder | Bedeutung |
| --- | --- | --- |
| `reset` | `trial`, `field`, `expect`, `texts` | Ein Zurücksetzen ist fertig; `field` hat den Fokus. |
| `key` | `stage`, `action`, `name`, `code`, `scan`, `meta`, `repeat`, `dev`, `devName`, `ch`, `flags`, `evt`, `down`, `field`, `pre` | Eine Taste. `stage` `pre`: bevor die Tastatur-App sie sah. `stage` `post`: danach, auf dem Weg zur App; `pre` false heißt dann, dass sie vor der Tastatur-App nie gesehen wurde, also von ihr gesendet wurde. `ch` ist das Zeichen, das die Tastenbelegung des Systems dafür angibt. |
| `shortcut` | wie `key` | Eine als Tastenkürzel angebotene Taste. |
| `conn` | `field`, `conn`, `inputType`, `imeOptions`, `selStart`, `selEnd` | Die Tastatur-App hat eine neue Eingabeverbindung angefordert. |
| `ic` | `field`, `call`, `conn` und die Argumente (`text`, `pos`, `start`, `end`, `before`, `after`, `action`, `name`, `code`, `meta`, `ch`, `id`) | Ein Aufruf der Tastatur-App auf der `InputConnection`: `commitText`, `setComposingText`, `setComposingRegion`, `finishComposingText`, `replaceText`, `commitCompletion`, `commitCorrection`, `commitContent`, `deleteSurroundingText`(`InCodePoints`), `sendKeyEvent`, `setSelection`, `performEditorAction`, `performContextMenuAction`, `performPrivateCommand`, `clearMetaKeyStates`, `beginBatchEdit`, `endBatchEdit`, `closeConnection`. Protokolliert, bevor der Aufruf wirkt. Lesezugriffe werden nicht protokolliert. |
| `text` | `field`, `cause`, `by`, `start`, `removed`, `inserted`, `text`, `len`, `cut` | Ein Feld hat sich geändert. `cause`: `ic` (Aufruf der Tastatur-App), `key` (eine von der App verarbeitete Taste), `reset` oder `app`; `by`: die `seq` dieses Aufrufs oder dieser Taste. |
| `sel` | `field`, `start`, `end` | Cursor oder Auswahl haben sich bewegt. |
| `focus` | `field`, `gained` | Ein Feld hat den Fokus bekommen oder verloren. |
| `action` | `field`, `action`, `fromKey` | Eine Eingabeaktion hat ein Feld erreicht. |
| `motion` | `event` (`touch` oder `generic`: Trackpad, Maus, Scrollen), `source`, `dev`, `x`, `y`, `dx`, `dy`, `samples`, `pointers`, `scrollX`, `scrollY`, `ms`, `end` | Eine Bewegung, am Ende zusammengefasst (`up`, `cancel` oder `quiet` nach 150 ms ohne Messpunkt). |
| `ime` | `id`, `version`, `code` | Die verwendete Tastatur-App (bei Änderung protokolliert). |
| `config` | `keyboard`, `keyboardHidden`, `hardKeyboardHidden`, `navigation`, `orientation`, `widthDp`, `heightDp`, `uiMode` | Die Konfiguration hat sich geändert. |
| `life` | `state` (`create`, `start`, `resume`, `pause`, `stop`, `destroy`, `new-intent`, `window-focus`, `window-blur`), `restored` | Lebenszyklus des Bildschirms. |

Was die Zeilen nicht zeigen können: Eine Taste, die vor der App abgefangen wird (Tastenfilter eines Bedienungshilfedienstes, Systemkürzel), hinterlässt gar keine Zeile.

## Aufbau

- Ein im Manifest eingetragener Empfänger und ein prozessweiter Fensterhalter. Kein Vordergrunddienst: Solange ein Overlay-Fenster sichtbar ist, hält das System den Prozess am Leben; ohne Leiste gibt es nichts zu halten.
- Fenster: `TYPE_APPLICATION_OVERLAY`, Flags `NOT_FOCUSABLE | NOT_TOUCH_MODAL | LAYOUT_IN_SCREEN`, oben, volle Breite, um die Höhe der Statusleiste nach unten versetzt.
- Der reine Protokollcode steht in `Protocol.kt` und `ProbeProtocol.kt`, getestet durch `ProtocolTest.kt` und `ProbeProtocolTest.kt`. Der Prüfstand ist `BenchActivity.kt` mit `ProbeViews.kt`.
- Erreicht ein Absender den Empfänger nicht, im `adb logcat` nach "Permission Denial ... android.permission.DUMP" suchen: Gibt ein Gerät dem Shell-Benutzer diese Berechtigung nicht, muss das Attribut `android:permission` am Empfänger in `AndroidManifest.xml` entfernt werden.
