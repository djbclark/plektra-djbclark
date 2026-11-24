"""The on-screen button bar: wire protocol and how the runner uses it."""

import threading
import time

import pytest

from plektra_qa import hitl_runner
from plektra_qa.button_bar import (
    ButtonBar,
    OverlayError,
    parse_entry,
    parse_line,
    parse_pm_uid,
    uid_from_column,
)
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


APP_UID = 10234
DEVICE_NOW = "11-24 16:00:00.000"


def _line(text: str, uid: str | int = APP_UID) -> str:
    """A logcat line as `logcat -v threadtime -v uid` prints it."""
    return f"11-24 16:00:01.000 {uid} 4321 4321 I QAOVERLAY: QAOVERLAY {text}"


def _bar(shell_calls, pipe, ack=0.3, *, uid_listing=f"package:x.bar uid:{APP_UID}\n", popen_calls=None):
    """A bar whose fake phone answers pm, date and the SHOW/HIDE broadcasts (seq 1 and 2)."""

    def shell(args):
        if args[0] == "pm":
            return uid_listing
        if args[0] == "date":
            return DEVICE_NOW + "\n"
        shell_calls.append(list(args))
        if "SHOW" in args[5]:
            pipe.push(_line("shown seq=1"))
        if "HIDE" in args[5]:
            pipe.push(_line("hidden seq=2"))
        return ""

    def popen(argv, **_k):
        if popen_calls is not None:
            popen_calls.append(list(argv))
        return _Proc(pipe)

    return ButtonBar("serial", package="x.bar", shell=shell, popen=popen, ack_timeout_s=ack)


def _wait_for(read, timeout=1.0):
    deadline = time.monotonic() + timeout
    value = read()
    while value is None and time.monotonic() < deadline:
        time.sleep(0.01)
        value = read()
    return value


def test_show_broadcasts_waits_for_the_ack_and_reads_buttons():
    calls: list[list[str]] = []
    pipe = _Pipe()
    with _bar(calls, pipe) as bar:
        bar.show("Type hello")
        assert calls[0][:8] == ["am", "broadcast", "-f", "0x20", "-a", "x.bar.SHOW", "-p", "x.bar"]
        assert "Type hello" in calls[0] and "done,next,stop" in calls[0]
        assert bar.pressed() is None
        pipe.push(_line("button=done seq=3"))
        assert _wait_for(bar.pressed) == "done"


def test_show_without_an_ack_raises_instead_of_pretending():
    pipe = _Pipe()

    def shell(args):
        return f"package:x.bar uid:{APP_UID}\n" if args[0] == "pm" else ""

    bar = ButtonBar("serial", package="x.bar", shell=shell, popen=lambda *a, **k: _Proc(pipe), ack_timeout_s=0.1)
    bar.start()
    try:
        with pytest.raises(OverlayError, match="did not report shown"):
            bar.show("Type hello")
    finally:
        bar.close()


def test_logcat_starts_at_the_phones_clock_and_asks_for_the_uid_column():
    popen_calls: list[list[str]] = []
    with _bar([], _Pipe(), popen_calls=popen_calls):
        pass
    argv = popen_calls[0]
    assert argv[argv.index("-T") + 1] == DEVICE_NOW
    assert argv.count("-v") == 2 and "uid" in argv and "threadtime" in argv


def test_logcat_falls_back_to_the_last_line_when_the_phone_clock_is_unreadable():
    popen_calls: list[list[str]] = []
    pipe = _Pipe()

    def shell(args):
        if args[0] == "pm":
            return f"package:x.bar uid:{APP_UID}\n"
        if args[0] == "date":
            raise RuntimeError("no date")
        return ""

    bar = ButtonBar("s", package="x.bar", shell=shell, popen=lambda a, **k: popen_calls.append(a) or _Proc(pipe))
    bar.start()
    bar.close()
    assert popen_calls[0][popen_calls[0].index("-T") + 1] == "1"


def test_start_refuses_when_the_apps_uid_cannot_be_resolved():
    for listing in ("", "package:x.bar.evil uid:10999\n", "package:x.bar uid:notanumber\n"):
        bar = _bar([], _Pipe(), uid_listing=listing)
        with pytest.raises(OverlayError, match="not installed"):
            bar.start()


def test_start_refuses_when_the_uid_lookup_fails():
    def shell(args):
        raise RuntimeError("device offline")

    with pytest.raises(OverlayError, match="cannot look up the uid"):
        ButtonBar("s", package="x.bar", shell=shell, popen=lambda *a, **k: _Proc(_Pipe())).start()


def test_pm_uid_and_uid_columns():
    assert parse_pm_uid("package:a.b uid:10234\npackage:a.b.c uid:5\n", "a.b") == 10234
    assert parse_pm_uid("package:a.b.c uid:5\n", "a.b") is None
    assert uid_from_column("10234") == 10234
    assert uid_from_column("u0_a234") == 10234
    assert uid_from_column("u10_a7") == 1010007
    assert uid_from_column("root") is None
    assert parse_entry(_line("button=done seq=7", uid="u0_a234")) == (10234, 7, "button", "done")
    assert parse_entry(_line("button=done seq=7", uid="shell"))[0] is None


def test_forged_lines_from_another_uid_are_ignored():
    pipe = _Pipe()
    with _bar([], pipe) as bar:
        # Another app (uid 10999), a system column and a line with no readable uid, all claiming to be the bar.
        pipe.push(_line("button=done seq=1", uid=10999))
        pipe.push(_line("button=stop seq=2", uid="u0_a999"))
        pipe.push(_line("shown seq=3", uid="shell"))
        pipe.push("I/QAOVERLAY( 77): QAOVERLAY button=next seq=4")  # old brief format, no uid column
        bar.show("Type hello")  # the real app answers shown seq=1
        time.sleep(0.1)
        assert bar.pressed() is None
        pipe.push(_line("button=done seq=2"))
        assert _wait_for(bar.pressed) == "done"


def test_a_forged_shown_does_not_acknowledge_a_bar_that_never_appeared():
    pipe = _Pipe()

    def shell(args):
        if args[0] == "pm":
            return f"package:x.bar uid:{APP_UID}\n"
        if args[0] == "date":
            return DEVICE_NOW
        if "SHOW" in args[5]:
            pipe.push(_line("shown seq=1", uid=10999))  # forged: the overlay permission was revoked
        return ""

    bar = ButtonBar("s", package="x.bar", shell=shell, popen=lambda *a, **k: _Proc(pipe), ack_timeout_s=0.2)
    bar.start()
    try:
        with pytest.raises(OverlayError, match="did not report shown"):
            bar.show("Type hello")
    finally:
        bar.close()


def test_a_replayed_older_line_is_not_a_new_answer():
    pipe = _Pipe()
    with _bar([], pipe) as bar:
        bar.show("Type hello")  # shown seq=1
        pipe.push(_line("button=done seq=3"))
        assert _wait_for(bar.pressed) == "done"
        pipe.push(_line("button=done seq=3"))  # the same line again
        pipe.push(_line("button=stop seq=2"))  # older than what was seen
        time.sleep(0.1)
        assert bar.pressed() is None
        pipe.push(_line("button=next seq=4"))
        assert _wait_for(bar.pressed) == "next"


def test_the_counter_restarting_while_a_bar_is_up_is_not_trusted():
    pipe = _Pipe()
    with _bar([], pipe) as bar:
        bar.show("Type hello")
        pipe.push(_line("button=done seq=5"))
        assert _wait_for(bar.pressed) == "done"
        pipe.push(_line("button=stop seq=1"))  # a bar cannot restart its process while showing
        time.sleep(0.1)
        assert bar.pressed() is None


def test_a_restarted_app_may_start_counting_at_one_after_a_hide():
    pipe = _Pipe()
    with _bar([], pipe) as bar:
        bar.show("first card")  # shown seq=1
        pipe.push(_line("button=done seq=9"))
        assert _wait_for(bar.pressed) == "done"
        bar.hide()  # hidden seq=2 is older than seq 9, accepted because it answers the HIDE
        # Android killed the idle app; its counter starts again, and the next card still works.
        bar.show("second card")  # the fake phone answers shown seq=1
        pipe.push(_line("button=next seq=2"))
        assert _wait_for(bar.pressed) == "next"


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
    results, _ = _run(field, bar, [ChordTrial(prompt="a: hello", expect="hello")])
    assert results[0].passed
    assert time.perf_counter() - started >= 1.2  # no timeout by default: it waited for Done


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


def test_pm_list_with_one_uid_per_user_profile_uses_the_main_user():
    # Seen on the Titan 2: three profiles, comma-separated.
    from plektra_qa.button_bar import parse_pm_uid

    out = "package:dev.hitlbar.overlay uid:10711,1010711,1110711\n"
    assert parse_pm_uid(out, "dev.hitlbar.overlay") == 10711
    assert parse_pm_uid("package:dev.hitlbar.overlay.other uid:10712\n", "dev.hitlbar.overlay") is None
