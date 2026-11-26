"""The end of a batch says whether the phone is free or another batch is coming (djbclark, 2026-11-25)."""

import io
import json
from contextlib import contextmanager
from types import SimpleNamespace

import pytest
from rich.console import Console
from typer.testing import CliRunner

from plektra_qa import cli, end_state, mcp_server, runlock
from plektra_qa import recording as recording_module
from plektra_qa.button_bar import OverlayError
from plektra_qa.controller import ScreenController
from plektra_qa.end_state import END_DONE_TEXT, END_WAIT_TEXT, STARTING_TEXT, EndNotice
from plektra_qa.hitl_runner import ChordTrial, run_chord_batch
from plektra_qa.recording import RecordingError
from plektra_qa.screen_gate import AWAKE_PID, NOTIFY_TAG, START_TAG, STATE_TAG, STOP_TAG, ScreenGate
from plektra_qa.start_gate import StartGateError


def test_the_two_texts_are_the_operators():
    assert END_DONE_TEXT == "Done. No more batches: the phone may be used normally."
    assert END_WAIT_TEXT == "Wait. Another batch is coming: please leave the phone for now."


class Phone:
    """Records what reaches the phone, the voice and the bar, in one list."""

    def __init__(self, snoozed: str = ""):
        self.events: list[tuple] = []
        self.snoozed = snoozed

    def shell(self, args):
        if args[:3] == ["cmd", "notification", "snoozed"]:
            return self.snoozed
        self.events.append(("adb", *args))
        return ""

    def posts(self):
        return [(e[5], e[6], e[7]) for e in self.events if e[:4] == ("adb", "cmd", "notification", "post")]

    def said(self):
        return [e[1] for e in self.events if e[0] == "say"]

    def cards(self):
        return [e[1] for e in self.events if e[0] == "show"]


class Voice:
    enabled = True

    def __init__(self, phone):
        self.phone = phone

    def prepare(self, texts):
        self.prepared = list(texts)

    def say(self, text, rate=1.0):
        self.phone.events.append(("say", text))

    def speaking(self):
        return False

    def stop(self):
        return None


class Bar:
    def __init__(self, phone, presses=(), dead=False):
        self.phone, self.presses, self.dead = phone, list(presses), dead

    def __enter__(self):
        return self

    def __exit__(self, *_a):
        self.phone.events.append(("bar-closed",))
        return False

    def show(self, text, buttons=()):
        if self.dead:
            raise OverlayError("gone")
        self.phone.events.append(("show", text, tuple(buttons)))

    def hide(self):
        self.phone.events.append(("hide",))

    def pressed(self):
        return self.presses.pop(0) if self.presses else None

    def wait(self, pressed=False):
        return None


def quiet() -> Console:
    console = Console(file=io.StringIO(), width=300)
    console.bell = lambda: None
    return console


def notice(phone, **kwargs) -> EndNotice:
    kwargs.setdefault("bar", Bar(phone))
    return EndNotice(shell=phone.shell, speaker=Voice(phone), console=quiet(), **kwargs)


def test_done_goes_to_the_shade_the_voice_and_the_bar_which_then_closes(monkeypatch):
    monkeypatch.setattr(end_state, "VISIBLE_S", 7.0)
    phone, slept = Phone(), []
    end = notice(phone, sleep=slept.append)
    end.deliver()
    assert phone.posts() == [("Plektra QA: done", STATE_TAG, END_DONE_TEXT)]
    assert phone.said() == [END_DONE_TEXT]
    assert phone.events[-2:] == [("show", END_DONE_TEXT, ()), ("hide",)]  # no buttons: nothing to wait for
    assert slept == [7.0] and end.posted and end.on_bar


def test_end_voice_finishes_before_showing_or_posting_the_card():
    phone = Phone()
    end = notice(phone, sleep=lambda _seconds: None)
    end.speaker.wait_idle = lambda seconds: phone.events.append(("voice-idle", seconds))
    end.deliver()
    wait = phone.events.index(("voice-idle", end_state.SPEECH_WAIT_S))
    post = next(i for i, event in enumerate(phone.events) if event[:4] == ("adb", "cmd", "notification", "post"))
    assert phone.events.index(("say", END_DONE_TEXT)) < wait < post
    assert post < phone.events.index(("show", END_DONE_TEXT, ()))


def test_more_coming_says_wait_unless_the_person_stopped():
    phone = Phone()
    notice(phone, more_coming=True).deliver()
    assert phone.said() == [END_WAIT_TEXT] and phone.posts()[0][2] == END_WAIT_TEXT
    phone = Phone()
    notice(phone, more_coming=True).deliver(stopped=True)
    assert phone.said() == [END_DONE_TEXT] and phone.posts()[0][2] == END_DONE_TEXT


def test_a_snoozed_card_is_unsnoozed_before_the_post():
    key = f"0|com.android.shell|2020|{STATE_TAG}|2000"
    phone = Phone(snoozed=f"{key} snoozed, time=1\n0|com.android.shell|2020|plektra-qa-stop|2000 snoozed")
    notice(phone).deliver()
    adb = [e for e in phone.events if e[0] == "adb"]
    assert adb[0] == ("adb", "cmd", "notification", "unsnooze", key)
    assert adb[1][:4] == ("adb", "cmd", "notification", "post") and len(adb) == 2


@pytest.mark.parametrize("dead", [False, True])
def test_without_a_working_bar_the_shade_opens_for_a_while(dead):
    phone = Phone()
    end = notice(phone, bar=Bar(phone, dead=True) if dead else None)
    end.deliver()
    assert [e[3] for e in phone.events if e[:3] == ("adb", "cmd", "statusbar")] == [
        "expand-notifications", "collapse",
    ]
    assert not end.on_bar and (("hide",) in phone.events) is dead


def test_a_silent_phone_does_not_raise():
    def gone(_args):
        raise RuntimeError("device offline")

    end = EndNotice(shell=gone, console=quiet())
    end.starting()
    end.deliver()
    assert not end.posted


class Gate:
    def __init__(self, swiped=False, took_screen=True, log=None):
        self.swiped, self.took_screen, self.log = swiped, took_screen, log if log is not None else []

    def __enter__(self):
        return self

    def __exit__(self, *_a):
        return False

    def hold(self, _reason):
        return None

    def yield_to_human(self, _prompt, **_kwargs):
        return None

    def user_stopped(self):
        return self.swiped

    def settle_watcher(self):
        self.log.append(("settle",))


def test_finish_says_nothing_when_no_batch_was_announced():
    phone = Phone()
    notice(phone).finish(Gate())
    assert phone.events == []


@pytest.mark.parametrize("more, text", [(False, END_DONE_TEXT), (True, END_WAIT_TEXT)])
def test_a_batch_that_never_started_only_puts_the_card_right(more, text):
    phone = Phone()
    end = notice(phone, more_coming=more)
    end.starting()
    end.finish(Gate())
    assert [p[2] for p in phone.posts()] == [STARTING_TEXT, text]
    assert phone.said() == [] and phone.cards() == []


def test_a_started_batch_that_failed_is_told_after_the_watcher_finished():
    phone = Phone()
    end = notice(phone)
    end.started = True
    end.finish(Gate(log=phone.events))
    assert phone.events[0] == ("settle",) and phone.cards() == [END_DONE_TEXT]
    end.finish(Gate())
    assert phone.cards() == [END_DONE_TEXT]  # once


@pytest.mark.parametrize("seen_by", ["the batch", "the gate"])
def test_a_stop_frees_the_phone_even_when_more_was_coming(seen_by):
    phone = Phone()
    end = notice(phone, more_coming=True)
    end.started = True
    end.stopped = seen_by == "the batch"
    end.finish(Gate(swiped=seen_by == "the gate"))
    assert phone.cards() == [END_DONE_TEXT]


def test_an_old_stop_mark_is_ignored_when_the_gate_never_took_the_screen():
    phone = Phone()
    end = notice(phone, more_coming=True)
    end.starting()
    end.finish(Gate(swiped=True, took_screen=False))  # not this run's mark
    assert phone.posts()[-1][2] == END_WAIT_TEXT


class Field:
    def __init__(self, text="x"):
        self.text = text

    def exists(self):
        return True

    def info(self):
        return SimpleNamespace(focused=True)

    def click(self):
        return None

    def clear_text_field(self):
        return None

    def get_text(self):
        return ""


def batch(phone, bar, end, gate=None, trials=None):
    return run_chord_batch(
        ScreenController(lambda **_k: Field()), trials or [ChordTrial(prompt="a: hello", expect="hello")],
        quiet(), gate or Gate(), Voice(phone), overlay=bar, notice=end,
    )


@pytest.mark.parametrize(
    "press, more, text",
    [("done", False, END_DONE_TEXT), ("done", True, END_WAIT_TEXT), ("stop", True, END_DONE_TEXT)],
)
def test_the_batch_leaves_the_last_word_to_its_caller(press, more, text):
    phone = Phone()
    bar = Bar(phone, presses=[press])
    end = notice(phone, bar=bar, more_coming=more)
    batch(phone, bar, end)
    # still recording and gated as far as the caller knows: nothing says the phone is free yet
    assert end.told is None and phone.posts() == [] and text not in phone.cards() + phone.said()
    assert ("hide",) not in phone.events
    end.started = True
    end.finish(Gate(log=phone.events))
    assert phone.cards()[-1] == text and phone.said()[-1] == text and phone.posts()[-1][2] == text
    assert phone.events.count(("hide",)) == 1 and phone.events[-1] == ("hide",)


def test_a_swipe_stop_is_told_after_the_watcher_finished():
    phone = Phone()
    bar = Bar(phone)
    end = notice(phone, bar=bar, more_coming=True)
    gate = Gate(swiped=True, log=phone.events)
    batch(phone, bar, end, gate=gate)
    assert end.stopped
    end.started = True
    end.finish(gate)
    assert phone.events.index(("settle",)) < phone.events.index(("show", END_DONE_TEXT, ()))


@pytest.mark.parametrize("entered", [False, True])
def test_a_batch_on_its_own_tells_the_person_even_when_it_crashes(entered):
    phone = Phone()
    bar = Bar(phone)
    gate = Gate(log=phone.events)

    def boom(*_a):
        raise ZeroDivisionError

    with pytest.raises(ZeroDivisionError):
        run_chord_batch(
            ScreenController(lambda **_k: Field()), [ChordTrial(prompt="a", expect="a")], quiet(), gate,
            Voice(phone), on_trial=boom, overlay=bar, gate_entered=entered,
        )
    assert phone.cards()[-1] == END_DONE_TEXT and phone.said()[-1] == END_DONE_TEXT
    assert (("settle",) in phone.events) is not entered  # a gate the caller holds is not waited for


def test_the_end_card_survives_the_screen_gate_exit():
    """Neither the exit restore, nor the phone watcher, nor the entry's unsnooze list holds our tag."""
    tags = [STOP_TAG, NOTIFY_TAG, START_TAG, STATE_TAG]
    sent = []

    def shell(args):
        if args[:3] == ["cmd", "notification", "list"]:
            return "".join(f"0|com.android.shell|2020|{tag}|2000\n" for tag in tags)
        sent.append(list(args))
        return ""

    gate = ScreenGate("s", shell=shell)
    gate._snooze_ours()
    assert sorted(args[-1].split("|")[3] for args in sent) == sorted([STOP_TAG, NOTIFY_TAG, START_TAG])
    assert not gate._our_tag(STATE_TAG)
    gate._saved_heads = "1"
    assert STATE_TAG not in gate._watcher_text()


def test_settle_waits_until_the_watcher_is_gone():
    left = {"polls": 3}
    slept = []

    def shell(args):
        if f"cat {AWAKE_PID}" in args[-1]:
            return "42\n"
        if "kill -0" in args[-1]:
            left["polls"] -= 1
            return "yes\n" if left["polls"] > 0 else ""
        return ""

    ScreenGate("s", shell=shell, sleep=slept.append).settle_watcher()
    assert slept == [0.5, 0.5]


# --- the CLI run --------------------------------------------------------------------------------------


def _cli_run(monkeypatch, tmp_path, *, start_error=None, record_error=None, batch_error=None, more=False):
    phone = Phone()

    class Gated(Gate):
        def __init__(self, *_a, **_k):
            super().__init__(log=phone.events)

        def __exit__(self, *_a):
            phone.events.append(("gate-exit",))
            return False

    class Start:
        record_declined = False

        def __init__(self, *_a, **_k):
            pass

        def wait(self, *_a, **kwargs):
            phone.events.append(("start", kwargs.get("repeat_reason")))
            if start_error:
                raise start_error

    @contextmanager
    def fake_recording(*_a, captured=None, **_k):
        if record_error:
            raise record_error
        try:
            yield None
        finally:
            phone.events.append(("filming-stopped",))
            captured()
            phone.events.append(("video-written",))

    def fake_batch(*_a, **_k):
        if batch_error:
            raise batch_error
        return []

    @contextmanager
    def no_bench(*_a, **_k):
        yield None

    monkeypatch.setattr(end_state, "adb_shell", lambda _serial, args: phone.shell(args))
    monkeypatch.setattr(cli, "open_device", lambda: None)
    monkeypatch.setattr(cli, "ScreenController", lambda _d: SimpleNamespace(device=None))
    monkeypatch.setattr(cli, "install_watchers", lambda _d: None)
    monkeypatch.setattr(cli, "ScreenGate", Gated)
    monkeypatch.setattr(cli, "Speaker", lambda: SimpleNamespace(backend="off", enabled=True,
                                                               say=lambda t: phone.events.append(("say", t))))
    monkeypatch.setattr(cli, "ButtonBar", lambda *_a, **_k: Bar(phone))
    monkeypatch.setattr(cli, "StartGate", Start)
    monkeypatch.setattr(cli, "recording", fake_recording)
    monkeypatch.setattr(cli, "run_chord_batch", fake_batch)
    monkeypatch.setattr(cli, "bench_run", no_bench)
    monkeypatch.setattr(cli, "_write_final", lambda *_a: None)
    monkeypatch.setattr(cli, "Ledger", lambda *_a: None)
    raised = None
    try:
        cli._run_locked(
            tmp_path / "s.yaml", [ChordTrial(prompt="a", expect="a", number=1)], "build", "target", "serial", False,
            False, None, True, "bar.pkg", True, more_coming=more, repeat_reason="why",
        )
    except Exception as exc:
        raised = exc
    return phone, raised


def test_cli_replaces_the_old_card_when_the_batch_is_announced(monkeypatch, tmp_path):
    phone, raised = _cli_run(monkeypatch, tmp_path, more=True)
    assert raised is None
    assert [p[2] for p in phone.posts()] == [STARTING_TEXT, END_WAIT_TEXT]
    first_post = next(i for i, e in enumerate(phone.events) if STARTING_TEXT in e)
    assert first_post < phone.events.index(("start", "why"))
    assert phone.cards() == [END_WAIT_TEXT]
    order = [phone.events.index(e) for e in (("filming-stopped",), ("gate-exit",), ("settle",),
                                             ("show", END_WAIT_TEXT, ()), ("video-written",), ("bar-closed",))]
    assert order == sorted(order)  # told once nothing films or holds the phone, without waiting for the video
    assert phone.cards() == [END_WAIT_TEXT]  # and only once


@pytest.mark.parametrize(
    "failure", [dict(record_error=RecordingError("no scrcpy")), dict(batch_error=RuntimeError("adb gone"))]
)
def test_cli_failure_after_the_start_tells_the_person_after_the_gate(monkeypatch, tmp_path, failure):
    phone, raised = _cli_run(monkeypatch, tmp_path, **failure)
    assert raised is not None
    shown = phone.events.index(("show", END_DONE_TEXT, ()))
    assert phone.events.index(("gate-exit",)) < phone.events.index(("settle",)) < shown
    assert shown < phone.events.index(("bar-closed",))
    assert phone.said() == [END_DONE_TEXT]


def test_cli_ctrl_c_after_the_start_tells_the_person(monkeypatch, tmp_path):
    phone, raised = _cli_run(monkeypatch, tmp_path, batch_error=KeyboardInterrupt(), more=True)
    assert raised is not None  # typer.Exit 130
    assert phone.cards() == [END_WAIT_TEXT]


def test_a_second_screen_gate_exit_changes_nothing():
    sent = []
    gate = ScreenGate("s", shell=lambda args: sent.append(list(args)) or "")
    gate.__exit__(None, None, None)
    first = len(sent)
    assert first
    gate.__exit__(None, None, None)
    assert len(sent) == first


@pytest.mark.parametrize("fails", [False, True])
def test_the_person_is_told_after_filming_stops_and_before_the_video_is_written(monkeypatch, tmp_path, fails):
    order = []

    class Fake:
        def __init__(self, *_a, **kwargs):
            assert "captured" not in kwargs

        def start(self):
            order.append("start")

        def stop_capture(self):
            order.append("filming-stopped")

        def finish(self):
            order.append("video-written")

    def told():
        order.append("told")
        if fails:
            raise KeyboardInterrupt

    monkeypatch.setattr(recording_module, "Recorder", Fake)
    with pytest.raises(KeyboardInterrupt if fails else ZeroDivisionError):
        with recording_module.recording("s", SimpleNamespace(), True, tmp_path, captured=told):
            raise ZeroDivisionError  # the batch failed: the person is still told, and the video written
    assert order == ["start", "filming-stopped", "told", "video-written"]


def test_cli_never_started_stays_quiet(monkeypatch, tmp_path):
    phone, raised = _cli_run(monkeypatch, tmp_path, start_error=StartGateError("not started"))
    assert raised is not None
    assert [p[2] for p in phone.posts()] == [STARTING_TEXT, END_DONE_TEXT]
    assert phone.cards() == [] and phone.said() == []


# --- release and MCP ----------------------------------------------------------------------------------


def _release_env(monkeypatch, tmp_path, phone, bar_error=None):
    class Dead(Bar):
        def __enter__(self):
            raise bar_error

    monkeypatch.setenv("ADB_TARGET", "target")
    monkeypatch.setenv("PLEKTRA_QA_LOCK_DIR", str(tmp_path))
    monkeypatch.setattr(runlock, "device_serial", lambda _t: "SERIAL")
    monkeypatch.setattr(end_state, "adb_shell", lambda _serial, args: phone.shell(args))
    monkeypatch.setattr(end_state, "ButtonBar", lambda *_a, **_k: Dead(phone) if bar_error else Bar(phone))
    monkeypatch.setattr(end_state, "Speaker", lambda: Voice(phone))


def test_release_command_sends_the_done_message(monkeypatch, tmp_path):
    phone = Phone()
    _release_env(monkeypatch, tmp_path, phone)
    done = CliRunner().invoke(cli.app, ["release"])
    assert done.exit_code == 0, done.output
    assert phone.posts() == [("Plektra QA: done", STATE_TAG, END_DONE_TEXT)]
    assert phone.said() == [END_DONE_TEXT] and phone.cards() == [END_DONE_TEXT]
    assert ("bar-closed",) in phone.events and runlock._HELD == {}


def test_release_works_without_the_bar_app(monkeypatch, tmp_path):
    phone = Phone()
    _release_env(monkeypatch, tmp_path, phone, bar_error=OverlayError("not installed"))
    out = json.loads(mcp_server.release_phone())
    assert out == {"released": True, "told": END_DONE_TEXT, "bar": False}
    assert phone.posts()[0][2] == END_DONE_TEXT


def test_release_is_refused_while_a_run_drives_the_phone(monkeypatch, tmp_path):
    phone = Phone()
    _release_env(monkeypatch, tmp_path, phone)
    runlock.acquire("SERIAL")
    try:
        assert "refused" in json.loads(mcp_server.release_phone())
        done = CliRunner().invoke(cli.app, ["release"])
        assert done.exit_code == 2 and phone.events == []
    finally:
        runlock.release("SERIAL")


def test_release_fails_loudly_when_the_phone_cannot_be_told(monkeypatch, tmp_path):
    phone = Phone()
    _release_env(monkeypatch, tmp_path, phone)

    def offline(_serial, _args):
        raise RuntimeError("device offline")

    monkeypatch.setattr(end_state, "adb_shell", offline)
    done = CliRunner().invoke(cli.app, ["release"])
    assert done.exit_code == 1 and "release failed" in done.output
