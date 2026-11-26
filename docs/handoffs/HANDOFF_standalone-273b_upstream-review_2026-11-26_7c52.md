---
schema_version: 1
handoff_id: 7c52
parent_handoff_ids: []
lineage: none
chain: [standalone-273b]
repo: plektra-djbclark
workspace: plektra-djbclark
branch: plk/pr-autocorrect-lite
head_sha: b3cf551b2fa04ba15f2ee46c72bf5c1195b3d0ca
created_at: 2026-11-26T09:23:07-05:00
writer: codex
---

# Handoff — Plektra upstream review

## The goal

Spend review and device QA effort on upstream priorities. Keep overlapping fork features parked while upstream changes settle.

## Where we are

Source review of the three requested changes is complete. The double-space candidate passed six device cases. The popup recovery needs a failed-show regression case, and the long-field test needs to reach the fixture boundary. The broad Flux work has been scoped into seven review slices, with dependencies, effort ranges, and device needs recorded in the working queue.

The reference checkout is anchored above and has a pre-existing `.gitignore` edit. This handoff is the only file added to the dedicated documentation branch. The narration fix in the separate HandQA repository affects `handqa/hitl_runner.py`, `handqa/recording.py`, their speech and recording tests, and the README. The prior guided-QA chain was checked and is archived. This upstream-review scope started independently.

## What we tried

The first ordinary-word baseline sample was inconclusive at 545 ms between space presses. Three timely paired guard cases reproduced the baseline behavior and passed on the candidate. Both builds preserved post-period capitalization with automatic suggestion spacing on and off. The probe logs preserve these observations. Several earlier recording batches retain raw video and probe logs after interrupted muxing.

The first narration patch could prolong capture after Stop. The reviewed fix stops capture before handback and caps the recorded narration at the capture timestamp.

## Key decisions

Keep this handoff focused on review. Use the scoped queue to identify work upstream wants. A wider review depends on maintainer direction. Preserve the existing device results and their limitations.

## Decisions

- [decision] Scope the broad Flux branch before reviewing individual slices; rejected: review the whole integration branch as one change; because the full Git inventory reveals overlapping upstream work and independent fixes.

## Relations

- decided_in [[HANDOFF_standalone-273b_upstream-review_2026-11-26_7c52]]

## Evidence and data

The full Git comparison between upstream `fac3a5b95825b837e48f0a7ebd75010de40b9b9c` and Flux `7a50087ac61519c6c26368830593258e599b4725` found 135 Flux-only commits and 617 base-relative changed paths. The exact upstream and candidate build comparison used `fac3a5b95` and `e9c594a7`. The [device build and focused tests](https://github.com/djbclark/plektra-djbclark/actions/runs/45819696847) passed.

[HandQA PR 1](https://github.com/frdminc/handqa/pull/1) is open at `233bd7430f94d4513b9deda93e009e4660b7ba37`. The before-and-after run passed 354 Python tests. Standard PR Python and Android overlay checks passed. [Regression evidence](https://github.com/frdminc/handqa/actions/runs/45834122817)

## Operator feedback

Device QA with a new runner requires demonstrated parity with the existing workflow. The completed qualification covered seven trials, Redo, Stop, and phone handback. HandQA narration should continue while the operator types, and a new prompt can replace the active line.

## Where we're going

1. Refresh the upstream refs and compare them against the saved scoped queue and current maintainer requests. Start with the popup and long-field regression cases if still requested and applicable. Keep the wider Flux review at the scope boundary until a slice is selected.
2. Check the HandQA PR for new review or check results. Its current head has CI evidence and the live device comparison used the previous harness.

## Detached jobs

None. The completed qualification runner is archived.

## Quick start

```sh
git status --short --branch
git ls-remote upstream refs/heads/main
gh pr view 1 --repo frdminc/handqa --json state,headRefOid,reviewDecision,statusCheckRollup
```
