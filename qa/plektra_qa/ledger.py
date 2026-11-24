"""Remember which trials already passed, keep a clip of each, and join the clips into one video.

A trial that passed on the installed Plektra build is not run again unless the person asks
(`run --redo`). Its clip stays on disk, so the finished video is made of the trials that
passed, in suite order; failed, stopped and unfinished trials are cut out (djbclark, 2026-11-24:
"We keep re-doing tests we already did. ... adding to videos as needed, cutting out any
unfinished tests").
"""

from __future__ import annotations

import hashlib
import json
import os
import subprocess
import tempfile
import time
from collections.abc import Callable
from pathlib import Path
from typing import Any

TAIL_S = 1.0  # keep a moment after the result so the cut does not clip the last key


def state_dir() -> Path:
    return Path(os.environ.get("PLEKTRA_QA_LEDGER_DIR") or Path.home() / ".local" / "state" / "plektra-qa" / "ledger")


def clip_dir(suite: str) -> Path:
    base = os.environ.get("PLEKTRA_QA_RECORD_DIR") or Path.home() / "plektra-qa-recordings"
    return Path(base) / "clips" / suite


def trial_key(trial: Any) -> str:
    """Stable id of what a trial asks for: edit the prompt or the expectation and it is a new trial."""
    parts = [getattr(trial, name, "") or "" for name in ("phase", "prompt", "expect", "verify_mode", "sentinel")]
    return hashlib.sha1("\x1f".join(parts).encode()).hexdigest()[:12]


def ime_build(serial: str, run: Callable[..., Any] = subprocess.run) -> str:
    """Identify the installed keyboard build ("" when the phone will not say): a new install retests."""

    def adb(*args: str) -> str:
        done = run(["adb", "-s", serial, "shell", *args], capture_output=True, text=True, timeout=20)
        return str(done.stdout)

    try:
        # Read only: the keyboard in use is the one under test, and this never changes it.
        current = [line for line in adb("dumpsys", "input_method").splitlines() if "mCurId=" in line]
        package = current[0].split("mCurId=")[1].split("/")[0].strip() if current else ""
        if not package or package == "null":
            return ""
        lines = adb("dumpsys", "package", package).splitlines()
    except (OSError, subprocess.SubprocessError):
        return ""
    wanted = ("versionName=", "versionCode=", "lastUpdateTime=")
    found = [line.strip() for line in lines if line.strip().startswith(wanted)]
    return package + " " + " ".join(found)


class Ledger:
    def __init__(self, suite: str, build: str, root: Path | None = None, clips: Path | None = None) -> None:
        self.suite = suite
        self.build = build
        self.path = (root or state_dir()) / f"{suite}.json"
        self.clips = clips or clip_dir(suite)
        self.entries: dict[str, dict[str, Any]] = {}
        if self.path.is_file():
            self.entries = json.loads(self.path.read_text()).get("trials", {})

    def passed(self, trial: Any) -> bool:
        entry = self.entries.get(trial_key(trial))
        return bool(entry and entry.get("passed") and entry.get("build") == self.build)

    def pending(self, trials: list[Any]) -> list[Any]:
        return [trial for trial in trials if not self.passed(trial)]

    def record(self, trial: Any, observed: str, clip: Path | None) -> None:
        self.entries[trial_key(trial)] = {
            "prompt": trial.prompt,
            "phase": getattr(trial, "phase", ""),
            "passed": True,
            "observed": observed,
            "build": self.build,
            "at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
            "clip": str(clip) if clip is not None else None,
        }
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps({"suite": self.suite, "trials": self.entries}, indent=2, ensure_ascii=False))
        tmp.replace(self.path)

    def clip_for(self, trial: Any) -> Path | None:
        if not self.passed(trial):
            return None
        clip = self.entries[trial_key(trial)].get("clip")
        return Path(clip) if clip and Path(clip).is_file() else None

    def clip_path(self, trial: Any) -> Path:
        return self.clips / f"{trial_key(trial)}.mp4"


def cut_clip(source: Path, start: float, end: float, dest: Path, run: Callable[..., Any] = subprocess.run) -> bool:
    """Copy [start, end] seconds of a recording into its own mp4 (re-encoded so the cut is exact)."""
    dest.parent.mkdir(parents=True, exist_ok=True)
    cmd = [
        "ffmpeg", "-v", "error", "-y", "-ss", f"{max(start, 0):.3f}", "-to", f"{end:.3f}", "-i", str(source),
        "-map", "0:v:0", "-map", "0:a?", "-c:v", "libx264", "-preset", "veryfast", "-crf", "23",
        "-pix_fmt", "yuv420p", "-c:a", "aac", "-movflags", "+faststart", str(dest),
    ]
    try:
        done = run(cmd, capture_output=True, text=True, timeout=600)
    except (OSError, subprocess.SubprocessError):
        return False
    return done.returncode == 0 and dest.is_file()


def join_clips(clips: list[Path], dest: Path, run: Callable[..., Any] = subprocess.run) -> bool:
    """Join clips in order into one mp4. They share one encoding, so no re-encode."""
    if not clips:
        return False
    dest.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="plektra-qa-join-") as tmp:
        listing = Path(tmp) / "clips.txt"
        listing.write_text("".join("file '" + str(clip).replace("'", "'\\''") + "'\n" for clip in clips))
        cmd = ["ffmpeg", "-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", str(listing), "-c", "copy", str(dest)]
        try:
            done = run(cmd, capture_output=True, text=True, timeout=600)
        except (OSError, subprocess.SubprocessError):
            return False
    return done.returncode == 0 and dest.is_file()


def final_video(ledger: Ledger, trials: list[Any], dest: Path, run: Callable[..., Any] = subprocess.run) -> tuple[bool, list[Any]]:
    """Join the clips of every passed trial in suite order. Returns (written, trials still without a passing clip)."""
    have = [(trial, ledger.clip_for(trial)) for trial in trials]
    missing = [trial for trial, clip in have if clip is None]
    written = join_clips([clip for _, clip in have if clip is not None], dest, run)
    return written, missing
