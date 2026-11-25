# HITL button bar

[Deutsch](README.de.md)

A tiny Android overlay app for human-in-the-loop test harnesses. It draws a compact bar at the top of the screen (below the status bar): an instruction text across the full width and one thin row of small buttons under it, **Done**, **No rec** or **Redo**, **◀ Prev**, **Next** and **Stop**. It never takes focus, so the keyboard and the app under test keep the input focus. The harness's own logic stays on the computer; the one extra screen here is a plain two-field test screen (below).

**This is a test tool for developer devices.** It draws over other apps and accepts broadcasts from adb. Install it only on a device you test with.

- Package `dev.hitlbar.overlay`, label "HITL button bar", Android 11 or newer (minSdk 30).
- Only permission: draw over other apps (`SYSTEM_ALERT_WINDOW`). No network, no storage, `allowBackup=false`.

## Install

Build on CI (the workflow `djb-overlay-app.yml` on the fork's `ci/base` branch uploads the APK as an artifact; a release run attaches it to a GitHub release named `overlay-app-<UTC time>-<sha8>`). Then:

```bash
adb install -r hitl-button-bar-<sha8>.apk
adb shell appops set dev.hitlbar.overlay SYSTEM_ALERT_WINDOW allow   # or: open the app and use its button
```

A build before version 0.2.0 has no **Redo** button, labels **◀ Prev** as **Redo last**, shows no wait card of its own and has no two-field screen, so install a current one.

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

## Two-field screen

`dev.hitlbar.overlay/.TwoFieldsActivity` shows two empty text fields, ids `probe_title` (upper) and `probe_body` (lower). The cursor starts in the lower one and Tab moves it to the upper one. Every start empties both; nothing is saved. It handles keyboard configuration changes itself, so such a change does not restart it (on some phones a single key press can cause one). It is a second app for repeating a typing failure, to tell a keyboard fault from an app's own bug (suite `qa/suites/tab_first_letter.yaml`).

```bash
adb shell am start -n dev.hitlbar.overlay/.TwoFieldsActivity
```

## Design

- A manifest receiver plus one process-wide window holder. No foreground service: while an overlay window is visible the system keeps the process alive, and with no bar there is nothing to keep.
- Window: `TYPE_APPLICATION_OVERLAY`, flags `NOT_FOCUSABLE | NOT_TOUCH_MODAL | LAYOUT_IN_SCREEN`, top gravity, full width, moved down by the status-bar height.
- Pure protocol code is in `Protocol.kt` and unit-tested (`ProtocolTest.kt`).
- If a sender never reaches the receiver, check `adb logcat` for "Permission Denial ... android.permission.DUMP": a device that does not give the shell user that permission needs the `android:permission` attribute removed from the receiver in `AndroidManifest.xml`.
