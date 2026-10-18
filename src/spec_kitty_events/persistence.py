"""Reference write path for local JSONL event ledgers (#118).

:func:`append_event` is the Python reference of the local-ledger write
contract. Every other binding (for example a Java write-side sidecar) is a
second implementation of the same contract and is held to the same bytes by
the packaged ``ledger_append`` conformance fixtures.

Contract, in order:

1. **Normalise** the input to one JSON *record*:

   - :class:`~spec_kitty_events.models.Event` -> ``event.model_dump(mode="json")``;
   - ``Mapping`` carrying a string ``event_type`` -> the mapping itself, every
     key kept, no projection or defaults. A flat record's identity keys
     (``event_type`` and optional string ``event_id`` / ``timestamp``) are
     written but not validated as payload fields;
   - a registered payload-model instance -> ``{"event_type": T, **model_dump}``
     where ``T`` is the one event type that model class is registered under.

2. **Validate** the record fail-closed against the canonical conformance
   contract (``strict=True``). An envelope is validated as ``"Event"`` and its
   inner payload against its own ``event_type``.
3. **Serialise** with the pinned byte rule (:func:`serialize_record`).
4. **Append** with one ``os.write`` on an ``O_APPEND`` descriptor, refusing a
   ledger whose last byte is not LF.

Every validation refusal raises a :class:`LedgerAppendError` before the
ledger is opened, so a refused event never touches the file. Two residuals
are I/O-level, not validation-level: the torn-tail check is advisory under
concurrent writers (it runs before the write, not under a lock), and a short
``os.write`` -- impossible on a regular local file -- is reported after the
partial bytes have landed. ``LOCAL_ONLY_EVENT_TYPES`` is not
consulted: that registry is retired (deprecated, removed in 11.0).
"""

from __future__ import annotations

import json
import math
import os
from collections.abc import Mapping
from functools import lru_cache
from pathlib import Path
from typing import Any, Dict, Union

from pydantic import BaseModel

from spec_kitty_events.models import Event, SpecKittyEventsError

__all__ = [
    "LedgerAppendError",
    "LedgerTornTailError",
    "LedgerValidationError",
    "append_event",
    "build_record",
    "serialize_record",
]

_ENVELOPE_TYPE = "Event"

# Identity keys a flat record may carry beside its payload fields. They are
# kept in the written line but stripped before payload validation unless the
# payload model itself declares the field (e.g. ReviewerSelfApproval.timestamp).
_FLAT_IDENTITY_KEYS = frozenset({"event_type", "event_id", "timestamp"})

LedgerInput = Union[Event, Mapping[str, Any], BaseModel]


class LedgerAppendError(SpecKittyEventsError):
    """Base class of every refusal raised by the ledger write path."""


class LedgerValidationError(LedgerAppendError, ValueError):
    """The input is not a valid canonical event; nothing was written."""


class LedgerTornTailError(LedgerAppendError):
    """The ledger's last byte is not LF (a torn line); nothing was appended."""


def _type_to_model() -> Dict[str, Any]:
    # Deferred: conformance.validators imports most of the package.
    from spec_kitty_events.conformance.validators import _EVENT_TYPE_TO_MODEL

    return _EVENT_TYPE_TO_MODEL


@lru_cache(maxsize=1)
def _model_to_event_type() -> Dict[type, str]:
    """Map each payload-model class to the one event type it is registered under.

    A class registered under more than one event type is ambiguous and left out,
    so its instances are refused rather than guessed.
    """
    seen: Dict[type, list[str]] = {}
    for event_type, model in _type_to_model().items():
        if event_type == _ENVELOPE_TYPE:
            continue
        seen.setdefault(model, []).append(event_type)
    return {model: types[0] for model, types in seen.items() if len(types) == 1}


def _known_event_types() -> frozenset[str]:
    return frozenset(_type_to_model())


def _normalise(event: object) -> Dict[str, Any]:
    if isinstance(event, Event):
        return event.model_dump(mode="json")
    if isinstance(event, BaseModel):
        event_type = _model_to_event_type().get(type(event))
        if event_type is None:
            raise LedgerValidationError(
                f"{type(event).__name__} is not a registered event payload model"
            )
        return {"event_type": event_type, **event.model_dump(mode="json")}
    if isinstance(event, Mapping):
        return dict(event)
    raise LedgerValidationError(
        f"unsupported ledger input {type(event).__name__}; expected Event, Mapping or BaseModel"
    )


def _is_envelope(record: Mapping[str, Any]) -> bool:
    return isinstance(record.get("payload"), dict) and "aggregate_id" in record


def _check_json_native(value: object, where: str = "$") -> None:
    if value is None or isinstance(value, (str, bool, int)):
        return
    if isinstance(value, float):
        if not math.isfinite(value):
            raise LedgerValidationError(f"{where}: non-finite float is not allowed")
        return
    if isinstance(value, list):
        for index, item in enumerate(value):
            _check_json_native(item, f"{where}[{index}]")
        return
    if isinstance(value, dict):
        for key, item in value.items():
            if not isinstance(key, str):
                raise LedgerValidationError(f"{where}: non-string key {key!r}")
            _check_json_native(item, f"{where}.{key}")
        return
    raise LedgerValidationError(f"{where}: {type(value).__name__} is not a JSON-native value")


def _flat_payload(record: Mapping[str, Any], event_type: str) -> Dict[str, Any]:
    fields = set(getattr(_type_to_model()[event_type], "model_fields", {}))
    for key in ("event_id", "timestamp"):
        if key in record and key not in fields and not isinstance(record[key], str):
            raise LedgerValidationError(f"identity key {key!r} must be a string")
    return {k: v for k, v in record.items() if k not in _FLAT_IDENTITY_KEYS or k in fields}


def _validate(record: Dict[str, Any], event_type: str) -> None:
    from spec_kitty_events.conformance.validators import validate_event

    result = validate_event(record, event_type, strict=True)
    if result.model_violations or result.schema_violations:
        details = [f"{v.field}: {v.message}" for v in result.model_violations]
        details += [f"{v.json_path}: {v.message}" for v in result.schema_violations]
        raise LedgerValidationError(f"invalid {event_type}: " + "; ".join(details))


def build_record(event: LedgerInput) -> Dict[str, Any]:
    """Normalise and validate *event* into the record :func:`append_event` writes.

    Performs no I/O. Raises :class:`LedgerValidationError` on any refusal.
    """
    record = _normalise(event)
    _check_json_native(record)
    event_type = record.get("event_type")
    if not isinstance(event_type, str):
        raise LedgerValidationError("record has no string 'event_type'")
    if event_type == _ENVELOPE_TYPE or event_type not in _known_event_types():
        raise LedgerValidationError(f"unknown or unregistered event type {event_type!r}")
    if _is_envelope(record):
        _validate(record, _ENVELOPE_TYPE)
        _validate(record["payload"], event_type)
    else:
        _validate(_flat_payload(record, event_type), event_type)
    return record


def serialize_record(record: Mapping[str, Any]) -> bytes:
    """Return the pinned ledger line for *record*: canonical JSON plus one LF.

    ``json.dumps(record, sort_keys=True, ensure_ascii=True, allow_nan=False)``
    encoded as ASCII, followed by ``b"\\n"``. For JSON-native records this is
    byte-identical to core's local fallback append.
    """
    try:
        text = json.dumps(record, sort_keys=True, ensure_ascii=True, allow_nan=False)
    except (TypeError, ValueError) as exc:
        raise LedgerValidationError(f"record is not serialisable: {exc}") from exc
    return text.encode("ascii") + b"\n"


def _refuse_torn_tail(path: Path) -> None:
    if path.exists() and path.stat().st_size > 0:
        with path.open("rb") as handle:
            handle.seek(-1, os.SEEK_END)
            if handle.read(1) != b"\n":
                raise LedgerTornTailError(f"{path}: last byte is not LF; refusing to append")


def append_event(event: LedgerInput, path: Union[str, "os.PathLike[str]"]) -> None:
    """Append *event* to the JSONL ledger at *path* as exactly one line.

    Fail-closed: the event is fully normalised, validated and serialised
    before the ledger is opened, so any :class:`LedgerAppendError` leaves the
    file byte-identical (or absent). Atomic only for a regular local file.
    """
    line = serialize_record(build_record(event))
    ledger = Path(path)
    ledger.parent.mkdir(parents=True, exist_ok=True)
    _refuse_torn_tail(ledger)
    flags = os.O_WRONLY | os.O_APPEND | os.O_CREAT | getattr(os, "O_BINARY", 0)
    fd = os.open(ledger, flags, 0o644)
    try:
        written = os.write(fd, line)
    finally:
        os.close(fd)
    if written != len(line):
        raise LedgerAppendError(f"{ledger}: short write ({written} of {len(line)} bytes)")
