"""A second run on the same phone is refused by the operating system."""

import subprocess
import sys

import pytest

from plektra_qa import runlock


def test_second_holder_is_refused_and_named_then_release_frees_it(tmp_path):
    runlock.acquire("phone:1", tmp_path)
    try:
        code = (
            "import sys; from plektra_qa import runlock; from pathlib import Path\n"
            "try:\n    runlock.acquire('phone:1', Path(sys.argv[1]))\nexcept runlock.RunLockError as e:\n    print(e); sys.exit(3)\n"
        )
        other = subprocess.run([sys.executable, "-c", code, str(tmp_path)], capture_output=True, text=True)
        assert other.returncode == 3 and "pid" in other.stdout
        with pytest.raises(runlock.RunLockError):
            runlock.acquire("phone:1", tmp_path)  # same process twice
    finally:
        runlock.release("phone:1")
    runlock.acquire("phone:1", tmp_path)
    runlock.release("phone:1")


def test_a_different_phone_has_its_own_lock(tmp_path):
    runlock.acquire("a", tmp_path)
    runlock.acquire("b", tmp_path)
    runlock.release("a")
    runlock.release("b")
