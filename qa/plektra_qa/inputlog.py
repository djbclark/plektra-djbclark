"""What the person presses and touches, read from the phone's own input devices.

`adb shell getevent -lt` reports every key and touch the kernel sees, with its
kernel name (`KEY_A`, `ABS_MT_POSITION_X`). This module turns that stream into
two timelines for the recording's overlay:

- keys: the keys held at each moment, as `L-Shift + A`;
- touch: `TOUCH (x, y)` while a finger is down, then `TAP`, `LONG PRESS` or
  `SWIPE up (x1, y1) to (x2, y2)` once it lifts. The keyboard's own trackpad
  is labelled `PAD` (its coordinates are in its own units).

Everything is passive: it only reads. The parsing and the gesture logic are
pure functions of the event lines, so they are tested without a phone.
"""

from __future__ import annotations

import math
import re
import subprocess
import threading
import time
from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Any

KEY_MIN_S = 0.8  # a key text stays on screen at least this long
KEY_LINGER_S = 0.4  # and this long after the last key lifts
TOUCH_LINGER_S = 1.6  # a finished gesture stays on screen this long
LIVE_EVERY_S = 0.1  # live touch coordinates update at most this often
TAP_MAX_PX = 24.0
LONG_PRESS_S = 0.45

EVENT = re.compile(
    r"^\[\s*(?P<ts>\d+\.\d+)\]\s+(?P<dev>/dev/input/event\d+):\s+"
    r"(?P<type>EV_\w+)\s+(?P<code>\w+)\s+(?P<value>\w+)\s*$"
)
FRIENDLY = {
    "LEFTSHIFT": "L-Shift", "RIGHTSHIFT": "R-Shift", "LEFTCTRL": "L-Ctrl",
    "RIGHTCTRL": "R-Ctrl", "LEFTALT": "L-Alt", "RIGHTALT": "R-Alt",
    "LEFTMETA": "L-Meta", "RIGHTMETA": "R-Meta", "CAPSLOCK": "Caps",
    "SPACE": "Space", "ENTER": "Enter", "BACKSPACE": "Backspace", "TAB": "Tab",
    "ESC": "Esc", "UP": "Up", "DOWN": "Down", "LEFT": "Left", "RIGHT": "Right",
    "DELETE": "Delete", "HOME": "Home", "END": "End", "PAGEUP": "PgUp",
    "PAGEDOWN": "PgDn", "SYM": "Sym",
}
COMPASS = ["right", "down-right", "down", "down-left", "left", "up-left", "up", "up-right"]


@dataclass(frozen=True)
class Event:
    ts: float
    dev: str
    type: str
    code: str
    value: str


def parse_event(line: str) -> Event | None:
    match = EVENT.match(line.strip())
    if match is None:
        return None
    return Event(float(match["ts"]), match["dev"], match["type"], match["code"], match["value"])


def key_label(code: str) -> str:
    name = code.removeprefix("KEY_").removeprefix("BTN_")
    return FRIENDLY.get(name, name if len(name) == 1 else name.title())


@dataclass
class Geometry:
    """From `getevent -lp` and `wm size`: which device is the screen's touch panel."""

    names: dict[str, str] = field(default_factory=dict)
    maxima: dict[str, tuple[int, int]] = field(default_factory=dict)  # dev -> (max x, max y)
    screen: tuple[int, int] | None = None

    @property
    def touchscreen(self) -> str | None:
        """The multi-touch device whose shape is closest to the screen's."""
        if not self.maxima:
            return None
        if self.screen is None:
            return max(self.maxima, key=lambda dev: self.maxima[dev][0] * self.maxima[dev][1])
        ratio = self.screen[0] / self.screen[1]
        return min(self.maxima, key=lambda dev: abs(self.maxima[dev][0] / max(1, self.maxima[dev][1]) - ratio))

    def to_screen(self, dev: str, x: int, y: int) -> tuple[int, int]:
        if dev != self.touchscreen or self.screen is None or dev not in self.maxima:
            return x, y
        mx, my = self.maxima[dev]
        return round(x * self.screen[0] / max(1, mx)), round(y * self.screen[1] / max(1, my))


def parse_geometry(getevent_lp: str, wm_size: str = "") -> Geometry:
    geo = Geometry()
    current = ""
    max_x = max_y = 0
    for line in getevent_lp.splitlines():
        line = line.strip()
        if line.startswith("add device"):
            current = line.split(":", 1)[1].strip()
        elif line.startswith("name:") and current:
            geo.names[current] = line.split(":", 1)[1].strip().strip('"')
        elif "ABS_MT_POSITION_X" in line and current:
            m = re.search(r"max (\d+)", line)
            max_x = int(m[1]) if m else 0
            geo.maxima[current] = (max_x, geo.maxima.get(current, (0, 0))[1])
        elif "ABS_MT_POSITION_Y" in line and current:
            m = re.search(r"max (\d+)", line)
            max_y = int(m[1]) if m else 0
            geo.maxima[current] = (geo.maxima.get(current, (0, 0))[0], max_y)
    sizes = re.findall(r"(Override|Physical) size:\s*(\d+)x(\d+)", wm_size)
    for kind in ("Override", "Physical"):
        for found, w, h in sizes:
            if found == kind:
                geo.screen = (int(w), int(h))
                return geo
    return geo


@dataclass(frozen=True)
class Shown:
    start: float
    end: float
    text: str


@dataclass
class _Finger:
    t_down: float
    x0: int
    y0: int
    x: int
    y: int


class Interpreter:
    """Event lines in, two timelines out. Times are the caller's clock (`feed(now, event)`)."""

    def __init__(self, geometry: Geometry | None = None) -> None:
        self.geo = geometry or Geometry()
        self._held: list[str] = []
        self._key_changes: list[tuple[float, str | None]] = []
        self._touch: list[tuple[float, str | None, bool]] = []  # (t, text, is_final)
        self._slot: dict[str, int] = {}
        self._fingers: dict[tuple[str, int], _Finger] = {}
        self._pending_up: dict[tuple[str, int], float] = {}
        self._last_live = -1e9
        self._pos: dict[tuple[str, int], list[int]] = {}

    # -- keys ---------------------------------------------------------------

    def _key(self, now: float, ev: Event) -> None:
        if ev.code == "BTN_TOUCH":
            # Devices without tracking ids (the keyboard's trackpad) mark a
            # finger only by BTN_TOUCH: slot 0, down at DOWN, lifted at UP.
            slot = (ev.dev, self._slot.get(ev.dev, 0))
            if ev.value == "DOWN":
                self._pos.setdefault(slot, [0, 0])
                self._fingers.pop(slot, None)
                self._pending_up.pop(slot, None)
            elif ev.value == "UP" and slot in self._pos:
                self._pending_up[slot] = ev.ts
            return
        if ev.code.startswith("BTN_TOOL"):
            return
        if not (ev.code.startswith("KEY_") or ev.code.startswith("BTN_")):
            return
        label = key_label(ev.code)
        if ev.value == "DOWN" and label not in self._held:
            self._held.append(label)
        elif ev.value == "UP" and label in self._held:
            self._held.remove(label)
        else:
            return
        self._key_changes.append((now, " + ".join(self._held) if self._held else None))

    # -- touch --------------------------------------------------------------

    def _tag(self, dev: str) -> str:
        return "" if dev == self.geo.touchscreen or self.geo.touchscreen is None else "PAD "

    def _coords(self, dev: str, x: int, y: int) -> tuple[int, int]:
        return self.geo.to_screen(dev, x, y)

    def _abs(self, now: float, ev: Event) -> None:
        slot = self._slot.get(ev.dev, 0)
        key = (ev.dev, slot)
        if ev.code == "ABS_MT_SLOT":
            self._slot[ev.dev] = int(ev.value, 16)
        elif ev.code == "ABS_MT_TRACKING_ID":
            if int(ev.value, 16) >= 0xFFFFFFF0 or ev.value.lower() == "ffffffff":
                self._pending_up[key] = ev.ts
            else:
                self._pos.setdefault(key, [0, 0])
                self._fingers.pop(key, None)
                self._pending_up.pop(key, None)
        elif ev.code == "ABS_MT_POSITION_X":
            self._pos.setdefault(key, [0, 0])[0] = int(ev.value, 16)
        elif ev.code == "ABS_MT_POSITION_Y":
            self._pos.setdefault(key, [0, 0])[1] = int(ev.value, 16)

    def feed(self, now: float, ev: Event) -> None:
        if ev.type == "EV_KEY":
            self._key(now, ev)
        elif ev.type == "EV_ABS" and ev.code.startswith("ABS_MT_"):
            self._abs(now, ev)
        elif ev.type == "EV_SYN" and ev.code == "SYN_REPORT":
            self._sync(now, ev)

    def _sync(self, now: float, ev: Event) -> None:
        for key in list(self._pos):
            dev, slot = key
            if dev != ev.dev:
                continue
            x, y = self._pos[key]
            finger = self._fingers.get(key)
            if key in self._pending_up:
                t_up = self._pending_up.pop(key)
                del self._pos[key]
                if finger is not None:
                    self._lift(now, key, finger, t_up)
                    del self._fingers[key]
                continue
            if finger is None:
                self._fingers[key] = _Finger(ev.ts, x, y, x, y)
                self._live(now, key, x, y, force=True)
            else:
                finger.x, finger.y = x, y
                self._live(now, key, x, y)

    def _count(self, dev: str) -> int:
        return sum(1 for d, _ in self._fingers if d == dev)

    def _live(self, now: float, key: tuple[str, int], x: int, y: int, force: bool = False) -> None:
        if not force and now - self._last_live < LIVE_EVERY_S:
            return
        self._last_live = now
        dev, slot = key
        sx, sy = self._coords(dev, x, y)
        fingers = self._count(dev) + (0 if key in self._fingers else 1)
        prefix = f"{fingers} FINGERS " if fingers > 1 else ""
        self._touch.append((now, f"{self._tag(dev)}{prefix}TOUCH ({sx}, {sy})", False))

    def _lift(self, now: float, key: tuple[str, int], finger: _Finger, t_up: float) -> None:
        dev, slot = key
        x0, y0 = self._coords(dev, finger.x0, finger.y0)
        x1, y1 = self._coords(dev, finger.x, finger.y)
        text = describe_gesture(self._tag(dev), (x0, y0), (x1, y1), max(0.0, t_up - finger.t_down))
        self._touch.append((now, text, True))

    # -- the finished timelines ---------------------------------------------

    def key_timeline(self) -> list[Shown]:
        return _intervals([(t, x) for t, x in self._key_changes], KEY_MIN_S, KEY_LINGER_S)

    def touch_timeline(self) -> list[Shown]:
        out: list[Shown] = []
        items = self._touch
        for i, (t, text, final) in enumerate(items):
            if text is None:
                continue
            nxt = items[i + 1][0] if i + 1 < len(items) else math.inf
            end = min(nxt, t + (TOUCH_LINGER_S if final else 1.0))
            if end > t:
                out.append(Shown(t, end, text))
        return out


def describe_gesture(tag: str, start: tuple[int, int], end: tuple[int, int], seconds: float) -> str:
    dx, dy = end[0] - start[0], end[1] - start[1]
    dist = math.hypot(dx, dy)
    if dist < TAP_MAX_PX:
        if seconds >= LONG_PRESS_S:
            return f"{tag}LONG PRESS ({end[0]}, {end[1]}) {seconds:.1f} s"
        return f"{tag}TAP ({end[0]}, {end[1]})"
    angle = math.degrees(math.atan2(dy, dx)) % 360
    direction = COMPASS[round(angle / 45) % 8]
    return (
        f"{tag}SWIPE {direction} ({start[0]}, {start[1]}) to ({end[0]}, {end[1]}) {seconds:.2f} s"
    )


def _intervals(
    changes: list[tuple[float, str | None]], min_s: float, linger_s: float
) -> list[Shown]:
    out: list[Shown] = []
    for i, (t, text) in enumerate(changes):
        if text is None:
            continue
        nxt = changes[i + 1] if i + 1 < len(changes) else None
        if nxt is None:
            end = t + max(min_s, linger_s)
        elif nxt[1] is not None:
            end = nxt[0]
        else:
            end = max(nxt[0] + linger_s, t + min_s)
            later = next((c[0] for c in changes[i + 2 :] if c[1] is not None), math.inf)
            end = min(end, later)
        if end > t:
            out.append(Shown(t, end, text))
    return out


class InputLogger:
    """Run `adb shell getevent -lt` and feed an Interpreter with arrival times."""

    def __init__(
        self,
        serial: str,
        *,
        popen: Callable[..., Any] = subprocess.Popen,
        run: Callable[..., Any] = subprocess.run,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self.serial = serial
        self._popen = popen
        self._run = run
        self._clock = clock
        self.interpreter = Interpreter()
        self._proc: Any = None
        self._thread: threading.Thread | None = None
        self._lock = threading.Lock()

    def start(self) -> None:
        def adb(*args: str) -> str:
            done = self._run(["adb", "-s", self.serial, "shell", *args], capture_output=True, timeout=15)
            return bytes(done.stdout).decode("utf-8", "replace")

        self.interpreter = Interpreter(parse_geometry(adb("getevent", "-lp"), adb("wm", "size")))
        # -tt gives getevent a terminal on the phone, so its lines arrive as they happen.
        self._proc = self._popen(
            ["adb", "-s", self.serial, "shell", "-tt", "getevent", "-lt"],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
        )
        self._thread = threading.Thread(target=self._pump, name="getevent", daemon=True)
        self._thread.start()

    def _pump(self) -> None:
        for raw in iter(self._proc.stdout.readline, b""):
            event = parse_event(raw.decode("utf-8", "replace").replace("\r", ""))
            if event is None:
                continue
            with self._lock:
                self.interpreter.feed(self._clock(), event)

    def stop(self) -> tuple[list[Shown], list[Shown]]:
        proc, self._proc = self._proc, None
        if proc is not None:
            try:
                proc.stdin.close()
            except OSError:
                pass
            proc.terminate()
            try:
                proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                proc.kill()
        if self._thread is not None:
            self._thread.join(timeout=2)
        with self._lock:
            return self.interpreter.key_timeline(), self.interpreter.touch_timeline()
