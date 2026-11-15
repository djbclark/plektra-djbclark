"""Host commands for the FIRERPA HITL harness."""

from __future__ import annotations

from pathlib import Path

import typer
from rich.console import Console

from plektra_qa.connection import connect as open_device
from plektra_qa.connection import require_version
from plektra_qa.controller import ScreenController
from plektra_qa.hitl_runner import load_suite, run_chord_batch

app = typer.Typer(add_completion=False, no_args_is_help=True)
console = Console()

# Names are added only after a watcher is proven on a device. A watcher may
# click a dialog button. It must not change the IME. Each item is
# (name, condition selector, click-target selector).
PROVEN_WATCHERS: list[tuple[str, dict[str, object], dict[str, object]]] = []
ANIMATION_SCALES = (
    "window_animation_scale",
    "transition_animation_scale",
    "animator_duration_scale",
)


@app.command()
def connect() -> None:
    """Open FIRERPA and print version, uptime, and round-trip time."""
    device = open_device()
    status = require_version(device)
    console.print(
        f"FIRERPA v{status['version']} uptime={status['uptime_s']}s rtt={status['rtt_ms']}ms"
    )


@app.command()
def prep() -> None:
    """Zero animator scales and enable the UI watcher loop."""
    device = open_device()
    settings = device.stub("Settings")
    for name in ANIMATION_SCALES:
        settings.put_global(name, "0")
    for name, conditions, target in PROVEN_WATCHERS:
        device.register_click_target_selector_watcher(name, conditions, target)
        device.set_watcher_enabled(name, True)
    device.set_watcher_loop_enabled(True)
    console.print(
        f"animator scales 0; watcher loop on; proven watchers={len(PROVEN_WATCHERS)}"
    )


@app.command()
def run(suite: Path) -> None:
    """Run a YAML chord suite. A person types on the keyboard; the host does not."""
    trials = load_suite(suite)
    controller = ScreenController(open_device())
    results = run_chord_batch(controller, trials, console)
    failed = [item.prompt for item in results if not item.passed]
    if failed:
        raise typer.Exit(code=1)


@app.command()
def mcp() -> None:
    """Serve plektra-qa-mcp on stdio."""
    from plektra_qa.mcp_server import main

    main()
