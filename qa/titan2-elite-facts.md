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
5. Plektra recommends Fn → Ctrl ("Map Fn to Ctrl … recommended for Titan 2 Elite", Toolbox, string
   `fn_ctrl_map_description`). Plektra's nav mode then runs on held Fn-as-Ctrl (Fn+S = DPAD_LEFT).

## 4. Plektra storage
1. `shared_prefs/plektra_prefs.xml` (main), `embedded_adb.xml` (toolbox revert records), `app_list_cache_prefs.xml`,
   `perf_img_scale.xml`, `recent_emojis_prefs.xml`; device-protected `/data/user_de/0/rocks.pkb.plektra.djb/
   shared_prefs/direct_boot_keyboard_config.xml` (layout, modifier latches, currency symbol, profile override).
2. Key remaps are the pref `hardware_key_remaps` (JSON `{"v":1,"profiles":{"titan2elite_qwerty":{"rules":[...]}}}`).
   "Swap right Shift and Enter" = rules `{match:scan, scan:54, key:60, to:66}` + `{match:key, key:66, to:60}`: the
   scan-54 rule only fires if the key arrives as SHIFT_RIGHT (60), so a vendor remap of right ⇧ breaks it.
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
