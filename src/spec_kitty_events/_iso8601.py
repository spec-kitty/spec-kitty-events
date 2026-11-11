"""Shared ISO-8601/RFC-3339 timestamp shape normalization and parsing.

Private module (spec-kitty-events#143): factored out of ``strict.py``,
``retrospective.py``, and ``conformance/timestamp_semantics.py``, which each
carried a byte-identical ~45-line copy of :data:`_ISO8601_SHAPE_RE` and
:func:`normalize_iso8601_shape` after spec-kitty-events#137. A fix to this
shape-reshape logic (e.g. the 3.10-only narrowing regression and
mixed-basic/extended acceptance widening #137's pass-2 review found) used to
have to be written once per copy and remembered again in every future one;
this module is the one place it lives now. Stays private (leading
underscore) so no public API surface is added -- each importing module
re-exports it under its own existing private name, so no consumer or test
import path changes.

:func:`parse_iso8601_datetime` (spec-kitty-events#160, PR #230 squad
finding) is the one full parser here, used by ``status_log`` for session
timestamps. On every interpreter it is a faithful port of CPython 3.11's
``datetime.fromisoformat`` grammar (:func:`_parse_isoformat_311`), so every
form 3.11 accepts -- basic-format
dates and times, ISO week dates, ``Z``, hour-only and second/fraction
offsets, reduced precision, comma fractions and any single-character
date/time separator -- yields the same ``datetime`` on every interpreter as core's
interpreter produces. Its oracle is the CPython 3.11 vector set shared with
the Java port (``service/domain-model/src/test/resources/pyisodatetime/
vectors.json``).
"""

from __future__ import annotations

import re
from datetime import date, datetime, timedelta, timezone

#: Matches an ISO-8601/RFC-3339 timestamp in either extended
#: (``2026-08-25T09:00:00.123456+00:00``) or basic (``20260825T090000Z``)
#: format, with an optional fractional-second part of *any* digit count and
#: an optional numeric offset. Used only to reshape a match into the one
#: extended-with-6-digit-fraction spelling ``fromisoformat`` accepts
#: identically on every supported interpreter (see
#: ``normalize_iso8601_shape``); a non-match is passed through unchanged so
#: a genuinely malformed string still reaches ``fromisoformat``'s own error.
#: The trailing-``Z`` designator is handled separately, unconditionally,
#: before this regex ever runs -- see ``normalize_iso8601_shape`` -- so this
#: pattern's offset alternative only needs to cover a *numeric* offset.
_ISO8601_SHAPE_RE = re.compile(
    r"^(?P<date>\d{4}-\d{2}-\d{2}|\d{8})"
    r"[T ]"
    r"(?P<time>\d{2}:\d{2}:\d{2}|\d{2}:\d{2}|\d{6}|\d{4}|\d{2})"
    r"(?P<frac>[.,]\d+)?"
    r"(?P<offset>[+-]\d{2}:?\d{2})?$"
)


def normalize_iso8601_shape(value: str) -> str:
    """Reshape *value* so ``datetime.fromisoformat`` parses it identically
    on Python 3.10 and 3.11+.

    3.10's ``fromisoformat`` has three gaps 3.11+ closed: it does not
    recognize the ``Z`` UTC designator at all, it only accepts a
    fractional-second part of exactly 0, 3, or 6 digits (rejecting, e.g.,
    Go's ``time.RFC3339Nano`` 9-digit output), and it only accepts the
    ``-``/``:``-separated "extended" format (rejecting basic format like
    ``20260825T090000Z``). All three gaps let the same wire bytes decode on
    one interpreter and raise on the other (spec-kitty-events#122, #135).

    The ``Z`` gap is closed first, unconditionally, exactly as it was
    before the fractional/basic-format reshape below existed: a trailing
    ``Z`` is stripped and replaced with ``+00:00`` so it parses the same way
    on every interpreter. A well-formed value has at most this one trailing
    ``Z``; if another ``z``/``Z`` remains after stripping it, the input was
    already malformed and must not be laundered into something 3.11+'s
    single-stray-character leniency around a trailing ``Z`` would otherwise
    accept (e.g. a doubled ``"...00ZZ"`` or mixed-case ``"...00zZ"``) while
    3.10 rejects it outright -- so that case raises instead of being
    reshaped. Doing this *before and independently of* the regex below
    means a shape the regex does not match (a different separator, reduced
    precision, ...) is never worse off than it was before the regex-based
    reshape existed -- e.g. a lowercase ``t`` date/time separator or a space
    before the ``Z`` both parse identically on 3.10 and 3.11+ once the ``Z``
    has already been rewritten, exactly as on this repo's pre-#122/#135
    ``main``.

    The regex then truncates/pads any fractional part to 6 digits -- the
    precision ``datetime`` itself stores -- inserts the extended-format
    separators when given basic format, pads a reduced-precision time (bare
    hour, or hour:minute with no seconds, in either format) out to
    hour:minute:second, and inserts a colon into a colon-less numeric
    offset. A value that does not match the expected timestamp shape at all
    (already malformed, or a format this repo does not need to handle) is
    returned unchanged, so it still fails ``fromisoformat`` with its
    ordinary ``ValueError``.
    """
    if value.endswith("Z"):
        candidate = value[:-1]
        if "z" in candidate.lower():
            raise ValueError(f"timestamp {value!r} is not ISO-8601 (doubled UTC designator)")
        value = f"{candidate}+00:00"
    match = _ISO8601_SHAPE_RE.match(value)
    if match is None:
        return value
    date, time, frac, offset = match.group("date", "time", "frac", "offset")
    if len(date) == 8:
        date = f"{date[0:4]}-{date[4:6]}-{date[6:8]}"
    if ":" in time:
        if time.count(":") == 1:
            time = f"{time}:00"
    elif len(time) == 2:
        time = f"{time}:00:00"
    elif len(time) == 4:
        time = f"{time[0:2]}:{time[2:4]}:00"
    else:
        time = f"{time[0:2]}:{time[2:4]}:{time[4:6]}"
    fraction = f".{frac[1:][:6].ljust(6, '0')}" if frac else ""
    if offset is None:
        offset = ""
    elif ":" not in offset:
        offset = f"{offset[:3]}:{offset[3:]}"
    return f"{date}T{time}{fraction}{offset}"


class _InvalidIsoFormat(ValueError):
    """Internal refusal mirroring ``datetime.fromisoformat``'s ``ValueError``."""


#: CPython's ``correction[]`` table in``parse_hh_mm_ss_ff``: a fraction of
#: fewer than six digits is scaled up to microseconds by this factor.
_FRACTION_CORRECTION = (100_000, 10_000, 1_000, 100, 10)

_MICROS_PER_DAY = 86_400 * 1_000_000


def _is_ascii_digit(character: str) -> bool:
    """``'0' <= character <= '9'`` without Unicode's wider ``isdigit``."""

    return "0" <= character <= "9"


def _is_ascii_digit_byte(value: int) -> bool:
    return 48 <= value <= 57


def _character_at(text: str, index: int) -> str:
    """Return ``text[index]`` or CPython's NUL terminator past the end.

    CPython's C parser reads a NUL byte one past the UTF-8 buffer, so a
    check like ``*p != '\\0'`` conflates "past the end" with an embedded
    NUL. This helper reproduces that for a Python string.
    """

    if 0 <= index < len(text):
        return text[index]
    return "\0"


def _byte_at(data: bytes, index: int) -> int:
    """Return ``data[index]`` or CPython's NUL terminator past the end."""

    if 0 <= index < len(data):
        return data[index]
    return 0


def _ascii_digits(text: str, start: int, count: int) -> int:
    """CPython's ``parse_digits``: exactly *count* ASCII digits, no more."""

    value = 0
    for index in range(start, start + count):
        character = _character_at(text, index)
        if not _is_ascii_digit(character):
            raise _InvalidIsoFormat("expected an ASCII digit")
        value = value * 10 + (ord(character) - ord("0"))
    return value


def _ascii_digit_bytes(data: bytes, start: int, count: int) -> int:
    """CPython's ``parse_digits`` over the UTF-8 bytes of the time part."""

    value = 0
    for index in range(start, start + count):
        byte = _byte_at(data, index)
        if not _is_ascii_digit_byte(byte):
            raise _InvalidIsoFormat("expected an ASCII digit")
        value = value * 10 + byte - 48
    return value


def _sanitize_isoformat_str(value: str) -> str:
    """CPython's ``_sanitize_isoformat_str``: allow one surrogate separator.

    ``fromisoformat`` permits a lone surrogate in exactly one position --
    the date/time separator at index 7, 8 or 10 -- and replaces it with
    ``T`` before encoding the string as UTF-8. A surrogate anywhere else
    makes the UTF-8 encode fail, which CPython turns into ``ValueError``.
    """

    if len(value) < 7:
        raise _InvalidIsoFormat("ISO-8601 string is shorter than seven characters")
    for position in (7, 8, 10):
        if position >= len(value):
            break
        if 0xD800 <= ord(value[position]) <= 0xDFFF:
            value = f"{value[:position]}T{value[position + 1 :]}"
            break
    try:
        value.encode("utf-8")
    except UnicodeEncodeError as exc:
        raise _InvalidIsoFormat("ISO-8601 string is not UTF-8 encodable") from exc
    return value


def _find_datetime_separator(text: str) -> int:
    """CPython's ``_find_isoformat_datetime_separator`` on code points."""

    length = len(text)
    if length == 7:
        return 7
    if text[4] == "-":
        if text[5] == "W":
            if length < 8:
                raise _InvalidIsoFormat("invalid ISO week date")
            if length > 8 and text[8] == "-":
                if length == 9:
                    raise _InvalidIsoFormat("invalid ISO week date")
                if length > 10 and _is_ascii_digit(text[10]):
                    return 8
                return 10
            return 8
        return 10
    if text[4] == "W":
        index = 7
        while index < length and _is_ascii_digit(text[index]):
            index += 1
        if index < 9:
            return index
        return 7 if index % 2 == 0 else 8
    return 8


def _require_dash(text: str, position: int, dashed: bool) -> None:
    found = position < len(text) and text[position] == "-"
    if found != dashed:
        raise _InvalidIsoFormat("inconsistent date separator")


def _calendar_date(year: int, month: int, day: int) -> date:
    try:
        return date(year, month, day)
    except ValueError as exc:
        raise _InvalidIsoFormat("invalid calendar date") from exc


def _parse_iso_week_date(text: str, year: int, dashed: bool, position: int) -> date:
    week = _ascii_digits(text, position, 2)
    position += 2
    day = 1
    if len(text) > position:
        _require_dash(text, position, dashed)
        position += 1 if dashed else 0
        day = _ascii_digits(text, position, 1)
    try:
        return date.fromisocalendar(year, week, day)
    except ValueError as exc:
        raise _InvalidIsoFormat("invalid ISO week date") from exc


def _parse_isoformat_date(text: str) -> date:
    """CPython's ``parse_isoformat_date`` for a length-7/8/10 date slice."""

    if len(text) not in (7, 8, 10):
        raise _InvalidIsoFormat("invalid date length")
    year = _ascii_digits(text, 0, 4)
    if year < 1:
        raise _InvalidIsoFormat("year is out of range")
    dashed = text[4] == "-"
    position = 5 if dashed else 4
    if position < len(text) and text[position] == "W":
        return _parse_iso_week_date(text, year, dashed, position + 1)
    month = _ascii_digits(text, position, 2)
    position += 2
    _require_dash(text, position, dashed)
    position += 1 if dashed else 0
    day = _ascii_digits(text, position, 2)
    return _calendar_date(year, month, day)


def _parse_hh_mm_ss_ff(
    data: bytes, start: int, end: int, *, offset: bool
) -> tuple[list[int], bool, bool]:
    """CPython's ``parse_hh_mm_ss_ff`` over ``data[start:end]``.

    CPython advances a ``char *`` over the UTF-8 bytes, not Unicode code
    points, so a multi-byte character consumes several positions. Returns
    ``(components, has_trailing_text, parsed_fraction)``. *offset* only
    selects the error branch name, as in CPython.
    """

    components = [0, 0, 0, 0]
    position = start
    colon = False
    for component in range(3):
        components[component] = _ascii_digit_bytes(data, position, 2)
        position += 2
        next_byte = _byte_at(data, position)
        position += 1
        if component == 0:
            colon = next_byte == 58  # ":"
        if position >= end:
            return components, next_byte != 0, False
        if colon and next_byte == 58:  # ":"
            continue
        if next_byte in (44, 46):  # "," or "."
            break
        if not colon:
            position -= 1
        else:
            raise _InvalidIsoFormat("malformed time separator")
    count = min(6, end - position)
    if count <= 0:
        raise _InvalidIsoFormat("invalid microsecond component")
    components[3] = _ascii_digit_bytes(data, position, count)
    if count < 6:
        components[3] *= _FRACTION_CORRECTION[count - 1]
    position += count
    while _is_ascii_digit_byte(_byte_at(data, position)):
        position += 1
    return components, _byte_at(data, position) != 0, True


def _parse_iso_timezone(data: bytes, position: int) -> int:
    """CPython's ``parse_timezone``: signed offset as microseconds."""

    start = position + 1
    if len(data) - start in (0, 1, 3):
        raise _InvalidIsoFormat("malformed time zone string")
    components, trailing, _ = _parse_hh_mm_ss_ff(data, start, len(data), offset=True)
    if trailing:
        raise _InvalidIsoFormat("trailing text in time zone")
    hours, minutes, seconds, micros = components
    whole_seconds = hours * 3_600 + minutes * 60 + seconds
    if whole_seconds == 0:
        # CPython's tzinfo_from_isoformat_results returns UTC whenever the
        # whole-second offset is zero, discarding a sub-second component.
        return 0
    magnitude = whole_seconds * 1_000_000 + micros
    if magnitude >= _MICROS_PER_DAY:
        raise _InvalidIsoFormat("time zone offset is not strictly within a day")
    sign = -1 if data[position] == 45 else 1  # "-"
    return sign * magnitude


def _parse_iso_time(text: str) -> tuple[int, int, int, int, int | None]:
    """CPython's ``parse_isoformat_time`` returning fields and offset micros."""

    if len(text) < 2:
        raise _InvalidIsoFormat("ISO-8601 time is too short")
    # CPython parses the time from the UTF-8 encoding, so byte offsets (not
    # code points) decide where a multi-byte character ends the component.
    data = text.encode("utf-8")
    timezone_position = -1
    for index, byte in enumerate(data):
        if byte in (43, 45, 90):  # "+", "-", "Z"
            timezone_position = index
            break
    clock_end = timezone_position if timezone_position >= 0 else len(data)
    components, trailing, _ = _parse_hh_mm_ss_ff(data, 0, clock_end, offset=False)
    if timezone_position < 0:
        if trailing:
            raise _InvalidIsoFormat("trailing text after ISO-8601 time")
        return components[0], components[1], components[2], components[3], None
    if data[timezone_position] == 90:  # "Z"
        if _byte_at(data, timezone_position + 1) != 0:
            raise _InvalidIsoFormat("text after the UTC designator")
        return components[0], components[1], components[2], components[3], 0
    offset = _parse_iso_timezone(data, timezone_position)
    return components[0], components[1], components[2], components[3], offset


def _parse_isoformat_311(value: str) -> datetime:
    """Parse exactly like CPython 3.11's ``datetime.fromisoformat``.

    This is the implementation behind :func:`parse_iso8601_datetime` on every
    interpreter; it is the
    grammar ported from CPython's C implementation (the same one the Java
    ``PyIsoDateTime`` binding follows), so it accepts and rejects the same
    strings and produces the same naive/aware value.
    """

    text = _sanitize_isoformat_str(value)
    separator = _find_datetime_separator(text)
    day = _parse_isoformat_date(text[:separator])
    if separator >= len(text):
        return datetime(day.year, day.month, day.day)
    hour, minute, second, microsecond, offset = _parse_iso_time(text[separator + 1 :])
    if hour > 23 or minute > 59 or second > 59:
        raise _InvalidIsoFormat("time component is out of range")
    if offset is None:
        return datetime(day.year, day.month, day.day, hour, minute, second, microsecond)
    return datetime(
        day.year,
        day.month,
        day.day,
        hour,
        minute,
        second,
        microsecond,
        tzinfo=timezone(timedelta(microseconds=offset)),
    )


def parse_iso8601_datetime(value: str) -> datetime:
    """Parse *value* exactly as CPython 3.11's ``datetime.fromisoformat``.

    The CPython 3.11 port (:func:`_parse_isoformat_311`) is used on every
    interpreter, never the running interpreter's own ``fromisoformat``, so
    3.10, 3.11, 3.12 and 3.13 accept, reject and produce identical values
    (3.12+ changed edge cases such as sub-minute offsets with fractions).
    """

    return _parse_isoformat_311(value)
