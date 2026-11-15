import pytest


@pytest.fixture(autouse=True)
def _no_live_keymap(monkeypatch):
    """Tests never read a phone or a developer's own key profile."""
    for name in (
        "ADB_TARGET",
        "PLEKTRA_QA_KEYMAP",
        "PLEKTRA_QA_KEYMAP_SOURCE",
        "PLEKTRA_QA_PACKAGE",
    ):
        monkeypatch.delenv(name, raising=False)
