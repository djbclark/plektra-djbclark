"""Fast screen jumps and input-focus prep over FIRERPA."""

from __future__ import annotations

from typing import Any


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
    ) -> Any:
        if resource_id:
            element = self.device(resourceId=resource_id)
        elif text_hint:
            element = self.device(text=text_hint)
        elif self.device(focused=True).exists():
            element = self.device(focused=True)
        else:
            element = self.device(className=class_name)
        if not element.exists():
            raise RuntimeError("input field not found")
        info = element.info()
        if not info.focused:
            element.click()
        element.clear_text_field()
        remaining = element.get_text() or ""
        if remaining != "":
            raise RuntimeError(f"input field was not cleared: {remaining!r}")
        return element
