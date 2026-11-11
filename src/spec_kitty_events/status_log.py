"""Sanitize and encode one ``status.events.jsonl`` row exactly as core does.

This is a standard-library port of core's ``status.store._serialize`` (exposed
as ``serialize_event_line`` at pin ``512567ee``) and
``events.sanitizer.sanitize_event_for_log``. The input is the value produced by
``json.loads`` from the wire, so distinct JSON numbers such as ``1`` and
``1.0`` remain distinct.

Deliberate differences from core, none reachable from ``json.loads`` output
except the first two: non-finite floats are refused instead of being written
as ``NaN``/``Infinity``; mixed naive/aware session times raise
:class:`SessionTimesNotSubtractable` instead of leaking core's ``TypeError``;
integers beyond the interpreter's int-to-str digit limit raise
:class:`IntegerTooLarge`, non-string mapping keys raise :class:`NonStringKey`
and any other non-JSON value raises :class:`UnsupportedValue`, where core
would leak a bare ``ValueError``/``TypeError`` (or, for non-string keys,
coerce them). Circular-container handling is intentionally absent:
``json.loads`` cannot produce circular containers.
"""

from __future__ import annotations

import json
import sys
from collections.abc import Mapping
from datetime import datetime
from typing import Any

from spec_kitty_events._iso8601 import parse_iso8601_datetime

__all__ = [
    "sanitize_status_row",
    "encode_status_row",
    "StatusRowRefused",
    "NonFiniteNumber",
    "IntegerTooLarge",
    "NonStringKey",
    "UnsupportedValue",
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


class IntegerTooLarge(StatusRowRefused):
    """An integer's decimal form exceeds the interpreter's int-to-str digit limit."""


class NonStringKey(StatusRowRefused):
    """A mapping key is not a string; JSON object keys are strings."""


class UnsupportedValue(StatusRowRefused):
    """A value is not a JSON-like type (object, array, string, number, bool, null)."""


class SessionTimesNotSubtractable(StatusRowRefused):
    """Session timestamps have incompatible naive/aware shapes."""


def _strip_pii_mapping(row: Mapping[str, Any]) -> dict[str, Any]:
    return {key: _strip_pii_value(value) for key, value in row.items() if key not in _PII_FIELDS}


def _strip_pii_value(value: Any) -> Any:
    if isinstance(value, dict):
        return _strip_pii_mapping(value)
    if isinstance(value, list):
        return [_strip_pii_value(item) for item in value]
    if isinstance(value, tuple):
        return tuple(_strip_pii_value(item) for item in value)
    return value


#: CPython's default int-to-str limit, applied on 3.10 patch releases that
#: predate ``sys.get_int_max_str_digits`` (before 3.10.7) so the refusal does
#: not depend on the patch level.
_FALLBACK_INT_MAX_STR_DIGITS = 4300


def _int_digit_limit() -> int:
    getter = getattr(sys, "get_int_max_str_digits", None)
    return getter() if getter is not None else _FALLBACK_INT_MAX_STR_DIGITS


def _check_int(value: int) -> None:
    limit = _int_digit_limit()
    if limit == 0:
        return
    # 4300 digits is ~14284 bits; skip the str() cost for anything clearly smaller.
    if value.bit_length() <= limit * 3:
        return
    try:
        digits = len(str(abs(value)))  # CPython's limit counts digits, not the sign
    except ValueError as exc:
        raise IntegerTooLarge("integer exceeds the int-to-str digit limit") from exc
    if digits > limit:
        raise IntegerTooLarge("integer exceeds the int-to-str digit limit")


def _validate_encodable(value: Any) -> None:
    """Raise a typed refusal for anything ``json.dumps`` would reject by type.

    Non-finite floats are left to ``allow_nan=False``.
    """
    if isinstance(value, dict):
        for key, nested in value.items():
            if not isinstance(key, str):
                raise NonStringKey("status row mapping keys must be strings")
            _validate_encodable(nested)
    elif isinstance(value, (list, tuple)):
        for item in value:
            _validate_encodable(item)
    elif isinstance(value, bool) or value is None or isinstance(value, (str, float)):
        return
    elif isinstance(value, int):
        _check_int(value)
    else:
        raise UnsupportedValue(f"{type(value).__name__} is not a JSON-like value")


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
    by their integer-second duration. Tuples are sanitised like lists and stay
    tuples, as in core's sanitiser (they encode as arrays either way). The
    input and all nested values remain unchanged. A non-object row is outside the status-row protocol.

    Session timestamps use CPython 3.11's exact ``datetime.fromisoformat``
    grammar on every supported interpreter: the CPython 3.11 port in
    ``spec_kitty_events._iso8601`` is used everywhere, never the interpreter's
    own ``fromisoformat``. Basic-format dates and times, ISO week
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

    The session-timestamp grammar is the CPython 3.11 port on every
    interpreter (see :func:`sanitize_status_row`), so the encoded bytes --
    including ``session_duration_s`` -- do not depend on the interpreter
    version. Only typed :class:`StatusRowRefused` errors escape for JSON-like
    input: :class:`NonFiniteNumber` for NaN/infinity,
    :class:`IntegerTooLarge`, :class:`NonStringKey` and
    :class:`UnsupportedValue` for the rest. The digit limit is the process's
    ``sys.get_int_max_str_digits()`` (4300 where that does not exist, i.e.
    3.10 before 3.10.7); a limit of 0 disables the refusal.
    """
    sanitized = sanitize_status_row(row)
    _validate_encodable(sanitized)
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
