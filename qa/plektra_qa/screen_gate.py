"""Hands-off signal for the time this harness drives the phone.

The on-device signals match stayturgid screen control, and they use adb only.
Nothing from an ops repo is installed on the phone or imported here.

Inverted colors mean the harness has the screen. Ordinary colors mean the
person should type. The notification shade carries only that action. Heads-up
banners stay off for the session so a popup cannot cover the field, and the
previous value is put back on the way out.

The harness also keeps the screen on for the pass. It does not assume Wakey or
any other stay-awake app. A shade card, "Swipe away to stop testing", clears
inversion and that wake lock on the phone even when the computer run is gone.
"""

from __future__ import annotations

import base64
import re
import shlex
import subprocess
import time
from collections.abc import Callable, Sequence

INVERSION_KEY = "accessibility_display_inversion_enabled"
HEADS_UP_KEY = "heads_up_notifications_enabled"
TIMEOUT_KEY = "screen_off_timeout"
STAY_KEY = "stay_on_while_plugged_in"
NOTIFY_TAG = "plektra-qa-presence"
STOP_TAG = "plektra-qa-stop"
STOP_TEXT = "Swipe away to stop testing"
SNOOZE_MS = "2147483647"
AWAKE_MS = "86400000"
AWAKE_SCRIPT = "/data/local/tmp/plektra-qa-awake.sh"
AWAKE_PID = "/data/local/tmp/plektra-qa-awake.pid"
AWAKE_LOG = "/data/local/tmp/plektra-qa-awake.log"
AWAKE_RELEASE = "/data/local/tmp/plektra-qa-release"
AWAKE_STOPPED = "/data/local/tmp/plektra-qa-stopped"
AWAKE_NOTES = "/data/local/tmp/plektra-qa-notes.txt"
_FOREGROUND_RE = re.compile(r"\bu\d+\s+([A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+)/")
_LOCK_MARKERS = (
    "mDreamingLockscreen=true",
    "isKeyguardShowing=true",
    "mShowingLockscreen=true",
)
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


def shell_line(args: Sequence[str]) -> str:
    """One device-shell command. adb joins argv on spaces and the phone splits again."""
    return " ".join(shlex.quote(part) for part in args)


def adb_shell(serial: str, args: Sequence[str]) -> str:
    try:
        proc = subprocess.run(
            ["adb", "-s", serial, "shell", shell_line(args)],
            capture_output=True,
            text=True,
            check=False,
            timeout=60,
        )
    except subprocess.TimeoutExpired as exc:
        raise RuntimeError(f"adb shell timed out: {shell_line(args)}") from exc
    if proc.returncode != 0:
        detail = (proc.stderr or proc.stdout).strip()
        raise RuntimeError(detail or f"adb shell failed: {' '.join(args)}")
    return proc.stdout.replace("\r", "")


def _heads_restore_line(saved: str) -> str:
    if saved in {"0", "1"}:
        return f"settings put global {HEADS_UP_KEY} {saved}"
    if saved in {"", "null"}:
        return f"settings delete global {HEADS_UP_KEY}"
    return ""


class ScreenGate:
    """Context manager. Enter keeps the screen on and suppresses heads-up popups.

    Exit releases that wake lock and restores the saved settings. Swiping the
    stop card does the same from the phone.
    """

    def __init__(
        self,
        serial: str,
        *,
        shell: Shell | None = None,
        sleep: Callable[[float], None] | None = None,
        allowed_packages: set[str] | None = None,
        countdown_s: float = 10,
        read_s: float = 4,
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
        self.read_s = read_s
        self._saved_heads: str | None = None
        self._saved_timeout: str | None = None
        self._saved_stay: str | None = None

    def __enter__(self) -> ScreenGate:
        self._retire_previous_watcher()
        self._saved_heads = self._get("global", HEADS_UP_KEY).strip()
        timeout = self._digits("system", TIMEOUT_KEY)
        stay = self._digits("global", STAY_KEY)
        self._saved_timeout = timeout
        self._saved_stay = stay
        try:
            self._put("global", HEADS_UP_KEY, "0")
            if self._get("global", HEADS_UP_KEY).strip() != "0":
                raise RuntimeError(
                    "heads-up notifications stayed on; refusing to control the screen"
                )
            self._hold_awake()
            self._wake_and_unlock()
            self._shell(["sh", "-c", f"rm -f {AWAKE_STOPPED}"])
            self._notify(STOP_TEXT, STOP_TEXT, STOP_TAG)
            self._start_watcher()
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
        self._notify("Hands off", "Hands off")

    def yield_to_human(self, action: str) -> None:
        """Ordinary colors. The shade shows the action, then closes before typing."""
        if not self._set_inversion(False):
            raise RuntimeError("display inversion did not turn off")
        text = action.strip()
        self._notify(text, text)
        self._expand()
        self._sleep(self.read_s)
        self._collapse()

    def user_stopped(self) -> bool:
        text = self._shell(["sh", "-c", f"test -f {AWAKE_STOPPED} && echo yes || true"])
        return text.strip() == "yes"

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
        # Write the release file before snoozing. A missing stop card is a
        # swipe only when this file is absent, so exit is not an abort.
        self._release_awake()
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

    def _release_awake(self) -> None:
        try:
            self._shell(["sh", "-c", f"touch {AWAKE_RELEASE}"])
        except Exception:
            pass
        timeout = self._saved_timeout
        stay = self._saved_stay
        self._saved_timeout = None
        self._saved_stay = None
        if timeout is None and stay is None:
            return
        try:
            if timeout is not None:
                self._put("system", TIMEOUT_KEY, timeout)
            if stay is not None:
                self._put("global", STAY_KEY, stay)
                if stay == "0":
                    self._shell(["svc", "power", "stayon", "false"])
        except Exception:
            return

    def _hold_awake(self) -> None:
        self._put("system", TIMEOUT_KEY, AWAKE_MS)
        if self._get("system", TIMEOUT_KEY).strip() != AWAKE_MS:
            raise RuntimeError("screen timeout did not stick; refusing to control the screen")
        self._shell(["svc", "power", "stayon", "true"])
        stay = self._get("global", STAY_KEY).strip()
        # Android 16 writes 15 (AC, USB, wireless, and dock). Older builds write 7.
        if not stay.isdigit() or int(stay) == 0:
            raise RuntimeError("stay-awake did not stick; refusing to control the screen")

    def _wake_and_unlock(self) -> None:
        self._shell(["input", "keyevent", "KEYCODE_WAKEUP"])
        self._shell(["wm", "dismiss-keyguard"])
        text = self._shell(["dumpsys", "window"])
        if any(marker in text for marker in _LOCK_MARKERS):
            raise RuntimeError("Unlock the phone once. The harness will not enter a PIN.")

    def _retire_previous_watcher(self) -> None:
        if self._watcher_alive():
            try:
                self._shell(["sh", "-c", f"touch {AWAKE_RELEASE}"])
            except Exception:
                return
            self._sleep(1.2)
        try:
            self._shell(["sh", "-c", f"rm -f {AWAKE_RELEASE} {AWAKE_STOPPED}"])
        except Exception:
            return

    def _watcher_alive(self) -> bool:
        try:
            text = self._shell(["sh", "-c", f"cat {AWAKE_PID} 2>/dev/null || true"])
        except Exception:
            return False
        pid = text.strip()
        if not pid.isdigit():
            return False
        try:
            alive = self._shell(
                ["sh", "-c", f"kill -0 {pid} >/dev/null 2>&1 && echo yes || true"]
            )
        except Exception:
            return False
        return alive.strip() == "yes"

    def _start_watcher(self) -> None:
        payload = base64.b64encode(self._watcher_text().encode()).decode()
        write = f"printf %s {payload} | base64 -d > {AWAKE_SCRIPT}"
        self._shell(["sh", "-c", write])
        start = (
            f"/system/bin/setsid sh {AWAKE_SCRIPT} </dev/null >{AWAKE_LOG} 2>&1 & echo started"
        )
        self._shell(["sh", "-c", start])

    def _watcher_text(self) -> str:
        timeout = self._saved_timeout
        stay = self._saved_stay
        lines = [
            "#!/system/bin/sh",
            f"tag={STOP_TAG}",
            f"release={AWAKE_RELEASE}",
            f"stopped={AWAKE_STOPPED}",
            f"notes={AWAKE_NOTES}",
            f"echo $$ > {AWAKE_PID}",
            "missing=0",
            "while true; do",
            '  if [ -f "$release" ]; then',
            "    echo release",
            "    break",
            "  fi",
            "  sleep 1",
            '  if [ -f "$release" ]; then',
            "    echo release",
            "    break",
            "  fi",
            '  cmd notification list > "$notes" || true',
            '  if grep -F "|$tag|" "$notes" >/dev/null; then',
            "    missing=0",
            "    continue",
            "  fi",
            '  if [ -f "$release" ]; then',
            "    echo release",
            "    break",
            "  fi",
            "  missing=$((missing + 1))",
            '  if [ "$missing" -ge 2 ]; then',
            '    echo stopped > "$stopped"',
            "    echo stopped",
            "    break",
            "  fi",
            "done",
            f"settings put secure {INVERSION_KEY} 0",
            f"settings put system {TIMEOUT_KEY} {timeout}",
            f"settings put global {STAY_KEY} {stay}",
        ]
        if stay == "0":
            lines.append("svc power stayon false")
        heads = _heads_restore_line(self._saved_heads or "")
        if heads:
            lines.append(heads)
        lines.extend(
            [
                'cmd notification list > "$notes" || true',
                "while IFS= read -r row; do",
                '  case "$row" in',
                (
                    f'    *"|{STOP_TAG}|"*|*"|{NOTIFY_TAG}|"*) '
                    f'cmd notification snooze --for {SNOOZE_MS} "$row" || true ;;'
                ),
                "  esac",
                'done < "$notes"',
                f'rm -f "$release" {AWAKE_PID} {AWAKE_SCRIPT} "$notes"',
                "echo done",
            ]
        )
        return "\n".join(lines) + "\n"

    def _set_inversion(self, enabled: bool) -> bool:
        state = "1" if enabled else "0"
        try:
            self._put("secure", INVERSION_KEY, state)
            return self._get("secure", INVERSION_KEY).strip() == state
        except Exception:
            return False

    def _notify(self, title: str, body: str, tag: str = NOTIFY_TAG) -> None:
        self._shell(["cmd", "notification", "post", "-t", title, tag, body])

    def _snooze_ours(self) -> None:
        try:
            listing = self._shell(["cmd", "notification", "list"])
        except Exception:
            return
        for line in listing.splitlines():
            parts = [part.strip() for part in line.split("|")]
            if len(parts) >= 4 and parts[3] in {NOTIFY_TAG, STOP_TAG}:
                try:
                    self._shell(
                        ["cmd", "notification", "snooze", "--for", SNOOZE_MS, line.strip()]
                    )
                except Exception:
                    continue

    def _collapse(self) -> None:
        try:
            self._shell(["cmd", "statusbar", "collapse"])
        except Exception:
            return

    def _expand(self) -> None:
        try:
            self._shell(["cmd", "statusbar", "expand-notifications"])
        except Exception:
            return

    def _digits(self, namespace: str, key: str) -> str:
        raw = self._get(namespace, key).strip()
        if not raw.isdigit():
            raise RuntimeError(f"{key} is {raw!r}; refusing to change the screen timeout")
        return raw

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
