# HITL button bar

[Deutsch](README.de.md)

A tiny Android overlay app for human-in-the-loop test harnesses. It draws a compact bar at the top of the screen (below the status bar) with an instruction text and the buttons **Done**, **Next** and **Stop**. It never takes focus, so the keyboard and the app under test keep the input focus, and it does not cover the typing area. It has no project-specific code.

**This is a test tool for developer devices.** It draws over other apps and accepts broadcasts from adb. Install it only on a device you test with.

- Package `dev.hitlbar.overlay`, label "HITL button bar", Android 11 or newer (minSdk 30).
- Only permission: draw over other apps (`SYSTEM_ALERT_WINDOW`). No network, no storage, `allowBackup=false`.

## Install

Build on CI (the workflow `djb-overlay-app.yml` on the fork's `ci/base` branch uploads the APK as an artifact; a release run attaches it to a GitHub release named `overlay-app-<UTC time>-<sha8>`). Then:

```bash
adb install -r hitl-button-bar-<sha8>.apk
adb shell appops set dev.hitlbar.overlay SYSTEM_ALERT_WINDOW allow   # or: open the app and use its button
```

## Protocol

Host to app (adb shell user only: the receiver requires the `DUMP` permission, which no normal app holds):

```bash
adb shell am broadcast -a dev.hitlbar.overlay.SHOW -p dev.hitlbar.overlay --es text "Type: hello" --es buttons done,next,stop
adb shell am broadcast -a dev.hitlbar.overlay.HIDE -p dev.hitlbar.overlay
```

`buttons` names the buttons to show (shown in the fixed order Done, Next, Stop; a missing extra shows all three). `text` is cut at 200 characters.

App to host: `Log.i` lines with tag `QAOVERLAY` (read with `adb logcat -s QAOVERLAY:I`):

| Line | Meaning |
| --- | --- |
| `QAOVERLAY shown seq=N` | The bar is laid out on screen (answers SHOW). |
| `QAOVERLAY hidden seq=N` | The bar is gone (answers HIDE, also when none was showing). |
| `QAOVERLAY button=done seq=N` | The person pressed Done (also `next`, `stop`). |
| `QAOVERLAY error=no-permission seq=N` | SHOW arrived without the draw-over-apps permission. |
| `QAOVERLAY error=window-failed seq=N` | The system refused the window. |

`seq` counts up per line while the process lives (it starts at 1 again if Android restarts the process). The instruction text is never logged.

## Design

- A manifest receiver plus one process-wide window holder. No foreground service: while an overlay window is visible the system keeps the process alive, and with no bar there is nothing to keep.
- Window: `TYPE_APPLICATION_OVERLAY`, flags `NOT_FOCUSABLE | NOT_TOUCH_MODAL | LAYOUT_IN_SCREEN`, top gravity, full width, moved down by the status-bar height.
- Pure protocol code is in `Protocol.kt` and unit-tested (`ProtocolTest.kt`).
- If a sender never reaches the receiver, check `adb logcat` for "Permission Denial ... android.permission.DUMP": a device that does not give the shell user that permission needs the `android:permission` attribute removed from the receiver in `AndroidManifest.xml`.
