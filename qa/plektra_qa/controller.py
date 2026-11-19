"""Fast screen jumps and input-focus prep over FIRERPA."""

from __future__ import annotations

import io
import xml.etree.ElementTree as ElementTree
from typing import Any

# Content-desc labels that name a text composer even when the node is not an
# EditText. Compose (Messages) and WebView (Gmail) composers are plain Views;
# the send button / draft label is their content-desc.
KNOWN_COMPOSER_LABELS = frozenset(
    {
        "Compose:Draft:Send",
        "Compose:Draft:SendMessage",
        "Compose",
        "Compose email",
        "Compose message",
    }
)


def is_input_candidate(
    class_name: str = "",
    focusable: bool = False,
    hint: str = "",
    text: str = "",
    content_desc: str = "",
) -> bool:
    """Whether a node is somewhere the harness types.

    True when the class is an EditText, when the node is focusable and has a
    hint or nonempty text, or when its content-desc names a known composer
    label.
    """
    if "EditText" in (class_name or ""):
        return True
    if focusable and ((hint or "") or (text or "")):
        return True
    if (content_desc or "") in KNOWN_COMPOSER_LABELS:
        return True
    return False


def _node_is_input_candidate(node: ElementTree.Element) -> bool:
    return is_input_candidate(
        class_name=node.attrib.get("class", ""),
        focusable=node.attrib.get("focusable", "false") == "true",
        hint=node.attrib.get("hint", ""),
        text=node.attrib.get("text", ""),
        content_desc=node.attrib.get("content-desc", ""),
    )


def _info_is_input_candidate(info: Any) -> bool:
    return is_input_candidate(
        class_name=getattr(info, "className", "") or "",
        focusable=bool(getattr(info, "focusable", False)),
        hint=getattr(info, "hint", "") or "",
        text=getattr(info, "text", "") or "",
        content_desc=getattr(info, "contentDescription", "") or "",
    )


def ocr_image(image: bytes) -> str:
    """Best-effort OCR, imported lazily so the harness pays nothing when it is
    not reached. Returns '' when no OCR backend is installed."""
    try:
        import pytesseract  # type: ignore
        from PIL import Image  # type: ignore
    except ImportError:
        return ""
    try:
        return (pytesseract.image_to_string(Image.open(io.BytesIO(image))) or "").strip()
    except Exception:
        return ""


def obj_info(info: Any) -> dict[str, object]:
    bounds = info.bounds
    return {
        "resourceId": info.resourceName,
        "text": info.text,
        "className": info.className,
        "contentDescription": getattr(info, "contentDescription", "") or "",
        "hint": getattr(info, "hint", "") or "",
        "bounds": {
            "top": bounds.top,
            "left": bounds.left,
            "bottom": bounds.bottom,
            "right": bounds.right,
        },
        "focused": bool(info.focused),
    }


class ScreenController:
    def __init__(self, device: Any) -> None:
        self.device = device
        # Text that clear_text_field leaves in an empty field. Android reports
        # the hint ("Title", "Start writing…") as the field text.
        self._resting: dict[str, str] = {}

    def teleport_to_activity(
        self,
        package: str,
        activity: str,
        action: str | None = None,
        data_uri: str | None = None,
        extras: dict[str, object] | None = None,
    ) -> object:
        request: dict[str, object] = {
            "package": package,
            "component": f"{package}/{activity}",
            "extras": extras or {},
        }
        if action is not None:
            request["action"] = action
        if data_uri is not None:
            request["data"] = data_uri
        return self.device.start_activity(**request)

    def focus_and_clear(
        self,
        resource_id: str | None = None,
        text_hint: str | None = None,
        class_name: str = "android.widget.EditText",
        clear: bool = True,
        description: str | None = None,
        description_contains: str | None = None,
    ) -> Any:
        element = self._locate(resource_id, text_hint, description, description_contains)
        if element is None:
            raise RuntimeError("input field not found")
        element = self._drill_to_input(element)
        if not element.exists():
            raise RuntimeError("input field not found")
        info = element.info()
        if not info.focused:
            element.click()
        if clear:
            key = resource_id or description or description_contains or text_hint or ""
            if not key:
                key = self._element_key(info)
            self._clear_to_resting(element, key)
        return element

    def _locate(self, resource_id, text_hint, description, description_contains):
        # a. resource_id (suite-provided, trusted).
        if resource_id:
            element = self.device(resourceId=resource_id)
            if element.exists():
                return element

        # b. content-desc (exact, then contains), kept to input candidates.
        if description:
            element = self.device(description=description)
            if element.exists() and _info_is_input_candidate(element.info()):
                return element
        if description_contains:
            element = self.device(descriptionContains=description_contains)
            if element.exists() and _info_is_input_candidate(element.info()):
                return element

        # c. text / textContains for a visible label or already-typed value.
        if text_hint:
            element = self.device(text=text_hint)
            if element.exists() and _info_is_input_candidate(element.info()):
                return element
            element = self.device(textContains=text_hint)
            if element.exists() and _info_is_input_candidate(element.info()):
                return element

        # d. focused=True.
        element = self.device(focused=True)
        if element.exists():
            return element

        # e. local xpath-style scan of the dump XML as the final fallback.
        return self._locate_in_dump(text_hint, description, description_contains)

    def _locate_in_dump(self, text_hint, description, description_contains):
        root = self._dump_twice()
        if root is None:
            return None
        candidates = [node for node in root.iter() if _node_is_input_candidate(node)]
        if not candidates:
            return None
        for node in candidates:
            desc = node.attrib.get("content-desc", "")
            text = node.attrib.get("text", "")
            if description and (desc == description or description in desc):
                return self._element_from_node(node)
            if description_contains and description_contains in desc:
                return self._element_from_node(node)
            if text_hint and (text == text_hint or text_hint in text):
                return self._element_from_node(node)
        return self._element_from_node(candidates[0])

    def _drill_to_input(self, element: Any) -> Any:
        """Return the element when it is an input, else a nested input.

        Compose and WebView composers are plain containers; their inner input is
        only visible after the lazy tree is materialized (dump twice).
        """
        info = element.info()
        class_name = getattr(info, "className", "") or ""
        if not class_name or _info_is_input_candidate(info):
            return element
        node = self._find_nested_input_node(info)
        if node is None:
            return element
        return self._element_from_node(node)

    def _find_nested_input_node(self, info: Any) -> ElementTree.Element | None:
        root = self._dump_twice()
        if root is None:
            return None
        anchor = self._node_for_element(root, info)
        if anchor is None:
            return None
        for node in anchor.iter():
            if node is anchor:
                continue
            if _node_is_input_candidate(node):
                return node
        return None

    def _element_from_node(self, node: ElementTree.Element) -> Any:
        rid = node.attrib.get("resource-id", "")
        if rid:
            return self.device(resourceId=rid)
        text = node.attrib.get("text", "")
        if text:
            return self.device(text=text)
        desc = node.attrib.get("content-desc", "")
        if desc:
            return self.device(description=desc)
        return self.device(className=node.attrib.get("class", ""))

    def _dump_twice(self) -> ElementTree.Element | None:
        dump = getattr(self.device, "dump_window_hierarchy", None)
        if dump is None:
            return None
        root: ElementTree.Element | None = None
        for _ in range(2):
            root = ElementTree.fromstring(dump().read())
        return root

    def _node_for_element(
        self, root: ElementTree.Element, info: Any
    ) -> ElementTree.Element | None:
        rid = getattr(info, "resourceName", "") or ""
        if rid:
            for node in root.iter():
                if node.attrib.get("resource-id", "") == rid:
                    return node
        bounds = getattr(info, "bounds", None)
        if bounds is not None:
            target = f"[{bounds.left},{bounds.top}][{bounds.right},{bounds.bottom}]"
            for node in root.iter():
                if node.attrib.get("bounds", "") == target:
                    return node
        return None

    @staticmethod
    def _element_key(info: Any) -> str:
        return (
            getattr(info, "resourceName", "")
            or getattr(info, "contentDescription", "")
            or getattr(info, "className", "")
            or "input"
        )

    def visible_text(self, element: Any) -> str:
        """Field value (text), never the content-desc name.

        A hint (the field's own hint or a recorded resting hint) is empty. An
        actually-empty read re-reads after materializing the lazy WebView tree,
        then falls back to OCR of the node bounds (never a password node).
        """
        raw = element.get_text() or ""
        if raw:
            return self._typed_value(raw, element)
        root = self._dump_twice()
        raw = element.get_text() or ""
        if raw:
            return self._typed_value(raw, element)
        return self._ocr_fallback(element, root)

    def _typed_value(self, raw: str, element: Any) -> str:
        if raw == self._hint_for(element) or raw in self._resting.values():
            return ""
        return raw

    @staticmethod
    def _hint_for(element: Any) -> str:
        return getattr(element.info(), "hint", "") or ""

    def _ocr_fallback(self, element: Any, root: ElementTree.Element | None) -> str:
        info = element.info()
        if root is not None:
            node = self._node_for_element(root, info)
            if node is not None and node.attrib.get("password", "false") == "true":
                return ""
        if getattr(info, "password", False):
            return ""
        image = self._element_crop(element)
        if image is None:
            return ""
        return ocr_image(image)

    def _element_crop(self, element: Any) -> bytes | None:
        screenshot = getattr(element, "screenshot", None)
        if screenshot is not None:
            try:
                return screenshot().read()
            except Exception:
                return None
        try:
            from PIL import Image  # type: ignore
        except ImportError:
            return None
        try:
            screen = self.device.screenshot().read()
            info = element.info()
            bounds = info.bounds
            image = Image.open(io.BytesIO(screen))
            cropped = image.crop((bounds.left, bounds.top, bounds.right, bounds.bottom))
            buf = io.BytesIO()
            cropped.save(buf, format="PNG")
            return buf.getvalue()
        except Exception:
            return None

    def _clear_to_resting(self, element: Any, key: str) -> None:
        before = element.get_text() or ""
        element.clear_text_field()
        after = element.get_text() or ""
        known = self._resting.get(key)
        if after and after != known and after != before:
            element.clear_text_field()
            stable = element.get_text() or ""
            if stable != after:
                raise RuntimeError(f"input field was not cleared: {stable!r}")
            self._resting[key] = stable
            return
        if after == "" or after == known or (known is None and after == before):
            if after:
                self._resting[key] = after
            return
        raise RuntimeError(f"input field was not cleared: {after!r}")
