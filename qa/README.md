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
7. [Use it from an AI agent (MCP)](#7-use-it-from-an-ai-agent-mcp)
8. [If something goes wrong](#8-if-something-goes-wrong)
9. [Develop the harness](#9-develop-the-harness)
10. [Lessons learned on the Titan 2 Elite](#10-lessons-learned-on-the-titan-2-elite)

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

The computer shows a live table and the voice in use (`voice: kokoro`). Follow the signals in section 1. Ctrl-C on the computer stops the pass and restores the phone.

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
      - prompt: "Ctrl+A, Ctrl+C"
        shade: "Press Ctrl+A, then Ctrl+C"
        speak: "Press Control A, then Control C."
        expect: "a, b."
        verify_mode: clipboard   # check the clipboard instead of the field
        clear_field: false       # keep the text from the previous trial
```

All trial fields: `prompt`, `expect`, `verify_mode` (`text` or `clipboard`), `sentinel`, `timeout_s`, `shade`, `speak`, `start_wait_s`, `max_reprompts`, `package`, `activity`, `resource_id`, `text_hint`, `click_resource_id`, `clear_field`, `cue_on_device` (the phone beeps too).

Tips:

1. Spell symbols out in `speak`. A voice reads `,` and `.` as pauses.
2. Find resource ids with the MCP tool `inspect_ui_fast`, or `adb shell uiautomator dump`.
3. Open an app's exported launcher activity, then click into the editor with `click_resource_id`. Activities that are not exported cannot be started directly.
4. Avoid search boxes as targets: Enter submits them.

## 7. Use it from an AI agent (MCP)

`just qa-mcp` serves the MCP server `plektra-qa-mcp` on stdio. Tools:

| Tool | Does |
| --- | --- |
| `device_status` | FIRERPA version, uptime, round-trip time. |
| `teleport_and_prep_input` | Open an activity and focus and clear a field (behind the screen gate). |
| `inspect_ui_fast` | The screen's UI tree, optionally with a JPEG screenshot. |
| `execute_ui_action` | One click or key press on a selector (behind the screen gate). |
| `request_human_chord_batch` | Run trials (a JSON list of trial objects) with a person typing, and return the report. It blocks until the person is done. |

Rules for agents:

1. Start a run only when the person has asked and is holding the phone. Tell them first what they will see and hear.
2. Never type for the person (`set_text` types through FIRERPA, not the keyboard).
3. Never change the active input method, and never `am force-stop` or `am start -S` the Plektra package. Android would switch keyboards.
4. Do not click dialogs the harness has not seen before, press Back, or change Do Not Disturb.

[firerpa-hitl.md](firerpa-hitl.md) is the full specification. It is written as a build prompt for an agent.

## 8. If something goes wrong

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

## 9. Develop the harness

```bash
cd qa && uv run --python 3.14 pytest tests -q
```

The tests use fakes and need no phone.

| File | Role |
| --- | --- |
| `plektra_qa/hitl_runner.py` | Suite loading, the trial loop, scoring, reminders. |
| `plektra_qa/screen_gate.py` | Inverted colors, the shade, stay-awake, the stop card and the phone-side watcher. adb only. |
| `plektra_qa/speech.py` | The voice backends and the phrase cache. |
| `plektra_qa/controller.py` | Opening activities, focusing and clearing fields, reading text. |
| `plektra_qa/connection.py` | The certificate-authenticated FIRERPA connection. |
| `plektra_qa/mcp_server.py` / `cli.py` | The MCP server and the `plektra-qa` command. |
| `suites/` | Trial suites. |
| [titan2-elite-facts.md](titan2-elite-facts.md) | Facts about the test phone: key codes, settings, storage paths. |

## 10. Lessons learned on the Titan 2 Elite

1. This Android build has no `cmd notification cancel`. The harness removes a card by snoozing it, and unsnoozes it before posting it again. A snoozed tag stays hidden even when posted again.
2. `adb shell` splits a multi-word notification title into tag and text unless the whole command is one quoted argument.
3. `svc power stayon true` reads back as `15` (AC, USB, wireless, dock) on Android 16, not `7`. Any non-zero value counts.
4. An overlay window cannot be the "your turn" signal: it needs an install, can take keyboard focus, and Android 12+ blocks touches through it. The notification shade is the trusted surface.
5. Empty Pinnote fields report their hint (`Title`, `Start writing…`) as their text. The harness treats a hint as empty.
