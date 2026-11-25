---
schema_version: 1
handoff_id: ec59
parent_handoff_ids: [c47a]
lineage: deterministic
chain: [standalone-f12a]
repo: plektra-djbclark
workspace: plektra-qa-record
branch: plk/qa-record-default
head_sha: bac40e24a0775fc607167745f508b4f785d50d0b
created_at: 2026-11-25T13:50:15-05:00
writer: claude-code
---
# Handoff — lost first letter traced to the app relaunching; new QA batch rules landed

## The Goal
Settle the dropped-first-letter observation and trial 6 with the phone QA harness.

## Where We Are
- Settled as far as tested: in Pinnote the first letter after the vendor Tab (printed Recent key) is lost with the stock Kika keyboard (3 of 3: `Ta`, `Eta`, `Eta`) and with Plektra (trial 6 today 1 of 1, plus two attempts in an earlier run). The test bench kept it 10 of 10 with Plektra. Pinnote's note editor declares no `configChanges` and was relaunched twice per try. Not Plektra-specific; the relaunch is the likely cause. Recorded in `qa/titan2-elite-facts.md` section 10 (bac40e24).
- 40b2606b: at most 3 tries of one action unless a reason is stated; every batch ends with "phone is free" or "wait"; `just qa-release`. Worker ran 330 Python tests; lead reran 80, passed. Both end messages and the release command ran on the phone without error; nobody confirmed seeing the card.
- plk/main and both QA worktrees level (writer ~/.local/state/bigteam/plektra-qa-record/wt, run copy ~/src/plektra-djbclark-ba14-run-wt). Phone keyboard restored to Plektra and the phone released. No sub-agent or run is live.
- Fork status issue 19 updated for the harness change.
- Project AGENTS.md items 8.1 and 10 and Claude memory notes carry the new rules.

## What We Tried
- First bgrun rerun with `uv --directory` was refused (names another tree); running it with the worktree as cwd worked.
- Bare `sed` was refused by a hook; use `gsed`.
- Not run: Pinnote with a Tab from a key on the keyboard device (the control that would confirm the cause).
- Key press times were not lined up with the relaunch log lines.

## Key Decisions
The second A/B batch (Plektra arm) was dropped after Kika failed 3 of 3: Plektra losses were already on record, and djbclark wants few repeats. smoke_chords trial 6 is left failing: it measures the app relaunch, not the keyboard.

## Decisions
- [ruling] "10 times is too many times to have a human do the same thing unless we really think that is neccisary. Limit to 3 max on a regular basis." (djbclark, 2026-11-25)
- [ruling] "we need to tell the user to wait or that the phone may be used normally now, depending on if there is another batch coming up or not." (djbclark, 2026-11-25)
- [ruling] "at end of queue, we want to make as much as reasonable be algorithmic instead of AI based, as that will likely be faster and use less tokens." (djbclark, 2026-11-25)
- [decision] skip the Plektra arm of the A/B; rejected: running three more tries; because Plektra losses were already recorded.

## Relations
- continues [[HANDOFF_standalone-f12a_qa-test-bench_2026-11-25_c47a]]
- decided_in [[HANDOFF_standalone-f12a_qa-first-letter-cause_2026-11-25_ec59]]

## Evidence & Data
- Recordings: ~/plektra-qa-recordings/plektra-qa-20261125-134416.mp4 (trial 6, Plektra), -134708.mp4 (Kika), -131523.mp4 (bench).
- Bench event files: ~/plektra-qa-recordings/probe/tab_first_letter-20261125-131447/
- A/B suite: ~/.local/state/bigteam/plektra-qa-record/ab_first_letter.yaml (run with PLEKTRA_QA_PACKAGE=com.iqqijni.bbkeyboard after `adb shell ime set` to Kika; set Plektra back afterwards).
- Harness report: ~/.local/state/agent-reports/f49dfcbc-0352-41e8-a3d2-bcaa9b1988dc/qa-reps-endstate-report.md

## Operator Feedback
At most 3 repeats. End each batch with the phone state. Prefer scripts over AI turns (queued last). Runs record by default and a batch waits for djbclark's start. Claude Code outside Paseo: local handoff only; djbclark runs /new and /baton.

## Where We're Going
1. Report the finding and ask djbclark whether to run the control (3 tries in Pinnote with a keyboard-device Tab, for example right ⇧ under Plektra's swap, tapping only that key; expected: no loss). It would also confirm a user workaround.
2. File it where it belongs, after a duplicate search: Pinnote (find its repo; missing configChanges / lost input on relaunch), and add the fact to upstream device-facts PR pkb-rocks/plektra#205 (respect the upstream holds memory). Consider whether Plektra can or should mitigate it.
3. Move smoke_chords phase two to an app that handles keyboard configuration changes (the bench), so trial 6 tests the keyboard.
4. Still unverified: a keyboard-sent sendKeyEvent must give no "pre" key line on the bench; the end card and long start cards as seen on screen.
5. Ask whether to replace the private-network device address in qa/tests/test_fixes.py (public fork; history keeps it).
6. Later list: HANDOFF_standalone-f12a_qa-redo-buttons_2026-11-25_b139.md item 5.
7. LAST: make as much QA work algorithmic as reasonable (preflight, run summary, relaunch check, phone messages).

## Detached jobs
none

## Quick Start
`git -C ~/src/plektra-djbclark-ba14-run-wt fetch -q origin && git -C ~/src/plektra-djbclark-ba14-run-wt merge --ff-only origin/plk/main`
