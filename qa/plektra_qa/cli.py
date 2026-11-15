"""Host commands for the FIRERPA HITL harness."""

from __future__ import annotations

import json
from pathlib import Path

import typer
from rich.console import Console

from plektra_qa.connection import connect as open_device
from plektra_qa.connection import require_version
from plektra_qa.controller import ScreenController
from plektra_qa.hitl_runner import load_suite, run_chord_batch
from plektra_qa.keys import Flavor, KeyNamingError, KeyResolutionError, LiveReadError, render, resolve_keymap
from plektra_qa.screen_gate import ScreenGate, require_serial
from plektra_qa.speech import Speaker

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
    try:
        resolved = resolve_keymap(on_live_failure="error")
        console.print(resolved.describe(), markup=False)
        trials = load_suite(suite, resolved.keymap)
    except (KeyNamingError, KeyResolutionError, LiveReadError, ValueError) as exc:
        typer.echo(f"refused: {exc}", err=True)
        raise typer.Exit(code=2) from exc
    controller = ScreenController(open_device())
    packages = {trial.package for trial in trials if trial.package}
    gate = ScreenGate(require_serial(), allowed_packages=packages)
    speaker = Speaker()
    console.print(f"voice: {speaker.backend} (PLEKTRA_QA_VOICE=auto|kokoro|say|espeak|off)")
    results = run_chord_batch(controller, trials, console, gate, speaker)
    failed = [item for item in results if not item.passed]
    for item in failed:
        console.print(
            f"fail {item.phase} | {item.prompt} | expect={item.expect!r} "
            f"observed={item.observed!r} error={item.error}"
        )
    if failed:
        raise typer.Exit(code=1)


@app.command()
def mcp() -> None:
    """Serve plektra-qa-mcp on stdio."""
    from plektra_qa.mcp_server import main

    main()


@app.command("render")
def render_keys(
    text: str = typer.Argument(..., help='Text with key tokens, e.g. "<Ctrl+A>, then <Ctrl+C>"'),
    as_json: bool = typer.Option(False, "--json", help="Print one JSON object instead of lines."),
) -> None:
    """Print the screen, shade and spoken versions of text with key tokens.

    Uses the phone's live keymap when ADB_TARGET is set (read-only), else the
    profile (PLEKTRA_QA_KEYMAP, default qa/keymaps/titan2-elite-baseline.yaml).
    """
    try:
        resolved = resolve_keymap()
        versions = {
            flavor.value: render(text, resolved.keymap, flavor) for flavor in Flavor
        }
    except (KeyNamingError, KeyResolutionError, LiveReadError, ValueError) as exc:
        typer.echo(f"refused: {exc}", err=True)
        raise typer.Exit(code=2) from exc
    if as_json:
        typer.echo(json.dumps({"keymap": resolved.describe(), **versions}, ensure_ascii=False))
        return
    typer.echo(resolved.describe())
    for name, value in versions.items():
        typer.echo(f"{name}: {value}")
