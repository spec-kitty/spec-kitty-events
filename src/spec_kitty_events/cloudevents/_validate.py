"""Validate a CloudEvents row against an envelope profile and its type's data schema.

Non-normative: nothing in the package calls this on a live path, and
``validate_strict_envelope`` is unchanged until the ADR 0001 cutover release. Needs the
optional ``jsonschema`` dependency (the ``conformance`` extra).
"""

from __future__ import annotations

from dataclasses import dataclass
from functools import lru_cache
from typing import Any, List

from spec_kitty_events import producer_contract as pc

__all__ = ["ValidationIssue", "validate_cloudevent"]

_MISSION_SOURCE = "/spec-kitty/missions/"


@dataclass(frozen=True)
class ValidationIssue:
    """One reason a row is not valid: a code, the JSON pointer it is at, and a message.

    Codes: ``NOT_AN_OBJECT``, ``UNREGISTERED_TYPE``, ``SCHEMA_VIOLATION`` (with the
    failing JSON Schema keyword in ``keyword``) and ``SOURCE_MISMATCH`` (``missionid`` is
    not the id in ``source``, a rule JSON Schema cannot state).
    """

    code: str
    path: str
    message: str
    keyword: str = ""


@lru_cache(maxsize=None)
def _validator(type_name: str, profile: str) -> Any:
    try:
        import jsonschema  # type: ignore[import-untyped]
    except ImportError as exc:  # pragma: no cover - the conformance extra is installed in CI
        raise ImportError(
            "validate_cloudevent needs jsonschema: pip install spec-kitty-events[conformance]"
        ) from exc
    schema = pc.row_schema(type_name, profile=profile)
    return jsonschema.Draft202012Validator(schema)


def validate_cloudevent(row: Any, *, profile: str = "read") -> List[ValidationIssue]:
    """Return every reason the row is not a valid CloudEvent of its registered type.

    An empty list means valid. ``profile`` is ``read`` (rows as a reader meets them,
    including upcast legacy rows) or ``write`` (rows a producer writes).
    """
    if profile not in pc.PROFILES:
        raise ValueError(f"profile must be one of {pc.PROFILES}, not {profile!r}")
    if not isinstance(row, dict):
        return [ValidationIssue("NOT_AN_OBJECT", "", "the row is not a JSON object")]
    kind = row.get("type")
    if not isinstance(kind, str) or kind not in pc.registered_types():
        return [ValidationIssue("UNREGISTERED_TYPE", "/type", f"type is not registered: {kind!r}")]
    issues = [
        ValidationIssue(
            "SCHEMA_VIOLATION",
            "".join(f"/{part}" for part in error.absolute_path),  # JSON Pointer; root is ""
            error.message,
            str(error.validator),
        )
        for error in sorted(
            _validator(kind, profile).iter_errors(row),
            key=lambda e: list(map(str, e.absolute_path)),
        )
    ]
    source, mission = row.get("source"), row.get("missionid")
    if isinstance(source, str) and source.startswith(_MISSION_SOURCE) and isinstance(mission, str):
        if source[len(_MISSION_SOURCE) :] != mission:
            issues.append(
                ValidationIssue(
                    "SOURCE_MISMATCH", "/missionid", "missionid is not the id in source"
                )
            )
    return issues
