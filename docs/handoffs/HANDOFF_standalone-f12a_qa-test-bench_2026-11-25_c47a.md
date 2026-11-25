---
schema_version: 1
handoff_id: c47a
parent_handoff_ids: [b139]
lineage: deterministic
chain: [standalone-f12a]
repo: plektra-djbclark
workspace: plektra-qa-record
branch: plk/qa-record-default
head_sha: 12045283bbf2a80b19bbca0b5a31023a06676c93
created_at: 2026-11-25T11:30:31-05:00
writer: claude-code
---
# Handoff — QA test bench built and installed, not yet run

## The Goal
A button-bar-driven phone QA harness, then settle the dropped-letter observation and trial 6.

## Where We Are
- plk/main has 12045283: test-bench screen (nine boxes in Tab order, live telemetry, QAPROBE log lines, instant reset by intent, per-try verdict match / typo / keyboard-or-app / unknown), on top of fae31ad5 (Redo, Prev, wait card).
- Release overlay-app-20261125.1625-12045283 (app 0.3.0) installed on the Titan 2 (SHA-256 matched, install Success).
- 253 Python tests pass (sub-agent and lead); Kotlin tests passed in CI. Nothing from fae31ad5 or 12045283 has run on the phone.
- Both worktrees at plk/main and clean: writer ~/.local/state/bigteam/plektra-qa-record/wt, run copy ~/src/plektra-djbclark-ba14-run-wt. No sub-agent is running.
- Status issue 19 updated with a dated comment.

## What We Tried
See parent handoff b139. Self-handoff refused under Herdr (no PASEO_AGENT_ID: "a full UUID is required").

## Key Decisions
The bench lives in the bar app (one install). It is an instrument only: a suspected keyboard bug is still retried in a real app. A typo is claimed only when the key evidence proves it, otherwise unknown.

## Decisions
- [ruling] "Consider if we should have our own screen for testing and text entry etc. Might be good to show additional telemitry and info about what is going on." (djbclark, 2026-11-25)
- [ruling] "It would also help you see what typos the human made" (djbclark, 2026-11-25)
- [ruling] "Just do the handoff locally, I will manually do the /new /baton when needed." (djbclark, 2026-11-25)
- [decision] bench screen in the bar app, replacing the two-box screen; rejected: a separate app; because one APK and one install; decided in the sub-agent report below.

## Relations
- continues [[HANDOFF_standalone-f12a_qa-redo-buttons_2026-11-25_b139]]
- decided_in [[HANDOFF_standalone-f12a_qa-test-bench_2026-11-25_c47a]]

## Evidence & Data
- Report with how-to-run, unverified list and DECIDED items: ~/.local/state/agent-reports/1de0d4b0-9641-42ce-8cf3-208f23603e7e/qa-test-bench-screen-with-telemetry-report.md
- To check on the first run: the keys-pressed evidence assumes keys sent by the keyboard skip the pre-IME stage (read in SDK source only): a sendKeyEvent from the keyboard must not produce a "pre" key line. Also layout under the bar, Tab order, "am start" of the DUMP-guarded activity, password box reading as dots.
- Dropped B and trial 6 background: parent handoff.

## Operator Feedback
Runs record by default; offer no recording. A batch waits for djbclark's start. Name keys as "your X" plus keycaps. Retry suspected keyboard bugs in a second app. Claude Code outside Paseo: local handoff only.

## Where We're Going
1. Ask djbclark (question card: 1. recorded, 2. not recorded; steps inside the card) to unlock the Titan 2 and start, in background from the run copy's qa/: `env ADB_TARGET=<phone adb address from ~/.config/plektra-djbclark/local.toml> just qa-run suites/tab_first_letter.yaml` (`--no-record` for option 2). Per try: tap □ or right ⇧ (your Tab), check where the cursor went, type beta, tap Done on the bar; 10 tries.
2. Read the per-try probe files under ~/plektra-qa-recordings/probe/ and check the unverified items.
3. `suites/smoke_chords.yaml` (only trial 6 pending).
4. djbclark-ade handoff fix: LEAD 86df0a5f-33ea-4365-999c-74ab63cc173a dispatched worker 274dcb2e-53da-48c8-b86b-b904799419d4 (Carried #122); no action here.
5. Later list: parent handoff item 5 (remove writer worktree, stale CI worktree entry, session_log.py crash, release keystore hardening, recording salvage, productize harness with a /steps plan first); bgrun note to the same LEAD from 2026-11-24, no ack seen.

## Detached jobs
none

## Quick Start
`git -C ~/src/plektra-djbclark-ba14-run-wt fetch -q origin && git -C ~/src/plektra-djbclark-ba14-run-wt merge --ff-only origin/plk/main`
