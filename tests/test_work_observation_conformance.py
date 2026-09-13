"""Conformance tests for the durable WorkObservation family (#55).

Wires the ``work_observation`` fixture category into the package's
loader-driven pattern (``conformance/fixtures/<category>/{valid,invalid}``
+ ``manifest.json`` + ``load_fixtures()``), mirroring
``tests/test_harness_observation_conformance.py``, and machine-tests the
two replay-stream fixtures against their golden classifications — the
exact-duplicate / same-ID-different-payload / late / gap semantics every
consumer must agree on.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest
from pydantic import ValidationError

from spec_kitty_events.conformance.loader import load_fixtures
from spec_kitty_events.conformance.validators import validate_event
from spec_kitty_events.models import Event
from spec_kitty_events.strict import validate_strict_envelope
from spec_kitty_events.work_observation import (
    WORK_OBSERVATION,
    WorkObservationPayload,
)
from spec_kitty_events.work_replay import classify_work_stream, work_item_from_event

_FIXTURES = (
    Path(__file__).resolve().parent.parent
    / "src"
    / "spec_kitty_events"
    / "conformance"
    / "fixtures"
)


@pytest.fixture
def work_observation_fixtures():
    return load_fixtures("work_observation")


def test_fixtures_loaded(work_observation_fixtures) -> None:
    """29 valid + 15 invalid fixtures are on disk and manifest-registered."""
    valid = [f for f in work_observation_fixtures if f.expected_valid]
    invalid = [f for f in work_observation_fixtures if not f.expected_valid]
    assert len(valid) == 29
    assert len(invalid) == 15


@pytest.mark.parametrize(
    "fixture",
    load_fixtures("work_observation"),
    ids=lambda f: f.id,
)
def test_work_observation_conformance(fixture) -> None:
    """Validate each fixture payload against WorkObservationPayload."""
    if fixture.expected_valid:
        WorkObservationPayload.model_validate(fixture.payload)
    else:
        with pytest.raises(ValidationError):
            WorkObservationPayload.model_validate(fixture.payload)


@pytest.mark.parametrize(
    "fixture",
    load_fixtures("work_observation"),
    ids=lambda f: f.id,
)
def test_work_observation_dual_layer_conformance(fixture) -> None:
    """The packaged validate_event() entry point agrees with the model on
    every fixture — the same path consumers (saas#1814, zeitgeist#304)
    call, including the JSON-schema layer."""
    result = validate_event(fixture.payload, WORK_OBSERVATION, strict=True)
    assert result.valid is fixture.expected_valid, fixture.id


def test_acceptance_scenario_fixtures_exist() -> None:
    """The #55 acceptance scenarios are each pinned by a named fixture:
    one human + two concurrent agents, delegated session, credential
    rotation, retry attempt, same-name missions, mission/repo rename,
    cross-repo programme linkage."""
    by_id = {f.id: f for f in load_fixtures("work_observation")}
    required = {
        "work-observation-valid-human-session-started",
        "work-observation-valid-two-agents-first",
        "work-observation-valid-two-agents-second",
        "work-observation-valid-delegated-session",
        "work-observation-valid-credential-rotation",
        "work-observation-valid-retry-attempt",
        "work-observation-valid-same-name-missions-first",
        "work-observation-valid-same-name-missions-second",
        "work-observation-valid-mission-rename",
        "work-observation-valid-repo-rename",
        "work-observation-valid-cross-repo-programme",
    }
    assert required <= by_id.keys(), sorted(required - by_id.keys())


# ── replay streams: golden classifications ──────────────────────────────────


def _golden_case(stream_name: str, output_name: str) -> None:
    stream_path = _FIXTURES / "work_observation" / "replay" / stream_name
    output_path = _FIXTURES / "work_observation" / "replay" / output_name
    events = [
        Event.model_validate(json.loads(line))
        for line in stream_path.read_text(encoding="utf-8").splitlines()
        if line
    ]
    assert events, stream_name
    report = classify_work_stream([work_item_from_event(e) for e in events])
    golden = json.loads(output_path.read_text(encoding="utf-8"))
    assert [i.event_id for i in report.accepted] == golden["accepted_event_ids"]
    assert [i.event_id for i in report.duplicates] == golden["duplicates"]
    assert [{"event_id": c.event_id, "reason": c.reason.value} for c in report.conflicts] == golden[
        "conflicts"
    ]
    assert [i.event_id for i in report.late] == golden["late"]
    assert [
        {
            "producer_id": g.producer_id,
            "instance_id": g.instance_id,
            "expected": g.expected,
            "observed": g.observed,
        }
        for g in report.gaps
    ] == golden["gaps"]


def test_duplicate_and_conflict_stream_matches_golden() -> None:
    _golden_case("duplicate_and_conflict_stream.jsonl", "duplicate_and_conflict_output.json")


def test_late_and_gap_stream_matches_golden() -> None:
    _golden_case("late_and_gap_stream.jsonl", "late_and_gap_output.json")


# ── strict profile integration ───────────────────────────────────────────────


def test_strict_envelope_accepts_a_valid_work_envelope() -> None:
    fixture = next(
        f
        for f in load_fixtures("work_observation")
        if f.id == "work-observation-valid-human-session-started"
    )
    record = {
        "event_id": "01J0000000000000000000WRK1",
        "event_type": WORK_OBSERVATION,
        "aggregate_id": "mission/m-alpha-001",
        "payload": fixture.payload,
        "timestamp": "2026-01-01T00:00:00Z",
        "build_id": "build-r1",
        "node_id": "node-r1",
        "lamport_clock": 0,
        "causation_id": None,
        "project_uuid": "12345678-1234-5678-1234-567812345678",
        "project_slug": None,
        "correlation_id": "01J0000000000000000000WRKC",
        "schema_version": "3.0.0",
        "data_tier": 0,
    }
    assert validate_strict_envelope(record) == ()


def test_strict_envelope_rejects_server_owned_fields() -> None:
    fixture = next(
        f
        for f in load_fixtures("work_observation")
        if f.id == "work-observation-valid-human-session-started"
    )
    record = {
        "event_id": "01J0000000000000000000WRK1",
        "event_type": WORK_OBSERVATION,
        "aggregate_id": "mission/m-alpha-001",
        "payload": {**fixture.payload, "received_at": "2026-01-01T00:00:05Z"},
        "timestamp": "2026-01-01T00:00:00Z",
        "build_id": "build-r1",
        "node_id": "node-r1",
        "lamport_clock": 0,
        "causation_id": None,
        "project_uuid": "12345678-1234-5678-1234-567812345678",
        "project_slug": None,
        "correlation_id": "01J0000000000000000000WRKC",
        "schema_version": "3.0.0",
        "data_tier": 0,
    }
    errors = validate_strict_envelope(record)
    assert errors, "a server-owned received_at field must fail closed"
    assert any(
        "received_at" in str(error.details.get("forbidden_keys", error.details))
        or error.details.get("forbidden_keys")
        for error in errors
    )
