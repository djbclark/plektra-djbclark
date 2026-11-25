"""Redo of the trial on screen, the screen it restores, the wait card and the bar's protocol for them
(djbclark, 2026-11-25)."""

import io
import json
from pathlib import Path
from types import SimpleNamespace

import pytest
from rich.console import Console
from typer.testing import CliRunner

from plektra_qa import cli, hitl_runner
from plektra_qa.button_bar import WAIT_TEXT, ButtonBar, parse_line, parse_pm_uid, parse_pm_version
from plektra_qa.controller import ScreenController
from plektra_qa.end_state import END_DONE_TEXT
from plektra_qa.hitl_runner import (
    LATER_BUTTONS,
    RECOVERY_BUTTONS,
    SKIPPED,
    TRIAL_BUTTONS,
    ChordTrial,
    load_suite,
    plan_trials,
    run_chord_batch,
    trial_matched,
)
from plektra_qa.keys import resolve_keymap
from plektra_qa.ledger import trial_key
from plektra_qa.recording import Recorder
from plektra_qa.screen_gate import ScreenGate
from plektra_qa.start_gate import StartGate

from test_button_bar import APP_UID, DEVICE_NOW, _line, _Pipe, _Proc, _wait_for

SUITES = Path(__file__).resolve().parents[1] / "suites"
APP = "branislav667.pinnote:id/"
BODY, TITLE, FAB = APP + "pin_text", APP + "title", APP + "notes_fab"
HINTS = {BODY: "Start writing…", TITLE: "Title"}


class _Element:
    def __init__(self, notes, rid):
        self.notes, self.rid = notes, rid

    def _id(self):
        return self.notes.focus if self.rid is None else self.rid

    def exists(self):
        if self._id() == FAB:
            return self.notes.fields is None and self.notes.has_fab
        return self.notes.fields is not None and self._id() in self.notes.fields

    def info(self):
        return SimpleNamespace(focused=self._id() == self.notes.focus, resourceName=self._id())

    def click(self):
        if self._id() == FAB:
            self.notes.new_note()
        else:
            self.notes.focus = self._id()

    def get_text(self):
        rid = self._id()
        return self.notes.fields[rid] or self.notes.hints.get(rid, "")

    def clear_text_field(self):
        self.notes.fields[self._id()] = ""


class _Notes:
    """A note app: a list with a "new note" button, and an editor with a body and a title."""

    def __init__(self, open_note=None, hints=HINTS):
        self.hints = hints
        self.fields = dict(open_note) if open_note is not None else None
        self.focus = BODY if open_note is not None else None
        self.has_fab = True
        self.opened = 0
        self.clipboard = ""

    def new_note(self):
        self.fields, self.focus = {BODY: "", TITLE: ""}, BODY
        self.opened += 1

    def __call__(self, **kwargs):
        if kwargs.get("focused"):
            return _Element(self, None)
        return _Element(self, kwargs["resourceId"])

    def start_activity(self, **_request):
        return None

    def press_key(self, _key):
        self.fields, self.focus = None, None

    def dump_window_hierarchy(self):
        nodes = "".join(
            f'<node resource-id="{rid}" class="android.widget.EditText" text="{text or self.hints.get(rid, "")}"/>'
            for rid, text in (self.fields or {}).items()
        )
        return io.BytesIO(f"<hierarchy>{nodes}</hierarchy>".encode())

    def get_clipboard(self):
        return self.clipboard

    def set_clipboard(self, text):
        self.clipboard = text

    # what the person does
    def type(self, text):
        self.fields[self.focus] = text

    def tab(self):
        self.focus = TITLE

    def snapshot(self):
        return (self.focus, dict(self.fields or {}), self.clipboard)


class _Bar:
    """Each card runs the person's next step: what they do on the phone, then the button."""

    def __init__(self, notes, *steps):
        self.notes, self.steps = notes, list(steps)
        self.cards, self.seen, self.log, self._next = [], [], [], None

    def show(self, text, buttons=()):
        self.cards.append((text, tuple(buttons)))
        self.seen.append(self.notes.snapshot())
        self.log.append("show")
        action, self._next = self.steps.pop(0) if self.steps else (None, "stop")
        if action is not None:
            action(self.notes)

    def wait(self, pressed=False):
        self.log.append(f"wait:{pressed}")

    def hide(self):
        self.log.append("hide")

    def pressed(self):
        button, self._next = self._next, None
        return button


def _typed(*actions):
    def do(notes):
        for action in actions:
            action(notes)

    return do


def _type(text):
    return lambda notes: notes.type(text)


def _tab(notes):
    notes.tab()


def _run(notes, bar, trials, **kwargs):
    seen = {"discard": [], "redo": []}
    results = run_chord_batch(
        ScreenController(notes), trials, Console(file=io.StringIO()), overlay=bar,
        on_discard=lambda number, trial: seen["discard"].append(number),
        on_redo=lambda trial: seen["redo"].append(trial.number),
        **kwargs,
    )
    return results, seen


@pytest.fixture
def smoke():
    keymap = resolve_keymap(env={"PLEKTRA_QA_KEYMAP_SOURCE": "profile"}).keymap
    return load_suite(SUITES / "smoke_chords.yaml", keymap)


CLEAN = (BODY, {BODY: "", TITLE: ""})


def test_a_trial_that_names_no_field_borrows_a_start_field_not_a_field_to_read(smoke):
    six = plan_trials(smoke, lambda trial: trial.number != 6)
    assert [t.number for t in six] == [6]
    assert six[0].resource_id is None and six[0].start_resource_id == BODY
    assert six[0].click_resource_id == FAB and six[0].package == "branislav667.pinnote"


def test_trial_6_run_alone_after_a_tab_reads_the_title_it_was_typed_in(smoke):
    notes = _Notes(open_note={BODY: "old", TITLE: "Eta"})  # what the failed run left on screen
    bar = _Bar(notes, (_typed(_tab, _type("Beta")), "done"))
    results, _ = _run(notes, bar, plan_trials(smoke, lambda trial: trial.number != 6))
    assert results[0].passed and results[0].observed == "Beta"
    assert bar.seen[0][:2] == CLEAN and notes.opened == 1  # a fresh note, focus where the trial starts


def test_redo_restarts_this_trial_on_a_clean_screen_and_records_no_result_for_the_dropped_attempt(smoke):
    notes = _Notes()
    notes.clipboard = "mine"
    bar = _Bar(
        notes,
        (_typed(_tab, _type("Eta"), lambda n: n.set_clipboard("junk")), "retry"),
        (_typed(_tab, _type("Beta")), "done"),
    )
    results, seen = _run(notes, bar, plan_trials(smoke, lambda trial: trial.number != 6))
    assert [r.number for r in results] == [6] and results[0].passed
    assert seen == {"discard": [6], "redo": []}
    assert bar.seen[1] == bar.seen[0] == (*CLEAN, "mine")  # title gone, focus back, clipboard back
    assert all("retry" in buttons for _text, buttons in bar.cards[:-1])
    assert bar.cards[-1] == (END_DONE_TEXT, ())  # the end card asks for nothing


def test_prev_after_a_tab_opens_a_fresh_note_and_replays_the_previous_trial(smoke):
    notes = _Notes()
    bar = _Bar(
        notes,
        (_type("alpha"), "done"),
        (_typed(_tab, _type("Eta")), "redo"),
        (_type("alpha"), "done"),
        (_typed(_tab, _type("Beta")), "done"),
    )
    results, seen = _run(notes, bar, smoke[4:6])
    assert [r.number for r in results] == [5, 6] and all(r.passed for r in results)
    assert seen == {"discard": [6], "redo": [5]}
    assert bar.seen[2][:2] == CLEAN  # the operator's report: "Eta" stayed in the title
    assert bar.cards[0][1] == TRIAL_BUTTONS and bar.cards[1][1] == LATER_BUTTONS


def test_redo_of_a_trial_that_keeps_the_field_replays_the_trial_that_fills_it(smoke):
    notes = _Notes()
    copy = lambda n: n.set_clipboard("Beta")  # noqa: E731
    bar = _Bar(
        notes,
        (_type("alpha"), "done"),
        (_typed(_tab, _type("Beta")), "done"),
        (lambda n: n.set_clipboard("wrong"), "retry"),
        (_typed(_tab, _type("Beta")), "done"),
        (copy, "done"),
    )
    results, seen = _run(notes, bar, smoke[4:7])
    assert [c[0] for c in bar.cards] == [*(f"T{smoke[i].number}. {smoke[i].shade}" for i in (4, 5, 6, 5, 6)), END_DONE_TEXT]
    assert [r.number for r in results] == [5, 6, 7] and all(r.passed for r in results)
    assert seen == {"discard": [7], "redo": [6]}
    assert bar.seen[3][:2] == CLEAN and bar.seen[4] == (TITLE, {BODY: "", TITLE: "Beta"}, "")


def test_a_normal_start_opens_a_new_note_only_when_another_field_was_left_dirty(smoke):
    notes = _Notes()
    dirty = lambda n: n.fields.__setitem__(TITLE, "stray")  # noqa: E731
    bar = _Bar(notes, (_type("Hello"), "done"), (_type("42"), "done"), (_typed(_type("a, b."), dirty), "done"),
               (_type("ok"), "done"))
    results, _ = _run(notes, bar, smoke[:4])
    assert all(r.passed for r in results)
    assert bar.seen[2][:2] == CLEAN and bar.seen[3][:2] == CLEAN
    assert notes.opened == 2  # the first trial's note, and one more after the stray title


def test_without_a_new_note_button_redo_clears_the_other_fields_and_returns_the_focus():
    notes = _Notes(open_note={BODY: "", TITLE: ""})
    notes.has_fab = False
    trial = ChordTrial(prompt="p", expect="x", phase="p", resource_id=BODY, number=1)
    bar = _Bar(notes, (_typed(_type("y"), _tab, _type("stray")), "retry"), (_type("x"), "done"))
    results, seen = _run(notes, bar, [trial])
    assert results[0].passed and seen["discard"] == [1]
    assert bar.seen[1][:2] == CLEAN and notes.opened == 0


def test_a_kept_field_with_nothing_to_replay_is_checked_not_typed():
    notes = _Notes(open_note={BODY: "kept", TITLE: ""})
    trial = ChordTrial(prompt="p", expect="kept!", phase="p", clear_field=False, number=1)
    bar = _Bar(
        notes,
        (_type("broken"), "retry"),  # the host never types, so it cannot put "kept" back
        (_type("kept"), "retry"),  # on the recovery card: fixed by hand
        (_type("kept!"), "done"),
    )
    results, seen = _run(notes, bar, [trial])
    assert bar.cards[1][1] == RECOVERY_BUTTONS and "Reset failed" in bar.cards[1][0]
    assert results[0].passed and len(results) == 1 and seen["discard"] == [1, 1]


def test_a_trial_that_names_no_field_goes_to_the_start_field_when_nothing_is_focused(smoke):
    notes = _Notes()
    unfocus = lambda n: setattr(n, "focus", None)  # noqa: E731
    bar = _Bar(notes, (_type("alpha"), "done"), (_typed(_type("Beta"), unfocus), "done"),
               (lambda n: n.set_clipboard("Beta"), "done"))
    results, _ = _run(notes, bar, smoke[4:7])
    assert [r.error for r in results] == ["", "", ""]
    assert bar.seen[2][0] == BODY


def test_a_redo_that_cannot_reset_asks_the_person_and_next_skips(smoke):
    notes = _Notes()

    def lose_the_button(n):
        n.has_fab = False

    bar = _Bar(notes, (_typed(_type("x"), lose_the_button), "retry"), (None, "next"))
    results, seen = _run(notes, bar, plan_trials(smoke, lambda trial: trial.number != 6))
    assert bar.cards[1][1] == RECOVERY_BUTTONS and "Reset failed" in bar.cards[1][0]
    assert [r.error for r in results] == [SKIPPED] and seen["discard"] == [6]


def test_leaving_a_note_presses_back_until_it_is_gone():
    class Sticky(_Notes):
        presses = 0

        def press_key(self, key):
            self.presses += 1
            if self.presses > 1:  # the first Back only closes a keyboard
                super().press_key(key)

    notes = Sticky(open_note={BODY: "a", TITLE: "b"})
    trial = ChordTrial(prompt="p", expect="x", resource_id=BODY, click_resource_id=FAB, fresh=True)
    hitl_runner._open_target(ScreenController(notes), trial)
    assert notes.presses == 2 and notes.opened == 1


def test_between_trials_the_bar_waits_and_is_hidden_once_at_the_end(smoke):
    notes = _Notes()
    bar = _Bar(notes, (_type("alpha"), "done"), (_typed(_tab, _type("Beta")), "next"))
    _run(notes, bar, smoke[4:6])
    assert bar.log == ["show", "wait:True", "show", "wait:True", "show", "hide"]  # the last card: the end state


def test_a_trial_no_button_ended_asks_the_host_to_show_the_wait_card():
    notes = _Notes(open_note={BODY: "", TITLE: ""})
    trials = [ChordTrial(prompt="p", expect="x", resource_id=BODY, timeout_s=0.1),
              ChordTrial(prompt="q", expect="x", resource_id=BODY)]
    bar = _Bar(notes, (None, None), (_type("x"), "done"))
    _run(notes, bar, trials)
    assert bar.log[:2] == ["show", "wait:False"] and bar.log[-1] == "hide"


def test_the_clipboard_is_cleared_before_the_card_appears():
    notes = _Notes(open_note={BODY: "", TITLE: ""})
    notes.clipboard = "Beta"
    bar = _Bar(notes, (None, "stop"))
    _run(notes, bar, [ChordTrial(prompt="p", expect="Beta", verify_mode="clipboard", resource_id=BODY)])
    assert bar.seen[0][2] == ""


def test_restore_field_only_ever_clears():
    notes = _Notes(open_note={BODY: "typed", TITLE: ""})
    controller = ScreenController(notes)
    assert controller.editable_fields() == {BODY: "typed", TITLE: "Title"}
    assert controller.restore_field(TITLE, "Title") and notes.fields[BODY] == "typed"
    assert controller.restore_field(BODY, HINTS[BODY]) and notes.fields[BODY] == ""
    assert not controller.restore_field(BODY, "something that was there")
    assert not controller.restore_field("no:id/field", "")
    assert ScreenController(object()).editable_fields() is None


def test_the_second_app_suite_has_three_separate_tries_that_read_the_focused_field():
    keymap = resolve_keymap(env={"PLEKTRA_QA_KEYMAP_SOURCE": "profile"}).keymap
    trials = load_suite(SUITES / "tab_first_letter.yaml", keymap)
    assert len(trials) == 3 and len({trial_key(t) for t in trials}) == 3  # each try is its own ledger entry
    probe = "dev.hitlbar.overlay"
    assert all(t.package == probe and not t.resource_id for t in trials)
    assert all(t.start_resource_id == f"{probe}:id/probe_body" and t.clear_field for t in trials)
    manifest = (SUITES.parent / "overlay-app/app/src/main/AndroidManifest.xml").read_text()
    ids = (SUITES.parent / "overlay-app/app/src/main/res/values/ids.xml").read_text()
    assert trials[0].activity == f"{probe}.BenchActivity" and '".BenchActivity"' in manifest
    assert all(t.probe_field == "title" for t in trials)
    assert 'name="probe_body"' in ids and 'name="probe_title"' in ids
    assert not trial_matched("eta", "beta", "\n", ci_first=True) and trial_matched("Beta", "beta", "\n", ci_first=True)


# --- the bar's protocol ------------------------------------------------------------------------------


def test_retry_is_a_button_and_the_version_comes_from_the_package_listing():
    assert parse_line("QAOVERLAY button=retry seq=3") == ("button", "retry")
    listing = "package:x.bar versionCode:2 uid:10234,1010234\n"
    assert parse_pm_uid(listing, "x.bar") == 10234 and parse_pm_version(listing, "x.bar") == 2
    assert parse_pm_version("package:x.bar uid:10234\n", "x.bar") is None


def _app(version, *, restarted_at=None):
    """A bar and its fake phone, whose app counts its own log lines. Returns (bar, broadcasts, pipe, app)."""
    pipe, sent, app = _Pipe(), [], {"seq": 0}

    def say(text, rate=1.0):
        app["seq"] += 1
        pipe.push(_line(f"{text} seq={app['seq']}"))

    def shell(args):
        if args[0] == "pm":
            assert "--show-versioncode" in args
            number = "" if version is None else f" versionCode:{version}"
            return f"package:x.bar{number} uid:{APP_UID}\n"
        if args[0] == "date":
            return DEVICE_NOW
        sent.append(list(args))
        say("shown" if args[5].endswith("SHOW") else "hidden")
        return ""

    bar = ButtonBar("s", package="x.bar", shell=shell, popen=lambda *a, **k: _Proc(pipe), ack_timeout_s=0.3)
    return bar, sent, pipe, app


def test_a_new_app_shows_the_wait_card_itself_after_a_tap():
    bar, sent, _pipe, _state = _app(2)
    with bar:
        bar.wait(pressed=True)
        assert sent == []
        bar.wait(pressed=False)  # no button ended the card: the host has to ask
        assert WAIT_TEXT in sent[-1]
        assert sent[-1][sent[-1].index("buttons") + 1] == ""


@pytest.mark.parametrize("version", [1, None])
def test_an_old_app_is_sent_the_wait_card(version):
    bar, sent, _pipe, _state = _app(version)
    with bar:
        bar.wait(pressed=True)
        assert WAIT_TEXT in sent[0] and sent[0][sent[0].index("buttons") + 1] == ""


def test_a_press_on_the_card_being_replaced_does_not_answer_the_new_one():
    bar, _sent, pipe, state = _app(2)
    with bar:
        bar.show("first")
        shell = bar._shell

        def late_press(args):
            state["seq"] += 1
            pipe.push(_line(f"button=next seq={state['seq']}"))  # tapped while the SHOW was on its way
            return shell(args)

        bar._shell = late_press
        bar.show("second")
        bar._shell = shell
        assert bar.pressed() is None


def test_an_app_that_restarted_under_a_standing_card_is_found_again():
    bar, sent, pipe, state = _app(2)
    with bar:
        bar.show("first")
        state["seq"] += 7
        pipe.push(_line(f"button=done seq={state['seq']}"))
        assert _wait_for(bar.pressed) == "done"
        state["seq"] = 0  # Android restarted the app; the bar stayed up between trials, so no HIDE
        bar.show("second")
        assert [args[5].rsplit(".", 1)[1] for args in sent] == ["SHOW", "SHOW", "HIDE", "SHOW"]
        pipe.push(_line(f"button=retry seq={state['seq'] + 1}"))
        assert _wait_for(bar.pressed) == "retry"


@pytest.mark.parametrize(("line", "pressed"), [(None, True), ("go\n", False)])
def test_the_start_card_becomes_the_wait_card(line, pressed):
    now = [0.0]
    calls = []

    class Bar:
        def show(self, *_a):
            return None

        def wait(self, pressed=False):
            calls.append(pressed)

        def pressed(self):
            return "done" if line is None and now[0] >= 2 else None

    gate = StartGate(
        "s", console=Console(file=io.StringIO()), shell=lambda args: "", bar=Bar(), clock=lambda: now[0],
        sleep=lambda s: now.__setitem__(0, now[0] + s), terminal=lambda: line if now[0] >= 2 else None, timeout_s=10,
    )
    gate.wait("t", "i")
    assert calls == [pressed]


def test_keyboard_guard_reads_override_and_window_and_restores_the_next_surface(monkeypatch):
    monkeypatch.setenv("PLEKTRA_QA_PACKAGE", "example.ime")
    actions = []
    snapshots = iter([
        '<string name="software_keyboard_mode">force_hardware</string>\n'
        '<string name="software_keyboard_mode_runtime_override">force_virtual</string>\n'
        'mCurId=example.ime/.Service\nmInputShown=true\n',
        '<string name="software_keyboard_mode_runtime_override">force_hardware</string>\n'
        'mCurId=example.ime/.Service\nmInputShown=true\n',
    ])

    def shell(args):
        actions.append(args)
        return next(snapshots) if args[0] == "sh" else "Status: ok"

    gate = ScreenGate("SERIAL", shell=shell)
    initial = gate.keyboard_state()
    assert initial.override == "force_virtual" and initial.view_shown
    restored = gate.restore_keyboard("hidden", initial)
    assert restored.mode == "force_hardware" and restored.view_shown  # compact control bar
    assert gate.restore_keyboard("hidden", restored) is restored
    assert len(actions) == 3 and "SoftwareKeyboardModeActionActivity" in actions[1][-1]


def test_hands_off_and_yield_are_one_adb_call_each():
    calls = []

    def shell(args):
        calls.append(args)
        return "1\n" if " 1; " in args[2] else "0\n"

    gate = ScreenGate("SERIAL", shell=shell)
    gate.hold("Preparing the field")
    gate.yield_to_human("T1. Type hello", shade=False)
    assert len(calls) == 2 and "notification post" in calls[0][2] and "statusbar collapse" in calls[0][2]
    assert "notification post" in calls[1][2] and "T1. Type hello" in calls[1][2]


# --- recording and import ----------------------------------------------------------------------------


def test_a_dropped_attempt_has_no_span_and_a_withdrawn_trial_loses_its_spans(tmp_path):
    now = [100.0]
    recorder = Recorder("s", tmp_path, clock=lambda: now[0], capture_input=False)
    one, two = ChordTrial(prompt="a", expect="a", number=1), ChordTrial(prompt="b", expect="b", number=2)
    passed = SimpleNamespace(passed=True, observed="a", expect="a", error="", diff="")
    recorder.on_trial(1, one)
    recorder.on_result(1, passed)
    recorder.on_trial(2, two)
    now[0] += 3
    recorder.on_discard(2, two)
    recorder.on_trial(2, two)
    began = now[0]
    now[0] += 4
    recorder.on_result(2, passed)
    assert [(s[0].number, s[1], s[2]) for s in recorder.spans][1] == (2, began, began + 4)
    recorder.withdraw(1)
    assert [s[0].number for s in recorder.spans] == [2]


def test_import_cuts_the_last_attempt_of_a_redone_trial(monkeypatch, tmp_path):
    marks = [(0, "1. a"), (5, "1. a"), (9, "2. b")]
    probe = {"format": {"duration": "12"},
             "chapters": [{"start_time": str(t), "tags": {"title": title}} for t, title in marks]}
    cuts = []
    monkeypatch.setenv("ADB_TARGET", "1.2.3.4:5555")
    monkeypatch.setenv("PLEKTRA_QA_LEDGER_DIR", str(tmp_path / "ledger"))
    monkeypatch.setenv("PLEKTRA_QA_KEYMAP_SOURCE", "profile")
    monkeypatch.setattr(cli.subprocess, "run", lambda *a, **k: SimpleNamespace(stdout=json.dumps(probe)))
    monkeypatch.setattr(cli, "_keyboard_build", lambda serial: "rocks.pkb.plektra.djb versionName=1")
    monkeypatch.setattr(cli, "resolve_keymap", lambda **k: resolve_keymap(env={"PLEKTRA_QA_KEYMAP_SOURCE": "profile"}))
    monkeypatch.setattr(cli, "cut_clip", lambda source, began, ended, dest: cuts.append((began, ended)) or False)
    result = CliRunner().invoke(
        cli.app, ["import", str(SUITES / "smoke_chords.yaml"), str(tmp_path / "r.mp4"), "--passed", "1"]
    )
    assert result.exit_code == 0, result.output
    assert cuts == [(5.0, 9.0)]
