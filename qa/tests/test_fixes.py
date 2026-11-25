"""Fixes from the 2026-11-24 review of the harness (findings 1-15)."""

import io
import os
import signal
import subprocess
import sys
import threading
import time
from contextlib import nullcontext
from pathlib import Path
from types import SimpleNamespace

import pytest
from rich.console import Console
from typer.testing import CliRunner

from plektra_qa import cli, runlock
from plektra_qa.controller import ScreenController
from plektra_qa.hitl_runner import (
    ChordTrial,
    allowed_packages,
    load_suite,
    plan_trials,
    run_chord_batch,
    trial_matched,
)
from plektra_qa.inputlog import Event
from plektra_qa.keys import resolve_keymap
from plektra_qa.ledger import BuildError, Ledger, legacy_trial_key, require_plektra_build, trial_key
from plektra_qa.start_gate import StartGate, StartGateError

ROOT = Path(__file__).resolve().parents[1]
SUITE = ROOT / "suites" / "smoke_chords.yaml"
BUILD = "rocks.pkb.plektra.djb versionName=1"


@pytest.fixture
def suite_trials():
    return load_suite(SUITE, resolve_keymap(env={"PLEKTRA_QA_KEYMAP_SOURCE": "profile"}).keymap)


def _ledger(tmp_path, build=BUILD):
    return Ledger("smoke_chords", build, root=tmp_path / "state", clips=tmp_path / "clips")


# --- finding 1: a skipped trial never strands a later one ------------------------------------------

def test_a_phase_is_skipped_only_when_every_trial_in_it_passed(tmp_path, suite_trials):
    ledger = _ledger(tmp_path)
    for t in suite_trials[:4]:
        ledger.record(t, t.expect, None)
    planned = plan_trials(suite_trials, ledger.passed)
    assert [t.number for t in planned] == [5, 6, 7, 8][: len(planned)]
    assert planned and planned[0].phase == "two_fields"


def test_the_first_pending_trial_of_a_phase_gets_the_phase_setup(tmp_path, suite_trials):
    ledger = _ledger(tmp_path)
    ledger.record(suite_trials[0], "Hello", None)  # trial 1 passed, trial 2 did not
    planned = plan_trials(suite_trials, ledger.passed)
    first = planned[0]
    assert first.number == 2
    assert (first.package, first.activity, first.click_resource_id) == (
        "branislav667.pinnote", "branislav667.pinnote.NotesActivity", "branislav667.pinnote:id/notes_fab",
    )
    assert first.resource_id == "branislav667.pinnote:id/pin_text"


def test_a_trial_that_keeps_the_field_reruns_the_trial_before_it(tmp_path, suite_trials):
    ledger = _ledger(tmp_path)
    for t in suite_trials:
        ledger.record(t, t.expect, None)
    ledger.forget(suite_trials[-1])  # only the clipboard trial (clear_field false) is left
    planned = plan_trials(suite_trials, ledger.passed)
    assert [t.number for t in planned] == [len(suite_trials) - 1, len(suite_trials)]


def test_allowed_packages_come_from_all_trials_not_the_pending_ones(suite_trials):
    assert allowed_packages(suite_trials) == {"branislav667.pinnote"}
    assert allowed_packages(suite_trials[5:]) == set()  # the pending slice alone would lose it


def test_no_field_named_and_none_focused_is_an_error_not_any_edittext():
    class Nothing:
        def exists(self):
            return False

    class Dev:
        def __call__(self, **kw):
            assert "className" not in kw, "must not fall back to any EditText"
            return Nothing()

    with pytest.raises(RuntimeError, match="input field not found"):
        ScreenController(Dev()).focus_and_clear()


# --- finding 2: suite numbers --------------------------------------------------------------------------

def test_trials_carry_their_suite_number_and_results_report_it(suite_trials):
    assert [t.number for t in suite_trials] == list(range(1, len(suite_trials) + 1))
    from tests.test_hitl import _Gate, _ReadyDevice, _ReadyField  # noqa: PLC0415

    seen = []
    console = Console(file=io.StringIO())
    console.bell = lambda: None
    later = ChordTrial(prompt="digits: 42", expect="hello", phase="p", number=6)
    results = run_chord_batch(
        ScreenController(_ReadyDevice(_ReadyField())), [later], console, _Gate(),
        on_trial=lambda n, t: seen.append(("trial", n)), on_result=lambda n, r: seen.append(("result", n)),
    )
    assert results[0].number == 6 and seen == [("trial", 6), ("result", 6)]


# --- finding 9 / 10: expectations -------------------------------------------------------------------

def test_first_letter_case_is_ignored_only_when_asked():
    assert trial_matched("hello", "Hello", "\n", ci_first=True)
    assert not trial_matched("hello", "Hello", "\n")
    assert not trial_matched("hELLO", "Hello", "\n", ci_first=True)  # only the first letter


def test_a_leading_tab_or_newline_is_ignored():
    assert trial_matched("\tBeta", "Beta", "\n")
    assert trial_matched("\n\tBeta", "Beta", "\n")
    assert not trial_matched("\tBeta", "\tBeta!", "\n")


def test_trial_6_does_not_promise_a_field_b(suite_trials):
    six = suite_trials[5]
    assert "field B" not in six.prompt and "Enter" in six.prompt
    assert suite_trials[0].fresh and suite_trials[0].expect == "Hello" and not suite_trials[0].expect_ci_first
    assert all(t.expect_ci_first for t in suite_trials[1:] if t.expect[:1].isalpha())


# --- finding 11: ledger identity -----------------------------------------------------------------------

def test_the_ledger_refuses_an_unknown_or_foreign_keyboard():
    with pytest.raises(BuildError):
        require_plektra_build("", "rocks.pkb.plektra.djb")
    with pytest.raises(BuildError):
        require_plektra_build("com.google.android.inputmethod.latin versionName=1", "rocks.pkb.plektra.djb")
    assert require_plektra_build(BUILD, "rocks.pkb.plektra.djb") == BUILD
    assert not Ledger("s", "", root=Path("/nonexistent")).passed(ChordTrial(prompt="a", expect="a"))


def test_the_key_hashes_the_raw_yaml_prompt_and_old_entries_still_match(tmp_path):
    rendered_a = ChordTrial(prompt="press Tab or Enter", raw_prompt="<Tab|Enter>", expect="x", phase="p")
    rendered_b = ChordTrial(prompt="press the key at left", raw_prompt="<Tab|Enter>", expect="x", phase="p")
    assert trial_key(rendered_a) == trial_key(rendered_b)  # a different keymap does not re-key it
    ledger = _ledger(tmp_path)
    ledger.entries[legacy_trial_key(rendered_a)] = {"passed": True, "build": BUILD, "clip": None}
    assert ledger.passed(rendered_a)  # the entry from before the change is still found


# --- finding 3: the start gate ---------------------------------------------------------------------------

class _Clock:
    def __init__(self):
        self.now = 0.0

    def __call__(self):
        return self.now

    def sleep(self, seconds):
        self.now += seconds


def _gate(clock, lines, bar=None, speaker=None, queue=None, timeout_s=8.0):
    data = [(line + "\n").encode() for line in lines]
    proc = SimpleNamespace(
        stdout=SimpleNamespace(readline=lambda: data.pop(0) if data else b""), stdin=None, terminate=lambda: None
    )
    return StartGate(
        "serial", speaker=speaker, bar=bar, console=Console(file=io.StringIO()), shell=lambda args: "",
        popen=lambda *a, **k: proc, clock=clock, sleep=clock.sleep, timeout_s=timeout_s,
        terminal=lambda: (queue.pop(0) if queue else None), 
    )


TOUCH = "[ 1.0] /dev/input/event2: EV_KEY       BTN_TOUCH            DOWN"
FINGER = "[ 1.0] /dev/input/event2: EV_ABS       ABS_MT_TRACKING_ID   00000005"
KEY = "[ 1.0] /dev/input/event1: EV_KEY       KEY_A                DOWN"


def test_with_the_bar_no_key_or_touch_starts_the_batch_only_done_does():
    clock = _Clock()
    pressed = []

    class Bar:
        def show(self, *a): pass
        def hide(self): pass
        def pressed(self): return "done" if clock.now > 4 else None

    gate = _gate(clock, [TOUCH, FINGER, KEY], bar=Bar(), queue=[])
    assert gate.wait("t", "i") == "bar"
    assert clock.now > 4  # the touches and the key at the start did not end the wait


def test_a_touch_never_starts_the_batch_without_the_bar_either_a_key_does():
    clock = _Clock()
    with pytest.raises(StartGateError):
        _gate(clock, [TOUCH, FINGER], timeout_s=3.0).wait("t", "i")


def test_keys_pressed_while_the_instruction_is_spoken_do_not_start():
    clock = _Clock()

    class Voice:
        def say(self, text): pass
        def speaking(self): return clock.now < 6

    gate = _gate(clock, [KEY], speaker=Voice(), timeout_s=5.0)
    with pytest.raises(StartGateError):  # the one key came during the speech; nothing after it
        gate.wait("t", "i")


# --- finding 5 and 14: no-bar idle end, baseline before the voice --------------------------------------

class _TypingField:
    """Shows the text typed so far; wrong text that never changes again."""

    def __init__(self, texts):
        self.texts, self.reads = texts, 0

    def exists(self): return True
    def info(self): return SimpleNamespace(focused=True)
    def click(self): pass
    def clear_text_field(self): pass

    def get_text(self):
        self.reads += 1
        return self.texts[min(self.reads - 1, len(self.texts) - 1)]


class _Dev:
    def __init__(self, field): self.field = field
    def __call__(self, **kw): return self.field


def test_a_wrong_text_ends_a_no_bar_trial_after_the_idle_limit(monkeypatch):
    from plektra_qa import hitl_runner

    monkeypatch.setattr(hitl_runner, "NO_BAR_IDLE_S", 0.2)
    console = Console(file=io.StringIO())
    console.bell = lambda: None
    field = _TypingField(["", "", "", "hello"])  # typed 'hello' where 'Hello' was expected
    trial = ChordTrial(prompt="p", expect="Hello", phase="p", resource_id="f")
    results = run_chord_batch(ScreenController(_Dev(field)), [trial], console, None)
    assert not results[0].passed and results[0].error == ""


def test_done_rereads_the_field_so_a_last_key_counts(monkeypatch):
    from plektra_qa import hitl_runner

    monkeypatch.setattr(hitl_runner, "DONE_SETTLE_S", 0.0)
    console = Console(file=io.StringIO())
    console.bell = lambda: None
    texts = ["", "", "", "Hell", "Hell", "Hello"]  # the last key lands after the poll that saw Done
    field = _TypingField(texts)

    class Bar:
        def __init__(self): self.calls = 0
        def show(self, *_): pass
        def hide(self): pass
        def pressed(self):
            self.calls += 1
            return "done" if self.calls >= 2 else None

    class Gate:
        def __enter__(self): return self
        def __exit__(self, *a): return False
        def hold(self, r): pass
        def yield_to_human(self, *a, **k): pass

    trial = ChordTrial(prompt="p", expect="Hello", phase="p", resource_id="f")
    results = run_chord_batch(ScreenController(_Dev(field)), [trial], console, Gate(), overlay=Bar())
    assert results[0].passed


# --- finding 6 and 15: lock key, child pids, signals -------------------------------------------------

def test_the_lock_is_keyed_on_the_phones_own_serial():
    def run(cmd, **kw):
        return SimpleNamespace(stdout="ABC123\n")

    assert runlock.device_serial("192.168.68.78:5555", run) == "ABC123"
    assert runlock.device_serial("100.73.253.10:5555", run) == "ABC123"  # same phone, same lock
    assert runlock.device_serial("x", lambda *a, **k: SimpleNamespace(stdout="error: closed")) == "x"


def test_the_lock_file_lists_child_pids_and_the_next_run_reaps_them(tmp_path):
    runlock.acquire("S1", tmp_path)
    try:
        runlock.note_children("S1")
        text = (tmp_path / "S1.lock").read_text()
        assert text.startswith(f"pid {os.getpid()}") and "children" in text
    finally:
        runlock.release("S1")
    killed = []
    table = [(500, 1, "scrcpy"), (501, 1, "python"), (502, 1, "afplay")]
    holder = "pid 9, started 1, children 500:scrcpy,501:python,502:ffmpeg,503:afplay"
    pids = runlock.reap_leftovers(holder, table, lambda pid, sig: killed.append((pid, sig)))
    assert pids == [500] and killed == [(500, signal.SIGTERM)]  # python is not reapable; 502 changed name; 503 gone


def test_descendants_walks_the_process_tree():
    table = [(1, 0, "init"), (10, 1, "py"), (11, 10, "scrcpy"), (12, 11, "adb"), (13, 1, "other")]
    assert runlock.descendants(10, table) == [(11, "scrcpy"), (12, "adb")]


# --- cli.run with a partly passed ledger: findings 1, 2, 6, 11, 12 ---------------------------------------

class _FakeGate:
    entered = 0

    def __init__(self, *a, **k):
        _FakeGate.kwargs = k

    def __enter__(self):
        _FakeGate.entered += 1
        return self

    def __exit__(self, *a):
        return False

    def yield_to_human(self, *a, **k): pass


class _FakeStart:
    record_declined = False
    def __init__(self, *a, **k): pass
    def wait(self, *a, **k): return "terminal"


class _FakeBar:
    def __init__(self, *a, **k): pass
    def __enter__(self): return self
    def __exit__(self, *a): return False
    def hide_blind(self): pass
    def hide(self): pass


def _patch_cli(monkeypatch, tmp_path, build=BUILD, batch=None):
    monkeypatch.setenv("ADB_TARGET", "1.2.3.4:5555")
    monkeypatch.setenv("PLEKTRA_QA_LEDGER_DIR", str(tmp_path / "ledger"))
    monkeypatch.setenv("PLEKTRA_QA_LOCK_DIR", str(tmp_path / "locks"))
    monkeypatch.setenv("PLEKTRA_QA_RECORD_DIR", str(tmp_path / "rec"))
    monkeypatch.setenv("PLEKTRA_QA_KEYMAP_SOURCE", "profile")
    monkeypatch.setenv("PLEKTRA_QA_VOICE", "off")
    monkeypatch.setattr(cli, "ime_build", lambda serial: build)
    monkeypatch.setattr(cli.runlock, "device_serial", lambda target: "REALSERIAL")
    monkeypatch.setattr(cli, "open_device", lambda: SimpleNamespace())
    monkeypatch.setattr(cli, "install_watchers", lambda device: None)
    monkeypatch.setattr(cli, "ScreenController", lambda device: SimpleNamespace(device=device))
    monkeypatch.setattr(cli, "ScreenGate", _FakeGate)
    monkeypatch.setattr(cli, "StartGate", _FakeStart)
    monkeypatch.setattr(cli, "ButtonBar", _FakeBar)
    monkeypatch.setattr(cli, "run_chord_batch", batch)
    monkeypatch.setattr(cli, "recording", lambda *a, **k: nullcontext(None))  # no scrcpy in tests
    monkeypatch.setattr(cli.shutil, "which", lambda name: f"/usr/bin/{name}")


def _result(trial, passed, error=""):
    return SimpleNamespace(
        number=trial.number, phase=trial.phase, prompt=trial.prompt, expect=trial.expect,
        observed=trial.expect if passed else "x", passed=passed, error=error, diff="",
    )


def test_run_with_a_partly_passed_ledger_runs_the_phase_setup_and_writes_passes_at_once(monkeypatch, tmp_path, suite_trials):
    ledger = Ledger("smoke_chords", BUILD, root=tmp_path / "ledger", clips=tmp_path / "clips")
    ledger.record(suite_trials[0], "Hello", None)  # trial 1 passed; the rest did not
    captured = {}

    def batch(controller, trials, console, gate, speaker, on_trial, on_result, overlay, gate_entered, on_redo):
        captured["trials"], captured["entered"] = trials, gate_entered
        for t in trials:
            on_trial(t.number, t)
            if t.number == 3:
                on_result(t.number, _result(t, True))
                # killed right here: the pass of trial 3 must already be on disk
                assert Ledger("smoke_chords", BUILD, root=tmp_path / "ledger").passed(t)
                raise KeyboardInterrupt
            on_result(t.number, _result(t, True))
        return []

    _patch_cli(monkeypatch, tmp_path, batch=batch)
    result = CliRunner().invoke(cli.app, ["run", str(SUITE)])
    first = captured["trials"][0]
    assert first.number == 2 and first.package == "branislav667.pinnote" and first.click_resource_id
    assert captured["entered"] is True and _FakeGate.entered == 1  # preflight before the go, entered once
    assert _FakeGate.kwargs["allowed_packages"] == {"branislav667.pinnote"}
    assert result.exit_code == 130
    reread = Ledger("smoke_chords", BUILD, root=tmp_path / "ledger")
    assert [reread.passed(t) for t in suite_trials[:4]] == [True, True, True, False]
    assert "interrupted" in result.output
    assert runlock._HELD == {}  # the lock is released


def test_run_refuses_a_foreign_keyboard_before_anything_starts(monkeypatch, tmp_path):
    def batch(*a, **k):
        raise AssertionError("must not run")

    _patch_cli(monkeypatch, tmp_path, build="com.google.android.inputmethod.latin versionName=1", batch=batch)
    result = CliRunner().invoke(cli.app, ["run", str(SUITE)])
    assert result.exit_code == 2 and "not rocks.pkb.plektra.djb" in result.output


def test_sigterm_in_run_becomes_a_keyboard_interrupt_and_the_handler_is_restored(monkeypatch, tmp_path):
    before = signal.getsignal(signal.SIGTERM)
    seen = {}

    def batch(*a, **k):
        seen["handler"] = signal.getsignal(signal.SIGTERM)
        os.kill(os.getpid(), signal.SIGTERM)  # a real SIGTERM, to this test process only
        time.sleep(1)
        raise AssertionError("SIGTERM did not interrupt")

    _patch_cli(monkeypatch, tmp_path, batch=batch)
    result = CliRunner().invoke(cli.app, ["run", str(SUITE)])
    assert result.exit_code == 130 and seen["handler"] is cli._raise_interrupt
    assert signal.getsignal(signal.SIGTERM) == before


def test_bar_is_the_default_for_run():
    import inspect

    assert inspect.signature(cli.run).parameters["bar"].default.default is True
