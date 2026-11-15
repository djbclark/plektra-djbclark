---
schema_version: 1
handoff_id: "0013"
parent_handoff_ids: []
lineage: none
chain:
  - standalone-f12a
repo: plektra-djbclark
workspace: plektra-djbclark-qa-wt
branch: plk/qa-notes
head_sha: 182a3c02384bf5d08dfb1f031a65f1cb567103a1
created_at: 2026-11-15T14:04:50-0500
writer: Grok
---

# Handoff — Plektra QA screen gate

## The Goal

Run the physical-keyboard chord smoke on the Titan through FIRERPA without the human touching the glass while the harness is driving it. The human types only when the phone shows ordinary colors and the shade shows the action. The harness itself keeps the screen on, and a shade card can return the phone to non-testing mode if inverse colors or the wake lock get left behind.

## Where We Are

Code head is `182a3c02384bf5d08dfb1f031a65f1cb567103a1` on `plk/qa-notes`, published to `origin` `refs/heads/plk/main`. The worktree was clean when this handoff was written. This document is the only intended commit after that sha, and it is docs-only.

FIRERPA 10.12 is already installed on USB serial `TITAN20000040244`. Do not reinstall it. gRPC stays on `100.73.253.10:65000` with the cert at `/Users/djbclark/firerpa-nonroot/certs/lamda.pem`. The Plektra IME is `rocks.pkb.plektra.djb` / `PhysicalKeyboardInputMethodService`. Do not change it, do not `am force-stop` it, and do not call `set_text`.

The chord batch has not had a successful human pass since the screen gate existed. The last `qa-run` died on trial one with `stopped from the phone` because the stop card was still snoozed. That false stop is fixed in `182a3c02` and proven on the phone, but djbclark has not re-run the suite since that commit.

After the proof the phone was back to timeout `120000`, stay-awake `0`, inversion `0`, heads-up `1`. The stop and presence cards are snoozed again by the proof's exit. The next pass unsnoozes them.

## What We Tried

1. A floating overlay for the next action. Rejected. It needs an install, can take keyboard focus, and Android 12 blocks touches through an application overlay. The shade is the trusted surface.
2. `cmd notification post` with an unquoted multi-word title. The phone split `Hands off` into tag `off` and `Your turn` into tag `turn`. `shell_line` now quotes one adb-shell argument. Those two leftover rows were snoozed.
3. `cmd notification cancel`. This Android build does not have it. Snooze is the removal. Snooze then hid the next post of the same tag, which caused the false abort below.
4. `svc power stayon true` read back as `7`. On this Titan (Android 16, sdk 36) it writes `15` (AC, USB, wireless, and dock). Hard-requiring `7` made enter fail closed and restore. The gate now accepts any non-zero integer.
5. Treating a missing stop card as a swipe. The previous pass had snoozed `plektra-qa-stop` and `plektra-qa-presence`. The next post stayed out of `cmd notification list`. The watcher cleared inversion within about two seconds and the runner recorded `stopped from the phone` on `alpha: hello` with an empty observation. The screen flashed inverted and returned to ordinary colors. `182a3c02` unsnoozes those tags first, waits until the stop card is listed, and counts a swipe only after the card has been seen.
6. Termux notification buttons, Tasker, and Shizuku as the one-tap restore. They are on the phone. The QA harness must not require an ops or Termux install. The gesture is swipe-away, watched by a `setsid` shell script.
7. Opening `branislav667.pinnote/.NoteEditActivity` directly. It is not exported. The suite opens `NotesActivity` and clicks the FAB only when the body field is absent. Quicksearch is the wrong target because Enter submits.
8. The 12:58 Grok `!` qa-run. Bash mode ended the turn while tool call `bash-mode-47d9b93e-f0d1-43d8-b740-f6d036341807` was still `in_progress`. No stdout was stored. Do not invent a pass/fail for that run. A local feedback draft is `01a1e087-55c5-7fd0-a893-f4fd79654190`.

## Key Decisions

- Signals are adb-only inside `qa/plektra_qa/screen_gate.py`. Nothing from an ops repo is installed or imported. `gate=None` keeps unit tests off adb.
- Inverted colors mean the harness has the glass. Ordinary colors mean the human types. Inversion stays on through field prep and driving. It turns off when the harness yields for typing, and again when the pass ends.
- The shade shows only the action (`Hands off`, or the trial `shade` line). It opens for 4 seconds, then closes before the terminal bell. The chat may explain why. The shade must not.
- The pass sets `screen_off_timeout` to `86400000` and runs `svc power stayon true`. Exit restores the saved values. `svc power stayon false` runs only when the saved stay value is `0`, so a user's existing stay mask is not wiped. This Titan's baseline is timeout `120000` and stay `0`.
- The abort card is tag `plektra-qa-stop`, title and body `Swipe away to stop testing`. A `setsid` watcher at `/data/local/tmp/plektra-qa-awake.sh` restores inversion, timeout, stay-awake, and heads-up when that card disappears and `/data/local/tmp/plektra-qa-release` is absent. A clean exit writes the release file before it snoozes.
- A card that never appears is a harness failure (`stop card did not appear`), not a user abort. The runner still honors `/data/local/tmp/plektra-qa-stopped` once a real swipe happens, and it does not start later trials.
- Heads-up (`heads_up_notifications_enabled`) is `0` for the whole session, including the human's turn, and the saved value is restored. Do not change DND. Do not press Back. Do not auto-click dialogs.
- Chord trials never call `set_text`. Empty Pinnote fields report their hint (`Title`, `Start writing…`) as text. The runner treats that hint as empty. A trailing Enter is the sentinel newline.

Rejected with the approaches above: overlay, Termux buttons, assuming Wakey, assuming stay-awake readback `7`, and `cmd notification cancel`.

## Evidence & Data

- Worktree: `/Users/djbclark/src/plektra-djbclark-qa-wt`
- Suite: `qa/suites/smoke_chords.yaml`
- Spec: `qa/firerpa-hitl.md` section 5
- Gate: `qa/plektra_qa/screen_gate.py`
- Runner stop check: `qa/plektra_qa/hitl_runner.py` (`STOPPED = "stopped from the phone"`)
- Tests: `qa/tests/test_hitl.py`. After `8667bcea`, 18 passed. After `182a3c02`, 19 passed. Command: `cd /Users/djbclark/src/plektra-djbclark-qa-wt/qa && uv run --python 3.14 pytest tests/test_hitl.py -q`
- USB proof after `182a3c02`: stop card listed, still listed after 3 seconds, `user_stopped` false, `hold` left inversion `1` with timeout `86400000` and stay `15`, exit restored timeout `120000`, stay `0`, inversion `0`, heads `1`. A second enter after that snooze listed the stop card again.
- Device: serial `TITAN20000040244`, also `100.73.253.10:5555` and `192.168.68.51:5555`. Screen-driving runs need the transport `adb devices` actually lists. USB was unplugged once this session and then came back.
- Snoozed on purpose and left alone: `plektra-qa-probe` until about 2026-12-10. A stray tag `check` from a bad quoting probe was snoozed during the unsnooze fix. Do not try to cancel the probe.
- Session chain log: `/Users/djbclark/.local/state/handoffs/chains/standalone-f12a/SESSION_LOG.md`
- Published commits this arc: [83533e00](https://github.com/djbclark/plektra-djbclark/commit/83533e0076f37d018c5869a1833d2d94da63ea5e), [c68134d4](https://github.com/djbclark/plektra-djbclark/commit/c68134d4834dab8165b8f7c1a595f645a6776d88), [8667bcea](https://github.com/djbclark/plektra-djbclark/commit/8667bcea47099532b855d880f9f4b0e8aa6b8408), [182a3c02](https://github.com/djbclark/plektra-djbclark/commit/182a3c02384bf5d08dfb1f031a65f1cb567103a1)

## Operator Feedback

- Complete copy-paste command lines, with `cd` and every variable. `ADB_TARGET=TITAN20000040244` needs a space before `just`. An empty `ADB_TARGET` skips the gate. `ADB_TARGET=just` is the bug.
- When djbclark must act, the first line is an emoji, then `Do this:`, then the action. The shade shows only the action. The chat may add why.
- He already ran one qa-run with Grok `!`. The assistant told him to paste it again. That was wrong. Look for tool id `bash-mode-` before saying a `!` command did not run.
- Inverse colors must stay on until the QA work is done or the harness is about to pause for him to type. A failure must be fixed and continued, or he must be told the next action. Do not flash inversion and exit with only a table row.
- Do not assume Wakey. The persistent stop card must be able to clear inverse colors and the wake lock.
- Do not start `just qa-run` or `just qa-prep` unless he asks. He types on the Titan keyboard. The Mac watches. `qa-prep` zeros animator scales and has not been run.

## Where We're Going

1. djbclark runs the Quick Start command and types only the shade action on the Titan keyboard. Inverted plus `Hands off` means leave the phone alone. Ordinary colors mean the shade has shown the action for 4 seconds and closed, and the terminal bell means type that action. Swipe `Swipe away to stop testing` only to leave testing mode.
2. Read the trial table. A `stopped from the phone` result now means the stop card was visible and then dismissed. Any other first-trial failure gets a code fix before another human run.
3. If the seven Pinnote chords pass, record WPM and the observed text, including whether a trailing Enter matched the newline sentinel. Do not claim HID coexistence from that table alone.
4. Leave the phone at timeout `120000`, stay `0`, inversion `0`, and heads-up `1` when the pass ends. If a run dies mid-invert, enter the gate's restore path or unsnooze is not enough: put those four settings back.

## Quick Start

```bash
cd /Users/djbclark/src/plektra-djbclark-qa-wt/qa && FIRERPA_HOST=100.73.253.10 FIRERPA_PORT=65000 FIRERPA_CERT=/Users/djbclark/firerpa-nonroot/certs/lamda.pem FIRERPA_VERSION=10.12 ADB_TARGET=TITAN20000040244 just qa-run /Users/djbclark/src/plektra-djbclark-qa-wt/qa/suites/smoke_chords.yaml
```

Confirm `adb devices -l` lists `TITAN20000040244` before that command. Tests, without the phone:

```bash
cd /Users/djbclark/src/plektra-djbclark-qa-wt/qa && uv run --python 3.14 pytest tests/test_hitl.py -q
```

Publish code with `/Users/djbclark/.local/bin/git-commit-own` from the worktree. This handoff commit stays local until djbclark asks to push it.
