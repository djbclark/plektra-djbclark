"""The test bench's lines: reading them, who made a difference, and where a letter got lost."""

import io
import json
import re
from pathlib import Path

import pytest
from rich.console import Console

from plektra_qa import probe
from plektra_qa.controller import ScreenController
from plektra_qa.hitl_runner import ChordTrial, run_chord_batch
from plektra_qa.probe import (
    CHANGED, MATCH, TYPO, UNKNOWN, ProbeError, ProbeRun, ProbeSession, analyse, describe_difference,
    fault_summary, field_of, is_bench, parse_line, render_keys, report_lines, reset_args,
)

APP = Path(__file__).parents[1] / "overlay-app/app/src"
UID = 10234
LISTING = f"package:dev.hitlbar.overlay versionCode:3 uid:{UID}\n"


def _logcat(event, uid=UID):
    return f"11-25 12:00:00.000 {uid} 111 111 I QAPROBE : {json.dumps(event)}"


class _Log:
    """The lines one try would produce, with a model of what each box holds."""

    def __init__(self, tag="t1", prefill=None):
        self.events, self.seq, self.t = [], 0, 1000
        self.boxes = dict(prefill or {})
        self.tag = tag
        self.add("reset", trial=tag, field="title", texts=dict(self.boxes))

    def add(self, kind, **data):
        self.seq += 1
        self.t += 100
        event = {"v": 1, "seq": self.seq, "t": self.t, "kind": kind, **data}
        self.events.append(event)
        return event

    def press(self, ch, name=None, *, field="title", meta=0, repeat=0, evt=None):
        name = name or ("SPACE" if ch == " " else ch.upper())
        return self.add(
            "key", stage="pre", action="down", name=name, code=sum(map(ord, name)), ch=ch, meta=meta,
            repeat=repeat, field=field, evt=self.t if evt is None else evt,
        )

    def ic(self, call, text="", *, field="title", **more):
        return self.add("ic", field=field, call=call, text=text, **more)

    def text(self, inserted="", removed="", *, field="title", cause="ic", by=0):
        old = self.boxes.get(field, "")
        new = old[: len(old) - len(removed)] + inserted
        self.boxes[field] = new
        return self.add(
            "text", field=field, cause=cause, by=by, inserted=inserted, removed=removed, text=new, len=len(new)
        )

    def type(self, key, sends=None, **press):
        """A key press and what the keyboard commits for it (by default the same character)."""
        self.press(key, **press)
        sends = key if sends is None else sends
        if sends:
            call = self.ic("commitText", sends, field=press.get("field", "title"))
            self.text(sends, field=press.get("field", "title"), by=call["seq"])

    def backspace(self):
        self.press("", "DEL")
        call = self.ic("deleteSurroundingText", before=1, after=0)
        self.text("", self.boxes.get("title", "")[-1:], by=call["seq"])

    def judge(self, expect="beta", passed=None, observed=None, **more):
        box = self.boxes.get("title", "") if observed is None else observed
        passed = box.casefold() == expect.casefold() if passed is None else passed
        return analyse(
            self.events, expect=expect, observed=box, passed=passed, field="title", tag=self.tag, **more
        )


def _typed(keys, sends=None):
    log = _Log()
    for index, key in enumerate(keys):
        log.type(key, None if sends is None else sends[index])
    return log


# -- who made the difference ------------------------------------------------------------------------


def test_a_clean_try_matches_and_shows_the_keys():
    report = _typed("beta").judge()
    assert (report["fault"], report["reason"], report["layer"], report["corrections"]) == (MATCH, "", [], [])
    assert report["keys"] == "b e t a" and report["typed"] == "beta" and report["difference"] == ""


@pytest.mark.parametrize("keys", ["betz", "betta", "btea", "xbeta"])
def test_a_different_extra_or_swapped_key_is_the_persons_typing_error(keys):
    log = _Log()
    for index, key in enumerate(keys):
        log.type(key, evt=1000 * index)
    report = log.judge()
    assert report["fault"] == TYPO and report["layer"] == []
    assert report["typed"] == keys and report["box"] == keys


def test_the_right_keys_with_another_text_is_the_keyboard_or_the_app():
    report = _typed("beta", sends=["", "e", "t", "a"]).judge()
    assert report["fault"] == CHANGED
    assert report["difference"] == "missing 'b' at 1"
    assert report["layer"] == ["'b' reached the app (seen before the keyboard) and the keyboard sent nothing for it"]


def test_a_key_that_never_arrived_is_not_called_a_typing_error():
    report = _typed("eta").judge()
    assert report["fault"] == UNKNOWN and "not pressed, or lost before the app" in report["reason"]
    assert report["layer"][0].startswith("'b' never reached the app")


def test_other_keys_with_a_text_that_is_not_theirs_is_unknown():
    report = _typed("betz", sends=["b", "e", "", "z"]).judge()
    assert report["fault"] == UNKNOWN and "neither" in report["reason"]
    assert all("never reached" not in line for line in report["layer"])


def test_case_alone_is_the_keyboards_without_shift_and_unknown_with_it():
    capital = _typed("beta", sends=list("Beta"))
    assert capital.judge(passed=False)["fault"] == CHANGED
    assert capital.judge(passed=True)["fault"] == MATCH
    shifted = _Log()
    shifted.press("", "SHIFT_LEFT")
    for key in "beta":
        shifted.type(key, key.upper())
    assert shifted.judge(passed=False)["fault"] == UNKNOWN


def test_a_corrected_text_that_matches_still_shows_the_correction():
    log = _typed("betz")
    log.backspace()
    log.type("a")
    report = log.judge()
    assert report["fault"] == MATCH and report["box"] == "beta" and report["keys"] == "b e t z \u232b a"
    assert report["corrections"] == [{"backspaces": 1, "removed": "z", "then": "a"}]
    lines = report_lines(3, report, True)
    assert lines[0].startswith("trial 3 typing: expected 'beta' | box 'beta'") and lines[0].endswith("| match")
    assert "trial 3 correction: Backspace x1 removed 'z', then typed 'a'" in lines


def test_two_backspaces_are_one_correction():
    log = _typed("bxy")
    log.backspace()
    log.backspace()
    for key in "eta":
        log.type(key)
    assert log.judge()["corrections"] == [{"backspaces": 2, "removed": "xy", "then": "eta"}]


def test_a_keyboard_correction_of_a_typing_error_is_noted_on_a_pass():
    log = _typed("bet")
    log.press("q")
    call = log.ic("commitText", "a")
    log.text("a", by=call["seq"])
    report = log.judge()
    assert report["fault"] == MATCH and "corrected" in report["reason"]


def test_keys_that_cannot_be_read_as_text_blame_nobody():
    alt = _typed("bet")
    alt.press("", "ALT_LEFT")
    alt.type("a")
    held = _typed("bet")
    held.type("a")
    held.press("a", repeat=1)
    elsewhere = _Log()
    elsewhere.type("b", field="body")
    for key in "eta":
        elsewhere.type(key)
    cursor = _typed("eta")
    cursor.press("", "DPAD_LEFT")
    cursor.type("b")
    for log in (alt, held, elsewhere, cursor):
        report = log.judge(passed=False)
        assert report["fault"] == UNKNOWN and report["reason"], report
    assert "box body" in elsewhere.judge()["reason"] and elsewhere.judge()["typed"] is None


def test_the_same_key_twice_within_milliseconds_may_be_the_key():
    log = _Log()
    for at, key in ((0, "b"), (200, "e"), (400, "t"), (410, "t"), (600, "a")):
        log.type(key, evt=at)
    report = log.judge()
    assert report["fault"] == UNKNOWN and "bouncing" in report["reason"]


def test_missing_lines_or_no_reset_make_everything_unknown():
    gap = _typed("beta", sends=["", "e", "t", "a"])
    del gap.events[2]
    assert gap.judge()["fault"] == UNKNOWN and gap.judge()["layer"][0].startswith("unknown: probe lines are missing")
    report = analyse(_typed("eta").events, expect="beta", observed="eta", passed=False, field="title", tag="other")
    assert report["fault"] == UNKNOWN and "reset" in report["reason"]
    assert analyse([], expect="beta", observed="", passed=False)["fault"] == UNKNOWN


def test_a_harness_read_that_differs_from_the_box_is_unknown():
    report = _typed("beta").judge(observed="eta", passed=False)
    assert report["fault"] == UNKNOWN and "harness read" in report["reason"]


def test_the_password_box_is_read_from_the_lines():
    log = _Log()
    for key in "beta":
        log.type(key, field="password")
    report = analyse(
        log.events, expect="beta", observed="\u2022\u2022\u2022\u2022", passed=False, field="password", tag="t1"
    )
    assert report["box"] == "beta"


def test_prefilled_text_is_the_start_of_what_the_keys_spell():
    log = _Log(prefill={"title": "be"})
    log.type("t")
    log.type("z")
    assert log.judge(start="be")["fault"] == TYPO


def test_a_pass_read_from_another_box_is_said_so():
    log = _Log()
    for key in "beta":
        log.type(key, field="body")
    report = log.judge(observed="beta", passed=True)
    assert report["fault"] == MATCH and "the box title does not hold it" in report["reason"]


# -- where a missing letter got to ------------------------------------------------------------------


def _layer(log):
    return " | ".join(log.judge(passed=False)["layer"])


def test_the_keyboard_sent_something_else():
    assert "the keyboard sent 'v' instead" in _layer(_typed("beta", sends=list("veta")))


def test_the_keyboard_sent_it_and_the_app_did_not_apply_it():
    log = _Log()
    log.press("b")
    log.ic("commitText", "b")
    for key in "eta":
        log.type(key)
    assert "was sent by the keyboard (commitText) and the app did not apply it" in _layer(log)


def test_it_was_in_the_box_and_the_keyboard_removed_it():
    log = _typed("b")
    call = log.ic("deleteSurroundingText", before=1, after=0)
    log.text("", "b", by=call["seq"])
    for key in "eta":
        log.type(key)
    assert "was then removed by the keyboard (deleteSurroundingText)" in _layer(log)
    assert log.judge()["fault"] == CHANGED


def test_it_was_in_the_box_and_the_app_removed_it():
    log = _typed("b")
    log.text("", "b", cause="app")
    for key in "eta":
        log.type(key)
    assert "removed by the app itself" in _layer(log)


def test_it_went_into_another_box():
    log = _Log()
    log.press("b")
    call = log.ic("commitText", "b", field="body")
    log.text("b", field="body", by=call["seq"])
    for key in "eta":
        log.type(key)
    assert "went into another box (body), not title" in _layer(log)


def test_the_keyboard_passed_the_key_on_and_the_app_lost_it():
    log = _Log()
    log.press("b")
    log.add("key", stage="post", action="down", name="B", ch="b", pre=True, field="title")
    for key in "eta":
        log.type(key)
    assert "the keyboard passed the key on" in _layer(log)


def test_a_key_the_keyboard_sent_and_the_app_ignored():
    log = _Log()
    log.press("b")
    log.ic("sendKeyEvent", action="down", ch="b")
    for key in "eta":
        log.type(key)
    assert "sent by the keyboard as a key" in _layer(log)


def test_an_extra_character_names_who_inserted_it():
    report = _typed("beta", sends=["b", "e", "tt", "a"]).judge()
    assert report["fault"] == CHANGED
    assert report["layer"] == ["extra 't' was inserted by the keyboard (commitText)"]


# -- small parts ------------------------------------------------------------------------------------


def test_keys_are_rendered_with_signs_and_held_counts():
    log = _Log()
    log.press("", "TAB")
    log.press("a")
    log.press("a", repeat=1)
    log.press("a", repeat=2)
    log.press("", "DEL")
    log.press(" ")
    assert render_keys(log.events) == "\u21e5 a(held, +2) \u232b \u2423"


def test_differences_are_described_in_words():
    assert describe_difference("beta", "eta") == "missing 'b' at 1"
    assert describe_difference("beta", "betax") == "extra 'x' at 5"
    assert describe_difference("beta", "bota") == "'o' instead of 'e' at 2"


def test_only_the_apps_own_well_formed_lines_count():
    event = {"v": 1, "seq": 4, "t": 5, "kind": "sel"}
    assert parse_line(_logcat(event), UID) == event
    assert parse_line(_logcat(event, uid="u0_a234"), UID) == event
    assert parse_line(_logcat(event, uid=10999), UID) is None
    assert parse_line(_logcat(event)[:-3], UID) is None
    assert parse_line(_logcat({**event, "v": 2}), UID) is None
    assert parse_line("11-25 12:00:00.000 10234 1 1 I Other : {}", UID) is None


def test_bench_names():
    assert is_bench("dev.hitlbar.overlay", "dev.hitlbar.overlay.BenchActivity")
    assert is_bench("dev.hitlbar.overlay", ".BenchActivity")
    assert not is_bench("x", ".BenchActivity") and not is_bench("dev.hitlbar.overlay", None)
    assert field_of("dev.hitlbar.overlay:id/probe_url") == "url"
    assert field_of("other:id/probe_url") is None and field_of(None) is None


def test_the_reset_command_carries_only_set_extras():
    command = reset_args("t1", "title", {"body": "a b", "plain": ""}, "beta")
    assert command[:5] == ["am", "start", "-W", "-n", "dev.hitlbar.overlay/dev.hitlbar.overlay.BenchActivity"]
    assert command[5:] == [
        "--es", "qa.trial", "t1", "--es", "qa.focus", "title", "--es", "qa.expect", "beta",
        "--es", "qa.text.body", "a b",
    ]


class _Proc:
    class stdout:
        @staticmethod
        def readline():
            return b""

    def terminate(self):
        return None


def _session(on_reset=None, listing=LISTING):
    """A started session whose fake phone feeds `on_reset(tag, extras)`'s events after a reset."""
    session = None

    def shell(args):
        if args[0] == "pm":
            return listing
        if args[0] == "date":
            return "11-25 12:00:00.000"
        extras = {args[i + 1]: args[i + 2] for i, a in enumerate(args) if a == "--es"}
        for event in (on_reset or _just_reset)(extras):
            session.feed(_logcat(event))
        return "Status: ok\n"

    session = ProbeSession("s", shell=shell, popen=lambda *a, **k: _Proc(), reset_timeout_s=0.1)
    session.start()
    return session


def _just_reset(extras):
    return [{"v": 1, "seq": 1, "t": 1, "kind": "reset", "trial": extras["qa.trial"], "field": "body"}]


@pytest.mark.parametrize("listing", ["package:dev.hitlbar.overlay versionCode:2 uid:10234\n", ""])
def test_an_old_or_absent_app_is_refused(listing):
    with pytest.raises(ProbeError, match="version code 3 or later"):
        _session(listing=listing)


def test_a_reset_needs_its_acknowledgement():
    _session().reset("t1", "body", {}, "beta")
    with pytest.raises(ProbeError, match="did not report the reset"):
        _session(on_reset=lambda extras: []).reset("t1", "body", {}, "beta")


# -- in a run ---------------------------------------------------------------------------------------


class _Box:
    def __init__(self):
        self.text = ""

    def exists(self):
        return True

    def info(self):
        class Info:
            focused = True

        return Info()

    def get_text(self):
        return self.text


class _Presses:
    """The bar: before each answer the person "types" the next scripted keys into the box."""

    def __init__(self, box, session_events, script):
        self.box, self.emit, self.script, self._next = box, session_events, list(script), None

    def show(self, text, buttons=()):
        keys, self._next = self.script.pop(0)
        self.emit(keys)

    def hide(self):
        return None

    def pressed(self):
        button, self._next = self._next, None
        return button


def test_a_bench_trial_resets_by_intent_and_reports_a_typing_error_apart(tmp_path, monkeypatch):
    monkeypatch.setattr(probe, "QUIET_S", 0.0)
    log, resets, box = _Log(), [], _Box()

    def on_reset(extras):
        resets.append(extras)
        box.text = ""
        log.boxes.clear()
        start = len(log.events)
        log.add("reset", trial=extras["qa.trial"], field=extras["qa.focus"], texts={})
        return log.events[start:]

    session = _session(on_reset)

    def person(keys):
        start = len(log.events)
        for key in keys:
            log.type(key)
        box.text = log.boxes.get("title", "")
        for event in log.events[start:]:
            session.feed(_logcat(event))

    bar = _Presses(box, person, [("xx", "retry"), ("betz", "done")])
    run = ProbeRun(session, tmp_path / "probe")
    trial = ChordTrial(
        prompt="type beta", expect="beta", package="dev.hitlbar.overlay",
        activity="dev.hitlbar.overlay.BenchActivity", start_resource_id="dev.hitlbar.overlay:id/probe_body",
        probe_field="title",
    )

    class Device:
        def __call__(self, **_kwargs):
            return box

        def get_clipboard(self):
            return ""

        def set_clipboard(self, _text):
            return None

    console = Console(file=io.StringIO(), width=300)
    [result] = run_chord_batch(ScreenController(Device()), [trial], console, overlay=bar, probe=run)
    assert [r["qa.focus"] for r in resets] == ["body", "body"] and resets[0]["qa.expect"] == "beta"
    assert not result.passed and result.fault == TYPO and result.probe["keys"] == "b e t z"
    assert "TYPING ERROR by the person" in console.file.getvalue()
    files = sorted(p.name for p in (tmp_path / "probe").iterdir())
    assert files == ["trial-01-attempt-1.jsonl", "trial-01-attempt-2.jsonl"]
    first, last = (json.loads((tmp_path / "probe" / f).read_text().splitlines()[-1]) for f in files)
    assert first["discarded"] and first["keys"] == "x x"
    assert last["kind"] == "verdict" and last["expected"] == "beta" and last["box"] == "betz" and not last["passed"]
    assert result.probe_log.endswith(files[1])
    assert "typing errors by the person, not keyboard failures: 1" in fault_summary([result])


def test_a_skipped_try_blames_nobody(tmp_path, monkeypatch):
    monkeypatch.setattr(probe, "QUIET_S", 0.0)
    session = _session()
    run = ProbeRun(session, tmp_path)
    run.reset(1, "body", {}, "beta")
    report, _path = run.finish(
        1, phase="p", field=None, expect="beta", observed="", passed=False, error="skipped from the phone"
    )
    assert report["fault"] == UNKNOWN and report["layer"] == [] and "skipped" in report["reason"]


def test_an_unknown_box_name_refuses_the_trial():
    with pytest.raises(ValueError, match="no such test bench box: tittle"):
        ChordTrial(prompt="p", expect="x", probe_field="tittle")
    with pytest.raises(ValueError, match="no such test bench box"):
        ChordTrial(prompt="p", expect="x", prefill={"nope": "a"})


# -- the app and the host agree ---------------------------------------------------------------------


def test_the_box_names_match_the_app():
    protocol = (APP / "main/kotlin/dev/hitlbar/overlay/ProbeProtocol.kt").read_text()
    enum = protocol[protocol.index("enum class ProbeField") : protocol.index("val next: ProbeField")]
    named = re.findall(r"^\s{4}[A-Z]+\(\s*\"([a-z]+)\"", enum, re.MULTILINE)
    assert tuple(named) == probe.FIELDS
    ids = (APP / "main/res/values/ids.xml").read_text()
    assert tuple(re.findall(r'name="probe_([a-z]+)"', ids)) == probe.FIELDS
    assert 'const val PROBE_TAG = "QAPROBE"' in protocol
    assert f"const val PROBE_VERSION = {probe.PROBE_VERSION}\n" in protocol
    gradle = (APP.parent / "build.gradle.kts").read_text()
    assert int(re.search(r"versionCode = (\d+)", gradle)[1]) >= probe.MIN_APP_VERSION


def test_the_line_the_app_writes_is_read_here():
    """ProbeProtocolTest.kt asserts that the app writes exactly this line."""
    source = (APP / "test/kotlin/dev/hitlbar/overlay/ProbeProtocolTest.kt").read_text()
    golden = re.search(r'val GOLDEN = """(.*?)"""', source)[1]
    event = parse_line(f"11-25 12:00:00.000 {UID} 1 1 I QAPROBE : {golden}", UID)
    assert event["kind"] == "ic" and event["call"] == "commitText" and event["text"] == 'a "b"\\\n'
