---
schema_version: 1
handoff_id: 0916
parent_handoff_ids: [d41a]
lineage: inferred
chain: [standalone-f12a]
repo: plektra-djbclark
workspace: integration-wt
branch: plk/qa-harness-integration
head_sha: 9cd5a91b811eef78dcde4574585ec3b2c7430f49
created_at: 2026-11-25T19:23:32-05:00
writer: codex
---

# Handoff — Guided Plektra QA integration

## The Goal

Integrate the guided device QA harness, setup prompt, and short demo into the fork. Offer shared UI selectors upstream and resume feature QA once the harness is ready.

## Where We Are

[Draft fork PR #21](https://github.com/djbclark/plektra-djbclark/pull/21) targets `plk/main` at `afb037f16e8e027ec92297b385cf201f01d05668`. The integration branch is clean and published at `9cd5a91b811eef78dcde4574585ec3b2c7430f49`. It contains the QA branch plus the keyboard rollback changes and preserves the fork's current device facts. The shared Plektra checkout holds unrelated local changes.

The [published QA branch](https://github.com/djbclark/plektra-djbclark/tree/plk/qa-batch10-fixes/qa) contains the original tested harness and demo. [Upstream PR #230](https://github.com/pkb-rocks/plektra/pull/230) is open and ready for review at `03c2715f`. [ADE PR #1005](https://github.com/djbclark/djbclark-ade/pull/1005) merged the repo-root `qa/` discovery convention.

## What We Tried

1. The first integration snapshot left the Plektra runtime keyboard override set after changing modes in a guided trial. Astra blocked it. The first rollback fix could abandon cleanup when a slow toggle held the lock longer than the watcher waited. The final watcher restores screen settings immediately and keeps retrying keyboard rollback during contention.
2. PR #21's general Android CI failed during the application ID contract check. The check expects `rocks.pkb.plektra` and the fork builds `rocks.pkb.plektra.djb`. The base and PR head have identical app Gradle, variant validator, and Android workflow files. The QA-specific CI passed. [General CI failure](https://github.com/djbclark/plektra-djbclark/actions/runs/45727986224)
3. The ADB harness's plain hierarchy dump covered the typing bench. FIRERPA exposed the three IME selectors, and the native input-flow probe still found its two existing emoji tap targets. The PR body records the difference. [Selector evidence](https://github.com/pkb-rocks/plektra/pull/230)

## Key Decisions

Use `qa/README.md` and `qa/SETUP.md` for discovery and setup. Keep the guided harness alongside upstream's independent input flows. Open the fork integration as a draft and verify rollback on the device before it is ready to merge. Keep the Pin Notes support report as an unsent local draft.

The approved cleanup removed the side-by-side selector APK. The Titan is using Plektra djb and overlay 0.4.1. The installed Plektra debug receiver successfully cleared the forced hardware override and readback confirmed configured Auto. This directly checks the receiver on the handset. The new harness rollback remains CI-tested and awaits a driven handset run.

## Decisions

- [ruling] "It is okay if Plektra has 2 different QA harness implementations." (djbclark, 2026-11-25)
- [decision] Preserve the fork's main device-facts file in the integration; rejected: replace it with the feature branch copy; because the main copy includes the later emoji selector evidence; in [PR #21](https://github.com/djbclark/plektra-djbclark/pull/21).

## Relations

- continues [[HANDOFF_standalone-f12a_qa-batch10-feedback_2026-11-25_d41a]]
- decided_in [[HANDOFF_standalone-f12a_guided-qa-integration_2026-11-25_0916]]

## Evidence & Data

1. [Fork integration changes](https://github.com/djbclark/plektra-djbclark/pull/21/files) include the root README, `qa/SETUP.md`, `qa/Justfile`, the English and German documentation, the Android overlay, host harness, suites, tests, and reviewed MP4 and screenshots. The recent rollback code and tests are in `qa/plektra_qa/screen_gate.py` and `qa/tests/test_retry.py`.
2. Final integration CI passed 349 host tests, Android overlay tests, and the debug build. Astra approved exact head `9cd5a91b811eef78dcde4574585ec3b2c7430f49` after both rollback findings were fixed. [Final integration CI](https://github.com/djbclark/plektra-djbclark/actions/runs/45727598174)
3. The original handset demo had seven passing bench trials and a 122.655-second MP4. The optional Redo gesture was skipped in that take. [Reviewed demo](https://github.com/djbclark/plektra-djbclark/blob/plk/qa-batch10-fixes/qa/media/harness-demo.mp4)
4. The selector PR passed full upstream Android CI and the no-record Titan 2 Elite check. The three FIRERPA selectors and native probe outcomes are in the public device notes. [Android CI](https://github.com/pkb-rocks/plektra/actions/runs/45720485537) · [Device notes](https://github.com/djbclark/plektra-djbclark/blob/plk/main/qa/titan2-elite-facts.md)
5. [Collie #861](https://github.com/AltanS/collie/issues/861) is waiting for the next refusal. Capture the visible panel in ANSI with private text blanked and record whether the operator used Send or Enter. The tested Collie release and fork main were `d98f51ce7c`.

## Operator Feedback

The operator approved preparing the integration PR, drafting the Pin Notes report, correcting the local start-gate note, removing the selector APK, returning the keyboard to Auto, and handing off. Keep the next harness check focused on handset rollback, with a fresh start gate and an explicit recording choice.

## Where We're Going

1. **Next action.** In the existing integration workspace at `9cd5a91b`, confirm the phone is available and obtain a fresh explicit start for a short no-record rollback check. Exercise normal completion and the phone's shade Stop while the software keyboard is forced on. Read the runtime override and effective mode before and after each path. Restore the operator's starting state and record the results in the PR and QA notes.
2. Resolve the fork Android variant contract in a separate scoped change or obtain the maintainer's chosen treatment of the base mismatch before merging PR #21. Preserve the fork's app ID. The implementation files and CI run above show the mismatch.
3. Track upstream review of PR #230. Leave Collie capture queued until the next refusal. The Pin Notes draft and capture procedure are linked in the private Tier 1 pointer.
4. After harness validation, resume the recorded QA gaps in device-facts sections 10–12, including the symbol layer, initial autocapitalization and trackpad input. Use a focused batch rather than repeating the full earlier matrix.

## Detached jobs

None. The integration reviews and CI runs have returned. The open PRs are the durable tracking points.

## Quick Start

Open the integration workspace named in the Tier 1 pointer and check the exact branch and commit:

```sh
git status --short --branch
git rev-parse HEAD
just --justfile qa/Justfile qa-connect
```

Use the machine-local device configuration for the Titan 2 Elite and the existing authorized ADB transport. Use the fork's GitHub Actions overlay build for Android verification.
