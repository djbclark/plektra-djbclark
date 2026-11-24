"""Host commands for the FIRERPA HITL harness."""

from __future__ import annotations

import json
import os
import signal
import subprocess
import shutil
import time
from contextlib import ExitStack
from pathlib import Path
from typing import Any

import typer
from lamda.client import Selector
from rich.console import Console

from plektra_qa.connection import connect as open_device
from plektra_qa.connection import require_version
from plektra_qa.controller import ScreenController
from plektra_qa.hitl_runner import allowed_packages, load_suite, plan_trials, run_chord_batch
from plektra_qa import runlock
from plektra_qa.ledger import TAIL_S, BuildError, Ledger, cut_clip, final_video, ime_build, require_plektra_build
from plektra_qa.keys import DEFAULT_PACKAGE as KEYBOARD_PACKAGE
from plektra_qa.keys import Flavor, KeyNamingError, KeyResolutionError, LiveReadError, render, resolve_keymap
from plektra_qa.button_bar import DEFAULT_PACKAGE, ButtonBar, OverlayError
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
        try:
            device.remove_watcher(name)  # an earlier run's copy stays on the phone; re-register it fresh
        except Exception:
            pass
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
    bar: bool = typer.Option(
        True,
        "--bar/--no-bar",
        help="Use the on-screen Done/Next/Stop bar (default); a trial ends on Done and the batch starts only on its Done "
        "or a typed go. --no-bar: the notification-shade card, a trial ends when the text matches.",
    ),
    bar_package: str = typer.Option(
        DEFAULT_PACKAGE, "--bar-package", envvar="PLEKTRA_QA_BAR_PACKAGE", help="Package of the button-bar app."
    ),
    redo: bool = typer.Option(
        False, "--redo", help="Run every trial again, also those that already passed on this keyboard build."
    ),
) -> None:
    """Run a YAML chord suite. A person types on the keyboard; the host does not.

    A trial that already passed on the installed keyboard build is skipped; its clip stays in the
    final video (`plektra-qa video`). Failed or unfinished trials run again."""
    target = require_serial()
    try:
        resolved = resolve_keymap(on_live_failure="error")
        console.print(resolved.describe(), markup=False)
        all_trials = load_suite(suite, resolved.keymap)
        build = _keyboard_build(target)
    except (KeyNamingError, KeyResolutionError, LiveReadError, ValueError, BuildError) as exc:
        typer.echo(f"refused: {exc}", err=True)
        raise typer.Exit(code=2) from exc
    if record:
        missing = [tool for tool in ("scrcpy", "ffmpeg") if not shutil.which(tool)]
        if missing:
            typer.echo(f"refused: recording needs {', '.join(missing)} on PATH (macOS: brew install scrcpy ffmpeg)", err=True)
            raise typer.Exit(code=2)
    serial = runlock.device_serial(target)  # the phone's own serial: LAN, Tailscale and USB share one lock
    try:
        runlock.acquire(serial)
    except runlock.RunLockError as exc:
        typer.echo(f"refused: {exc}", err=True)
        raise typer.Exit(code=2) from exc
    try:
        _run_locked(suite, all_trials, build, target, serial, record, panel, record_dir, bar, bar_package, redo)
    finally:
        runlock.release(serial)


def _raise_interrupt(signum: int, _frame: Any) -> None:
    raise KeyboardInterrupt(f"signal {signum}")


def _run_locked(
    suite: Path, all_trials: list[Any], build: str, target: str, serial: str, record: bool, panel: bool,
    record_dir: Path | None, bar: bool, bar_package: str, redo: bool,
) -> None:
    ledger = Ledger(suite.stem, build)
    trials = all_trials
    if not redo:
        trials = plan_trials(all_trials, ledger.passed)
        done = len(all_trials) - len(trials)
        if done:
            console.print(
                f"{done} of {len(all_trials)} trials already passed on this keyboard build and are skipped "
                "(--redo runs them again).", markup=False,
            )
        if not trials:
            console.print("nothing left to run.", markup=False)
            _write_final(ledger, all_trials, suite, record_dir)
            return
    by_number = {trial.number: trial for trial in trials}
    controller = ScreenController(open_device())
    install_watchers(controller.device)
    gate = ScreenGate(
        target, allowed_packages=allowed_packages(all_trials), overlay_package=bar_package if bar else None
    )
    speaker = Speaker()
    console.print(f"voice: {speaker.backend} (PLEKTRA_QA_VOICE=auto|kokoro|say|espeak|off)")
    got: list[Any] = []
    recorder = None

    def on_trial(number: int, trial: Any) -> None:
        if recorder is not None:
            recorder.on_trial(number, trial)
        runlock.note_children(serial)

    def on_result(number: int, result: Any) -> None:
        got.append(result)
        if recorder is not None:
            recorder.on_result(number, result)
        trial = by_number.get(number)
        if result.passed and trial is not None:
            ledger.record(trial, result.observed, None)  # at once: a kill later does not lose it

    interrupted = False
    old_handlers = {sig: signal.signal(sig, _raise_interrupt) for sig in (signal.SIGTERM, signal.SIGHUP)}
    try:
        with ExitStack() as stack:
            button_bar = None
            if bar:
                button_bar = stack.enter_context(ButtonBar(target, package=bar_package))
            else:
                ButtonBar(target, package=bar_package).hide_blind()  # a bar left by an earlier run
                stack.callback(ButtonBar(target, package=bar_package).hide_blind)
            # Preflight before the person is asked to start: unlock, heads-up, stop card, the foreground app.
            stack.enter_context(gate)
            gate.yield_to_human("", shade=False)  # ordinary colors for the go
            StartGate(target, speaker=speaker, console=console, bar=button_bar).wait(
                f"Plektra QA: {suite.name}", batch_instruction(len(trials))
            )
            with recording(target, speaker, record, record_dir, panel=panel) as recorder:
                if recorder is not None:
                    voice = "with the voice" if speaker.enabled else "video only (voice is off)"
                    console.print(f"recording the screen {voice} to {recorder.output}")
                    runlock.note_children(serial)
                run_chord_batch(
                    controller, trials, console, gate, speaker,
                    on_trial=on_trial, on_result=on_result, overlay=button_bar, gate_entered=True,
                )
    except KeyboardInterrupt:
        interrupted = True
        console.print("interrupted: passes so far are kept.", markup=False)
    except (RecordingError, StartGateError, OverlayError, RuntimeError) as exc:
        typer.echo(f"refused: {exc}", err=True)
        raise typer.Exit(code=2) from exc
    finally:
        for sig, handler in old_handlers.items():
            signal.signal(sig, handler)
    if recorder is not None:
        for warning in recorder.warnings:
            console.print(f"recording: {warning}", markup=False)
        if recorder.output.is_file():
            console.print(f"recording saved: {recorder.output}", markup=False)
        for trial, began, ended, passed, _result in recorder.spans:
            if not passed:
                continue
            dest = ledger.clip_path(trial)
            if recorder.output.is_file() and cut_clip(recorder.output, began, ended + TAIL_S, dest):
                ledger.attach_clip(trial, dest)
            else:
                ledger.forget(trial)  # recorded run: a pass with no clip does not count
                console.print(f"trial {trial.number}: clip could not be cut, so it is not counted as passed", markup=False)
    _write_final(ledger, all_trials, suite, record_dir)
    failed = [item for item in got if not item.passed]
    for item in failed:
        console.print(
            f"fail {item.number} {item.phase} | {item.prompt} | expect={item.expect!r} "
            f"observed={item.observed!r} error={item.error}"
        )
    if interrupted:
        raise typer.Exit(code=130)
    if failed:
        raise typer.Exit(code=1)


def _keyboard_build(target: str) -> str:
    """The installed keyboard build, or BuildError: unknown or not Plektra."""
    package = os.environ.get("PLEKTRA_QA_PACKAGE", "").strip() or KEYBOARD_PACKAGE
    return require_plektra_build(ime_build(target), package)


def _write_final(ledger: Ledger, trials: list[Any], suite: Path, record_dir: Path | None) -> None:
    """Join the clips of the passed trials, in suite order, into one video; say what is still open."""
    out_dir = record_dir or Path(os.environ.get("PLEKTRA_QA_RECORD_DIR") or Path.home() / "plektra-qa-recordings")
    dest = out_dir / f"{suite.stem}-final.mp4"
    written, missing, no_clip = final_video(ledger, trials, dest)
    if written:
        console.print(f"video of the passed trials: {dest}", markup=False)
    if no_clip:
        console.print(
            f"passed, no clip ({len(no_clip)}; --redo --record to film them): "
            + "; ".join(f"{t.number}. {t.prompt}" for t in no_clip), markup=False,
        )
    if missing:
        console.print(
            f"still to pass ({len(missing)}): " + "; ".join(f"{t.number}. {t.prompt}" for t in missing), markup=False
        )
    elif not no_clip:
        console.print("every trial has passed on this keyboard build.", markup=False)


@app.command()
def video(suite: Path, record_dir: Path | None = typer.Option(None, "--record-dir")) -> None:
    """Rebuild the final video of a suite from the clips of its passed trials."""
    try:
        trials = load_suite(suite, resolve_keymap(on_live_failure="error").keymap)
    except (KeyNamingError, KeyResolutionError, LiveReadError, ValueError) as exc:
        typer.echo(f"refused: {exc}", err=True)
        raise typer.Exit(code=2) from exc
    try:
        build = _keyboard_build(require_serial())
    except BuildError as exc:
        typer.echo(f"refused: {exc}", err=True)
        raise typer.Exit(code=2) from exc
    _write_final(Ledger(suite.stem, build), trials, suite, record_dir)


@app.command("import")
def import_recording(
    suite: Path,
    recording_file: Path,
    passed: str = typer.Option(..., "--passed", help="Trial numbers that passed in that run, e.g. 2,3,4,5."),
) -> None:
    """Add trials that passed in an earlier recording (cut by its chapters) to the ledger."""
    try:
        trials = load_suite(suite, resolve_keymap(on_live_failure="error").keymap)
    except (KeyNamingError, KeyResolutionError, LiveReadError, ValueError) as exc:
        typer.echo(f"refused: {exc}", err=True)
        raise typer.Exit(code=2) from exc
    probe = subprocess.run(
        ["ffprobe", "-v", "error", "-show_chapters", "-show_format", "-of", "json", str(recording_file)],
        capture_output=True, text=True, check=True,
    )
    info = json.loads(probe.stdout)
    length = float(info["format"]["duration"])
    marks = [(int(c["tags"]["title"].split(".")[0]), float(c["start_time"])) for c in info.get("chapters", [])
             if c.get("tags", {}).get("title", "")[:1].isdigit()]
    marks.sort(key=lambda m: m[1])
    try:
        build = _keyboard_build(require_serial())
    except BuildError as exc:
        typer.echo(f"refused: {exc}", err=True)
        raise typer.Exit(code=2) from exc
    ledger = Ledger(suite.stem, build)
    for number in (int(n) for n in passed.split(",")):
        found = [i for i, m in enumerate(marks) if m[0] == number]
        if not found or not 1 <= number <= len(trials):
            console.print(f"trial {number}: no chapter in the recording, skipped", markup=False)
            continue
        i = found[0]
        end = marks[i + 1][1] if i + 1 < len(marks) else length
        trial = trials[number - 1]
        dest = ledger.clip_path(trial)
        if cut_clip(recording_file, marks[i][1], end, dest):
            ledger.record(trial, trial.expect, dest)
            console.print(f"trial {number} added: {trial.prompt}", markup=False)
        else:
            console.print(f"trial {number}: could not cut the clip", markup=False)


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
