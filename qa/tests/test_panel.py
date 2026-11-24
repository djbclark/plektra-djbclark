from pathlib import Path

from PIL import Image

from plektra_qa.overlay import PANEL_W, panel_geometry, panel_lines, srt, write_panel

NOTES = [
    (1.0, "trial", "trial 1: alpha: hello"),
    (2.5, "voice", "voice: Type hello"),
    (6.0, "pass", "trial 1 pass: 'hello'"),
    (7.0, "fail", "trial 2 FAIL: expected 'Hi', saw 'hi' diff"),
]


def test_panel_keeps_the_newest_lines_at_the_bottom():
    lines = panel_lines(NOTES, 4, 40, 3)
    assert len(lines) == 3
    assert lines[-1][0] == "fail"
    assert panel_lines(NOTES, 0, 40, 3) == []


def test_panel_wraps_long_lines():
    long = [(0.0, "info", "word " * 40)]
    assert len(panel_lines(long, 1, 30, 20)) > 1


def test_write_panel_lists_one_png_per_state(tmp_path: Path):
    listing = write_panel(NOTES, 10.0, PANEL_W, 400, tmp_path)
    text = listing.read_text()
    assert text.count("duration") == len(NOTES) + 1
    assert text.rstrip().splitlines()[-1].startswith("file ")
    first = sorted(tmp_path.glob("panel-*.png"))[0]
    assert Image.open(first).size == (PANEL_W, 400)


def test_srt_numbers_cues_and_caps_their_length():
    out = srt(NOTES, 12.0)
    assert out.startswith("1\n00:00:01,000 --> 00:00:02,500\ntrial 1: alpha: hello")
    assert "4\n00:00:07,000 --> 00:00:12,000\n" in out


def test_geometry_gives_columns_and_rows():
    cols, rows = panel_geometry(PANEL_W, 600)
    assert cols > 20 and rows > 5
