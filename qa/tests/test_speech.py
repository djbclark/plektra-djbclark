import io
from pathlib import Path
from types import SimpleNamespace

from rich.console import Console

from plektra_qa.controller import ScreenController
from plektra_qa.end_state import END_DONE_TEXT
from plektra_qa.hitl_runner import (
    HANDS_OFF,
    NOT_STARTED,
    REPROMPT,
    ChordTrial,
    load_suite,
    run_chord_batch,
)
from plektra_qa.speech import Speaker, pick_backend

ROOT = Path(__file__).resolve().parents[1]


class _FakeSpeaker:
    enabled = True

    def __init__(self) -> None:
        self.said: list[str] = []
        self.prepared: list[str] = []
        self.stops = 0

    def prepare(self, texts) -> None:
        self.prepared = list(texts)

    def say(self, text: str, rate: float = 1.0) -> None:
        self.said.append(text)

    def speaking(self) -> bool:
        return False

    def stop(self) -> None:
        self.stops += 1


class _Field:
    def __init__(self, typed_after: int | None) -> None:
        self.reads = 0
        self.typed_after = typed_after

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
        if self.typed_after is None or self.reads <= self.typed_after:
            return ""
        return "hello"


class _Device:
    def __init__(self, field: _Field) -> None:
        self.field = field

    def __call__(self, **_kwargs):
        return self.field


class _Gate:
    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False

    def hold(self, _reason: str) -> None:
        return None

    def yield_to_human(self, _prompt: str) -> None:
        return None


def _console() -> Console:
    console = Console(file=io.StringIO(), force_terminal=False)
    console.bell = lambda: None
    return console


def test_voice_reprompts_after_a_quiet_spell_then_stops_the_batch():
    speaker = _FakeSpeaker()
    trials = [
        ChordTrial(
            prompt="alpha: hello",
            shade="Type hello",
            expect="hello",
            start_wait_s=0.05,
            max_reprompts=2,
        ),
        ChordTrial(prompt="digits: 42", expect="42"),
    ]
    results = run_chord_batch(
        ScreenController(_Device(_Field(typed_after=None))),
        trials,
        _console(),
        _Gate(),
        speaker,
    )
    assert [r.error for r in results] == [NOT_STARTED]
    assert speaker.said == [
        HANDS_OFF,
        "Type hello",
        REPROMPT + "Type hello",
        REPROMPT + "Type hello",
        END_DONE_TEXT,
    ]
    assert "Type hello" in speaker.prepared
    assert REPROMPT + "digits: 42" in speaker.prepared


def test_typing_ends_the_reprompts_and_the_trial_passes():
    speaker = _FakeSpeaker()
    trial = ChordTrial(prompt="alpha: hello", expect="hello", speak="Type hello now")
    results = run_chord_batch(
        ScreenController(_Device(_Field(typed_after=6))),  # a few reads go to open/baseline
        [trial],
        _console(),
        _Gate(),
        speaker,
    )
    assert results[0].passed
    assert speaker.said == [HANDS_OFF, "Type hello now", END_DONE_TEXT]


def test_without_a_voice_the_old_start_window_still_applies():
    trial = ChordTrial(prompt="alpha: hello", expect="hello", timeout_s=0.1)
    results = run_chord_batch(
        ScreenController(_Device(_Field(typed_after=None))),
        [trial],
        _console(),
        _Gate(),
    )
    assert results[0].passed is False
    assert results[0].error == ""


def test_suite_carries_voice_timing_and_spoken_lines():
    trials = load_suite(ROOT / "suites" / "smoke_chords.yaml")
    assert all(t.start_wait_s == 10 and t.max_reprompts is None and t.timeout_s is None for t in trials)
    spoken = {t.speak for t in trials if t.speak}
    assert "Type: a, comma, space, b, period." in spoken


def _which(*names: str):
    return lambda name: f"/bin/{name}" if name in names else None


def test_backend_prefers_kokoro_then_say_then_espeak():
    assert pick_backend("auto", _which("kokoro", "afplay", "say")) == "kokoro"
    assert pick_backend("auto", _which("kokoro", "say")) == "say"  # no player
    assert pick_backend("auto", _which("espeak-ng")) == "espeak"
    assert pick_backend("auto", _which()) == "off"
    assert pick_backend("off", _which("kokoro", "afplay")) == "off"


def test_kokoro_renders_each_phrase_once_and_plays_the_cache(tmp_path):
    runs: list[list[str]] = []
    plays: list[list[str]] = []

    def run(cmd, **_kwargs):
        runs.append(cmd)
        Path(cmd[cmd.index("-o") + 1]).write_bytes(b"RIFF")
        return SimpleNamespace(returncode=0)

    class _Proc:
        def poll(self):
            return 0

    def popen(cmd, **_kwargs):
        plays.append(cmd)
        return _Proc()

    speaker = Speaker(
        "kokoro", which=_which("kokoro", "afplay"), popen=popen, run=run, cache=tmp_path
    )
    speaker.say("Type hello")
    speaker.say("Type hello")
    assert len(runs) == 1
    assert runs[0][:3] == ["kokoro", "speak", "Type hello"]
    assert [p[0] for p in plays] == ["afplay", "afplay"]
    assert plays[0][1] == plays[1][1] and plays[0][1].endswith(".wav")
    assert not speaker.speaking()


def test_espeak_uses_the_first_linux_voice_found():
    plays: list[list[str]] = []

    def popen(cmd, **_kwargs):
        plays.append(cmd)
        return SimpleNamespace(poll=lambda: 0)

    Speaker("espeak", which=_which("espeak-ng"), popen=popen).say("Type 42")
    assert plays == [["espeak-ng", "Type 42"]]


def test_a_faster_line_is_asked_of_each_system_voice():
    plays: list[list[str]] = []

    def popen(cmd, **_kwargs):
        plays.append(cmd)
        return SimpleNamespace(poll=lambda: 0)

    for backend, tool in (("say", "say"), ("espeak", "espeak-ng"), ("espeak", "spd-say")):
        Speaker(backend, which=_which(tool), popen=popen).say("Hi", rate=1.5)
    assert plays == [
        ["say", "-r", "282", "Hi"], ["espeak-ng", "-s", "262", "Hi"], ["spd-say", "-r", "50", "-w", "Hi"],
    ]


def test_kokoro_renders_a_faster_line_apart_from_the_normal_one(tmp_path):
    runs: list[list[str]] = []
    plays: list[list[str]] = []

    def run(cmd, **_kwargs):
        runs.append(cmd)
        Path(cmd[cmd.index("-o") + 1]).write_bytes(b"RIFF")
        return SimpleNamespace(returncode=0)

    def popen(cmd, **_kwargs):
        plays.append(cmd)
        return SimpleNamespace(poll=lambda: 0)

    speaker = Speaker("kokoro", which=_which("kokoro", "afplay"), popen=popen, run=run, cache=tmp_path)
    speaker.say("Hi")
    speaker.say("Hi", rate=1.5)
    assert "--speed" not in runs[0] and runs[1][-2:] == ["--speed", "1.5"]
    assert plays[0][1] != plays[1][1]
