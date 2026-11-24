"""Local 25 ms chord-batch runner. No model call between trials."""

from __future__ import annotations

import difflib
import time
from collections.abc import Callable
from contextlib import nullcontext
from pathlib import Path
from typing import Any

import yaml
from pydantic import BaseModel
from rich.console import Console
from rich.live import Live
from rich.table import Table

from plektra_qa.controller import ScreenController
from plektra_qa.keys import Keymap, localise_texts, resolve_keymap
from plektra_qa.speech import Speaker

POLL_S = 0.025
STOP_CHECK_S = 1.0
SETTLE_S = 1.5  # a match counts only after the text stays put this long: people fix typos
STOPPED = "stopped from the phone"
NOT_STARTED = "never started after the spoken prompts"
HANDS_OFF = "Hands off. Get ready."
FINISHED = "Testing finished."
REPROMPT = "Still waiting. "


class ChordTrial(BaseModel):
    prompt: str
    expect: str
    verify_mode: str = "text"
    sentinel: str = "\n"
    timeout_s: float = 30
    package: str | None = None
    activity: str | None = None
    resource_id: str | None = None
    text_hint: str | None = None
    click_resource_id: str | None = None
    clear_field: bool = True
    cue_on_device: bool = False
    shade: str | None = None
    # Spoken action; defaults to `shade`, then `prompt`. Spell out symbols
    # here ("comma", "period") because a voice reads them as pauses.
    speak: str | None = None
    # Quiet seconds after the spoken prompt ends before it is said again,
    # and how many times. With a voice, `timeout_s` counts from the first
    # key that changes the field, not from the prompt.
    start_wait_s: float = 10
    max_reprompts: int = 3
    phase: str = ""


class TrialResult(BaseModel):
    phase: str
    prompt: str
    expect: str
    observed: str
    passed: bool
    accuracy: float
    diff: str
    wpm: float | None
    t_ready: float | None
    t_first_input: float | None
    t_complete: float | None
    gap_s: float | None
    error: str = ""


def load_suite(path: Path, keymap: Keymap | None = None) -> list[ChordTrial]:
    """Read a suite. Key tokens in prompt/shade/speak are expanded with `keymap`
    (default: the live or profile keymap, see `keys.resolve_keymap`); a bare key
    name or a token no keycap can produce refuses the whole suite."""
    data = yaml.safe_load(path.read_text())
    if keymap is None:
        keymap = resolve_keymap().keymap
    timeout_s = float(data.get("timeout_s", 30))
    sentinel = data.get("sentinel", "\n")
    start_wait_s = float(data.get("start_wait_s", 10))
    max_reprompts = int(data.get("max_reprompts", 3))
    trials: list[ChordTrial] = []
    for phase in data.get("phases", []):
        phase_name = str(phase.get("name", ""))
        for raw in phase.get("trials", []):
            fields: dict[str, Any] = {
                "timeout_s": phase.get("timeout_s", timeout_s),
                "start_wait_s": phase.get("start_wait_s", start_wait_s),
                "max_reprompts": phase.get("max_reprompts", max_reprompts),
                "sentinel": phase.get("sentinel", sentinel),
                "resource_id": phase.get("resource_id"),
                "package": phase.get("package"),
                "activity": phase.get("activity"),
                "phase": phase_name,
            }
            for key, value in raw.items():
                if value is not None:
                    fields[key] = value
            fields = localise_texts(
                fields, keymap, f"{path.name}, phase '{phase_name}', trial {len(trials) + 1}"
            )
            trials.append(ChordTrial.model_validate(fields))
    return trials


def strip_sentinel(observed: str, sentinel: str) -> str:
    if sentinel and observed.endswith(sentinel):
        return observed[: -len(sentinel)]
    return observed


def trial_matched(observed: str, expect: str, sentinel: str) -> bool:
    if observed == expect:
        return True
    return bool(sentinel) and strip_sentinel(observed, sentinel) == expect


def character_diff(observed: str, expect: str) -> str:
    if observed == expect:
        return ""
    return "".join(
        difflib.ndiff([expect], [observed])
    ).strip()


def accuracy(observed: str, expect: str) -> float:
    if observed == expect:
        return 1.0
    if not observed and not expect:
        return 1.0
    return round(difflib.SequenceMatcher(a=expect, b=observed).ratio(), 4)


def words_per_minute(text: str, t_first: float | None, t_complete: float | None) -> float | None:
    if t_first is None or t_complete is None:
        return None
    elapsed = t_complete - t_first
    if elapsed <= 0:
        return None
    return round((len(text) / 5) / (elapsed / 60), 2)


def _table(results: list[TrialResult], current: str) -> Table:
    table = Table(title="Plektra chord batch")
    table.add_column("phase")
    table.add_column("prompt")
    table.add_column("result")
    table.add_column("wpm", justify="right")
    for result in results:
        mark = "pass" if result.passed else "fail"
        table.add_row(
            result.phase,
            result.prompt,
            mark,
            "" if result.wpm is None else f"{result.wpm:.1f}",
        )
    if current:
        table.add_row("", current, "typing", "")
    return table


def _read_observed(
    trial: ChordTrial, element: Any, device: Any, controller: ScreenController
) -> str:
    if trial.verify_mode == "clipboard":
        return device.get_clipboard() or ""
    target = element
    if not trial.resource_id and not trial.text_hint:
        focused = device(focused=True)
        if focused.exists():
            target = focused
    return controller.visible_text(target)


def _open_target(controller: ScreenController, trial: ChordTrial) -> Any:
    device = controller.device
    if trial.package and trial.activity:
        controller.teleport_to_activity(trial.package, trial.activity)
    if trial.click_resource_id:
        deadline = time.perf_counter() + 3
        opened = False
        while time.perf_counter() < deadline:
            if trial.resource_id and device(resourceId=trial.resource_id).exists():
                opened = True
                break
            button = device(resourceId=trial.click_resource_id)
            if button.exists():
                button.click()
                opened = True
                break
            time.sleep(POLL_S)
        if trial.resource_id:
            deadline = time.perf_counter() + 3
            while time.perf_counter() < deadline:
                if device(resourceId=trial.resource_id).exists():
                    opened = True
                    break
                time.sleep(POLL_S)
        if not opened:
            raise RuntimeError(f"click target not found: {trial.click_resource_id}")
    return controller.focus_and_clear(
        resource_id=trial.resource_id,
        text_hint=trial.text_hint,
        clear=trial.clear_field,
    )


def run_chord_batch(
    controller: ScreenController,
    trials: list[ChordTrial],
    console: Console | None = None,
    gate: Any | None = None,
    speaker: Speaker | None = None,
    on_trial: Callable[[int, ChordTrial], None] | None = None,
    on_result: Callable[[int, TrialResult], None] | None = None,
) -> list[TrialResult]:
    console = console or Console()
    if speaker is not None:
        phrases = [HANDS_OFF]
        for trial in trials:
            phrases += [spoken_action(trial), REPROMPT + spoken_action(trial)]
        speaker.prepare([*phrases, FINISHED])
    device = controller.device
    results: list[TrialResult] = []
    previous_complete: float | None = None
    with gate if gate is not None else nullcontext():
        console.print(
            "Inverted colors: hands off. Ordinary colors: the shade shows the action, then closes. "
            "Type that action. The screen stays on until this command ends. "
            "Swipe away the stop card to leave testing mode."
        )
        with Live(_table(results, ""), console=console, refresh_per_second=8) as live:
            try:
                results = _run_trials(
                    controller,
                    trials,
                    console,
                    gate,
                    device,
                    live,
                    results,
                    previous_complete,
                    speaker,
                    on_trial,
                    on_result,
                )
            finally:
                if speaker is not None:
                    speaker.say(FINISHED)
    return results


def spoken_action(trial: ChordTrial) -> str:
    return trial.speak or trial.shade or trial.prompt


class _Reprompter:
    """Say the action, then say it again after a human-length quiet spell."""

    def __init__(self, speaker: Speaker | None, trial: ChordTrial) -> None:
        self.speaker = speaker if speaker is not None and speaker.enabled else None
        self.text = spoken_action(trial)
        self.wait_s = trial.start_wait_s
        self.left = trial.max_reprompts
        self.quiet_since: float | None = None

    @property
    def active(self) -> bool:
        return self.speaker is not None

    def start(self) -> None:
        if self.speaker is not None:
            self.speaker.say(self.text)

    def tick(self, now: float) -> bool:
        """False once every reprompt has had its quiet spell and nobody started."""
        if self.speaker is None:
            return True
        if self.speaker.speaking():
            self.quiet_since = None
            return True
        if self.quiet_since is None:
            self.quiet_since = now
            return True
        if now - self.quiet_since < self.wait_s:
            return True
        if self.left <= 0:
            return False
        self.left -= 1
        self.quiet_since = None
        self.speaker.say(REPROMPT + self.text)
        return True

    def started(self) -> None:
        if self.speaker is not None and self.speaker.speaking():
            self.speaker.stop()


def _phone_stopped(gate: Any | None) -> bool:
    if gate is None:
        return False
    check = getattr(gate, "user_stopped", None)
    if not callable(check):
        return False
    try:
        return bool(check())
    except Exception:
        return False


def _run_trials(
    controller: ScreenController,
    trials: list[ChordTrial],
    console: Console,
    gate: Any | None,
    device: Any,
    live: Live,
    results: list[TrialResult],
    previous_complete: float | None,
    speaker: Speaker | None = None,
    on_trial: Callable[[int, ChordTrial], None] | None = None,
    on_result: Callable[[int, TrialResult], None] | None = None,
) -> list[TrialResult]:
    for index, trial in enumerate(trials):
        error = ""
        element = None
        if _phone_stopped(gate):
            error = STOPPED
        if gate is not None and error == "":
            if index == 0 and speaker is not None:
                speaker.say(HANDS_OFF)
            try:
                gate.hold("Preparing the field")
            except Exception as exc:
                error = str(exc)
        if error == "":
            try:
                element = _open_target(controller, trial)
            except Exception as exc:
                error = str(exc)
        if error == "" and _phone_stopped(gate):
            error = STOPPED
        if error == "" and gate is not None:
            try:
                gate.yield_to_human(trial.shade or trial.prompt)
            except Exception as exc:
                error = str(exc)
        ready = time.perf_counter()
        voice = _Reprompter(speaker, trial)
        if error == "":
            if on_trial is not None:
                on_trial(index + 1, trial)
            console.bell()
            if trial.cue_on_device:
                device.beep()
            voice.start()
        if error == "" and trial.verify_mode == "clipboard":
            try:
                device.set_clipboard("")  # a stale copy must not pass the trial
            except Exception:
                pass
        baseline = ""
        if error == "" and element is not None:
            baseline = _read_observed(trial, element, device, controller)
        first: float | None = None
        complete: float | None = None
        observed = baseline
        # With a voice the human gets reprompts instead of a fixed start window.
        deadline = float("inf") if voice.active else ready + trial.timeout_s
        live.update(_table(results, "" if error else trial.prompt))
        last_stop_check = ready
        last_seen = observed
        last_change = ready
        matched_at: float | None = None
        while error == "" and time.perf_counter() < deadline:
            now = time.perf_counter()
            if now - last_stop_check >= STOP_CHECK_S:
                last_stop_check = now
                if _phone_stopped(gate):
                    error = STOPPED
                    break
            try:
                observed = _read_observed(trial, element, device, controller)
            except Exception as exc:  # the person left the app or the field went away
                error = f"field lost: {type(exc).__name__}"
                break
            changed = observed != baseline
            if observed != last_seen:
                last_seen = observed
                last_change = now
                matched_at = None
                if first is not None:
                    deadline = last_change + trial.timeout_s  # the timeout is idle time, not total time
            if changed and first is None and strip_sentinel(observed, trial.sentinel) != "":
                first = time.perf_counter()
                voice.started()
                if voice.active:
                    deadline = first + trial.timeout_s
            if changed and trial_matched(observed, trial.expect, trial.sentinel):
                if matched_at is None:
                    matched_at = time.perf_counter()
                if now - last_change >= SETTLE_S:
                    complete = matched_at
                    break
            if first is None and not voice.tick(now):
                error = NOT_STARTED
                break
            time.sleep(POLL_S)
        if complete is None:
            complete = time.perf_counter()
        compared = strip_sentinel(observed, trial.sentinel)
        passed = error == "" and trial_matched(observed, trial.expect, trial.sentinel)
        gap = None if previous_complete is None else round(ready - previous_complete, 4)
        result = TrialResult(
            phase=trial.phase,
            prompt=trial.prompt,
            expect=trial.expect,
            observed=compared,
            passed=passed,
            accuracy=0.0 if error else accuracy(compared, trial.expect),
            diff="" if passed else (error or character_diff(compared, trial.expect)),
            wpm=words_per_minute(compared, first, complete),
            t_ready=ready,
            t_first_input=first,
            t_complete=complete,
            gap_s=gap,
            error=error,
        )
        results.append(result)
        if on_result is not None:
            on_result(index + 1, result)
        previous_complete = complete
        live.update(_table(results, ""))
        if error in (STOPPED, NOT_STARTED):
            break
    return results
