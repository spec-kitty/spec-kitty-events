"""CPython 3.11 ``fromisoformat`` parity for the 3.10 timestamp parser.

The oracle is the vector set shared with the Java ``PyIsoDateTime`` binding,
generated from CPython 3.11 by
``scripts/conformance/generate_fromisoformat_vectors.py``. Every vector must
produce the same naive or UTC-epoch microsecond value through
:func:`spec_kitty_events._iso8601._parse_isoformat_311` on 3.10 and on later
interpreters, and the same accept/reject boundary.
"""

from __future__ import annotations

import json
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List

import pytest

from spec_kitty_events._iso8601 import (
    _parse_isoformat_311,
    normalize_iso8601_shape,
    parse_iso8601_datetime,
)

_VECTOR_PATH = (
    Path(__file__).resolve().parents[1]
    / "service/domain-model/src/test/resources/pyisodatetime/vectors.json"
)
_VECTORS: List[Dict[str, Any]] = json.loads(_VECTOR_PATH.read_text(encoding="utf-8"))
_VECTOR_IDS = [f"{index}-{vector['branch']}" for index, vector in enumerate(_VECTORS)]


def _epoch_micros(value: datetime, aware: bool) -> int:
    if aware:
        value = value.astimezone(timezone.utc)
    delta = value.replace(tzinfo=None) - datetime(1970, 1, 1)
    return (delta.days * 86_400 + delta.seconds) * 1_000_000 + delta.microseconds


def _assert_matches_vector(parsed: datetime, vector: Dict[str, Any]) -> None:
    if vector["aware"]:
        assert parsed.tzinfo is not None
        assert _epoch_micros(parsed, aware=True) == vector["utc_epoch_micros"]
    else:
        assert parsed.tzinfo is None
        assert _epoch_micros(parsed, aware=False) == vector["naive_micros"]


def test_vectors_are_the_generated_cpython_311_oracle() -> None:
    assert len(_VECTORS) >= 90
    assert {vector["branch"] for vector in _VECTORS} >= {"valid", "date_length"}
    assert sum(1 for vector in _VECTORS if vector["parses"]) >= 50


@pytest.mark.parametrize("vector", _VECTORS, ids=_VECTOR_IDS)
def test_311_parser_matches_every_vector(vector: Dict[str, Any]) -> None:
    if not vector["parses"]:
        with pytest.raises(ValueError):
            _parse_isoformat_311(vector["text"])
        return
    _assert_matches_vector(_parse_isoformat_311(vector["text"]), vector)


@pytest.mark.skipif(
    sys.version_info >= (3, 11),
    reason="3.11+ keeps its native datetime.fromisoformat path; the port is tested above",
)
@pytest.mark.parametrize("vector", _VECTORS, ids=_VECTOR_IDS)
def test_floor_status_parser_matches_every_vector(vector: Dict[str, Any]) -> None:
    if not vector["parses"]:
        with pytest.raises(ValueError):
            parse_iso8601_datetime(vector["text"])
        return
    _assert_matches_vector(parse_iso8601_datetime(vector["text"]), vector)


@pytest.mark.skipif(
    sys.version_info < (3, 11),
    reason="3.11+ must keep delegating to datetime.fromisoformat as before",
)
@pytest.mark.parametrize("vector", _VECTORS, ids=_VECTOR_IDS)
def test_native_path_is_unchanged_on_311_plus(vector: Dict[str, Any]) -> None:
    text = vector["text"]
    try:
        expected = datetime.fromisoformat(normalize_iso8601_shape(text))
    except ValueError:
        with pytest.raises(ValueError):
            parse_iso8601_datetime(text)
        return
    assert parse_iso8601_datetime(text) == expected
