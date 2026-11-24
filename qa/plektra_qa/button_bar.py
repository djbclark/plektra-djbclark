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
BUTTONS = ("done", "next", "stop")
ACK_TIMEOUT_S = 5.0
POLL_S = 0.05
_LINE = re.compile(r"QAOVERLAY[: ]+(?:(?P<state>shown|hidden)|button=(?P<button>done|next|stop))\b")


class OverlayError(RuntimeError):
    """The bar did not show, so the person could not answer."""


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
        self._stop = threading.Event()
        self._proc: Any | None = None

    def __enter__(self) -> ButtonBar:
        self.start()
        return self

    def __exit__(self, *_exc: object) -> bool:
        self.close()
        return False

    def start(self) -> None:
        """Begin reading logcat. Only lines from now on count (`-T 1`)."""
        try:
            self._proc = self._popen(
                ["adb", "-s", self.serial, "logcat", "-T", "1", "-v", "brief", "-s", f"{LOG_TAG}:I"],
                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            )
        except OSError as exc:
            raise OverlayError(f"cannot read the overlay's button presses: {exc}") from exc

        def pump() -> None:
            for raw in iter(self._proc.stdout.readline, b""):
                if self._stop.is_set():
                    return
                parsed = parse_line(raw.decode("utf-8", "replace"))
                if parsed is None:
                    continue
                kind, value = parsed
                with self._lock:
                    (self._buttons if kind == "button" else self._states).append(value)

        threading.Thread(target=pump, name="overlay-logcat", daemon=True).start()

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
        self._broadcast("HIDE", [])
        try:
            self._await_state("hidden")
        except OverlayError:
            pass  # a bar that never showed has nothing to hide

    def pressed(self) -> str | None:
        """The oldest unread button (done, next or stop), or None."""
        with self._lock:
            return self._buttons.popleft() if self._buttons else None

    def _broadcast(self, action: str, extras: list[str]) -> None:
        self._shell(["am", "broadcast", "-a", f"{self.package}.{action}", "-p", self.package, *extras])

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
