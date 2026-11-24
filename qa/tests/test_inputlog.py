from plektra_qa.inputlog import (
    Geometry,
    Interpreter,
    describe_gesture,
    key_label,
    parse_event,
    parse_geometry,
)

LP = """add device 1: /dev/input/event6
  name:     "fts_ts"
    ABS (0003): ABS_MT_SLOT           : value 0, min 0, max 9, fuzz 0, flat 0, resolution 0
                ABS_MT_POSITION_X     : value 0, min 0, max 1080, fuzz 0, flat 0, resolution 0
                ABS_MT_POSITION_Y     : value 0, min 0, max 1200, fuzz 0, flat 0, resolution 0
add device 2: /dev/input/event4
  name:     "touchPad"
                ABS_MT_POSITION_X     : value 0, min 0, max 1079, fuzz 0, flat 0, resolution 0
                ABS_MT_POSITION_Y     : value 0, min 0, max 599, fuzz 0, flat 0, resolution 0
add device 3: /dev/input/event5
  name:     "TitanKey"
"""
SCREEN = "Physical size: 1080x1200\nOverride size: 1076x1200\n"


def ev(ts, dev, typ, code, value):
    return parse_event(f"[ {ts:12.6f}] /dev/input/event{dev}: {typ:<12} {code:<20} {value}")


def run(interp, lines):
    for now, e in lines:
        interp.feed(now, e)


def test_parse_event_and_geometry():
    e = ev(12.5, 5, "EV_KEY", "KEY_A", "DOWN")
    assert (e.dev, e.type, e.code, e.value) == ("/dev/input/event5", "EV_KEY", "KEY_A", "DOWN")
    assert parse_event("add device 1: /dev/input/event7") is None
    geo = parse_geometry(LP, SCREEN)
    assert geo.screen == (1076, 1200)
    assert geo.touchscreen == "/dev/input/event6"  # shape closest to the screen's
    assert geo.to_screen("/dev/input/event6", 540, 600) == (538, 600)
    assert geo.to_screen("/dev/input/event4", 5, 6) == (5, 6)  # the pad stays in its own units


def test_keys_show_the_combination_held_and_linger_after_release():
    i = Interpreter(Geometry())
    run(i, [
        (1.0, ev(1.0, 5, "EV_KEY", "KEY_LEFTSHIFT", "DOWN")),
        (1.2, ev(1.2, 5, "EV_KEY", "KEY_A", "DOWN")),
        (1.3, ev(1.3, 5, "EV_KEY", "KEY_A", "UP")),
        (1.4, ev(1.4, 5, "EV_KEY", "KEY_LEFTSHIFT", "UP")),
    ])
    shown = [(round(s.start, 2), round(s.end, 2), s.text) for s in i.key_timeline()]
    assert [x[2] for x in shown] == ["L-Shift", "L-Shift + A", "L-Shift"]
    assert shown[0][1] == 1.2 and shown[1][1] == 1.3  # replaced at once by the next text
    assert shown[2][1] >= 1.4 + 0.4 - 1e-9  # lingers after the last key lifts
    assert key_label("KEY_ENTER") == "Enter" and key_label("KEY_Q") == "Q"


def touch(i, dev, t0, points, slot=0):
    """Feed one finger: down at points[0], moves, up after the last point."""
    n = 0
    for k, (x, y) in enumerate(points):
        t = t0 + k * 0.05
        if k == 0:
            run(i, [(t, ev(t, dev, "EV_ABS", "ABS_MT_TRACKING_ID", "00000011"))])
        run(i, [
            (t, ev(t, dev, "EV_ABS", "ABS_MT_POSITION_X", f"{x:08x}")),
            (t, ev(t, dev, "EV_ABS", "ABS_MT_POSITION_Y", f"{y:08x}")),
            (t, ev(t, dev, "EV_SYN", "SYN_REPORT", "00000000")),
        ])
        n = k
    t = t0 + (n + 1) * 0.05
    run(i, [
        (t, ev(t, dev, "EV_ABS", "ABS_MT_TRACKING_ID", "ffffffff")),
        (t, ev(t, dev, "EV_SYN", "SYN_REPORT", "00000000")),
    ])


def test_a_short_still_touch_is_a_tap_and_a_long_one_a_long_press():
    i = Interpreter(parse_geometry(LP, "Physical size: 1080x1200"))
    touch(i, 6, 5.0, [(540, 600), (541, 600)])
    texts = [s.text for s in i.touch_timeline()]
    assert texts[0] == "TOUCH (540, 600)" and texts[-1] == "TAP (541, 600)"
    j = Interpreter(parse_geometry(LP, "Physical size: 1080x1200"))
    touch(j, 6, 5.0, [(540, 600)] * 12)
    assert j.touch_timeline()[-1].text.startswith("LONG PRESS (540, 600)")


def test_a_swipe_names_its_direction_and_endpoints_and_the_pad_is_labelled():
    i = Interpreter(parse_geometry(LP, "Physical size: 1080x1200"))
    touch(i, 6, 5.0, [(540, 900), (540, 700), (540, 300)])
    assert i.touch_timeline()[-1].text.startswith("SWIPE up (540, 900) to (540, 300)")
    j = Interpreter(parse_geometry(LP, "Physical size: 1080x1200"))
    touch(j, 4, 5.0, [(100, 100), (500, 100)])
    assert j.touch_timeline()[-1].text.startswith("PAD SWIPE right (100, 100) to (500, 100)")
    assert describe_gesture("", (0, 0), (30, 30), 0.1).startswith("SWIPE down-right")
