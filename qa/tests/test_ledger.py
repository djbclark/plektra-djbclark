"""Passed trials are remembered, so a rerun only does what is left."""

from types import SimpleNamespace

from plektra_qa import ledger as ledger_mod
from plektra_qa.ledger import Ledger, final_video, ime_build, trial_key


def _trial(prompt="a: hello", expect="hello", phase="one"):
    return SimpleNamespace(prompt=prompt, expect=expect, phase=phase, verify_mode="text", sentinel="\n")


def _ledger(tmp_path, build="b1"):
    return Ledger("smoke", build, root=tmp_path / "state", clips=tmp_path / "clips")


def test_a_passed_trial_is_not_pending_but_an_edited_one_is(tmp_path):
    first, second = _trial(), _trial("b: 42", "42")
    ledger = _ledger(tmp_path)
    ledger.record(first, "hello", None)
    assert ledger.pending([first, second]) == [second]
    assert trial_key(first) != trial_key(_trial(expect="Hello"))
    assert _ledger(tmp_path).passed(first)  # it survives a new process


def test_a_new_keyboard_build_retests_everything(tmp_path):
    first = _trial()
    _ledger(tmp_path, "b1").record(first, "hello", None)
    assert _ledger(tmp_path, "b2").pending([first]) == [first]


def test_final_video_joins_passed_clips_in_suite_order_and_names_the_rest(tmp_path):
    a, b, c = _trial("a"), _trial("b"), _trial("c")
    ledger = _ledger(tmp_path)
    for trial in (a, c):
        clip = ledger.clip_path(trial)
        clip.parent.mkdir(parents=True, exist_ok=True)
        clip.write_bytes(b"x")
        ledger.record(trial, "ok", clip)
    ledger.record(b, "ok", None)  # passed without a clip: no video of it
    calls = []

    def fake_run(cmd, **_kwargs):
        calls.append(cmd)
        (tmp_path / "final.mp4").write_bytes(b"v")
        return SimpleNamespace(returncode=0)

    written, missing = final_video(ledger, [a, b, c], tmp_path / "final.mp4", fake_run)
    assert written and missing == [b]
    listing = calls[0][calls[0].index("-i") + 1]
    assert listing.endswith("clips.txt")


def test_ime_build_reads_the_package_of_the_current_keyboard():
    def fake_run(cmd, **_kwargs):
        if "input_method" in cmd:
            return SimpleNamespace(stdout="      mCurId=rocks.pkb.plektra.djb/.Svc\n")
        return SimpleNamespace(stdout="    versionCode=9 minSdk=26\n    versionName=1.2\n    lastUpdateTime=2026-11-24 10:00:00\n")

    build = ime_build("serial", fake_run)
    assert build.startswith("rocks.pkb.plektra.djb ") and "lastUpdateTime=2026-11-24 10:00:00" in build
    assert ledger_mod.ime_build("serial", lambda *a, **k: SimpleNamespace(stdout="")) == ""
