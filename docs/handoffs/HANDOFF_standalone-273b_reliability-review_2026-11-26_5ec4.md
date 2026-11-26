---
schema_version: 1
handoff_id: 5ec4
parent_handoff_ids: [7c52]
lineage: deterministic
chain: [standalone-273b]
repo: plektra-djbclark
workspace: plektra-djbclark
branch: plk/pr-autocorrect-lite
head_sha: b3cf551b2fa04ba15f2ee46c72bf5c1195b3d0ca
created_at: 2026-11-26T10:21:05-05:00
writer: codex
---

# Handoff — Plektra reliability review

## The goal

Follow upstream reviewer priorities. Complete the first Flux reliability review and preserve a settings-only fix for upstream consideration.

## Where we are

Both review pins matched on November 26: upstream main `fac3a5b95825b837e48f0a7ebd75010de40b9b9c` and Flux integration `7a50087ac61519c6c26368830593258e599b4725`. Three isolated commits are ready for separate review: link-handler fallback `c0087d48`, next-word store fallback `b826f6b5`, and UI test waits `df56c7b1`. The original atomic-saves commit `93b617f0` needs changes because concurrent saves share a staging pathname. Upstream already has an independent streaming dictionary installer.

The settings-only alternative is [f2a747b2](https://github.com/djbclark/plektra-djbclark/commit/f2a747b242e9790a76c5e77981c538626b47a258) on `plk/fix-atomic-settings`. Five production files cover the four settings files, including both user-default initializers, plus a test file and topic registration. The branch is clean. The reference checkout is anchored above and has a pre-existing .gitignore edit.

## What we tried

A 25-second Actions watcher was rejected by a local hook before execution. A one-shot status loop with 60-second spacing verified completion. The full Stable Debug suite passed in 7m 18s with the fork's existing CornerButtonThemeTest exclusion. The previous planning turn prepared an audit and transfer plan only.

## Key decisions

Keep the other Flux slices parked and leave upstream submission and device QA pending operator direction. The summary email and proposed PR body are drafts.

## Decisions

- [decision] Preserve the upstream dictionary installer and port the settings writes separately; rejected: port the atomic-saves commit wholesale; because the shared staging name races and upstream has its own validated dictionary installer.

## Relations

- continues [[HANDOFF_standalone-273b_upstream-review_2026-11-26_7c52]]
- decided_in [[HANDOFF_standalone-273b_reliability-review_2026-11-26_5ec4]]

## Evidence and data

[Fork CI](https://github.com/djbclark/plektra-djbclark/actions/runs/45842404292) verified concurrent publication, partial-write failure and cleanup, late initialization, and missing-asset fallback. Source review found no equivalent upstream protection for the remaining settings writers. The upstream atomic-settings issue and PR searches found no match.

## Operator feedback

Preserve the private drafts. Take no outbound action without separate authorization.

## Where we're going

1. Verify the fork head and CI once, verify whether any upstream PR has appeared for the branch, then await operator direction on delivery, submission, device QA, or next review scope. Review drafts and local paths in the exact Tier 1 chain.
2. Keep [Plektra PRs #231](https://github.com/pkb-rocks/plektra/pull/231), [#232](https://github.com/pkb-rocks/plektra/pull/232), [#233](https://github.com/pkb-rocks/plektra/pull/233) and [HandQA PR #1](https://github.com/frdminc/handqa/pull/1) as context.

## Detached jobs

None.

## Quick start

```sh
git ls-remote origin refs/heads/plk/fix-atomic-settings
gh run view 45842404292 -R djbclark/plektra-djbclark --json status,conclusion
gh pr list -R pkb-rocks/plektra --head djbclark:plk/fix-atomic-settings --state all --json number,state,url
```
