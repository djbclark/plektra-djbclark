"""One run at a time per phone, enforced by the operating system.

Two runs on one phone fight over the screen and speak over each other (2026-11-24: a run thought
aborted kept going and a second run started beside it). The lock is a flock on a file: the kernel
drops it when its process ends, however it ends, so it cannot go stale and needs no cleanup.
"""

from __future__ import annotations

import fcntl
import os
import re
import time
from pathlib import Path


class RunLockError(RuntimeError):
    pass


_HELD: dict[str, int] = {}  # serial -> open fd, kept for the life of the process


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
    os.ftruncate(fd, 0)
    os.pwrite(fd, f"pid {os.getpid()}, started {time.strftime('%H:%M:%S')}".encode(), 0)
    _HELD[serial] = fd
    return path


def release(serial: str) -> None:
    fd = _HELD.pop(serial, None)
    if fd is not None:
        os.close(fd)  # closing drops the flock
