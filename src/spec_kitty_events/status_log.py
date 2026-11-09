"""Sanitize and encode one ``status.events.jsonl`` row exactly as core does.

This is a standard-library port of core's ``status.store._serialize`` (exposed
as ``serialize_event_line`` at pin ``512567ee``) and
``events.sanitizer.sanitize_event_for_log``. The input is the value produced by
``json.loads`` from the wire, so distinct JSON numbers such as ``1`` and
``1.0`` remain distinct.

Two protocol rules deliberately differ from core: non-finite floats are
refused instead of being written as ``NaN``/``Infinity``, and mixed naive/aware
session times raise :class:`SessionTimesNotSubtractable` instead of leaking
core's ``TypeError``. Circular-container handling is intentionally absent:
``json.loads`` cannot produce circular containers.
"""

from __future__ import annotations

import json
from collections.abc import Mapping
from datetime import datetime
from typing import Any

from spec_kitty_events._iso8601 import parse_iso8601_datetime

__all__ = [
    "sanitize_status_row",
    "encode_status_row",
    "StatusRowRefused",
    "NonFiniteNumber",
    "SessionTimesNotSubtractable",
]

_PII_FIELDS = frozenset(
    {
        "machine_name",
        "hostname",
        "workspace_path",
        "developer_name",
        "developer_email",
    }
)


class StatusRowRefused(ValueError):
    """Base refusal for a status row the protocol cannot encode."""


class NonFiniteNumber(StatusRowRefused):
    """A non-finite number is not permitted on the write protocol."""


class SessionTimesNotSubtractable(StatusRowRefused):
    """Session timestamps have incompatible naive/aware shapes."""


def _strip_pii_mapping(row: Mapping[str, Any]) -> dict[str, Any]:
    return {key: _strip_pii_value(value) for key, value in row.items() if key not in _PII_FIELDS}


def _strip_pii_value(value: Any) -> Any:
    if isinstance(value, dict):
        return _strip_pii_mapping(value)
    if isinstance(value, list):
        return [_strip_pii_value(item) for item in value]
    return value


def _parse_session_time(value: Any) -> datetime | None:
    if not isinstance(value, str):
        return None
    try:
        return parse_iso8601_datetime(value)
    except (TypeError, ValueError):
        return None


def _replace_session_times(row: dict[str, Any]) -> dict[str, Any]:
    started_raw = row.pop("session_started_at", None)
    ended_raw = row.pop("session_ended_at", None)
    started = _parse_session_time(started_raw)
    ended = _parse_session_time(ended_raw)
    if started is not None and ended is not None:
        try:
            row["session_duration_s"] = int((ended - started).total_seconds())
        except TypeError as exc:
            raise SessionTimesNotSubtractable(
                "session_started_at and session_ended_at must both be naive or both be aware"
            ) from exc
    return row


def sanitize_status_row(row: Mapping[str, Any]) -> dict[str, Any]:
    """Return core's sanitized value for one parsed JSON status-row object.

    PII keys are removed recursively through objects and arrays. Top-level
    session timestamps are always removed; when both parse they are replaced
    by their integer-second duration. The input and all nested values remain
    unchanged. A non-object row is outside the status-row protocol.

    Session timestamps use CPython 3.11's exact ``datetime.fromisoformat``
    grammar on every supported interpreter: 3.11+ natively, and 3.10 through
    an internal port of that parser. Basic-format dates and times, ISO week
    dates (``2026-W01-1``), ``Z``, hour-only (``+05``) and second/fraction
    offsets, reduced precision and any single-character date/time separator
    therefore produce the same duration on the declared floor as core's
    interpreter does. Forms 3.11 rejects stay unparseable on every
    interpreter and leave any existing ``session_duration_s`` untouched.
    """
    if not isinstance(row, Mapping):
        raise StatusRowRefused("a status row must be a JSON object")
    return _replace_session_times(_strip_pii_mapping(row))


def encode_status_row(row: Mapping[str, Any]) -> bytes:
    """Return the sanitized row as core-compatible ASCII JSON plus one LF.

    The session-timestamp grammar is identical on 3.10 and 3.11+ (see
    :func:`sanitize_status_row`), so the encoded bytes -- including
    ``session_duration_s`` -- do not depend on the interpreter version.
    """
    sanitized = sanitize_status_row(row)
    try:
        encoded = json.dumps(
            sanitized,
            sort_keys=True,
            ensure_ascii=True,
            separators=(", ", ": "),
            allow_nan=False,
        )
    except ValueError as exc:
        raise NonFiniteNumber("status rows cannot contain NaN or infinity") from exc
    return (encoded + "\n").encode("ascii")
