"""Key naming: tokens, rendering, lint, and the keymap. No phone: adb is faked."""

import json
from pathlib import Path
from types import SimpleNamespace

import pytest
from typer.testing import CliRunner

from plektra_qa.cli import app
from plektra_qa.hitl_runner import load_suite
from plektra_qa.keys import (
    DEFAULT_PROFILE,
    Chord,
    Flavor,
    KeyNamingError,
    KeyResolutionError,
    LiveReadError,
    Rule,
    find_tokens,
    lint,
    load_profile,
    localise_texts,
    read_live,
    read_vendor_props,
    render,
    resolve_keymap,
    rules_from_prefs,
)

ROOT = Path(__file__).resolve().parents[1]
BASELINE = load_profile(DEFAULT_PROFILE)
SERIAL = "TESTSERIAL"


def screen(text, keymap=BASELINE):
    return render(text, keymap, Flavor.SCREEN)


# --- tokens ------------------------------------------------------------------


def test_token_parsing_names_modifiers_keys_and_alternatives():
    (token,) = find_tokens("Press <Ctrl+A> now")
    assert token.raw == "<Ctrl+A>"
    assert token.alternatives == (Chord(("Ctrl",), "A"),)
    (alt,) = find_tokens("<Tab|Enter>")
    assert [c.key for c in alt.alternatives] == ["Tab", "Enter"]
    (shift_tab,) = find_tokens("<shift+tab>")
    assert shift_tab.alternatives == (Chord(("Shift",), "Tab"),)
    (alias,) = find_tokens("<control+a>")
    assert alias.alternatives == (Chord(("Ctrl",), "A"),)
    assert find_tokens("<Ctrl+A>, then <Ctrl+C>")[1].alternatives[0].key == "C"


def test_angle_brackets_with_spaces_are_not_tokens():
    assert find_tokens("if a < b and b > c") == []


@pytest.mark.parametrize(
    "bad",
    ["<Foo>", "<Ctrl+Foo>", "<Tab+A>", "<Ctrl+Ctrl+A>", "<Ctrl+>"],
)
def test_malformed_tokens_are_refused_naming_the_token(bad):
    with pytest.raises(KeyNamingError) as err:
        find_tokens(f"Press {bad}")
    assert bad in str(err.value) or "empty" in str(err.value)


# --- rendering -----------------------------------------------------------------


def test_render_the_ctrl_a_ctrl_c_example_in_all_three_flavors():
    text = "<Ctrl+A>, then <Ctrl+C>"
    assert screen(text) == (
        "Your Ctrl + A, then your Ctrl + C. Keycaps: hold Fn, tap A; then hold Fn, tap C."
    )
    assert render(text, BASELINE, Flavor.SHADE) == (
        "Your Ctrl + A, then your Ctrl + C. Keycaps: Fn+A, then Fn+C."
    )
    assert render(text, BASELINE, Flavor.SPOKEN) == (
        "Your Control plus letter A, then your Control plus letter C. "
        "By keycaps, hold the key printed F N, tap letter A. "
        "Then hold the key printed F N, tap letter C."
    )


def test_render_keeps_the_authors_words_and_ends_the_sentence_once():
    assert screen("Press <Ctrl+A>.") == "Press your Ctrl + A. Keycaps: hold Fn, tap A."
    assert screen("Press <Ctrl+A>!") == "Press your Ctrl + A! Keycaps: hold Fn, tap A."
    assert screen("Press <Ctrl+A>") == "Press your Ctrl + A. Keycaps: hold Fn, tap A."


def test_render_without_tokens_is_unchanged():
    assert screen("Type hello") == "Type hello"
    assert render("Type a, comma, b.", BASELINE, Flavor.SPOKEN) == "Type a, comma, b."


def test_render_function_keys_and_alternatives():
    assert screen("Press <Backspace>") == "Press your Backspace. Keycaps: tap ⌫."
    out = screen("<Tab|Enter> on Plektra")
    assert out.startswith("Your Tab or your Enter on Plektra. Keycaps: tap Recent or tap right ⇧")
    assert "Shift + your Tab" in screen("<Shift+Tab>")


def test_shift_prefers_the_key_that_is_shift_by_factory():
    # left shift and printed Enter both act as Shift in the baseline; left shift wins.
    assert BASELINE.resolve("Shift").id == "shift_l"
    assert BASELINE.resolve("Enter").id == "shift_r"
    assert BASELINE.resolve("Ctrl").id == "fn"
    assert BASELINE.resolve("Tab").id == "recent"


def test_place_hint_is_given_once_and_only_in_the_full_texts():
    out = screen("<Shift+Tab>, then <Shift+A>")
    assert out.count("bottom left corner") == 1
    assert "corner" not in render("<Shift+Tab>", BASELINE, Flavor.SHADE)
    assert "bottom left corner" in render("<Shift+Tab>", BASELINE, Flavor.SPOKEN)


def test_digits_and_letters_are_keycaps_of_themselves():
    assert screen("<Alt+4>").endswith("Keycaps: hold Alt, tap 4.")
    assert "digit 4" in render("<Alt+4>", BASELINE, Flavor.SPOKEN)


def test_unresolvable_token_is_refused_by_name_never_single_version():
    with pytest.raises(KeyResolutionError) as err:
        screen("Press <Esc>")
    assert "<Esc>" in str(err.value)
    with pytest.raises(KeyResolutionError):
        render("<Meta+A>", BASELINE, Flavor.SPOKEN)
    # Fn does nothing by itself once it acts as Ctrl.
    with pytest.raises(KeyResolutionError):
        screen("<Fn+S>")


# --- lint ----------------------------------------------------------------------


@pytest.mark.parametrize(
    "text,name,suggest",
    [
        ("Press Ctrl+A", "Ctrl", "<Ctrl+A>"),
        ("press ctrl + c now", "ctrl", "<ctrl+c>"),
        ("Hold CONTROL", "CONTROL", "<CONTROL>"),
        ("Use Shift+Tab", "Shift", "<Shift+Tab>"),
        ("backspace the previous word", "backspace", "<backspace>"),
        ("Press Enter", "Enter", "<Enter>"),
        ("Press Return", "Return", "<Return>"),
        ("Press Esc", "Esc", "<Esc>"),
        ("tap Fn then Sym", "Fn", "<Fn>"),
        ("Tap Delete", "Delete", "<Delete>"),
        ("Hit Meta", "Meta", "<Meta>"),
        ("Press Back", "Back", "<Back>"),
        ("Press Home", "Home", "<Home>"),
        ("Open Recent", "Recent", "<Recent>"),
        ("Press Alt", "Alt", "<Alt>"),
        ("Press Del", "Del", "<Del>"),
    ],
)
def test_lint_flags_bare_key_names_and_suggests_the_token(text, name, suggest):
    hits = lint(text)
    assert hits and hits[0] == (name, suggest)


@pytest.mark.parametrize(
    "text",
    [
        "Press <Ctrl+A>, then <Ctrl+C>",
        "Type hello",
        "Go to the home screen",
        "Open the home page",
        "Come back later",
        "Type it, then come back to the field",
        "Return to the note",
        "Open a new tab in the browser",
        "The shifted letters, the entered text, backspaced words",
        "Type a, comma, space, b, period.",
    ],
)
def test_lint_allows_tokens_and_ordinary_prose(text):
    assert lint(text) == []


def test_lint_message_points_to_the_field_and_the_token_form():
    with pytest.raises(KeyNamingError) as err:
        localise_texts({"prompt": "ok", "shade": "Press Ctrl+A"}, BASELINE, "trial 3")
    message = str(err.value)
    assert "trial 3" in message and "field 'shade'" in message and "<Ctrl+A>" in message


def test_lint_also_covers_the_spoken_line():
    with pytest.raises(KeyNamingError) as err:
        localise_texts({"prompt": "<Ctrl+A>", "speak": "Press Control A"}, BASELINE, "trial 1")
    assert "field 'speak'" in str(err.value)


# --- suites ----------------------------------------------------------------------


def _suite(tmp_path, trial_yaml):
    path = tmp_path / "s.yaml"
    path.write_text(f"name: t\nphases:\n  - name: p\n    trials:\n{trial_yaml}")
    return path


def test_load_suite_refuses_a_bare_key_name_with_a_clear_error(tmp_path):
    path = _suite(tmp_path, '      - prompt: "Press Ctrl+A"\n        expect: "x"\n')
    with pytest.raises(KeyNamingError) as err:
        load_suite(path, BASELINE)
    assert "field 'prompt'" in str(err.value) and "<Ctrl+A>" in str(err.value)
    assert "s.yaml" in str(err.value) and "trial 1" in str(err.value)


def test_load_suite_refuses_an_unresolvable_token(tmp_path):
    path = _suite(tmp_path, '      - prompt: "Press <Esc>"\n        expect: "x"\n')
    with pytest.raises(KeyResolutionError) as err:
        load_suite(path, BASELINE)
    assert "<Esc>" in str(err.value) and "trial 1" in str(err.value)


def test_trial_with_tokens_gets_all_three_versions(tmp_path):
    path = _suite(
        tmp_path,
        '      - prompt: "<Ctrl+A> in the note"\n        shade: "Press <Ctrl+A>"\n        expect: "x"\n',
    )
    (trial,) = load_suite(path, BASELINE)
    assert trial.prompt == "Your Ctrl + A in the note. Keycaps: hold Fn, tap A."
    assert trial.shade == "Press your Ctrl + A. Keycaps: Fn+A."
    assert trial.speak.startswith("Press your Control plus letter A. By keycaps, hold the key printed F N")


def test_trial_without_tokens_is_untouched(tmp_path):
    path = _suite(tmp_path, '      - prompt: "digits: 42"\n        shade: "Type 42"\n        expect: "42"\n')
    (trial,) = load_suite(path, BASELINE)
    assert (trial.prompt, trial.shade, trial.speak) == ("digits: 42", "Type 42", None)


def test_spoken_text_falls_back_to_shade_then_prompt():
    fields = {"prompt": "<Tab> then go", "expect": "x"}
    out = localise_texts(fields, BASELINE, "t")
    assert out["speak"].startswith("Your Tab then go.")
    assert out["shade"].startswith("Your Tab then go.")
    explicit = localise_texts({**fields, "speak": "Press <Tab>"}, BASELINE, "t")
    assert explicit["speak"].startswith("Press your Tab.")


def test_smoke_suite_uses_tokens_and_keeps_its_expectations():
    trials = load_suite(ROOT / "suites" / "smoke_chords.yaml", BASELINE)
    assert [t.expect for t in trials] == ["Hello", "42", "a, b.", "ok", "alpha", "beta", "beta"]
    backspace, tab, chord = trials[3], trials[5], trials[6]
    assert "your Backspace" in backspace.shade and "Keycaps: ⌫." in backspace.shade
    assert "your Tab or your Enter" in tab.shade
    assert "tap Recent or tap right ⇧" in tab.shade or "Recent or right ⇧" in tab.shade
    assert chord.shade == "Press your Ctrl + A, then your Ctrl + C. Keycaps: Fn+A, then Fn+C."
    assert "Control plus letter A" in chord.speak
    assert chord.verify_mode == "clipboard" and chord.clear_field is False


# --- keymap from the phone ----------------------------------------------------------

GETPROP = """\
[persist.sys.agui_programmable_alt_l_key]: [113]
[persist.sys.agui_programmable_back_key]: [0]
[persist.sys.agui_programmable_fn_key]: [0]
[persist.sys.agui_programmable_func1_key]: [85]
[persist.sys.agui_programmable_home_key]: [0]
[persist.sys.agui_programmable_recent_key]: [61]
[persist.sys.agui_programmable_shift_l_key]: [59]
[persist.sys.agui_programmable_shift_r_key]: [59]
[persist.sys.agui_programmable_sym_key]: [63]
[persist.sys.agui_programmable_volume_key]: [24]
[ro.build.version.release]: [16]
"""
PREFS = """<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="autocap" value="true" />
    <string name="hardware_key_remaps">{&quot;v&quot;:1,&quot;profiles&quot;:{&quot;titan2elite_qwerty&quot;:{&quot;rules&quot;:[{&quot;match&quot;:&quot;scan&quot;,&quot;scan&quot;:54,&quot;key&quot;:60,&quot;to&quot;:66},{&quot;match&quot;:&quot;key&quot;,&quot;key&quot;:66,&quot;to&quot;:60}]}}}</string>
</map>
"""


def fake_adb(getprop=GETPROP, prefs=PREFS, calls=None):
    def run(argv):
        if calls is not None:
            calls.append(argv)
        assert argv[:3] == ["adb", "-s", SERIAL]
        if argv[3:] == ["shell", "getprop"]:
            return SimpleNamespace(returncode=0 if getprop is not None else 1, stdout=getprop or "", stderr="")
        if argv[3:5] == ["exec-out", "run-as"]:
            if prefs is None:
                return SimpleNamespace(returncode=1, stdout="", stderr="run-as: package not debuggable")
            return SimpleNamespace(returncode=0, stdout=prefs, stderr="")
        raise AssertionError(f"unexpected adb call {argv}")

    return run


def test_vendor_props_are_parsed_from_getprop_output():
    props = read_vendor_props(SERIAL, fake_adb())
    assert props["alt_l"] == 113 and props["fn"] == 0 and props["shift_l"] == 59
    assert "volume" in props and "build" not in props


def test_prefs_rules_are_read_from_the_stored_json():
    assert rules_from_prefs(PREFS, "titan2elite_qwerty") == (
        Rule("scan", 66, scan=54, key=60),
        Rule("key", 60, key=66),
    )
    assert rules_from_prefs(PREFS, "other_profile") == ()
    assert rules_from_prefs("<map></map>", "titan2elite_qwerty") == ()
    legacy = '<map><boolean name="key_remap_swap_shift_right_enter" value="true" /></map>'
    assert len(rules_from_prefs(legacy, "titan2elite_qwerty")) == 2
    newer = '<map><string name="hardware_key_remaps">{&quot;v&quot;:2}</string></map>'
    assert rules_from_prefs(newer, "titan2elite_qwerty") is None


def test_live_keymap_reads_both_layers_and_only_reads(tmp_path):
    calls = []
    keymap, notes = read_live(SERIAL, BASELINE, run=fake_adb(calls=calls))
    assert "Plektra remap layer" in keymap.source
    # Alt types Ctrl (113) and Fn is native here, so Ctrl is the printed Alt key.
    assert keymap.resolve("Ctrl").id == "alt"
    assert keymap.resolve("Enter").id == "shift_r"  # Plektra's swap read from the prefs
    assert "volume" in notes[0]  # a vendor key outside the keycap table is noted
    verbs = {tuple(c[3:5]) for c in calls}
    assert verbs == {("shell", "getprop"), ("exec-out", "run-as")}
    assert all(c[3] in ("shell", "exec-out") for c in calls)  # never `am`, `input`, `setprop`
    assert "cat" in calls[1] and not any(w in c for c in calls for w in ("setprop", "input", "am", "settings"))


def test_unreadable_plektra_layer_uses_the_profile_rules_and_says_so():
    keymap, notes = read_live(SERIAL, BASELINE, run=fake_adb(prefs=None))
    assert "Plektra remap from the profile" in keymap.source
    assert any("not readable" in n for n in notes)
    assert keymap.rules == BASELINE.rules


def test_failed_getprop_is_a_live_read_error():
    with pytest.raises(LiveReadError):
        read_vendor_props(SERIAL, fake_adb(getprop=None))


def test_scan_rule_beats_key_rule_and_the_first_rule_for_a_source_wins():
    from dataclasses import replace

    both = replace(BASELINE, rules=(Rule("key", 59, key=61), Rule("scan", 66, scan=580, key=187)))
    recent = next(c for c in both.caps if c.id == "recent")
    # vendor makes Recent type Tab (61); the key rule says 61 -> 59 but the scan rule says 580 -> 66.
    assert both.function_of(recent) == "Enter"
    only_key = replace(BASELINE, rules=(Rule("key", 59, key=61),))
    assert only_key.function_of(recent) == "Shift"
    twice = replace(BASELINE, rules=(Rule("scan", 66, scan=580), Rule("scan", 59, scan=580)))
    assert twice.function_of(recent) == "Enter"


def test_vendor_zero_means_the_key_is_native():
    from dataclasses import replace

    keymap = replace(BASELINE, vendor={**BASELINE.vendor, "fn": 0})
    # the helper that builds from getprop drops zeros; function_of treats a 0 the same way.
    fn = next(c for c in keymap.caps if c.id == "fn")
    assert keymap.function_of(fn) == "Fn"


# --- live vs profile ---------------------------------------------------------------------


def test_live_wins_over_the_profile_and_the_cli_says_so():
    env = {"ADB_TARGET": SERIAL}
    resolved = resolve_keymap(env, run=fake_adb())
    assert resolved.keymap.resolve("Ctrl").id == "alt"
    assert resolved.profile.resolve("Ctrl").id == "fn"
    text = resolved.describe()
    assert text.startswith("keymap: live")
    assert "live wins" in text and "Fn acts as Fn (profile: Ctrl)" in text
    assert render("<Ctrl+A>", resolved.keymap).endswith("Keycaps: hold Alt, tap A.")


def test_agreeing_live_read_prints_no_difference():
    getprop = GETPROP.replace("alt_l_key]: [113]", "alt_l_key]: [58]").replace("fn_key]: [0]", "fn_key]: [113]")
    resolved = resolve_keymap({"ADB_TARGET": SERIAL}, run=fake_adb(getprop=getprop))
    assert "differs" not in resolved.describe()


def test_without_adb_target_the_profile_is_used_and_adb_is_not_called():
    calls = []
    resolved = resolve_keymap({}, run=fake_adb(calls=calls))
    assert resolved.keymap is resolved.profile and calls == []
    assert resolved.describe() == "keymap: profile titan2-elite-baseline"


def test_env_selects_the_profile_file_and_can_force_it(tmp_path):
    path = tmp_path / "mine.yaml"
    path.write_text(
        "name: mine\nkeycaps:\n  - {id: ctl, screen: 'Ctl', spoken: 'the Ctl key', native: 113}\n"
        "  - {id: tb, screen: 'Tb', spoken: 'the Tb key', native: 61}\n"
    )
    resolved = resolve_keymap({"PLEKTRA_QA_KEYMAP": str(path)}, run=fake_adb())
    assert resolved.keymap.name == "mine"
    assert render("<Ctrl+Tab>", resolved.keymap) == "Your Ctrl + your Tab. Keycaps: hold Ctl, tap Tb."
    forced = resolve_keymap({"ADB_TARGET": SERIAL, "PLEKTRA_QA_KEYMAP_SOURCE": "profile"}, run=fake_adb())
    assert forced.keymap is forced.profile


def test_live_failure_falls_back_with_a_note_or_errors_when_asked():
    env = {"ADB_TARGET": SERIAL}
    resolved = resolve_keymap(env, run=fake_adb(getprop=None))
    assert resolved.keymap is resolved.profile and "live read failed" in resolved.describe()
    with pytest.raises(LiveReadError):
        resolve_keymap(env, run=fake_adb(getprop=None), on_live_failure="error")
    with pytest.raises(LiveReadError):
        resolve_keymap({**env, "PLEKTRA_QA_KEYMAP_SOURCE": "live"}, run=fake_adb(getprop=None))


def test_profile_with_a_vendor_entry_for_no_keycap_is_refused(tmp_path):
    path = tmp_path / "bad.yaml"
    path.write_text("keycaps:\n  - {id: a, screen: A, spoken: a, native: 29}\nvendor: {zz: 113}\n")
    with pytest.raises(ValueError, match="zz"):
        load_profile(path)


# --- CLI ---------------------------------------------------------------------------------


def test_render_command_prints_the_three_versions():
    result = CliRunner().invoke(app, ["render", "<Ctrl+A>, then <Ctrl+C>"])
    assert result.exit_code == 0
    assert "keymap: profile titan2-elite-baseline" in result.output
    assert "screen: Your Ctrl + A, then your Ctrl + C. Keycaps: hold Fn, tap A; then hold Fn, tap C." in result.output
    assert "shade: " in result.output and "spoken: " in result.output


def test_render_command_json_and_refusal():
    ok = CliRunner().invoke(app, ["render", "--json", "<Tab>"])
    data = json.loads(ok.output)
    assert set(data) == {"keymap", "screen", "shade", "spoken"}
    refused = CliRunner().invoke(app, ["render", "<Esc>"])
    assert refused.exit_code == 2 and "<Esc>" in refused.output
