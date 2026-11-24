"""A trial passes only when the matching text stays put (people fix typos)."""

import time
from types import SimpleNamespace

import pytest

from plektra_qa import hitl_runner
from plektra_qa.controller import ScreenController
from plektra_qa.hitl_runner import ChordTrial, run_chord_batch

from test_speech import _console, _Gate


class _TimedField:
    """Reads back the last scheduled text whose offset has passed."""

    def __init__(self, schedule: list[tuple[float, str]]) -> None:
        self.schedule = schedule
        self.t0: float | None = None

    def exists(self) -> bool:
        return True

    def info(self):
        return SimpleNamespace(focused=True)

    def click(self) -> None:
        return None

    def clear_text_field(self) -> None:
        return None

    def get_text(self) -> str:
        if self.t0 is None:
            self.t0 = time.perf_counter()
        elapsed = time.perf_counter() - self.t0
        text = ""
        for offset, value in self.schedule:
            if elapsed >= offset:
                text = value
        return text


class _Device:
    def __init__(self, field=None, clipboard: str = "") -> None:
        self.field = field
        self.clipboard = clipboard
        self.cleared = 0

    def __call__(self, **_kwargs):
        return self.field

    def get_clipboard(self) -> str:
        return self.clipboard

    def set_clipboard(self, text: str) -> None:
        self.clipboard = text
        self.cleared += 1


@pytest.fixture(autouse=True)
def _settle(monkeypatch):
    monkeypatch.setattr(hitl_runner, "SETTLE_S", 0.3)


def _run(device, trial):
    return run_chord_batch(ScreenController(device), [trial], _console(), _Gate())


def test_a_typo_fixed_before_the_text_settles_still_passes():
    field = _TimedField([(0.1, "hallo"), (0.2, "hello")])
    result = _run(_Device(field), ChordTrial(prompt="a: hello", expect="hello", timeout_s=2))[0]
    assert result.passed
    assert result.observed == "hello"


def test_a_match_that_changes_again_does_not_complete_on_the_match():
    field = _TimedField([(0.1, "hello"), (0.2, "hello!")])
    result = _run(_Device(field), ChordTrial(prompt="a: hello", expect="hello", timeout_s=0.6))[0]
    assert not result.passed
    assert result.observed == "hello!"


def test_the_timeout_counts_idle_time_not_total_time():
    # Keeps typing past the old total timeout; each change restarts the idle clock.
    steps = [(0.1 * i, "x" * i) for i in range(1, 10)] + [(1.0, "hello")]
    field = _TimedField(steps)
    result = _run(_Device(field), ChordTrial(prompt="a: hello", expect="hello", timeout_s=0.5))[0]
    assert result.passed


def test_a_stale_clipboard_is_cleared_before_a_clipboard_trial():
    device = _Device(field=_TimedField([]), clipboard="hello")
    trial = ChordTrial(prompt="copy: hello", expect="hello", verify_mode="clipboard", timeout_s=0.4)
    result = _run(device, trial)[0]
    assert device.cleared >= 1
    assert not result.passed


def test_a_leading_newline_from_enter_does_not_fail_a_match():
    assert hitl_runner.trial_matched("\nBeta", "Beta", "\n")
    assert hitl_runner.trial_matched("Beta", "Beta", "\n")
    assert not hitl_runner.trial_matched("\nBeta", "\nBeta!", "\n")
