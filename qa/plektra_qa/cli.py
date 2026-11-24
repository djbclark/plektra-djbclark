"""Host commands for the FIRERPA HITL harness."""

from __future__ import annotations

import json
import time
from pathlib import Path
from typing import Any

import typer
from lamda.client import Selector
from rich.console import Console

from plektra_qa.connection import connect as open_device
from plektra_qa.connection import require_version
from plektra_qa.controller import ScreenController
from plektra_qa.hitl_runner import load_suite, run_chord_batch
from plektra_qa.keys import Flavor, KeyNamingError, KeyResolutionError, LiveReadError, render, resolve_keymap
from plektra_qa.recording import RecordingError, recording
from plektra_qa.screen_gate import ScreenGate, require_serial
from plektra_qa.speech import Speaker
from plektra_qa.start_gate import StartGate, StartGateError, batch_instruction

app = typer.Typer(add_completion=False, no_args_is_help=True)
console = Console()

# Names are added only after a watcher is proven on a device. A watcher may
# click a dialog button. It must not change the IME. Each item is
# (name, condition selector, click-target selector).
PROVEN_WATCHERS: list[tuple[str, dict[str, object], dict[str, object]]] = [
    # Pinnote asks for a rating every few launches and the modal blocks the field
    # (seen 2026-11-24: every trial failed "input field not found"). Decline it.
    (
        "pinnote-rate-prompt",
        {"resourceId": "branislav667.pinnote:id/alertTitle", "text": "Enjoying the app?"},
        {"resourceId": "android:id/button2", "text": "Maybe later"},
    ),
]
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


def install_watchers(device: Any) -> None:
    """Register the proven dialog watchers and turn the watcher loop on."""
    for name, conditions, target in PROVEN_WATCHERS:
        device.register_click_target_selector_watcher(name, [Selector(**conditions)], Selector(**target))
        device.set_watcher_enabled(name, True)
    device.set_watcher_loop_enabled(True)


@app.command()
def watchers() -> None:
    """Register the proven dialog watchers only (no settings change) and say whether the dialogs are on screen."""
    device = open_device()
    install_watchers(device)
    time.sleep(8.0)  # the watcher loop takes a few seconds to notice a dialog
    for name, conditions, _target in PROVEN_WATCHERS:
        shown = device(**{key: value for key, value in conditions.items() if key == "resourceId"}).exists()
        console.print(f"{name}: dialog still on screen={shown}", markup=False)


@app.command()
def prep() -> None:
    """Zero animator scales and enable the UI watcher loop."""
    device = open_device()
    settings = device.stub("Settings")
    for name in ANIMATION_SCALES:
        settings.put_global(name, "0")
    install_watchers(device)
    console.print(
        f"animator scales 0; watcher loop on; proven watchers={len(PROVEN_WATCHERS)}"
    )


@app.command()
def run(
    suite: Path,
    record: bool = typer.Option(
        False,
        "--record/--no-record",
        help="Record the screen and the spoken prompts to an mp4 for sharing. Off by default; ask the person first.",
    ),
    panel: bool = typer.Option(
        True,
        "--panel/--no-panel",
        help="With --record: put the terminal log beside the phone and add it as a switchable subtitle track.",
    ),
    record_dir: Path | None = typer.Option(
        None, "--record-dir", help="Where the mp4 goes (default: PLEKTRA_QA_RECORD_DIR or ~/plektra-qa-recordings)."
    ),
) -> None:
    """Run a YAML chord suite. A person types on the keyboard; the host does not."""
    try:
        resolved = resolve_keymap(on_live_failure="error")
        console.print(resolved.describe(), markup=False)
        trials = load_suite(suite, resolved.keymap)
    except (KeyNamingError, KeyResolutionError, LiveReadError, ValueError) as exc:
        typer.echo(f"refused: {exc}", err=True)
        raise typer.Exit(code=2) from exc
    controller = ScreenController(open_device())
    install_watchers(controller.device)
    packages = {trial.package for trial in trials if trial.package}
    gate = ScreenGate(require_serial(), allowed_packages=packages)
    speaker = Speaker()
    console.print(f"voice: {speaker.backend} (PLEKTRA_QA_VOICE=auto|kokoro|say|espeak|off)")
    try:
        # A human batch never starts on its own: card, voice, then the person's go.
        StartGate(require_serial(), speaker=speaker, console=console).wait(
            f"Plektra QA: {suite.name}", batch_instruction(len(trials))
        )
        with recording(require_serial(), speaker, record, record_dir, panel=panel) as recorder:
            if recorder is not None:
                voice = "with the voice" if speaker.enabled else "video only (voice is off)"
                console.print(f"recording the screen {voice} to {recorder.output}")
            results = run_chord_batch(
                controller, trials, console, gate, speaker,
                on_trial=recorder.on_trial if recorder is not None else None,
                on_result=recorder.on_result if recorder is not None else None,
            )
    except (RecordingError, StartGateError) as exc:
        typer.echo(f"refused: {exc}", err=True)
        raise typer.Exit(code=2) from exc
    if recorder is not None:
        for warning in recorder.warnings:
            console.print(f"recording: {warning}", markup=False)
        if recorder.output.is_file():
            console.print(f"recording saved: {recorder.output}", markup=False)
    failed = [item for item in results if not item.passed]
    for item in failed:
        console.print(
            f"fail {item.phase} | {item.prompt} | expect={item.expect!r} "
            f"observed={item.observed!r} error={item.error}"
        )
    if failed:
        raise typer.Exit(code=1)


@app.command()
def ui(
    open_: str = typer.Option("", "--open", help="Open PACKAGE/ACTIVITY first."),
    click: str = typer.Option("", "--click", help="Click this resource id next, then wait a second."),
    all_nodes: bool = typer.Option(False, "--all", help="Print every node, not only interactive or labelled ones."),
) -> None:
    """Print the foreground app and the screen's nodes (to debug a trial that cannot find its field).

    Read-only unless you pass --open or --click, which drive the phone: only with the person's say-so.
    """
    from xml.etree import ElementTree

    device = open_device()
    if open_:
        package, _, activity = open_.partition("/")
        ScreenController(device).teleport_to_activity(package, activity)
        time.sleep(1.5)
    if click:
        device(resourceId=click).click()
        time.sleep(1.0)
    app = device.current_application()
    console.print(f"{app.applicationId} {getattr(app, 'activity', '')}", markup=False)
    root = ElementTree.fromstring(device.dump_window_hierarchy().read())
    for node in root.iter():
        a = node.attrib
        if a.get("resource-id", "").startswith("com.android.systemui"):
            continue
        wanted = all_nodes or a.get("clickable") == "true" or a.get("focused") == "true" or a.get("text") or a.get("resource-id")
        if wanted:
            console.print(
                f"{a.get('resource-id', '')} | {a.get('class', '')} | {a.get('text', '')!r} | {a.get('bounds', '')}"
                f"{' | focused' if a.get('focused') == 'true' else ''}",
                markup=False,
            )


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
