"""Local 25 ms chord-batch runner. No model call between trials."""

from __future__ import annotations

import difflib
import re
import time
from collections.abc import Callable
from contextlib import nullcontext
from pathlib import Path
from typing import Any, NamedTuple

import yaml
from pydantic import BaseModel, field_validator
from rich.console import Console
from rich.live import Live
from rich.table import Table

from plektra_qa.controller import ScreenController
from plektra_qa.end_state import EndNotice
from plektra_qa.end_state import phone_stopped as _phone_stopped
from plektra_qa.keys import Keymap, localise_texts, resolve_keymap
from plektra_qa.button_bar import ButtonBar
from plektra_qa.probe import FIELDS as BENCH_FIELDS
from plektra_qa.probe import ProbeRun, field_of, is_bench, report_lines
from plektra_qa.probe import resource_id as bench_id
from plektra_qa.speech import Speaker

POLL_S = 0.025
STOP_CHECK_S = 1.0
NO_BAR_IDLE_S = 20.0  # no bar and no timeout: this long without a key after the first input ends the trial
FIELD_LOST_GRACE_S = 10.0  # no bar: the field may be unreadable this long (focus moving) before the trial fails
END_CARD_S = 60.0  # how long the last card offers "redo the previous one" before the batch ends by itself
END_CARD = "All trials done. ◀ Prev redoes the last one. Done finishes."
END_SPOKEN = "All trials done. Previous repeats the last one. Done finishes."
# Wire names on the bar: "retry" (labelled Redo) restarts this trial, "redo" (labelled ◀ Prev) the one before.
TRIAL_BUTTONS = ("done", "retry", "next", "stop")  # the first trial: there is none before it
LATER_BUTTONS = ("done", "retry", "redo", "next", "stop")
RECOVERY_BUTTONS = ("retry", "next", "stop")
RECOVERY_CARD = "Reset failed: {error}. Fix the screen by hand, then Redo. Next skips this trial."
LEAVE_TRIES = 3  # Back presses to leave an open note: the first may only close a keyboard or a menu
LEAVE_WAIT_S = 0.7
DONE_SETTLE_S = 0.3
SETTLE_S = 1.5  # a match counts only after the text stays put this long: people fix typos
STOPPED = "stopped from the phone"
NOT_STARTED = "never started after the spoken prompts"
SKIPPED = "skipped from the phone"
HANDS_OFF = "Hands off. Get ready."
REPROMPT = "Still waiting. "


class ChordTrial(BaseModel):
    prompt: str
    expect: str
    verify_mode: str = "text"
    sentinel: str = "\n"
    timeout_s: float | None = None  # None: wait for the person without a limit
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
    max_reprompts: int | None = None  # None: keep reprompting, never give up
    phase: str = ""
    number: int = 0  # position in the whole suite (1-based): chapters, logs, the table and `import` use it
    raw_prompt: str = ""  # the prompt as written in the YAML, before key tokens were rendered (ledger identity)
    # Compare the first letter without regard to case, for trials that do not test autocapitalization.
    expect_ci_first: bool = False
    # Always start from a fresh note: leave an open note first, then click `click_resource_id`.
    fresh: bool = False
    # The field to focus at the start of a trial that names none (it reads whatever has the focus, so a
    # key may move the focus elsewhere). `plan_trials` fills it from the phase. Never used for reading.
    start_resource_id: str | None = None
    # Test bench trials only: the box the trial is about (default: the one focused at the end), and
    # the text each box starts with (box name: text; the others start empty).
    probe_field: str | None = None
    prefill: dict[str, str] | None = None

    @field_validator("probe_field", "prefill")
    @classmethod
    def _bench_boxes(cls, value: Any) -> Any:
        named = [value] if isinstance(value, str) else list(value or {})
        unknown = [name for name in named if name not in BENCH_FIELDS]
        if unknown:
            raise ValueError(f"no such test bench box: {', '.join(unknown)} (boxes: {', '.join(BENCH_FIELDS)})")
        return value


class TrialResult(BaseModel):
    phase: str
    prompt: str
    expect: str
    observed: str
    passed: bool
    number: int = 0
    accuracy: float
    diff: str
    wpm: float | None
    t_ready: float | None
    t_first_input: float | None
    t_complete: float | None
    gap_s: float | None
    error: str = ""
    # Test bench trials only. `fault` says who made a failed trial differ ("typo": the person;
    # "keyboard-or-app"; "unknown"); `passed` stays the raw result either way.
    fault: str = ""
    probe: dict[str, Any] | None = None  # the bench's report: keys, corrections, difference, layer
    probe_log: str = ""  # the file with the try's probe lines and that report


# djbclark, 2026-11-25: "10 times is too many times to have a human do the same thing unless we really
# think that is neccisary. Limit to 3 max on a regular basis."
MAX_SAME_ACTION = 3
MAX_REASON_CHARS = 80  # the reason has to fit on the bar's start card

_COUNT = r"(?:\d+|(?:one|two|three|four|five|six|seven|eight|nine|ten|eins|zwei|drei|vier|fünf|sechs|sieben|acht|neun|zehn)\b)"
_OF = r"(?:\s*/\s*|\s+(?:of|out\s+of|von)\s+)"
_LABEL = (
    r"(?:try|trial|attempt|round|take|rep|repetition|repeat|iteration|versuch|anlauf|durchgang|wiederholung)"
    r"(?:\s*(?:no\.|nr\.|number|#))?\s*"
)
_START = r"(?:^|(?<=[.:;,!?(\[\n–—-]))\s*"  # the start of the text or of a clause
_NUMBERING = tuple(
    re.compile(pattern, re.IGNORECASE)
    for pattern in (
        rf"\b{_LABEL}{_COUNT}{_OF}{_COUNT}",  # "try 2 of 10", "Versuch 2 von 10", anywhere
        rf"{_START}\b{_LABEL}{_COUNT}",  # "Try 2.", "(attempt 3)"; not "type round 2"
        r"[(\[]\s*#?\d+\s*(?:/|of|out of|von)\s*\d+\s*[)\]]",  # "(2/10)", "[2 of 10]"
        r"^\s*[(\[]\s*#?\d+\s*[)\]]|[(\[]\s*#?\d+\s*[)\]](?=\s*\.?\s*$)",  # "[3] ...", "... (3)"
        rf"^\s*#?\d+{_OF}\d+",  # "2 of 10: ..."
        r"^\s*(?:#\d+|\d+[.)](?=\s))",  # "#3 ...", "3. ..."
        rf"[,;:–—-]\s*\d+{_OF}\d+(?=\s*\.?\s*$)",  # "..., 2 of 10."
        r"\s#\d+(?=\s*\.?\s*$)",  # "... #3"
        rf"{_START}(?:(?:first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth|next|last|final|another|"
        r"\d+(?:st|nd|rd|th))\s+(?:try|attempt|time|round|go)|once\s+more|once\s+again|one\s+more\s+time|again|"
        r"noch\s*(?:ein)?mal)\s*[:,.!–—-]",  # "Again: ...", "Last try, ..."
    )
)


class RepeatError(ValueError):
    """A batch asks the person for the same action too often and does not say why."""


def action_text(text: str) -> str:
    """An instruction without its numbering, so that "try 2 of 10: X" and "try 3 of 10: X" are one action."""
    for pattern in _NUMBERING:
        text = pattern.sub(" ", text)
    text = re.sub(r"[(\[]\s*[)\]]", " ", text)  # brackets the numbering left empty
    text = re.sub(r"\s+[.:,;](?=\s|$)", " ", text)  # and its punctuation
    text = re.sub(r"^[\s:.,;|–—-]+|[\s:,;|–—-]+$", "", " ".join(text.split()))
    return text[:1].lower() + text[1:]


def check_repeats(trials: list[ChordTrial], reason: object, where: str) -> str:
    """Refuse a batch that asks for the same action more than MAX_SAME_ACTION times without a reason.

    The same action: the same instruction on the phone (`shade`, else `prompt`), or the same spoken
    instruction with the same expected text; numbering is ignored. Returns the reason to show on the
    start card, or "" when nothing repeats too often."""
    if reason is not None and not isinstance(reason, str):
        raise RepeatError(f"{where}: repeat_reason must be a text that says why, not {reason!r}")
    why = " ".join((reason or "").split())
    if len(why) > MAX_REASON_CHARS:
        raise RepeatError(
            f"{where}: repeat_reason has {len(why)} characters; keep it to {MAX_REASON_CHARS} so that it "
            "fits on the start card"
        )
    shown: dict[str, list[int]] = {}
    said: dict[tuple[str, str], list[int]] = {}
    for position, trial in enumerate(trials, start=1):
        number = trial.number or position
        shown.setdefault(action_text(trial.shade or trial.prompt), []).append(number)
        said.setdefault((action_text(spoken_action(trial)), trial.expect), []).append(number)
    groups = [(text, numbers) for text, numbers in shown.items()]
    groups += [(text, numbers) for (text, _expect), numbers in said.items()]
    text, numbers = max(groups, key=lambda group: len(group[1]), default=("", []))
    if len(numbers) <= MAX_SAME_ACTION:
        return ""
    if not why:
        raise RepeatError(
            f"{where} asks the person for the same action {len(numbers)} times (trials "
            f"{', '.join(map(str, numbers))}: {text!r}); the limit is {MAX_SAME_ACTION}. Cut the repeats. Only if "
            "they are really necessary, say why in a non-empty repeat_reason (a top-level suite field; the "
            "repeat_reason argument over MCP), which the start card shows to the person."
        )
    return why


class Suite(NamedTuple):
    trials: list[ChordTrial]
    repeat_reason: str  # for the start card; "" unless the suite repeats an action more than the limit


def load_suite(path: Path, keymap: Keymap | None = None) -> list[ChordTrial]:
    """The trials of a suite (see `read_suite`, which also refuses the suite)."""
    return read_suite(path, keymap).trials


def read_suite(path: Path, keymap: Keymap | None = None) -> Suite:
    """Read a suite. Key tokens in prompt/shade/speak are expanded with `keymap`
    (default: the live or profile keymap, see `keys.resolve_keymap`); a bare key
    name or a token no keycap can produce refuses the whole suite, and so does
    asking for the same action too often (`check_repeats`)."""
    data = yaml.safe_load(path.read_text())
    if keymap is None:
        keymap = resolve_keymap().keymap
    raw_timeout = data.get("timeout_s")
    timeout_s = float(raw_timeout) if raw_timeout is not None else None
    sentinel = data.get("sentinel", "\n")
    start_wait_s = float(data.get("start_wait_s", 10))
    raw_reprompts = data.get("max_reprompts")
    max_reprompts = int(raw_reprompts) if raw_reprompts is not None else None
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
                "start_resource_id": phase.get("start_resource_id"),
                "package": phase.get("package"),
                "activity": phase.get("activity"),
                "probe_field": phase.get("probe_field"),
                "prefill": phase.get("prefill"),
                "phase": phase_name,
            }
            for key, value in raw.items():
                if value is not None:
                    fields[key] = value
            fields = localise_texts(
                fields, keymap, f"{path.name}, phase '{phase_name}', trial {len(trials) + 1}"
            )
            fields["number"] = len(trials) + 1
            fields["raw_prompt"] = str(raw.get("prompt", ""))
            trials.append(ChordTrial.model_validate(fields))
    return Suite(trials, check_repeats(trials, data.get("repeat_reason"), path.name))


SETUP_FIELDS = ("package", "activity", "click_resource_id")  # what opens a phase's screen


def _borrowed(trial: ChordTrial, peers: list[ChordTrial]) -> dict[str, Any]:
    """The setup `trial` lacks and a peer of its phase has (the first peer that has it wins).

    A peer's field becomes `start_resource_id`, never `resource_id`: a trial that names no field reads
    the focused one, and must keep doing so (a key may move the focus to another field)."""
    update: dict[str, Any] = {}
    for name in SETUP_FIELDS:
        if getattr(trial, name) is None:
            value = next((getattr(p, name) for p in peers if getattr(p, name) is not None), None)
            if value is not None:
                update[name] = value
    if not (trial.resource_id or trial.text_hint or trial.start_resource_id):
        field = next((p.resource_id or p.start_resource_id for p in peers
                      if p.resource_id or p.start_resource_id), None)
        if field is not None:
            update["start_resource_id"] = field
    return update


def _setup_of(trials: list[ChordTrial], index: int) -> ChordTrial:
    """Trial `index` with all it takes to open its screen from nothing: the app, the button and the
    field in focus at the start, borrowed from its phase (the nearest earlier trial first).

    For opening only. The trial is always read as written."""
    trial = trials[index]
    peers: list[ChordTrial] = []
    if trial.phase:
        peers = [t for t in reversed(trials[:index]) if t.phase == trial.phase]
        peers += [t for t in trials[index + 1:] if t.phase == trial.phase]
    trial = trial.model_copy(update=_borrowed(trial, peers))
    if not trial.resource_id and not trial.text_hint and trial.start_resource_id:
        trial = trial.model_copy(update={"resource_id": trial.start_resource_id})
    return trial


def plan_trials(all_trials: list[ChordTrial], is_passed: Callable[[ChordTrial], bool]) -> list[ChordTrial]:
    """The trials to run: a phase is skipped only when every trial in it passed.

    Pending trials run. The first pending trial of a phase gets the phase's setup (the app to open, the
    button to click, the field to start in), copied from the phase's other trials, so a skipped first
    trial never leaves the next one with no app open. A trial that keeps the field (`clear_field: false`)
    depends on the trial before it, so that one runs again too."""
    by_phase: dict[str, list[ChordTrial]] = {}
    for trial in all_trials:
        by_phase.setdefault(trial.phase, []).append(trial)
    pending_numbers: set[int] = set()
    for members in by_phase.values():
        if all(is_passed(t) for t in members):
            continue
        for i, trial in enumerate(members):
            if not is_passed(trial):
                pending_numbers.add(trial.number)
                if not trial.clear_field and i > 0:
                    pending_numbers.add(members[i - 1].number)
    planned: list[ChordTrial] = []
    first_seen: set[str] = set()
    for trial in all_trials:
        if trial.number not in pending_numbers:
            continue
        if trial.phase not in first_seen:
            first_seen.add(trial.phase)
            update = _borrowed(trial, by_phase[trial.phase])
            if update:
                trial = trial.model_copy(update=update)
        planned.append(trial)
    return planned


def allowed_packages(all_trials: list[ChordTrial]) -> set[str]:
    """Every app any trial of the suite may use, also the trials that are skipped this time."""
    return {trial.package for trial in all_trials if trial.package}


def strip_sentinel(observed: str, sentinel: str) -> str:
    if sentinel and observed.endswith(sentinel):
        return observed[: -len(sentinel)]
    return observed


def _same(observed: str, expect: str, ci_first: bool) -> bool:
    if observed == expect:
        return True
    return bool(ci_first and observed and expect and observed[0].casefold() == expect[0].casefold()
                and observed[1:] == expect[1:])


def trial_matched(observed: str, expect: str, sentinel: str, ci_first: bool = False) -> bool:
    if _same(observed, expect, ci_first):
        return True
    if not expect.startswith(("\n", "\t")):
        # <Tab|Enter> may be either key: Enter leaves a leading newline, Tab a tab (or nothing).
        observed = observed.lstrip("\n\t")
        if _same(observed, expect, ci_first):
            return True
    return bool(sentinel) and _same(strip_sentinel(observed, sentinel), expect, ci_first)


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
    table.add_column("#", justify="right")
    table.add_column("phase")
    table.add_column("prompt")
    table.add_column("result")
    table.add_column("wpm", justify="right")
    for result in results:
        mark = "pass" if result.passed else "fail"
        table.add_row(
            str(result.number or ""),
            result.phase,
            result.prompt,
            mark,
            "" if result.wpm is None else f"{result.wpm:.1f}",
        )
    if current:
        table.add_row("", "", current, "typing", "")
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


def _leave_open_note(device: Any, trial: ChordTrial) -> bool:
    """A trial that wants a fresh note: if its field is on screen, go back until it is gone. True if it left."""
    if not (trial.fresh and trial.click_resource_id and trial.resource_id):
        return False
    if not device(resourceId=trial.resource_id).exists():
        return False
    from lamda.client import Keys

    for _ in range(LEAVE_TRIES):
        device.press_key(Keys.KEY_BACK)
        deadline = time.perf_counter() + LEAVE_WAIT_S
        while time.perf_counter() < deadline:
            time.sleep(POLL_S)
            if not device(resourceId=trial.resource_id).exists():
                return True
    raise RuntimeError(f"could not leave the open note: {trial.resource_id} is still on screen")


def _open_target(controller: ScreenController, trial: ChordTrial) -> Any:
    device = controller.device
    if trial.package and trial.activity:
        controller.teleport_to_activity(trial.package, trial.activity)
    if _leave_open_note(device, trial) and trial.package and trial.activity:
        if not device(resourceId=trial.click_resource_id).exists():
            controller.teleport_to_activity(trial.package, trial.activity)  # Back went past the list
    if trial.click_resource_id:
        field_seen = clicked = False
        deadline = time.perf_counter() + 3
        while time.perf_counter() < deadline:
            if trial.resource_id and device(resourceId=trial.resource_id).exists():
                field_seen = True
                break
            button = device(resourceId=trial.click_resource_id)
            if button.exists():
                button.click()
                clicked = True
                break
            time.sleep(POLL_S)
        if trial.resource_id and not field_seen:
            deadline = time.perf_counter() + 3
            while time.perf_counter() < deadline:
                if device(resourceId=trial.resource_id).exists():
                    field_seen = True
                    break
                time.sleep(POLL_S)
        if not (clicked or field_seen):
            raise RuntimeError(f"click target not found: {trial.click_resource_id}")
    return controller.focus_and_clear(
        resource_id=trial.resource_id,
        text_hint=trial.text_hint,
        clear=trial.clear_field,
    )


def _replays(trials: list[ChordTrial], index: int) -> bool:
    """A redo of this trial replays the one before it: it keeps that trial's text in the field."""
    trial = trials[index]
    return not trial.clear_field and index > 0 and trials[index - 1].phase == trial.phase


class _Start(NamedTuple):
    """What a trial found when it began."""

    resource_id: str | None  # the field that had the focus
    text: str | None  # its text; recorded only for a trial that keeps it
    others: dict[str, str]  # every editable field's text, by resource id
    clipboard: str | None  # None: not recorded (a clipboard trial starts with it empty)


class _Screens:
    """Open each trial's screen and remember how it looked, so that a redo can put it back.

    Redo restores the trial's true starting state, not only its own field: text typed into any other
    field is removed and the focus returns to the start field. A trial that clears its field, in a
    phase with a "new note" button, gets a new note (also at a normal start when another field was
    left dirty). Elsewhere the changed fields are cleared one by one. The host never types, so text
    a trial keeps from the one before it comes back by replaying that trial."""

    def __init__(
        self, controller: ScreenController, trials: list[ChordTrial], redoable: bool, probe: ProbeRun | None = None
    ) -> None:
        self.probe = probe
        self.controller = controller
        self.device = controller.device
        self.trials = trials
        self.redoable = redoable  # only the bar offers a redo; without it nothing is recorded for one
        self.starts: dict[int, _Start] = {}
        self.base: dict[str, dict[str, str]] = {}  # per phase: every field's text on a clean screen
        self.begun: set[str] = set()  # phases that started in this run

    def prepare(self, index: int, redo: bool = False) -> Any:
        """Open the screen for trial `index` and return the field that has the focus."""
        trial = self.trials[index]
        setup = _setup_of(self.trials, index)
        if self.probe is not None and is_bench(setup.package, setup.activity):
            return self._bench(trial, setup, trial.number or index + 1)
        key = trial.phase or f"#{index}"
        saved = self.starts.get(index) if redo else None
        can_reopen = bool(setup.click_resource_id and setup.resource_id)
        first_of_phase = bool(trial.phase) and trial.phase not in self.begun
        target = trial
        reopened = False
        if trial.clear_field:
            if not trial.resource_id and not trial.text_hint and setup.resource_id:
                target = trial.model_copy(update={"resource_id": setup.resource_id})
            if can_reopen and (
                redo or first_of_phase or trial.fresh or self._changed(self.base.get(key), setup.resource_id)
            ):
                target = setup.model_copy(update={"fresh": True})
                reopened = True
        if redo and not reopened:
            field = target.resource_id or (saved.resource_id if saved else None)
            if field and not self.device(resourceId=field).exists():
                target = setup  # the screen is gone: open it again
        spare = (saved.resource_id if saved else None) or setup.resource_id
        element = self._open(target, spare)
        self.begun.add(trial.phase)
        focus = target.resource_id
        if focus is None and (self.redoable or key not in self.base):
            focus = getattr(element.info(), "resourceName", None) or None
        if trial.clear_field:
            if not reopened and self._restore(self.base.get(key), skip=focus, strict=redo):
                self._focus(element)
        elif saved is not None:
            self._restore(saved.others, skip=saved.resource_id, strict=True)
            if saved.resource_id:
                if saved.text is not None and not self.controller.restore_field(saved.resource_id, saved.text):
                    raise RuntimeError(f"the start field needs the text {saved.text!r}")
                element = self.device(resourceId=saved.resource_id)
                self._focus(element)
        # A kept field with no earlier trial to replay: record it, so a redo can check it.
        snapshot = self.redoable and saved is None and not trial.clear_field and not _replays(self.trials, index)
        fields = self.controller.editable_fields() if snapshot or key not in self.base else None
        if fields is not None:
            self.base.setdefault(key, fields)
        if self.redoable and saved is None:
            text = None
            if snapshot and focus:
                try:
                    text = element.get_text() or ""
                except Exception:
                    pass
            self.starts[index] = _Start(focus, text, fields or {}, self._clipboard(trial))
        self._reset_clipboard(trial, saved)
        return element

    def _bench(self, trial: ChordTrial, setup: ChordTrial, number: int) -> Any:
        """The test bench resets by intent: no taps, and a redo starts exactly like the first try."""
        rid = trial.resource_id or setup.resource_id
        fresh = trial.clear_field or trial.prefill is not None or trial.phase not in self.begun
        if fresh:
            self.probe.reset(number, field_of(rid) or "body", trial.prefill or {}, trial.expect)
        else:
            self.probe.keep()
        self.begun.add(trial.phase)
        self._reset_clipboard(trial, None)
        if trial.resource_id:
            return self.device(resourceId=trial.resource_id)
        if fresh:
            return self.device(resourceId=bench_id(field_of(rid) or "body"))
        return self.device(focused=True)

    def _open(self, target: ChordTrial, spare: str | None) -> Any:
        try:
            return _open_target(self.controller, target)
        except RuntimeError as exc:
            # The trial names no field and nothing has the focus: go to the field it started in.
            if target.resource_id or target.text_hint or not spare or "none is focused" not in str(exc):
                raise
            return _open_target(self.controller, target.model_copy(update={"resource_id": spare}))

    def _changed(self, wanted: dict[str, str] | None, skip: str | None) -> dict[str, str]:
        """The fields on screen, other than `skip`, that no longer read as in `wanted`."""
        if not wanted or not any(rid != skip for rid in wanted):
            return {}
        now = self.controller.editable_fields()
        if now is None:
            return {}
        return {rid: text for rid, text in wanted.items() if rid != skip and rid in now and now[rid] != text}

    def _restore(self, wanted: dict[str, str] | None, skip: str | None, strict: bool) -> bool:
        """Clear the other fields that changed. `strict` (a redo): a field that cannot be put back is an
        error; at a normal start it is left as it is. True if any field had changed."""
        changed = self._changed(wanted, skip)
        failed = [rid for rid, text in changed.items() if not self.controller.restore_field(rid, text)]
        if failed and strict:
            raise RuntimeError(f"field not restored: {', '.join(rid.rsplit('/', 1)[-1] for rid in failed)}")
        return bool(changed)

    def _focus(self, element: Any) -> None:
        if not element.info().focused:
            element.click()

    def _clipboard(self, trial: ChordTrial) -> str | None:
        if trial.verify_mode == "clipboard":
            return None
        try:
            return self.device.get_clipboard() or ""
        except Exception:
            return None

    def _reset_clipboard(self, trial: ChordTrial, saved: _Start | None) -> None:
        try:
            if trial.verify_mode == "clipboard":
                self.device.set_clipboard("")  # a stale copy must not pass the trial
            elif saved is not None and saved.clipboard is not None:
                if (self.device.get_clipboard() or "") != saved.clipboard:
                    self.device.set_clipboard(saved.clipboard)
        except Exception:
            pass


def run_chord_batch(
    controller: ScreenController,
    trials: list[ChordTrial],
    console: Console | None = None,
    gate: Any | None = None,
    speaker: Speaker | None = None,
    on_trial: Callable[[int, ChordTrial], None] | None = None,
    on_result: Callable[[int, TrialResult], None] | None = None,
    overlay: ButtonBar | None = None,
    gate_entered: bool = False,
    on_redo: Callable[[ChordTrial], None] | None = None,
    on_discard: Callable[[int, ChordTrial], None] | None = None,
    probe: ProbeRun | None = None,
    notice: EndNotice | None = None,
) -> list[TrialResult]:
    """probe: the test bench session; trials on the bench then reset by intent and get a report.

    notice: the caller's end-of-batch message. The batch only marks it stopped; the caller delivers it
    with `notice.finish(gate)` once the phone is no longer filmed and the gate has restored it, so
    that "the phone may be used normally" is true when the person reads it. Without one, the batch says
    that itself (bar and voice) when it ends.

    gate_entered: the caller already entered `gate` (the preflight ran before the person's go).

    on_redo: a redo goes back over this trial; any earlier result of it is withdrawn.
    on_discard: the attempt under way was dropped (Redo or ◀ Prev on the bar): it gets no result."""
    console = console or Console()
    own_notice = notice is None
    if own_notice:
        notice = EndNotice(speaker=speaker, bar=overlay, console=console)
    if notice.bar is None:
        notice.bar = overlay
    if speaker is not None:
        phrases = [HANDS_OFF]
        for trial in trials:
            phrases += [spoken_action(trial), REPROMPT + spoken_action(trial)]
        speaker.prepare([*phrases, *notice.phrases()])
    device = controller.device
    results: list[TrialResult] = []
    previous_complete: float | None = None
    entered = False
    try:
        with gate if gate is not None and not gate_entered else nullcontext():
            entered = True
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
                        overlay,
                        on_redo,
                        on_discard,
                        probe,
                    )
                finally:
                    # Asked while the gate still holds the phone. A Stop, on the bar or the stop card, frees it.
                    notice.stopped = _phone_stopped(gate) or any(item.error == STOPPED for item in results)
    finally:
        if own_notice and entered:
            notice.started = True
            notice.finish(None if gate_entered else gate)
    return results


def spoken_action(trial: ChordTrial) -> str:
    return trial.speak or trial.shade or trial.prompt


class _Reprompter:
    """Say the action, then say it again after a human-length quiet spell."""

    def __init__(self, speaker: Speaker | None, trial: ChordTrial) -> None:
        self.speaker = speaker if speaker is not None and speaker.enabled else None
        self.text = spoken_action(trial)
        self.wait_s = trial.start_wait_s
        self.left = trial.max_reprompts if trial.max_reprompts is not None else 10**9
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
    overlay: ButtonBar | None = None,
    on_redo: Callable[[ChordTrial], None] | None = None,
    on_discard: Callable[[int, ChordTrial], None] | None = None,
    probe: ProbeRun | None = None,
) -> list[TrialResult]:
    # No limit unless the suite or the operator sets `timeout_s`.
    idle_s_for = lambda trial: float("inf") if trial.timeout_s is None else trial.timeout_s  # noqa: E731
    # With no bar the person has no Done button, so a wrong text must still end the trial.
    idle_after_input = lambda trial: (  # noqa: E731
        NO_BAR_IDLE_S if trial.timeout_s is None and overlay is None else idle_s_for(trial)
    )
    def rewind(last: int) -> int:
        """Withdraw the results from trial `last` back to the trial that starts its field; return that index."""
        start = last
        while start > 0 and not trials[start].clear_field:  # it needs the text the earlier trial leaves
            start -= 1
        for position in range(start, last + 1):
            number = trials[position].number or position + 1
            results[:] = [item for item in results if item.number != number]
            if on_redo is not None:
                on_redo(trials[position])
        live.update(_table(results, ""))
        return start

    def redo_this(index: int) -> int:
        """Where "redo this trial" starts again. A field kept from the trial before is rebuilt by
        replaying that trial (the person types it again)."""
        return rewind(index - 1) if _replays(trials, index) else index

    def discard(number: int, trial: ChordTrial) -> None:
        if on_discard is not None:
            on_discard(number, trial)

    screens = _Screens(controller, trials, redoable=overlay is not None, probe=probe)
    index = 0
    redo_target: int | None = None  # the trial whose screen must be put back before it runs again
    handed_off = False
    while True:
        if index >= len(trials):
            if overlay is None or not trials or not _end_card(overlay, speaker):
                break
            index = redo_target = rewind(len(trials) - 1)
        trial = trials[index]
        number = trial.number or index + 1
        redoing = redo_target == index
        redo_target = None
        error = ""
        again = ""  # "retry" or "redo": the person dropped this attempt
        by_press = False  # the bar already shows the wait card when a button ended the trial
        element = None
        benched = False  # this attempt has probe lines to report
        if _phone_stopped(gate):
            error = STOPPED
        if gate is not None and error == "":
            if not handed_off and speaker is not None:
                speaker.say(HANDS_OFF)
            handed_off = True
            try:
                gate.hold("Preparing the field")
            except Exception as exc:
                error = str(exc)
        if error == "":
            try:
                element = screens.prepare(index, redoing)
                setup = _setup_of(trials, index)
                benched = (
                    probe is not None and trial.verify_mode == "text" and is_bench(setup.package, setup.activity)
                )
            except Exception as exc:
                error = str(exc) or type(exc).__name__
        if error == "" and _phone_stopped(gate):
            error = STOPPED
        if redoing and overlay is not None and error not in ("", STOPPED):
            choice = _recovery_card(overlay, gate, error)
            if choice == "retry":
                discard(number, trial)
                _wait_card(overlay, pressed=True)
                redo_target = index
                continue
            if choice:
                by_press = True
                error = SKIPPED if choice == "next" else STOPPED
                if on_trial is not None:
                    on_trial(number, trial)
        if error == "" and gate is not None:
            try:
                if overlay is not None:
                    gate.yield_to_human(trial.shade or trial.prompt, shade=False)
                else:
                    gate.yield_to_human(trial.shade or trial.prompt)
            except Exception as exc:
                error = str(exc)
        if error == "" and overlay is not None:
            try:
                overlay.show(trial.shade or trial.prompt, LATER_BUTTONS if index > 0 else TRIAL_BUTTONS)
            except Exception as exc:
                error = str(exc)
        baseline = ""
        if error == "" and element is not None:
            # Read it before the voice starts: say() may block, and typing then must not become the baseline.
            try:
                baseline = _read_observed(trial, element, device, controller)
            except Exception as exc:
                if overlay is None:
                    error = f"field lost: {type(exc).__name__}"
        ready = time.perf_counter()
        voice = _Reprompter(speaker, trial)
        if error == "":
            if on_trial is not None:
                on_trial(number, trial)
            console.bell()
            if trial.cue_on_device:
                device.beep()
            voice.start()
        first: float | None = None
        complete: float | None = None
        observed = baseline
        # With a voice the human gets reprompts instead of a fixed start window.
        deadline = float("inf") if voice.active else ready + idle_s_for(trial)
        live.update(_table(results, "" if error else trial.prompt))
        last_stop_check = ready
        last_seen = observed
        last_change = ready
        matched_at: float | None = None
        lost_since: float | None = None
        while error == "" and time.perf_counter() < deadline:
            now = time.perf_counter()
            if now - last_stop_check >= STOP_CHECK_S:
                last_stop_check = now
                if _phone_stopped(gate):
                    error = STOPPED
                    break
            try:
                observed = _read_observed(trial, element, device, controller)
                lost_since = None
            except Exception as exc:  # focus moving, the person left the app, or the field went away
                # Never end a trial under the person's hands: with the bar they decide (2026-11-25).
                if lost_since is None:
                    lost_since = now
                    console.print(f"trial {number}: field unreadable ({type(exc).__name__}), still waiting")
                if overlay is None and now - lost_since >= FIELD_LOST_GRACE_S:
                    error = f"field lost: {type(exc).__name__}"
                    break
            changed = observed != baseline
            if observed != last_seen:
                last_seen = observed
                last_change = now
                matched_at = None
                if first is not None:
                    deadline = last_change + idle_after_input(trial)  # the timeout is idle time, not total time
            if changed and first is None and strip_sentinel(observed, trial.sentinel) != "":
                first = time.perf_counter()
                voice.started()
                deadline = first + idle_after_input(trial)
            if overlay is not None:
                button = overlay.pressed()
                if button in ("done", "next", "stop"):
                    voice.started()  # the person has acted: nobody waits for the prompt to finish
                if button == "retry" or (button == "redo" and index > 0):
                    again = button
                    break
                by_press = button in ("stop", "next", "done")
                if button == "stop":
                    error = STOPPED
                    break
                if button == "next":
                    error = SKIPPED
                    break
                if button == "done":
                    complete = time.perf_counter()  # the person says it is finished
                    time.sleep(DONE_SETTLE_S)  # a last key or an autocorrect commit may still land
                    try:
                        observed = _read_observed(trial, element, device, controller)
                    except Exception as exc:
                        error = f"field lost: {type(exc).__name__}"
                    break
            elif changed and trial_matched(observed, trial.expect, trial.sentinel, trial.expect_ci_first):
                if matched_at is None:
                    matched_at = time.perf_counter()
                if now - last_change >= SETTLE_S:
                    complete = matched_at
                    break
            if first is None and not voice.tick(now):
                error = NOT_STARTED
                break
            time.sleep(POLL_S)
        if again:
            voice.started()  # stop reading this trial's action
            if benched:
                _bench_report(
                    probe, console, number, trial, strip_sentinel(observed, trial.sentinel), False, discarded=True
                )
            discard(number, trial)
            _wait_card(overlay, pressed=True)
            index = redo_target = redo_this(index) if again == "retry" else rewind(index - 1)
            continue
        if complete is None:
            complete = time.perf_counter()
        compared = strip_sentinel(observed, trial.sentinel)
        passed = error == "" and trial_matched(observed, trial.expect, trial.sentinel, trial.expect_ci_first)
        gap = None if previous_complete is None else round(ready - previous_complete, 4)
        report, log = None, ""
        if benched:
            report, log = _bench_report(probe, console, number, trial, compared, passed, error=error)
        result = TrialResult(
            phase=trial.phase,
            prompt=trial.prompt,
            expect=trial.expect,
            observed=compared,
            passed=passed,
            number=number,
            accuracy=0.0 if error else accuracy(compared, trial.expect),
            diff="" if passed else (error or character_diff(compared, trial.expect)),
            wpm=words_per_minute(compared, first, complete),
            t_ready=ready,
            t_first_input=first,
            t_complete=complete,
            gap_s=gap,
            error=error,
            fault="" if passed or report is None else report["fault"],
            probe=report,
            probe_log=log,
        )
        results.append(result)
        if on_result is not None:
            on_result(number, result)
        previous_complete = complete
        live.update(_table(results, ""))
        if error in (STOPPED, NOT_STARTED):
            break
        index += 1
        if overlay is not None:
            _wait_card(overlay, pressed=by_press)
    return results


def _bench_report(
    probe: ProbeRun, console: Console, number: int, trial: ChordTrial, observed: str, passed: bool,
    error: str = "", discarded: bool = False,
) -> tuple[dict[str, Any] | None, str]:
    """The bench's report of an attempt, printed unless the attempt was dropped, and its file."""
    try:
        report, path = probe.finish(
            number, phase=trial.phase, field=trial.probe_field, expect=trial.expect, observed=observed,
            passed=passed, error=error, discarded=discarded,
        )
    except Exception as exc:  # the telemetry must never cost a trial
        console.print(f"trial {number}: no probe report ({type(exc).__name__}: {exc})", markup=False)
        return None, ""
    if not discarded:
        for line in report_lines(number, report, passed):
            console.print(line, markup=False, highlight=False)
    return report, str(path)


def _wait_card(overlay: Any, pressed: bool) -> None:
    """Between trials the bar says to wait and has no buttons. `pressed`: a button ended the card."""
    wait = getattr(overlay, "wait", None)
    if callable(wait):
        try:
            wait(pressed)
        except Exception:
            pass  # the next card reports a bar that is gone


def _recovery_card(overlay: ButtonBar, gate: Any | None, error: str) -> str:
    """A redo could not put the screen back. The person fixes it by hand and presses Redo, or skips.

    Returns the button ("retry", "next" or "stop"), or "" when the card could not be shown."""
    try:
        if gate is not None:
            gate.yield_to_human("Reset failed", shade=False)
        overlay.show(RECOVERY_CARD.format(error=error[:90].rstrip(". ")), RECOVERY_BUTTONS)
    except Exception:
        return ""
    checked = time.perf_counter()
    while True:
        button = overlay.pressed()
        if button in RECOVERY_BUTTONS:
            return button
        if time.perf_counter() - checked >= STOP_CHECK_S:
            checked = time.perf_counter()
            if _phone_stopped(gate):
                return "stop"
        time.sleep(POLL_S)


def _end_card(overlay: ButtonBar, speaker: Speaker | None) -> bool:
    """After the last trial: True when the person asks to redo it (◀ Prev). Done, silence or no bar ends
    the batch."""
    if END_CARD_S <= 0:
        return False
    try:
        overlay.show(END_CARD, ("done", "redo"))
    except Exception:
        return False
    if speaker is not None:
        speaker.say(END_SPOKEN)
    deadline = time.perf_counter() + END_CARD_S
    while time.perf_counter() < deadline:
        button = overlay.pressed()
        if button in ("done", "redo") and speaker is not None:
            speaker.stop()
        if button == "redo":
            _wait_card(overlay, pressed=True)
            return True
        if button == "done":
            break
        time.sleep(POLL_S)
    return False
