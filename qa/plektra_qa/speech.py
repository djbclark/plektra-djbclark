"""Spoken prompts for the human at the keyboard.

The runner says each action out loud and says it again when the human has
not started. Speech never blocks the 25 ms poll loop: `say` starts a player
process and returns.

Backend, from `PLEKTRA_QA_VOICE` (default `auto`):

- `kokoro`: Kokoro-82M through `kokoro-cli` (`uv tool install --python 3.13
  kokoro-cli && kokoro setup`). Offline, macOS and Linux. Phrases are
  rendered once into a cache and then played, so a prompt starts at once.
- `say`: macOS `say`.
- `espeak`: `spd-say`, `espeak-ng` or `espeak` on Linux.
- `off`: silent.
- `auto`: the first of kokoro, say, espeak found on PATH; else silent.

`PLEKTRA_QA_VOICE_NAME` picks the Kokoro voice (default: kokoro's own).
"""

from __future__ import annotations

import hashlib
import os
import shutil
import subprocess
import sys
import threading
import time
from pathlib import Path
from collections.abc import Callable, Iterable

Which = Callable[[str], str | None]


def cache_dir() -> Path:
    base = os.environ.get("XDG_CACHE_HOME") or str(Path.home() / ".cache")
    return Path(base) / "plektra-qa" / "voice"


def _player(which: Which) -> list[str] | None:
    for cmd in (
        ["afplay"],
        ["paplay"],
        ["pw-play"],
        ["aplay", "-q"],
        ["ffplay", "-nodisp", "-autoexit", "-loglevel", "quiet"],
    ):
        if which(cmd[0]):
            return cmd
    return None


def pick_backend(choice: str | None = None, which: Which = shutil.which) -> str:
    choice = (choice or os.environ.get("PLEKTRA_QA_VOICE", "auto")).strip().lower()
    if choice in ("", "auto"):
        if which("kokoro") and _player(which):
            return "kokoro"
        if which("say"):
            return "say"
        if which("spd-say") or which("espeak-ng") or which("espeak"):
            return "espeak"
        return "off"
    if choice not in ("kokoro", "say", "espeak", "off"):
        raise ValueError(f"PLEKTRA_QA_VOICE must be auto, kokoro, say, espeak or off: {choice}")
    return choice


class Speaker:
    """Say one line at a time; a new line cuts off the one still playing."""

    def __init__(
        self,
        backend: str | None = None,
        which: Which = shutil.which,
        popen: Callable[..., subprocess.Popen[bytes]] = subprocess.Popen,
        run: Callable[..., subprocess.CompletedProcess[bytes]] = subprocess.run,
        cache: Path | None = None,
    ) -> None:
        self.backend = pick_backend(backend, which)
        self._which = which
        self._popen = popen
        self._run = run
        self._cache = cache or cache_dir()
        self._voice = os.environ.get("PLEKTRA_QA_VOICE_NAME", "").strip()
        self._proc: subprocess.Popen[bytes] | None = None
        self._player = _player(which) if self.backend == "kokoro" else None
        self._lock = threading.Lock()
        self._warm: threading.Thread | None = None
        # Optional (event, **info) callback: "say" with time/text/kind/path/tool
        # when a line starts, "cut" with time when a line is cut off. The
        # recorder uses it to lay the voice on a screen recording.
        self.listener: Callable[..., None] | None = None

    @property
    def enabled(self) -> bool:
        return self.backend != "off"

    def _wav(self, text: str) -> Path:
        key = hashlib.sha256(f"{self._voice}\0{text}".encode()).hexdigest()[:20]
        return self._cache / f"{key}.wav"

    def _render(self, text: str) -> Path | None:
        wav = self._wav(text)
        if wav.is_file() and wav.stat().st_size > 0:
            return wav
        self._cache.mkdir(parents=True, exist_ok=True)
        tmp = wav.with_name(f"{wav.stem}.{os.getpid()}.{threading.get_ident()}.tmp.wav")
        cmd = ["kokoro", "speak", text, "-o", str(tmp), "--format", "wav", "--service", "auto"]
        if self._voice:
            cmd += ["--voice", self._voice]
        try:
            self._run(cmd, check=True, capture_output=True, timeout=60)
            os.replace(tmp, wav)
        except Exception as exc:  # speech is a convenience, never a test failure
            print(f"voice: kokoro could not render {text!r}: {exc}", file=sys.stderr)
            return None
        return wav

    def prepare(self, texts: Iterable[str]) -> None:
        """Render Kokoro phrases in the background, in order of first use."""
        if self.backend != "kokoro":
            return
        phrases = list(dict.fromkeys(t for t in texts if t))

        def work() -> None:
            for text in phrases:
                self._render(text)

        self._warm = threading.Thread(target=work, name="voice-prepare", daemon=True)
        self._warm.start()

    def _command(self, text: str) -> list[str] | None:
        if self.backend == "say":
            return ["say", text]
        if self.backend == "espeak":
            for name in ("spd-say", "espeak-ng", "espeak"):
                if self._which(name):
                    return [name, "-w", text] if name == "spd-say" else [name, text]
            return None
        if self.backend == "kokoro" and self._player:
            wav = self._render(text)
            if wav is not None:
                return [*self._player, str(wav)]
            if self._which("say"):
                return ["say", text]
        return None

    def _announce(self, text: str, cmd: list[str]) -> None:
        if self.listener is None:
            return
        if cmd[0] == "say":
            info: dict[str, object] = {"kind": "say"}
        elif cmd[-1].endswith(".wav"):
            info = {"kind": "wav", "path": Path(cmd[-1])}
        else:
            info = {"kind": "espeak", "tool": cmd[0]}
        self.listener("say", time=time.monotonic(), text=text, **info)

    def say(self, text: str) -> None:
        if not self.enabled or not text:
            return
        with self._lock:
            self.stop()
            cmd = self._command(text)
            if cmd is None:
                return
            try:
                self._proc = self._popen(
                    cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL
                )
            except OSError as exc:
                print(f"voice: {exc}", file=sys.stderr)
                self._proc = None
                return
            self._announce(text, cmd)

    def wait_idle(self, timeout_s: float) -> None:
        """Block until the line being said has finished, or the timeout."""
        deadline = time.monotonic() + timeout_s
        while self.speaking() and time.monotonic() < deadline:
            time.sleep(0.05)

    def speaking(self) -> bool:
        return self._proc is not None and self._proc.poll() is None

    def stop(self) -> None:
        proc, self._proc = self._proc, None
        if proc is not None and proc.poll() is None:
            if self.listener is not None:
                self.listener("cut", time=time.monotonic())
            proc.terminate()
            try:
                proc.wait(timeout=1)
            except subprocess.TimeoutExpired:
                proc.kill()

    def close(self) -> None:
        self.stop()
