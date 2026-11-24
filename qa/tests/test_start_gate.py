import io
from types import SimpleNamespace

import pytest
from rich.console import Console

from plektra_qa.inputlog import Event
from plektra_qa.start_gate import START_TAG, StartGate, StartGateError, card_text, is_start_event


def ev(type_, code, value):
    return Event(1.0, "/dev/input/event1", type_, code, value)


def test_start_events_are_key_downs_and_new_fingers_only():
    assert is_start_event(ev("EV_KEY", "KEY_A", "DOWN"))
    assert is_start_event(ev("EV_KEY", "BTN_TOUCH", "DOWN"))
    assert is_start_event(ev("EV_ABS", "ABS_MT_TRACKING_ID", "00000005"))
    assert not is_start_event(ev("EV_KEY", "KEY_A", "UP"))
    assert not is_start_event(ev("EV_KEY", "KEY_A", "REPEAT"))
    assert not is_start_event(ev("EV_ABS", "ABS_MT_TRACKING_ID", "ffffffff"))
    assert not is_start_event(ev("EV_SYN", "SYN_REPORT", "00000000"))


class Clock:
    def __init__(self):
        self.now = 0.0

    def __call__(self):
        return self.now

    def sleep(self, seconds):
        self.now += seconds


class Voice:
    def __init__(self):
        self.said = []

    def say(self, text):
        self.said.append(text)


def make(clock, *, lines=None, timeout_s=5.0, shell_log=None):
    log = shell_log if shell_log is not None else []
    queue = list(lines or [])
    voice = Voice()
    proc = SimpleNamespace(
        stdout=SimpleNamespace(readline=lambda: b""), stdin=None, terminate=lambda: None
    )
    gate = StartGate(
        "serial",
        speaker=voice,
        console=Console(file=io.StringIO()),
        shell=lambda args: log.append(list(args)) or "",
        popen=lambda *a, **k: proc,
        clock=clock,
        sleep=clock.sleep,
        terminal=lambda: queue.pop(0) if queue and clock.now >= 2 else None,
        timeout_s=timeout_s,
    )
    return gate, voice, log


def test_no_signal_never_starts_the_batch():
    clock = Clock()
    gate, voice, log = make(clock, timeout_s=3.0)
    with pytest.raises(StartGateError):
        gate.wait("Batch", "Pick up the phone.")
    assert any(START_TAG in args for args in log)
    assert voice.said and "Pick up the phone." in voice.said[0]


def test_a_typed_go_starts_it_after_the_card_and_voice():
    clock = Clock()
    gate, voice, log = make(clock, lines=["no\n", "go\n"])
    assert gate.wait("Batch", "Pick up the phone.") == "terminal"
    assert log[0][:4] == ["cmd", "notification", "post", "-t"]
    assert ["cmd", "statusbar", "collapse"] in log


def test_a_relayed_go_still_posts_the_card_first():
    clock = Clock()
    gate, voice, log = make(clock)
    assert gate.wait("Batch", "Pick up the phone.", confirmed=True) == "relayed"
    assert any(START_TAG in args for args in log)
    assert voice.said


def test_card_names_the_ways_to_start():
    both = card_text("t", "Do it.", phone=True, terminal=True)
    assert "press any key or tap the screen" in both and "type go" in both
    assert "type go" not in card_text("t", "Do it.", phone=True, terminal=False)
