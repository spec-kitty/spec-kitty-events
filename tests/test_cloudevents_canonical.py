"""Canonical serialization: the byte rules every binding reproduces (research R-4)."""

from __future__ import annotations

import math

import pytest

from spec_kitty_events.cloudevents import canonical_bytes, canonical_text


def test_keys_are_sorted_by_code_point_at_every_depth() -> None:
    value = {"b": 1, "a": {"z": [3, {"y": 1, "x": 2}], "B": None}, "Ä": True}
    assert canonical_text(value) == '{"a":{"B":null,"z":[3,{"x":2,"y":1}]},"b":1,"Ä":true}'


def test_no_whitespace_and_arrays_keep_their_order() -> None:
    assert canonical_text({"k": [3, 1, 2], "e": [], "o": {}}) == '{"e":[],"k":[3,1,2],"o":{}}'


def test_non_ascii_is_written_as_is_in_utf8() -> None:
    assert canonical_bytes({"s": "Groß → 漢字"}) == '{"s":"Groß → 漢字"}'.encode("utf-8")


@pytest.mark.parametrize(
    "text, expected",
    [
        ('a"b', '"a\\"b"'),
        ("a\\b", '"a\\\\b"'),
        ("line\nnext\ttab\r", '"line\\nnext\\ttab\\r"'),
        ("\b\f", '"\\b\\f"'),
        ("\x00\x1f\x7f", '"\\u0000\\u001f\x7f"'),
        ("a/b", '"a/b"'),
        (" ", '" "'),
    ],
)
def test_string_escapes(text: str, expected: str) -> None:
    assert canonical_text(text) == expected


@pytest.mark.parametrize(
    "number, expected",
    [
        (0, "0"),
        (-7, "-7"),
        (2**40, "1099511627776"),
        (1.5, "1.5"),
        (0.1, "0.1"),
        (1e-05, "1e-05"),
        (0.0001, "0.0001"),
        (1e16, "1e+16"),
        (1234567890123456.0, "1234567890123456.0"),
        (-0.0, "-0.0"),
        (2.0, "2.0"),
    ],
)
def test_numbers(number: object, expected: str) -> None:
    assert canonical_text(number) == expected


@pytest.mark.parametrize("bad", [math.nan, math.inf, -math.inf])
def test_nan_and_infinities_are_refused(bad: float) -> None:
    with pytest.raises(ValueError):
        canonical_bytes({"x": bad})


def test_non_json_values_are_refused() -> None:
    with pytest.raises(TypeError):
        canonical_bytes({"x": object()})
