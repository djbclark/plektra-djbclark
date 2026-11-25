---
schema_version: 1
handoff_id: 73ca
parent_handoff_ids: [ec59]
lineage: deterministic
chain: [standalone-f12a]
repo: plektra-djbclark
workspace: plektra-qa-record
branch: plk/qa-record-default
head_sha: c00a279aeb089749aff48c68b819ae6d2b76f1c8
created_at: 2026-11-25T14:02:14-05:00
writer: claude-code
---
# Handoff — QA speech fix built; next is the first-letter report and control

## The Goal
Settle the dropped-first-letter observation and trial 6 with the phone QA harness. djbclark's newest ask (faster, interruptible opening speech) came first and is done.

## Where We Are
- d6e45b0a and c00a279a on plk/main: the start instruction is read at 1.5x; the person's start cuts it. Done was ignored while the voice played (the start gate pushed its settle time forward during speech); fixed. Done/Next/Stop in a trial and Done/Prev on the end card cut speech too.
- Exact git state: writer and run worktrees at c00a279a before this handoff commit, both clean. Main checkout ~/src/plektra-djbclark is on plk/pr-autocorrect-lite with a pre-existing modified .gitignore and untracked .codex/ (not touched).
- Files changed: qa/plektra_qa/{speech,start_gate,hitl_runner,recording}.py, qa/tests/ (8 files), qa/README.md, qa/README.de.md.
- Tests: `uv run --python 3.14 pytest tests -q` in qa/ via bgrun: 336 passed (both commits). Measured on this Mac: Kokoro 1.50x; macOS say -r 262 gave 1.43x, -r 282 gives 1.50x.
- Not verified: nobody has heard it in a run on the phone. With Kokoro, an opening text not yet cached is spoken by macOS say (older behaviour), now at 1.5x.
- Fork issue 19 updated (body line and dated comment). Claude memory note updated.
- No phone run, no sub-agent, nothing live. The writer-worktree claim was released at handoff: claim it again before editing.

## What We Tried
- A code sub-agent: `route_agent.py plan --kinds code` returned no pool (Claude weekly about 4%; other pools ranked too weak for code). The lead wrote the change.
- `say -r 262` (175 x 1.5) measured 1.43x; replaced by 282.

## Key Decisions
Kokoro renders the faster line with `--speed` under its own cache key (rejected: playing the normal file faster with afplay -r, which the recording's voice track would not match).

## Decisions
- [ruling] "We talk at 1.5x for the opening monologue, and also hitting done should end it immediatly so human does not have to wait for speach to end" (djbclark, 2026-11-25)
- [ruling] "1. I write it now, here" (djbclark, 2026-11-25, who writes the speech fix with Claude quota tight)
- [ruling] "10 times is too many times to have a human do the same thing unless we really think that is neccisary. Limit to 3 max on a regular basis." (djbclark, 2026-11-25)
- [ruling] "we need to tell the user to wait or that the phone may be used normally now, depending on if there is another batch coming up or not." (djbclark, 2026-11-25)
- [ruling] "at end of queue, we want to make as much as reasonable be algorithmic instead of AI based, as that will likely be faster and use less tokens." (djbclark, 2026-11-25)

## Blockers and open questions
- Claude weekly quota about 4% on 2026-11-25 13:55 EST; reset Fri 2026-11-27 05:00 EST.
- djbclark's start press for each phone batch. No BLOCKED sub-agent reports.

## Relations
- continues [[HANDOFF_standalone-f12a_qa-first-letter-cause_2026-11-25_ec59]]
- decided_in [[HANDOFF_standalone-f12a_qa-speech-fast_2026-11-25_73ca]]

## Evidence & Data
- First-letter finding: qa/titan2-elite-facts.md section 10; recordings ~/plektra-qa-recordings/plektra-qa-20261125-134416.mp4 (Plektra trial 6), -134708.mp4 (Kika), -131523.mp4 (bench).
- A/B suite: ~/.local/state/bigteam/plektra-qa-record/ab_first_letter.yaml (PLEKTRA_QA_PACKAGE=com.iqqijni.bbkeyboard after `adb shell ime set` to Kika; set Plektra back).

## Operator Feedback
At most 3 repeats. End each batch with the phone state. Prefer scripts over AI turns (queued last). Runs record by default; a batch waits for djbclark's start. Claude Code outside Paseo: local handoff only; djbclark runs /new and /baton.

## Where We're Going
1. NEXT: report the first-letter finding if not yet read (final reply of 2026-11-25 14:0x gave it) and ask djbclark whether to run the control: 3 tries in Pinnote with a Tab from a key on the keyboard device (right ⇧ under Plektra's swap, tapping only that key; expected: no loss). It also confirms a user workaround. The first such run is a chance to hear the 1.5x speech and test Done cutting it.
2. File the finding after a duplicate search: Pinnote (find its repo; missing configChanges, lost input on relaunch), and add the fact to upstream device-facts PR pkb-rocks/plektra#205 (respect the upstream holds memory). Consider whether Plektra can or should mitigate it.
3. Move smoke_chords phase two to an app that handles keyboard configuration changes (the bench), so trial 6 tests the keyboard.
4. Still unverified: a keyboard-sent sendKeyEvent gives no "pre" key line on the bench; the end card and long start cards as seen on screen.
5. Ask whether to replace the private-network device address in qa/tests/test_fixes.py (public fork; history keeps it).
6. Later list: HANDOFF_standalone-f12a_qa-redo-buttons_2026-11-25_b139.md item 5.
7. LAST: make as much QA work algorithmic as reasonable.

## Detached jobs
none

## Quick Start
`git -C ~/src/plektra-djbclark-ba14-run-wt fetch -q origin && git -C ~/src/plektra-djbclark-ba14-run-wt merge --ff-only origin/plk/main`
