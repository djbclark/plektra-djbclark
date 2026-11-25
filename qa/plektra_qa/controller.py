"""Fast screen jumps and input-focus prep over FIRERPA."""

from __future__ import annotations

from typing import Any
from xml.etree import ElementTree


def obj_info(info: Any) -> dict[str, object]:
    bounds = info.bounds
    return {
        "resourceId": info.resourceName,
        "text": info.text,
        "className": info.className,
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
    ) -> Any:
        if resource_id:
            element = self.device(resourceId=resource_id)
        elif text_hint:
            element = self.device(text=text_hint)
        elif self.device(focused=True).exists():
            element = self.device(focused=True)
        else:
            # Never grab "any" EditText: it may belong to another app (review finding 1).
            raise RuntimeError("input field not found: the trial names no field and none is focused")
        if not element.exists():
            raise RuntimeError("input field not found")
        info = element.info()
        if not info.focused:
            element.click()
        if clear:
            self._clear_to_resting(element, resource_id or class_name)
        return element

    def editable_fields(self) -> dict[str, str] | None:
        """Every text field on screen as resource id -> text; None when the screen cannot be read.

        An empty field reports its hint, as `get_text` does. Fields without an id, or sharing one, are
        left out: they cannot be addressed."""
        dump = getattr(self.device, "dump_window_hierarchy", None)
        if not callable(dump):
            return None
        try:
            root = ElementTree.fromstring(dump().read())
        except Exception:
            return None
        found: dict[str, list[str]] = {}
        for node in root.iter("node"):
            resource_id = node.get("resource-id") or ""
            if resource_id and (node.get("class") or "").endswith("EditText"):
                found.setdefault(resource_id, []).append(node.get("text") or "")
        return {resource_id: texts[0] for resource_id, texts in found.items() if len(texts) == 1}

    def restore_field(self, resource_id: str, text: str) -> bool:
        """Put one field back by clearing it: the host never types. True when it then reads `text`
        (also the hint an empty field reports); False when only typing could bring `text` back."""
        element = self.device(resourceId=resource_id)
        if not element.exists():
            return False
        if (element.get_text() or "") == text:
            return True
        element.clear_text_field()
        return not text or (element.get_text() or "") == text

    def visible_text(self, element: Any) -> str:
        """Field text, with a recorded empty-field hint treated as empty."""
        text = element.get_text() or ""
        if text and text in self._resting.values():
            return ""
        return text

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
