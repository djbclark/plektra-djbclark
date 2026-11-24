"""One run at a time per phone, enforced by the operating system.

Two runs on one phone fight over the screen and speak over each other (2026-11-24: a run thought
aborted kept going and a second run started beside it). The lock is a flock on a file: the kernel
drops it when its process ends, however it ends, so it cannot go stale and needs no cleanup.
"""

from __future__ import annotations

import fcntl
import os
import re
import signal
import subprocess
import time
from collections.abc import Callable
from pathlib import Path
from typing import Any


class RunLockError(RuntimeError):
    pass


_HELD: dict[str, int] = {}  # serial -> open fd, kept for the life of the process
REAPABLE = {"scrcpy", "afplay", "adb", "ffmpeg", "say", "kokoro"}  # children a dead run may have left running


def device_serial(target: str, run: Callable[..., Any] = subprocess.run) -> str:
    """The phone's own serial (getprop ro.serialno), so LAN, Tailscale and USB share one lock.

    Falls back to the ADB_TARGET string when the phone does not answer."""
    try:
        done = run(["adb", "-s", target, "shell", "getprop", "ro.serialno"], capture_output=True, text=True, timeout=15)
        serial = str(done.stdout).strip()
    except (OSError, subprocess.SubprocessError):
        return target
    return serial if serial and " " not in serial and "\n" not in serial and "error" not in serial.lower() else target


def _process_table() -> list[tuple[int, int, str]]:
    """(pid, ppid, command name) of every process."""
    out = subprocess.run(["ps", "-axo", "pid=,ppid=,comm="], capture_output=True, text=True, timeout=10).stdout
    rows = []
    for line in out.splitlines():
        parts = line.split(None, 2)
        if len(parts) == 3 and parts[0].isdigit() and parts[1].isdigit():
            rows.append((int(parts[0]), int(parts[1]), os.path.basename(parts[2])))
    return rows


def descendants(root: int, table: list[tuple[int, int, str]] | None = None) -> list[tuple[int, str]]:
    rows = _process_table() if table is None else table
    found: list[tuple[int, str]] = []
    frontier = {root}
    while frontier:
        nxt = {pid for pid, ppid, _ in rows if ppid in frontier}
        found += [(pid, name) for pid, _, name in rows if pid in nxt]
        frontier = nxt
    return found


def note_children(serial: str) -> None:
    """Write this process's child pids into the lock file (so the next run can clean up after a kill)."""
    fd = _HELD.get(serial)
    if fd is None:
        return
    try:
        kids = ",".join(f"{pid}:{name}" for pid, name in descendants(os.getpid()))
    except (OSError, subprocess.SubprocessError):
        return
    text = f"pid {os.getpid()}, started {_STARTED.get(serial, '')}, children {kids}".encode()
    os.ftruncate(fd, 0)
    os.pwrite(fd, text, 0)


def reap_leftovers(
    holder: str,
    table: list[tuple[int, int, str]] | None = None,
    kill: Callable[[int, int], None] = os.kill,
) -> list[int]:
    """Kill the children a dead run left behind (its lock text lists them): only the same pid AND command name."""
    match = re.search(r"children ([\d:A-Za-z_.,-]+)", holder)
    if not match:
        return []
    rows = _process_table() if table is None else table
    live = {pid: name for pid, _, name in rows}
    killed: list[int] = []
    for item in match.group(1).split(","):
        pid_text, _, name = item.partition(":")
        if pid_text.isdigit() and name in REAPABLE and live.get(int(pid_text)) == name:
            try:
                kill(int(pid_text), signal.SIGTERM)
                killed.append(int(pid_text))
            except OSError:
                pass
    return killed


_STARTED: dict[str, str] = {}


def lock_dir() -> Path:
    return Path(os.environ.get("PLEKTRA_QA_LOCK_DIR") or Path.home() / ".local" / "state" / "plektra-qa" / "locks")


def acquire(serial: str, directory: Path | None = None) -> Path:
    """Take the phone's run lock for this process, or raise RunLockError naming the holder."""
    if serial in _HELD:
        raise RunLockError(f"this process already holds the run lock for {serial}")
    folder = directory or lock_dir()
    folder.mkdir(parents=True, exist_ok=True)
    path = folder / (re.sub(r"[^A-Za-z0-9_.-]", "_", serial) + ".lock")
    fd = os.open(path, os.O_RDWR | os.O_CREAT, 0o644)
    try:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        holder = os.pread(fd, 200, 0).decode(errors="replace").strip() or "unknown process"
        os.close(fd)
        raise RunLockError(
            f"another QA run is already driving {serial} ({holder}). Stop it first "
            f"(kill its pid), because two runs on one phone talk over each other."
        ) from None
    previous = os.pread(fd, 2000, 0).decode(errors="replace")  # the dead holder's note: kill what it left
    try:
        reap_leftovers(previous)
    except (OSError, subprocess.SubprocessError):
        pass
    _STARTED[serial] = time.strftime("%H:%M:%S")
    os.ftruncate(fd, 0)
    os.pwrite(fd, f"pid {os.getpid()}, started {_STARTED[serial]}".encode(), 0)
    _HELD[serial] = fd
    return path


def release(serial: str) -> None:
    fd = _HELD.pop(serial, None)
    if fd is not None:
        os.close(fd)  # closing drops the flock
