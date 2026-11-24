"""The on-screen button bar: wire protocol and how the runner uses it."""

import threading
import time

import pytest

from plektra_qa import hitl_runner
from plektra_qa.button_bar import ButtonBar, OverlayError, parse_line
from plektra_qa.controller import ScreenController
from plektra_qa.hitl_runner import SKIPPED, STOPPED, ChordTrial, run_chord_batch

from test_settle import _TimedField
from test_speech import _console


def test_parse_line_reads_acks_and_buttons():
    assert parse_line("I/QAOVERLAY( 123): QAOVERLAY shown seq=1") == ("state", "shown")
    assert parse_line("I/QAOVERLAY( 123): QAOVERLAY hidden seq=2") == ("state", "hidden")
    assert parse_line("I/QAOVERLAY( 123): QAOVERLAY button=done seq=3") == ("button", "done")
    assert parse_line("I/QAOVERLAY( 123): QAOVERLAY button=next seq=4") == ("button", "next")
    assert parse_line("I/Other( 1): button=done") is None
    assert parse_line("QAOVERLAY button=erase seq=1") is None


class _Pipe:
    def __init__(self):
        self.lines: list[bytes] = []
        self.cv = threading.Condition()

    def push(self, text: str) -> None:
        with self.cv:
            self.lines.append(text.encode() + b"\n")
            self.cv.notify_all()

    def readline(self) -> bytes:
        with self.cv:
            while not self.lines:
                if not self.cv.wait(timeout=2):
                    return b""
            return self.lines.pop(0)


class _Proc:
    def __init__(self, pipe):
        self.stdout = pipe

    def terminate(self) -> None:
        self.stdout.push("")


def _bar(shell_calls, pipe, ack=0.3):
    def shell(args):
        shell_calls.append(list(args))
        if "SHOW" in args[3]:
            pipe.push("I/QAOVERLAY(1): QAOVERLAY shown seq=1")
        if "HIDE" in args[3]:
            pipe.push("I/QAOVERLAY(1): QAOVERLAY hidden seq=2")
        return ""

    return ButtonBar("serial", package="x.bar", shell=shell, popen=lambda *a, **k: _Proc(pipe), ack_timeout_s=ack)


def test_show_broadcasts_waits_for_the_ack_and_reads_buttons():
    calls: list[list[str]] = []
    pipe = _Pipe()
    with _bar(calls, pipe) as bar:
        bar.show("Type hello")
        assert calls[0][:6] == ["am", "broadcast", "-a", "x.bar.SHOW", "-p", "x.bar"]
        assert "Type hello" in calls[0] and "done,next,stop" in calls[0]
        assert bar.pressed() is None
        pipe.push("I/QAOVERLAY(1): QAOVERLAY button=done seq=3")
        deadline = time.monotonic() + 1
        pressed = None
        while pressed is None and time.monotonic() < deadline:
            pressed = bar.pressed()
            time.sleep(0.01)
        assert pressed == "done"


def test_show_without_an_ack_raises_instead_of_pretending():
    pipe = _Pipe()
    bar = ButtonBar("serial", package="x.bar", shell=lambda a: "", popen=lambda *a, **k: _Proc(pipe), ack_timeout_s=0.1)
    bar.start()
    try:
        with pytest.raises(OverlayError, match="did not report shown"):
            bar.show("Type hello")
    finally:
        bar.close()


class _FakeBar:
    """A scripted bar: pressed() hands out the press scheduled for this moment."""

    def __init__(self, presses):
        self.presses = presses  # [(seconds after show, button)]
        self.shown: list[str] = []
        self.hidden = 0
        self.t0 = 0.0

    def show(self, text, buttons=()):
        self.shown.append(text)
        self.t0 = time.perf_counter()

    def hide(self):
        self.hidden += 1

    def pressed(self):
        now = time.perf_counter() - self.t0
        for item in list(self.presses):
            if now >= item[0]:
                self.presses.remove(item)
                return item[1]
        return None


class _Gate:
    def __init__(self):
        self.calls: list = []

    def __enter__(self):
        return self

    def __exit__(self, *_a):
        return False

    def hold(self, _r):
        return None

    def yield_to_human(self, prompt, **kwargs):
        self.calls.append((prompt, kwargs))


class _Device:
    def __init__(self, field):
        self.field = field

    def __call__(self, **_k):
        return self.field


def _run(field, bar, trials):
    gate = _Gate()
    results = run_chord_batch(ScreenController(_Device(field)), trials, _console(), gate, overlay=bar)
    return results, gate


def test_done_ends_the_trial_only_after_the_person_fixed_a_typo():
    field = _TimedField([(0.1, "hallo"), (0.3, "hello")])
    bar = _FakeBar([(0.6, "done")])
    results, gate = _run(field, bar, [ChordTrial(prompt="a: hello", shade="Type hello", expect="hello")])
    assert results[0].passed and results[0].observed == "hello"
    assert bar.shown == ["Type hello"] and bar.hidden == 1
    assert gate.calls == [("Type hello", {"shade": False})]  # no shade card


def test_a_match_alone_does_not_end_the_trial_with_the_bar():
    field = _TimedField([(0.1, "hello")])
    bar = _FakeBar([(1.2, "done")])
    started = time.perf_counter()
    results, _ = _run(field, bar, [ChordTrial(prompt="a: hello", expect="hello", timeout_s=0.3)])
    assert results[0].passed
    assert time.perf_counter() - started >= 1.2  # waited for Done, not the 0.3 s timeout


def test_done_with_the_wrong_text_fails_the_trial():
    field = _TimedField([(0.1, "hellp")])
    bar = _FakeBar([(0.4, "done")])
    results, _ = _run(field, bar, [ChordTrial(prompt="a: hello", expect="hello")])
    assert not results[0].passed and results[0].error == ""


def test_next_skips_the_trial_and_the_batch_goes_on():
    field = _TimedField([(0.05, "x"), (0.1, "42")])
    bar = _FakeBar([(0.1, "next")])
    trials = [ChordTrial(prompt="a: hello", expect="hello"), ChordTrial(prompt="d: 42", expect="42")]
    bar.presses.append((0.5, "done"))  # second trial; times restart at show
    results, _ = _run(field, bar, trials)
    assert results[0].error == SKIPPED
    assert len(results) == 2


def test_stop_ends_the_batch():
    field = _TimedField([])
    bar = _FakeBar([(0.1, "stop")])
    trials = [ChordTrial(prompt="a: hello", expect="hello"), ChordTrial(prompt="d: 42", expect="42")]
    results, _ = _run(field, bar, trials)
    assert [r.error for r in results] == [STOPPED]


def test_a_bar_that_will_not_show_fails_the_trial_loudly():
    class Dead(_FakeBar):
        def show(self, text, buttons=()):
            raise OverlayError("the overlay did not report shown")

    results, _ = _run(_TimedField([]), Dead([]), [ChordTrial(prompt="a: hello", expect="hello")])
    assert "overlay did not report shown" in results[0].error and not results[0].passed
