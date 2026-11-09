"""Unit contract for the public status-row sanitizer and byte encoder."""

from __future__ import annotations

import copy
import json
from typing import Any

import pytest

from spec_kitty_events.status_log import (
    NonFiniteNumber,
    SessionTimesNotSubtractable,
    StatusRowRefused,
    encode_status_row,
    sanitize_status_row,
)

_PII_FIELDS = (
    "machine_name",
    "hostname",
    "workspace_path",
    "developer_name",
    "developer_email",
)


def _assert_key_absent(value: Any, key: str) -> None:
    if isinstance(value, dict):
        assert key not in value
        for nested in value.values():
            _assert_key_absent(nested, key)
    elif isinstance(value, list):
        for nested in value:
            _assert_key_absent(nested, key)


@pytest.mark.parametrize("pii_key", _PII_FIELDS)
def test_pii_is_removed_recursively_without_mutating_input(pii_key: str) -> None:
    row = {
        pii_key: "top",
        "keep": "top-level",
        "nested": {pii_key: "nested", "keep": 1},
        "objects": [{pii_key: "in-list", "keep": 2}],
        "lists": [[{pii_key: "deep-list", "keep": 3}]],
    }
    before = copy.deepcopy(row)

    sanitized = sanitize_status_row(row)

    _assert_key_absent(sanitized, pii_key)
    assert sanitized["keep"] == "top-level"
    assert sanitized["nested"]["keep"] == 1
    assert sanitized["objects"][0]["keep"] == 2
    assert sanitized["lists"][0][0]["keep"] == 3
    assert row == before


def test_empty_row_is_preserved() -> None:
    assert sanitize_status_row({}) == {}


@pytest.mark.parametrize("row", [[], ["not", "an", "object"]])
def test_non_object_row_is_refused(row: list[str]) -> None:
    with pytest.raises(StatusRowRefused):
        sanitize_status_row(row)  # type: ignore[arg-type]


@pytest.mark.parametrize(
    ("started", "ended", "existing_duration", "expected_duration"),
    [
        ("2026-01-01T00:00:00.250000+00:00", "2026-01-01T00:00:02.750000+00:00", 99, 2),
        ("2026-01-01T00:00:02.750000+00:00", "2026-01-01T00:00:00.250000+00:00", 99, -2),
    ],
)
def test_session_pair_computes_truncated_duration(
    started: str, ended: str, existing_duration: int, expected_duration: int
) -> None:
    sanitized = sanitize_status_row(
        {
            "session_started_at": started,
            "session_ended_at": ended,
            "session_duration_s": existing_duration,
        }
    )

    assert sanitized == {"session_duration_s": expected_duration}


@pytest.mark.parametrize(
    "row",
    [
        {"session_started_at": "2026-01-01T00:00:00+00:00", "session_duration_s": 9},
        {"session_ended_at": "2026-01-01T00:00:01+00:00", "session_duration_s": 9},
        {
            "session_started_at": None,
            "session_ended_at": "2026-01-01T00:00:01+00:00",
            "session_duration_s": 9,
        },
        {
            "session_started_at": "2026-01-01T00:00:00+00:00",
            "session_ended_at": None,
            "session_duration_s": 9,
        },
        {
            "session_started_at": "not-a-date",
            "session_ended_at": "2026-01-01T00:00:01+00:00",
            "session_duration_s": 9,
        },
        {
            "session_started_at": "2026-01-01T00:00:00+00:00",
            "session_ended_at": "not-a-date",
            "session_duration_s": 9,
        },
        {
            "session_started_at": 42,
            "session_ended_at": "2026-01-01T00:00:01+00:00",
            "session_duration_s": 9,
        },
    ],
)
def test_incomplete_or_unparseable_session_pair_preserves_existing_duration(
    row: dict[str, Any],
) -> None:
    assert sanitize_status_row(row) == {"session_duration_s": 9}


def test_nested_session_names_are_not_replaced() -> None:
    row = {
        "nested": {
            "session_started_at": "2026-01-01T00:00:00+00:00",
            "session_ended_at": "2026-01-01T00:00:10+00:00",
        }
    }

    assert sanitize_status_row(row) == row
    assert row["nested"]["session_started_at"] == "2026-01-01T00:00:00+00:00"


@pytest.mark.parametrize(
    ("started", "ended"),
    [
        ("2026-01-01T00:00:00", "2026-01-01T00:00:01+00:00"),
        ("2026-01-01T00:00:00+00:00", "2026-01-01T00:00:01"),
    ],
)
def test_mixed_naive_and_aware_session_pair_is_refused(started: str, ended: str) -> None:
    with pytest.raises(SessionTimesNotSubtractable):
        sanitize_status_row({"session_started_at": started, "session_ended_at": ended})


@pytest.mark.parametrize(
    ("started", "ended", "expected"),
    [
        ("20260101T000000Z", "20260101T000002Z", 2),
        ("2026-01-01T00:00:00.5Z", "2026-01-01T00:00:02.1234567Z", 1),
        ("2026-01-01 00:00:00", "2026-01-01 00:00:03", 3),
        ("2026-01-01", "2026-01-02", 86400),
        ("2026-01-01T00:00:00-00:00", "2026-01-01T00:00:01+00:00", 1),
    ],
)
def test_session_timestamp_grammar_matches_pinned_core(
    started: str, ended: str, expected: int
) -> None:
    sanitized = sanitize_status_row({"session_started_at": started, "session_ended_at": ended})
    assert sanitized == {"session_duration_s": expected}


@pytest.mark.parametrize(
    ("started", "ended", "expected"),
    [
        ("2026-01-01T00:00:00+05", "2026-01-01T01:00:00+05", b'{"session_duration_s": 3600}\n'),
        ("20260101", "20260102", b'{"session_duration_s": 86400}\n'),
        ("2026-W01-1", "2026-W01-2", b'{"session_duration_s": 86400}\n'),
    ],
)
def test_encoder_matches_core_for_forms_311_added_over_310(
    started: str, ended: str, expected: bytes
) -> None:
    assert encode_status_row({"session_started_at": started, "session_ended_at": ended}) == expected


def test_encoder_matches_sanitize_then_default_spaced_sorted_json() -> None:
    row = {
        "z": [1, 1.0, -0.0, True, False, None, {"b": 2, "a": 1}],
        "a": 2**80,
    }
    sanitized = sanitize_status_row(row)

    assert encode_status_row(row) == (json.dumps(sanitized, sort_keys=True) + "\n").encode("ascii")


def test_encoder_sorts_keys_by_code_point_at_every_depth() -> None:
    row = {"\U0001f600": "astral", "\uffff": "bmp", "a": {"é": 2, "z": 1}}

    assert encode_status_row(row) == (
        b'{"a": {"z": 1, "\\u00e9": 2}, "\\uffff": "bmp", "\\ud83d\\ude00": "astral"}\n'
    )


def test_encoder_escapes_non_ascii_and_astral_characters() -> None:
    assert encode_status_row({"text": "é—😀"}) == (b'{"text": "\\u00e9\\u2014\\ud83d\\ude00"}\n')


def test_encoder_escapes_controls_quotes_backslashes_and_lone_surrogates() -> None:
    assert encode_status_row({"text": '\b\f\n\r\t\x00\x1f\x7f"\\/'}) == (
        b'{"text": "\\b\\f\\n\\r\\t\\u0000\\u001f\\u007f\\"\\\\/"}\n'
    )
    assert encode_status_row({"text": "\ud800"}) == b'{"text": "\\ud800"}\n'


@pytest.mark.parametrize(
    ("value", "expected"),
    [
        (1.0, b'{"n": 1.0}\n'),
        (-0.0, b'{"n": -0.0}\n'),
        (0.1, b'{"n": 0.1}\n'),
        (1e-5, b'{"n": 1e-05}\n'),
        (0.0001, b'{"n": 0.0001}\n'),
        (1e16, b'{"n": 1e+16}\n'),
        (1000000000000000.0, b'{"n": 1000000000000000.0}\n'),
        (5e-324, b'{"n": 5e-324}\n'),
        (1.7976931348623157e308, b'{"n": 1.7976931348623157e+308}\n'),
        (2**80, b'{"n": 1208925819614629174706176}\n'),
        (-(2**70), b'{"n": -1180591620717411303424}\n'),
        (True, b'{"n": true}\n'),
        (False, b'{"n": false}\n'),
        (None, b'{"n": null}\n'),
    ],
)
def test_encoder_pins_python_number_and_scalar_spellings(value: Any, expected: bytes) -> None:
    assert encode_status_row({"n": value}) == expected


@pytest.mark.parametrize("value", [float("nan"), float("inf"), float("-inf")])
def test_non_finite_numbers_are_refused_anywhere(value: float) -> None:
    with pytest.raises(NonFiniteNumber):
        encode_status_row({"nested": [{"value": value}]})


def test_status_refusals_are_value_errors() -> None:
    assert issubclass(StatusRowRefused, ValueError)
    assert issubclass(NonFiniteNumber, StatusRowRefused)
    assert issubclass(SessionTimesNotSubtractable, StatusRowRefused)
