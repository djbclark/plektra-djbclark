import signal
import subprocess
from pathlib import Path
from types import SimpleNamespace

import pytest

from plektra_qa import recording as rec
from plektra_qa.recording import Recorder, RecordingError, SpeechLine, build_track, recording
from plektra_qa.speech import Speaker

RATE = rec.SAMPLE_RATE
BPS = rec.BYTES_PER_SAMPLE


def pcm(seconds: float, value: int = 1) -> bytes:
    return (value.to_bytes(2, "little", signed=True)) * int(seconds * RATE)


def test_track_places_lines_at_their_start_time_and_trims_cut_ones():
    lines = [
        SpeechLine(start=11.0, text="a", kind="wav"),
        SpeechLine(start=12.0, text="b", kind="wav", cut=12.25),
    ]
    track = build_track(lines, 10.0, lambda line: pcm(0.5, 7 if line.text == "a" else 9))
    seconds = lambda s: int(s * RATE) * BPS
    assert track[: seconds(1.0)] == bytes(seconds(1.0))  # silence before the first line
    assert track[seconds(1.0) : seconds(1.5)] == pcm(0.5, 7)
    assert track[seconds(1.5) : seconds(2.0)] == bytes(seconds(0.5))
    assert track[seconds(2.0) : seconds(2.25)] == pcm(0.25, 9)  # cut at 0.25 s
    assert len(track) == seconds(2.25)


def test_track_skips_lines_without_audio():
    lines = [SpeechLine(start=1.0, text="x", kind="espeak")]
    assert build_track(lines, 0.0, lambda _line: b"") == b""


class _Proc:
    def __init__(self, raw: Path, writes: bool = True) -> None:
        if writes:
            raw.write_bytes(b"video")
            raw.with_name(raw.name.replace(".raw.mkv", ".scrcpy.log")).write_text(
                "INFO: Recording started to matroska file\n"
            )
        self.signals: list[int] = []
        self.done = False

    def poll(self):
        return 0 if self.done else None

    def send_signal(self, sig: int) -> None:
        self.signals.append(sig)
        self.done = True

    def wait(self, timeout=None) -> int:
        return 0


def _fake_run(calls: list[list[str]]):
    def run(cmd, **_kwargs):
        calls.append(list(cmd))
        out = b""
        if cmd[-1] == "-":
            out = pcm(0.1)
        elif cmd[0] == "ffprobe":
            out = b"1080,1200\n"
        return subprocess.CompletedProcess(cmd, 0, stdout=out, stderr=b"")

    return run


@pytest.fixture(autouse=True)
def _encode_is_faked(monkeypatch):
    """The real encode streams a band into ffmpeg; tests record what it was asked."""
    seen: list[dict] = []

    def encode(self, cmd, frames):
        seen.append({"cmd": list(cmd), "frames": sum(1 for _ in frames)})
        Path(cmd[-1]).write_bytes(b"mp4")

    monkeypatch.setattr(Recorder, "_encode", encode)
    return seen


def _recorder(tmp_path, calls, which=lambda name: f"/usr/bin/{name}", writes=True):
    holder: dict[str, _Proc] = {}

    def popen(cmd, **_kwargs):
        raw = Path(cmd[cmd.index("--record") + 1])
        holder["proc"] = _Proc(raw, writes)
        return holder["proc"]

    recorder = Recorder(
        "10.0.0.1:5555", tmp_path, popen=popen, run=_fake_run(calls), which=which, stamp="t",
        capture_input=False,
    )
    return recorder, holder


def test_recording_is_view_only_video_with_the_voice_laid_on_it(tmp_path, _encode_is_faked):
    calls: list[list[str]] = []
    recorder, holder = _recorder(tmp_path, calls)
    recorder.start()
    wav = tmp_path / "line.wav"
    wav.write_bytes(b"w")
    recorder.on_speech("say", time=recorder._t0 + 1.0, text="Type hello", kind="wav", path=wav)
    out = recorder.finish()
    assert out == tmp_path / "plektra-qa-t.mp4" and out.is_file()
    assert holder["proc"].signals == [signal.SIGINT]
    assert not (tmp_path / "plektra-qa-t.raw.mkv").exists()
    assert recorder.warnings == []
    cmd = _encode_is_faked[0]["cmd"]
    assert "-c:a" in cmd and "-c:v" in cmd and "-filter_complex" in cmd
    assert "vstack" in cmd[cmd.index("-filter_complex") + 1]


def test_scrcpy_is_launched_view_only_and_without_phone_audio(tmp_path):
    seen: list[list[str]] = []

    def popen(cmd, **_kwargs):
        seen.append(list(cmd))
        return _Proc(Path(cmd[cmd.index("--record") + 1]))

    Recorder("S", tmp_path, popen=popen, which=lambda n: n, stamp="t").start()
    assert "--no-control" in seen[0] and "--no-audio" in seen[0] and "--no-window" in seen[0]
    assert seen[0][seen[0].index("-s") + 1] == "S"


def test_without_a_voice_line_the_mp4_has_no_audio_track(tmp_path, _encode_is_faked):
    calls: list[list[str]] = []
    recorder, _ = _recorder(tmp_path, calls)
    recorder.start()
    assert recorder.finish() is not None
    assert "-c:a" not in _encode_is_faked[0]["cmd"]


def test_trial_marks_become_chapters_in_the_mp4_and_a_youtube_file(tmp_path, _encode_is_faked):
    clock = iter([100.0, 100.0, 100.0, 112.0, 130.0, 150.0, 150.0, 150.0, 150.0, 150.0])
    calls: list[list[str]] = []
    recorder, _ = _recorder(tmp_path, calls)
    recorder._clock = lambda: next(clock)
    recorder.start()  # t0 = 100
    recorder.on_trial(1, SimpleNamespace(phase="alpha", prompt="Type hello"))  # 112 -> 12 s
    recorder.on_trial(2, SimpleNamespace(phase="", prompt="Ctrl+A"))  # 130 -> 30 s
    recorder.finish()  # length 50 s
    cmd = _encode_is_faked[0]["cmd"]
    assert "-map_chapters" in cmd
    assert (tmp_path / "plektra-qa-t.chapters.txt").read_text() == (
        "0:00 Setup\n0:12 1. alpha: Type hello\n0:30 2. Ctrl+A\n"
    )


def test_missing_tools_refuse_before_the_run(tmp_path):
    recorder, _ = _recorder(tmp_path, [], which=lambda name: None)
    with pytest.raises(RecordingError, match="scrcpy, ffmpeg"):
        recorder.start()


def test_scrcpy_that_writes_no_video_refuses(tmp_path):
    class Dead(_Proc):
        def poll(self):
            return 1

    recorder = Recorder(
        "S", tmp_path, popen=lambda *_a, **_k: Dead(tmp_path / "x", writes=False),
        which=lambda n: n, stamp="t",
    )
    with pytest.raises(RecordingError, match="scrcpy stopped"):
        recorder.start()


def test_say_lines_are_rendered_with_say_for_the_track(tmp_path):
    calls: list[list[str]] = []
    recorder, _ = _recorder(tmp_path, calls)
    recorder.start()
    recorder.on_speech("say", time=recorder._t0 + 0.5, text="Hello", kind="say")
    recorder.finish()
    assert any(c[0] == "say" and c[1] == "-o" and c[-1] == "Hello" for c in calls)


def test_recording_context_is_a_no_op_when_off():
    with recording("S", SimpleNamespace(), False) as recorder:
        assert recorder is None


def test_recording_context_wires_and_unwires_the_speaker_and_waits_for_it(tmp_path, monkeypatch):
    calls: list[list[str]] = []
    made: dict[str, Recorder] = {}
    real = rec.Recorder

    def build(serial, out_dir=None, **kw):
        recorder, _ = _recorder(tmp_path, calls)
        made["r"] = recorder
        return recorder

    monkeypatch.setattr(rec, "Recorder", build)
    waits: list[float] = []
    speaker = SimpleNamespace(listener=None, wait_idle=waits.append)
    with recording("S", speaker, True, tmp_path) as recorder:
        assert speaker.listener == recorder.on_speech
    assert speaker.listener is None and waits == [rec.IDLE_TIMEOUT_S]
    assert recorder.output.is_file()
    assert real is not build


def test_speaker_reports_lines_and_cuts_to_its_listener(tmp_path):
    class Proc:
        def __init__(self):
            self.dead = False

        def poll(self):
            return 0 if self.dead else None

        def terminate(self):
            self.dead = True

        def wait(self, timeout=None):
            return 0

        def kill(self):
            self.dead = True

    events: list[tuple] = []
    speaker = Speaker("say", which=lambda n: n, popen=lambda *a, **k: Proc(), cache=tmp_path)
    speaker.listener = lambda event, **info: events.append((event, info))
    speaker.say("one")
    speaker.say("two")  # cuts "one"
    assert [e[0] for e in events] == ["say", "cut", "say"]
    assert events[0][1]["text"] == "one" and events[0][1]["kind"] == "say"
    assert events[1][1]["time"] >= events[0][1]["time"]


def test_overlay_band_is_one_frame_per_thirtieth_second_and_cached():
    from plektra_qa.inputlog import Shown
    from plektra_qa.overlay import BAND_H, band_frames, timecode

    frames = list(band_frames(1.0, 200, [Shown(10.2, 10.6, "L-Shift + A")], [], t0=10.0))
    assert len(frames) == 31
    assert all(len(f) == 200 * BAND_H * 3 for f in frames)
    assert frames[0] != frames[10]  # the keys text appears at 0.2 s
    assert frames[1] == frames[1] and timecode(75.34) == "00:01:15.3"


def test_youtube_chapters_merge_anything_under_ten_seconds():
    from plektra_qa.overlay import chapters, ffmetadata, youtube_chapters, youtube_text

    items = chapters([(4.0, "a"), (20.0, "b"), (26.0, "c"), (45.0, "d")], 50.0)
    merged = youtube_chapters(items, 50.0)
    assert [t for t, _ in merged] == [0.0, 20.0]  # 26 s too close; 45 s too near the end
    assert [title for _, title in merged] == ["a", "b"]  # the 4 s chapter takes over 0:00
    assert youtube_text(merged).splitlines()[0] == "0:00 a"
    assert all(b - a >= 10 for (a, _), (b, _) in zip(merged, merged[1:]))
    meta = ffmetadata(items, 50.0)
    assert meta.startswith(";FFMETADATA1") and meta.count("[CHAPTER]") == 5 and "title=d" in meta


def test_the_voice_track_is_rendered_at_the_speed_it_was_spoken(tmp_path):
    runs: list[list[str]] = []

    def run(cmd, **_kwargs):
        runs.append(cmd)
        return SimpleNamespace(stdout=b"")

    recorder = Recorder("serial", tmp_path, run=run, which=lambda name: name, capture_input=False)
    recorder.on_speech("say", time=1.0, text="Hi", kind="say", rate=1.5)
    recorder._pcm(recorder._lines[0], tmp_path)
    assert runs[0][:3] == ["say", "-r", "282"]
