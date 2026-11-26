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
import os
import re
import shlex
import subprocess
import time
from dataclasses import dataclass
from xml.etree import ElementTree
from collections.abc import Callable, Sequence

from plektra_qa.keys import DEFAULT_PACKAGE, PREFS_PATH

IME_ID = re.compile(r"(?m)^\s*mCurId=([^/\s]+)/")
IME_SHOWN = re.compile(r"\bmInputShown=(true|false)\b")
MODE_OVERRIDE = "software_keyboard_mode_runtime_override"
TOGGLE_ACTION = "rocks.pkb.plektra.action.TOGGLE_SOFTWARE_KEYBOARD_MODE"


@dataclass(frozen=True)
class KeyboardState:
    override: str | None
    mode: str
    view_shown: bool

    def __str__(self) -> str:
        return f"{self.mode} (override={self.override or 'none'}, IME window={'shown' if self.view_shown else 'hidden'})"


INVERSION_KEY = "accessibility_display_inversion_enabled"
HEADS_UP_KEY = "heads_up_notifications_enabled"
TIMEOUT_KEY = "screen_off_timeout"
STAY_KEY = "stay_on_while_plugged_in"
NOTIFY_TAG = "plektra-qa-presence"
STOP_TAG = "plektra-qa-stop"
START_TAG = "plektra-qa-start"
# The end-of-batch card (end_state.py). It must outlive the run: never add it to anything that snoozes.
STATE_TAG = "plektra-qa-state"
RETIRE_WAIT_S = 10.0  # how long an earlier run's phone watcher may take to restore the settings
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


def unsnooze(shell: Shell, wanted: Callable[[str], bool]) -> None:
    """Unsnooze every notification whose tag `wanted` accepts. A later post of a snoozed tag stays hidden."""
    try:
        listing = shell(["cmd", "notification", "snoozed"])
    except Exception:
        return
    for line in listing.splitlines():
        key = line.split(" snoozed", 1)[0].strip()
        parts = [part.strip() for part in key.split("|")]
        if len(parts) < 4 or not wanted(parts[3]):
            continue
        try:
            shell(["cmd", "notification", "unsnooze", key])
        except Exception:
            continue


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
        overlay_package: str | None = None,
    ) -> None:
        self.overlay_package = overlay_package  # the button-bar app; the phone watcher hides its bar on exit
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
        self.took_screen = False  # True once an earlier run's stop mark is gone, so user_stopped() is this run's
        self._restored = False

    def __enter__(self) -> ScreenGate:
        self._restored = False
        self._retire_previous_watcher()
        self._saved_heads = self._get("global", HEADS_UP_KEY).strip()
        timeout = self._digits("system", TIMEOUT_KEY)
        if timeout == AWAKE_MS:
            self._restore()
            raise RuntimeError(
                f"{TIMEOUT_KEY} is {AWAKE_MS} (this harness's own stay-awake value, left by a run that did not "
                "restore it); set the real screen timeout first, e.g. settings put system screen_off_timeout 60000"
            )
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
            self.took_screen = True
            self._unsnooze_ours()
            self._notify(STOP_TEXT, STOP_TEXT, STOP_TAG)
            self._start_watcher()
            self._wait_for_stop_card()
            self._collapse()
            self._gate()
        except Exception:
            self._restore()
            raise
        return self

    def __exit__(self, exc_type, exc, tb) -> bool:
        if not self._restored:  # a recorded run restores the phone early, before the video is written
            self._restore()
            self._restored = True
        return False

    def hold(self, reason: str) -> None:
        """Inverted screen. The person leaves the phone alone."""
        # One adb call (it runs between trials, while the person waits): invert, read back, close the
        # shade, post the card.
        card = shell_line(["cmd", "notification", "post", "-t", "Hands off", NOTIFY_TAG, "Hands off"])
        if not self._set_inversion(True, f"cmd statusbar collapse >/dev/null 2>&1; {card} >/dev/null"):
            raise RuntimeError(
                "display inversion did not turn on (or the card was not posted); refusing to control the screen"
            )

    def keyboard_state(self) -> KeyboardState:
        package = os.environ.get("PLEKTRA_QA_PACKAGE", "").strip() or DEFAULT_PACKAGE
        prefs = shell_line(["run-as", package, "cat", PREFS_PATH])
        # Only return the two mode preferences. Keep the rest of the IME's XML on the phone.
        pattern = shell_line(['name="software_keyboard_mode(_runtime_override)?"'])
        script = (
            f'prefs=$({prefs}) || exit 1; printf "%s\\n" "$prefs" | grep -E {pattern}; '
            "dumpsys input_method | grep -E 'mCurId=|mInputShown='"
        )
        data = self._shell(["sh", "-c", script])
        ime = IME_ID.search(data)
        shown = IME_SHOWN.search(data)
        if ime is None or ime[1] != package or shown is None:
            raise RuntimeError("could not read the active Plektra IME window")
        values = {}
        for line in data.splitlines():
            if line.lstrip().startswith("<string "):
                item = ElementTree.fromstring(line.strip())
                values[item.attrib["name"]] = item.text
        override = values.get(MODE_OVERRIDE)
        mode = override or values.get("software_keyboard_mode") or "auto"
        # Auto with a connected physical keyboard uses the hardware surface. Android can report
        # its compact control bar as an input window, so mInputShown alone cannot distinguish it.
        return KeyboardState(override, mode, shown[1] == "true")

    def restore_keyboard(self, wanted: str, state: KeyboardState) -> KeyboardState:
        showing = wanted == "shown"
        mode = "force_virtual" if showing else "force_hardware"
        if (state.mode == mode and (not showing or state.view_shown)) or (not showing and state.mode == "auto"):
            return state

        if state.mode != mode:
            package = os.environ.get("PLEKTRA_QA_PACKAGE", "").strip() or DEFAULT_PACKAGE
            self._shell([
                "am", "start", "-W", "-a", TOGGLE_ACTION,
                "-n", f"{package}/rocks.pkb.plektra.SoftwareKeyboardModeActionActivity",
            ])
        for _ in range(4):
            restored = self.keyboard_state()
            if restored.mode == mode and (not showing or restored.view_shown):
                return restored
            self._sleep(0.12)
        raise RuntimeError(f"software keyboard did not become {wanted}: {restored}")

    def yield_to_human(self, action: str, *, shade: bool = True) -> None:
        """Ordinary colors. The shade shows the action, then closes before typing.

        With the on-screen bar, post the action without opening the shade.
        """
        text = action.strip()
        card = shell_line(["cmd", "notification", "post", "-t", text, NOTIFY_TAG, text]) if text and not shade else ""
        if not self._set_inversion(False, f"{card} >/dev/null" if card else ""):
            raise RuntimeError("display inversion did not turn off")
        if not shade:
            return
        self._notify(text, text)
        self._expand()
        self._sleep(self.read_s)
        self._collapse()

    def user_stopped(self) -> bool:
        text = self._shell(["sh", "-c", f"test -f {AWAKE_STOPPED} && echo yes || true"])
        return text.strip() == "yes"

    def settle_watcher(self) -> None:
        """After exit or a swipe: wait, bounded, until the phone watcher is gone. Its last steps put the
        settings back and hide the button bar, so a card shown before that would vanish."""
        waited = 0.0
        while waited < RETIRE_WAIT_S and self._watcher_alive():
            self._sleep(0.5)
            waited += 0.5

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
            waited = 0.0
            while waited < RETIRE_WAIT_S and self._watcher_alive():
                self._sleep(0.5)
                waited += 0.5
            if self._watcher_alive():
                raise RuntimeError("an earlier run's phone watcher did not finish restoring the phone; try again")
            self._sleep(0.5)  # its last lines (snooze, rm) finish just after the pid file goes
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
            "seen=0",
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
            "    seen=1",
            "    missing=0",
            "    continue",
            "  fi",
            '  if [ "$seen" -eq 0 ]; then',
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
        if self.overlay_package:
            pkg = shlex.quote(self.overlay_package)
            lines.append(f"am broadcast -f 0x20 -a {pkg}.HIDE -p {pkg} >/dev/null 2>&1 || true")
        heads = _heads_restore_line(self._saved_heads or "")
        if heads:
            lines.append(heads)
        lines.extend(
            [
                'cmd notification list > "$notes" || true',
                "while IFS= read -r row; do",
                '  case "$row" in',
                (
                    f'    *"|{STOP_TAG}|"*|*"|{NOTIFY_TAG}|"*|*"|{START_TAG}|"*) '
                    f'cmd notification snooze --for {SNOOZE_MS} "$row" || true ;;'
                ),
                "  esac",
                'done < "$notes"',
                f'rm -f "$release" {AWAKE_PID} {AWAKE_SCRIPT} "$notes"',
                "echo done",
            ]
        )
        return "\n".join(lines) + "\n"

    def _set_inversion(self, enabled: bool, then: str = "") -> bool:
        """Set and read back in one adb call; `then` is more shell for the same call."""
        state = "1" if enabled else "0"
        script = f"settings put secure {INVERSION_KEY} {state}; settings get secure {INVERSION_KEY}"
        if then:
            script += f"; {then}"
        try:
            return self._shell(["sh", "-c", script]).strip() == state
        except Exception:
            return False

    def _notify(self, title: str, body: str, tag: str = NOTIFY_TAG) -> None:
        self._shell(["cmd", "notification", "post", "-t", title, tag, body])

    def _our_tag(self, tag: str) -> bool:
        return tag in {STOP_TAG, NOTIFY_TAG, START_TAG} or tag.startswith(f"{STOP_TAG}-") or tag.startswith(
            f"{NOTIFY_TAG}-"
        )

    def _unsnooze_ours(self) -> None:
        # Exit snoozes these tags because this Android build has no cancel.
        # A later post of a snoozed tag stays hidden, and a hidden stop card
        # must not look like the person swiped it.
        unsnooze(self._shell, self._our_tag)

    def _tag_listed(self, tag: str) -> bool:
        try:
            listing = self._shell(["cmd", "notification", "list"])
        except Exception:
            return False
        needle = f"|{tag}|"
        return any(needle in line for line in listing.splitlines())

    def _wait_for_stop_card(self) -> None:
        for attempt in range(6):
            if self._tag_listed(STOP_TAG):
                return
            self._unsnooze_ours()
            self._notify(STOP_TEXT, STOP_TEXT, STOP_TAG)
            self._sleep(0.4)
        raise RuntimeError("stop card did not appear; refusing to control the screen")

    def _snooze_ours(self) -> None:
        try:
            listing = self._shell(["cmd", "notification", "list"])
        except Exception:
            return
        for line in listing.splitlines():
            parts = [part.strip() for part in line.split("|")]
            if len(parts) >= 4 and parts[3] in {NOTIFY_TAG, STOP_TAG, START_TAG}:
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
