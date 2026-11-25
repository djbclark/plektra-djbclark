---
schema_version: 1
handoff_id: d41a
parent_handoff_ids: [73ca]
lineage: deterministic
chain: [standalone-f12a]
repo: plektra-djbclark
workspace: plektra-qa-record
branch: plk/qa-record-default
head_sha: 40e9002f373d7dcd748c55122cf2aa68dce15a1d
created_at: 2026-11-25T15:00:00-05:00
writer: claude-code
---
# Handoff: 10-minute batch stopped early; fix the harness per djbclark's feedback

## The Goal
Guided phone QA that settles the lost-first-letter question and pending device checks, in ~10-minute batches.

## Where We Are
- 40e9002f (pushed to plk/main): QA facts section 10 item 6 (keyboard Tab in Pinnote goes body to the overflow button; no relaunch) and README rule 11 (~10-minute batches, EN+DE).
- Suites in ~/.local/state/bigteam/plektra-qa-record/: control_first_letter.yaml, control2_first_letter.yaml, batch10_20261125.yaml (28 trials).
- batch10 ran up to trial 25; djbclark stopped. The lead ended it with SIGTERM (exit 130). Only plektra-qa-20261125-143849.raw.mkv and its scrcpy.log exist in ~/plektra-qa-recordings: no mp4 was written (check why; salvage the mkv).
- Per-trial evidence: ~/plektra-qa-recordings/probe/batch10_20261125-20261125-143830/ (bench trials 4-25). Terminal output was copied to nowhere durable; the ledger is ~/.local/state/plektra-qa/ledger/batch10_20261125.json. Trials 1-3 (Pinnote first-letter control 2) results NOT yet read: check ledger and the mkv. This is the open first-letter question.
- The on-screen keyboard is hidden again (Ctrl+B sent by adb). software_keyboard_mode_runtime_override now reads force_hardware (it read force_virtual before; its pre-batch value is unknown).
- The recorder failed once at start ("scrcpy stopped at start", 14:32); cause unknown; a 4 s retest worked.

## First reading of results (not verified; do not report as keyboard bugs yet)
- Passed: T4, T5 (keyboard Tab on bench keeps first letter), T6 (quote autocap), T11, T16.
- T15 caused the on-screen keyboard: keys were Ctrl S, Ctrl, b, so Ctrl+B (toggle software keyboard) fired. T18/T19 ran with it up.
- Body-box first letter stayed lowercase in several trials (T7, T9, T10, T13, T14, T20, T22): possible autocap miss after the bench reset. Retry in a second app (Pinnote) first.
- T9 one-shot Shift and T8 double-space period worked apart from that; T21 Ctrl latch worked apart from that; T17 copy/paste worked apart from capitals (expected text assumed autocap).
- T12 typo from the ambiguous "type up". T7 stopped at "ok. " (the word "next" reads like the bar button). T8 typed "hide" (speech likely misread).
- T10 keys show Alt, not Shift (wrong key or naming). T13: "right ⇧ (Enter)" arrived as Shift: check the swap on the bench, and the renderer's key names.
- T14: w and z never appear in the app's key stream (typo or the intermittent keyboard fault: check getevent/mkv overlay).
- T20 "aa x" instead of "Aa xbb": check Ctrl+N and whether Ctrl stayed latched.
- T22/T23: trigger key + swipe showed a toast and the cursor did not move (string ids: screen_trackpad_permission_toast). Investigate (permission/Shizuku?) before any claim about PR 118.
- T25 was in progress at the stop. T26-T28 not run.

## Decisions
- [ruling] "We need to try to batch up more continuous things, the aim should be to optimize for around 10 minutes of human time per run." (djbclark, 2026-11-25)
- [ruling] (djbclark, 2026-11-25, after stopping batch10) "in future we should give each test a number like T1...T10...TN"; "you should use \"space\", not \"blank\""; "\"type up\" doesn't make sense. Up arrow somehow?" and "if arrow is required, say how to invole it from the keyboard"; "avoid using numbers or other things that can be ambigous as test words" (chose: "type one" means the word); "check state of [the on-screen keyboard] after every test and make sure it is shown or not shows as appropriate"; "Do this in an efficient, fast way."

## Blockers and open questions
- Claude weekly quota low (reset Fri 2026-11-27 05:00 EST); codex weekly had 95% headroom at 14:35.
- djbclark's start press for each batch.

## Evidence & Data
aiuse at 14:35: openai:codex 95, google:gemini 73. Facts: qa/titan2-elite-facts.md section 10.

## Where We're Going
1. NEXT: harness changes (dispatch with route_agent plan --kinds code, likely codex; own claimed worktree off plk/main): (a) number every trial T1..Tn in bar, shade, voice, terminal; (b) after every trial read the on-screen keyboard state (pref software_keyboard_mode_runtime_override and IME view) and restore what the next trial needs, report a change; (c) allow the word "space" in suite text (keys.py BARE_KEY_NAMES rejects "Space"); (d) arrow steps say how to type them; (e) retry recorder start once; write the mp4 on Stop/SIGTERM. Tests: uv run --python 3.14 pytest tests -q in qa/ via bgrun.
2. Rewrite batch10 wording (no "up"/"next"/"one"/digits as words, "space"), fix expectations from the results above, avoid Ctrl chords next to B.
3. Read trials 1-3 results and report the first-letter answer.
4. Check autocap at bench body start in Pinnote; check trackpad toast; check T10/T13 key naming.
5. Handoff 73ca items 2-5 (file the Pinnote finding after a duplicate search; replace the private device address in qa/tests/test_fixes.py).
6. LAST: make as much QA work algorithmic as reasonable.

## Detached jobs
none

## Quick Start
`git -C ~/src/plektra-djbclark-ba14-run-wt fetch -q origin && git -C ~/src/plektra-djbclark-ba14-run-wt merge --ff-only origin/plk/main`
