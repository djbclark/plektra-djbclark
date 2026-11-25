"""Host side of the test bench, the overlay app's BenchActivity (qa/overlay-app, app version 3 on).

The bench is a screen of text boxes that logs every event as one logcat line (tag QAPROBE, one JSON
object; the format is in qa/overlay-app/README.md). This module resets it by intent, collects those
lines for each try, and then says two things about the try, each only as far as the lines prove it:

- who made the difference: the person (a different or an extra key), the keyboard or the app (the
  right keys, another text), or unknown;
- where a missing character went: it never reached the app, it reached it and the keyboard sent
  nothing, the keyboard sent it and the app lost it, and so on.

It is an instrument. A suspected keyboard bug is still retried in a real second app.
"""

from __future__ import annotations

import contextlib
import difflib
import json
import os
import re
import subprocess
import threading
import time
from collections import Counter
from collections.abc import Callable, Iterator, Mapping, Sequence
from pathlib import Path
from typing import Any, NamedTuple

from plektra_qa.button_bar import DEFAULT_PACKAGE, parse_pm_uid, parse_pm_version, uid_from_column
from plektra_qa.screen_gate import adb_shell

PACKAGE = DEFAULT_PACKAGE
ACTIVITY = f"{PACKAGE}.BenchActivity"
LOG_TAG = "QAPROBE"
PROBE_VERSION = 1
MIN_APP_VERSION = 3  # the first overlay app versionCode with the bench
FIELDS = ("body", "title", "plain", "email", "url", "number", "password", "search", "nosuggest")
RESET_TIMEOUT_S = 5.0
QUIET_S = 0.2  # a try's lines are complete after this long without a new one
QUIET_MAX_S = 1.0
POLL_S = 0.02
BOUNCE_MS = 40  # the same key twice this fast may be the key's fault, not the person's

# Who made the difference between the expected text and the text in the box.
MATCH = ""
TYPO = "typo"
CHANGED = "keyboard-or-app"
UNKNOWN = "unknown"
FAULT_WORDS = {
    MATCH: "match",
    TYPO: "TYPING ERROR by the person, not a keyboard failure",
    CHANGED: "KEYBOARD OR APP",
    UNKNOWN: "UNKNOWN",
}

_LINE = re.compile(
    r"^\S+\s+\S+\s+(?P<uid>\S+)\s+\d+\s+\d+\s+[VDIWEFA]\s+QAPROBE\s*:\s(?P<json>\{.*\})\s*$"
)
_BACKSPACE = "DEL"
_ENTER = ("ENTER", "NUMPAD_ENTER")
_OTHER_MODIFIERS = ("ALT_LEFT", "ALT_RIGHT", "CTRL_LEFT", "CTRL_RIGHT", "META_LEFT", "META_RIGHT", "SYM", "FUNCTION")
_SHIFTS = ("SHIFT_LEFT", "SHIFT_RIGHT", "CAPS_LOCK")
_SILENT = (*_OTHER_MODIFIERS, *_SHIFTS, "TAB", "BACK", _BACKSPACE, *_ENTER)  # keys that type no character
_CURSOR = (
    "DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT", "DPAD_CENTER",
    "MOVE_HOME", "MOVE_END", "PAGE_UP", "PAGE_DOWN", "FORWARD_DEL",
)
_META_SHIFT = 0x1 | 0x100000  # shift or caps lock
_META_OTHER = 0x2 | 0x4 | 0x8 | 0x1000 | 0x10000  # alt, sym, function, ctrl, meta
_SIGNS = {
    _BACKSPACE: "\u232b", "FORWARD_DEL": "\u2326", "ENTER": "\u23ce", "NUMPAD_ENTER": "\u23ce", "TAB": "\u21e5",
    "SPACE": "\u2423", "SHIFT_LEFT": "\u21e7", "SHIFT_RIGHT": "\u21e7", "ALT_LEFT": "Alt", "ALT_RIGHT": "Alt",
    "CTRL_LEFT": "Ctrl", "CTRL_RIGHT": "Ctrl", "SYM": "Sym", "FUNCTION": "Fn",
}
_TEXT_CALLS = ("commitText", "setComposingText", "replaceText", "commitCompletion", "commitCorrection")

Event = dict[str, Any]


class ProbeError(RuntimeError):
    """The bench cannot be used: the app is missing or old, or it did not answer a reset."""


def is_bench(package: str | None, activity: str | None) -> bool:
    return package == PACKAGE and bool(activity) and activity.rsplit(".", 1)[-1] == "BenchActivity"


def resource_id(field: str) -> str:
    return f"{PACKAGE}:id/probe_{field}"


def field_of(rid: str | None) -> str | None:
    """'title' for the bench's resource id probe_title; None for anything else."""
    if not rid:
        return None
    name = rid.rsplit("/", 1)[-1].removeprefix("probe_")
    return name if rid == resource_id(name) and name in FIELDS else None


def parse_line(line: str, uid: int) -> Event | None:
    """The event of one threadtime+uid logcat line, if the app with `uid` wrote it."""
    found = _LINE.match(line)
    if found is None or uid_from_column(found["uid"]) != uid:
        return None
    try:
        event = json.loads(found["json"])
    except json.JSONDecodeError:
        return None  # a cut line: the gap in "seq" shows it
    if not isinstance(event, dict) or event.get("v") != PROBE_VERSION:
        return None
    if not (isinstance(event.get("seq"), int) and isinstance(event.get("kind"), str)):
        return None
    return event


def reset_args(tag: str, focus: str, texts: Mapping[str, str], expect: str) -> list[str]:
    """The device command that empties or prefills every box, focuses one and shows the expected text."""
    extras = [("qa.trial", tag), ("qa.focus", focus), ("qa.expect", expect)]
    extras += [(f"qa.text.{name}", value) for name, value in texts.items()]
    command = ["am", "start", "-W", "-n", f"{PACKAGE}/{ACTIVITY}"]
    for name, value in extras:
        if value:
            command += ["--es", name, value]
    return command


# -- reading the lines ------------------------------------------------------------------------------


class ProbeSession:
    """The bench's logcat lines, and the reset by intent."""

    def __init__(
        self,
        serial: str,
        *,
        shell: Callable[[Sequence[str]], str] | None = None,
        popen: Callable[..., Any] = subprocess.Popen,
        clock: Callable[[], float] = time.monotonic,
        sleep: Callable[[float], None] = time.sleep,
        reset_timeout_s: float = RESET_TIMEOUT_S,
    ) -> None:
        self.serial = serial
        self._shell = shell or (lambda args: adb_shell(serial, args))
        self._popen = popen
        self._clock = clock
        self._sleep = sleep
        self.reset_timeout_s = reset_timeout_s
        self._lock = threading.Lock()
        self._events: list[Event] = []
        self._uid: int | None = None
        self._stop = threading.Event()
        self._proc: Any | None = None

    def __enter__(self) -> ProbeSession:
        self.start()
        return self

    def __exit__(self, *_exc: object) -> bool:
        self.close()
        return False

    def start(self) -> None:
        """Begin reading the app's lines from the phone's current time. Only its own uid counts."""
        try:
            listing = self._shell(["pm", "list", "packages", "-U", "--show-versioncode", PACKAGE])
        except RuntimeError as exc:
            raise ProbeError(f"cannot look up {PACKAGE}: {exc}") from exc
        self._uid = parse_pm_uid(listing, PACKAGE)
        version = parse_pm_version(listing, PACKAGE)
        if self._uid is None or version is None or version < MIN_APP_VERSION:
            found = "not installed" if self._uid is None else f"version code {version}"
            raise ProbeError(
                f"the test bench needs {PACKAGE} version code {MIN_APP_VERSION} or later (found: {found}); "
                "install it with qa/overlay-app/install.sh or the latest overlay-app release"
            )
        try:
            now = self._shell(["date", "+%m-%d %H:%M:%S.000"]).strip()
        except RuntimeError:
            now = ""
        since = now if re.fullmatch(r"\d\d-\d\d \d\d:\d\d:\d\d\.\d{3}", now) else "1"
        try:
            self._proc = self._popen(
                ["adb", "-s", self.serial, "logcat", "-T", since, "-v", "threadtime", "-v", "uid",
                 "-s", f"{LOG_TAG}:I"],
                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            )
        except OSError as exc:
            raise ProbeError(f"cannot read the bench's lines: {exc}") from exc
        proc = self._proc

        def pump() -> None:
            for raw in iter(proc.stdout.readline, b""):
                if self._stop.is_set():
                    return
                self.feed(raw.decode("utf-8", "replace").rstrip("\r\n"))

        threading.Thread(target=pump, name="probe-logcat", daemon=True).start()

    def feed(self, line: str) -> None:
        event = None if self._uid is None else parse_line(line, self._uid)
        if event is not None:
            with self._lock:
                self._events.append(event)

    def close(self) -> None:
        self._stop.set()
        if self._proc is not None:
            self._proc.terminate()

    def mark(self) -> int:
        with self._lock:
            return len(self._events)

    def events(self, start: int = 0) -> list[Event]:
        with self._lock:
            return list(self._events[start:])

    def reset(self, tag: str, focus: str, texts: Mapping[str, str], expect: str) -> None:
        """Reset the bench and wait for its "reset" line with this tag."""
        start = self.mark()
        try:
            answer = self._shell(reset_args(tag, focus, texts, expect))
        except RuntimeError as exc:
            raise ProbeError(f"the test bench could not be started: {exc}") from exc
        if "Error" in answer:
            raise ProbeError(f"the test bench could not be started: {answer.strip()[-200:]}")
        began = self._clock()
        while not any(e["kind"] == "reset" and e.get("trial") == tag for e in self.events(start)):
            if self._clock() - began >= self.reset_timeout_s:
                raise ProbeError(f"the test bench did not report the reset within {self.reset_timeout_s:g} s")
            self._sleep(POLL_S)

    def settle(self) -> None:
        """Wait until no line has arrived for QUIET_S (at most QUIET_MAX_S)."""
        began = changed = self._clock()
        seen = self.mark()
        while True:
            now = self._clock()
            count = self.mark()
            if count != seen:
                seen, changed = count, now
            if now - changed >= QUIET_S or now - began >= QUIET_MAX_S:
                return
            self._sleep(POLL_S)


# -- what the lines prove ---------------------------------------------------------------------------


def render_keys(events: Sequence[Event]) -> str:
    """The hardware keys pressed, as the app saw them before the keyboard; held keys show a count."""
    shown: list[str] = []
    repeats = 0
    for event in _presses(events, repeats=True):
        if event.get("repeat", 0) > 0:
            repeats += 1
            continue
        if repeats:
            shown[-1] += f"(held, +{repeats})"
            repeats = 0
        shown.append(_SIGNS.get(event.get("name", ""), event.get("ch") or event.get("name", "?")))
    if repeats and shown:
        shown[-1] += f"(held, +{repeats})"
    return " ".join(shown)


def describe_difference(expected: str, got: str) -> str:
    """What turns `expected` into `got`, in words; positions count from 1 in the expected text."""
    parts = []
    for op, a, b, c, d in difflib.SequenceMatcher(a=expected, b=got, autojunk=False).get_opcodes():
        if op == "delete":
            parts.append(f"missing {expected[a:b]!r} at {a + 1}")
        elif op == "insert":
            parts.append(f"extra {got[c:d]!r} at {a + 1}")
        elif op == "replace":
            parts.append(f"{got[c:d]!r} instead of {expected[a:b]!r} at {a + 1}")
    return "; ".join(parts)


def _presses(events: Sequence[Event], repeats: bool = False) -> list[Event]:
    return [
        e for e in events
        if e["kind"] == "key" and e.get("stage") == "pre" and e.get("action") != "up"
        and (repeats or not e.get("repeat", 0))
    ]


class Typed(NamedTuple):
    """What the pressed keys spell by the system's key map."""

    text: str
    corrections: list[dict[str, Any]]  # each run of Backspace: what it removed and what came after
    unreadable: list[str]  # why the keys cannot be read as text; empty: they can
    shift: bool


def typed_by_keys(events: Sequence[Event], start: str, target: str | None) -> Typed:
    """Replay the key presses on `start`: a character is added at the end, Backspace takes one away."""
    text = start
    corrections: list[dict[str, Any]] = []
    why: list[str] = []
    shift = False
    elsewhere: Counter[str] = Counter()
    erasing = False
    for event in _presses(events, repeats=True):
        name = event.get("name", "")
        typed = event.get("ch", "")
        meta = event.get("meta", 0)
        shift = shift or name in _SHIFTS or bool(meta & _META_SHIFT)
        if event.get("action") != "down":
            why.append("a key sent several characters at once")
        if name in _OTHER_MODIFIERS or meta & _META_OTHER:
            why.append("Alt, Sym, Ctrl or Fn was used, which the keyboard may map its own way")
        if name in _CURSOR:
            why.append(f"{name} may have moved the cursor")
        if event.get("repeat", 0) and (name == _BACKSPACE or name not in _SILENT):
            why.append(f"{name} was held down")
        if name == _BACKSPACE:
            if not erasing:
                corrections.append({"backspaces": 0, "removed": "", "then": ""})
                erasing = True
            corrections[-1]["backspaces"] += 1
            corrections[-1]["removed"] = text[-1:] + corrections[-1]["removed"]
            text = text[:-1]
            continue
        if name in _ENTER:
            added = "\n" if target == "body" else ""
        elif name in _SILENT or name in _CURSOR:
            continue
        else:
            added = typed
            if not added:
                why.append(f"the key {name} types nothing by the system's key map")
        if added:
            erasing = False
            text += added
            if corrections:
                corrections[-1]["then"] += added
            if target and event.get("field") not in (None, target):
                elsewhere[event["field"]] += 1
    for box, count in elsewhere.items():
        why.append(f"{count} character key(s) were pressed while the box {box} had the focus")
    return Typed(text, corrections, list(dict.fromkeys(why)), shift)


def _bounce(events: Sequence[Event]) -> str:
    presses = _presses(events)
    for first, second in zip(presses, presses[1:]):
        gap = second.get("evt", 0) - first.get("evt", 0)
        if first.get("code") == second.get("code") and first.get("ch") and 0 <= gap < BOUNCE_MS:
            return f"{first.get('ch')!r} was pressed twice within {gap} ms: a double press or a bouncing key"
    return ""


def _loses(expected: str, got: str) -> str:
    """The characters of `expected` that nothing in `got` stands for."""
    lost = ""
    for op, a, b, c, d in difflib.SequenceMatcher(a=expected, b=got, autojunk=False).get_opcodes():
        if op == "delete":
            lost += expected[a:b]
        elif op == "replace" and b - a > d - c:
            lost += expected[a + (d - c):b]
    return lost


def _plain(text: str, expect: str) -> str:
    """Without the Enter that ends a try and, unless it is expected, a leading Enter or Tab."""
    if not expect.endswith("\n"):
        text = text.rstrip("\n")
    if not expect.startswith(("\n", "\t")):
        text = text.lstrip("\n\t")
    return text


def judge_typing(
    events: Sequence[Event], *, expect: str, box: str, passed: bool, start: str, target: str | None,
    problems: Sequence[str] = (),
) -> tuple[str, str, Typed]:
    """(fault, reason, what the keys spell). The person is blamed only when the box shows exactly what
    the keys spell; the keyboard or app only when the keys spell exactly what was expected."""
    typed = typed_by_keys(events, start, target)
    wanted = _plain(expect, expect).casefold()
    keys = _plain(typed.text, expect).casefold()
    shown = _plain(box, expect).casefold()
    why = [*problems, *typed.unreadable]
    if passed:
        reason = ""
        if not why and keys != wanted:
            reason = f"the keys spell {typed.text!r} and the box is right: the keyboard or app corrected it"
        return MATCH, reason, typed
    if why:
        return UNKNOWN, "; ".join(why), typed
    if keys == wanted:
        if shown != wanted:
            return CHANGED, "the right keys were pressed, the text differs: changed or lost after the keys", typed
        if typed.shift:
            return UNKNOWN, "only the case differs and Shift was used: the keys do not show who changed it", typed
        return CHANGED, "the right keys and no Shift, only the case differs: the keyboard or app set the case", typed
    if keys == shown:
        # The same characters in another order lose nothing: the keys came in that order.
        lost = _loses(wanted, keys) if Counter(keys) != Counter(wanted) else ""
        if lost:
            return UNKNOWN, (
                f"no key for {lost!r} reached the app: not pressed, or lost before the app; "
                "the box shows what the keys that arrived spell"
            ), typed
        bounce = _bounce(events)
        if bounce:
            return UNKNOWN, bounce, typed
        return TYPO, (
            f"the keys pressed spell {typed.text!r} and the box shows the same: "
            "a different or an extra key was pressed"
        ), typed
    return UNKNOWN, (
        f"the keys spell {typed.text!r}, which is neither the expected text nor the text in the box"
    ), typed


def lost_layer(
    events: Sequence[Event], *, expect: str, box: str, target: str | None, start: str = ""
) -> list[str]:
    """For each character missing from the box: how far it got, as the lines show it."""
    wanted = Counter(expect.rstrip("\n").casefold())
    have = Counter(box.casefold())
    missing = wanted - have
    if target is None:
        return ["unknown: no box can be named for this try"]
    if not missing:
        extra = "".join(sorted((have - wanted).elements()))
        return [_origin(events, char, target) for char in dict.fromkeys(extra) if char != "\n"] or [
            "nothing is missing: the text differs in case or order"
        ]
    return [
        f"{char!r} {_follow(events, char, wanted, target, _count(start, char))}" for char in missing
    ]


def _count(text: str, char: str) -> int:
    return text.casefold().count(char)


def _gain(event: Event, char: str) -> int:
    return _count(event.get("inserted", ""), char) - _count(event.get("removed", ""), char)


def _changes(events: Sequence[Event]) -> list[Event]:
    return [e for e in events if e["kind"] == "text" and e.get("cause") != "reset"]


def _by(events: Sequence[Event], seq: int) -> Event:
    return next((e for e in events if e["seq"] == seq), {})


def _origin(events: Sequence[Event], char: str, target: str) -> str:
    """Who put an extra character into the box."""
    for event in reversed(_changes(events)):
        if event.get("field") == target and _gain(event, char) > 0:
            return f"extra {char!r} was inserted by {_actor(events, event)}"
    return f"extra {char!r}: unknown, no line shows it being inserted"


def _actor(events: Sequence[Event], change: Event) -> str:
    source = _by(events, change.get("by", 0))
    if change.get("cause") == "ic":
        return f"the keyboard ({source.get('call', 'an input connection call')})"
    if change.get("cause") == "key":
        if source.get("pre") is False:
            return f"a key the keyboard sent ({source.get('name', '?')})"
        return f"a pressed key the app handled ({source.get('name', '?')})"
    return "the app itself"


def _follow(events: Sequence[Event], char: str, expected: Counter[str], target: str, held: int) -> str:
    """How far `char` got. `expected` counts the expected text's characters; `held`: how many of
    `char` the box had at the start."""
    wanted = expected[char]
    changes = _changes(events)
    here = [e for e in changes if e.get("field") == target]
    if held + sum(max(0, _gain(e, char)) for e in here) >= wanted:
        removal = next((e for e in reversed(here) if _gain(e, char) < 0), None)
        if removal is None:
            return "unknown: it was inserted, and no line shows it being removed"
        return f"was in the box and was then removed by {_actor(events, removal)}"
    calls = [e for e in events if e["kind"] == "ic"]
    for call in calls:
        if call.get("field") == target and call.get("call") in _TEXT_CALLS and _count(call.get("text", ""), char):
            applied = sum(_count(e.get("inserted", ""), char) for e in here if e.get("by") == call["seq"])
            if applied < _count(call.get("text", ""), char):
                return f"was sent by the keyboard ({call['call']}) and the app did not apply it"
    sent = [
        c for c in calls
        if c.get("field") == target and c.get("call") == "sendKeyEvent" and c.get("action") == "down"
        and c.get("ch", "").casefold() == char
    ]
    if sent:
        return "was sent by the keyboard as a key and the app did not insert it"
    for box_name in dict.fromkeys(e.get("field") for e in [*changes, *calls]):
        if box_name == target:
            continue
        landed = any(e.get("field") == box_name and _gain(e, char) > 0 for e in changes)
        if landed:
            return f"went into another box ({box_name}), not {target}"
    for key in events:
        if (
            key["kind"] == "key" and key.get("stage") == "post" and key.get("pre") is True
            and key.get("action") == "down" and key.get("ch", "").casefold() == char and key.get("field") == target
            and not any(e.get("by") == key["seq"] and _count(e.get("inserted", ""), char) for e in here)
        ):
            return "reached the app, the keyboard passed the key on, and the app did not insert it"
    presses = _presses(events)
    matching = [k for k in presses if k.get("ch", "").casefold() == char]
    if len(matching) >= wanted:
        instead = _sent_after(events, matching, presses)
        sent_words = f"sent {instead} instead" if instead else "sent nothing for it"
        return f"reached the app (seen before the keyboard) and the keyboard {sent_words}"
    typing = [k for k in presses if k.get("name") not in _SILENT]
    if any(not k.get("ch") for k in typing) or Counter(k.get("ch", "").casefold() for k in typing) - expected:
        return (
            "unknown: too few keys typing it were seen, but other keys were pressed (a different key, "
            "or a keyboard layout that maps keys its own way)"
        )
    return (
        "never reached the app: not pressed, or lost before the app (an accessibility key filter or "
        "the system); check the recording's key log"
    )


def _sent_after(events: Sequence[Event], matching: list[Event], presses: list[Event]) -> str:
    """The texts the keyboard sent between the presses of a key and the next press."""
    texts = []
    for key in matching:
        until = next((p["seq"] for p in presses if p["seq"] > key["seq"]), float("inf"))
        texts += [
            repr(e.get("text", "")) for e in events
            if e["kind"] == "ic" and e.get("call") in _TEXT_CALLS and key["seq"] < e["seq"] < until
        ]
    return ", ".join(texts)


def integrity(events: Sequence[Event]) -> list[str]:
    """Why the lines of a try cannot be trusted to be complete."""
    if not events:
        return ["no probe lines arrived"]
    problems = []
    if any(b["seq"] != a["seq"] + 1 for a, b in zip(events, events[1:])):
        problems.append("probe lines are missing or the app restarted")
    if any(e["kind"] == "life" and e.get("state") == "create" for e in events[1:]):
        problems.append("the bench was recreated during the try")
    return problems


def analyse(
    events: Sequence[Event], *, expect: str, observed: str, passed: bool, field: str | None = None,
    tag: str | None = None, start: str = "",
) -> dict[str, Any]:
    """The report of one try. `events` are its lines; with a `tag` only those from that reset on count.
    `field` is the box the try is about (default: the last one focused); `start` its text at the start."""
    problems: list[str] = []
    if tag is not None:
        index = next(
            (i for i, e in enumerate(events) if e["kind"] == "reset" and e.get("trial") == tag), None
        )
        if index is None:
            problems.append("the reset of this try was not reported")
        else:
            events = events[index:]
    problems += integrity(events)
    target = field
    if target is None:
        for event in events:
            if event["kind"] == "reset" or (event["kind"] == "focus" and event.get("gained")):
                target = event.get("field") or target
    in_box = [e for e in events if e["kind"] == "text" and e.get("field") == target]
    box = observed
    if in_box:
        last = in_box[-1].get("text", "")
        if target == "password":
            box = last  # the harness can only read dots there
        elif not in_box[-1].get("cut") and _plain(last, expect) != _plain(observed, expect):
            problems.append(f"the harness read {observed!r} but the box {target} last reported {last!r}")
    fault, reason, typed = judge_typing(
        events, expect=expect, box=box, passed=passed, start=start, target=target, problems=problems
    )
    if passed and field and target != "password":
        held = in_box[-1].get("text", "") if in_box else start
        if _plain(held, expect).casefold() != _plain(box, expect).casefold():
            reason = f"passed on text read from another box: the box {target} does not hold it"
    layer: list[str] = []
    if not passed and fault != TYPO:
        layer = [f"unknown: {'; '.join(problems)}"] if problems else lost_layer(
            events, expect=expect, box=box, target=target, start=start
        )
    return {
        "field": target,
        "expected": expect,
        "box": box,
        "keys": render_keys(events),
        "typed": None if typed.unreadable else typed.text,
        "corrections": typed.corrections,
        "difference": describe_difference(expect.rstrip("\n"), _plain(box, expect)),
        "fault": fault,
        "reason": reason,
        "layer": layer,
    }


def report_lines(number: int, report: Mapping[str, Any], passed: bool) -> list[str]:
    """The lines the host prints for one try."""
    head = f"trial {number}"
    verdict = "match" if passed else FAULT_WORDS[report["fault"] or UNKNOWN]
    difference = f" | {report['difference']}" if report["difference"] and not passed else ""
    lines = [
        f"{head} typing: expected {report['expected']!r} | box {report['box']!r} | "
        f"keys {report['keys'] or '(none)'}{difference} | {verdict}"
    ]
    if report["reason"]:
        lines.append(f"{head} why: {report['reason']}")
    for item in report["corrections"]:
        then = f", then typed {item['then']!r}" if item["then"] else ""
        lines.append(f"{head} correction: Backspace x{item['backspaces']} removed {item['removed']!r}{then}")
    lines += [f"{head} layer: {text}" for text in report["layer"]]
    return lines


def fault_summary(failed: Sequence[Any]) -> str:
    """One line on who the failed bench tries are put down to; "" when none failed on the bench."""
    benched = [result for result in failed if result.probe is not None]
    if not benched:
        return ""
    counts = Counter(result.fault or UNKNOWN for result in benched)
    return (
        f"failed on the test bench: {len(benched)} (typing errors by the person, not keyboard failures: "
        f"{counts[TYPO]}; keyboard or app: {counts[CHANGED]}; unknown: {counts[UNKNOWN]})"
    )


# -- one run ----------------------------------------------------------------------------------------


@contextlib.contextmanager
def bench_run(
    serial: str, trials: Sequence[Any], name: str, record_dir: Path | None = None
) -> Iterator[ProbeRun | None]:
    """The bench for a suite run, or None when no trial uses it. Its files go below the recordings."""
    if not any(is_bench(trial.package, trial.activity) for trial in trials):
        yield None
        return
    base = record_dir or Path(os.environ.get("PLEKTRA_QA_RECORD_DIR") or Path.home() / "plektra-qa-recordings")
    with ProbeSession(serial) as session:
        yield ProbeRun(session, base / "probe" / f"{name}-{time.strftime('%Y%m%d-%H%M%S')}")


class ProbeRun:
    """The bench in one suite run: reset before a try, report and keep its lines after it."""

    def __init__(self, session: ProbeSession, directory: Path) -> None:
        self.session = session
        self.directory = directory
        self._attempts: Counter[int] = Counter()
        self._mark = 0
        self._tag: str | None = None
        self._texts: dict[str, str] = {}  # each box's text at the start of the try
        self._count = 0

    def reset(self, number: int, focus: str, texts: Mapping[str, str], expect: str) -> None:
        self._count += 1
        self._mark = self.session.mark()
        self._tag = f"t{number}-{self._count}-{time.time_ns():x}"
        self._texts = dict(texts)
        self.session.reset(self._tag, focus, texts, expect)

    def keep(self) -> None:
        """Begin a try that keeps what the boxes hold."""
        before = self.session.events()
        for event in before:
            if event["kind"] == "text":
                self._texts[event.get("field", "")] = event.get("text", "")
        self._mark = len(before)
        self._tag = None

    def finish(
        self, number: int, *, phase: str, field: str | None, expect: str, observed: str, passed: bool,
        error: str = "", discarded: bool = False,
    ) -> tuple[dict[str, Any], Path]:
        """Analyse the try and write its lines and the verdict (the last line) to a file."""
        self.session.settle()
        events = self.session.events(self._mark)
        target = field
        if target is None:
            focused = [e for e in events if e["kind"] in ("focus", "reset") and e.get("gained", True)]
            target = next((e.get("field") for e in reversed(focused) if e.get("field")), None)
        report = analyse(
            events, expect=expect, observed=observed, passed=passed, field=target, tag=self._tag,
            start=self._texts.get(target or "", ""),
        )
        if error:  # skipped or stopped: nobody is to blame for what is missing
            report.update(fault=UNKNOWN, reason=f"the try did not finish: {error}", layer=[])
        self._attempts[number] += 1
        self.directory.mkdir(parents=True, exist_ok=True)
        path = self.directory / f"trial-{number:02d}-attempt-{self._attempts[number]}.jsonl"
        verdict = {
            "v": PROBE_VERSION, "kind": "verdict", "trial": number, "phase": phase, "passed": passed,
            "discarded": discarded, "error": error, **report,
        }
        with path.open("w", encoding="utf-8") as out:
            for event in [*events, verdict]:
                out.write(json.dumps(event, ensure_ascii=False) + "\n")
        return report, path
