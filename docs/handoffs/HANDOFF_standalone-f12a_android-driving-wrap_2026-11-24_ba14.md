---
schema_version: 1
handoff_id: "ba14"
parent_handoff_ids: ["0019"]
lineage: deterministic
chain:
  - standalone-f12a
repo: plektra-djbclark
workspace: plektra-djbclark
branch: plk/main
head_sha: f60b42fe472b96649c58956170c27f90cad0b926
created_at: 2026-11-24T14:01:34.899+00:00
writer: claude-code
---

# Handoff — Android driving alternatives closed, guided QA pass next

## The Goal

Run the guided physical-keyboard check on the Titan 2 Elite with the voice QA harness (`smoke_chords`, then the autocap 15/16 retest), with djbclark holding the phone. This session first settled which Android-driving tools we use, and found why the phone keyboard dies now and then.

## Where We Are

- Driving stack decided: FIRERPA 10.12 (control, gRPC) + scrcpy 5.0.1 (view-only mirror, `--no-control`) + plain adb (logs). Nothing else adopted.
- FIRERPA is running on the phone with the patched accessibility-coexistence driver. After a re-deploy (djbclark approved 2026-11-24) the phone's server certificate is this Mac's `certs/lamda.pem`; `just status`, `just grpc` (FIRERPA v10.12) and the SSH check all pass. Device address and serial are in `~/.config/plektra-djbclark/local.toml`.
- `qa/README.md` and `qa/README.de.md` section 11 items 6-8 record: uiautomator dump conflicts with FIRERPA (one UiAutomation slot), the tools tried and rejected (Mobile MCP, Mobilerun, Vercel UI SDK, uiautomator2 untried), and the Android 16 restart gotcha. On `origin/plk/main` as f60b42fe.
- djbclark removed the Notch-Touch accessibility service. `dumpsys accessibility` now shows only `[MotionEventInjector]`; no bound service declares key filtering. Animation scales are 1.0.
- Tests: none run (builds and Gradle are CI-only). Live checks only: FIRERPA status/gRPC, a 100 s raw key capture, a 40 s scrcpy run.
- Git: main checkout is on `plk/pr-autocorrect-lite` (b3cf551b) with `.gitignore` modified and `.codex/` untracked; both belong to others, leave them. This handoff was written in its own worktree off origin/plk/main.
- Files changed this session: `qa/README.md`, `qa/README.de.md`, memory file `t2e-keyboard-intermittent-fault-evidence.md` (Claude project memory, not the repo).

## What We Tried

- Mobile MCP 1.0.19, both modes. With FIRERPA running both fail (`uiautomator dump` prints `Killed`); with FIRERPA stopped both work (about 4 s legacy, about 12 s default). Rejected: it only duplicates FIRERPA, and its typing tool bypasses the keyboard under test. Trial left no dex, DeviceKit or daemon on phone or Mac.
- `just start` failed repeatedly with "UIAutomation helpers did not start". Cause: on Android 16 `/proc/PID/maps` names the driver `[anon:dalvik-classes.dex extracted in memory from @aab.zip]`, so `driver_pids()` in `firerpa_lifecycle.py` never matches. Filed as [stayturgid#344](https://github.com/djbclark/stayturgid/issues/344), no fix PR. Workaround: copy `overrides/aab.zip.patched` over the active `aab.zip` (temp, then `mv -f`), kill only the `lamda` pids whose maps contain `@aab.zip`, wait for respawn, then `just status`. Expect to repeat it after every `just start` or `just deploy`.
- gRPC TLS failed on a certificate mismatch (phone July cert vs host cert). Fixed by `just deploy`.
- Keyboard fault: the raw capture saw all keys and text appeared, so hardware and driver were fine in that window. Not yet reproduced while failing.

## Key Decisions

Skip every pasted alternative; keep FIRERPA + scrcpy + adb. Re-deploy FIRERPA with this Mac's certificate rather than leaving the mismatch. File the lifecycle bug as an issue only, not a fix. Treat the accessibility key-event filter (Notch-Touch) as the leading suspect for the keyboard fault, with it now removed as the test.

## Decisions

- [decision] Use FIRERPA, scrcpy view-only and adb for Android driving; rejected: Mobile MCP, Mobilerun, Vercel UI SDK; because Mobile MCP only duplicates FIRERPA and conflicts with it on the single UiAutomation slot.
- [decision] Re-deploy FIRERPA with the Mac's `certs/lamda.pem`; rejected: leaving the July device certificate; because the harness's gRPC connection failed with it.
- [ruling] "Oh actually wait if we need to uninstall lamda temporarily to test things that is fine." (djbclark, 2026-11-24)
- [ruling] "Re can revisit this after we finish the android driving alternatives; do /steps on that" (djbclark, 2026-11-24)
- [ruling] "I removed touch-notch from accessability." (djbclark, 2026-11-24)

## Relations

- continues [[HANDOFF_standalone-f12a_qa-voice-prompts_2026-11-15_0019]]
- decided_in [[HANDOFF_standalone-f12a_android-driving-wrap_2026-11-24_ba14]]

## Evidence & Data

- Raw capture: 26 key events (A, B, C, Space, Enter twice, then Shift+Alt+A) from the TitanKey input device, text appeared normally.
- Before the change: Enabled features `[KeyboardInterceptor,MotionEventInjector]`, InputFilterEnabled true; Notch-Touch declared capabilities 41. After: `[MotionEventInjector]`.
- FIRERPA v10.12, patched aab.zip hash ac0dcbf8d7528aae7770e8d8387152991d5a4690c45b37f9f8ab24a1a9139e2e.
- Context-nudge failure: hard handoff nudge fired at 219,703 tokens (13:40Z) and a boundary nudge at 189,295 tokens (13:33Z) mid-turn; this session ran to about 265k and auto-compacted without handing off. Reported to LEAD djbclark-ade (agent 86df0a5f-33ea-4365-999c-74ab63cc173a) at 14:00Z; no ack yet.

## Operator Feedback

djbclark noticed the high context before I did and asked for the handoff. Keyboard fault "has been happening periodically. Rebooting fixes it. It seems to also happen in other keyboards."

## Where We're Going

1. Guided pass: create a fresh worktree off `origin/plk/main`, then when djbclark says he is holding the phone and "go", run the voice QA harness `smoke_chords` suite (`qa/README.md`, the harness defaults: voice, shade card, inversion for hands-off). First check `just status` in `~/firerpa-nonroot`; if FIRERPA is down, start it and do the manual driver swap above.
2. Write a short autocap 15/16 retest suite (Patrick's upstream fix is already on plk/main) and run it the same way.
3. Keyboard fault watch: if it fails again, ask djbclark not to reboot yet; capture `getevent -lt` on the TitanKey device while he presses keys, plus `dumpsys accessibility` and `dumpsys input`. If it stays fixed for about a week, record Notch-Touch as the cause in the memory file.
4. Open for djbclark: add one line about scrcpy `--no-control` to the QA README (EN+DE); optional stayturgid fix PR for #344.
5. Check for LEAD's ack of the context-nudge failure report; if none, resend once.
6. Remove worktree `plektra-djbclark-handoff-wt` once this commit is on origin (plk/handoff-ba14).

## Detached jobs

none

## Quick Start

```
cd ~/firerpa-nonroot && just status && just grpc
git -C ~/src/plektra-djbclark fetch origin && git -C ~/src/plektra-djbclark worktree add -b plk/qa-run-<date> ~/src/plektra-djbclark-qa-run-wt origin/plk/main
```
