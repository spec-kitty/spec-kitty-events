"""Ledger-records fold for DecisionPoint events (#120, FR-006)."""

from __future__ import annotations

import random
import uuid
from datetime import datetime, timezone
from typing import Any

import pytest
from ulid import ULID

from spec_kitty_events.decisionpoint import (
    DECISION_POINT_OPENED,
    DECISION_POINT_RESOLVED,
    DecisionPointFoldError,
    DecisionPointState,
    reduce_decision_point_records,
)
from spec_kitty_events.models import Event, SpecKittyEventsError
from spec_kitty_events.persistence import LedgerValidationError

_PROJECT = uuid.UUID("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")


def _ts(n: int) -> str:
    return datetime(2026, 3, 1, 10, 0, n, tzinfo=timezone.utc).isoformat()


def _common(dp_id: str, n: int) -> dict[str, Any]:
    return {
        "origin_surface": "planning_interview",
        "decision_point_id": dp_id,
        "mission_id": "m-1",
        "run_id": "run-1",
        "mission_slug": "slug",
        "mission_type": "software-dev",
        "state_entered_at": _ts(n),
        "recorded_at": _ts(n),
    }


def _opened_payload(dp_id: str, n: int) -> dict[str, Any]:
    return {
        **_common(dp_id, n),
        "origin_flow": "plan",
        "phase": "P1",
        "question": "Q?",
        "options": ["a", "b"],
        "input_key": "k",
        "step_id": "s",
        "actor_id": "svc",
        "actor_type": "service",
    }


def _resolved_payload(dp_id: str, n: int, outcome: str) -> dict[str, Any]:
    payload = {
        **_common(dp_id, n),
        "terminal_outcome": outcome,
        "other_answer": False,
        "resolved_by": "owner",
        "closed_locally_while_widened": False,
        "actual_participants": [],
    }
    if outcome == "resolved":
        payload["final_answer"] = "yes"
    else:
        payload["rationale"] = "later"
    return payload


def _record(event_type: str, payload: dict[str, Any], n: int) -> dict[str, Any]:
    event = Event(
        event_id=str(ULID()),
        event_type=event_type,
        aggregate_id=f"dp/{payload['decision_point_id']}",
        payload=payload,
        timestamp=datetime(2026, 3, 1, 10, 0, n, tzinfo=timezone.utc),
        build_id="b",
        node_id="n",
        lamport_clock=n,
        project_uuid=_PROJECT,
        correlation_id=str(ULID()),
    )
    return event.model_dump(mode="json")


def _reopen_stream(dp_id: str) -> list[dict[str, Any]]:
    return [
        _record(DECISION_POINT_OPENED, _opened_payload(dp_id, 1), 1),
        _record(DECISION_POINT_RESOLVED, _resolved_payload(dp_id, 2, "deferred"), 2),
        _record(DECISION_POINT_RESOLVED, _resolved_payload(dp_id, 3, "resolved"), 3),
    ]


def test_error_is_a_library_error() -> None:
    assert issubclass(DecisionPointFoldError, SpecKittyEventsError)


def test_groups_by_decision_and_folds_reopen() -> None:
    records = _reopen_stream("dp-a") + [
        _record(DECISION_POINT_OPENED, _opened_payload("dp-b", 1), 1)
    ]
    random.Random(7).shuffle(records)
    result = reduce_decision_point_records(records)
    assert set(result) == {"dp-a", "dp-b"}
    assert result["dp-a"].state == DecisionPointState.RESOLVED
    assert result["dp-a"].reopened_from_deferred is True
    assert result["dp-b"].state == DecisionPointState.OPEN


def test_non_decision_records_are_skipped() -> None:
    glossary = {
        "event_type": "GlossaryScopeActivated",
        "event_id": "6f1c2a8e-7d1b-4b4e-9a53-0d2f2f6a1c11",
        "timestamp": "2026-10-18T12:00:00+00:00",
        "mission_id": "mission-001",
        "run_id": "run-001",
        "scope_id": "spec_kitty_core",
        "glossary_version_id": "v3",
    }
    result = reduce_decision_point_records([glossary, *_reopen_stream("dp-a")])
    assert list(result) == ["dp-a"]
    assert reduce_decision_point_records([glossary]) == {}


def test_strict_rejects_malformed_record() -> None:
    bad = _reopen_stream("dp-a")
    bad[0]["payload"] = {"decision_point_id": "dp-a"}
    with pytest.raises(LedgerValidationError):
        reduce_decision_point_records(bad)


def test_strict_rejects_flat_decision_record() -> None:
    flat = {"event_type": DECISION_POINT_OPENED, **_opened_payload("dp-a", 1)}
    with pytest.raises(LedgerValidationError, match="full envelope"):
        reduce_decision_point_records([flat])


def test_non_strict_skips_malformed_and_flat_records() -> None:
    records = _reopen_stream("dp-a")
    records[0]["payload"] = {"decision_point_id": "dp-a"}
    flat = {"event_type": DECISION_POINT_OPENED, **_opened_payload("dp-z", 1)}
    result = reduce_decision_point_records(records + [flat], strict=False)
    assert set(result) == {"dp-a"}
    assert "invalid_transition" in [a.kind for a in result["dp-a"].anomalies]


def _unfoldable() -> list[dict[str, Any]]:
    return [
        _record(DECISION_POINT_OPENED, _opened_payload("dp-a", 1), 1),
        _record(DECISION_POINT_RESOLVED, _resolved_payload("dp-a", 2, "deferred"), 2),
        _record(DECISION_POINT_RESOLVED, _resolved_payload("dp-a", 3, "canceled"), 3),
    ]


def test_strict_raises_on_unfoldable_terminal_combination() -> None:
    with pytest.raises(DecisionPointFoldError, match="dp-a"):
        reduce_decision_point_records(_unfoldable())


def test_non_strict_keeps_unfoldable_as_anomaly() -> None:
    result = reduce_decision_point_records(_unfoldable(), strict=False)
    state = result["dp-a"]
    assert [a.kind for a in state.anomalies] == ["unfoldable_terminal_combination"]
    assert state.terminal_outcome is not None and state.terminal_outcome.value == "deferred"
