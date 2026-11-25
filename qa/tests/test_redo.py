"""Redo last, a field that goes unreadable, and starting without recording (djbclark, 2026-11-25)."""

import io

from rich.console import Console

from plektra_qa import hitl_runner
from plektra_qa.controller import ScreenController
from plektra_qa.end_state import END_DONE_TEXT
from plektra_qa.hitl_runner import ChordTrial, run_chord_batch
from plektra_qa.start_gate import BAR_RECORD_CARD, StartGate


class _Field:
    """A field that reads `text`, and raises for its first `broken` reads."""

    def __init__(self, text, broken=0):
        self.text, self.broken = text, broken

    def exists(self):
        return True

    def info(self):
        class Info:
            focused = True

        return Info()

    def click(self):
        return None

    def clear_text_field(self):
        return None

    def get_text(self):
        if self.broken:
            self.broken -= 1
            raise LookupError("focus moved")
        return self.text


class _Bar:
    """Each card gets the next scripted press."""

    def __init__(self, presses):
        self.presses, self.cards, self._next = list(presses), [], None

    def show(self, text, buttons=()):
        self.cards.append((text, tuple(buttons)))
        self._next = self.presses.pop(0) if self.presses else "stop"

    def hide(self):
        return None

    def pressed(self):
        button, self._next = self._next, None
        return button


def _run(bar, trials, field):
    redone = []
    results = run_chord_batch(
        ScreenController(lambda **_k: field), trials, Console(file=io.StringIO()), overlay=bar,
        on_redo=lambda trial: redone.append(trial.prompt),
    )
    return results, redone


def _kept():
    """One trial that does not clear the field, so the fake's text is what it reads."""
    return [ChordTrial(prompt="one", expect="x", resource_id="f", clear_field=False)]


def _trials(*prompts, **second):
    made = [ChordTrial(prompt=p, expect="x", resource_id="f", clear_field=False) for p in prompts]
    made[0] = made[0].model_copy(update={"clear_field": True})
    if second:
        made[1] = made[1].model_copy(update=second)
    return made


def test_redo_last_reruns_the_previous_trial_and_withdraws_its_result():
    bar = _Bar(["done", "redo", "done", "done"])
    results, redone = _run(bar, _trials("one", "two", clear_field=True), _Field("x"))
    assert [c[0] for c in bar.cards] == ["T1. one", "T2. two", "T1. one", "T2. two", END_DONE_TEXT]
    assert "redo" not in bar.cards[0][1] and "redo" in bar.cards[1][1]
    assert redone == ["one"] and [r.number for r in results] == [1, 2]


def test_redo_of_a_trial_that_keeps_the_field_starts_from_the_trial_that_fills_it():
    bar = _Bar(["done", "done", "redo", "done", "done", "done"])
    results, redone = _run(bar, _trials("one", "two", "three"), _Field("x"))
    assert [c[0] for c in bar.cards] == ["T1. one", "T2. two", "T3. three", "T1. one", "T2. two", "T3. three", END_DONE_TEXT]
    assert redone == ["one", "two"] and len(results) == 3


def test_the_end_card_offers_redo_of_the_last_trial(monkeypatch):
    monkeypatch.setattr(hitl_runner, "END_CARD_S", 1.0)
    bar = _Bar(["done", "redo", "done", "done"])
    field = _Field("")
    shown = bar.show
    bar.show = lambda text, buttons=(): (shown(text, buttons), setattr(field, "text", "x" if text == "T1. one" else ""))
    results, redone = _run(bar, _kept(), field)
    assert [c[0] for c in bar.cards] == ["T1. one", hitl_runner.END_CARD, "T1. one", hitl_runner.END_CARD, END_DONE_TEXT]
    assert redone == ["one"] and len(results) == 1 and results[0].passed


def test_an_unreadable_field_does_not_end_a_trial_while_the_person_has_the_bar():
    bar = _Bar([])
    field = _Field("x", broken=5)
    reads = []
    bar.pressed = lambda: "done" if (reads.append(1) or len(reads) > 8) else None
    results, _ = _run(bar, _kept(), field)
    assert results[0].passed and results[0].error == ""


def test_done_on_a_field_that_stays_unreadable_fails_the_trial():
    bar = _Bar(["done"])
    results, _ = _run(bar, _kept(), _Field("x", broken=10**6))
    assert not results[0].passed and results[0].error.startswith("field lost")


def _gate(bar=None, line=None):
    now = [0.0]
    return StartGate(
        "serial", console=Console(file=io.StringIO()), shell=lambda args: "", bar=bar,
        clock=lambda: now[0], sleep=lambda s: now.__setitem__(0, now[0] + s),
        terminal=lambda: line if now[0] >= 2 else None, timeout_s=10.0,
    )


def test_the_start_card_offers_no_recording_on_the_bar():
    bar = _Bar(["norec"])
    pressed = bar.pressed
    gate = _gate(bar)
    now = gate._clock
    bar.pressed = lambda: pressed() if now() >= 2 else None
    assert gate.wait("Batch", "Pick up the phone.", recording=True) == "bar"
    assert gate.record_declined
    assert bar.cards == [(BAR_RECORD_CARD, ("done", "norec"))] and len(BAR_RECORD_CARD) <= 90


def test_norec_typed_in_the_terminal_starts_without_recording_and_go_records():
    gate = _gate(line="norec\n")
    gate.bar = _Bar([])
    gate.bar.pressed = lambda: None
    gate.wait("Batch", "Pick up the phone.", recording=True)
    assert gate.record_declined
    gate = _gate(bar=gate.bar, line="go\n")
    gate.wait("Batch", "Pick up the phone.", recording=True)
    assert not gate.record_declined


class _Talking:
    """A voice that never finishes a line by itself."""

    enabled = True

    def __init__(self):
        self.line, self.cut = None, []

    def prepare(self, texts):
        return None

    def say(self, text, rate=1.0):
        self.line = text

    def speaking(self):
        return self.line is not None

    def stop(self):
        if self.line is not None:
            self.cut.append(self.line)
        self.line = None


def test_done_cuts_the_trial_prompt_and_the_end_card_speech(monkeypatch):
    monkeypatch.setattr(hitl_runner, "END_CARD_S", 1.0)
    voice = _Talking()
    run_chord_batch(
        ScreenController(lambda **_k: _Field("x")), _kept(), Console(file=io.StringIO()),
        overlay=_Bar(["done", "done"]), speaker=voice,
    )
    assert len(voice.cut) >= 2 and hitl_runner.END_SPOKEN in voice.cut
