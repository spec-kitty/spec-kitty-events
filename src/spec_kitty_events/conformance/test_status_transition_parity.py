"""Shape and NFR-002 coverage checks for the status-transition parity corpus."""

from __future__ import annotations

import json
from importlib import resources
from typing import Any

import pytest

from spec_kitty_events.conformance.parity_corpus import (
    LEGAL_EDGES,
    REASON_URNS,
    WRITE_REFUSAL_CODES,
    coverage,
    load_parity_cases,
    load_unreachable,
)

_CASES = load_parity_cases()


def _case_resources() -> list[tuple[str, dict[str, Any]]]:
    case_dir = resources.files("spec_kitty_events.conformance").joinpath(
        "status_transition_parity/cases"
    )
    documents: list[tuple[str, dict[str, Any]]] = []
    for entry in case_dir.iterdir():
        if entry.name.endswith(".json"):
            document = json.loads(entry.read_text(encoding="utf-8"))
            assert isinstance(document, dict)
            documents.append((entry.name.removesuffix(".json"), document))
    return documents


def test_every_case_has_a_unique_id_matching_its_file_stem() -> None:
    documents = _case_resources()
    ids = [document["id"] for _, document in documents]
    assert len(ids) == len(set(ids))
    assert len(ids) == len(_CASES)
    assert all(document["id"] == stem for stem, document in documents)


def test_every_case_validates_against_the_json_schema() -> None:
    jsonschema = pytest.importorskip(
        "jsonschema", reason="Install spec-kitty-events[conformance] to validate parity cases"
    )
    schema_resource = resources.files("spec_kitty_events.conformance").joinpath(
        "status_transition_parity/case.schema.json"
    )
    schema = json.loads(schema_resource.read_text(encoding="utf-8"))
    validator = jsonschema.Draft202012Validator(schema)
    validator.check_schema(schema)
    for case in _CASES:
        errors = sorted(validator.iter_errors(case), key=lambda error: error.json_path)
        assert not errors, f"{case['id']}: {[error.message for error in errors]}"


def test_refusal_vocabulary_is_closed() -> None:
    assert len(REASON_URNS) == 15
    for case in _CASES:
        expected = case["expected"]
        if expected["result"] == "refusal":
            assert expected["code"] in WRITE_REFUSAL_CODES
            reason = expected["reason"]
            assert reason is None or reason in REASON_URNS


def test_nfr_002_coverage() -> None:
    result = coverage(_CASES)
    assert result.accepted_unforced_edges == LEGAL_EDGES
    assert len(result.accepted_unforced_edges) == 29
    assert result.batch_count >= 5
    assert result.cross_wp_batch_count >= 2
    assert result.total >= 150


def test_every_reason_is_reached_or_has_an_unreachable_cause() -> None:
    refused = coverage(_CASES).refused_reasons
    unreachable = load_unreachable()
    assert set(unreachable) <= REASON_URNS
    assert all(cause.strip() for cause in unreachable.values())
    assert refused.isdisjoint(unreachable)
    assert refused | set(unreachable) == REASON_URNS
