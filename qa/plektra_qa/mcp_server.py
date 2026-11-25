"""stdio MCP server so an agent can drive the phone and hand chords to a person."""

from __future__ import annotations

import base64
import json
import os
import xml.etree.ElementTree as ElementTree
from collections.abc import Iterator
from contextlib import contextmanager
from typing import Any

from rich.console import Console

from lamda.client import Keys
from mcp.server.mcpserver import MCPServer

from plektra_qa.connection import connect, server_status
from plektra_qa.controller import ScreenController, obj_info
from plektra_qa import end_state, runlock
from plektra_qa.button_bar import DEFAULT_PACKAGE, ButtonBar, OverlayError
from plektra_qa.end_state import EndNotice
from plektra_qa.hitl_runner import ChordTrial, RepeatError, check_repeats, run_chord_batch
from plektra_qa.keys import localise_texts, resolve_keymap
from plektra_qa.probe import ProbeError, bench_run
from plektra_qa.recording import RecordingError, recording
from plektra_qa.screen_gate import ScreenGate, require_serial
from plektra_qa.speech import Speaker
from plektra_qa.start_gate import StartGate, StartGateError, batch_instruction

mcp = MCPServer("plektra-qa-mcp")
_device: Any = None
_controller: ScreenController | None = None

# Token budget for one inspect call. Focused nodes are kept first.
_NODE_CAP = 80


def _session() -> tuple[Any, ScreenController]:
    global _device, _controller
    if _controller is None or _device is None:
        _device = connect()
        _controller = ScreenController(_device)
    return _device, _controller


@contextmanager
def _phone_lock() -> Iterator[None]:
    """Every tool that drives the screen takes the phone's run lock, so it cannot collide with a `run`."""
    serial = runlock.device_serial(require_serial())
    try:
        runlock.acquire(serial)
    except runlock.RunLockError as exc:
        raise RuntimeError(f"refused: {exc}") from exc
    try:
        yield
    finally:
        runlock.release(serial)


def _key(name: str) -> Any:
    token = name if name.startswith("KEY_") else f"KEY_{name}"
    try:
        return getattr(Keys, token)
    except AttributeError as exc:
        known = [item for item in dir(Keys) if item.startswith("KEY_")]
        raise ValueError(f"unknown key {name}; known: {', '.join(known)}") from exc


def _interesting(node: ElementTree.Element) -> bool:
    def flag(attr: str) -> bool:
        return node.attrib.get(attr, "false") == "true"

    return bool(
        flag("clickable")
        or flag("focusable")
        or flag("focused")
        or node.attrib.get("text")
        or node.attrib.get("resource-id")
    )


def _node_row(node: ElementTree.Element) -> dict[str, object]:
    return {
        "resourceId": node.attrib.get("resource-id", ""),
        "text": node.attrib.get("text", ""),
        "className": node.attrib.get("class", ""),
        "bounds": node.attrib.get("bounds", ""),
        "focused": node.attrib.get("focused", "false") == "true",
    }


@mcp.tool()
def device_status() -> dict[str, object]:
    """Connection latency, screen size, foreground activity, and clipboard."""
    device, _controller = _session()
    status = server_status(device)
    display = device.device_info()
    app = device.current_application()
    return {
        **status,
        "display_width": display.displayWidth,
        "display_height": display.displayHeight,
        "package": app.applicationId,
        "activity": getattr(app, "activity", ""),
        "clipboard": device.get_clipboard() or "",
    }


@mcp.tool()
def teleport_and_prep_input(package: str, activity: str, field_resource_id: str) -> dict[str, object]:
    """Jump to an activity and focus and clear the named input."""
    _device, controller = _session()
    with _phone_lock(), ScreenGate(require_serial(), allowed_packages={package}) as gate:
        gate.hold(f"Opening {package}")
        controller.teleport_to_activity(package, activity)
        element = controller.focus_and_clear(resource_id=field_resource_id)
        info = obj_info(element.info())
    return info


@mcp.tool()
def inspect_ui_fast(include_screenshot_b64: bool = False, jpeg_quality: int = 75) -> dict[str, object]:
    """Compact interactive nodes, plus a JPEG screenshot only when asked."""
    device, _controller = _session()
    raw = device.dump_window_hierarchy().read()
    root = ElementTree.fromstring(raw)
    rows = [_node_row(node) for node in root.iter() if _interesting(node)]
    rows.sort(key=lambda row: not bool(row["focused"]))
    payload: dict[str, object] = {
        "nodes": rows[:_NODE_CAP],
        "truncated": max(0, len(rows) - _NODE_CAP),
    }
    if include_screenshot_b64:
        image = device.screenshot(quality=jpeg_quality).read()
        payload["screenshot_b64"] = base64.b64encode(image).decode("ascii")
    return payload


@mcp.tool()
def execute_ui_action(action: str, selector_kwargs: dict[str, Any]) -> str:
    """click, long_click, clear_text_field, or press_key. There is no set_text."""
    device, _controller = _session()
    if action == "press_key":
        key_name = str(selector_kwargs.get("key", ""))
        with _phone_lock(), ScreenGate(require_serial()) as gate:
            gate.hold(f"Pressing {key_name}")
            device.press_key(_key(key_name))
        return f"pressed {key_name}"
    if action not in {"click", "long_click", "clear_text_field"}:
        raise ValueError(f"unsupported action {action}")
    with _phone_lock(), ScreenGate(require_serial()) as gate:
        gate.hold(action)
        element = device(**selector_kwargs)
        getattr(element, action)()
    return action


def _bar_package() -> str:
    return os.environ.get("PLEKTRA_QA_BAR_PACKAGE", "").strip() or DEFAULT_PACKAGE


@mcp.tool()
def request_human_chord_batch(
    trials_json: str,
    record: bool = True,
    human_said_go: bool = False,
    more_coming: bool = False,
    repeat_reason: str = "",
) -> str:
    """Block while a person chords the batch. Returns the per-trial report.

    The run is recorded by default (screen, spoken prompts and every key, to an
    mp4). Tell the person before the batch and pass record=false if they say no;
    the start card also offers starting without recording. With human_said_go=true
    there is no phone start, so settle recording with them in chat first. A
    recorded run returns {"results": [...],
    "recording": path or null, "warnings": [...]}.

    The batch never starts on its own: the phone shows and speaks the
    instruction, then waits for a key press or tap on the phone. Set
    human_said_go=true only when the person has just told you go for this
    batch; the card and voice still run first. A wait with no start ends the
    run with {"refused": ...}.

    Ask for the same action at most 3 times ("try 2 of 10" numbering does not
    make it a new action); more is refused unless repeat_reason says why it is
    really necessary (at most 80 characters, shown on the start card).

    When the batch ends the person is told that the phone may be used normally.
    Pass more_coming=true when you will start another batch right after: they
    are then told to wait and leave the phone (a stop by them always frees it).
    If that batch will not run, call release_phone.

    A trial on the test bench (package dev.hitlbar.overlay, activity
    dev.hitlbar.overlay.BenchActivity) also gets "probe" (keys pressed,
    corrections, difference, where a missing character got to) and, when it
    failed, "fault": "typo" is the person's typing error and not a keyboard
    failure, "keyboard-or-app" or "unknown".
    """
    _device, controller = _session()
    raw = json.loads(trials_json)
    keymap = resolve_keymap(on_live_failure="error").keymap
    trials = [
        ChordTrial.model_validate(localise_texts(item, keymap, f"trial {index}"))
        for index, item in enumerate(raw, start=1)
    ]
    try:
        reason = check_repeats(trials, repeat_reason, "this batch")
    except RepeatError as exc:
        return json.dumps({"refused": str(exc)})
    packages = {trial.package for trial in trials if trial.package}
    target = require_serial()
    bar_package = _bar_package()
    # stdout is the JSON-RPC channel: everything the batch prints goes to stderr.
    console = Console(stderr=True)
    gate = ScreenGate(target, allowed_packages=packages, overlay_package=bar_package)
    speaker = Speaker()
    notice = EndNotice(target, more_coming=more_coming, speaker=speaker, console=console)
    locked = False
    serial = runlock.device_serial(target)
    bar_cm = ButtonBar(target, package=bar_package)
    bar: ButtonBar | None = None
    try:
        runlock.acquire(serial)
        locked = True
        try:
            bar = bar_cm.__enter__()  # the on-screen bar is the default; without the overlay app use the shade card
        except OverlayError as exc:
            console.print(f"no button bar ({exc}); using the shade card", markup=False)
            bar = None
        notice.bar = bar
        try:
            with bench_run(target, trials, "mcp") as probe:
                start = StartGate(target, speaker=speaker, console=console, bar=bar)
                notice.starting()  # an earlier batch's "done" or "wait" card no longer holds
                start.wait(
                    "Plektra QA batch", batch_instruction(len(trials)), confirmed=human_said_go, recording=record,
                    repeat_reason=reason,
                )
                notice.started = True
                record = record and not start.record_declined
                with recording(target, speaker, record, captured=lambda: notice.finish(gate)) as recorder:
                    results = run_chord_batch(
                        controller, trials, console, gate=gate, speaker=speaker,
                        on_trial=recorder.on_trial if recorder is not None else None,
                        on_result=recorder.on_result if recorder is not None else None,
                        on_redo=(lambda trial: recorder.withdraw(trial.number)) if recorder is not None else None,
                        on_discard=recorder.on_discard if recorder is not None else None,
                        overlay=bar,
                        probe=probe,
                        notice=notice,
                    )
        finally:
            notice.finish(gate)  # failed or never started: the person still learns where they stand
            if bar is not None:
                bar_cm.__exit__(None, None, None)
    except (RecordingError, StartGateError, ProbeError, runlock.RunLockError) as exc:
        return json.dumps({"refused": str(exc)})
    finally:
        if locked:
            runlock.release(serial)
    report = [item.model_dump() for item in results]
    if recorder is None:
        return json.dumps(report)
    saved = recorder.output.is_file()
    return json.dumps(
        {
            "results": report,
            "recording": str(recorder.output) if saved else None,
            "warnings": recorder.warnings,
        }
    )


@mcp.tool()
def release_phone() -> str:
    """Tell the person that no more batches are coming and the phone may be used normally.

    Call it when a batch announced with more_coming=true will not run. Posts the
    notification, says it and shows it on the button bar. Refused while a run is
    driving the phone."""
    try:
        notice = end_state.release(require_serial(), console=Console(stderr=True), bar_package=_bar_package())
    except runlock.RunLockError as exc:
        return json.dumps({"refused": str(exc)})
    return json.dumps({"released": notice.posted, "told": notice.told, "bar": notice.on_bar})


def main() -> None:
    mcp.run(transport="stdio")
