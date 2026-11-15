import base64
import io
from pathlib import Path
from types import SimpleNamespace

from rich.console import Console

from plektra_qa.controller import ScreenController
from plektra_qa.hitl_runner import (
    ChordTrial,
    accuracy,
    load_suite,
    run_chord_batch,
    trial_matched,
    words_per_minute,
)
from plektra_qa.screen_gate import (
    AWAKE_MS,
    AWAKE_PID,
    AWAKE_RELEASE,
    AWAKE_SCRIPT,
    HEADS_UP_KEY,
    INVERSION_KEY,
    STAY_KEY,
    STOP_TAG,
    STOP_TEXT,
    TIMEOUT_KEY,
    ScreenGate,
    shell_line,
)

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = ROOT / "plektra_qa"


def test_suite_drops_null_overrides_and_keeps_phase_name():
    trials = load_suite(ROOT / "suites" / "smoke_chords.yaml")
    assert [trial.prompt for trial in trials][:2] == ["alpha: hello", "digits: 42"]
    assert trials[0].phase == "one_screen"
    assert trials[0].resource_id == "branislav667.pinnote:id/pin_text"
    assert trials[0].package == "branislav667.pinnote"
    assert trials[0].click_resource_id == "branislav667.pinnote:id/notes_fab"
    assert trials[0].shade == "Type hello"
    assert trials[-2].shade.startswith("Press your Tab or your Enter, then type beta.")
    assert trials[0].sentinel == "\n"
    clipboard = trials[-1]
    assert clipboard.verify_mode == "clipboard"
    assert clipboard.expect == "beta"
    assert clipboard.clear_field is False


def test_match_accepts_the_sentinel_suffix():
    assert trial_matched("hello", "hello", "\n")
    assert trial_matched("hello\n", "hello", "\n")
    assert not trial_matched("hello!", "hello", "\n")


def test_wpm_uses_the_first_input_to_complete_window():
    assert words_per_minute("hello", 10.0, 12.0) == 30.0
    assert words_per_minute("hello", None, 12.0) is None
    assert accuracy("hello", "hello") == 1.0


class _Field:
    def __init__(self, text: str, hint: str) -> None:
        self.text = text
        self.hint = hint
        self.focused = True

    def exists(self) -> bool:
        return True

    def info(self):
        return SimpleNamespace(focused=self.focused)

    def click(self) -> None:
        self.focused = True

    def clear_text_field(self) -> None:
        self.text = ""

    def get_text(self) -> str:
        return self.text or self.hint


class _Device:
    def __init__(self, field: _Field) -> None:
        self.field = field

    def __call__(self, **_kwargs):
        return self.field


def test_clear_treats_the_hint_that_survives_clear_as_empty():
    field = _Field("", "Start writing…")
    controller = ScreenController(_Device(field))
    controller.focus_and_clear(resource_id="field")
    assert controller.visible_text(field) == ""


def test_clear_accepts_real_text_that_falls_back_to_the_hint():
    field = _Field("Hello", "Title")
    controller = ScreenController(_Device(field))
    controller.focus_and_clear(resource_id="field")
    assert controller.visible_text(field) == ""
    assert controller._resting["field"] == "Title"


def test_clear_raises_when_text_survives_and_is_not_the_known_hint():
    field = _Field("Hello", "Hello")
    controller = ScreenController(_Device(field))
    controller._resting["field"] = "Title"
    try:
        controller.focus_and_clear(resource_id="field")
    except RuntimeError as exc:
        assert "not cleared" in str(exc)
    else:
        raise AssertionError("clear accepted leftover text")


class _Shell:
    def __init__(self, package: str) -> None:
        self.values = {
            ("secure", INVERSION_KEY): "0",
            ("global", HEADS_UP_KEY): "1",
            ("system", TIMEOUT_KEY): "120000",
            ("global", STAY_KEY): "0",
        }
        self.package = package
        self.slept: list[float] = []
        self.notes: list[tuple[str, str]] = []
        self.posted_tags: list[str] = []
        self.statusbar: list[str] = []
        self.snoozed = False
        self.snoozed_tags: list[str] = []
        self.scripts: list[str] = []
        self.stay_commands: list[str] = []
        self.lockscreen = False
        self.snoozed_lines: list[str] = []
        self.unsnoozed: list[str] = []

    def __call__(self, args):
        if args[:2] == ["settings", "get"]:
            return self.values[(args[2], args[3])] + "\n"
        if args[:2] == ["settings", "put"]:
            self.values[(args[2], args[3])] = args[4]
            return ""
        if args[:2] == ["settings", "delete"]:
            self.values[(args[2], args[3])] = "null"
            return ""
        if args[:2] == ["dumpsys", "activity"]:
            return f"topResumedActivity=ActivityRecord{{x u0 {self.package}/.Main t1}}\n"
        if args[:2] == ["dumpsys", "window"]:
            flag = "true" if self.lockscreen else "false"
            return (
                f"mDreamingLockscreen={flag}\n"
                f"isKeyguardShowing={flag}\n"
                f"mShowingLockscreen={flag}\n"
            )
        if args[:2] == ["cmd", "statusbar"]:
            self.statusbar.append(args[2])
            return ""
        if args[:3] == ["cmd", "notification", "post"]:
            self.notes.append((args[4], args[6]))
            self.posted_tags.append(args[5])
            return ""
        if args[:3] == ["cmd", "notification", "list"]:
            seen: set[str] = set()
            lines = []
            for tag in self.posted_tags:
                if tag in seen:
                    continue
                seen.add(tag)
                lines.append(f"0|com.android.shell|7|{tag}|2000")
            return ("\n".join(lines) + "\n") if lines else ""
        if args[:3] == ["cmd", "notification", "snooze"]:
            self.snoozed = True
            parts = args[-1].split("|")
            if len(parts) >= 4:
                self.snoozed_tags.append(parts[3].strip())
            return ""
        if args[:3] == ["cmd", "notification", "snoozed"]:
            return "".join(f"{line}\n" for line in self.snoozed_lines)
        if args[:3] == ["cmd", "notification", "unsnooze"]:
            self.unsnoozed.append(args[3])
            self.snoozed_lines = [line for line in self.snoozed_lines if args[3] not in line]
            return ""
        if args[:3] == ["svc", "power", "stayon"]:
            self.stay_commands.append(args[3])
            self.values[("global", STAY_KEY)] = "7" if args[3] == "true" else "0"
            return ""
        if args[:2] == ["input", "keyevent"] or args[:2] == ["wm", "dismiss-keyguard"]:
            return ""
        if args[:2] == ["sh", "-c"]:
            self.scripts.append(args[2])
            return ""
        raise AssertionError(args)


def _watcher_script(shell: _Shell) -> str:
    marker = "printf %s "
    for line in shell.scripts:
        if marker not in line or "base64 -d" not in line:
            continue
        payload = line.split(marker, 1)[1].split(" | base64 -d", 1)[0]
        return base64.b64decode(payload).decode()
    raise AssertionError("watcher script was not sent")


def test_gate_inverts_only_while_holding_and_restores_heads_up():
    shell = _Shell("bitpit.launcher")
    gate = ScreenGate("SERIAL", shell=shell, sleep=shell.slept.append, countdown_s=10)
    with gate:
        assert shell.slept == []
        gate.hold("Preparing the field")
        assert shell.values[("secure", INVERSION_KEY)] == "1"
        assert shell.notes[-1] == ("Hands off", "Hands off")
        assert shell.statusbar[-1] == "collapse"
        gate.yield_to_human("Type hello")
        assert shell.values[("secure", INVERSION_KEY)] == "0"
        assert shell.notes[-1] == ("Type hello", "Type hello")
        assert shell.statusbar[-2:] == ["expand-notifications", "collapse"]
        assert shell.slept == [4]
        assert shell.values[("system", TIMEOUT_KEY)] == AWAKE_MS
        assert shell.values[("global", STAY_KEY)] == "7"
        assert (STOP_TEXT, STOP_TEXT) in shell.notes
    assert shell.values[("secure", INVERSION_KEY)] == "0"
    assert shell.values[("global", HEADS_UP_KEY)] == "1"
    assert shell.values[("system", TIMEOUT_KEY)] == "120000"
    assert shell.values[("global", STAY_KEY)] == "0"
    assert shell.stay_commands == ["true", "false"]
    assert shell.snoozed
    assert STOP_TAG in shell.snoozed_tags
    script = _watcher_script(shell)
    assert "accessibility_display_inversion_enabled 0" in script
    assert "screen_off_timeout 120000" in script
    assert "svc power stayon false" in script
    assert "seen=0" in script
    assert 'if [ "$seen" -eq 0 ]; then' in script
    assert any("setsid" in line and AWAKE_SCRIPT in line for line in shell.scripts)


def test_gate_unsnoozes_a_hidden_stop_card_and_refuses_if_it_stays_hidden():
    shell = _Shell("bitpit.launcher")
    shell.snoozed_lines = [
        "0|com.android.shell|2020|plektra-qa-stop|2000 snoozed, time=1 context=null"
    ]
    gate = ScreenGate("SERIAL", shell=shell, sleep=shell.slept.append)
    with gate:
        assert shell.unsnoozed == ["0|com.android.shell|2020|plektra-qa-stop|2000"]
        assert shell.slept == []

    def hiding(args):
        if args[:3] == ["cmd", "notification", "list"]:
            return ""
        return shell(args)

    hidden = ScreenGate("SERIAL", shell=hiding, sleep=shell.slept.append)
    try:
        with hidden:
            raise AssertionError("entered without a stop card")
    except RuntimeError as exc:
        assert "stop card did not appear" in str(exc)
    assert shell.values[("system", TIMEOUT_KEY)] == "120000"
    assert shell.values[("secure", INVERSION_KEY)] == "0"


def test_gate_counts_down_when_another_app_is_in_front():
    shell = _Shell("com.android.chrome")
    gate = ScreenGate("SERIAL", shell=shell, sleep=shell.slept.append, countdown_s=10)
    with gate:
        assert shell.slept == [10]
        assert shell.values[("secure", INVERSION_KEY)] == "1"
    assert shell.values[("secure", INVERSION_KEY)] == "0"


def test_gate_restores_a_previous_stay_awake_mask():
    shell = _Shell("bitpit.launcher")
    shell.values[("global", STAY_KEY)] = "3"
    gate = ScreenGate("SERIAL", shell=shell, sleep=shell.slept.append, countdown_s=10)
    with gate:
        assert shell.values[("global", STAY_KEY)] == "7"
    assert shell.values[("global", STAY_KEY)] == "3"
    assert shell.stay_commands == ["true"]
    script = _watcher_script(shell)
    assert "stay_on_while_plugged_in 3" in script
    assert "svc power stayon false" not in script


def test_gate_refuses_a_lock_screen_and_restores_power_settings():
    shell = _Shell("bitpit.launcher")
    shell.lockscreen = True
    gate = ScreenGate("SERIAL", shell=shell, sleep=shell.slept.append)
    try:
        with gate:
            raise AssertionError("gate entered while the keyguard was showing")
    except RuntimeError as exc:
        assert "Unlock the phone once" in str(exc)
    assert shell.values[("system", TIMEOUT_KEY)] == "120000"
    assert shell.values[("global", STAY_KEY)] == "0"
    assert shell.values[("secure", INVERSION_KEY)] == "0"
    assert shell.values[("global", HEADS_UP_KEY)] == "1"
    assert shell.posted_tags == []


def test_gate_refuses_a_non_numeric_timeout_before_changing_settings():
    shell = _Shell("bitpit.launcher")
    shell.values[("system", TIMEOUT_KEY)] = "null"
    gate = ScreenGate("SERIAL", shell=shell, sleep=shell.slept.append)
    try:
        with gate:
            raise AssertionError("gate changed a non-numeric timeout")
    except RuntimeError as exc:
        assert "screen_off_timeout" in str(exc)
    assert shell.values[("system", TIMEOUT_KEY)] == "null"
    assert shell.values[("global", HEADS_UP_KEY)] == "1"
    assert shell.stay_commands == []
    assert shell.slept == []


def test_gate_waits_for_an_old_watcher_before_replacing_it():
    shell = _Shell("bitpit.launcher")

    def answering(args):
        if args[:2] == ["sh", "-c"] and "kill -0" in args[2]:
            shell.scripts.append(args[2])
            return "yes\n"
        if args[:2] == ["sh", "-c"] and f"cat {AWAKE_PID}" in args[2]:
            shell.scripts.append(args[2])
            return "4242\n"
        return shell(args)

    gate = ScreenGate("SERIAL", shell=answering, sleep=shell.slept.append)
    with gate:
        assert shell.slept == [1.2]
        assert any(AWAKE_RELEASE in line and line.startswith("touch ") for line in shell.scripts)


def test_gate_refuses_to_hold_when_inversion_does_not_stick():
    shell = _Shell("bitpit.launcher")
    shell.values[("secure", INVERSION_KEY)] = "0"

    def frozen(args):
        if args[:2] == ["settings", "put"] and args[3] == INVERSION_KEY:
            return ""
        return shell(args)

    gate = ScreenGate("SERIAL", shell=frozen, sleep=shell.slept.append)
    with gate:
        try:
            gate.hold("Preparing the field")
        except RuntimeError as exc:
            assert "inversion" in str(exc)
        else:
            raise AssertionError("hold proceeded without inversion")


class _ReadyField:
    def __init__(self) -> None:
        self.reads = 0

    def exists(self) -> bool:
        return True

    def info(self):
        return SimpleNamespace(focused=True)

    def click(self) -> None:
        return None

    def clear_text_field(self) -> None:
        return None

    def get_text(self) -> str:
        self.reads += 1
        return "" if self.reads <= 2 else "hello"


class _ReadyDevice:
    def __init__(self, field: _ReadyField) -> None:
        self.field = field

    def __call__(self, **_kwargs):
        return self.field


class _Gate:
    def __init__(self) -> None:
        self.events: list[object] = []

    def __enter__(self):
        self.events.append("enter")
        return self

    def __exit__(self, *_args):
        self.events.append("exit")
        return False

    def hold(self, reason: str) -> None:
        self.events.append(("hold", reason))

    def yield_to_human(self, prompt: str) -> None:
        self.events.append(("yield", prompt))


def test_batch_holds_the_screen_then_yields_for_typing():
    gate = _Gate()
    console = Console(file=io.StringIO(), force_terminal=False)
    console.bell = lambda: None
    trial = ChordTrial(prompt="alpha: hello", expect="hello", phase="one_screen")
    results = run_chord_batch(
        ScreenController(_ReadyDevice(_ReadyField())),
        [trial],
        console,
        gate,
    )
    assert results[0].passed
    assert gate.events == [
        "enter",
        ("hold", "Preparing the field"),
        ("yield", "alpha: hello"),
        "exit",
    ]


def test_batch_stops_when_the_phone_clears_testing_mode():
    gate = _Gate()
    gate.user_stopped = lambda: True
    console = Console(file=io.StringIO(), force_terminal=False)
    console.bell = lambda: None
    trials = [
        ChordTrial(prompt="alpha: hello", expect="hello", phase="one_screen"),
        ChordTrial(prompt="digits: 42", expect="42", phase="one_screen"),
    ]
    results = run_chord_batch(
        ScreenController(_ReadyDevice(_ReadyField())),
        trials,
        console,
        gate,
    )
    assert [result.error for result in results] == ["stopped from the phone"]
    assert results[0].passed is False
    assert gate.events == ["enter", "exit"]


def test_batch_continues_when_the_stop_check_fails():
    gate = _Gate()

    def boom() -> bool:
        raise RuntimeError("adb down")

    gate.user_stopped = boom
    console = Console(file=io.StringIO(), force_terminal=False)
    console.bell = lambda: None
    trial = ChordTrial(prompt="alpha: hello", expect="hello", phase="one_screen")
    results = run_chord_batch(
        ScreenController(_ReadyDevice(_ReadyField())),
        [trial],
        console,
        gate,
    )
    assert results[0].passed
    assert ("hold", "Preparing the field") in gate.events


def test_shell_line_keeps_a_spaced_action_as_one_word():
    line = shell_line(
        ["cmd", "notification", "post", "-t", "Type hello", "plektra-qa-presence", "Type hello"]
    )
    assert line == "cmd notification post -t 'Type hello' plektra-qa-presence 'Type hello'"
    stop = shell_line(
        ["cmd", "notification", "post", "-t", STOP_TEXT, STOP_TAG, STOP_TEXT]
    )
    assert stop == (
        "cmd notification post -t 'Swipe away to stop testing' "
        "plektra-qa-stop 'Swipe away to stop testing'"
    )


def test_package_never_types_through_firerpa_or_replaces_the_ime():
    banned = ("set_text(", "default_input_method", "force-stop", "am start -S")
    for path in PACKAGE.glob("*.py"):
        text = path.read_text()
        for token in banned:
            assert token not in text, f"{path.name} contains {token}"
