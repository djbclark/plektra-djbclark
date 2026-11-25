"""Host side of the on-screen button bar (the companion overlay app).

Not the recording overlay (overlay.py): that one draws keys and touches into a video.

The bar replaces the notification-shade card between trials (djbclark,
2026-11-24): a non-focusable overlay with Done, Next and Stop buttons. A trial
ends when the person presses Done, so a typing mistake never ends it early.

Wire protocol (kept free of anything app-specific so it moves to the public
harness repo unchanged):

- host to phone: `am broadcast -a <package>.SHOW -p <package> --es text T --es buttons done,next,stop`
  and `... .HIDE`.
- phone to host: logcat lines with tag QAOVERLAY, priority I:
  `QAOVERLAY shown seq=N`   the bar is on screen (acknowledges SHOW),
  `QAOVERLAY hidden seq=N`  the bar is gone (acknowledges HIDE),
  `QAOVERLAY button=done seq=N` (or next, stop) the person pressed a button.

Trust (any app can write to logcat): only lines written by the overlay app's own uid count
(`logcat -v uid`, the uid from `pm list packages -U`); logcat starts at the phone's current time;
and a line counts only if its seq is higher than every seq accepted so far. The one exception is
the restart rule: the app's counter starts at 1 again when Android restarts its process, which
can only happen while no bar is up, so right after a HIDE (or at start) a state line (shown or
hidden) is accepted whatever its seq, and the counter baseline moves to it.
"""

from __future__ import annotations

import re
import subprocess
import threading
import time
from collections import deque
from collections.abc import Callable, Sequence
from typing import Any

from plektra_qa.screen_gate import adb_shell

DEFAULT_PACKAGE = "dev.hitlbar.overlay"
LOG_TAG = "QAOVERLAY"
BUTTONS = ("done", "next", "stop")  # the default card; "redo" and "norec" are asked for by name
ACK_TIMEOUT_S = 5.0
POLL_S = 0.05
_LINE = re.compile(r"QAOVERLAY[: ]+(?:(?P<state>shown|hidden)|button=(?P<button>done|next|stop|redo|norec))\b")
_SEQ = re.compile(r"\bseq=(?P<seq>\d+)\b")
_APP_UID = re.compile(r"^u(?P<user>\d+)_a(?P<app>\d+)$")
_PM_UID = re.compile(r"^package:(?P<package>\S+)\s+uid:(?P<uid>\d+)(?:,\d+)*\s*$")  # one uid per user profile: the first is the main user's
_THREADTIME_UID = re.compile(r"^\S+\s+\S+\s+(?P<uid>\S+)\s+\d+\s+\d+\s+[VDIWEFA]\s+QAOVERLAY\s*:")


class OverlayError(RuntimeError):
    """The bar did not show, so the person could not answer."""


def uid_from_column(column: str) -> int | None:
    """A logcat uid column ('10234' or 'u0_a234') as a numeric uid; None for anything else."""
    if column.isdigit():
        return int(column)
    named = _APP_UID.match(column)
    if named:
        return int(named["user"]) * 100000 + 10000 + int(named["app"])
    return None


def parse_pm_uid(output: str, package: str) -> int | None:
    """The uid of exactly `package` from `pm list packages -U <package>` (it filters by substring)."""
    for line in output.splitlines():
        found = _PM_UID.match(line.strip())
        if found and found["package"] == package:
            return int(found["uid"])
    return None


def parse_entry(line: str) -> tuple[int | None, int | None, str, str] | None:
    """(uid, seq, kind, value) from one threadtime+uid logcat line; None if it is not a protocol line.

    uid and seq are None where the line has none (the caller then refuses it).
    """
    parsed = parse_line(line)
    if parsed is None:
        return None
    column = _THREADTIME_UID.match(line)
    seq = _SEQ.search(line)
    return (
        uid_from_column(column["uid"]) if column else None,
        int(seq["seq"]) if seq else None,
        parsed[0],
        parsed[1],
    )


def parse_line(line: str) -> tuple[str, str] | None:
    """('shown'|'hidden'|'button', value) from one logcat line, else None."""
    found = _LINE.search(line)
    if found is None:
        return None
    if found.group("state"):
        return "state", found.group("state")
    return "button", found.group("button")


class ButtonBar:
    """Show the bar, then read what the person pressed from logcat."""

    def __init__(
        self,
        serial: str,
        *,
        package: str = DEFAULT_PACKAGE,
        shell: Callable[[Sequence[str]], str] | None = None,
        popen: Callable[..., Any] = subprocess.Popen,
        clock: Callable[[], float] = time.monotonic,
        sleep: Callable[[float], None] = time.sleep,
        ack_timeout_s: float = ACK_TIMEOUT_S,
    ) -> None:
        self.serial = serial
        self.package = package
        self._shell = shell or (lambda args: adb_shell(serial, args))
        self._popen = popen
        self._clock = clock
        self._sleep = sleep
        self.ack_timeout_s = ack_timeout_s
        self._lock = threading.Lock()
        self._buttons: deque[str] = deque()
        self._states: deque[str] = deque()
        self._uid: int | None = None
        self._high = 0  # highest seq accepted so far
        self._fresh = True  # no bar line seen since start or the last HIDE: a restarted counter is fine
        self._stop = threading.Event()
        self._proc: Any | None = None

    def __enter__(self) -> ButtonBar:
        self.start()
        return self

    def __exit__(self, *_exc: object) -> bool:
        self.close()
        return False

    def start(self) -> None:
        """Begin reading logcat from the phone's current time, keeping only the overlay app's own lines.

        Any app can write a QAOVERLAY line, so the app's uid is resolved first; with no uid
        (the app is not installed) the bar refuses to start rather than trust every line.
        """
        try:
            listing = self._shell(["pm", "list", "packages", "-U", self.package])
        except RuntimeError as exc:
            raise OverlayError(f"cannot look up the uid of {self.package}: {exc}") from exc
        self._uid = parse_pm_uid(listing, self.package)
        if self._uid is None:
            raise OverlayError(f"{self.package} is not installed on the phone (no uid), so its lines cannot be trusted")
        try:
            # Logcat's own time format, in the phone's clock; a failed lookup falls back to the last line.
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
            raise OverlayError(f"cannot read the overlay's button presses: {exc}") from exc
        proc = self._proc

        def pump() -> None:
            for raw in iter(proc.stdout.readline, b""):
                if self._stop.is_set():
                    return
                entry = parse_entry(raw.decode("utf-8", "replace"))
                if entry is not None:
                    self._accept(*entry)

        threading.Thread(target=pump, name="overlay-logcat", daemon=True).start()

    def _accept(self, uid: int | None, seq: int | None, kind: str, value: str) -> None:
        """Keep one parsed line only if the app wrote it and its seq is new (see the module docstring)."""
        if uid is None or uid != self._uid or seq is None:
            return  # another app, an unreadable uid column, or no counter: not ours
        with self._lock:
            if seq <= self._high and not (self._fresh and kind == "state"):
                return  # a replayed or forged older line
            self._high = seq
            if kind == "state":
                self._fresh = value == "hidden"
                self._states.append(value)
            else:
                self._buttons.append(value)

    def close(self) -> None:
        self._stop.set()
        try:
            self.hide()
        except Exception:
            pass
        if self._proc is not None:
            self._proc.terminate()

    def show(self, text: str, buttons: Sequence[str] = BUTTONS) -> None:
        """Put the bar up and wait until the phone says it is showing."""
        with self._lock:
            self._states.clear()
            self._buttons.clear()  # presses from before this card are not answers
        self._broadcast("SHOW", ["--es", "text", text, "--es", "buttons", ",".join(buttons)])
        self._await_state("shown")

    def hide(self) -> None:
        with self._lock:
            self._states.clear()
            self._fresh = True  # the app may restart while no bar is up
        self._broadcast("HIDE", [])
        try:
            self._await_state("hidden")
        except OverlayError:
            pass  # a bar that never showed has nothing to hide

    def hide_blind(self) -> None:
        """Send HIDE without waiting for an answer (no logcat reader): clears a bar an earlier run left up."""
        try:
            self._broadcast("HIDE", [])
        except Exception:
            pass

    def pressed(self) -> str | None:
        """The oldest unread button (done, next, stop, redo or norec), or None."""
        with self._lock:
            return self._buttons.popleft() if self._buttons else None

    def _broadcast(self, action: str, extras: list[str]) -> None:
        # -f 0x20 (include stopped packages): a freshly installed app is "stopped" and gets nothing
        # until it has run once (found on the first live check, 2026-11-24).
        self._shell(["am", "broadcast", "-f", "0x20", "-a", f"{self.package}.{action}", "-p", self.package, *extras])

    def _await_state(self, state: str) -> None:
        start = self._clock()
        while True:
            with self._lock:
                if state in self._states:
                    return
            if self._clock() - start >= self.ack_timeout_s:
                raise OverlayError(
                    f"the overlay did not report {state} within {self.ack_timeout_s:g} s "
                    f"(is {self.package} installed with the draw-over-apps permission?)"
                )
            self._sleep(POLL_S)
