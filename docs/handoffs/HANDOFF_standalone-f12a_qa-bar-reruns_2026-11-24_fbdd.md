---
schema_version: 1
handoff_id: "fbdd"
parent_handoff_ids: ["ba14"]
lineage: deterministic
chain:
  - standalone-f12a
repo: plektra-djbclark
workspace: plektra-djbclark
branch: plk/qa-smoke-ba14
head_sha: c00a16bb3aabae60798cc2cf43a114b8dbdf155b
created_at: 2026-11-24T16:32:00+00:00
writer: claude-code
---
# Handoff — QA button bar: second run results and two rule changes

## The Goal
Get the Plektra QA harness's `smoke_chords` suite passing 7/7 on the Titan 2 with the phone-side button bar (Done/Next/Stop), recorded, with djbclark at the phone.

## Where We Are
- Worktree `/Users/djbclark/src/plektra-djbclark-ba14-run-wt`, branch `plk/qa-smoke-ba14`, HEAD c00a16bb, pushed to `origin plk/main`. Clean. 144 tests pass (`/opt/homebrew/bin/uv run --project qa pytest qa/tests -q`).
- Done this session: settle fix, host button bar (`qa/plektra_qa/button_bar.py`), Android overlay app (`qa/overlay-app/`, installed, adversary-approved), start card on the bar (ec7c4494), self-contained backspace trial, trials 6/7 expect `Beta` with leading newline ignored (c00a16bb).
- Run 1 (recorded, /Users/djbclark/plektra-qa-recordings/plektra-qa-20261124-111928.mp4): 4/7. Run 2 (recorded, started after c00a16bb; log /private/tmp/claude-502/-Users-djbclark-src-plektra-djbclark/90acbb75-a0da-480c-be59-05b7c371f7c5/scratchpad/qa-run-bar2.log): trial 1 FAIL (expect Hello, observed hello: no autocap on that first trial), trials 2-5 pass, trial 6 (<Tab|Enter>) failed because djbclark ran out of time, trial 7 (clipboard) not reached.

## What We Tried
- Shade-based start card: pulled the notification shade down at start; replaced by a bar card (StartGate bar=).
- Backspace trial "use Backspace on the previous word": failed because the runner clears the field before each trial. Replaced by "type no, use <Backspace> twice, then type ok". "to delete it" is refused by the key-name lint (bare "delete").

## Key Decisions
Done on the bar ends a trial; Next skips; Stop ends the batch. Navigation keys by shape: djbclark wants Back/Home/Recent said and shown as triangle/circle/square; Recent is the square (standard Android order; verify against the Titan 2 keycaps).

## Decisions
- [decision] Trial timeout defaults to no limit (wait for Done); rejected: 30 s idle timeout; because the human may need long and the bar's Done/Stop already end a trial.
- [decision] Expect Beta in trials 6/7 and ignore a leading newline from Enter; rejected: lowercase beta; because Plektra autocapitalizes.
- [ruling] "By default, we need a longer timeout, in fact we can just wait forever unless the user asks for a specific timeout value." (djbclark, 2026-11-24)
- [ruling] "Instead of 'Recent' Key and the other keys with symbols on them, say (or display) circle/triangle/square." (djbclark, 2026-11-24)
- [ruling] "1, and do it now, always have the #1 choice be next/do it now when doing qa" (djbclark, 2026-11-24)

## Relations
- continues [[HANDOFF_standalone-f12a_android-driving-wrap_2026-11-24_ba14]]
- decided_in [[HANDOFF_standalone-f12a_qa-bar-reruns_2026-11-24_fbdd]]

## Evidence & Data
Run 2 table: pass at 7.2, 11.8, 3.1, 23.3 s; trial 1 fail 9.1 s. Files: qa/plektra_qa/hitl_runner.py (ChordTrial.timeout_s=30 line 40; `timeout_s = float(data.get("timeout_s", 30))` line 83; idle_s_for line 337; deadline lines 391-420; OVERLAY_IDLE_S=300 line 29), qa/plektra_qa/keys.py (FUNCTIONS line 61, KEYCODE_FUNCTION 71, FUNCTION_KEYCODE 78, SPOKEN_FUNCTION 80, BARE_KEY_NAMES 402), qa/suites/smoke_chords.yaml (line 2 timeout_s: 30), qa/README.md line 146.

## Operator Feedback
Shade at start was wrong (fixed); backspace trial had nothing to delete (fixed); "I didn't have time to do the last test" (no-timeout rule, not yet implemented).

## Where We're Going
1. Implement no-default-timeout: ChordTrial.timeout_s default None/infinite; suite default and the `data.get("timeout_s", 30)` default become none; drop `timeout_s: 30` from smoke_chords.yaml; idle_s_for returns inf when None; update README (EN+DE) and tests (test_settle uses explicit timeout_s so still valid).
2. Name Back/Home/Recent as triangle/circle/square in spoken and displayed text (keys.py SPOKEN_FUNCTION and the keycap text; keep the lint's BARE_KEY_NAMES; check tests test_keys.py).
3. Trial 1: decide expect Hello vs hello (autocap did not fire on a fresh note in run 2 but did in run 1).
4. Run tests, git-commit-own, fast-forward push to origin plk/main, then rerun: cd /Users/djbclark/src/plektra-djbclark-ba14-run-wt/qa && ADB_TARGET=100.73.253.10:5555 just qa-run suites/smoke_chords.yaml --bar --record (background, log in scratchpad); djbclark is at the phone and has said to rerun now; he already agreed to recording for these runs but ask once more.
5. Later: MCP tool qa/plektra_qa/mcp_server.py bar parameter; productize as frdminc repo (/steps plan); session_log.py datetime crash in ~/src/djbclark-ade; djb-release.yml keystore-before-Gradle.

## Detached jobs
none

## Quick Start
`/baton` on chain standalone-f12a (/Users/djbclark/.local/state/handoffs/chains/standalone-f12a/SESSION_LOG.md). Tests: /opt/homebrew/bin/uv run --project /Users/djbclark/src/plektra-djbclark-ba14-run-wt/qa pytest /Users/djbclark/src/plektra-djbclark-ba14-run-wt/qa/tests -q
