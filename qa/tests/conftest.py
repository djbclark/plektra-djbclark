import pytest

from plektra_qa import end_state, hitl_runner


@pytest.fixture(autouse=True)
def _no_live_keymap(monkeypatch):
    """Tests never read a phone or a developer's own key profile."""
    monkeypatch.setattr(hitl_runner, "SETTLE_S", 0.0)
    monkeypatch.setattr(hitl_runner, "END_CARD_S", 0.0)  # no "redo last" card unless a test asks for it
    monkeypatch.setattr(end_state, "VISIBLE_S", 0.0)
    monkeypatch.setattr(end_state, "adb_shell", lambda serial, args: "")  # tests never touch a phone
    for name in (
        "ADB_TARGET",
        "PLEKTRA_QA_KEYMAP",
        "PLEKTRA_QA_KEYMAP_SOURCE",
        "PLEKTRA_QA_PACKAGE",
    ):
        monkeypatch.delenv(name, raising=False)
