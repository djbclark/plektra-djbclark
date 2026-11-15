from pathlib import Path

from plektra_qa.hitl_runner import (
    accuracy,
    load_suite,
    trial_matched,
    words_per_minute,
)

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = ROOT / "plektra_qa"


def test_suite_drops_null_overrides_and_keeps_phase_name():
    trials = load_suite(ROOT / "suites" / "smoke_chords.yaml")
    assert [trial.prompt for trial in trials][:2] == ["alpha: hello", "digits: 42"]
    assert trials[0].phase == "one_screen"
    assert trials[0].resource_id is None
    assert trials[0].sentinel == "\n"
    clipboard = trials[-1]
    assert clipboard.verify_mode == "clipboard"
    assert clipboard.expect == "beta"


def test_match_accepts_the_sentinel_suffix():
    assert trial_matched("hello", "hello", "\n")
    assert trial_matched("hello\n", "hello", "\n")
    assert not trial_matched("hello!", "hello", "\n")


def test_wpm_uses_the_first_input_to_complete_window():
    assert words_per_minute("hello", 10.0, 12.0) == 30.0
    assert words_per_minute("hello", None, 12.0) is None
    assert accuracy("hello", "hello") == 1.0


def test_package_never_types_through_firerpa_or_replaces_the_ime():
    banned = ("set_text(", "default_input_method", "force-stop", "am start -S")
    for path in PACKAGE.glob("*.py"):
        text = path.read_text()
        for token in banned:
            assert token not in text, f"{path.name} contains {token}"
