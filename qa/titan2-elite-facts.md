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

## 2. Physical keys (scan codes; KeyProbe + `getevent -lt`, 2026-11-14)
1. Keycaps are factory. Printed ⏎ Enter = scan 28. Printed right ⇧ = scan 54. Printed Fn = scan 251 (FUNC3).
   Sym = scan 253 (AGUI_SYM). Home = scan 102. Back = scan 158. Orange side key = ff_key 249 (vendor "Func1").
   Volume = gpio-keys 115/114. Power = ff_key 116.
2. Name keys in prompts as "printed X (now acts as Y)" .

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
   `KEYCODE_SHIFT_LEFT` (scanCode 54, the vendor "Shift key" mapping), and Plektra logged nothing for it. With no text
   field the IME is bound only through `fallbackInputConnection` (`mServedView=null`), and Android does not route
   hardware keys through the IME then, so **Plektra's IME-level key remaps do not apply in windows without a text
   field**. The key acts as whatever the vendor layer maps it to. Stock Settings has few config-safe activities
   (homepage and Wi-Fi/network dialogs); Calculator, Keep, Clock, Contacts, Dialer and Files have none.
