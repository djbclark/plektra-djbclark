# HITL button bar

[Deutsch](README.de.md)

A tiny Android overlay app for human-in-the-loop test harnesses. It draws a compact bar at the top of the screen (below the status bar): an instruction text across the full width and one thin row of small buttons under it, **Done**, **No rec** or **Redo**, **◀ Prev**, **Next** and **Stop**. It never takes focus, so the keyboard and the app under test keep the input focus. The harness's own logic stays on the computer; the one extra screen here is a test bench for typing (below).

**This is a test tool for developer devices.** It draws over other apps and accepts broadcasts from adb. Install it only on a device you test with.

- Package `dev.hitlbar.overlay`, label "HITL button bar", Android 11 or newer (minSdk 30).
- Only permission: draw over other apps (`SYSTEM_ALERT_WINDOW`). No network, no storage, `allowBackup=false`.

## Install

Build on CI (the workflow `djb-overlay-app.yml` on the fork's `ci/base` branch uploads the APK as an artifact; a release run attaches it to a GitHub release named `overlay-app-<UTC time>-<sha8>`). Then:

```bash
adb install -r hitl-button-bar-<sha8>.apk
adb shell appops set dev.hitlbar.overlay SYSTEM_ALERT_WINDOW allow   # or: open the app and use its button
```

A build before version 0.2.0 has no **Redo** button, labels **◀ Prev** as **Redo last** and shows no wait card of its own. The test bench needs version 0.3.0 (version code 3). Install a current one.

## Protocol

Host to app (adb shell user only: the receiver requires the `DUMP` permission, which no normal app holds):

```bash
adb shell am broadcast -a dev.hitlbar.overlay.SHOW -p dev.hitlbar.overlay --es text "Type: hello" --es buttons done,next,stop
adb shell am broadcast -a dev.hitlbar.overlay.HIDE -p dev.hitlbar.overlay
```

`buttons` names the buttons to show, as a comma-separated list of the wire names below. Each button has its own place in the row and its own color, whatever the order they are named in. A missing extra shows Done, Next, Stop. An empty extra shows no buttons: only the text, and touches pass through to the app behind. Unknown names are dropped. `text` is cut at 200 characters.

| Button | Wire name | Place | Color |
| --- | --- | --- | --- |
| **Done** | `done` | 1 | green `#2E7D32` |
| **No rec** | `norec` | 2 | brown `#6D4C41` |
| **Redo** | `retry` | 2 | purple `#7B1FA2` |
| **◀ Prev** | `redo` | 3 | blue `#1565C0` |
| **Next** | `next` | 4 | gray `#455A64` |
| **Stop** | `stop` | 5 | red `#C62828` |

Buttons are 40 dp tall and a fifth of the bar wide, with 6 dp between them. Should two buttons that share a place be named together, the row holds just the named buttons.

**Wait card.** A tap logs the press, then at once replaces the card with `Wait a few seconds...` and no buttons, so it cannot be pressed twice, until the next SHOW or HIDE. A card with no buttons lets touches through (`FLAG_NOT_TOUCHABLE`, opacity 0.8, the most Android 12+ allows for that).

App to host: `Log.i` lines with tag `QAOVERLAY` (read with `adb logcat -s QAOVERLAY:I`):

| Line | Meaning |
| --- | --- |
| `QAOVERLAY shown seq=N` | The bar is laid out on screen (answers SHOW). |
| `QAOVERLAY hidden seq=N` | The bar is gone (answers HIDE, also when none was showing). |
| `QAOVERLAY button=done seq=N` | The person pressed Done (also `norec`, `retry`, `redo`, `next`, `stop`). |
| `QAOVERLAY error=no-permission seq=N` | SHOW arrived without the draw-over-apps permission. |
| `QAOVERLAY error=window-failed seq=N` | The system refused the window. |

`seq` counts up per line while the process lives (it starts at 1 again if Android restarts the process, which the host accepts only right after a HIDE). The host reads only lines written by this app's uid (`logcat -v uid`) and ignores any seq that is not higher than the last one. The buttons ignore touches while another window covers them (`filterTouchesWhenObscured`). The instruction text is never logged.

## Test bench

`dev.hitlbar.overlay/.BenchActivity` is a screen for testing a keyboard (open it with the app's **Open the test bench** button, or as below). It works with any keyboard and saves nothing. **Everything typed on it, the password box included, is written to the log: type no real secrets.** Nothing is logged from any other screen or app.

- **Nine boxes** in a 3 by 3 grid; Tab goes left to right, top to bottom, and from the last back to the first (Shift+Tab goes back). The grid stays clear of the button bar.
- **Header** below the boxes: the text to type (when the host gave one) and the keys pressed since the last reset, then the keyboard in use and its version, then the focused box with its `inputType` and `imeOptions`.
- **Panel** at the bottom: the last events, newest first. It never takes the focus, and the boxes never move when it changes.
- It handles configuration changes itself, so a keyboard attaching or detaching does not restart it.

| # | Box | Resource id | Kind |
| --- | --- | --- | --- |
| 1 | Body | `probe_body` | multi-line, sentence auto-caps |
| 2 | Title | `probe_title` | single line, sentence auto-caps |
| 3 | Plain | `probe_plain` | plain single line |
| 4 | Email | `probe_email` | email address |
| 5 | URL | `probe_url` | web address |
| 6 | Number | `probe_number` | number |
| 7 | Password | `probe_password` | password (shown as dots, logged in clear) |
| 8 | Search | `probe_search` | search action; Enter keeps the focus |
| 9 | No suggestions | `probe_nosuggest` | no suggestions |

### Reset by intent

Every start, also while the screen is already open, empties all boxes (or fills them as asked), focuses one, restarts the input connection and logs a `reset` line when that is done. All extras are optional strings. Only the adb shell user and the app itself can start the screen (it requires the `DUMP` permission).

```bash
adb shell am start -n dev.hitlbar.overlay/.BenchActivity \
  --es qa.trial t1 --es qa.focus title --es qa.expect beta --es qa.text.body "some text"
```

| Extra | Meaning |
| --- | --- |
| `qa.trial` | A tag repeated in the `reset` line. |
| `qa.focus` | The box to focus: `body`, `title`, `plain`, `email`, `url`, `number`, `password`, `search` or `nosuggest` (default `body`). |
| `qa.expect` | The text the person is asked to type; shown in the header and logged. |
| `qa.text.<box>` | The starting text of that box. |

### Log lines (`QAPROBE`)

One `Log.i` line per event with tag `QAPROBE` (`adb logcat -s QAPROBE:I`). The message is one JSON object: `v` (format version, 1), `seq` (counts up while the process lives, so a gap means a lost line), `t` (uptime in ms), `kind`, then the fields of that kind. Strings are cut at 200 characters (`"cut":true`; `text` keeps the end and `len` the full length); the texts in a `reset` line at 40.

| `kind` | Fields | Meaning |
| --- | --- | --- |
| `reset` | `trial`, `field`, `expect`, `texts` | A reset is done; `field` has the focus. |
| `key` | `stage`, `action`, `name`, `code`, `scan`, `meta`, `repeat`, `dev`, `devName`, `ch`, `flags`, `evt`, `down`, `field`, `pre` | A key. `stage` `pre`: before the keyboard saw it. `stage` `post`: after it, reaching the app; `pre` false then means it was never seen before the keyboard, so the keyboard sent it. `ch` is what the system's key map says it types. |
| `shortcut` | as `key` | A key offered as a shortcut. |
| `conn` | `field`, `conn`, `inputType`, `imeOptions`, `selStart`, `selEnd` | The keyboard asked for a new input connection. |
| `ic` | `field`, `call`, `conn`, and its arguments (`text`, `pos`, `start`, `end`, `before`, `after`, `action`, `name`, `code`, `meta`, `ch`, `id`) | A call by the keyboard on the `InputConnection`: `commitText`, `setComposingText`, `setComposingRegion`, `finishComposingText`, `replaceText`, `commitCompletion`, `commitCorrection`, `commitContent`, `deleteSurroundingText`(`InCodePoints`), `sendKeyEvent`, `setSelection`, `performEditorAction`, `performContextMenuAction`, `performPrivateCommand`, `clearMetaKeyStates`, `beginBatchEdit`, `endBatchEdit`, `closeConnection`. Logged before the call takes effect. Reads are not logged. |
| `text` | `field`, `cause`, `by`, `start`, `removed`, `inserted`, `text`, `len`, `cut` | A box changed. `cause`: `ic` (a keyboard call), `key` (a key the app handled), `reset` or `app`; `by`: the `seq` of that call or key. |
| `sel` | `field`, `start`, `end` | The cursor or selection moved. |
| `focus` | `field`, `gained` | A box gained or lost the focus. |
| `action` | `field`, `action`, `fromKey` | An editor action reached a box. |
| `motion` | `event` (`touch` or `generic`: trackpad, mouse, scroll), `source`, `dev`, `x`, `y`, `dx`, `dy`, `samples`, `pointers`, `scrollX`, `scrollY`, `ms`, `end` | One movement, summed up when it ends (`up`, `cancel`, or `quiet` after 150 ms without a sample). |
| `ime` | `id`, `version`, `code` | The keyboard in use (logged when it changes). |
| `config` | `keyboard`, `keyboardHidden`, `hardKeyboardHidden`, `navigation`, `orientation`, `widthDp`, `heightDp`, `uiMode` | The configuration changed. |
| `life` | `state` (`create`, `start`, `resume`, `pause`, `stop`, `destroy`, `new-intent`, `window-focus`, `window-blur`), `restored` | The screen's lifecycle. |

What the lines cannot show: a key that something takes away before the app (an accessibility service's key filter, a system shortcut) leaves no line at all.

## Design

- A manifest receiver plus one process-wide window holder. No foreground service: while an overlay window is visible the system keeps the process alive, and with no bar there is nothing to keep.
- Window: `TYPE_APPLICATION_OVERLAY`, flags `NOT_FOCUSABLE | NOT_TOUCH_MODAL | LAYOUT_IN_SCREEN`, top gravity, full width, moved down by the status-bar height.
- Pure protocol code is in `Protocol.kt` and `ProbeProtocol.kt`, unit-tested by `ProtocolTest.kt` and `ProbeProtocolTest.kt`. The bench is `BenchActivity.kt` and `ProbeViews.kt`.
- If a sender never reaches the receiver, check `adb logcat` for "Permission Denial ... android.permission.DUMP": a device that does not give the shell user that permission needs the `android:permission` attribute removed from the receiver in `AndroidManifest.xml`.
