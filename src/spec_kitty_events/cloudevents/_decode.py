"""Decode a lane or annotation CloudEvent back to the status-diary row the reducer reads.

The inverse of the shape-B mapping (design section 4): ``id`` to ``event_id``, ``time``
to ``at``, ``subject`` to ``wp_id``, the Mission attributes to ``mission_id`` and
``mission_slug``, ``actor`` (or the whole ``data.actorbinding``) to ``actor``,
``executionmode`` to ``execution_mode``, and the remaining ``data`` members as they are.
The reducer's folding is unchanged by ADR 0001: it reads this row exactly as it reads
the legacy one (design section 9, "equal reduced state").
"""

from __future__ import annotations

import copy
from typing import Any, Dict

__all__ = ["DIARY_TYPES", "diary_row"]

_LANE = "ai.spec-kitty.lane.transitioned.v1"
_ANNOTATION = "ai.spec-kitty.wp.innerstate.changed.v1"
#: The CloudEvent types that are status-diary rows.
DIARY_TYPES = (_LANE, _ANNOTATION)


def diary_row(event: Dict[str, Any]) -> Dict[str, Any]:
    """Return the status-diary row of a lane or annotation CloudEvent.

    Raises:
        ValueError: If the event is not of a status-diary type.
    """
    kind = event.get("type")
    if kind not in DIARY_TYPES:
        raise ValueError(f"not a status-diary CloudEvent type: {kind!r}")
    data: Dict[str, Any] = copy.deepcopy(event.get("data", {}))
    binding = data.pop("actorbinding", None)
    row: Dict[str, Any] = {"event_id": event["id"], "at": event["time"], "wp_id": event["subject"]}
    actor = binding if binding is not None else event.get("actor")
    if actor is not None:
        row["actor"] = actor
    if kind == _ANNOTATION:
        row["kind"] = "annotation"
    else:
        if "missionslug" in event:
            row["mission_slug"] = event["missionslug"]
        if "missionid" in event:
            row["mission_id"] = event["missionid"]
        if "executionmode" in event:
            row["execution_mode"] = event["executionmode"]
    row.update(data)
    return row
