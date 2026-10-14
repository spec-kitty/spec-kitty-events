"""Total order over Spec Kitty event-log rows in their CloudEvents (upcast) form.

The contract is ``contracts/total-order.md``; this module is its reference
implementation, and the fixtures under ``producer_contract/fixtures/total_order/`` are
its conformance suite. Rows are the attribute dictionaries of CloudEvents 1.0 rows
(``id``, ``time``, ``source``, ``lamportclock``, ...), as parsed from JSON.

The key of a row is ``(h, c, t, i, s)``:

* ``h`` is 1 if the row has a ``lamportclock`` member, else 0, so every clockless row
  sorts before every clocked row;
* ``c`` is the clock (0 when clockless);
* ``t`` is ``time`` as an exact UTC instant;
* ``i`` is the id normalised by :func:`~spec_kitty_events.models.normalize_event_id`;
* ``s`` is ``source`` (the empty string when absent or null).

Strings compare by Unicode code point. A comparator that used the clock only when both
rows have one would not be transitive; this one is a lexicographic key sort, so it is.

This is distinct from the legacy :func:`spec_kitty_events.conflict.total_order_key`, which
keys ``(lamportclock, event_id)`` over the Pydantic ``Event`` model. This order keys
``(h, c, t, i, s)`` over CloudEvents-form row dicts and supersedes that key for rows in
that form; new consumers ordering CloudEvents rows should use this module.

:func:`order_rows` sets rows it cannot key aside as named error rows (never dropped,
never sorted), collapses duplicates of ``(source, id)`` deterministically, and sorts the
rest. Its result does not depend on the order of its input.
"""

from __future__ import annotations

import json
import re
from collections import defaultdict
from dataclasses import dataclass
from datetime import datetime, timezone
from fractions import Fraction
from typing import Any, Dict, Iterable, List, Mapping, Tuple

from spec_kitty_events.models import normalize_event_id

__all__ = [
    "CLOCK_MAX",
    "ERROR_REASONS",
    "ErrorRow",
    "OrderResult",
    "UnorderableRow",
    "order_key",
    "order_rows",
]

#: Largest ``lamportclock``: the CloudEvents ``Integer`` range is signed 32-bit.
CLOCK_MAX = 2**31 - 1

REASON_TIME = "time_unparseable"
REASON_CLOCK = "clock_out_of_range"
REASON_ID_MISSING = "id_missing"
REASON_ID_FORM = "id_not_normalizable"
REASON_SOURCE = "source_invalid"
REASON_NOT_OBJECT = "row_not_object"

#: The closed set of reasons a row is set aside with.
ERROR_REASONS = frozenset(
    {REASON_TIME, REASON_CLOCK, REASON_ID_MISSING, REASON_ID_FORM, REASON_SOURCE, REASON_NOT_OBJECT}
)

#: At most nine fractional-second digits (nanoseconds), the precision Go's RFC3339Nano
#: and Java's ``Instant`` carry; more is unparseable, so every implementation agrees.
_MAX_FRACTION_DIGITS = 9

_RFC3339 = re.compile(
    r"^(?P<year>[0-9]{4})-(?P<month>[0-9]{2})-(?P<day>[0-9]{2})"
    r"[Tt]"
    r"(?P<hour>[0-9]{2}):(?P<minute>[0-9]{2}):(?P<second>[0-9]{2})"
    r"(?:\.(?P<fraction>[0-9]+))?"
    r"(?P<offset>[Zz]|[+-][0-9]{2}:[0-9]{2})$"
)
_EPOCH = datetime(1970, 1, 1, tzinfo=timezone.utc)


class UnorderableRow(ValueError):
    """Raised by :func:`order_key` for a row that cannot be keyed.

    ``reason`` is one of :data:`ERROR_REASONS`.
    """

    def __init__(self, reason: str, detail: str = "") -> None:
        super().__init__(f"{reason}: {detail}" if detail else reason)
        self.reason = reason


@dataclass(frozen=True)
class ErrorRow:
    """A row set aside because it cannot be keyed."""

    position: int  # index in the input, i.e. the line position as read
    reason: str  # one of ERROR_REASONS
    row: Any


@dataclass(frozen=True)
class OrderResult:
    """The outcome of :func:`order_rows`.

    ``len(ordered) + len(errors) + collapsed == read`` always holds.
    """

    ordered: Tuple[Mapping[str, Any], ...]
    errors: Tuple[ErrorRow, ...]
    collapsed: int
    conflicts: Tuple[Tuple[str, str], ...]  # (source, normalized id), sorted
    read: int

    def canonical(self) -> Dict[str, Any]:
        """A comparison form that ignores input position.

        Two inputs that are permutations of one another must give equal canonical forms:
        the ordered rows, the collapsed count, the conflict keys, and the error rows as a
        multiset (position excluded).
        """
        return {
            "ordered": [_serialize(row) for row in self.ordered],
            "errors": sorted((e.reason, _serialize_any(e.row)) for e in self.errors),
            "collapsed": self.collapsed,
            "conflicts": [list(key) for key in self.conflicts],
            "read": self.read,
        }


def _serialize(row: Any) -> bytes:
    """Compact, sorted-key, ASCII-escaped JSON text: the survivor's tie-break bytes.

    Non-ASCII is written as ``\\uXXXX`` (lower-case hex, surrogate pairs for astral code
    points), so a lone surrogate from ``"\\ud800"`` serialises like any other character
    and every implementation can reproduce the bytes.
    """
    return json.dumps(row, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode("ascii")


_serialize_any = _serialize


def _parse_instant(value: Any) -> Fraction:
    """Parse an RFC 3339 timestamp to an exact count of seconds since the epoch."""
    if not isinstance(value, str):
        raise UnorderableRow(REASON_TIME, "time is not a string")
    match = _RFC3339.fullmatch(value)
    if match is None:
        raise UnorderableRow(REASON_TIME, "time is not an RFC 3339 timestamp")
    fraction_digits = match.group("fraction") or ""
    if len(fraction_digits) > _MAX_FRACTION_DIGITS:
        raise UnorderableRow(REASON_TIME, "time has more than nine fractional digits")
    second = int(match.group("second"))
    if second > 60:
        raise UnorderableRow(REASON_TIME, "time has seconds above 60")
    offset = match.group("offset")
    offset_seconds = 0
    if offset not in ("Z", "z"):
        offset_hours, offset_minutes = int(offset[1:3]), int(offset[4:6])
        if offset_hours > 23 or offset_minutes > 59:
            raise UnorderableRow(REASON_TIME, "time has an offset out of range")
        offset_seconds = (offset_hours * 3600 + offset_minutes * 60) * (
            -1 if offset[0] == "-" else 1
        )
    try:
        # A leap second (:60) is read as :59 plus one second, as RFC 3339 intends.
        moment = datetime(
            int(match.group("year")),
            int(match.group("month")),
            int(match.group("day")),
            int(match.group("hour")),
            int(match.group("minute")),
            min(second, 59),
            tzinfo=timezone.utc,
        )
    except ValueError as exc:
        raise UnorderableRow(REASON_TIME, str(exc)) from exc
    delta = moment - _EPOCH
    whole = delta.days * 86400 + delta.seconds + (second - min(second, 59)) - offset_seconds
    return Fraction(whole) + (
        Fraction(int(fraction_digits), 10 ** len(fraction_digits)) if fraction_digits else 0
    )


def _clock(row: Mapping[str, Any]) -> Tuple[int, int]:
    """Return ``(h, c)`` for a row, or raise for a clock that is present and invalid."""
    if "lamportclock" not in row:
        return (0, 0)
    value = row["lamportclock"]
    # bool is an int subclass in Python; JSON true/false is not an integer.
    if type(value) is not int or not 0 <= value <= CLOCK_MAX:
        raise UnorderableRow(REASON_CLOCK, "lamportclock is not an integer in 0..2^31-1")
    return (1, value)


def _identifier(row: Mapping[str, Any]) -> str:
    value = row.get("id")
    if value is None or value == "":
        raise UnorderableRow(REASON_ID_MISSING, "id is absent or empty")
    try:
        return normalize_event_id(value)
    except ValueError as exc:
        raise UnorderableRow(REASON_ID_FORM, str(exc)) from exc


def _source(row: Mapping[str, Any]) -> str:
    value = row.get("source")
    if value is None:  # CloudEvents: a null attribute is an unset one
        return ""
    if not isinstance(value, str):
        raise UnorderableRow(REASON_SOURCE, "source is not a string")
    return value


def order_key(row: Mapping[str, Any]) -> Tuple[int, int, Fraction, str, str]:
    """Return the sort key ``(h, c, t, i, s)`` of a row.

    Raises:
        UnorderableRow: With a ``reason`` from :data:`ERROR_REASONS` if the row cannot be
            keyed. The checks run in a fixed order (object, id, clock, time, source), so
            the reason for a row with several defects is itself deterministic.
    """
    if not isinstance(row, Mapping):
        raise UnorderableRow(REASON_NOT_OBJECT, "row is not a JSON object")
    identifier = _identifier(row)
    has_clock, clock = _clock(row)
    instant = _parse_instant(row.get("time"))
    return (has_clock, clock, instant, identifier, _source(row))


def order_rows(rows: Iterable[Any]) -> OrderResult:
    """Order rows by the total order, setting aside rows that cannot be keyed.

    Steps: (1) key every row, setting aside those that cannot be keyed as
    :class:`ErrorRow` with their input position; (2) group the keyable rows by
    ``(source, normalized id)``; (3) keep, of each group, the row whose compact
    sorted-key ASCII-escaped serialisation has the smallest bytes, count the others as collapsed,
    and report the group as a conflict if its rows differ in any member other than
    ``id``; (4) sort the survivors by key. Because ``(source, id)`` is unique after step 3,
    no two survivors have equal keys, so the order is total.
    """
    items: List[Any] = list(rows)
    errors: List[ErrorRow] = []
    groups: Dict[
        Tuple[str, str], List[Tuple[Mapping[str, Any], Tuple[int, int, Fraction, str, str]]]
    ] = defaultdict(list)
    for position, row in enumerate(items):
        try:
            key = order_key(row)
        except UnorderableRow as exc:
            errors.append(ErrorRow(position=position, reason=exc.reason, row=row))
            continue
        groups[(key[4], key[3])].append((row, key))

    survivors: List[Tuple[Tuple[int, int, Fraction, str, str], Mapping[str, Any]]] = []
    conflicts: List[Tuple[str, str]] = []
    collapsed = 0
    for group_key, members in groups.items():
        best_row, best_key = min(members, key=lambda member: _serialize(member[0]))
        survivors.append((best_key, best_row))
        collapsed += len(members) - 1
        if len(members) > 1:
            variants = {
                _serialize({k: v for k, v in member[0].items() if k != "id"}) for member in members
            }
            if len(variants) > 1:
                conflicts.append(group_key)

    survivors.sort(key=lambda pair: pair[0])
    return OrderResult(
        ordered=tuple(row for _, row in survivors),
        errors=tuple(errors),
        collapsed=collapsed,
        conflicts=tuple(sorted(conflicts)),
        read=len(items),
    )
