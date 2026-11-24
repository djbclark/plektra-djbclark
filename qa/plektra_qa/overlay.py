"""The band above the recorded screen: running timecode on the left, what is
being pressed or touched on the right, and the chapter list.

This Mac's ffmpeg has no drawtext or libass, so the band is drawn here with
Pillow and fed to ffmpeg as raw frames; ffmpeg stacks it on top of the video.
The phone's own picture is never covered.
"""

from __future__ import annotations

import bisect
import os
from collections.abc import Iterator, Sequence
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

from plektra_qa.inputlog import Shown

FPS = 30
BAND_H = 112
MIN_CHAPTER_S = 10.0  # YouTube ignores chapter lists with shorter chapters
MONO_FONTS = (
    "/System/Library/Fonts/Menlo.ttc",
    "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf",
    "/usr/share/fonts/dejavu/DejaVuSansMono.ttf",
)


def timecode(seconds: float) -> str:
    tenths = int(max(0.0, seconds) * 10)
    h, rest = divmod(tenths // 10, 3600)
    m, s = divmod(rest, 60)
    return f"{h:02d}:{m:02d}:{s:02d}.{tenths % 10}"


def _font(size: int) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
    for path in MONO_FONTS:
        if os.path.isfile(path):
            try:
                return ImageFont.truetype(path, size)
            except OSError:
                continue
    return ImageFont.load_default(size)


class Timeline:
    """Which text is shown at time t, for sorted, non-overlapping intervals."""

    def __init__(self, shown: Sequence[Shown], t0: float) -> None:
        ordered = sorted(shown, key=lambda item: item.start)
        self._starts = [item.start - t0 for item in ordered]
        self._items = [(item.start - t0, item.end - t0, item.text) for item in ordered]

    def at(self, t: float) -> str:
        i = bisect.bisect_right(self._starts, t) - 1
        if i >= 0 and self._items[i][0] <= t < self._items[i][1]:
            return self._items[i][2]
        return ""


class Band:
    def __init__(self, width: int) -> None:
        self.width = width
        self._cache: dict[tuple[str, str, str], bytes] = {}
        self._clock_font = _font(46)

    def _fit(self, draw: ImageDraw.ImageDraw, text: str, size: int, room: float) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
        while size > 14:
            font = _font(size)
            if draw.textlength(text, font=font) <= room:
                return font
            size -= 2
        return _font(14)

    def frame(self, tc: str, keys: str, touch: str) -> bytes:
        key = (tc, keys, touch)
        cached = self._cache.get(key)
        if cached is not None:
            return cached
        image = Image.new("RGB", (self.width, BAND_H), (12, 12, 12))
        draw = ImageDraw.Draw(image)
        draw.text((16, BAND_H // 2), tc, font=self._clock_font, fill=(255, 255, 255), anchor="lm")
        room = self.width - 16 - 16 - draw.textlength(tc, font=self._clock_font) - 24
        if keys:
            font = self._fit(draw, keys, 40, room)
            draw.text((self.width - 16, 30), keys, font=font, fill=(255, 214, 10), anchor="rm")
        if touch:
            font = self._fit(draw, touch, 26, room)
            draw.text((self.width - 16, 82), touch, font=font, fill=(120, 220, 255), anchor="rm")
        data = image.tobytes()
        if len(self._cache) > 4000:
            self._cache.clear()
        self._cache[key] = data
        return data


def band_frames(
    length: float, width: int, keys: Sequence[Shown], touches: Sequence[Shown], t0: float
) -> Iterator[bytes]:
    band = Band(width)
    key_line, touch_line = Timeline(keys, t0), Timeline(touches, t0)
    for i in range(int(length * FPS) + 1):
        t = i / FPS
        yield band.frame(timecode(t), key_line.at(t), touch_line.at(t))


def chapters(marks: Sequence[tuple[float, str]], length: float) -> list[tuple[float, str]]:
    """(seconds, title) from the video's start, sorted, with an opening chapter at 0."""
    out = [(0.0, "Setup")]
    for t, title in sorted(marks):
        if t <= 0.0:
            out[0] = (0.0, title)
        elif 0.0 < t < length:
            out.append((t, title))
    return out


def youtube_chapters(items: Sequence[tuple[float, str]], length: float) -> list[tuple[float, str]]:
    """Merge chapters shorter than ten seconds into the one before, as YouTube requires."""
    kept: list[tuple[float, str]] = []
    for t, title in items:
        if kept and t - kept[-1][0] < MIN_CHAPTER_S:
            if len(kept) == 1:  # a short opening "Setup": the first real chapter starts at 0:00
                kept[0] = (0.0, title)
            continue
        kept.append((t, title))
    while len(kept) > 1 and length - kept[-1][0] < MIN_CHAPTER_S:
        kept.pop()
    return kept


def _stamp(seconds: float) -> str:
    total = int(seconds)
    h, rest = divmod(total, 3600)
    m, s = divmod(rest, 60)
    return f"{h}:{m:02d}:{s:02d}" if h else f"{m}:{s:02d}"


def youtube_text(items: Sequence[tuple[float, str]]) -> str:
    return "".join(f"{_stamp(t)} {title}\n" for t, title in items)


def _escape(text: str) -> str:
    for ch in ("\\", "=", ";", "#", "\n"):
        text = text.replace(ch, "\\" + ch)
    return text


def ffmetadata(items: Sequence[tuple[float, str]], length: float) -> str:
    """FFMETADATA1 chapters: players and editors show them as a chapter list."""
    lines = [";FFMETADATA1"]
    for i, (t, title) in enumerate(items):
        end = items[i + 1][0] if i + 1 < len(items) else max(length, t + 0.1)
        lines += [
            "[CHAPTER]", "TIMEBASE=1/1000", f"START={int(t * 1000)}", f"END={int(end * 1000)}",
            f"title={_escape(title)}",
        ]
    return "\n".join(lines) + "\n"


def write_chapter_files(
    items: Sequence[tuple[float, str]], length: float, base: Path
) -> Path:
    path = base.with_suffix(".chapters.txt")
    path.write_text(youtube_text(youtube_chapters(items, length)))
    return path
