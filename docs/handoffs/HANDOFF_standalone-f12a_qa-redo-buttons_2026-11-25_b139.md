---
schema_version: 1
handoff_id: b139
parent_handoff_ids: [fbdd]
lineage: deterministic
chain: [standalone-f12a]
repo: plektra-djbclark
workspace: plektra-qa-record
branch: plk/qa-record-default
head_sha: fae31ad5ba82c2296734041b64dba8e1960fd6ae
created_at: 2026-11-25T09:57:20-05:00
writer: claude-code
---
# Handoff — QA harness: record by default, Redo buttons, second-app check

## The Goal
A phone QA harness djbclark can drive from the on-screen button bar: recorded by default (with an opt-out), redoable trials, quick response. Then settle the dropped-letter observation and the one pending smoke trial.

## Where We Are
- Pushed to origin plk/main: 4e56ae68 (record by default, Redo last), 63f2daa6 (docs), acdc1462 (suite fix), fae31ad5 (Redo = this trial, "◀ Prev" = previous, wait card, small buttons, base-state reset, trial-6 read fix, tab_first_letter suite, two-field screen in the bar app).
- Bar app release overlay-app-20261125.1453-fae31ad5 installed on the Titan 2 (install Success, SHA-256 matched).
- Tests: 213 Python passed at fae31ad5 (run again by the lead via bgrun); Kotlin unit tests passed in the GitHub build.
- Nothing in fae31ad5 has run on the phone.
- Worktrees: writer ~/.local/state/bigteam/plektra-qa-record/wt (clean); run copy ~/src/plektra-djbclark-ba14-run-wt (plk/qa-smoke-ba14, fast-forwarded to fae31ad5). Main checkout's .gitignore and .codex/ changes predate this session: leave them.

## What We Tried
- A run from the writer worktree stopped at "FIRERPA_HOST is required" (nothing ran). Runs from the ba14 worktree work with only ADB_TARGET set.
- A run was refused while the phone was locked.
- On acdc1462, trial 6 could not pass: it read the note body while the text was in the title, and redo cleared only the body. Both fixed in fae31ad5. That recorded fail says nothing about the keyboard.

## Key Decisions
Redo restores the trial's start state (fresh note where the phase has a new-note button), because clearing one field left text in the title. The second app is a two-field screen in the bar app, because no suitable second app exists on plk/main (KeyProbe is only on plk/key-probe).

## Decisions
- [decision] wire "retry" is Redo and wire "redo" is "◀ Prev"; rejected: renaming the old wire; because older apps and hosts keep working; in the sub-agent report below.
- [ruling] "actually set recoding on by default for the harness overall, with options/prompting to not do it" (djbclark, 2026-11-25)
- [ruling] "In cases like this we should always try again in a seperate app to make sure we are not running into an app bug." (djbclark, 2026-11-25)
- [ruling] "We should also have the Redo button on the test of the screen itself, to reset it to initial conditions to try again. We can make the buttons pretty small to conservice space; each of them being a different color was a good idea, keep doing that." (djbclark, 2026-11-25)

## Relations
- continues [[HANDOFF_standalone-f12a_qa-bar-reruns_2026-11-24_fbdd]]
- decided_in [[HANDOFF_standalone-f12a_qa-redo-buttons_2026-11-25_b139]]

## Evidence & Data
- Dropped B: recording plektra-qa-20261125-091453.mp4 in ~/plektra-qa-recordings. B is in getevent after the square key, title read "Eta" in two attempts, one run, Pinnote only. Cause unverified; qa/titan2-elite-facts.md section 10. Section 7 item 5 (square key then a key changes keyboard configuration and relaunches some apps) is a candidate, not checked for Pinnote.
- Latency before the change: press to next card median about 2.1 s. Gain after the change not measured.
- Sub-agent report (unverified list, decisions): ~/.local/state/agent-reports/1de0d4b0-9641-42ce-8cf3-208f23603e7e/qa-bar-retry-small-buttons-wait-card-report.md
- Unverified on the phone: bar look and height, harness taps passing through the wait card, new FIRERPA clipboard and hierarchy calls, Back leaving a Pinnote note, Tab moving between the two new fields, Redo, Prev, No rec, wait card, tab_first_letter suite.

## Operator Feedback
Runs record by default; announce it and offer no recording (No rec on the bar, --no-record). A batch waits for djbclark's start press. Name keys as "your X" plus keycaps. Suspected keyboard bugs are retried in a second app. "That test went away without my finishing" and a redo that leaves old text are both bugs he expects fixed.

## Where We're Going
1. Tell djbclark the new bar is installed and ask (question card, option 1 = run recorded now, option 2 = run without recording) to unlock the Titan 2 and start the second-app check: in background, from ~/src/plektra-djbclark-ba14-run-wt/qa, `env ADB_TARGET=<phone adb address from ~/.config/plektra-djbclark/local.toml> just qa-run suites/tab_first_letter.yaml` (add `--no-record` for option 2). Steps per try: tap □ or right ⇧ (your Tab), check the cursor moved to the upper field, type beta, tap Done; 10 tries, about 2 s apart; Redo repeats a try, ◀ Prev the one before, Stop ends.
2. Then `suites/smoke_chords.yaml` (only trial 6 pending). Report which app reproduced any lost letter; do not blame the keyboard unless the second app shows it.
3. Fix whatever the run shows about the new buttons.
4. Update the status issue https://github.com/djbclark/plektra-djbclark/issues/19 for the QA harness changes if it tracks the harness (project rule 6.5).
5. Later: remove the writer worktree and branch plk/qa-record-default; prune the stale git worktree entry the CI build script leaves per run (and fix the script on ci/base); session_log.py datetime crash fix in ~/src/djbclark-ade; djb-release.yml keystore-before-Gradle hardening; salvage of raw .mkv recordings and host-side original settings (harness caveats); productize the harness as a public frdminc repo (/steps plan first).
6. A note about bgrun refusing a bare test directory was sent to LEAD 86df0a5f-33ea-4365-999c-74ab63cc173a on 2026-11-24; no ack seen.

## Detached jobs
none

## Quick Start
`git -C ~/src/plektra-djbclark-ba14-run-wt fetch -q origin && git -C ~/src/plektra-djbclark-ba14-run-wt merge --ff-only origin/plk/main`
Tests: from qa/, `~/ops/site-private-djbclark/bin/bgrun uv run pytest -q --no-header <every tests/test_*.py named>`
