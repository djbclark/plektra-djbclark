"""At most three times the same action, unless the batch says why (djbclark, 2026-11-25)."""

import io
import json
from pathlib import Path

import pytest
from rich.console import Console
from typer.testing import CliRunner

from plektra_qa import cli, mcp_server, runlock
from plektra_qa.hitl_runner import (
    MAX_REASON_CHARS,
    MAX_SAME_ACTION,
    ChordTrial,
    RepeatError,
    action_text,
    check_repeats,
    load_suite,
    read_suite,
)
from plektra_qa.keys import resolve_keymap
from plektra_qa.start_gate import BAR_RECORD_CARD, StartGate, card_text

SUITES = Path(__file__).resolve().parents[1] / "suites"
PROFILE = {"PLEKTRA_QA_KEYMAP_SOURCE": "profile"}


def keymap():
    return resolve_keymap(env=PROFILE).keymap


def suite_file(tmp_path, tries: int, head: str = "", prompt: str = "try {n} of {total}: <Tab>, then type beta") -> Path:
    path = tmp_path / "again.yaml"
    rows = "".join(
        f'      - prompt: "{prompt.format(n=n, total=tries)}"\n        expect: beta\n' for n in range(1, tries + 1)
    )
    path.write_text(f"name: again\n{head}phases:\n  - name: p\n    trials:\n{rows}")
    return path


def tries(count: int, text: str = "Try {n} of {total}: type beta") -> list[ChordTrial]:
    return [ChordTrial(prompt=text.format(n=n, total=count), expect="beta", number=n) for n in range(1, count + 1)]


def test_the_limit_is_three():
    assert MAX_SAME_ACTION == 3


@pytest.mark.parametrize(
    "numbered",
    [
        "try 2 of 10: type beta",
        "Try 2. type beta",
        "Trial #3: Type beta",
        "attempt 4/10 - type beta",
        "Type beta (try 5 of 10)",
        "Type beta (6/10)",
        "Type beta, 7 of 10.",
        "Type beta. Try 8.",
        "8 of 10: type beta",
        "3. type beta",
        "#4 type beta",
        "Type beta (5)",
        "Second try: type beta",
        "Once more: type beta",
        "Again: type beta",
        "Versuch 2 von 10: type beta",
        "try two of ten: type beta",
    ],
)
def test_numbering_does_not_make_a_new_action(numbered):
    assert action_text(numbered).rstrip(".") == "type beta"


@pytest.mark.parametrize(
    "one, other",
    [
        ("Type 42", "Type 43"),
        ("Type beta", "Type Beta"),
        ("Type round 2", "Type round 3"),
        ("Type a, b.", "Type a b."),
        ("Press <Tab> 2 times", "Press <Tab> 3 times"),
        ("Type try again", "Type try"),
    ],
)
def test_different_actions_stay_different(one, other):
    assert action_text(one) != action_text(other)


def test_three_tries_pass_and_four_are_refused_with_the_numbers():
    assert check_repeats(tries(3), None, "s.yaml") == ""
    with pytest.raises(RepeatError, match=r"s\.yaml asks the person for the same action 4 times \(trials 1, 2, 3, 4:"):
        check_repeats(tries(4), None, "s.yaml")


@pytest.mark.parametrize("reason", ["", "   ", True, 5, ["a"]])
def test_an_empty_or_non_text_reason_does_not_unlock_it(reason):
    with pytest.raises(RepeatError, match="repeat_reason"):
        check_repeats(tries(4), reason, "s.yaml")


def test_a_reason_unlocks_it_and_is_returned_only_when_needed():
    assert check_repeats(tries(10), "  a timing race:\n it hits one in five ", "s") == "a timing race: it hits one in five"
    assert check_repeats(tries(3), "not needed", "s") == ""
    with pytest.raises(RepeatError, match=str(MAX_REASON_CHARS)):
        check_repeats(tries(4), "x" * (MAX_REASON_CHARS + 1), "s")


def test_splitting_into_phases_or_changing_only_the_screen_text_does_not_help():
    split = [ChordTrial(prompt="type beta", expect="beta", phase=f"p{n}") for n in range(4)]
    with pytest.raises(RepeatError):
        check_repeats(split, None, "s")
    said = [ChordTrial(prompt=f"{word}: beta", speak=f"Try {n}. Type beta.", expect="beta")
            for n, word in enumerate(["alpha", "bravo", "charlie", "delta"], start=1)]
    with pytest.raises(RepeatError):
        check_repeats(said, None, "s")


def test_four_different_actions_are_fine_even_with_one_generic_spoken_line():
    trials = [ChordTrial(prompt=f"type {word}", speak="Type the word.", expect=word) for word in "abcd"]
    assert check_repeats(trials, None, "s") == ""


def test_a_suite_with_key_tokens_is_refused_unless_it_says_why(tmp_path):
    with pytest.raises(ValueError, match="same action 4 times"):
        load_suite(suite_file(tmp_path, 4), keymap())
    assert len(load_suite(suite_file(tmp_path, 3), keymap())) == 3
    loaded = read_suite(suite_file(tmp_path, 4, head="repeat_reason: a rare race\n"), keymap())
    assert len(loaded.trials) == 4 and loaded.repeat_reason == "a rare race"


@pytest.mark.parametrize("path", sorted(SUITES.glob("*.yaml")), ids=lambda path: path.name)
def test_every_shipped_suite_keeps_to_the_limit_without_a_reason(path):
    assert read_suite(path, keymap()).repeat_reason == ""
    assert "of 10" not in path.read_text()


def test_run_refuses_the_suite_before_anything_touches_the_phone(tmp_path, monkeypatch):
    monkeypatch.setenv("ADB_TARGET", "serial")
    monkeypatch.setenv("PLEKTRA_QA_KEYMAP_SOURCE", "profile")

    def never(*_a, **_k):
        raise AssertionError("the phone was asked")

    monkeypatch.setattr(cli, "_keyboard_build", never)
    monkeypatch.setattr(runlock, "device_serial", never)
    done = CliRunner().invoke(cli.app, ["run", str(suite_file(tmp_path, 4)), "--no-record"])
    assert done.exit_code == 2 and "same action 4 times" in done.output


def test_an_mcp_batch_is_refused_without_taking_the_phone(monkeypatch):
    monkeypatch.setenv("PLEKTRA_QA_KEYMAP_SOURCE", "profile")
    monkeypatch.setattr(mcp_server, "_session", lambda: (None, None))
    monkeypatch.setattr(runlock, "acquire", lambda *_a: pytest.fail("took the lock"))
    batch = json.dumps([{"prompt": f"try {n} of 4: type beta", "expect": "beta"} for n in range(1, 5)])
    for reason in ("", "  "):
        out = json.loads(mcp_server.request_human_chord_batch(batch, repeat_reason=reason))
        assert "same action 4 times" in out["refused"]


class _Bar:
    def __init__(self):
        self.shown = []

    def show(self, text, buttons=()):
        self.shown.append((text, tuple(buttons)))

    def pressed(self):
        return None

    def wait(self, pressed=False):
        return None


@pytest.mark.parametrize("recording", [True, False])
def test_the_start_card_shows_the_reason_on_screen_and_terminal(recording):
    reason = "w" * MAX_REASON_CHARS
    bar, out, posted = _Bar(), io.StringIO(), []
    gate = StartGate("s", console=Console(file=out, width=2000), bar=bar, shell=lambda args: posted.append(args) or "",
                     terminal=lambda: None)
    gate.wait("Batch", "Pick up the phone.", confirmed=True, recording=recording, repeat_reason=reason)
    card = bar.shown[0][0]
    # how to start comes first, so a card the bar cuts never loses that or the recording notice
    assert card.startswith(BAR_RECORD_CARD if recording else "Done starts.")
    assert card.endswith(f"Repeats on purpose: {reason}.")
    assert len(card) <= 200  # the bar app cuts there
    assert (BAR_RECORD_CARD in card) is recording
    assert reason in out.getvalue()


def test_the_shade_start_card_has_the_reason_too_and_markup_is_printed_as_written():
    posted, out = [], io.StringIO()
    gate = StartGate("s", console=Console(file=out, width=2000), shell=lambda args: posted.append(list(args)) or "",
                     terminal=lambda: None)
    gate.wait("Batch", "Pick up.", confirmed=True, repeat_reason="seen [/once] only")
    assert "Repeats on purpose: seen [/once] only. Pick up." in posted[0][-1]
    assert "[/once]" in out.getvalue()
    assert "Repeats" not in card_text("t", "Pick up.", phone=True, terminal=False)
