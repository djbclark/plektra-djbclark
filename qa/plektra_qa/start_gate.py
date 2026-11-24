"""Human start gate: a test batch never starts on its own.

Rule (djbclark, 2026-11-24): every new human-in-the-loop batch waits for the
person's explicit start before any trial begins or recording starts. The
instruction is on the phone's notification shade, in the terminal and spoken.
The person starts the batch with a key press or tap on the phone, or by
typing go in the terminal. Nothing else starts it, and a timeout never does:
it ends the run with an error instead.
"""

from __future__ import annotations

import select
import subprocess
import sys
import threading
import time
from collections.abc import Callable, Sequence
from typing import Any

from rich.console import Console

from plektra_qa.inputlog import Event, parse_event
from plektra_qa.screen_gate import adb_shell

START_TAG = "plektra-qa-start"
START_TIMEOUT_S = 900.0
SETTLE_S = 1.0  # presses this soon after the card went up are not a start
POLL_S = 0.1
NO_TRACKING_ID = "ffffffff"


class StartGateError(RuntimeError):
    """The person did not start the batch."""


def is_start_event(event: Event) -> bool:
    """A key going down (BTN_TOUCH included), or a finger arriving."""
    if event.type == "EV_KEY":
        return event.value == "DOWN"
    if event.type == "EV_ABS" and event.code == "ABS_MT_TRACKING_ID":
        return event.value.lower() != NO_TRACKING_ID
    return False


def batch_instruction(count: int) -> str:
    return (
        f"Pick up the phone. {count} typing trials follow. The screen turns inverted while the "
        "computer works; when the colors are ordinary, type the action shown."
    )


def card_text(title: str, instruction: str, *, phone: bool, terminal: bool) -> str:
    ways = []
    if phone:
        ways.append("press any key or tap the screen")
    if terminal:
        ways.append("type go in the terminal")
    how = " or ".join(ways) or "ask the agent to relay your go"
    return f"{instruction} To start: {how}."


class StartGate:
    """Show the instruction, then wait for the human's start signal."""

    def __init__(
        self,
        serial: str,
        *,
        speaker: Any | None = None,
        console: Console | None = None,
        shell: Callable[[Sequence[str]], str] | None = None,
        popen: Callable[..., Any] = subprocess.Popen,
        clock: Callable[[], float] = time.monotonic,
        sleep: Callable[[float], None] = time.sleep,
        terminal: Callable[[], str | None] | None = None,
        timeout_s: float = START_TIMEOUT_S,
    ) -> None:
        self.serial = serial
        self.speaker = speaker
        self.console = console or Console()
        self._shell = shell or (lambda args: adb_shell(serial, args))
        self._popen = popen
        self._clock = clock
        self._sleep = sleep
        self._terminal = terminal if terminal is not None else _stdin_line
        self._terminal_enabled = terminal is not None or sys.stdin.isatty()
        self.timeout_s = timeout_s

    def wait(self, title: str, instruction: str, *, confirmed: bool = False) -> str:
        """Block until the person starts the batch; return how they did.

        confirmed: the person already said go to the agent in chat (the agent
        relays it). The card, the terminal text and the voice still run, then
        the batch starts without waiting for a second signal.
        """
        text = card_text(title, instruction, phone=True, terminal=self._terminal_enabled)
        self.console.print(f"[bold]{title}[/bold]\n{text}")
        self._post(title, text)
        if self.speaker is not None:
            self.speaker.say(text)
        if confirmed:
            self._done()
            return "relayed"
        events: list[str] = []
        stop = threading.Event()
        proc = self._start_phone_watch(events, stop)
        shown = self._clock()
        try:
            while True:
                now = self._clock()
                if events and now - shown >= SETTLE_S:
                    return self._done("phone")
                if now - shown < SETTLE_S:
                    events.clear()
                if self._terminal_enabled:
                    line = self._terminal()
                    if line is not None and line.strip().lower() in {"go", "start", "ok"}:
                        return self._done("terminal")
                if now - shown >= self.timeout_s:
                    raise StartGateError("not started: no start signal from the person")
                self._sleep(POLL_S)
        finally:
            stop.set()
            if proc is not None:
                proc.terminate()

    def _done(self, how: str = "relayed") -> str:
        try:
            self._shell(["cmd", "statusbar", "collapse"])
        except Exception:
            pass
        return how

    def _post(self, title: str, text: str) -> None:
        for args in (
            ["cmd", "notification", "post", "-t", title, START_TAG, text],
            ["cmd", "statusbar", "expand-notifications"],
        ):
            try:
                self._shell(args)
            except Exception as exc:
                self.console.print(f"start card: {exc}", markup=False)

    def _start_phone_watch(self, events: list[str], stop: threading.Event) -> Any:
        try:
            proc = self._popen(
                ["adb", "-s", self.serial, "shell", "-tt", "getevent", "-lt"],
                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            )
        except OSError as exc:
            self.console.print(f"phone start signal unavailable: {exc}", markup=False)
            return None

        def pump() -> None:
            for raw in iter(proc.stdout.readline, b""):
                if stop.is_set():
                    return
                event = parse_event(raw.decode("utf-8", "replace").replace("\r", ""))
                if event is not None and is_start_event(event):
                    events.append(event.code)

        threading.Thread(target=pump, name="start-getevent", daemon=True).start()
        return proc


def _stdin_line() -> str | None:
    ready, _, _ = select.select([sys.stdin], [], [], 0)
    return sys.stdin.readline() if ready else None
