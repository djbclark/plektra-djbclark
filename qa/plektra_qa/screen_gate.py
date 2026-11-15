"""Hands-off signal for the time this harness drives the phone.

The on-device signals match stayturgid screen control, and they use adb only.
Nothing from an ops repo is installed on the phone or imported here.

Inverted colors mean the harness has the screen. Ordinary colors mean the
person should type. An adb notification says which of those is current.
Heads-up banners stay off for the session so a popup cannot cover the field,
and the previous value is put back on the way out.
"""

from __future__ import annotations

import re
import subprocess
import time
from collections.abc import Callable, Sequence

INVERSION_KEY = "accessibility_display_inversion_enabled"
HEADS_UP_KEY = "heads_up_notifications_enabled"
NOTIFY_TAG = "plektra-qa-presence"
SNOOZE_MS = "2147483647"
_FOREGROUND_RE = re.compile(r"\bu\d+\s+([A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+)/")
IDLE_PACKAGES = frozenset(
    {
        "",
        "bitpit.launcher",
        "com.android.launcher3",
        "com.android.systemui",
        "com.google.android.apps.nexuslauncher",
        "com.sec.android.app.launcher",
    }
)

Shell = Callable[[Sequence[str]], str]


def adb_shell(serial: str, args: Sequence[str]) -> str:
    proc = subprocess.run(
        ["adb", "-s", serial, "shell", *args],
        capture_output=True,
        text=True,
        check=False,
    )
    if proc.returncode != 0:
        detail = (proc.stderr or proc.stdout).strip()
        raise RuntimeError(detail or f"adb shell failed: {' '.join(args)}")
    return proc.stdout.replace("\r", "")


class ScreenGate:
    """Context manager. Enter suppresses heads-up popups. Exit restores them."""

    def __init__(
        self,
        serial: str,
        *,
        shell: Shell | None = None,
        sleep: Callable[[float], None] | None = None,
        allowed_packages: set[str] | None = None,
        countdown_s: float = 10,
    ) -> None:
        if not serial.strip():
            raise RuntimeError("ADB_TARGET is required while the harness drives the screen")
        self.serial = serial.strip()
        self._shell = shell or (lambda args: adb_shell(self.serial, args))
        self._sleep = sleep or time.sleep
        self.allowed = set(IDLE_PACKAGES)
        if allowed_packages:
            self.allowed.update(allowed_packages)
        self.countdown_s = countdown_s
        self._saved_heads: str | None = None

    def __enter__(self) -> ScreenGate:
        self._saved_heads = self._get("global", HEADS_UP_KEY).strip()
        try:
            self._put("global", HEADS_UP_KEY, "0")
            if self._get("global", HEADS_UP_KEY).strip() != "0":
                raise RuntimeError(
                    "heads-up notifications stayed on; refusing to control the screen"
                )
            self._collapse()
            self._gate()
        except Exception:
            self._restore()
            raise
        return self

    def __exit__(self, exc_type, exc, tb) -> bool:
        self._restore()
        return False

    def hold(self, reason: str) -> None:
        """Inverted screen. The person leaves the phone alone."""
        if not self._set_inversion(True):
            raise RuntimeError(
                "display inversion did not turn on; refusing to control the screen"
            )
        self._collapse()
        self._notify("Hands off", reason)

    def yield_to_human(self, prompt: str) -> None:
        """Ordinary colors. The person types this prompt."""
        if not self._set_inversion(False):
            raise RuntimeError("display inversion did not turn off")
        self._notify("Your turn", prompt)

    def foreground_package(self) -> str:
        text = self._shell(["dumpsys", "activity", "activities"])
        for line in text.splitlines():
            if "topResumedActivity" not in line and "mResumedActivity" not in line:
                continue
            match = _FOREGROUND_RE.search(line)
            if match:
                return match.group(1)
        return ""

    def _gate(self) -> None:
        package = self.foreground_package()
        if package in self.allowed:
            return
        self.hold(
            f"{package} is in front. Taking the screen in {self.countdown_s:g} seconds. "
            "Ctrl-C on the computer refuses."
        )
        self._sleep(self.countdown_s)

    def _restore(self) -> None:
        self._set_inversion(False)
        self._snooze_ours()
        saved = self._saved_heads
        self._saved_heads = None
        if saved is None:
            return
        if saved == "null" or saved == "":
            try:
                self._shell(["settings", "delete", "global", HEADS_UP_KEY])
            except Exception:
                return
            return
        try:
            self._put("global", HEADS_UP_KEY, saved)
        except Exception:
            return

    def _set_inversion(self, enabled: bool) -> bool:
        state = "1" if enabled else "0"
        try:
            self._put("secure", INVERSION_KEY, state)
            return self._get("secure", INVERSION_KEY).strip() == state
        except Exception:
            return False

    def _notify(self, title: str, body: str) -> None:
        self._shell(
            ["cmd", "notification", "post", "-t", title, NOTIFY_TAG, body]
        )

    def _snooze_ours(self) -> None:
        try:
            listing = self._shell(["cmd", "notification", "list"])
        except Exception:
            return
        for line in listing.splitlines():
            parts = [part.strip() for part in line.split("|")]
            if len(parts) >= 4 and parts[3] == NOTIFY_TAG:
                try:
                    self._shell(
                        ["cmd", "notification", "snooze", "--for", SNOOZE_MS, line.strip()]
                    )
                except Exception:
                    return

    def _collapse(self) -> None:
        try:
            self._shell(["cmd", "statusbar", "collapse"])
        except Exception:
            return

    def _get(self, namespace: str, key: str) -> str:
        return self._shell(["settings", "get", namespace, key])

    def _put(self, namespace: str, key: str, value: str) -> None:
        self._shell(["settings", "put", namespace, key, value])


def require_serial() -> str:
    import os

    serial = os.environ.get("ADB_TARGET", "").strip()
    if not serial:
        raise SystemExit(
            "ERROR: ADB_TARGET is required while the harness drives the screen"
        )
    return serial
