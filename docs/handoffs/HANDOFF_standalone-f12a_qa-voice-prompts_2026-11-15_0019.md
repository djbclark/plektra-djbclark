---
schema_version: 1
handoff_id: "0019"
parent_handoff_ids: ["0013"]
lineage: deterministic
chain:
  - standalone-f12a
repo: plektra-djbclark
workspace: plektra-djbclark-qa-voice-wt
branch: plk/qa-voice
head_sha: 63748a9bd3069b4be1c10aaadb672e9181f24621
created_at: 2026-11-15T17:49:55-0500
writer: claude-code
---

# Handoff — Plektra QA: spoken prompts and a user guide, ready for the human run

## The Goal

Run the physical-keyboard smoke suite (`qa/suites/smoke_chords.yaml`) on the Titan 2 Elite with djbclark typing, and fix whatever the trial table shows. djbclark ruled QA-harness work only until he says otherwise. F1 (the Fn + right-Shift select chord) stays parked.

## Where We Are

1. `origin/plk/main` = `63748a9b`. Worktree `/Users/djbclark/src/plektra-djbclark-qa-voice-wt`, branch `plk/qa-voice`, clean, claimed (`/Users/djbclark/.local/state/ade/claims/plektra-qa-voice.json`, expires 2026-11-16T21:45Z, owner was 0416bbec). The successor re-claims it for itself. Grok's `/Users/djbclark/src/plektra-djbclark-qa-wt` (branch `plk/qa-notes`) was fast-forwarded to the same head; it is unclaimed.
2. This session added [9922df57](https://github.com/djbclark/plektra-djbclark/commit/9922df57): the runner speaks each action after the bell. After `start_wait_s` (default 10) quiet seconds it says "Still waiting." and the action, up to `max_reprompts` (3) times. Then the trial records `never started after the spoken prompts` and the batch stops. With a voice, `timeout_s` counts from the first input. `qa/plektra_qa/speech.py`: Kokoro-82M via `kokoro-cli` (installed: `uv tool install --python 3.13 kokoro-cli && kokoro setup`; `kokoro doctor` passes). Phrases are pre-rendered in the background into `~/.cache/plektra-qa/voice/`; fallbacks are macOS `say`, then `spd-say`/`espeak-ng`, then silent. `PLEKTRA_QA_VOICE`, `PLEKTRA_QA_VOICE_NAME`. The suite has `speak:` lines that spell symbols out.
3. [63748a9b](https://github.com/djbclark/plektra-djbclark/commit/63748a9b): `qa/README.md` plus `qa/README.de.md`, the user guide for other people. `docs/development.md` links it. `firerpa-hitl.md` points to it, and its section 2 no longer calls Plektra an ESP32 chording keyboard.
4. No human run has happened since the screen gate's `182a3c02` fix. adb lists only `100.73.253.10:5555` (wireless); the USB serial is not connected. The code just passes `ADB_TARGET` to `adb -s`, so wireless should work, but that is untested.
5. Phone settings were at baseline when last read (timeout 120000, stay 0, inversion 0, heads-up 1).

## What We Tried

1. `kokoro speak --play` per prompt: about 3.1 s wall time (2.2 s of model load). Rejected for live prompts in favour of pre-rendered WAVs plus `afplay`/`paplay` (2 ms to start).
2. The router `--kinds code`: no pool had room, so the code and docs were written inline (allowed by the ruling for small work). `--kinds bulk` → `herdr-generic` has no model profile, so dispatch is blocked. That is reported to the djbclark-ade lead (now `ef15e9a4`), whose worker f2d34e81 is on it. A PR link is owed to this chain.
3. `bg ... pytest tests -q` (a directory) is refused as a full suite. Pass the files: `tests/test_hitl.py tests/test_speech.py`.

## Key Decisions

The voice is Kokoro-82M through kokoro-cli: local, Apache weights, macOS and Linux, and an agent-shaped CLI (`--json`, `doctor`). Rejected: cloud free tiers (local preferred), Piper (lower quality), macOS `say` alone (Mac only). The reprompt timer starts when the speech ends, so a long phrase does not eat the person's start time. A trial nobody starts ends the batch, because the person has gone. A new worktree instead of Grok's, to keep ownership clean. The toolchain-prompt rule was retired: the prompt was archived 2026-11-06, and new tools are now documented in the using repo's README.

## Decisions

- [decision] Kokoro-82M via kokoro-cli with a pre-rendered phrase cache; rejected: per-prompt kokoro speak, cloud TTS, Piper; because local, high quality, cross-platform, instant playback.
- [decision] Reprompt after 10 quiet seconds, 3 times, then stop the batch; rejected: a fixed 30 s start window; because the person may need more time or may have left.
- [ruling] "we can translate in batches, it is fine if the translations go a little stale as long as we get to them eventually" (djbclark, 2026-11-15)
- [ruling] "Be sure all this QA stuff is getting documented in a way that other people will be able to learn about it and use it." (djbclark, 2026-11-15)
- [ruling] "Do any work you can do without the phone for the movement. If nothing useful can be done pause." (djbclark, 2026-11-15)

## Relations

- continues [[HANDOFF_standalone-f12a_screen-awake-gate_2026-11-15_0013]]
- decided_in [[HANDOFF_standalone-f12a_qa-voice-prompts_2026-11-15_0019]]

## Evidence & Data

- Tests: `cd /Users/djbclark/src/plektra-djbclark-qa-voice-wt/qa && /Users/djbclark/ops/site-private-djbclark/bin/bg uv run --python 3.14 pytest tests/test_hitl.py tests/test_speech.py -q` → 26 passed (19 old + 7 new).
- Live Mac voice check: 2 phrases rendered in 2.5 s; `say` returned in 0.002 s.
- Instruction fix: `~/ops/site-public-djbclark/home-agents.md` + `cursor/home-agents.mdc` rule 5 (13375 / 13357 bytes); `~/ops/site-private-djbclark/memory/feedback_ask_update_apply_toolchain_prompt.md` marked RETIRED. Both pushed.
- Claude project memory `docs-bilingual-en-de.md` records the batch ruling. Pending German twins: `qa/firerpa-hitl.md`, `qa/titan2-elite-facts.md`.

## Operator Feedback

- Operator Feedback in handoff 0013 still applies: emoji + "Do this:" as the first line; run every command yourself; never start `qa-run`/`qa-prep` unless he asks; look for `bash-mode-` ids before claiming a `!` command did not run.
- Walk steps and expected results go INSIDE the AskUserQuestion card text.
- He prefers non-Claude workers when a pool has room. Re-plan per slice.

## Where We're Going

1. **Ask djbclark (emoji + "Do this:", inside the card) whether he is holding the phone and ready.** Offer wireless (`ADB_TARGET=100.73.253.10:5555`, recommended since USB is not listed) or plugging in USB. On "go", confirm `adb devices -l`, then run the Quick Start in the background (`run_in_background`) and tell him what he will see and hear (README section 1).
2. Read the trial table. A first-trial failure other than a real `stopped from the phone` gets a code fix in the worktree (re-claim it first), tests, commit, push to `plk/main`, then another run. Also check whether the voice was heard, was timely, and that the reprompts did not fire while he was typing.
3. If the seven Pinnote trials pass, record WPM and the observed text (and whether the trailing Enter matched the sentinel) in `qa/titan2-elite-facts.md`. Do not claim HID coexistence from that table alone.
4. Leave the phone at timeout 120000, stay 0, inversion 0, heads-up 1 (README section 8 has the restore commands).
5. Later: German twins in batches; when the ade lead's router PR lands, verify a `--kinds bulk` plan dispatches; F1 stays parked until djbclark asks.

## Detached jobs

none

## Quick Start

```bash
adb devices -l
cd /Users/djbclark/src/plektra-djbclark-qa-voice-wt/qa && FIRERPA_HOST=100.73.253.10 FIRERPA_PORT=65000 FIRERPA_CERT=/Users/djbclark/firerpa-nonroot/certs/lamda.pem FIRERPA_VERSION=10.12 ADB_TARGET=100.73.253.10:5555 just qa-run /Users/djbclark/src/plektra-djbclark-qa-voice-wt/qa/suites/smoke_chords.yaml
```

With USB plugged in, use `ADB_TARGET=TITAN20000040244` instead. The output prints `voice: kokoro`; `PLEKTRA_QA_VOICE=off` silences it.
