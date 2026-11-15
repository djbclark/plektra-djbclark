"""stdio MCP server so an agent can drive the phone and hand chords to a person."""

from __future__ import annotations

import base64
import json
import xml.etree.ElementTree as ElementTree
from typing import Any

from lamda.client import Keys
from mcp.server.mcpserver import MCPServer

from plektra_qa.connection import connect, server_status
from plektra_qa.controller import ScreenController, obj_info
from plektra_qa.hitl_runner import ChordTrial, run_chord_batch
from plektra_qa.screen_gate import ScreenGate, require_serial
from plektra_qa.speech import Speaker

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
    with ScreenGate(require_serial(), allowed_packages={package}) as gate:
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
        with ScreenGate(require_serial()) as gate:
            gate.hold(f"Pressing {key_name}")
            device.press_key(_key(key_name))
        return f"pressed {key_name}"
    if action not in {"click", "long_click", "clear_text_field"}:
        raise ValueError(f"unsupported action {action}")
    with ScreenGate(require_serial()) as gate:
        gate.hold(action)
        element = device(**selector_kwargs)
        getattr(element, action)()
    return action


@mcp.tool()
def request_human_chord_batch(trials_json: str) -> str:
    """Block while a person chords the batch. Returns the per-trial report."""
    _device, controller = _session()
    raw = json.loads(trials_json)
    trials = [ChordTrial.model_validate(item) for item in raw]
    packages = {trial.package for trial in trials if trial.package}
    gate = ScreenGate(require_serial(), allowed_packages=packages)
    results = run_chord_batch(controller, trials, gate=gate, speaker=Speaker())
    return json.dumps([item.model_dump() for item in results])


def main() -> None:
    mcp.run(transport="stdio")
