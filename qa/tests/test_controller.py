"""Fake-device tests for controller targeting, reading, and the screen hash."""

import io
import xml.etree.ElementTree as ElementTree
from types import SimpleNamespace

import pytest

from plektra_qa.controller import ScreenController, is_input_candidate, obj_info
from plektra_qa.mcp_server import _node_row
from plektra_qa.screen import screen_signature


def _info(**kwargs) -> SimpleNamespace:
    values = dict(
        resourceName="",
        text="",
        className="",
        contentDescription="",
        hint="",
        focusable=False,
        focused=False,
        password=False,
        bounds=SimpleNamespace(top=0, left=0, bottom=100, right=100),
    )
    values.update(kwargs)
    return SimpleNamespace(**values)


class _Missing:
    def exists(self) -> bool:
        return False


class _FakeElement:
    def __init__(self, info: SimpleNamespace) -> None:
        self._info = info
        self._text = info.text
        self._hint = info.hint
        self.clicked = False
        self.cleared = False

    def exists(self) -> bool:
        return True

    def info(self) -> SimpleNamespace:
        return self._info

    def click(self) -> None:
        self.clicked = True
        self._info.focused = True

    def clear_text_field(self) -> None:
        self.cleared = True
        self._text = ""

    def get_text(self) -> str:
        return self._text or self._hint


class _TargetDevice:
    """Routes selectors to given elements and replays a dump sequence."""

    def __init__(self, elements: dict, dumps: list[str]) -> None:
        self.elements = elements
        self.dumps = dumps
        self.dump_calls = 0
        self.selector_calls: list[dict] = []

    def __call__(self, **kwargs):
        self.selector_calls.append(kwargs)
        for key, value in kwargs.items():
            if key == "focused" and value is True:
                return self.elements.get(("focused", True), _Missing())
            if key in {"resourceId", "description", "descriptionContains", "text", "textContains", "className"}:
                return self.elements.get((key, value), _Missing())
        return _Missing()

    def dump_window_hierarchy(self) -> io.BytesIO:
        index = min(self.dump_calls, len(self.dumps) - 1)
        self.dump_calls += 1
        return io.BytesIO(self.dumps[index].encode("utf-8"))


class _ReadField:
    def __init__(self, text: str, *, hint: str = "", content_desc: str = "",
                 password: bool = False, resource_name: str = "") -> None:
        self.text = text
        self.hint = hint
        self.content_desc = content_desc
        self.password = password
        self.resource_name = resource_name

    def get_text(self) -> str:
        return self.text

    def info(self) -> SimpleNamespace:
        return _info(
            text=self.text,
            hint=self.hint,
            contentDescription=self.content_desc,
            password=self.password,
            resourceName=self.resource_name,
        )

    def screenshot(self) -> io.BytesIO:
        return io.BytesIO(b"fake-node-image")


class _DumpDevice:
    def __init__(self, dumps: list[str]) -> None:
        self.dumps = dumps
        self.dump_calls = 0

    def dump_window_hierarchy(self) -> io.BytesIO:
        index = min(self.dump_calls, len(self.dumps) - 1)
        self.dump_calls += 1
        return io.BytesIO(self.dumps[index].encode("utf-8"))


# --- predicate ------------------------------------------------------------

def test_input_candidate_predicate():
    assert is_input_candidate(class_name="android.widget.EditText")
    assert not is_input_candidate(class_name="android.widget.AutoCompleteTextView")
    assert is_input_candidate(class_name="android.view.View", focusable=True, hint="Title")
    assert is_input_candidate(class_name="android.view.View", focusable=True, text="hello")
    assert is_input_candidate(class_name="android.view.View", content_desc="Compose:Draft:Send")
    assert not is_input_candidate(class_name="android.view.View")
    assert not is_input_candidate(class_name="android.widget.Button", focusable=True)
    assert not is_input_candidate(class_name="android.widget.Button", content_desc="Send")


# --- obj_info -------------------------------------------------------------

def test_obj_info_adds_content_description_and_hint():
    info = _info(
        resourceName="field",
        text="hello",
        className="android.widget.EditText",
        contentDescription="Name field",
        hint="Your name",
    )
    row = obj_info(info)
    assert row["resourceId"] == "field"
    assert row["text"] == "hello"
    assert row["className"] == "android.widget.EditText"
    assert row["contentDescription"] == "Name field"
    assert row["hint"] == "Your name"
    assert row["bounds"]["top"] == 0
    assert row["focused"] is False


def test_obj_info_absent_attributes_become_empty_strings():
    info = SimpleNamespace(
        resourceName="",
        text="",
        className="",
        bounds=SimpleNamespace(top=0, left=0, bottom=10, right=10),
        focused=False,
    )
    row = obj_info(info)
    assert row["contentDescription"] == ""
    assert row["hint"] == ""


# --- targeting ------------------------------------------------------------

def test_focus_and_clear_picks_composer_by_content_desc():
    composer = _FakeElement(
        _info(className="android.view.View", contentDescription="Compose:Draft:Send")
    )
    device = _TargetDevice({("description", "Compose:Draft:Send"): composer}, dumps=["<hierarchy/>"])
    controller = ScreenController(device)

    got = controller.focus_and_clear(description="Compose:Draft:Send")

    assert got is composer
    assert composer.clicked
    assert composer.cleared
    assert all("className" not in call for call in device.selector_calls)


def test_focus_and_clear_drills_webview_body_by_resource_id_and_dump_twice():
    body = _FakeElement(
        _info(
            className="android.webkit.WebView",
            resourceName="wc_body",
            bounds=SimpleNamespace(top=0, left=0, bottom=200, right=1080),
        )
    )
    edit = _FakeElement(
        _info(
            className="android.widget.EditText",
            resourceName="wc_body_edit",
            focusable=True,
        )
    )
    # The nested EditText only appears on the second dump (lazy WebView tree).
    dump_first = (
        '<hierarchy>'
        '<node resource-id="wc_body" class="android.webkit.WebView" bounds="[0,0][1080,200]"/>'
        "</hierarchy>"
    )
    dump_second = (
        '<hierarchy>'
        '<node resource-id="wc_body" class="android.webkit.WebView" bounds="[0,0][1080,200]">'
        '<node resource-id="wc_body_edit" class="android.widget.EditText" '
        'focusable="true" bounds="[10,10][1000,190]"/>'
        "</node>"
        "</hierarchy>"
    )
    device = _TargetDevice(
        {("resourceId", "wc_body"): body, ("resourceId", "wc_body_edit"): edit},
        dumps=[dump_first, dump_second],
    )
    controller = ScreenController(device)

    got = controller.focus_and_clear(resource_id="wc_body")

    assert got is edit
    assert edit.clicked
    assert device.dump_calls >= 2
    assert all("className" not in call for call in device.selector_calls)


# --- reading --------------------------------------------------------------

def test_visible_text_returns_text_not_content_desc():
    field = _ReadField("Hello", content_desc="Compose:Draft:Send")
    controller = ScreenController(_DumpDevice(["<hierarchy/>"]))
    assert controller.visible_text(field) == "Hello"


def test_visible_text_treats_text_equal_to_hint_as_empty():
    field = _ReadField("Title", hint="Title")
    controller = ScreenController(_DumpDevice(["<hierarchy/>"]))
    assert controller.visible_text(field) == ""


def test_visible_text_never_ocrs_a_password_node(monkeypatch):
    def boom(_image: bytes) -> str:
        raise AssertionError("password node must not be OCR'd")

    monkeypatch.setattr("plektra_qa.controller.ocr_image", boom)
    field = _ReadField("", password=True, resource_name="secret")
    dump = (
        '<hierarchy>'
        '<node resource-id="secret" class="android.widget.EditText" password="true" '
        'bounds="[0,0][100,50]"/>'
        "</hierarchy>"
    )
    controller = ScreenController(_DumpDevice([dump]))
    assert controller.visible_text(field) == ""


def test_visible_text_ocrs_a_non_password_node(monkeypatch):
    monkeypatch.setattr("plektra_qa.controller.ocr_image", lambda image: "OCR-TEXT")
    field = _ReadField("", resource_name="plain")
    dump = (
        '<hierarchy>'
        '<node resource-id="plain" class="android.widget.EditText" bounds="[0,0][100,50]"/>'
        "</hierarchy>"
    )
    controller = ScreenController(_DumpDevice([dump]))
    assert controller.visible_text(field) == "OCR-TEXT"


# --- screen signature -----------------------------------------------------

def test_screen_signature_differs_for_different_id_desc_sets():
    first = (
        '<hierarchy>'
        '<node resource-id="a:id1" content-desc="Compose:Draft:Send"/>'
        '<node content-desc="To"/>'
        "</hierarchy>"
    )
    second = (
        '<hierarchy>'
        '<node resource-id="b:id2" content-desc="Subject"/>'
        "</hierarchy>"
    )
    app = SimpleNamespace(applicationId="com.android.mms", activity=".ComposeActivity")

    class Device:
        def __init__(self, xml: str) -> None:
            self.xml = xml

        def dump_window_hierarchy(self) -> io.BytesIO:
            return io.BytesIO(self.xml.encode("utf-8"))

        def current_application(self):
            return app

    one = screen_signature(Device(first))
    two = screen_signature(Device(second))

    assert one["hash"] != two["hash"]
    assert one["package"] == "com.android.mms"
    assert one["activity"] == ".ComposeActivity"
    assert isinstance(one["sample"], list)
    assert len(one["sample"]) <= 20


def test_screen_signature_is_stable_for_reordered_input():
    unordered = (
        '<hierarchy>'
        '<node content-desc="To"/>'
        '<node resource-id="a:id1" content-desc="Compose:Draft:Send"/>'
        "</hierarchy>"
    )
    ordered = (
        '<hierarchy>'
        '<node resource-id="a:id1" content-desc="Compose:Draft:Send"/>'
        '<node content-desc="To"/>'
        "</hierarchy>"
    )

    class Device:
        def __init__(self, xml: str) -> None:
            self.xml = xml

        def dump_window_hierarchy(self) -> io.BytesIO:
            return io.BytesIO(self.xml.encode("utf-8"))

        def current_application(self):
            return SimpleNamespace(applicationId="com.android.mms", activity=".ComposeActivity")

    assert screen_signature(Device(unordered))["hash"] == screen_signature(Device(ordered))["hash"]


# --- mcp _node_row --------------------------------------------------------

def test_node_row_includes_content_description_and_hint():
    node = ElementTree.fromstring(
        '<node resource-id="x" text="hi" class="android.widget.EditText" '
        'content-desc="Name field" hint="Your name" bounds="[0,0][10,10]" focused="true"/>'
    )
    row = _node_row(node)
    assert row["resourceId"] == "x"
    assert row["text"] == "hi"
    assert row["contentDescription"] == "Name field"
    assert row["hint"] == "Your name"
    assert row["focused"] is True


def test_node_row_omits_hint_when_absent():
    node = ElementTree.fromstring(
        '<node resource-id="x" class="android.view.View" '
        'content-desc="Compose:Draft:Send" bounds="[0,0][10,10]"/>'
    )
    row = _node_row(node)
    assert row["contentDescription"] == "Compose:Draft:Send"
    assert "hint" not in row
