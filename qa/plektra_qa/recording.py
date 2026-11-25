"""Screen and voice recording of a guided run, for sharing with the group.

On by default: `plektra-qa run SUITE`, and the MCP tool with `record=true`. It is
turned off with `--no-record` or `record=false`, or by the person on the start
card (No rec on the bar, norec in the terminal). An agent tells the person before
the batch and offers to turn it off (README section 8): the video shows the whole
screen, which can include notifications and other private content, and every key.

How it works, with no audio loopback or extra permission:

- The screen is recorded with `scrcpy --no-control --no-audio --no-window` over adb,
  so it is a view-only copy and cannot touch the phone.
- The computer's own voice is not captured from the speakers. The Speaker tells
  this module each line it says (when it started, what it said, where its audio
  is) and when it cut a line off. At the end the lines are laid on a silent
  track at those times and muxed into the video with ffmpeg.
- Over the video goes a band: running timecode on the left, the keys held and
  the touches (tap, long press, swipe, coordinates) on the right, read from
  the phone's `getevent` (inputlog.py, overlay.py). scrcpy `--show-touches`
  also draws a dot where a finger is (the phone's show_touches setting, put
  back afterwards; scrcpy's own option needs control, which this does not use). A chapter starts at each trial, for
  YouTube sections: they are in the mp4 and in `plektra-qa-*.chapters.txt`.
- Result: one `plektra-qa-YYYYmmdd-HHMMSS.mp4` in the record directory
  (`--record-dir`, else `PLEKTRA_QA_RECORD_DIR`, else `~/plektra-qa-recordings`).

Sync is within about a tenth of a second: the clock starts when scrcpy writes
scrcpy reports "Recording started". Lines spoken by `spd-say` have no audio file and are left out.
"""

from __future__ import annotations

import os
import pty
import shutil
import signal
import subprocess
import tempfile
import threading
import time
from collections.abc import Callable, Iterator
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any

from plektra_qa.inputlog import InputLogger, Shown
from plektra_qa.overlay import (
    BAND_H,
    FPS,
    PANEL_W,
    band_frames,
    chapters,
    ffmetadata,
    srt,
    write_chapter_files,
    write_panel,
)

SAMPLE_RATE = 24000
BYTES_PER_SAMPLE = 2  # s16le mono
START_TIMEOUT_S = 20.0
STOP_TIMEOUT_S = 15.0
IDLE_TIMEOUT_S = 20.0

Which = Callable[[str], str | None]


class RecordingError(RuntimeError):
    pass


def default_dir() -> Path:
    env = os.environ.get("PLEKTRA_QA_RECORD_DIR", "").strip()
    return Path(env).expanduser() if env else Path.home() / "plektra-qa-recordings"


@dataclass
class SpeechLine:
    start: float
    text: str
    kind: str  # "wav" | "say" | "espeak"
    path: Path | None = None
    tool: str = ""
    cut: float | None = None


def build_track(
    lines: list[SpeechLine],
    t0: float,
    to_pcm: Callable[[SpeechLine], bytes],
) -> bytes:
    """Lay each spoken line on a silent s16le track at its start time.

    A line that was cut off is trimmed to the time it was heard. A later line
    wins where two overlap (the Speaker never plays two at once).
    """
    track = bytearray()
    for line in sorted(lines, key=lambda item: item.start):
        pcm = to_pcm(line)
        if not pcm:
            continue
        if line.cut is not None:
            heard = max(0.0, line.cut - line.start)
            pcm = pcm[: int(heard * SAMPLE_RATE) * BYTES_PER_SAMPLE]
        offset = max(0, int((line.start - t0) * SAMPLE_RATE)) * BYTES_PER_SAMPLE
        end = offset + len(pcm)
        if len(track) < end:
            track.extend(bytes(end - len(track)))
        track[offset:end] = pcm
    return bytes(track)


class Recorder:
    def __init__(
        self,
        serial: str,
        out_dir: Path | None = None,
        *,
        popen: Callable[..., Any] = subprocess.Popen,
        run: Callable[..., Any] = subprocess.run,
        clock: Callable[[], float] = time.monotonic,
        which: Which = shutil.which,
        stamp: str | None = None,
        capture_input: bool = True,
        panel: bool = True,
    ) -> None:
        self.serial = serial
        self.out_dir = out_dir or default_dir()
        self._popen = popen
        self._run = run
        self._clock = clock
        self._which = which
        name = f"plektra-qa-{stamp or datetime.now().strftime('%Y%m%d-%H%M%S')}"
        self.output = self.out_dir / f"{name}.mp4"
        self._raw = self.out_dir / f"{name}.raw.mkv"
        self._log = self.out_dir / f"{name}.scrcpy.log"
        self._ffmpeg_log = self.out_dir / f"{name}.ffmpeg.log"
        self.chapters_file: Path | None = None
        # (trial, start s, end s, passed, result) in video time; a trial with no result is not here.
        self.spans: list[tuple[Any, float, float, bool, Any]] = []
        self._open_trial: tuple[Any, float] | None = None
        self._marks: list[tuple[float, str]] = []
        self._notes: list[tuple[float, str, str]] = []  # (clock, kind, text) for the log panel
        self._panel = panel
        self._touches_was: str | None = None  # show_touches before we turned it on
        self._input: InputLogger | None = (
            InputLogger(serial, clock=clock) if capture_input else None
        )
        self._proc: Any = None
        self._t0: float = 0.0
        self._lines: list[SpeechLine] = []
        self.warnings: list[str] = []

    # -- start and stop -----------------------------------------------------

    def start(self) -> None:
        missing = [tool for tool in ("scrcpy", "ffmpeg") if not self._which(tool)]
        if missing:
            raise RecordingError(
                f"recording needs {', '.join(missing)} on PATH (macOS: brew install scrcpy ffmpeg)"
            )
        self.out_dir.mkdir(parents=True, exist_ok=True)
        cmd = [
            "scrcpy", "-s", self.serial, "--no-control", "--no-audio", "--no-window",
            "--record", str(self._raw), "--record-format", "mkv",
        ]
        # scrcpy block-buffers its log into a file or pipe, so give it a
        # terminal: its lines then arrive as they happen.
        master, slave = pty.openpty()
        try:
            self._proc = self._popen(cmd, stdout=slave, stderr=slave)
        except OSError as exc:
            os.close(master)
            raise RecordingError(f"scrcpy did not start: {exc}") from exc
        finally:
            os.close(slave)
        threading.Thread(target=self._pump, args=(master,), name="scrcpy-log", daemon=True).start()
        deadline = self._clock() + START_TIMEOUT_S
        while self._clock() < deadline:
            # "Recording started" comes once the phone's stream is connected,
            # so the first frame follows at once. The file exists earlier and
            # stays empty on a still screen, so it is no signal.
            if "Recording started" in self._log_text():
                self._t0 = self._clock()
                self._start_input()
                return
            if self._proc.poll() is not None:
                raise RecordingError(f"scrcpy stopped at start: {self._tail()}")
            time.sleep(0.02)
        self._terminate()
        raise RecordingError(f"scrcpy did not start recording within {START_TIMEOUT_S:.0f} s: {self._tail()}")

    def _pump(self, master: int) -> None:
        with self._log.open("ab") as log:
            while True:
                try:
                    data = os.read(master, 4096)
                except OSError:
                    break
                if not data:
                    break
                log.write(data)
                log.flush()
        os.close(master)

    def _adb(self, *args: str) -> str:
        done = self._run(["adb", "-s", self.serial, "shell", *args], capture_output=True, timeout=15)
        return bytes(done.stdout).decode("utf-8", "replace").strip()

    def _show_touches(self, on: bool) -> None:
        """Android's own touch dots, which end up in the video. scrcpy's
        --show-touches needs control, and this recording is view-only."""
        try:
            if on:
                before = self._adb("settings", "get", "system", "show_touches")
                if before in ("0", "1"):
                    self._touches_was = before
                    self._adb("settings", "put", "system", "show_touches", "1")
            elif self._touches_was is not None:
                self._adb("settings", "put", "system", "show_touches", self._touches_was)
                self._touches_was = None
        except (OSError, subprocess.SubprocessError) as exc:
            self.warnings.append(f"show_touches could not be changed: {exc}")

    def _start_input(self) -> None:
        self._show_touches(True)
        if self._input is None:
            return
        try:
            self._input.start()
        except (OSError, subprocess.SubprocessError) as exc:
            self.warnings.append(f"keys and touches are not on the overlay: {exc}")
            self._input = None

    def _log_text(self) -> str:
        try:
            return self._log.read_text(errors="replace")
        except OSError:
            return ""

    def _tail(self) -> str:
        return " | ".join(self._log_text().strip().splitlines()[-3:]) or "no log"

    def _terminate(self) -> None:
        proc, self._proc = self._proc, None
        if proc is None or proc.poll() is not None:
            return
        proc.send_signal(signal.SIGINT)  # scrcpy closes the recording on SIGINT
        try:
            proc.wait(timeout=STOP_TIMEOUT_S)
        except subprocess.TimeoutExpired:
            proc.terminate()
            try:
                proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                proc.kill()

    # -- speech events (the Speaker's listener) ------------------------------

    def on_speech(self, event: str, **info: Any) -> None:
        if event == "say":
            self.note("voice", f"voice: {info['text']}", at=info["time"])
            self._lines.append(
                SpeechLine(
                    start=info["time"], text=info["text"], kind=info["kind"],
                    path=info.get("path"), tool=info.get("tool", ""),
                )
            )
        elif event == "cut" and self._lines and self._lines[-1].cut is None:
            self._lines[-1].cut = info["time"]

    # -- the finished file ---------------------------------------------------

    def _pcm(self, line: SpeechLine, work: Path) -> bytes:
        source = line.path
        if line.kind == "say" and self._which("say"):
            source = work / f"say-{id(line)}.aiff"
            self._run(["say", "-o", str(source), line.text], check=True, capture_output=True, timeout=60)
        elif line.kind == "espeak":
            tool = next((t for t in ("espeak-ng", "espeak") if self._which(t)), None)
            if tool is None:
                self.warnings.append(f"no audio file for {line.text!r} (spd-say cannot write one)")
                return b""
            source = work / f"espeak-{id(line)}.wav"
            self._run([tool, "-w", str(source), line.text], check=True, capture_output=True, timeout=60)
        if source is None or not Path(source).is_file():
            self.warnings.append(f"audio for {line.text!r} was not found")
            return b""
        done = self._run(
            ["ffmpeg", "-v", "error", "-i", str(source), "-ar", str(SAMPLE_RATE), "-ac", "1", "-f", "s16le", "-"],
            check=True, capture_output=True, timeout=60,
        )
        return bytes(done.stdout)

    def mark(self, title: str) -> None:
        """A chapter starts now (the next batch of key presses)."""
        self._marks.append((self._clock(), title))

    def note(self, kind: str, text: str, at: float | None = None) -> None:
        """A line for the log panel and the subtitle track, stamped now (or at `at`)."""
        self._notes.append((self._clock() if at is None else at, kind, text))

    def on_trial(self, index: int, trial: Any) -> None:
        phase = f"{trial.phase}: " if getattr(trial, "phase", "") else ""
        self.mark(f"{index}. {phase}{trial.prompt}")
        self.note("trial", f"trial {index}: {phase}{trial.prompt}", at=self._marks[-1][0])
        self._open_trial = (trial, self._marks[-1][0] - self._t0)

    def on_result(self, index: int, result: Any) -> None:
        now = self._clock()
        if self._open_trial is not None:
            trial, began = self._open_trial
            self._open_trial = None
            self.spans.append((trial, began, now - self._t0, bool(result.passed), result))
        if result.passed:
            self.note("pass", f"trial {index} pass: {result.observed!r}", at=now)
        else:
            detail = result.error or result.diff
            self.note("fail", f"trial {index} FAIL: expected {result.expect!r}, saw {result.observed!r} {detail}".strip(), at=now)

    def _size(self) -> tuple[int, int]:
        try:
            done = self._run(
                ["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
                 "stream=width,height", "-of", "csv=p=0", str(self._raw)],
                check=True, capture_output=True, timeout=30,
            )
            w, h = bytes(done.stdout).decode().strip().split(",")[:2]
            return int(w), int(h)
        except (OSError, ValueError, subprocess.SubprocessError):
            return 1080, 1200

    def _encode(self, cmd: list[str], frames: Iterator[bytes]) -> None:
        """Run ffmpeg with the overlay band streamed to its stdin."""
        with self._ffmpeg_log.open("wb") as err:
            proc = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=err)
            try:
                assert proc.stdin is not None
                for frame in frames:
                    proc.stdin.write(frame)
            except BrokenPipeError:
                pass  # ffmpeg stopped early; its exit status says why
            finally:
                if proc.stdin is not None:
                    proc.stdin.close()
            code = proc.wait(timeout=1800)
        if code:
            raise subprocess.CalledProcessError(code, cmd, stderr=self._ffmpeg_log.read_bytes()[-400:])

    def _timed_notes(self, keys: list[Shown], touches: list[Shown]) -> list[tuple[float, str, str]]:
        """Everything for the log panel, in time order, seconds from the start."""
        merged = list(self._notes)
        merged += [(item.start, "key", f"keys: {item.text}") for item in keys if item.text]
        merged += [(item.start, "touch", f"touch: {item.text}") for item in touches if item.text]
        merged.sort(key=lambda note: note[0])
        return [(max(t - self._t0, 0.0), kind, text) for t, kind, text in merged]

    def finish(self) -> Path | None:
        """Stop everything and write the mp4. None, with warnings, if it could not."""
        length = self._clock() - self._t0
        self._show_touches(False)
        keys: list[Shown] = []
        touches: list[Shown] = []
        if self._input is not None:
            keys, touches = self._input.stop()
        self._terminate()
        if not self._raw.is_file() or self._raw.stat().st_size == 0:
            self.warnings.append(f"no video was recorded ({self._tail()})")
            return None
        with tempfile.TemporaryDirectory(prefix="plektra-qa-rec-") as tmp:
            work = Path(tmp)
            try:
                track = build_track(self._lines, self._t0, lambda line: self._pcm(line, work))
            except (OSError, subprocess.SubprocessError) as exc:
                self.warnings.append(f"voice track failed, video only: {exc}")
                track = b""
            length = max(length, len(track) / (SAMPLE_RATE * BYTES_PER_SAMPLE))
            width, height = self._size()
            marks = [(t - self._t0, title) for t, title in self._marks]
            notes = self._timed_notes(keys, touches)
            items = chapters(marks, length)
            meta = work / "chapters.ffmeta"
            meta.write_text(ffmetadata(items, length))
            cmd = ["ffmpeg", "-v", "error", "-y", "-i", str(self._raw),
                   "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{width}x{BAND_H}",
                   "-framerate", str(FPS), "-i", "pipe:0"]
            audio = ""
            if track:
                pcm = work / "voice.pcm"
                pcm.write_bytes(track)
                cmd += ["-f", "s16le", "-ar", str(SAMPLE_RATE), "-ac", "1", "-i", str(pcm)]
                audio = "2"
            meta_index = 3 if track else 2
            cmd += ["-i", str(meta)]
            panel_index = srt_index = -1
            if self._panel and notes:
                listing = write_panel(notes, length, PANEL_W, height + BAND_H, work)
                panel_index = meta_index + 1
                cmd += ["-f", "concat", "-safe", "0", "-i", str(listing)]
                subs = work / "notes.srt"
                subs.write_text(srt(notes, length))
                srt_index = meta_index + 2
                cmd += ["-i", str(subs)]
            # A rotation changes scrcpy's frame size mid-stream, so every frame is
            # fitted to the first one's size. A still screen sends few frames, so the raw video can end seconds
            # before the run does: steady 30 fps, last frame held to the run's
            # length. The band (timecode, keys, touches) goes on top, so the
            # phone's own picture is never covered.
            graph = (
                f"[0:v]fps={FPS},scale={width}:{height}:force_original_aspect_ratio=decrease,"
                f"pad={width}:{height}:(ow-iw)/2:(oh-ih)/2,tpad=stop_mode=clone:stop_duration=3600,format=rgb24[v];"
                "[1:v][v]vstack=inputs=2:shortest=1[stk];"
            )
            if panel_index > 0:
                graph += (
                    f"[{panel_index}:v]fps={FPS},tpad=stop_mode=clone:stop_duration=3600,format=rgb24[pan];"
                    "[stk][pan]hstack=inputs=2:shortest=1,pad=ceil(iw/2)*2:ceil(ih/2)*2,format=yuv420p[out]"
                )
            else:
                graph += "[stk]pad=ceil(iw/2)*2:ceil(ih/2)*2,format=yuv420p[out]"
            cmd += ["-filter_complex", graph, "-map", "[out]"]
            if audio:
                cmd += ["-map", f"{audio}:a", "-c:a", "aac", "-af", "apad"]
            if srt_index > 0:
                cmd += ["-map", f"{srt_index}:s", "-c:s", "mov_text", "-metadata:s:s:0", "title=Terminal log"]
            cmd += [
                "-map_metadata", str(meta_index), "-map_chapters", str(meta_index),
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "23",
                "-t", f"{length:.2f}", "-movflags", "+faststart", str(self.output),
            ]
            try:
                self._encode(cmd, band_frames(length, width, keys, touches, self._t0))
            except (OSError, subprocess.SubprocessError) as exc:
                self.warnings.append(f"ffmpeg could not write {self.output.name}; the raw video is kept at {self._raw}: {exc}")
                return None
            self.chapters_file = write_chapter_files(items, length, self.output)
        self._raw.unlink(missing_ok=True)
        self._log.unlink(missing_ok=True)
        self._ffmpeg_log.unlink(missing_ok=True)
        return self.output


@contextmanager
def recording(
    serial: str,
    speaker: Any,
    enabled: bool,
    out_dir: Path | None = None,
    **kwargs: Any,
) -> Iterator[Recorder | None]:
    """Record around a run when `enabled`; otherwise do nothing. Never prints.

    Raises RecordingError before the run if recording cannot start. After the
    run it waits for the last spoken line to finish, then writes the file;
    read `.output` and `.warnings` on the yielded Recorder afterwards.
    """
    if not enabled:
        yield None
        return
    recorder = Recorder(serial, out_dir, **kwargs)
    recorder.start()
    if hasattr(speaker, "listener"):
        speaker.listener = recorder.on_speech
    try:
        yield recorder
    finally:
        wait = getattr(speaker, "wait_idle", None)
        if callable(wait):
            wait(IDLE_TIMEOUT_S)
        if hasattr(speaker, "listener"):
            speaker.listener = None
        recorder.finish()
