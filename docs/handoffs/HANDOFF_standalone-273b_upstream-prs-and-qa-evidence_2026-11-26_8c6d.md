---
schema_version: 1
handoff_id: 8c6d
parent_handoff_ids:
  - 5ec4
lineage: deterministic
chain:
  - standalone-273b
repo: plektra-djbclark
workspace: plektra-djbclark-handoff-8c6d-wt
branch: docs/plektra-handoff-20261126-8c6d
head_sha: fac3a5b95825b837e48f0a7ebd75010de40b9b9c
created_at: 2026-11-26T14:01:00-05:00
writer: antigravity
---
# Handoff — Plektra upstream PRs (#234, #238–#244), Flux Slices 2–3, and QA evidence repo

## The Goal

Execute upstream Plektra (`pkb-rocks/plektra`) maintainer priorities in parallel: review incoming PRs and Flux branch slices against `upstream/main` (`fac3a5b95825b837e48f0a7ebd75010de40b9b9c`), land clean upstream PRs backed by GitHub Actions CI and Titan 2 Elite `handqa` device QA, publish sanitized QA recordings/evidence cleanly, and follow up on high-impact open upstream issues.

## Where We Are

1. **Upstream PRs opened & verified on `pkb-rocks/plektra` (7 of 8 confirmed 2/2 GREEN on `Android CI`; `#244` running):**
   - [PR #234](https://github.com/pkb-rocks/plektra/pull/234) (**Ready for review**, CI 2/2 green, Titan 2 Elite QA 3/3 pass + atomic JSON persistence verified across `am force-stop`, video published): `plk/fix-atomic-settings` @ `f2a747b2`.
   - [PR #238](https://github.com/pkb-rocks/plektra/pull/238) (draft, **CI 2/2 green**): `plk/pr-replacement-after-undo` @ `8991198e` (clean port of Flux `dcc9d951`).
   - [PR #239](https://github.com/pkb-rocks/plektra/pull/239) (draft, **CI 2/2 green**): `plk/pr-punct-space-numbers-addresses` @ `8aa09dad` (clean port of Flux `11917460` + word-boundary hint regex).
   - [PR #240](https://github.com/pkb-rocks/plektra/pull/240) (draft, stacked on `#238`, **CI 2/2 green**): `plk/pr-backspace-undo-any-replacement` @ `ff218afa` (clean port of Flux `872d4056` + trailing-punctuation scan fix & `etc.` regression test).
   - [PR #241](https://github.com/pkb-rocks/plektra/pull/241) (draft, **CI 2/2 green**): `plk/pr-suggestions-without-autocorrect` @ `8d72068e` (addresses `#185`: moves `Automatic correction` under `Suggestions`, adds disabled-state reason in 11 locales, gates max-distance slider + `SettingAvailability.AutoReplaceEnabled`, adds suggestions-only regression tests).
   - [PR #242](https://github.com/pkb-rocks/plektra/pull/242) (draft, **CI 2/2 green**): `plk/pr-enter-explicit-editor-action` @ `d847bed1` (partial fix for `#184`: honors custom `actionId` and advertised `IME_MASK_ACTION` even with `IME_FLAG_NO_ENTER_ACTION` when `ENTER_SEND_STRATEGY_EDITOR_ACTION` is configured + rejected-action tests). Commented on `#184` requesting Perplexity/Muse/NotebookLM `EditorInfo` captures.
   - [PR #243](https://github.com/pkb-rocks/plektra/pull/243) (draft, **CI 2/2 green**): `plk/pr-smart-modifier-toggle` @ `e8ac53b5` (clean port of Flux `568a6448`, opt-in off by default, 11 locales + `DirectBootKeyboardConfig` + 4 E2E tests).
   - [PR #244](https://github.com/pkb-rocks/plektra/pull/244) (draft, **CI in progress**): `plk/pr-ui-test-races` @ `9196f73c` (test-only port of Flux `df56c7b1` fixing `ClipboardHistoryTest` and `EmojiPagesSurfacesTest` background races; eligible to mark ready once CI passes).
2. **Reviews, QA, and tooling completed:**
   - Merged [frdminc/handqa#1](https://github.com/frdminc/handqa/pull/1) (354/354 pytest passing; live-verified voice-while-typing on Titan 2 Elite).
   - Reviewed upstream PRs `#231`–`#233` (posted GitHub comments) and Flux Slice 1 follow-up (`913e8074`, `c541005d`); emailed Patrick (`1a2187e0bb993764`, `1a21894eda01b8a2`).
   - Caught and reported Titan 2 Elite Space-as-Home bug under `#228` (`persist.sys.space_key_gohome=1` fires HOME on Space whenever `inputShown=false`): commented on `#228`, emailed Patrick (`1a218c43d8f7b367`), and committed fact to `qa/titan2-elite-facts.md` on `origin/plk/main` (`e7a425fc`).
   - Completed full source reviews of Flux Slice 2 (emailed Patrick `1a218df926369807`) and Flux Slice 3 (email drafted and held pending Patrick's Slice 2 reply). Flux Slices 4–7 remain unreviewed.
   - Created public QA evidence repo [frdminc/plektra-test-evidence](https://github.com/frdminc/plektra-test-evidence) (`9e000ab`) following the `frdminc/barnowl-test-evidence` per-PR folder pattern with MP4s hosted as GitHub Release assets; uploaded clean synthetic Titan 2 Elite recordings for PR #234 (`pr-234-2026-11-26`) and PR #231 (`pr-231-2026-11-26`) and linked them on both PRs.
   - Updated pinned fork status issue [djbclark/plektra-djbclark#19](https://github.com/djbclark/plektra-djbclark/issues/19) through PR #244 and fixed `site-public-djbclark/skills/gmail-search/SKILL.md` (`0c8db14`).
3. **Exact git & workspace state:**
   - Shared reference checkout `plektra-djbclark`: `plk/pr-autocorrect-lite` @ `b3cf551b`, pre-existing `M .gitignore` (not ours; leave untouched).
   - Active claimed worktrees (all clean & pushed): `plektra-djbclark-atomic-settings-wt` (`f2a747b2`), `plektra-djbclark-replacement-after-undo-wt` (`8991198e`), `plektra-djbclark-punct-space-wt` (`8aa09dad`), `plektra-djbclark-backspace-undo-wt` (`ff218afa`), `plektra-djbclark-issue185-wt` (`8d72068e`), `plektra-djbclark-issue184-wt` (`d847bed1`), `plektra-djbclark-smart-modifier-toggle-wt` (`e8ac53b5`), `plektra-djbclark-test-races-wt` (`9196f73c`), `plektra-djbclark-handoff-8c6d-wt` (`docs/plektra-handoff-20261126-8c6d`), and `plektra-test-evidence` (`main` @ `9e000ab`).
   - Three finished worktrees (`atomic-qa-wt`, `space-home-fact-wt`, `reliability-handoff-wt`) were released and removed cleanly (`~/s` links updated).
   - Phone state: Titan 2 Elite active IME is daily `rocks.pkb.plektra.djb`; throwaway `rocks.pkb.plektra.qa` stays installed side-by-side for upcoming QA (note: its isolated prefs still have `hardware_only_mode=true` from Batch 2).

## What We Tried

1. **Composio Gmail send:** failed with invalid/revoked API key; switched to Hermes's authenticated `google-workspace/scripts/google_api.py` invoked with `PYTHONPATH` set to the Hermes repo root and updated `skills/gmail-search/SKILL.md` (`0c8db14`). Note: never use `gmail reply` on our own sent message (replies to `From:` = self); always use `gmail send --to <recipient> --thread-id <id>`.
2. **PR #240 `BackspaceUndoTest` failures (two rounds):** first failed because reusing one `AutoReplaceController` kept `"etc"` in `rejectedWords`; second failed because calling `AutoReplaceController.handleBoundary` directly without `tracker.setWord("etc")` saw an empty word; third CI run passed 199/199 `suggestions` tests but hit the unrelated upstream `EmojiPagesSurfacesTest` flake. Squashing into `ff218afa` turned `#240` 2/2 green and motivated opening `#244`.
3. **PR #241 `MissingTranslation` lint:** `scripts/check-lint.py` rejects any new English string missing translations across the 10 localized `values-*/strings.xml` files. Adding all 10 translations (`8d72068e`) turned `#241` 2/2 green.
4. **PR #184 test edit multi-match:** an overly generic `Target` block matched 3 places in `PhysicalKeyboardInputMethodServiceDeviceBehaviorTest.kt`; immediately reverted and re-applied with a unique method-name anchor.
5. **Batch 2 QA (`#228`) recording privacy:** `handqa-20261126-111827.raw.mkv` was canceled mid-T3 when Space fired Home into Niagara Launcher, exposing the operator's home screen/app list — intentionally excluded from public upload.

## Key Decisions

1. **Prioritize end-user typing fixes (`#184`, `#185`) over Patrick's greenfield E2E passkey sync server (`314033`) and UI polish (`314037`):** `#184` and `#185` directly unblock daily typing bugs without requiring backend hosting or awaiting unfiled issue specs.
2. **Hold the Flux Slice 3 email until Patrick replies to Slice 2 (`1a218df926369807`):** avoids flooding Patrick's inbox before he responds on Slice 2 prioritization, while still getting the one clean Slice 3 feature (`568a6448`, PR `#243`) green on CI.
3. **Create dedicated public `frdminc/plektra-test-evidence` repo with MP4s as GitHub Release assets:** mirrors `frdminc/barnowl-test-evidence` while avoiding Git history bloat on `plektra-djbclark`. However, GitHub Release MP4 URLs require download rather than inline streaming, so the operator asked to evaluate automated YouTube uploading (unlisted/public with `.chapters.txt`) as an addition.
4. **Keep `rocks.pkb.plektra.qa` installed on Titan 2 Elite:** saves re-installing for upcoming QA batches on `#238`–`#243`.

## Decisions

- [decision] Open clean draft upstream PRs for ready Flux Slice 2 fixes (`dcc9d951`, `11917460`, fixed `872d4056`), Slice 3 (`568a6448`), Slice 1 UI test-race fix (`df56c7b1`), and upstream issues `#184`/`#185`; rejected: waiting passively for maintainer email replies before running CI; because pre-verified green CI drafts save maintainer turnaround time; in [PR 238](https://github.com/pkb-rocks/plektra/pull/238).
- [decision] Host Plektra QA evidence in dedicated public `frdminc/plektra-test-evidence` with MP4 recordings as GitHub Release assets; rejected: committing `.mp4` files into `plektra-djbclark` Git history; because Git binaries bloat every worktree and raw repo MP4s do not stream inline; in [plektra-test-evidence](https://github.com/frdminc/plektra-test-evidence).
- [decision] Hold Flux Slice 3 summary email until Patrick replies to the Slice 2 email; rejected: sending a second review email the same afternoon; because Patrick has not yet responded to Slice 2 prioritization.
- [decision] Exclude Batch 2 raw recording `handqa-20261126-111827.raw.mkv` from public upload; rejected: publishing it on `#228`; because the Space-as-Home jump captured the operator's Niagara Launcher home screen and app list.
- [ruling] "1 - include in handoff\" Do we have a way of automatically posting to youtube? That might be good to do in addition if the current way requires the file to be downloaded or the built-in player leaves much to be desired." (djbclark, 2026-11-26)
- [ruling] "also we can always install anything we don't have, for play store items probably best if I do so manually unless there is a way you can install that will then be taken over by play store for updates later." (djbclark, 2026-11-26)

## Relations

- continues [[HANDOFF_standalone-273b_reliability-review_2026-11-26_5ec4]]
- decided_in [[HANDOFF_standalone-273b_upstream-prs-and-qa-evidence_2026-11-26_8c6d]]

## Evidence & Data

- Local chain artifacts dir (contains full Slice 2/3 reviews, `#184`/`#185` investigation, PR `#231`–`#233` review, PR `#234`/`#228` QA notes, and Tier 1 `SESSION_LOG.md`): `~/.local/state/handoffs/chains/standalone-273b/`.
- Flux 7-slice scope and inventory: `~/.local/state/handoffs/plektra-djbclark/main/flux-review-scope-v2026.11.26.1.md` and `flux-full-inventory-v2026.11.26.1.tsv`.
- Published QA evidence repo: https://github.com/frdminc/plektra-test-evidence (releases `pr-234-2026-11-26` and `pr-231-2026-11-26`).
- Fork status issue (current through `#244`): https://github.com/djbclark/plektra-djbclark/issues/19.
- Upstream PRs & checks:
  - `#234` (ready): 2/2 green, 3/3 Titan 2 Elite QA pass.
  - `#238`, `#239`, `#240`, `#241`, `#242`, `#243` (drafts): all 6 confirmed 2/2 green.
  - `#244` (test-only draft): CI running at handoff time.
- Gmail sent thread with Patrick (`pzauner@pkb.rocks`): thread `1a2187e0bb993764` (latest sent msg `1a218df926369807`); `#228` Space-as-Home email msg `1a218c43d8f7b367`.

## Operator Feedback

1. **Immediate question to answer in the next session:** *"Do we have a way of automatically posting to youtube? That might be good to do in addition if the current way requires the file to be downloaded or the built-in player leaves much to be desired."* Note: check existing local YouTube/Google OAuth scopes (Hermes `google_token.json` currently has Gmail/Calendar/Drive/Contacts/Sheets/Docs scopes — check whether `youtube.upload` scope, `youtube-upload`, `gws`, or a Cloudflare R2 / GitHub Pages `<video controls>` wrapper already exists on this Mac before proposing a new OAuth flow!).
2. Keep Play Store app installs (Perplexity, Muse, NotebookLM for `#184`) manual by the operator so Play Store updates stay attached.
3. End-user daily typing impact takes priority over greenfield infrastructure (sync server) unless Patrick explicitly prioritizes otherwise.
4. Never mention PhysiBoard positively to Patrick (hard no on Signal `313767`).

## Where We're Going

1. **THE Next Action — Verify [PR #244](https://github.com/pkb-rocks/plektra/pull/244) CI (mark ready if 2/2 green, since test-only PRs can open ready) AND answer djbclark's automated YouTube-posting question:** check what YouTube upload tooling/credentials or alternative inline-streaming options (e.g. YouTube Data API v3 `youtube.upload` unlisted upload with `handqa`'s `.chapters.txt` description, vs. GitHub Pages HTML5 `<video controls>` player on `frdminc/plektra-test-evidence` pointing to Release assets or R2/Cloudflare) are available, then run `/steps` with concrete options.
2. **Check for Patrick's email reply** (`from:pzauner@pkb.rocks newer_than:3d` via Hermes `google_api.py`): once he replies on Slice 2, send the drafted Flux Slice 3 summary email (`flux-slice3-keyboard-and-terminal-review-2026-11-26.md`) and confirm whether to review Flux Slices 4–7.
3. **Plan a ~10-minute batched Titan 2 Elite `handqa` QA run** (single combined APK or focused suites) to device-verify green drafts `#238`, `#239`, `#240`, `#241`, `#242`, `#243` so they can be promoted to Ready for review, publishing clean recordings to `frdminc/plektra-test-evidence` (and YouTube if set up).
4. **If operator installs Perplexity / Muse / NotebookLM manually:** capture live `EditorInfo` (`packageName`, `inputType`, `imeOptions`, `actionId`, `actionLabel`) for `#184` and verify whether `#242` fixes them or whether additional send strategies are needed.

## Detached jobs

none

## Quick Start

```bash
# 1. Verify rebound claims and check PR #244 CI status
python3 ~/src/djbclark-ade/bin/ade_claims.py list | rg "$(cat ~/.local/state/handoffs/chains/standalone-273b/SESSION_LOG.md | rg session_id | awk '{print $2}')"
gh pr checks 244 -R pkb-rocks/plektra

# 2. Check for any reply from Patrick on Gmail (via Hermes Google Workspace CLI)
PYTHONPATH=~/.hermes/hermes-agent ~/.hermes/hermes-agent/.venv/bin/python \
  ~/.hermes/hermes-agent/skills/productivity/google-workspace/scripts/google_api.py \
  gmail search "from:pzauner@pkb.rocks newer_than:3d" --max 5

# 3. Check existing local YouTube upload tools / OAuth scopes (never print token secrets!)
which youtube-upload yt-dlp gws 2>&1 || true
python3 -c 'import json, os; d = json.load(open(os.path.expanduser("~/.hermes/google_token.json"))); print("scopes:", d.get("scopes"))'
```
