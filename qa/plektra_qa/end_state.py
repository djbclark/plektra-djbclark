"""What the person is told when a human batch ends: the phone is free, or please wait.

Rule (djbclark, 2026-11-25): "we need to tell the user to wait or that the phone may be used
normally now, depending on if there is another batch coming up or not." The end card goes on the
button bar (or the opened notification shade without it), stays in the shade as a notification, and
is spoken. A stop by the person always means the phone is free.

The card has its own notification tag, which neither the screen gate's exit restore nor the
phone-side watcher snoozes. This Android build cannot cancel a notification and a later post of a
snoozed tag stays hidden, so an earlier batch's card is replaced by posting the same tag again
(after unsnoozing it, in case the person snoozed it).
"""

from __future__ import annotations

import time
from collections.abc import Callable, Sequence
from typing import Any

from rich.console import Console

from plektra_qa import runlock
from plektra_qa.button_bar import DEFAULT_PACKAGE, ButtonBar, OverlayError
from plektra_qa.screen_gate import STATE_TAG, adb_shell, unsnooze
from plektra_qa.speech import Speaker

END_DONE_TEXT = "Done. No more batches: the phone may be used normally."
END_WAIT_TEXT = "Wait. Another batch is coming: please leave the phone for now."
STARTING_TEXT = "A test batch is starting. Follow the cards on the screen."
TITLES = {
    END_DONE_TEXT: "Plektra QA: done",
    END_WAIT_TEXT: "Plektra QA: please wait",
    STARTING_TEXT: "Plektra QA: testing",
}
VISIBLE_S = 10.0  # how long the bar (without it, the open shade) shows the end card; then it closes by itself
SPEECH_WAIT_S = 15.0
RELEASE_HINT = (
    "If the phone was told to wait for a batch that will not run, free it: plektra-qa release (just qa-release)."
)


def phone_stopped(gate: Any | None) -> bool:
    """True when the person swiped the stop card away (asked of the phone; unknown counts as no)."""
    if gate is None or not getattr(gate, "took_screen", True):  # before it took the screen: an old run's mark
        return False
    check = getattr(gate, "user_stopped", None)
    if not callable(check):
        return False
    try:
        return bool(check())
    except Exception:
        return False


class EndNotice:
    """One batch's last word to the person. The caller sets `started` once the person has said go.

    `serial` or `shell` is the way to the phone; with neither there is no notification, only the bar
    and the voice. Nothing here raises when the phone does not answer: it prints why."""

    def __init__(
        self,
        serial: str | None = None,
        *,
        more_coming: bool = False,
        speaker: Any | None = None,
        bar: Any | None = None,
        shell: Callable[[Sequence[str]], str] | None = None,
        console: Console | None = None,
        sleep: Callable[[float], None] = time.sleep,
    ) -> None:
        if shell is None and serial is not None:
            shell = lambda args: adb_shell(serial, args)  # noqa: E731
        self._shell = shell
        self.more_coming = more_coming
        self.speaker = speaker
        self.bar = bar
        self.console = console or Console(stderr=True)
        self._sleep = sleep
        self.announced = False  # starting() replaced the earlier card
        self.started = False
        self.stopped = False  # the batch saw the person's Stop
        self.told: str | None = None  # the end text delivered
        self.posted = False  # the last notification post worked
        self.on_bar = False  # the end card was shown on the bar

    def text(self, stopped: bool = False) -> str:
        return END_WAIT_TEXT if self.more_coming and not stopped else END_DONE_TEXT

    def phrases(self) -> list[str]:
        """Every line this batch may end with (to render the voice ahead of time)."""
        return list(dict.fromkeys([self.text(), END_DONE_TEXT]))

    def starting(self) -> None:
        """A new batch is announced: replace the card an earlier batch left, which no longer holds."""
        self.announced = True
        self._post(STARTING_TEXT)

    def settle(self, gate: Any | None) -> None:
        """Wait (bounded) for the phone-side watcher to finish: on its way out it hides the bar."""
        wait = getattr(gate, "settle_watcher", None)
        if callable(wait) and getattr(gate, "took_screen", True):
            try:
                wait()
            except Exception as exc:
                self.console.print(f"end card: {exc}", markup=False)

    def deliver(self, *, stopped: bool = False) -> None:
        """Finish speaking before posting or displaying the end card."""
        text = self.text(stopped)
        self.told = text
        self.console.print(text, markup=False)
        if self.speaker is not None:
            try:
                self.speaker.say(text)
                wait = getattr(self.speaker, "wait_idle", None)
                if callable(wait):
                    wait(SPEECH_WAIT_S)
                    # Bound the wait and finish playback before another service reads the card.
                    if self.speaker.speaking():
                        self.speaker.stop()
            except Exception as exc:
                self.console.print(f"end voice: {exc}", markup=False)
        self._post(text)
        self._show(text)

    def finish(self, gate: Any | None = None) -> None:
        """The caller's last step, once the recording has stopped and the screen gate has restored the
        phone (or never took it).

        Always safe to call, and speaks once. A started batch is told however it ended (completed,
        stopped or failed); a batch that was announced but never started only puts the notification
        right, without voice or bar."""
        if self.told is not None:
            return
        stopped = self.stopped or phone_stopped(gate)
        if not self.started:
            if self.announced:
                self._post(self.text(stopped))
            return
        self.settle(gate)
        self.deliver(stopped=stopped)

    def _post(self, text: str) -> None:
        self.posted = False
        if self._shell is None:
            return
        try:
            unsnooze(self._shell, lambda tag: tag == STATE_TAG)
            self._shell(["cmd", "notification", "post", "-t", TITLES[text], STATE_TAG, text])
            self.posted = True
        except Exception as exc:
            self.console.print(f"end card: {exc}", markup=False)

    def _show(self, text: str) -> None:
        self.on_bar = False
        if self.bar is not None:
            try:
                self.bar.show(text, ())  # no buttons: touches go through, so it never blocks the person
                self.on_bar = True
                self._sleep(VISIBLE_S)
            except Exception as exc:
                self.console.print(f"end bar: {exc}", markup=False)
            finally:
                try:
                    self.bar.hide()
                except Exception:
                    pass
        if self.on_bar or self._shell is None:
            return
        try:
            self._shell(["cmd", "statusbar", "expand-notifications"])
            try:
                self._sleep(VISIBLE_S)
            finally:
                self._shell(["cmd", "statusbar", "collapse"])
        except Exception as exc:
            self.console.print(f"end card: {exc}", markup=False)


def release(
    target: str,
    *,
    console: Console,
    bar_package: str = DEFAULT_PACKAGE,
    speaker: Any | None = None,
) -> EndNotice:
    """A promised batch will not run: tell the person the phone may be used normally.

    Takes the phone's run lock, so it raises runlock.RunLockError while a run is driving the phone."""
    serial = runlock.device_serial(target)
    runlock.acquire(serial)
    try:
        speaker = speaker or Speaker()
        bar_cm = ButtonBar(target, package=bar_package)
        bar = None
        try:
            bar = bar_cm.__enter__()
        except OverlayError as exc:
            console.print(f"no button bar ({exc}); using the shade card", markup=False)
        notice = EndNotice(target, speaker=speaker, bar=bar, console=console)
        try:
            notice.deliver()
        finally:
            if bar is not None:
                bar_cm.__exit__(None, None, None)
        return notice
    finally:
        runlock.release(serial)
