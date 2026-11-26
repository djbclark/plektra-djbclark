---
schema_version: 1
handoff_id: b210
parent_handoff_ids:
  - 8c6d
lineage: deterministic
chain:
  - standalone-273b
repo: plektra-djbclark
workspace: plektra-djbclark-handoff-b210-wt
branch: docs/plektra-handoff-20261126-b210
head_sha: fac3a5b95825b837e48f0a7ebd75010de40b9b9c
created_at: 2026-11-26T16:32:00-05:00
writer: antigravity
---
# Handoff — All 8 Plektra PRs Ready, YouTube QA Uploader Live, Flux Slices 1–7 Reviewed, and `handqa#2` Merged

## The Goal

Execute upstream Plektra (`pkb-rocks/plektra`) maintainer priorities: keep our 8 upstream PRs (`#234`, `#238`–`#244`) merge-ready through maintainer review, maintain clean public device-QA video evidence (`frdminc/plektra-test-evidence` + unlisted YouTube with chapters), and coordinate Flux Slices 2–7 porting once Patrick (`pzauner@pkb.rocks`) responds on Slice 2 prioritization.

## Where We Are

1. **All 8 upstream PRs on `pkb-rocks/plektra` are OPEN, `MERGEABLE`, `Android CI` 2/2 GREEN, and Ready for review:**
   - [PR #234](https://github.com/pkb-rocks/plektra/pull/234) (`plk/fix-atomic-settings` @ `f2a747b2`): ready, device-tested, YouTube backfilled ([MgZV6y31YWo](https://youtu.be/MgZV6y31YWo)).
   - [PR #238](https://github.com/pkb-rocks/plektra/pull/238) (`plk/pr-replacement-after-undo` @ `8991198e`): ready, device-tested in Batch 3 (`teh<Space><Backspace>teh<Space>ok` → `teh the ok`; [W2dE854NeGc](https://youtu.be/W2dE854NeGc)).
   - [PR #239](https://github.com/pkb-rocks/plektra/pull/239) (`plk/pr-punct-space-numbers-addresses` @ `8aa09dad`): ready, device-tested 4/4 in Batch 1 (decimal `3.14`, prose `hi. Ok`, email `a.b@c.com`, URL `pkb.rocks/a`; [u8j5Rgiq7D8](https://youtu.be/u8j5Rgiq7D8)).
   - [PR #240](https://github.com/pkb-rocks/plektra/pull/240) (`plk/pr-backspace-undo-any-replacement` @ `ff218afa`, stacks on `#238`): ready, device-tested in Batch 1 & Batch 3 (`deleteSurroundingText(4,0)` + `commitText("teh ")` and `commitText("teh.")`; [W2dE854NeGc](https://youtu.be/W2dE854NeGc), [u8j5Rgiq7D8](https://youtu.be/u8j5Rgiq7D8)).
   - [PR #241](https://github.com/pkb-rocks/plektra/pull/241) (`plk/pr-suggestions-without-autocorrect` @ `8d72068e`, addresses `#185`): ready, device-tested in Batch 2 (`john bc teh` untouched with suggestions ON / auto-replace OFF; [pPnZV45C9K0](https://youtu.be/pPnZV45C9K0)).
   - [PR #242](https://github.com/pkb-rocks/plektra/pull/242) (`plk/pr-enter-explicit-editor-action` @ `d847bed1`, partial for `#184`): ready, device-tested in Batch 3 (`performEditorAction(search)` on `probe_search` → `qok`; [W2dE854NeGc](https://youtu.be/W2dE854NeGc)).
   - [PR #243](https://github.com/pkb-rocks/plektra/pull/243) (`plk/pr-smart-modifier-toggle` @ `e8ac53b5`): ready, device-tested in Batch 1 (`(hi` Smart Alt off after `(`) & Batch 3 (`hellox` Smart Ctrl off after `Ctrl+C` Copy; [W2dE854NeGc](https://youtu.be/W2dE854NeGc), [u8j5Rgiq7D8](https://youtu.be/u8j5Rgiq7D8)).
   - [PR #244](https://github.com/pkb-rocks/plektra/pull/244) (`plk/pr-ui-test-races` @ `9196f73c`): ready, test-only Flux `df56c7b1` port, CI 2/2 green.
2. **Automated unlisted YouTube QA uploader live & verified (`frdminc/plektra-test-evidence` @ `e4102b4`):**
   - Added `scripts/upload-youtube.py` using existing GCP OAuth client (`ai-mcp-504419`) with a dedicated minimal-scope token (`~/.config/plektra-djbclark/youtube_upload_token.json`, `0600`, `youtube.upload` scope only; Hermes's Google Workspace token untouched).
   - Verified live via `yt-dlp` that all 5 uploaded recordings remain `availability=unlisted` with clickable chapters parsed from `handqa` `.chapters.txt`: PR #234 (`MgZV6y31YWo`), PR #231 (`LfRqcRkJ9Us`), Batch 1 (`u8j5Rgiq7D8`), Batch 2 (`pPnZV45C9K0`), and Batch 3 (`W2dE854NeGc`).
   - Updated [frdminc/plektra-test-evidence](https://github.com/frdminc/plektra-test-evidence) (`pr-231`, `pr-234`, `pr-238`–`pr-243`, `README.md`) and pinned fork status issue [djbclark/plektra-djbclark#19](https://github.com/djbclark/plektra-djbclark/issues/19).
3. **Issue #184 live `EditorInfo` captures posted (`Perplexity`, `Muse`, `NotebookLM`):**
   - Captured passive unrecorded `curEditorInfo` on Titan 2 Elite and posted on [issue #184](https://github.com/pkb-rocks/plektra/issues/184#issuecomment-7202729381) and [PR #242](https://github.com/pkb-rocks/plektra/pull/242): Perplexity (`ai.perplexity.app.android` v2.106.0) and Muse (`com.facebook.aura` v17.1.0.28.160) advertise `inputType=0x2c001`, `imeOptions=0x42000000` (`IME_ACTION_UNSPECIFIED` + `IME_FLAG_NO_ENTER_ACTION`), `actionId=0`; NotebookLM (`com.google.android.apps.labs.language.tailwind` v1.68.7.1024472889) advertises `inputType=0x2c001`, `imeOptions=0x2000001` (`IME_ACTION_NONE`), `actionId=0`. Confirms those three apps advertise no IME editor action at all.
4. **All 7 Flux slices now fully reviewed against `upstream/main` (`fac3a5b95`) and open PR #225:**
   - Slices 4–5: `~/.local/state/handoffs/chains/standalone-273b/flux-slices-4-5-review-2026-11-26.md` (13 commits: 1 `CLEAN PORT` `fc3ca9e7`, 1 `SKIP/SUPERSEDED` `show_add_word_suggestion` in `f006063f`, 1 `DEFER` `5e8c808d`, 10 `PORT WITH FIXES`).
   - Slices 6–7: `~/.local/state/handoffs/chains/standalone-273b/flux-slices-6-7-review-2026-11-26.md` (12 Slice 6 feature units + 3 Slice 7 integration commits: `dbcd879f` paste suggestion & `28b9b0e7` OTP are superseded by open upstream PR #225; high-value `PORT WITH FIXES` picks are `20ec627b` snippet placeholders, `97a44d4f` clean copied links, `4171aae4` offline mode, `4ae9c99b` inline autofill, and shortcut trilogy `66501e0a` → `dfec305b` & `837c8e80`; defer `87bffd42`, full `8cbef183`, and `327bcd00`).
   - Both research workers (`31065266`, `c524acad`) and `WORKER plektra-djbclark` (`wks_ea4fa21604b1fefc`) were cleanly acked and archived.
5. **`frdminc/handqa#2` merged (`4bc9270`) & always-loaded `AGENTS.md` updated:**
   - Merged [frdminc/handqa#2](https://github.com/frdminc/handqa/pull/2) (CI 2/2 green: fixed `_render_inline()` verb-doubling in `handqa/keys.py` so `Tap <Ctrl>` / `Double-tap <Alt>` / `Press <Ctrl+A>` no longer say `"Tap tap ..."` / `"Press hold ..."`, and removed `max_reprompts: 2` from `suites/harness_demo.yaml`); fast-forwarded `~/src/handqa` to `4bc9270` and cleaned up `handqa-fix-key-verbs-wt`.
   - Updated gitignored `/Users/djbclark/src/plektra-djbclark/AGENTS.md` with pointers to `~/src/handqa` (`max_reprompts` default), `~/src/plektra-test-evidence`, `scripts/publish-video.sh`, and `scripts/upload-youtube.py`.
6. **Exact git, workspace, & phone state:**
   - Shared checkout `/Users/djbclark/src/plektra-djbclark`: `plk/pr-autocorrect-lite` @ `b3cf551b`, pre-existing `M .gitignore` (not ours; leave untouched).
   - Active claimed worktrees (all clean & pushed): the 8 open PR worktrees (`plektra-djbclark-atomic-settings-wt`, `replacement-after-undo-wt`, `punct-space-wt`, `backspace-undo-wt`, `issue185-wt`, `issue184-wt`, `smart-modifier-toggle-wt`, `test-races-wt`), `plektra-test-evidence` (`main` @ `e4102b4`), and this handoff worktree `plektra-djbclark-handoff-b210-wt`.
   - Cleaned up: released/removed `plektra-djbclark-batch-qa-20261126-wt`, `plektra-djbclark-handoff-8c6d-wt`, and `handqa-fix-key-verbs-wt`; deleted all 3 throwaway `origin/ci/run/handqa-*` branches; refreshed `~/s` links.
   - Titan 2 Elite phone state: daily IME `rocks.pkb.plektra.djb` active (`inv=0`, `heads_up=1`); throwaway `rocks.pkb.plektra.qa` (candidate `93702d8e`) kept side-by-side per operator decision.
   - Files changed this session: `plektra-test-evidence` (`README.md`, `scripts/upload-youtube.py`, `pr-231/`, `pr-234/`, `pr-238/`–`pr-243/`), `frdminc/handqa` (`handqa/keys.py`, `suites/harness_demo.yaml`, `tests/test_keys.py` via PR #2), gitignored `plektra-djbclark/AGENTS.md`, Tier 1 `SESSION_LOG.md`, and this handoff doc.

## What We Tried

1. **`adb shell run-as ... sh -c 'cat > ...'` quoting trap:** `adb shell` concatenates arguments and strips quotes, causing `>` to redirect in `/` instead of inside `run-as`'s app directory. Fixed by passing `["adb", "-s", serial, "shell", "run-as", pkg, "tee", "shared_prefs/plektra_prefs.xml"]` with `input=out`.
2. **Batch 1 & Batch 2 subtle QA trial gotchas (all resolved cleanly in Batch 3):**
   - Copying `max_reprompts: 2` from `harness_demo.yaml` caused Batch 3's first run to time out on Trial 2; removing `max_reprompts` restored `handqa`'s intended no-timeout (`None`) default and motivated fixing `harness_demo.yaml` in `frdminc/handqa#2`.
   - Writing `"Tap <Ctrl>"` doubled to `"Tap tap the key printed F N"` aloud because `_render_inline()` always prepended `"tap "` to non-chord keys; fixed cleanly in `frdminc/handqa#2`.
   - Batch 2 T2 (`expect: "query"`) settled before `<Enter>` was pressed; changing Batch 3 T3 to `q <Enter> ok` (`expect: "qok"`) forced `<Enter>` before settling and captured `performEditorAction({'action': 'search'})` in the probe JSONL.
3. **`dumpsys input_method` format on Titan 2 Elite:** `curEditorInfo:` is a multi-line indented block rather than single-line `EditorInfo{...}`; verified format directly before running the passive watcher, capturing all 3 `#184` apps cleanly on the first pass.

## Key Decisions

1. **Use dedicated minimal-scope `youtube.upload` token on existing GCP project `ai-mcp-504419` alongside GitHub Releases:** Google's live `videos.insert` docs (2026-11-23) allow 100 uploads/day without the old unverified-project private lock; confirmed live across all 5 unlisted uploads with clickable chapters, while keeping Hermes's Google Workspace token isolated. Rejected Cloudflare R2 + GitHub Pages HTML5 player (no automatic chapters/adaptive streaming).
2. **Keep Flux Slice 3–7 review summaries held locally until Patrick replies to Slice 2 (`1a218df926369807`):** prevents flooding Patrick's inbox while keeping all 7 slice reviews immediately actionable the moment he responds.
3. **Keep `rocks.pkb.plektra.qa` installed side-by-side on Titan 2 Elite (operator confirmed):** saves setup overhead for upcoming Slice 4–6 QA while daily `rocks.pkb.plektra.djb` remains active.

## Decisions

- [decision] Add automated unlisted YouTube uploading via dedicated minimal-scope `youtube.upload` OAuth token on GCP project `ai-mcp-504419` alongside GitHub Release archives; rejected: Cloudflare R2 plus GitHub Pages HTML5 video wrapper; because YouTube provides inline adaptive streaming and automatic `handqa` timeline chapters without touching Hermes's Workspace token; in [plektra-test-evidence](https://github.com/frdminc/plektra-test-evidence).
- [decision] Fix inline key-verb doubling in `handqa/keys.py` and remove `max_reprompts: 2` from `suites/harness_demo.yaml`; rejected: working around verb-doubling manually in every YAML suite; because authors naturally write `Tap <Ctrl>` and copy `harness_demo.yaml`; in [handqa PR 2](https://github.com/frdminc/handqa/pull/2).
- [decision] Keep Flux Slice 3–7 source review summaries local until Patrick replies to the Slice 2 email; rejected: emailing additional review summaries before Slice 2 prioritization is settled; because all 7 slices are now durably documented locally and ready to send on his reply.
- [ruling] "You can always check what is on the phone without asking. But for this I'll just tell you all of them are installed." (djbclark, 2026-11-26)
- [ruling] "The test start timed out; I thought we set it to not timeout by default? Also we need to reconsider how to say the keys, \"tap tap ... only once\" is confusing." (djbclark, 2026-11-26)

## Relations

- continues [[HANDOFF_standalone-273b_upstream-prs-and-qa-evidence_2026-11-26_8c6d]]
- decided_in [[HANDOFF_standalone-273b_prs-ready-and-flux-reviews_2026-11-26_b210]]

## Evidence & Data

- Local chain artifacts dir (all 7 Flux slice reviews, `#184`/`#185` investigation, QA reports, Tier 1 `SESSION_LOG.md`): `~/.local/state/handoffs/chains/standalone-273b/`.
- Published QA evidence repo: https://github.com/frdminc/plektra-test-evidence (`e4102b4`).
- Unlisted YouTube QA videos (all 5 verified live): `#234` https://youtu.be/MgZV6y31YWo, `#231` https://youtu.be/LfRqcRkJ9Us, Batch 1 (`#239`/`#240`/`#243`) https://youtu.be/u8j5Rgiq7D8, Batch 2 (`#241`) https://youtu.be/pPnZV45C9K0, Batch 3 (`#238`/`#240`/`#242`/`#243`) https://youtu.be/W2dE854NeGc.
- Fork status issue (current through all 8 PRs ready): https://github.com/djbclark/plektra-djbclark/issues/19.
- Upstream `#184` live `EditorInfo` captures: https://github.com/pkb-rocks/plektra/issues/184#issuecomment-7202729381.
- Merged `handqa` ergonomics fix: https://github.com/frdminc/handqa/pull/2 (`4bc9270`).
- Gmail sent thread with Patrick (`pzauner@pkb.rocks`): thread `1a2187e0bb993764` (latest sent msg `1a218df926369807`).

## Operator Feedback

1. Agents may inspect installed packages on the Titan 2 Elite without asking first (still keep Play Store installs manual so Play Store updates stay attached).
2. Guided `handqa` runs must never time out by default (omit `max_reprompts` in suites) and must avoid awkward verb-doubling in spoken/shade key cues (fixed in `frdminc/handqa#2`).
3. Keep `rocks.pkb.plektra.qa` installed side-by-side on Titan 2 Elite for upcoming QA runs; always restore `rocks.pkb.plektra.djb` after each batch.
4. Never mention PhysiBoard positively to Patrick (hard no on Signal `313767`).

## Where We're Going

1. **THE Next Action — Check for Patrick's Gmail reply (`from:pzauner@pkb.rocks newer_than:7d`) and check review activity / merge status on our 8 ready upstream PRs (`#234`, `#238`–`#244`):**
   - If any of `#234`, `#238`–`#244` received maintainer review comments or merged, address feedback or release/clean up merged PR worktrees and update fork issue [#19](https://github.com/djbclark/plektra-djbclark/issues/19).
   - If Patrick replied to the Slice 2 email (`1a218df926369807`), send the concise Slice 3–7 review summaries per his ordering (remember: `gmail send --to pzauner@pkb.rocks --thread-id 1a2187e0bb993764`, never `gmail reply` on our own sent message).
2. **If no reply yet and operator wants forward progress:** run `/steps` to pick the highest-value clean/low-risk Flux Slice 4–6 ports to open as green-CI PRs (`fc3ca9e7` layer-editor drag-to-swap `CLEAN PORT`, `a4beefb1` narrow popup clamps + `TextExpansionPopup` recovery preserving upstream `15ec202b`, `fd3fe087` search bars wait for typing with `lastTextFieldPackage` fix, `20ec627b` snippet placeholders with `IS_SENSITIVE` guard, `97a44d4f` clean copied links with `isHistoryAccessible` guard).
3. **Housekeeping on next turn:** once confirmed rebound, release and remove this finished handoff worktree `plektra-djbclark-handoff-b210-wt` (already pushed to `origin`).

## Detached jobs

none

## Quick Start

```bash
# 1. Verify rebound claims and check status/reviews on all 8 ready upstream PRs
python3 ~/src/djbclark-ade/bin/ade_claims.py list | rg "$PASEO_AGENT_ID"
for pr in 234 238 239 240 241 242 243 244; do
  gh pr view "$pr" -R pkb-rocks/plektra --json number,state,isDraft,reviewDecision,comments \
    --jq '"#\(.number) \(.state) draft=\(.isDraft) review=\(.reviewDecision // "none") comments=\(.comments|length)"'
done

# 2. Check for Patrick's reply on Gmail (Hermes Google Workspace CLI)
PYTHONPATH=~/.hermes/hermes-agent ~/.hermes/hermes-agent/.venv/bin/python \
  ~/.hermes/hermes-agent/skills/productivity/google-workspace/scripts/google_api.py \
  gmail search "from:pzauner@pkb.rocks newer_than:7d" --max 5
```
