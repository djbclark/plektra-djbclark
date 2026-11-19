# Android UI frameworks for the Plektra QA harness

Research pass for the FIRERPA/lamda harness. Do not swap the driver. The three gaps are selector policy, which accessibility fields we read, and a thin screen/nav layer on top of dumps we already have.

Local facts used below (not web): `ScreenController.focus_and_clear` defaults to `className=android.widget.EditText`; `obj_info` drops `contentDescription`; `inspect_ui_fast` keeps only resource-id, text, class, bounds, focused. Lamda's own proto already has `description` / `descriptionContains` and `KEY_HOME` / `KEY_BACK` / `KEY_RECENT`.

Stars and licenses are from `gh api` on 2026-11-19 unless noted.

## 1. Candidate inventory

| name | type | mechanism | license | maturity | URL |
| --- | --- | --- | --- | --- | --- |
| FIRERPA / lamda (already in use) | lib + on-device server | a11y tree (UiAutomator selectors), screenshot, OCR | MIT | production, 8758 stars, updated 2026-11-19 | https://github.com/firerpa/lamda |
| openatx `uiautomator2` | lib | a11y tree; xpath plugin; key names | MIT | production, 8517 stars, updated 2026-11-19 | https://github.com/openatx/uiautomator2 |
| Appium + uiautomator2 driver | framework | a11y tree in `NATIVE_APP`; DOM via chromedriver only in a web context | Apache-2.0 | production. Driver 897 stars; Appium 22240 | https://github.com/appium/appium-uiautomator2-driver |
| AOSP `AccessibilityNodeInfoDumper` | platform | serializes the a11y tree to XML | Apache-2.0 | platform | https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/cmds/uiautomator/library/core-src/com/android/uiautomator/core/AccessibilityNodeInfoDumper.java |
| AndroidWorld `representation_utils` | research env | a11y forest to `UIElement`, including `is_editable` and `hint_text` | Apache-2.0 | research benchmark, 1019 stars, 116 tasks | https://github.com/google-research/android_world |
| AndroidEnv | research env | Android RL / a11y forwarder (AndroidWorld vendors this) | Apache-2.0 | research, 1264 stars | https://github.com/google-deepmind/android_env |
| AndroidLab (`THUDM/Android-Lab`) | research benchmark | AVD/Docker task runner, 138 offline tasks | MIT | research, 328 stars | https://github.com/THUDM/Android-Lab |
| OSWorld | research benchmark | desktop, not Android | Apache-2.0 | research, 3268 stars | https://github.com/xlang-ai/OSWorld |
| Mobile-Agent family (v2 is inside this repo; current line is v3 / v3.5) | research agent | vision + GUI actions; not an a11y library | MIT | research, 9392 stars | https://github.com/X-PLUG/MobileAgent |
| AppAgent | research agent | screenshot + multimodal model, ADB on emulator or device | MIT | research, 6923 stars | https://github.com/TencentQQGYLab/AppAgent |
| UI-TARS / UI-TARS-desktop | research model + stack | vision-language native agent (screenshot in, action out) | Apache-2.0 | archived on GitHub (11701 and 39602 stars) | https://github.com/bytedance/UI-TARS |
| CogAgent | research model | end-to-end VLM GUI agent | Apache-2.0 | research, 1202 stars. `THUDM/CogAgent` is 404; canonical is `zai-org/CogAgent` | https://github.com/zai-org/CogAgent |
| SeeAct | research agent | web pages, not Android | NOASSERTION | research, 853 stars | https://github.com/OSU-NLP-Group/SeeAct |
| mobile-mcp (`mobile-next/mobile-mcp`) | installable MCP | a11y tree first, screenshot only as fallback | Apache-2.0 | active, 10717 stars, release notes through 1.0.18 (2026-11-18) | https://github.com/mobile-next/mobile-mcp |
| appium-mcp | installable MCP | Appium uiautomator2 session; optional vision finder (`AI_VISION_ENABLED`) | Apache-2.0 | active, 522 stars | https://github.com/appium/appium-mcp |
| droidrun / mobilerun | agent framework + on-device Portal | a11y tree via an accessibility service, plus optional vision / vision-only | MIT | active, 10070 stars. `droidrun/droidrun` resolves to `droidrun/mobilerun` | https://github.com/droidrun/mobilerun |
| droidrun Portal | on-device accessibility service | keeps a live a11y client bound | (portal repo; not re-licensed here) | companion to mobilerun | https://github.com/droidrun/mobilerun-portal |
| CursorTouch/Android-MCP | installable MCP | `uiautomator dump` + ADB taps; selector by text, resource-id, class, description | MIT | active, 932 stars. README fetched was a clean-room reimplementation page; upstream is this repo | https://github.com/CursorTouch/Android-MCP |
| minhalvp/android-mcp-server | installable MCP | ADB: shell, screenshot, UI layout | Apache-2.0 | active, 814 stars | https://github.com/minhalvp/android-mcp-server |
| srmorete/adb-mcp, landicefu/android-adb-mcp-server, hao-cyber/phone-mcp | installable MCP | ADB | mixed | smaller clones of the same idea (56 / 38 / 253 stars) | search `adb-mcp` on GitHub |
| mikechambers/adb-mcp | name collision | Adobe Photoshop/Premiere MCP, not Android Debug Bridge | MIT | skip | https://github.com/mikechambers/adb-mcp |
| Playwright Android | experimental lib | ADB + Chrome/WebView CDP, not general native UI | Apache-2.0 | experimental, docs still say so | https://playwright.dev/docs/api/class-android |
| MadeAgents/mobile-use | research harness | evaluates agents on AndroidWorld/AndroidLab | (not fetched) | research, 272 stars | https://github.com/MadeAgents/mobile-use |
| Anthropic computer-use tool | first-party API tool | desktop screenshot + mouse/keyboard | proprietary API | not Android | https://platform.claude.com/docs/en/agents-and-tools/tool-use/computer-use-tool |
| OpenAI computer-use docs | first-party API tool | browser/desktop (scraped example is Playwright `exec_js`) | proprietary API | not Android. Page text had zero "android" hits | https://developers.openai.com/api/docs/guides/tools-computer-use |
| Claude Android app connectors | consumer product | Claude app talks to the phone's own apps | n/a | not a QA driver | https://support.claude.com/en/articles/11869629-use-claude-with-android-apps |

No distinct "OSWorld-Android" repo turned up. AndroidWorld is the Android benchmark. SearXNG hits for "OSWorld Android" were noise plus `google-research/android_world`.

## 2. Verdicts

- **FIRERPA / lamda: keep.** Already the driver. Its `Selector` matches openatx's UiSelector set, including `description*`. `ObjInfo` already returns `text` and `contentDescription`. The harness ignores the second.
- **uiautomator2: copy, do not adopt.** Same tree and the same selector names. Useful as the semantic spec (xpath, `description`, key names `home`/`back`/`recent`). Installing it beside lamda duplicates the on-device server.
- **Appium + uiautomator2 driver: skip as the driver; copy two ideas.** (1) `NATIVE_APP` vs web context are different engines. (2) Its attribute enum exposes `hint`, `showing-hint`, `input-type`, `text-entry-key`, `actions`. It still does not expose `isEditable` in page source. A wholesale Appium session fights the existing gRPC client.
- **AOSP dumper: copy the attribute list.** This is what `dump_window_hierarchy` is. It writes `text`, `content-desc`, `hint`, `class`, `resource-id`, focus flags, `bounds`. It does **not** write `editable`.
- **AndroidWorld `UIElement`: copy the record shape, skip the env.** `is_editable`, `hint_text`, `content_description`, and `text` are separate fields. That split is the screen-understanding format to imitate. Do not stand up the AVD benchmark.
- **AndroidEnv, AndroidLab, OSWorld, SeeAct, MadeAgents/mobile-use: skip.** Benchmarks or a web agent. Nothing to link into a phone already on FIRERPA.
- **Mobile-Agent / AppAgent / UI-TARS / CogAgent: skip as drivers; adapt the fallback pattern only.** They are screenshot-in, action-out research stacks. UI-TARS repos are archived. Use a vision call only when the a11y value is empty, not as the primary clicker.
- **mobile-mcp, appium-mcp, Android-MCP, adb-mcp clones: skip install.** They are installable MCP servers and they reimplement what lamda already exposes (`mobile_press_button` HOME/BACK, click by text/resource-id/description, screenshot fallback). Name collision: `mikechambers/adb-mcp` is Adobe.
- **droidrun/mobilerun: skip the agent; adapt one fact.** A *bound* accessibility client makes the WebView tree non-lazy. Do not install Portal on the QA phone unless dump-twice fails in practice.
- **Playwright Android: skip.** Experimental. It drives Chrome and a WebView's CDP page, which production Gmail will not expose. It is not a Compose clicker.
- **Anthropic / OpenAI computer-use, Claude Android connectors, Codex-from-phone: skip.** Desktop or browser tools, or a consumer assistant. None are an Android a11y driver we can point at Messages/Gmail.

## 3. What to implement

### (a) Target Compose and WebView fields

Compose does not show up as `android.widget.EditText`. Semantics (`EditableText`, `Text`, `ContentDescription`) are what accessibility services see; the platform dump stores `getText()` as `text` and `getContentDescription()` as `content-desc`, and `getClassName()` as `class`. The Messages node the harness already saw (`View`, content-desc `Compose:Draft:Send`) is the expected shape, not a FIRERPA bug.

Stop defaulting `focus_and_clear` to `className="android.widget.EditText"`. Lamda already accepts the openatx selector set. Use this order:

1. `resourceId`, when the suite has one (`com.google.android.gm:id/wc_body` is this kind of handle).
2. `description` / `descriptionContains` for a content-desc. Messages: `description="Compose:Draft:Send"` or `descriptionContains="Compose:Draft"`. These fields are `Selector` 8 and 9 in `lamda/rpc/uiautomator.proto`.
3. `text` / `textContains` only for a visible label or an already-typed value, not as the class filter.
4. `focused=true` if a field is already focused.
5. If none of those exist, parse the dump we already take. Lamda's selector has no xpath; do the xpath locally on `dump_window_hierarchy()` XML.

Local xpath that matches the two known gaps:

- Messages composer: `.//*[@content-desc="Compose:Draft:Send"]`
- Gmail body, after the tree is warm: `.//*[@resource-id="com.google.android.gm:id/wc_body"]//*[@class="android.widget.EditText" or @text!=""]`

A node is an input candidate when any of these hold: class contains `EditText`; it is focusable and has `hint` or nonempty `text`; content-desc is a known composer label. Do not require `class=android.widget.EditText`.

WebView-specific: the accessibility subtree is built lazily when no accessibility service is bound. Dump once, and if the WebView node has no children, dump again before declaring it empty. On a `user` build the Chromium flag `/data/local/tmp/webview-command-line` with `--force-renderer-accessibility` is ignored, so dump-twice is the fix, not that flag. HTML `contenteditable` becomes `class="android.widget.EditText"` with the value in `text` once the tree is warm. `aria-label` lands in `text` and replaces inner text on the build studied, so query both `text` and `content-desc`. HTML `id` can appear as `resource-id` but regressed in Chrome/WebView 120-126; do not depend on it for Gmail. `data-testid` never appears in this XML.

Do not switch Appium/Playwright into a `WEBVIEW_*` context for production Gmail. That path is CDP and needs `WebView.setWebContentsDebuggingEnabled(true)`, which is on for debuggable apps (WebView 113+) and userdebug images, not for a stock user-build Gmail.

### (b) Read Compose and WebView field text

`element.get_text()` is `ObjInfo.text`, which is `AccessibilityNodeInfo.getText()`. That is the right call when the app publishes `EditableText` or the WebView tree is warm. It is the wrong *only* call.

Read both, and keep them distinct:

- **value:** `ObjInfo.text` / XML `text`, after the existing resting-hint filter.
- **name:** `ObjInfo.contentDescription` / XML `content-desc`. Add this to `obj_info` and to `inspect_ui_fast` rows. `Compose:Draft:Send` is a name, not the draft.
- **hint:** XML `hint` if the dump includes it (AOSP dumper writes it; lamda `ObjInfo` does not). If `text` equals `hint`, treat it as empty, which the harness already does for recorded resting strings.

Read algorithm for a targeted node:

1. Click to focus if not focused. Read `text`.
2. If `text` is empty, dump again (WebView lazy tree, or Compose value that appears only after focus) and read `text` on the same resource-id or content-desc.
3. If `text` is still empty, crop the screenshot to that node's `bounds` and OCR. This is the path for a custom composer that draws the draft without putting it in `EditableText`. Do not expect chromedriver to recover it.
4. Never treat content-desc as the typed value when it is a stable control name.

`isEditable` is the clean "this is a field" bit, but neither the AOSP dumper nor Appium's `Attribute` enum puts it in the XML. AndroidWorld gets it from the accessibility protobuf, not from `uiautomator dump`. Do not block on it. Infer input-ness from class, focusable+hint/text, or a known content-desc. If a later lamda `info()` grows an editable flag, prefer that; today's `ObjInfo` has no such field.

Password fields: the WebView study showed the value masked and `password="true"`. Do not OCR those.

### (c) Screen id and navigation

**Screen id, no new model required.** `device_status` already returns package and activity. Add a signature from the dump: package, activity, and the sorted set of nonempty resource-ids plus content-descs (cap it; `inspect_ui_fast` already caps nodes). That is enough to tell Messages compose from Gmail compose from a home screen. Store the signature next to the suite, not in a framework.

**Vision+LLM only as a labeler, not a clicker.** When the signature is new, send the compact rows (after content-desc is included) and a JPEG, and ask for `{screen_id, input_candidates}`. Copy AndroidWorld's split (`text` vs `content_description` vs `hint_text` vs bounds), not its agent loop. mobile-mcp's contract is the same idea: a11y list first, screenshot only when the tree is not enough. UI-TARS / Mobile-Agent / AppAgent are the wrong layer to install; they replace the click path we already have.

**Navigation: keyevents, not swipes.** Lamda already defines them (`lamda/rpc/uiautomator.proto`):

| intent | lamda `Key` | `KeyCode` | openatx name |
| --- | --- | --- | --- |
| Home | `KEY_HOME` = 6 | `KEYCODE_HOME` = 3 | `press("home")` |
| Back | `KEY_BACK` = 0 | `KEYCODE_BACK` = 4 | `press("back")` |
| Recents | `KEY_RECENT` = 10 | (use the lamda enum, not a guessed keycode) | `press("recent")` |
| Confirm | `KEY_ENTER` = 5 | `KEYCODE_ENTER` = 66 | `press("enter")` |

`execute_ui_action(action="press_key")` already calls `device.press_key`. Suites should use that for home/back/recents. Android 10's gesture-navigation note says Back, Home, and Recents survive as edge swipes instead of buttons; a keyevent still injects the button action and does not depend on gesture-nav vs 3-button. Do not implement "home = swipe up" as the primary action.

Swipe fallback, only if `press_key(KEY_HOME)` does not change the foreground package: a bottom-edge upward swipe. The exact pixel recipe was **not** on the Android 10 page (it says "edge swipes" and stops). Treat the usual mapping (swipe up from the bottom center = Home, inward from the left or right edge = Back, swipe up and hold = Recents) as knowledge, unverified, and do not hard-code it until a keyevent fails on this phone.

Also keep the screen awake. A locked screen makes `uiautomator dump` return the keyguard or `null root node`.

## 4. Sources

Verified this pass:

- https://github.com/firerpa/lamda (`uiautomator.proto` Selector and Key enums, via `gh api`)
- https://github.com/openatx/uiautomator2 (README: selectors, `press("home"|"back"|"recent")`)
- https://github.com/appium/appium-uiautomator2-driver
- https://github.com/appium/appium-uiautomator2-server `Attribute.java` (hint, showing-hint, input-type, actions; no editable)
- https://appium.io/docs/en/2.5/guides/context/ (NATIVE_APP vs other contexts; does not itself document the chromedriver socket)
- https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/cmds/uiautomator/library/core-src/com/android/uiautomator/core/AccessibilityNodeInfoDumper.java
- https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo (`getText`, `getContentDescription`, `getHintText`, `isEditable`, `ACTION_SET_TEXT`)
- https://developer.android.com/reference/kotlin/androidx/compose/ui/semantics/SemanticsProperties (`EditableText`, `ContentDescription`, `Text`, `TestTag`)
- https://developer.android.com/develop/ui/compose/accessibility/semantics
- https://developer.android.com/develop/ui/compose/accessibility
- https://developer.android.com/reference/android/view/KeyEvent (`KEYCODE_HOME`, `KEYCODE_BACK`, `KEYCODE_APP_SWITCH`, `KEYCODE_ENTER` names present)
- https://developer.android.com/about/versions/10/highlights (gesture nav keeps Back/Home/Recents as edge swipes)
- https://amoshydra.github.io/blog/posts/webview-uiautomator-dump/ (WebView XML mapping, lazy tree, contenteditable, user-build flag, CDP socket)
- https://github.com/google-research/android_world `android_world/env/representation_utils.py`
- https://google-research.github.io/android_world/
- https://github.com/google-deepmind/android_env
- https://github.com/THUDM/Android-Lab
- https://github.com/xlang-ai/OSWorld
- https://github.com/X-PLUG/MobileAgent
- https://github.com/TencentQQGYLab/AppAgent
- https://github.com/bytedance/UI-TARS and https://github.com/bytedance/UI-TARS-desktop (both archived)
- https://arxiv.org/html/2501.12326v1 (UI-TARS; search snippet places it against accessibility-tree/DOM agents)
- https://github.com/zai-org/CogAgent
- https://github.com/OSU-NLP-Group/SeeAct
- https://github.com/mobile-next/mobile-mcp
- https://github.com/appium/appium-mcp
- https://github.com/droidrun/mobilerun and https://github.com/droidrun/mobilerun-portal
- https://github.com/CursorTouch/Android-MCP
- https://github.com/minhalvp/android-mcp-server
- https://github.com/mikechambers/adb-mcp (Adobe, not ADB)
- https://playwright.dev/docs/api/class-android and https://playwright.dev/docs/api/class-androiddevice
- https://platform.claude.com/docs/en/agents-and-tools/tool-use/computer-use-tool
- https://developers.openai.com/api/docs/guides/tools-computer-use
- https://support.claude.com/en/articles/11869629-use-claude-with-android-apps
- https://github.com/MadeAgents/mobile-use
- Chromium issue cited by the WebView study: https://issues.chromium.org/issues/328681381 and https://github.com/appium/appium/issues/19848

Knowledge, unverified:

- Exact gesture-nav vectors (bottom-center swipe up = Home, side-edge inward = Back, swipe-up-and-hold = Recents). The Android 10 page only says "edge swipes". Pixel help URL `https://support.google.com/pixelphone/answer/9759462` returned 404.
- That a Compose `TextField`'s `EditableText` is always copied into `AccessibilityNodeInfo.getText()`. The API has the property; the Messages observation shows a custom node whose value is not in `text`. I did not fetch the Compose accessibility-delegate source.
- `KEYCODE_APP_SWITCH` numeric value. The name is on the KeyEvent page; lamda's recents control is the `KEY_RECENT` enum, which is what the harness should call.
- OpenAI computer-use page body beyond the scraped Playwright browser example. The fetch was nav-heavy; "android" did not occur.

Prior art / docs checked: local `qa/plektra_qa/controller.py` and `mcp_server.py`; lamda `uiautomator.proto` via GitHub API; Firecrawl search/scrape on `127.0.0.1:18805` (SearXNG was noisy on generic "compose"/"android" queries; raw GitHub and developer.android.com scrapes were used instead); `gh api` / `gh search repos` for stars, licenses, and name collisions.

Closing search: re-queried "OSWorld Android", "adb-mcp", "AndroidLab", and the lamda selector proto against the conclusions. No OSWorld-Android repo. `adb-mcp` is several ADB servers plus one Adobe project. AndroidLab is `THUDM/Android-Lab`. Lamda already has `descriptionContains` and `KEY_HOME`/`KEY_BACK`/`KEY_RECENT`, which matches the "adapt the harness, do not swap frameworks" conclusion. No contradicting source.
