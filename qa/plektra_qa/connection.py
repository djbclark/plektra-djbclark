"""Open the certificate-authenticated FIRERPA channel."""

from __future__ import annotations

import os
import subprocess
import time
from pathlib import Path

from lamda.client import Device


def require_env(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        raise SystemExit(f"ERROR: {name} is required")
    return value


def connect() -> Device:
    """Connect to the device named by the environment.

    `ADB_TARGET`, when set, publishes `adb forward` for a USB-only client.
    The server still requires `FIRERPA_CERT`.
    """
    host = require_env("FIRERPA_HOST")
    port = int(os.environ.get("FIRERPA_PORT", "65000"))
    cert = Path(require_env("FIRERPA_CERT"))
    if not cert.is_file():
        raise SystemExit(f"ERROR: FIRERPA_CERT does not exist: {cert}")
    adb_target = os.environ.get("ADB_TARGET", "").strip()
    if adb_target:
        subprocess.run(
            ["adb", "-s", adb_target, "forward", f"tcp:{port}", f"tcp:{port}"],
            check=True,
        )
    return Device(host, port=port, certificate=str(cert))


def server_status(device: Device) -> dict[str, object]:
    started = time.perf_counter()
    info = device.server_info()
    elapsed = time.perf_counter() - started
    return {
        "version": info.version,
        "uptime_s": info.uptime,
        "rtt_ms": round(elapsed * 1000, 1),
    }


def require_version(device: Device) -> dict[str, object]:
    status = server_status(device)
    expected = os.environ.get("FIRERPA_VERSION", "10.12").strip() or "10.12"
    if status["version"] != expected:
        raise SystemExit(
            f"ERROR: FIRERPA version is {status['version']}; expected {expected}"
        )
    return status
