"""Cross-anchors the ``run.decision.timeoutexpired.v1`` producer row to the package payload.

The row mirrors core's ``TimeoutExpiredPayload``. It agrees with the generated
``decision_timeout_expired_payload`` schema on every property name, enum, bound and
required member, with two deliberate differences pinned below:

- ``actor_id`` (in ``actor`` and in each ``escalation_targets`` item) may be null or
  absent in the row (core emits ``str | None``); the package requires a non-empty
  string (operator ruling R2, converged by #131).
- ``raci_snapshot`` is an open object in the row (core types it ``dict[str, Any]``);
  the package validates it as a structured ``ResolvedRaciSnapshot``.

A transcription slip between the row and the payload fails one of these tests.
"""

from __future__ import annotations

import copy
import json
from pathlib import Path
from typing import Any, Dict, List

import pytest
from jsonschema import Draft202012Validator

import spec_kitty_events
from spec_kitty_events.conformance import validate_event
from spec_kitty_events.producer_contract import data_schema

PKG = Path(spec_kitty_events.__file__).parent
ROWS = PKG / "producer_contract" / "fixtures" / "rows"
ROW_NAME = "run.decision.timeoutexpired.v1"
EVENT_TYPE = "DecisionTimeoutExpired"
BINDINGS = ("actor", "escalation_targets")


def _row() -> Dict[str, Any]:
    return data_schema(ROW_NAME)


def _pkg() -> Dict[str, Any]:
    path = PKG / "schemas" / "decision_timeout_expired_payload.schema.json"
    schema: Dict[str, Any] = json.loads(path.read_text(encoding="utf-8"))
    return schema


def _pkg_binding() -> Dict[str, Any]:
    binding: Dict[str, Any] = _pkg()["$defs"]["RaciRoleBinding"]
    return binding


def _row_binding(name: str) -> Dict[str, Any]:
    prop: Dict[str, Any] = _row()["properties"][name]
    return prop["items"] if name == "escalation_targets" else prop


def _row_data() -> List[Dict[str, Any]]:
    out = []
    for path in sorted((ROWS / "valid").glob(f"{ROW_NAME}*.json")):
        out.append(json.loads(path.read_text(encoding="utf-8"))["input"]["data"])
    assert out, "no valid fixture for the row"
    return out


def test_property_names_match() -> None:
    assert set(_row()["properties"]) == set(_pkg()["properties"])


def test_required_sets_match() -> None:
    assert set(_row()["required"]) == set(_pkg()["required"])


def test_escalation_targets_is_an_array_in_both() -> None:
    assert _row()["properties"]["escalation_targets"]["type"] == "array"
    assert _pkg()["properties"]["escalation_targets"]["type"] == "array"


def test_enums_match() -> None:
    row, pkg = _row()["properties"], _pkg()["properties"]
    assert row["effective_band"]["enum"] == pkg["effective_band"]["enum"]
    pkg_types = _pkg_binding()["properties"]["actor_type"]["enum"]
    for name in BINDINGS:
        assert _row_binding(name)["properties"]["actor_type"]["enum"] == pkg_types


def test_bounds_match() -> None:
    row, pkg = _row()["properties"], _pkg()["properties"]
    for name in ("run_id", "decision_id", "step_id"):
        assert row[name]["minLength"] == pkg[name]["minLength"] == 1
    for key in ("type", "exclusiveMinimum"):
        assert row["timeout_configured_seconds"][key] == pkg["timeout_configured_seconds"][key]


@pytest.mark.parametrize("name", BINDINGS)
def test_binding_shape_matches_except_actor_id(name: str) -> None:
    row, pkg = _row_binding(name), _pkg_binding()
    assert set(row["properties"]) == set(pkg["properties"])
    assert row["additionalProperties"] is False
    assert set(pkg["required"]) - set(row["required"]) == {"actor_id"}


@pytest.mark.parametrize("name", BINDINGS)
def test_r2_exception_is_pinned(name: str) -> None:
    assert _row_binding(name)["properties"]["actor_id"]["type"] == ["string", "null"]
    pkg_id = _pkg_binding()["properties"]["actor_id"]
    assert pkg_id["type"] == "string"
    assert pkg_id["minLength"] == 1


def test_raci_snapshot_open_in_row_structured_in_package() -> None:
    assert _row()["properties"]["raci_snapshot"] == {"type": "object"}
    assert _pkg()["properties"]["raci_snapshot"]["$ref"].endswith("ResolvedRaciSnapshot")


def test_valid_rows_are_accepted_by_the_package() -> None:
    for data in _row_data():
        assert validate_event(data, EVENT_TYPE).valid


@pytest.mark.parametrize("name", BINDINGS)
def test_null_actor_id_accepted_by_row_refused_by_package(name: str) -> None:
    data = copy.deepcopy(_row_data()[0])
    if name == "actor":
        data["actor"]["actor_id"] = None
    else:
        data["escalation_targets"] = [{"actor_type": "human", "actor_id": None}]
    assert Draft202012Validator(_row()).is_valid(data)
    assert not validate_event(data, EVENT_TYPE).valid
