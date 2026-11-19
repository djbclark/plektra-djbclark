"""A cheap fingerprint of the screen, for telling one composer from another."""

from __future__ import annotations

import hashlib
import xml.etree.ElementTree as ElementTree
from typing import Any

# Cap the number of id/desc entries fed into the hash, and how many are shown.
_MAX_ENTRIES = 200
_SAMPLE = 20


def screen_signature(
    device: Any,
    package: str = "",
    activity: str = "",
    max_entries: int = _MAX_ENTRIES,
) -> dict[str, object]:
    """Package + activity + sorted nonempty resource-ids and content-descs.

    Tells Messages compose from Gmail compose from the home screen. Stores
    nothing; returns a short hash plus the first entries for debugging.
    """
    if not package or not activity:
        app = device.current_application()
        if not package:
            package = getattr(app, "applicationId", "") or ""
        if not activity:
            activity = getattr(app, "activity", "") or ""

    root = ElementTree.fromstring(device.dump_window_hierarchy().read())
    entries: set[str] = set()
    for node in root.iter():
        rid = node.attrib.get("resource-id", "")
        if rid:
            entries.add(f"id:{rid}")
        desc = node.attrib.get("content-desc", "")
        if desc:
            entries.add(f"desc:{desc}")

    ordered = sorted(entries)[:max_entries]
    digest = hashlib.sha256(
        "\x00".join([package, activity, *ordered]).encode("utf-8")
    ).hexdigest()[:16]
    return {
        "hash": digest,
        "package": package,
        "activity": activity,
        "sample": ordered[:_SAMPLE],
    }
