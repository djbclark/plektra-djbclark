# Consolidated recommendation + implementation plan: Android UI-awareness in the QA harness

Merged: grok4.7 web research (android-ui-frameworks-research.md) + lead synthesis
(android-ui-frameworks-lead-synthesis.md). Sources are in the grok4.7 report.

## Recommendation (one line)
Keep FIRERPA/lamda; copy the missing selector semantics and a11y-field reading into the harness.
The gaps are selector policy, which a11y fields we read, and a thin screen/nav layer — not the driver.

## Concrete changes (all in qa/plektra_qa/, no new dependency)

1. controller.py
   - `obj_info`: add `contentDescription` and `hint` to the dict (today it drops both).
   - `focus_and_clear`: stop defaulting to `className=android.widget.EditText`. New order:
     a. resourceId (suite-provided), b. description / descriptionContains (lamda Selector already
     has these), c. text/textContains for a visible label, d. focused=true, e. local xpath on
     `dump_window_hierarchy()` XML as the fallback.
     Input-candidate predicate: class contains "EditText", OR focusable with hint/nonempty text,
     OR content-desc is a known composer label (e.g. "Compose:Draft:Send").
   - Reading: keep `text` as the VALUE, add `contentDescription` as the NAME; treat text==hint as
     empty; if text is empty, dump twice (lazy WebView tree) then re-read; final fallback = crop
     screenshot to node bounds and OCR. Never treat a stable content-desc as the typed value.
     Never OCR a password node (`password="true"`).
   - Known xpath recipes (from research): Messages composer `.//*[@content-desc="Compose:Draft:Send"]`;
     Gmail body (warm tree) `.//*[@resource-id="com.google.android.gm:id/wc_body"]//*[@class="android.widget.EditText" or @text!=""]`.

2. mcp_server.py
   - `_node_row` + `inspect_ui_fast`: add content-desc (and hint when present) to each row, so the
     screen signature and any vision labeler see them.

3. New small helper (controller or screen_gate): screen signature = package + activity + sorted set of
   nonempty resource-ids + content-descs (capped, like inspect_ui_fast already caps nodes). Enough to
   tell Messages compose from Gmail compose from home. No model required for id.

4. Navigation: use lamda keyevents, not swipes — `execute_ui_action(press_key)` already wraps
   `device.press_key`. Home=KEY_HOME, Back=KEY_BACK, Recents=KEY_RECENT (lamda enum). Swipe-up is only
   a fallback if KEY_HOME does not change the foreground package.

5. Vision+LLM (optional, defer): only as a LABELER when a screen signature is new — send the compact
   a11y rows + a JPEG to a frontier model, get back {screen_id, input_candidates}. Do not use it as the
   click path. Copy AndroidWorld's UIElement field split (text / content_description / hint_text / bounds).

## Explicitly NOT doing
- No Appium / uiautomator2 / Maestro install (lamda already exposes the same a11y tree + selector set).
- No mobile-mcp / appium-mcp / Android-MCP / droidrun install (they reimplement lamda).
- No Playwright Android (experimental, CDP/WebView only; production Gmail won't expose it).
- No Mobile-Agent / AppAgent / UI-TARS / CogAgent driver (screenshot-out research stacks).
- No `--force-renderer-accessibility` flag on a user build (ignored); dump-twice is the fix.

## Effect on the stuck task
With (1)+(2), the autocap run can target Messages' Compose composer and Gmail's `wc_body`/subject and
read the answer, so the harness can drive #15/#16 natively instead of chat-only.

## Update (operator, 2026-11-19): installing extra software is fine
uiautomator2 is approved as a SUPPLEMENT (not a swap) if lamda's dump falls short in practice:
- install alongside lamda and let it handle field reading if we hit missing `hint`, flaky element-wait,
  or a richer xpath need that local-xpath-on-dump doesn't cleanly cover.
- The two known hard cases (Messages custom Compose node, Gmail lazy WebView) are inherent to the a11y
  tree, so uiautomator2 reads the same data; it is not expected to solve them on its own — OCR fallback
  still applies to the Messages composer.
Trigger to switch/add: the a11y worker's tests or the live #15/#16 run shows lamda's XML missing a field
uiautomator2 exposes, or targeting by content-desc proves unreliable through lamda's Selector.

## Decision (operator: "do what's best long-term; probably uiautomator2", 2026-11-19)
- Adopt uiautomator2 for field targeting + value reading (xpath, get_text, element-wait, warm tree, hint).
- Keep lamda for the human-in-the-loop chord flow, watchers, OCR, WebRTC.
- droidrun mobilerun-portal `a11y_tree_full` (complete live node info incl. editable/hint) is the upgrade
  path if uiautomator2's dump misses a field; do not install Portal unless that happens.
- OCR stays last-resort ONLY for the custom-drawn Messages composer (nothing reads it via a11y if the
  app never publishes the draft). Confirm on the live #15/#16 run whether Messages publishes the draft
  elsewhere in the tree (then OCR is not needed at all).
- Sequence: let the in-flight a11y worker (copy-semantics, 2e74e83b) land and integrate; then dispatch a
  follow-up worker to add uiautomator2 as the reading layer (avoid two workers on the same files).

## OCR fallback decision (operator, 2026-11-19)
Wire `ocr_image` fallback chain, algorithmic first, VLM slice last:
1. lamda device.ocr() (PaddleOCR already on-device; tools/paddle_ocr_http_api.py) — free, first.
2. Local algorithmic OCR (PaddleOCR/EasyOCR/tesseract or macOS Vision) if device.ocr absent/empty.
3. Slice-agent VLM only for empty/low-confidence: a cheap image-capable model (deepseek-v4.1-flash or
   minimax-m3, both accept images) dispatched with the cropped screenshot + "transcribe this text".
Rationale: local OCR is free/deterministic (makes the pool last); the VLM slice fires only for the rare
ambiguous frame, and it is a cheap image model, not the frontier. Follow-up wiring task (ocr_image is
currently a lazy stub returning ''); not blocking the autocap run (its answers return via chat).

## Harness requirements from operator (2026-11-19, during the run)
1. Keep the screen on for the longest NON-forever UI timeout = 30 min during a pass; restore the
   original timeout/stay-awake on exit. DONE: AWAKE_MS changed 86400000 -> 1800000 (screen_gate.py);
   _restore already puts the saved values back.
2. A notification with a button to reset the screen settings to default manually. NOTE: the harness
   already has the "Swipe away to stop testing" shade card (STOP_TAG) which restores everything when
   swiped. A real action BUTTON needs a PendingIntent -> BroadcastReceiver, which adb
   `cmd notification post` cannot attach (harness is adb-only, no APK). Options to decide: (a) keep the
   swipe card as the manual reset, (b) ship a tiny companion APK with a receiver + a reset action, or
   (c) a different surface. Queued, not blocking.

## Harness fix: keep-awake must be session-level, applied FIRST (operator, 2026-11-19)
The screen re-locks between turns because the per-pass ScreenGate restores the original 2-min timeout
on exit. The 30-min + stay-on must be applied once at Android-QA session START (before any screen
driving), held for the whole session, and restored only at session END. Follow-up: lift the keep-awake
out of ScreenGate.__enter__/__exit__ into a session-level qa-prep / qa-end (or an explicit "hold awake
for N minutes" applied first); the per-pass ScreenGate should only do inversion/shade/heads-up, not own
the timeout. Also: wm dismiss-keyguard cannot clear a PIN/pattern (README already says the harness will
not enter a PIN), so the 30-min hold only prevents RE-locking after the operator unlocks once.

## Prior art from ~/ops/stayturgid (operator, 2026-11-19)
Architecture is the improved line (ScreenGate ~ stayturgid control/lib/screen_control.py, inversion
fail-closed + restore), but two pieces are worth borrowing:
1. stayturgid/device/termux/py/stayturgid_screen_awake_guard.py - an on-device guard that, while the
   screen is held awake, keeps a notification offering ONE TAP to restore normal screen lock. This is
   the prior art for the "notification with a button to reset to default" harness requirement: borrow
   the on-device notification + restore-action pattern.
2. stayturgid/control/lib/device_screen_lease.py (DSCL v1) - cross-project screen-control lease at
   ~/.local/state/device-screen-control/leases/ so multiple agents don't fight over the same phone.
   Adopt this (or its protocol) so the QA harness and stayturgid cannot both drive the Titan at once.
