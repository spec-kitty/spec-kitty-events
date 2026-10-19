"""Pins the deliberate R2 difference between the package and the producer row.

The ``run.significance.evaluated.v1`` producer row (unchanged) agrees with the
generated ``significance_evaluated_payload`` schema on every property except
``actor.actor_id``: the row allows it to be null or absent (core emits
``str | None``); the package requires a non-empty string (operator ruling R2).
Consumers emitting a null ``actor_id`` are accepted by the row and refused by
``validate_event`` until core sets it.
"""

from __future__ import annotations

import copy
import json
from pathlib import Path
from typing import Any, Dict

import pytest

import spec_kitty_events
from spec_kitty_events.conformance import validate_event
from spec_kitty_events.producer_contract import data_schema

PKG = Path(spec_kitty_events.__file__).parent
ROWS = PKG / "producer_contract" / "fixtures" / "rows"
ROW_NAME = "run.significance.evaluated.v1"


def _row_schema() -> Dict[str, Any]:
    return data_schema(ROW_NAME)


def _pkg_schema() -> Dict[str, Any]:
    return json.loads((PKG / "schemas" / "significance_evaluated_payload.schema.json").read_text())


def _pkg_actor() -> Dict[str, Any]:
    schema = _pkg_schema()
    binding: Dict[str, Any] = schema["$defs"]["RaciRoleBinding"]
    return binding


def test_property_names_match() -> None:
    assert set(_row_schema()["properties"]) == set(_pkg_schema()["properties"])


def test_enums_match() -> None:
    row, pkg = _row_schema()["properties"], _pkg_schema()["properties"]
    assert row["effective_band"]["enum"] == pkg["effective_band"]["enum"]
    assert (
        row["actor"]["properties"]["actor_type"]["enum"]
        == _pkg_actor()["properties"]["actor_type"]["enum"]
    )


def test_identifier_min_length_matches() -> None:
    row, pkg = _row_schema()["properties"], _pkg_schema()["properties"]
    for name in ("run_id", "decision_id", "step_id"):
        assert row[name]["minLength"] == pkg[name]["minLength"] == 1


def test_required_sets_match_except_actor_id() -> None:
    assert set(_row_schema()["required"]) == set(_pkg_schema()["required"])
    row_actor = _row_schema()["properties"]["actor"]
    assert set(row_actor["required"]) == {"actor_type"}
    assert set(_pkg_actor()["required"]) == {"actor_type", "actor_id"}


def test_r2_exception_is_pinned() -> None:
    """Fails if the row ever drops null or the model ever allows it (R2)."""
    row_id = _row_schema()["properties"]["actor"]["properties"]["actor_id"]
    assert row_id["type"] == ["string", "null"]
    pkg_id = _pkg_actor()["properties"]["actor_id"]
    assert pkg_id["type"] == "string"
    assert pkg_id["minLength"] == 1


def _row_data(path: Path) -> Dict[str, Any]:
    data: Dict[str, Any] = json.loads(path.read_text())["input"]["data"]
    return data


def _valid_rows() -> list[Path]:
    return sorted((ROWS / "valid").glob(f"{ROW_NAME}*.json"))


def test_there_is_a_valid_row_to_check() -> None:
    assert _valid_rows()


@pytest.mark.parametrize("path", _valid_rows(), ids=lambda p: p.name)
def test_valid_rows_with_actor_id_accepted(path: Path) -> None:
    data = _row_data(path)
    if isinstance(data["actor"].get("actor_id"), str) and data["actor"]["actor_id"]:
        assert validate_event(data, "SignificanceEvaluated").valid
    else:
        assert not validate_event(data, "SignificanceEvaluated").valid


@pytest.mark.parametrize("path", _valid_rows(), ids=lambda p: p.name)
def test_null_actor_id_row_data_refused_by_package_accepted_by_row(path: Path) -> None:
    from jsonschema import Draft202012Validator

    data = copy.deepcopy(_row_data(path))
    data["actor"]["actor_id"] = None
    assert Draft202012Validator(_row_schema()).is_valid(data)
    assert not validate_event(data, "SignificanceEvaluated").valid
    del data["actor"]["actor_id"]
    assert Draft202012Validator(_row_schema()).is_valid(data)
    assert not validate_event(data, "SignificanceEvaluated").valid
