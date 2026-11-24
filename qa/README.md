English · [Deutsch](README.de.md)

# Plektra QA harness: a person types, the computer watches

This folder tests Plektra on a real phone with a real hardware keyboard. A person types on the phone's keyboard. The computer prepares each text field, tells the person what to type, reads what arrived, and scores it (pass or fail, a character diff, words per minute).

The computer never types for you. Plektra's job is turning physical key presses into text, so only real key presses test it. The computer drives the screen through [FIRERPA](https://github.com/firerpa/lamda) (lamda) over adb and never touches the input method.

Status: fork-only, not part of upstream Plektra. Proven on a Unihertz Titan 2 Elite (Android 16, not rooted). Any Android phone that runs FIRERPA 10.12 over adb should work.

## Contents

1. [What a run looks and sounds like](#1-what-a-run-looks-and-sounds-like)
2. [What you need](#2-what-you-need)
3. [One-time setup](#3-one-time-setup)
4. [Run a suite](#4-run-a-suite)
5. [Read the results](#5-read-the-results)
6. [Write your own suite](#6-write-your-own-suite)
7. [Naming keys](#7-naming-keys)
8. [Use it from an AI agent (MCP)](#8-use-it-from-an-ai-agent-mcp)
9. [If something goes wrong](#9-if-something-goes-wrong)
10. [Develop the harness](#10-develop-the-harness)
11. [Lessons learned on the Titan 2 Elite](#11-lessons-learned-on-the-titan-2-elite)

## 1. What a run looks and sounds like

| You see or hear | Meaning | You do |
| --- | --- | --- |
| Inverted colors, shade card `Hands off` | The computer is using the screen (opening the app, clearing the field). | Don't touch the phone. |
| Ordinary colors. The shade opens for 4 seconds with the action, closes, then the computer beeps and says the action out loud. | Your turn. | Type that action on the phone's keyboard. |
| `Still waiting.` and the action again | Nothing reached the field for 10 quiet seconds. | Start typing. After 3 reminders the run stops, assuming you have left. |
| `Testing finished.` | The pass is over and the phone's settings are back to normal. | Read the table on the computer. |
| The shade card `Swipe away to stop testing` | Always there during a pass. | Swipe it away only to stop testing. The phone then restores its own settings, even if the computer has lost contact. |

The harness keeps the screen on during a pass. When the pass ends (normally, after Ctrl-C, or after a swipe) it puts back color inversion, the screen timeout, stay-awake, and heads-up notifications.

## 2. What you need

1. A Mac or a Linux computer with [`uv`](https://docs.astral.sh/uv/), [`just`](https://just.systems/) and `adb` (Android platform-tools). `uv` downloads Python 3.14 on its own.
2. An Android phone with a hardware keyboard, developer options and USB debugging on, and Plektra installed and chosen as the keyboard.
3. FIRERPA 10.12 running on the phone as the `shell` user, started with a client certificate (`lamda.pem`). No root, no APK.
4. Optional: a voice. [Kokoro](https://huggingface.co/hexgrad/Kokoro-82M) through [kokoro-cli](https://pypi.org/project/kokoro-cli/) sounds best and runs offline. Without it the harness uses macOS `say`, or `spd-say` / `espeak-ng` on Linux, or stays silent.
5. A note-taking app with a plain text field. The sample suite uses Pinnote (`branislav667.pinnote`).

## 3. One-time setup

1. Install FIRERPA on the phone over adb. [firerpa-hitl.md section 1](firerpa-hitl.md#1-install-and-configure-the-users-device-over-adb) lists every step: the verified server build, the certificate, the patched driver that lets the keyboard keep working while FIRERPA is connected, and the start command. Keep `lamda.pem` outside the repository. It is a private key.
2. Optionally, install the voice (about 121 MB, once):

   ```bash
   uv tool install --python 3.13 kokoro-cli && kokoro setup && kokoro doctor
   ```

3. Create `qa/.env`. The file is gitignored; never commit it. Use your own values:

   ```dotenv
   FIRERPA_HOST=192.0.2.10          # the phone's address, or 127.0.0.1 with adb forward
   FIRERPA_PORT=65000
   FIRERPA_CERT=/absolute/path/to/lamda.pem
   FIRERPA_VERSION=10.12
   ADB_TARGET=SERIAL_OR_IP:5555     # a device that `adb devices` lists right now
   # PLEKTRA_QA_VOICE=auto          # auto, kokoro, say, espeak or off
   # PLEKTRA_QA_VOICE_NAME=af_heart # a Kokoro voice; `kokoro voices` lists them
   # PLEKTRA_QA_KEYMAP=/path/to/profile.yaml  # key profile, default keymaps/titan2-elite-baseline.yaml (section 7)
   ```

   `ADB_TARGET` must name a transport that `adb devices` lists now: the USB serial, or `IP:5555` for wireless adb. A run that drives the screen refuses to start without it.

4. Check the connection:

   ```bash
   cd qa && just qa-connect
   ```

   It prints the FIRERPA version, uptime, and round-trip time, and fails if the version is not `FIRERPA_VERSION`.

`just qa-prep` sets the phone's three animation scales to 0 and turns on FIRERPA's dialog watchers. It changes phone settings, so run it only when you want that.

## 4. Run a suite

Hold the phone, then:

```bash
cd qa && just qa-run suites/smoke_chords.yaml
```

The computer shows the keymap in use (`keymap: live ...` or `keymap: profile ...`, section 7), a live table and the voice in use (`voice: kokoro`). Follow the signals in section 1. Ctrl-C on the computer stops the pass and restores the phone.

### Record a run to share (optional, off by default)

```bash
cd qa && just qa-run suites/smoke_chords.yaml --record
```

`--record` saves one mp4 in `~/plektra-qa-recordings/` (`--record-dir` or `PLEKTRA_QA_RECORD_DIR` change it). It needs `scrcpy`, `ffmpeg` and the phone on adb (`ADB_TARGET`). What is in it:

- The phone's screen (a view-only copy; scrcpy runs with `--no-control`) and the computer's spoken prompts, laid on the audio track at the times they were spoken (the voice is not captured from the speakers).
- A band above the screen: a running timecode on the left; on the right the keys held at that moment (`L-Shift + A`, kernel names such as `ENTER`, d-pad keys included) and the touches (`TOUCH (x, y)`, then `TAP`, `LONG PRESS` or `SWIPE up (x1, y1) to (x2, y2)`; the keyboard's own trackpad is marked `PAD`). Android's "show touches" dots are on while recording and put back afterwards.
- A log panel to the right of the phone: what the terminal shows, in time with the picture (trial prompts, spoken lines, pass or fail, and the keys and touches as they happen). The same lines are also an mp4 subtitle track named "Terminal log" that a player can switch off. `--no-panel` leaves both out (CLI only).
- Chapters, one per trial (the next batch of key presses): inside the mp4, and as `plektra-qa-*.chapters.txt` to paste into a YouTube description (YouTube needs 0:00 first and chapters of at least 10 s, so shorter ones are merged there).

Keys and touches come from the phone's `getevent`; sync with the picture is within about a tenth of a second. The recording shows the whole screen, the status bar and every key the person presses, so it is shared only with their say-so. Do not use it for anything private.

### Button bar instead of the shade card (optional)

`just qa-run SUITE --bar` shows a small on-screen bar with **Done**, **Next** and **Stop** instead of pulling down the notification shade. A trial ends only when you press **Done**, so you can fix a typo first. **Next** skips the trial (it is recorded as skipped); **Stop** ends the batch. The bar is a companion app (`--bar-package`, default `dev.hitlbar.overlay`; it needs the draw-over-apps permission). The host talks to it by `am broadcast -a <package>.SHOW|HIDE` and reads the answers from logcat lines with tag `QAOVERLAY` (`shown`, `hidden`, `button=done|next|stop`). If the bar does not report `shown` within 5 s, the trial fails with a clear error. Any app can write to logcat, so the host trusts only lines from the bar app's own uid (read with `pm list packages -U`; it refuses to start if the app is not installed), only lines from the phone's current time on, and only lines whose `seq` is higher than every one accepted before (the counter may restart at 1 after a hide, when Android restarted the app). The app is in `qa/overlay-app/` (see its README); the host side is in `plektra_qa/button_bar.py`.

**Install** (once per phone; a developer device only, since the app draws over other apps and takes broadcasts from adb): build the APK on GitHub Actions (workflow `djb-overlay-app.yml` on `ci/base`, run with `scripts/github-build-overlay-app --release plk/main`), download `hitl-button-bar-<sha8>.apk` from the `overlay-app-<UTC time>-<sha8>` release, then:

```bash
adb install -r hitl-button-bar-<sha8>.apk
adb shell appops set dev.hitlbar.overlay SYSTEM_ALERT_WINDOW allow   # or: Settings, Apps, HITL button bar, Display over other apps
```

**Smoke test** (a human with the phone in hand): in one terminal run `adb logcat -s QAOVERLAY:I`, in another:

```bash
adb shell am broadcast -a dev.hitlbar.overlay.SHOW -p dev.hitlbar.overlay --es text "Smoke test: press Done" --es buttons done,next,stop
adb shell am broadcast -a dev.hitlbar.overlay.HIDE -p dev.hitlbar.overlay
```

Expected: after the first command the bar appears at the top below the status bar, the keyboard of any open text field stays up, and logcat prints `QAOVERLAY shown seq=1`. Press Done: `QAOVERLAY button=done seq=2` (Next and Stop print `button=next` and `button=stop`). After the second command the bar is gone and logcat prints `QAOVERLAY hidden seq=3`. Without the permission the first command prints `QAOVERLAY error=no-permission seq=1` instead and nothing appears.

## 5. Read the results

Each trial is a row: the prompt, the expected text, what the field held, pass or fail, accuracy, words per minute, and the gap since the previous trial. A failed run exits 1 and prints one `fail` line per failed trial.

Errors you may see:

| Error | Meaning |
| --- | --- |
| `stopped from the phone` | The stop card was seen and then swiped away. Later trials do not start. |
| `never started after the spoken prompts` | Nothing was typed after the action and every reminder. Later trials do not start. |
| `stop card did not appear` | The phone never listed the stop card. This is a harness problem, not your swipe. |
| `click target not found: …` / `input field not found` | The app was not on the expected screen. Check the suite's `package`, `activity` and `resource_id`. |
| `Unlock the phone once. The harness will not enter a PIN.` | The lock screen is up. Unlock it and run again. |

The typing time (and WPM) runs from your first key to the match, so a slow start does not lower your WPM.

## 6. Write your own suite

A suite is YAML. Settings at the top apply to every trial, and a phase or a trial can override them:

```yaml
name: my_suite
timeout_s: 30          # seconds to finish once typing has started
sentinel: "\n"         # a trailing Enter also counts as done
start_wait_s: 10       # quiet seconds before the action is said again
max_reprompts: 3
phases:
  - name: notes
    resource_id: branislav667.pinnote:id/pin_text
    trials:
      - prompt: "punctuation: a, b."             # shown on the computer
        shade: "Type a, b."                      # shown on the phone
        speak: "Type: a, comma, space, b, period."  # said out loud
        expect: "a, b."
        package: branislav667.pinnote
        activity: branislav667.pinnote.NotesActivity
        click_resource_id: branislav667.pinnote:id/notes_fab
      - prompt: "<Ctrl+A>, then <Ctrl+C>"      # keys are tokens, see section 7
        shade: "Press <Ctrl+A>, then <Ctrl+C>"
        expect: "a, b."
        verify_mode: clipboard   # check the clipboard instead of the field
        clear_field: false       # keep the text from the previous trial
```

All trial fields: `prompt`, `expect`, `verify_mode` (`text` or `clipboard`), `sentinel`, `timeout_s`, `shade`, `speak`, `start_wait_s`, `max_reprompts`, `package`, `activity`, `resource_id`, `text_hint`, `click_resource_id`, `clear_field`, `cue_on_device` (the phone beeps too).

Tips:

1. Spell symbols out in `speak`. A voice reads `,` and `.` as pauses. Write keys as tokens (`<Ctrl+A>`), never as bare names: section 7.
2. Find resource ids with the MCP tool `inspect_ui_fast`, or `adb shell uiautomator dump` with FIRERPA stopped (section 11, item 6).
3. Open an app's exported launcher activity, then click into the editor with `click_resource_id`. Activities that are not exported cannot be started directly.
4. Avoid search boxes as targets: Enter submits them.

## 7. Naming keys

On the Titan 2 Elite a key press goes through three layers:

1. The printed keycap, what is engraved on the key.
2. The vendor remap (Settings, Shortcut keys, Key setting; the vendor package `com.agui.shortcutsettings`). It rewrites a key before any app sees it.
3. Plektra's own remap (the hardware key remap, for example "Swap right Shift and Enter").

So "press Ctrl+A" is ambiguous: the key that does Ctrl may be Fn, Alt, or something else, depending on both remaps. The harness therefore gives every key instruction a person sees or hears in two versions:

1. The "your" version, `your Ctrl`: whichever key currently does Ctrl after both remaps.
2. A keycaps version, `Keycaps: hold Fn, tap A.`: what to press judging only by the printed glyphs.

Plain quoted text, such as `type "42"`, still means characters to produce by any means. Only keys use the two-version form.

**Write keys as tokens.** In a suite's `prompt`, `shade` and `speak`, put each key or chord in angle brackets and name the function, not the keycap:

| Write | Meaning |
| --- | --- |
| `<Ctrl+A>` | A chord: hold the key that does Ctrl, tap the key printed A. Letters and digits are keycaps of that letter. |
| `<Tab>`, `<Enter>`, `<Backspace>`, `<Shift+Tab>` | A single key, or a chord. Names: Ctrl, Shift, Alt, Meta, Sym, Fn, Tab, Enter, Backspace, Delete, Esc, Space, Back, Home, Recent, Media (Control, Return, Del and Escape also work; case does not matter). |
| `<Ctrl+A>, then <Ctrl+C>` | A sequence is separate tokens, in order. |
| `<Tab\|Enter>` | Either key will do (write a plain `|` in the suite). |

The harness turns `Press <Ctrl+A>, then <Ctrl+C>` into three texts. With the test baseline keymap:

| Where | Text |
| --- | --- |
| Screen (the table on the computer) | `Press your Ctrl + A, then your Ctrl + C. Keycaps: hold Fn, tap A; then hold Fn, tap C.` |
| Shade (the phone's notification, kept short) | `Press your Ctrl + A, then your Ctrl + C. Keycaps: Fn+A, then Fn+C.` |
| Spoken (words only, because the voice reads symbols as pauses) | `Press your Control plus letter A, then your Control plus letter C. By keycaps, hold the key printed F N, tap letter A. Then hold the key printed F N, tap letter C.` |

The screen and spoken texts also say where a key is when the profile knows it (`left ⇧ (in the bottom left corner of the keyboard)`). A trial that uses a token gets all three texts filled in from `prompt`, `shade` and `speak` (the spoken text comes from `speak`, else `shade`, else `prompt`). Do not write the keycaps line yourself.

**The harness refuses a suite** (or an MCP trial list) when:

1. A key name stands bare outside a token: `Ctrl`, `Control`, `Alt`, `Shift`, `Fn`, `Sym`, `Tab`, `Enter`, `Return`, `Backspace`, `Del`, `Delete`, `Esc`, `Escape`, `Meta`, `Home`, `Back` or `Recent`, in any case. The error names the field and suggests the token. A few ordinary phrases are allowed (`home screen`, `back to`, `come back`, `return to`, `new tab`; the list is `ALLOWED_PHRASES` in `plektra_qa/keys.py`).
2. A token is malformed or names no key (`<Foo>`).
3. A token cannot be resolved to a keycap, for example `<Esc>` on a phone with no key that does Escape. The error names the token. There is never a one-version fallback.

**Where the keymap comes from.** `plektra-qa run` and `render` print `keymap: ...` at the start, like `voice: kokoro`.

1. With `ADB_TARGET` set, the keymap is read from the phone, read-only: the vendor layer from `adb shell getprop` (`persist.sys.agui_programmable_<key>_key`) and Plektra's layer from its preferences (`adb exec-out run-as <package> cat shared_prefs/plektra_prefs.xml`, which needs a debuggable build; the package is `PLEKTRA_QA_PACKAGE`, default `rocks.pkb.plektra.djb`). If Plektra's layer cannot be read, the profile's rules stand in and the output says so.
2. Otherwise, or with `PLEKTRA_QA_KEYMAP_SOURCE=profile`, it comes from a profile: [keymaps/titan2-elite-baseline.yaml](keymaps/titan2-elite-baseline.yaml) (the factory keycaps, the vendor test baseline and the "Swap right Shift and Enter" setting), or the file named by `PLEKTRA_QA_KEYMAP=/path/to/profile.yaml`. Copy the baseline to describe another phone or another remap.
3. If the live read and the profile disagree, the live read wins, and the output lists the differences. `PLEKTRA_QA_KEYMAP_SOURCE=live` insists on the phone and fails if it cannot be read.

The keymap is read once at the start. Change a key setting during a run and the texts are stale; run again.

**Try it, or write a question card.** `just qa-keys "<Ctrl+A>, then <Ctrl+C>"` (or `plektra-qa render "..."`; add `--json` for one JSON object) prints the screen, shade and spoken versions without driving the phone. An agent that asks a person to press keys outside a suite (a device-walk question card) should paste the screen version from here instead of writing key names by hand.

## 8. Use it from an AI agent (MCP)

`just qa-mcp` serves the MCP server `plektra-qa-mcp` on stdio. Tools:

| Tool | Does |
| --- | --- |
| `device_status` | FIRERPA version, uptime, round-trip time. |
| `teleport_and_prep_input` | Open an activity and focus and clear a field (behind the screen gate). |
| `inspect_ui_fast` | The screen's UI tree, optionally with a JPEG screenshot. |
| `execute_ui_action` | One click or key press on a selector (behind the screen gate). |
| `request_human_chord_batch` | Run trials (a JSON list of trial objects) with a person typing, and return the report. It blocks until the person is done. `record=true` also records the run (section 4); only after the person said yes. |

Rules for agents:

1. Start a run only when the person has asked and is holding the phone. Tell them first what they will see and hear.
2. Never type for the person (`set_text` types through FIRERPA, not the keyboard).
3. Never change the active input method, and never `am force-stop` or `am start -S` the Plektra package. Android would switch keyboards.
4. Do not click dialogs the harness has not seen before, press Back, or change Do Not Disturb.
5. Name keys with tokens (`<Ctrl+A>`) in trial lists and in questions to the person: `just qa-keys "<Ctrl+A>"` prints the two-version text to paste (section 7).
6. Recording (section 4) is off unless the person asks for it. Ask at the start of every guided run ("record the screen and voice to share with the group?") and pass `--record` (CLI) or `record=true` (`request_human_chord_batch`) only on a yes. The answer covers that run only. Tell them where the file lands.
7. A human batch never starts on its own. Every new batch posts its instruction on the phone's notification shade, prints it in the terminal and speaks it, then waits for the person's start: a key press or tap on the phone, or `go` typed in the terminal. A timeout ends the run with `refused: not started`; it never starts the batch. Recording starts only after the start. If the person has just told you go in chat, pass `human_said_go=true`; the card and voice still run first. No run, overlay check or smoke script bypasses this gate (`plektra_qa/start_gate.py`).

[firerpa-hitl.md](firerpa-hitl.md) is the full specification. It is written as a build prompt for an agent.

## 9. If something goes wrong

Restore the phone by hand if a pass died while the colors were inverted. These are the Titan 2 Elite's defaults; use your phone's own values:

```bash
adb -s "$ADB_TARGET" shell settings put secure accessibility_display_inversion_enabled 0
adb -s "$ADB_TARGET" shell settings put system screen_off_timeout 120000
adb -s "$ADB_TARGET" shell svc power stayon false
adb -s "$ADB_TARGET" shell settings put global heads_up_notifications_enabled 1
```

1. No sound: run `kokoro doctor`, or set `PLEKTRA_QA_VOICE=say` (Mac) or `espeak` (Linux).
2. The voice says the wrong thing for a symbol: add a `speak` line to that trial.
3. Spoken phrases are cached in `~/.cache/plektra-qa/voice/`. Delete the folder to render them again.

## 10. Develop the harness

```bash
cd qa && uv run --python 3.14 pytest tests -q
```

The tests use fakes and need no phone.

| File | Role |
| --- | --- |
| `plektra_qa/hitl_runner.py` | Suite loading, the trial loop, scoring, reminders. |
| `plektra_qa/start_gate.py` | The start gate: shade card, terminal text and voice, then wait for the person's key press, tap or typed `go`. |
| `plektra_qa/screen_gate.py` | Inverted colors, the shade, stay-awake, the stop card and the phone-side watcher. adb only. |
| `plektra_qa/speech.py` | The voice backends and the phrase cache. |
| `plektra_qa/recording.py` | Optional screen and voice recording (scrcpy, ffmpeg), the voice track and the chapters. |
| `plektra_qa/inputlog.py` | Reads the phone's `getevent` and turns it into held keys and tap, long press and swipe text. |
| `plektra_qa/overlay.py` | The band above the video (Pillow) and the chapter files. |
| `plektra_qa/keys.py` | Key tokens, the keymap (live or profile), the renderer and the lint. |
| `plektra_qa/controller.py` | Opening activities, focusing and clearing fields, reading text. |
| `plektra_qa/connection.py` | The certificate-authenticated FIRERPA connection. |
| `plektra_qa/mcp_server.py` / `cli.py` | The MCP server and the `plektra-qa` command. |
| `suites/` | Trial suites. |
| `keymaps/` | Key profiles: factory keycaps plus the vendor and Plektra remaps. |
| [titan2-elite-facts.md](titan2-elite-facts.md) | Facts about the test phone: key codes, settings, storage paths. |

## 11. Lessons learned on the Titan 2 Elite

1. This Android build has no `cmd notification cancel`. The harness removes a card by snoozing it, and unsnoozes it before posting it again. A snoozed tag stays hidden even when posted again.
2. `adb shell` splits a multi-word notification title into tag and text unless the whole command is one quoted argument.
3. `svc power stayon true` reads back as `15` (AC, USB, wireless, dock) on Android 16, not `7`. Any non-zero value counts.
4. An overlay window cannot be the "your turn" signal: it needs an install, can take keyboard focus, and Android 12+ blocks touches through it. The notification shade is the trusted surface.
5. Empty Pinnote fields report their hint (`Title`, `Start writing…`) as their text. The harness treats a hint as empty.
6. `adb shell uiautomator dump`, and any tool built on it, fails while FIRERPA is running. Android allows one UiAutomation client at a time and FIRERPA holds it, so the dump prints `Killed` and no XML. Use `inspect_ui_fast` while FIRERPA is up; stop FIRERPA first if you need a plain `uiautomator dump`.
7. Alternatives considered (2026-11-24), none adopted. [Mobile MCP](https://github.com/mobile-next/mobile-mcp) 1.0.19 (Apache-2.0): both of its modes read the screen with `uiautomator dump`, so both fail the same way next to FIRERPA, and its default mode also pushes `mobilecli.dex` to `/data/local/tmp` and may install a DeviceKit agent on the phone. Its text-typing tool would also bypass the keyboard under test. [Vercel UI SDK](https://github.com/vercel-labs/ui): web only (React, Vue, Svelte, Solid, Web Components), no Android. [Mobilerun](https://github.com/droidrun/mobilerun) (MIT): an LLM agent framework that plans its own steps, not a scripted typing harness. [uiautomator2](https://github.com/openatx/uiautomator2) was not tried: it needs the same single UiAutomation slot as FIRERPA and adds nothing it lacks. With FIRERPA stopped, both Mobile MCP modes did list the screen (about 4 s in legacy mode, about 12 s in default mode; plain `uiautomator dump` returned 59 nodes), so the only blocker is the single UiAutomation slot.
8. After stopping FIRERPA, `just start` can fail with `FIRERPA UIAutomation helpers did not start` although the server is up. On Android 16 the lifecycle controller's helper check never matches, because `/proc/PID/maps` names the loaded driver `[anon:dalvik-classes.dex extracted in memory from @aab.zip]` and not the file path. Finish the swap by hand: copy `overrides/aab.zip.patched` over the active `aab.zip` (temp file, then `mv -f`), kill only the `lamda` processes whose maps contain `@aab.zip`, and wait for them to respawn. `just status` then passes.
