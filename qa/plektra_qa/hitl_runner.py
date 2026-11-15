"""Local 25 ms chord-batch runner. No model call between trials."""

from __future__ import annotations

import difflib
import time
from pathlib import Path
from typing import Any

import yaml
from pydantic import BaseModel
from rich.console import Console
from rich.live import Live
from rich.table import Table

from plektra_qa.controller import ScreenController

POLL_S = 0.025


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
    cue_on_device: bool = False
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


def load_suite(path: Path) -> list[ChordTrial]:
    data = yaml.safe_load(path.read_text())
    timeout_s = float(data.get("timeout_s", 30))
    sentinel = data.get("sentinel", "\n")
    trials: list[ChordTrial] = []
    for phase in data.get("phases", []):
        phase_name = str(phase.get("name", ""))
        for raw in phase.get("trials", []):
            fields: dict[str, Any] = {
                "timeout_s": phase.get("timeout_s", timeout_s),
                "sentinel": phase.get("sentinel", sentinel),
                "resource_id": phase.get("resource_id"),
                "package": phase.get("package"),
                "activity": phase.get("activity"),
                "phase": phase_name,
            }
            for key, value in raw.items():
                if value is not None:
                    fields[key] = value
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


def _read_observed(trial: ChordTrial, element: Any, device: Any) -> str:
    if trial.verify_mode == "clipboard":
        return device.get_clipboard() or ""
    return element.get_text() or ""


def run_chord_batch(
    controller: ScreenController,
    trials: list[ChordTrial],
    console: Console | None = None,
) -> list[TrialResult]:
    console = console or Console()
    device = controller.device
    results: list[TrialResult] = []
    previous_complete: float | None = None
    with Live(_table(results, ""), console=console, refresh_per_second=8) as live:
        for trial in trials:
            error = ""
            element = None
            try:
                if trial.package and trial.activity:
                    controller.teleport_to_activity(trial.package, trial.activity)
                element = controller.focus_and_clear(
                    resource_id=trial.resource_id,
                    text_hint=trial.text_hint,
                )
            except Exception as exc:
                error = str(exc)
            ready = time.perf_counter()
            console.bell()
            if trial.cue_on_device:
                device.beep()
            baseline = ""
            if error == "" and element is not None:
                baseline = _read_observed(trial, element, device)
            first: float | None = None
            complete: float | None = None
            observed = baseline
            deadline = ready + trial.timeout_s
            live.update(_table(results, trial.prompt))
            while error == "" and time.perf_counter() < deadline:
                observed = _read_observed(trial, element, device)
                changed = observed != baseline
                if changed and first is None and strip_sentinel(observed, trial.sentinel) != "":
                    first = time.perf_counter()
                if trial_matched(observed, trial.expect, trial.sentinel):
                    complete = time.perf_counter()
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
            previous_complete = complete
            live.update(_table(results, ""))
    return results
