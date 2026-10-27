"""Legacy-form detection, in the fixed order of the upcaster contract.

Detection reads member names only, so it needs no schema validator. The registry
decides which entry a recognised row maps to.
"""

from __future__ import annotations

from dataclasses import dataclass
from functools import lru_cache
from typing import Any, Dict, List, Mapping, Optional, Tuple

from spec_kitty_events import producer_contract as pc

from ._types import IssueCode

__all__ = ["Detection", "detect"]

# The members that make a row a 3.0.0 transport envelope (shape A). A CLI lifecycle
# envelope has ``aggregate_id`` too, but never ``node_id`` or ``build_id``.
_TRANSPORT_MARKERS = frozenset({"event_type", "aggregate_id", "payload", "node_id", "build_id"})


@dataclass(frozen=True)
class Detection:
    """The detected form, its registry entry (if any) and the issue code (if any)."""

    form: str
    entry: Optional[Dict[str, Any]] = None
    code: Optional[str] = None


@dataclass(frozen=True)
class _Indices:
    transport: Dict[str, Dict[str, Any]]
    envelope: Dict[Tuple[str, str], Dict[str, Any]]
    flat_constants: List[Tuple[Dict[str, Any], Dict[str, Any]]]
    lane: Dict[str, Any]


@lru_cache(maxsize=1)
def _indices() -> _Indices:
    registry = pc.load_registry()
    shapes = registry["legacy_shapes"]
    transport: Dict[str, Dict[str, Any]] = {}
    envelope: Dict[Tuple[str, str], Dict[str, Any]] = {}
    flat_constants: List[Tuple[Dict[str, Any], Dict[str, Any]]] = []
    lane: Optional[Dict[str, Any]] = None
    for entry in registry["types"]:
        if entry["transport"] is not None:
            transport[entry["transport"]["event_type"]] = entry
        legacy = entry["legacy"]
        if legacy is None:
            continue
        if shapes[legacy["shape"]]["form"] == "payload_envelope":
            envelope[(legacy["shape"], legacy["discriminator_value"])] = entry
        elif legacy["constants"]:
            flat_constants.append((legacy["constants"], entry))
        else:
            lane = entry
    if lane is None:  # pragma: no cover - the registry test pins the lane entry
        raise pc.RegistryError("the registry has no flat entry without constants (the lane row)")
    return _Indices(transport, envelope, flat_constants, lane)


def _envelope_shape(row: Mapping[str, Any]) -> Optional[str]:
    if "row_version" in row:
        return "journal_envelope"
    if "aggregate_type" in row:
        return "lifecycle_envelope"
    if "at" in row:
        return "decision_envelope"
    return None


def detect(row: Any) -> Detection:
    """Detect the legacy form of one parsed row (contract "Detection order")."""
    if not isinstance(row, dict):
        return Detection("none", code=IssueCode.NOT_AN_OBJECT)
    if "specversion" in row:
        return Detection("cloudevent", code=IssueCode.ALREADY_CLOUDEVENT)
    indices = _indices()
    if _TRANSPORT_MARKERS <= row.keys():
        entry = (
            indices.transport.get(row["event_type"]) if isinstance(row["event_type"], str) else None
        )
        if entry is None:
            return Detection("transport_envelope", code=IssueCode.UNREGISTERED_TYPE)
        return Detection("transport_envelope", entry)
    if "event_type" in row and "payload" in row:
        shape = _envelope_shape(row)
        if shape is None:
            return Detection("none", code=IssueCode.UNRECOGNISED_ROW)
        discriminator = row["event_type"]
        entry = (
            indices.envelope.get((shape, discriminator)) if isinstance(discriminator, str) else None
        )
        if entry is None:
            return Detection(shape, code=IssueCode.UNREGISTERED_TYPE)
        return Detection(shape, entry)
    for constants, entry in indices.flat_constants:
        if all(row.get(key) == value for key, value in constants.items()):
            return Detection("flat", entry)
    if "to_lane" in row and "wp_id" in row and "event_type" not in row:
        return Detection("flat", indices.lane)
    return Detection("none", code=IssueCode.UNRECOGNISED_ROW)
