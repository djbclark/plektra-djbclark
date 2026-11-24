"""The MCP batch path: bar by default, nothing on stdout, the phone lock keyed on the real serial."""

import json
from contextlib import nullcontext
from types import SimpleNamespace

import pytest

from plektra_qa import mcp_server, runlock
from plektra_qa.button_bar import OverlayError


def _patch(monkeypatch, bar_error=None):
    seen = {}

    class Bar:
        def __init__(self, *a, **k): pass
        def __enter__(self):
            if bar_error:
                raise bar_error
            seen["bar"] = True
            return self
        def __exit__(self, *a): return False

    class Start:
        def __init__(self, serial, **k):
            seen["start_bar"], seen["start_console"] = k.get("bar"), k["console"]
        def wait(self, *a, **k): return "relayed"

    def batch(controller, trials, console, **k):
        seen["console"], seen["overlay"] = console, k.get("overlay")
        seen["lock"] = dict(runlock._HELD)
        return []

    monkeypatch.setenv("ADB_TARGET", "1.2.3.4:5555")
    monkeypatch.setenv("PLEKTRA_QA_LOCK_DIR", str(__import__("tempfile").mkdtemp()))
    monkeypatch.setenv("PLEKTRA_QA_VOICE", "off")
    monkeypatch.setenv("PLEKTRA_QA_KEYMAP_SOURCE", "profile")
    monkeypatch.setattr(mcp_server, "_session", lambda: (None, SimpleNamespace()))
    monkeypatch.setattr(mcp_server, "ButtonBar", Bar)
    monkeypatch.setattr(mcp_server, "StartGate", Start)
    monkeypatch.setattr(mcp_server, "ScreenGate", lambda *a, **k: nullcontext())
    monkeypatch.setattr(mcp_server, "run_chord_batch", batch)
    monkeypatch.setattr(mcp_server, "resolve_keymap", lambda **k: SimpleNamespace(keymap=__import__("plektra_qa.keys", fromlist=["x"]).resolve_keymap(env={"PLEKTRA_QA_KEYMAP_SOURCE": "profile"}).keymap))
    monkeypatch.setattr(runlock, "device_serial", lambda target: "REALSERIAL")
    return seen


def test_the_batch_uses_the_bar_logs_to_stderr_and_locks_on_the_real_serial(monkeypatch):
    seen = _patch(monkeypatch)
    out = mcp_server.request_human_chord_batch(json.dumps([{"prompt": "a", "expect": "a"}]), human_said_go=True)
    assert json.loads(out) == []
    assert seen["bar"] and seen["overlay"] is not None and seen["start_bar"] is seen["overlay"]
    assert seen["console"].stderr is True and seen["start_console"].stderr is True
    assert list(seen["lock"]) == ["REALSERIAL"]
    assert runlock._HELD == {}


def test_without_the_overlay_app_the_batch_falls_back_to_the_shade_card(monkeypatch):
    seen = _patch(monkeypatch, bar_error=OverlayError("not installed"))
    mcp_server.request_human_chord_batch(json.dumps([{"prompt": "a", "expect": "a"}]), human_said_go=True)
    assert seen["overlay"] is None


def test_screen_tools_refuse_while_another_run_holds_the_phone(monkeypatch, tmp_path):
    _patch(monkeypatch)
    monkeypatch.setenv("PLEKTRA_QA_LOCK_DIR", str(tmp_path))
    runlock.acquire("REALSERIAL", tmp_path)
    try:
        # same process cannot take it twice: the tool refuses instead of driving the screen
        with pytest.raises(RuntimeError, match="refused"):
            mcp_server.teleport_and_prep_input("p", "a", "f")
    finally:
        runlock.release("REALSERIAL")
