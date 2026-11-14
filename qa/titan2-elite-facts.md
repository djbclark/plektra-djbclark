# Titan 2 Elite: concrete facts for Plektra QA runs

Fork-only (never in an upstream PR). Maintained by agents during QA runs on the maintainer's Titan 2 Elite.
Each fact: what, how it was established, date. Update in place; mark superseded facts instead of deleting.

## 1. Device access
1. adb: over TCP 5555 (Tailscale and LAN) and USB; addresses and serial are in the maintainer's local agent notes,
   not here. `scripts/github-install-latest` treats the transports of one phone as one device (ci/base a0193313).
2. adb TCP 5555 and `adb_allowed_connection_time=0` are deliberate on the maintainer's phone; never reset them.
3. Package `rocks.pkb.plektra.djb`, label "Plektra djb"; IME service
   `rocks.pkb.plektra.djb/rocks.pkb.plektra.inputmethod.PhysicalKeyboardInputMethodService`
   (`settings get secure default_input_method`). Never `am force-stop` / `am start -S` it: kills the IME, Android
   switches keyboards.
4. stableDebug builds are debuggable: `adb exec-out run-as rocks.pkb.plektra.djb cat shared_prefs/plektra_prefs.xml`
   and `run-as ... sh -c 'cd files && find .'` work (2026-11-14).
5. The system property `log.tag` is `I`, so every app `Log.d` (KeyProbe, Plektra's keyDelivery trace) is dropped at
   write time and never reaches logcat. Before a capture: `adb shell setprop log.tag.KeyProbe D` and
   `adb shell setprop log.tag.PlektraInputMethod D` (lasts until reboot). Found in QA S4, 2026-11-14.

## 2. Physical keys (scan codes; KeyProbe + `getevent -lt`, 2026-11-14)
1. Keycaps are factory. Printed ⏎ Enter = scan 28. Printed right ⇧ = scan 54. Printed Fn = scan 251 (FUNC3).
   Sym = scan 253 (AGUI_SYM). Home = printed ○ circle key, scan 102 (KEY_HOME; vendor keycode 407, see 3.7).
   Back = scan 158 (by convention the printed △ triangle and □ square are Back and Recent; not verified).
   Orange side key = ff_key 249 (vendor "Func1").
   Volume = gpio-keys 115/114. Power = ff_key 116.
2. Name keys in prompts as "printed X (now acts as Y)" .
3. Input devices (`getevent -i`, 2026-11-14): event5 "TitanKey" (keys), event4 "touchPad" (the keyboard's touch surface:
   resting a finger on the keys produces BTN_TOUCH there), event6 "fts_ts" (screen), event7 "ff_key", event0 "gpio-keys"
   (volume), event1 "mtk-pmic-keys", event3 "hall_sensor". During a key batch, `adb shell input tap` on the screen sometimes
   did not register (3 tries) while event4 had touches; asking the operator to lift fingers between presses and retrying
   up to 15 x 0.5 s fixed it (18/18). Cause not proven.

## 3. Vendor key layer (com.agui.shortcutsettings, Settings → Shortcut keys → Key setting)
1. The firmware remaps before any app sees the key. What a key now types is in
   `persist.sys.agui_programmable_<key>_key` (getprop; Android keycode). Function choice is in
   `Settings.System <key>_programmable_key_function` (+ `_enable` for some keys). Plektra reads these in
   `toolbox/KeyInventory.kt`. Absent property = native key (per KeyInventory's comment; to verify on a factory key).
2. Function codes, inferred 2026-11-14 by pairing each key's `_function` with the keycode it types:
   1 = Ctrl key (113 CTRL_LEFT), 2 = Sym key (63 SYM), 3 = Back key, 4 = Media key (85 MEDIA_PLAY_PAUSE),
   5 = Tab key (61 TAB), 6 = Home key, 7 = Shift key (59 SHIFT_LEFT), 8 = Alt key (58 ALT_RIGHT).
   Other menu entries (codes unknown): None, Magic key, Meta key, Power key, Comma, Period, Number 0, Letter key.
   Source of the menu: aapt2 dump of the vendor APK (`edit_key_*` strings). Note: "Alt key" types ALT_RIGHT (58),
   "Shift key" types SHIFT_LEFT (59), whichever physical key it is assigned to.
3. Keys the vendor screen lists: Left shift, Right shift, Left alt, Right alt (string exists), Fn, Sym, Func1,
   Func2, Home, Back, Recent, Space, PTT, Camping lamp, Subscreen control (not all exist on this phone).
4. djbclark's mapping as of 2026-11-14 03:25 (local snapshot):
   left ⇧ → Alt key (types 58), right ⇧ → Alt key (58), Alt → Shift key (59), Fn → Ctrl key (113),
   Sym → Sym (63), Recent → Tab (61), Func1/orange → Media (85), Home/Back programmable off.
5. Test baseline set 2026-11-14 05:0x (one key at a time, each checked with getprop): left ⇧ → Shift key (59),
   right ⇧ → Shift key (59), Alt → Alt key (58 ALT_RIGHT), Fn → Ctrl (113), Sym, Recent → Tab, Func1 → Media kept.
   Changes in the vendor screen take effect at once (property and `_function` update immediately, no reboot).
6. Plektra recommends Fn → Ctrl ("Map Fn to Ctrl … recommended for Titan 2 Elite", Toolbox, string
   `fn_ctrl_map_description`). Plektra's nav mode then runs on held Fn-as-Ctrl (Fn+S = DPAD_LEFT).
7. The ○ Home key goes through the vendor shortcut layer (A85ShortcutFunction in com.agui.shortcutsettings), not
   straight to Android: short press = Home, long press (~0.5 s) = Recents (KEYCODE_APP_SWITCH), double press = Back.
   Home is injected only after the double-press window closes, ~0.5-0.7 s after ○ is released (QA S6, 2026-11-14).

## 4. Plektra storage
1. `shared_prefs/plektra_prefs.xml` (main), `embedded_adb.xml` (toolbox revert records), `app_list_cache_prefs.xml`,
   `perf_img_scale.xml`, `recent_emojis_prefs.xml`; device-protected `/data/user_de/0/rocks.pkb.plektra.djb/
   shared_prefs/direct_boot_keyboard_config.xml` (layout, modifier latches, currency symbol, profile override).
2. Key remaps are the pref `hardware_key_remaps` (JSON `{"v":1,"profiles":{"titan2elite_qwerty":{"rules":[...]}}}`).
   "Swap right Shift and Enter" = rules `{match:scan, scan:54, key:60, to:66}` + `{match:key, key:66, to:60}`. A scan
   rule matches the scan code alone (`HardwareKeyRemap.targetFor`; `key` there is informational), so printed right ⇧
   becomes Enter whatever keycode the vendor layer gives it; printed ⏎ (keycode 66) becomes SHIFT_RIGHT.
3. `files/`: `ctrl_key_mappings.json` (nav mode), `user_defaults.json` (user word list), `dictionaries_serialized/
   custom/`, `keyboard_layouts/`, `theme-images/` (created by a restore, may be empty).
4. Backups: `/storage/emulated/0/Documents/Plektra/plektra-backup-YYYYMMDD-HHMM.zip` (prefs/plektra_prefs.json,
   files/*, theme-images/manifest.json, backup_meta.json).

## 5. Reset all settings (plk/reset-settings 7af9389a; device-tested 2026-11-14 on djb-20261114.0856-0ff87be5)
1. Search "factory" finds the row and highlights it in Advanced. Back up now → saves ZIP → confirm dialog.
   Reset stays disabled with "rese"; enabled with "reset"; toast shown; Plektra stays the IME.
2. From the restored 61-key state it removed 31 keys (incl. `hardware_key_remaps`, `keyboard_layout`, autocap,
   trackpad, launcher shortcuts) and kept the same 30 both times (ring/backlight, titan2_*, privileged backend,
   user_dictionary_entries, tutorial/migration markers). `ctrl_key_mappings.json` reverted to the asset default;
   `user_defaults.json` unchanged. Restore of the 04:25 ZIP brings all 61 back.
3. Pref/file snapshots before and after are kept locally with the QA run.

## 6. Modifier behaviour observed with real keys (2026-11-14, after Reset all, swap on, baseline 3.5)
1. A plain quick tap of printed Alt arms a one-shot Alt: a blue rounded line appears at the left edge of the screen
   (most likely Plektra's modifier indicator), the next letter types its Alt symbol (`a` → `*`), and the line goes.
2. Alt held over a quick tap of a Shift (printed left ⇧, or printed ⏎ remapped to Shift) then released behaves as
   an Alt tap: the next `a` types `*`. Shift is a modifier, so the hold counts as a tap.
3. Alt held over a quick tap of printed right ⇧ remapped to Enter (313 ms roll) types the new line and does NOT
   arm Alt; the next `a` types `A` (auto-capitalise first letter defaults to on).
4. So QA finding F2 (12a, injected run) does not reproduce as a remap bug with real keys; it was the default
   one-shot-Alt-on-tap behaviour.
5. When asking an operator to press a key, also say where it is: printed right ⇧ is on the right side of the
   keyboard, printed left ⇧ in the bottom-left corner (2026-11-14: "printed right ⇧" alone was misread).

## 7. Dialog without a text field (QA S3 / F3, Calculator ⋮ → Choose theme, 2026-11-14)
1. With Cancel focused (via Tab), printed right ⇧ (raw KEY_RIGHTSHIFT) and printed ⏎ (raw KEY_ENTER) each moved the
   focus back to the first item ("System default") instead of activating Cancel. No Plektra editor is bound there
   (`dumpsys input_method`: `mServedView=null`, `mInputShown=false`). Cause (isolated 2026-11-14, see 5): the press
   woke the keyboard backlight, which relaunched Calculator and rebuilt the dialog. Not a Plektra effect.
2. The Recent key arrives raw as KEY_APPSELECT; the vendor layer turns it into Tab (61) above the kernel.
3. Space never appears on the keyboard input device in `getevent` (Space is also the fingerprint sensor; vendor
   package `com.agui.spacebarkey`). Outside a text field, pressing it left Calculator and showed the launcher page
   Calculator was opened from (more than Back, which only closes the dialog). Do not use Space as a control key.
4. Screen trackpad (`screen_trackpad_enabled`) was on before Reset all and is off after it (default false);
   `nav_mode_ctrl_hold_enabled` was true before and is back to its default after.
5. **A key that wakes the keyboard backlight changes the configuration and relaunches the foreground activity.**
   Evidence (logcat main/system/events, one press of printed right ⇧ in the Choose theme dialog): `InputReader`
   `aguiSetProgrammableKey` scan 54 → 59, then `KeyboardLightController: pressKeyToPowerOn` (+9 ms), then
   `ActivityTaskManager: Config changes=30` (keyboard | keyboardHidden, +30 ms), then
   `wm_relaunch_resume_activity … Calculator,30`. The relaunched dialog puts focus back on its default item. An app
   that does not declare `configChanges="keyboard|keyboardHidden"` loses its focus/dialog state this way. IMEs see it
   as `onConfigurationChanged` with diff 0x30. Adding an input device (for example a uinput test keyboard) does the same.
   It also goes the other way: a press of the printed Recent key (vendor Tab, scanCode 580, not on the keyboard
   device) was followed within 40 ms by `Config changes=30` to `-keyb/v/h` (keyboard NOKEYS, hard keyboard hidden)
   and another relaunch; the next printed right ⇧ press switched it back to `qwerty/v/v` and relaunched again.
   So alternating the Recent key and keyboard keys can relaunch the app on each press. Waking the keyboard first
   does not avoid it. Keyboard-focus tests need an app that handles `keyboard|keyboardHidden` itself
   (`dumpsys package` does not show `configChanges` on this phone: `adb pull` the APK from `pm path <pkg>` and
   read `android:configChanges` with `aapt2 dump xmltree --file AndroidManifest.xml`; bits 0x30 must be set), or
   the result reflects the relaunch, not the key.
6. `getevent -lt` timestamps are CLOCK_MONOTONIC; converting them with `/proc/uptime` (CLOCK_BOOTTIME) drifted by
   about 11 s here. Use the vendor `InputReader` lines in logcat to line keys up with other log events.
7. **Config-safe retest (QuickEdit `com.rhmsoft.edit.pro`, ⋮ → Visual Styles: radio list, CANCEL/OK, no text field;
   its `MainActivity` declares configChanges 0xdb0, which includes keyboard|keyboardHidden).** The `Config changes=30`
   flips still happen on Recent and on backlight-waking presses, but QuickEdit is not relaunched (only the launcher
   is), and the printed Recent key (vendor Tab) moves focus through the list to CANCEL normally. With CANCEL focused,
   printed right ⇧ (with Plektra's "Swap right Shift and Enter" on) did **nothing**: the vendor delivered it as
   `KEYCODE_SHIFT_LEFT` (scanCode 54, the vendor "Shift key" mapping), and Plektra logged nothing for it. This is
   Android by design, not a phone setting: a stock AlertDialog with no text-input view sets `FLAG_ALT_FOCUSABLE_IM`
   (AOSP `AlertController`), so the window has no IME focus and `ImeFocusController` passes keys straight to the app
   (`mServedView=null`; the `fallbackInputConnection` in dumpsys is only the client's registered fallback, not an IME
   session). So **IME-level key remaps (Plektra's included) cannot act in such dialogs**; the key acts as whatever
   the vendor layer maps it to. Remapping there would need an AccessibilityService with key filtering (consume) plus
   injection (e.g. the Shizuku broker), as Key Mapper does. Plektra's scan-code rule (54) itself survives the vendor
   rewrite to keycode 59. Stock Settings has few config-safe activities
   (homepage and Wi-Fi/network dialogs); Calculator, Keep, Clock, Contacts, Dialer and Files have none.
8. `config_preventImeStartupUnlessTextEditor` resolves to `false` on this phone (`adb shell cmd overlay lookup android
   android:bool/config_preventImeStartupUnlessTextEditor`, overlays applied), so Android keeps the IME session in
   ordinary non-text windows; only windows flagged `FLAG_ALT_FOCUSABLE_IM` (text-less AlertDialogs, list popups)
   bypass the IME.

## 8. Remapped key release lost in a restartInput gap (QA S4; fix 47e15a70 on plk/key-remap-v2)
1. Repro: KeyProbe mode "restart 1x", N=100 ms, "Swap right Shift and Enter" on, press printed ⏎ (acts as Shift) so the
   release lands between the app's restartInput and the IME's onStartInput(restarting): about 150-160 ms on this phone
   (a ~100 ms tap releases before the restart, ~260 ms after the start). Hand timing misses often: ask for 10-15 presses.
2. Before the fix (djb-20261114.0856-0ff87be5): app log `sendKeyEvent on inactive InputConnection`, KeyProbe `held: A=[SHIFT_RIGHT]` for good.
3. After (djb-20261114.1333-6275dd3f, 2026-11-14): same warning, then Plektra re-sends a CANCELED UP through the new
   connection (`route=new (sent again)`, `cancelled UP ... taken=true`); KeyProbe `held=[]`. PASS.

## 9. Held remapped keys across focus changes (QA S5-S8, djb-20261114.1333-6275dd3f, 2026-11-14)
Only "Swap right Shift and Enter" on: printed ⏎ (scan 28) types SHIFT_RIGHT(60), printed right ⇧ (scan 54) types ENTER(66).
1. S5, roll ⌫ down, ⏎ down, ⌫ up, ⏎ up across a KeyProbe restartInput: 7/7 valid rolls PASS (DEL UP delivered with
   meta=0x81, not CANCELED; nothing held after). In 5 of them the app saw the held Shift arrive as `UP ... CANCELED`
   (flags 0x24) when the connection was replaced; the IME-side Shift state stayed intact.
2. S6, hold ⏎ in a Keep note and press ○ Home: ⏎ released on the launcher shows no Plektra shortcut sheet, and the
   next letter back in Keep types lower case. PASS.
3. S7, hold ⏎ and pull the notification shade down: the app gets `UP SHIFT_RIGHT(60) ... CANCELED`, nothing stays
   held; right ⇧ then types ENTER(66) scan 54 with meta 0, ⏎ types SHIFT_RIGHT(60) scan 28. PASS.
4. S8, hold right ⇧ in a Keep note until new lines repeat, switch to Chrome with the recents gesture, release: Chrome's
   address bar stays empty and the next `a` is lower case. PASS. (Chrome has to be in recents first.)
5. Auto-rotate (`settings system accelerometer_rotation`) was once found back at its default 1 (written by
   pkg:android, defaultSystemSet:true) with no agent or Plektra code touching it; cause unknown. Later flips were
   the operator's Quick Settings rotation tile, which shows "0°" when locked.
