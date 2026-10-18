"""Tests for the glossary reducer on core-shaped payloads (11.0, #120)."""

from __future__ import annotations

import json
import uuid
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional

import pytest
from hypothesis import given, settings
from hypothesis import strategies as st

from spec_kitty_events import (
    Event,
    ReducedGlossaryState,
    SpecKittyEventsError,
    reduce_glossary_events,
)

UTC = timezone.utc
MISSION = "m1"
RUN = "run-1"
CONFLICT = "0b1c2d3e-4f50-4a6b-8c7d-9e0f1a2b3c4d"
HASH = "a" * 64
TOKEN = "5f6a7b8c-9d0e-4f1a-8b2c-3d4e5f6a7b8c"
TS = "2026-10-18T12:00:00+00:00"

_clock_counter = 0


def make_event(
    event_type: str,
    payload: Dict[str, Any],
    event_id: Optional[str] = None,
    lamport_clock: Optional[int] = None,
    clocked: bool = True,
    second: int = 0,
) -> Event:
    """Build an Event; ``clocked=False`` yields an unclocked envelope."""
    global _clock_counter
    _clock_counter += 1
    seq = lamport_clock if lamport_clock is not None else _clock_counter
    return Event(
        event_id=event_id or f"01HX{seq:022d}",
        event_type=event_type,
        aggregate_id="mission-001",
        payload=payload,
        timestamp=datetime(2024, 1, 1, 0, 0, second, tzinfo=UTC).isoformat(),
        build_id="test-build",
        node_id="test-node",
        lamport_clock=seq if clocked else None,
        correlation_id=f"01CX{seq:022d}",
        project_uuid=uuid.UUID("12345678-1234-5678-1234-567812345678"),
        schema_version="2.0.0",
    )


def scope_activated(scope_id: str = "team_domain") -> Dict[str, Any]:
    return {
        "mission_id": MISSION,
        "run_id": RUN,
        "scope_id": scope_id,
        "glossary_version_id": "v1",
    }


def term_observed(term: str = "api") -> Dict[str, Any]:
    return {
        "mission_id": MISSION,
        "run_id": RUN,
        "term": term,
        "source_step": "st1",
        "actor_id": "a1",
        "confidence": 0.8,
        "extraction_method": "metadata_hint",
        "context": "step input",
    }


def sense_ref(scope: str = "team_domain", surface: str = "api") -> Dict[str, Any]:
    return {"surface": surface, "scope": scope, "definition": "REST interface", "confidence": 0.9}


ACTOR = {"actor_id": "a1", "actor_type": "human", "display_name": "Ann"}
PROVENANCE = {"source": "user_clarification", "timestamp": TS, "actor_id": "a1"}


def sense_updated(
    scope: str = "team_domain", term: str = "api", definition: str = "REST"
) -> Dict[str, Any]:
    new_sense = dict(sense_ref(scope, term), definition=definition, status="active")
    return {
        "term_surface": term,
        "scope": scope,
        "new_sense": new_sense,
        "actor": ACTOR,
        "update_type": "create",
        "provenance": PROVENANCE,
    }


def finding(term: str = "api") -> Dict[str, Any]:
    return {
        "term": {"surface_text": term},
        "conflict_type": "ambiguous",
        "severity": "high",
        "confidence": 0.9,
        "candidate_senses": [sense_ref()],
        "context": "step input",
    }


def semantic_check(step: str = "st2") -> Dict[str, Any]:
    return {
        "mission_id": MISSION,
        "run_id": RUN,
        "step_id": step,
        "findings": [finding()],
        "overall_severity": "high",
        "confidence": 0.9,
        "effective_strictness": "max",
        "recommended_action": "block",
        "blocked": True,
    }


def clarification_requested(
    conflict_id: str = CONFLICT, check_id: Optional[str] = None, term: str = "api"
) -> Dict[str, Any]:
    payload: Dict[str, Any] = {
        "mission_id": MISSION,
        "run_id": RUN,
        "step_id": "st2",
        "conflict_id": conflict_id,
        "term": term,
        "question": "Which meaning?",
        "options": ["REST", "Generic"],
        "urgency": "high",
    }
    if check_id is not None:
        payload["semantic_check_event_id"] = check_id
    return payload


def clarification_resolved(conflict_id: str = CONFLICT) -> Dict[str, Any]:
    return {
        "conflict_id": conflict_id,
        "term_surface": "api",
        "selected_sense": sense_ref(),
        "actor": ACTOR,
        "resolution_mode": "interactive",
        "provenance": PROVENANCE,
    }


def blocked() -> Dict[str, Any]:
    return {
        "mission_id": MISSION,
        "run_id": RUN,
        "step_id": "st2",
        "conflicts": [finding()],
        "strictness_mode": "max",
        "effective_strictness": "max",
    }


def checkpoint(step: str = "st1", cursor: str = "pre_generation_gate") -> Dict[str, Any]:
    return {
        "mission_id": MISSION,
        "run_id": RUN,
        "step_id": step,
        "strictness": "medium",
        "scope_refs": [{"scope": "team_domain", "version_id": "v1"}],
        "input_hash": HASH,
        "cursor": cursor,
        "retry_token": TOKEN,
    }


# ── Empty input ──────────────────────────────────────────────────────────────


def test_empty_and_non_glossary_input() -> None:
    assert reduce_glossary_events([]) == ReducedGlossaryState()
    other = make_event("WPStatusChanged", {"wp_id": "WP01", "new_lane": "doing"})
    assert reduce_glossary_events([other]) == ReducedGlossaryState()


# ── Happy path and re-keying (FR-009) ────────────────────────────────────────


def test_full_lifecycle_rekeyed() -> None:
    events = [
        make_event("GlossaryScopeActivated", scope_activated()),
        make_event(
            "GlossaryStrictnessSet",
            {"mission_id": MISSION, "new_strictness": "max", "actor": "admin"},
        ),
        make_event("TermCandidateObserved", term_observed("dashboard")),
        make_event("GlossarySenseUpdated", sense_updated(term="dashboard", definition="UI panel")),
        make_event("SemanticCheckEvaluated", semantic_check()),
        make_event("GenerationBlockedBySemanticConflict", blocked()),
        make_event("StepCheckpointed", checkpoint()),
    ]
    state = reduce_glossary_events(events)

    assert set(state.active_scopes) == {"team_domain"}
    assert state.current_strictness == "max"
    assert set(state.term_candidates) == {"dashboard"}
    assert len(state.term_candidates["dashboard"]) == 1
    key = ("team_domain", "dashboard")
    assert state.term_senses[key].new_sense.definition == "UI panel"
    assert len(state.semantic_checks) == 1
    assert len(state.generation_blocks) == 1
    assert set(state.checkpoints) == {"st1"}
    assert state.event_count == 7
    assert state.mission_id == MISSION
    assert state.anomalies == ()


@settings(max_examples=50, deadline=None)
@given(st.randoms(use_true_random=False))
def test_reduction_is_independent_of_input_order(rnd: Any) -> None:
    events = [
        make_event("GlossaryClarificationResolved", clarification_resolved()),
        make_event("GlossaryScopeActivated", scope_activated()),
        make_event("TermCandidateObserved", term_observed("dashboard")),
        make_event("StepCheckpointed", checkpoint()),
    ]
    expected = reduce_glossary_events(events, mode="permissive").model_dump()
    shuffled = list(events)
    rnd.shuffle(shuffled)
    assert reduce_glossary_events(shuffled, mode="permissive").model_dump() == expected


def test_mission_id_comes_from_first_event_that_carries_one() -> None:
    first = make_event("GlossaryClarificationResolved", clarification_resolved())
    second = make_event("GlossaryScopeActivated", scope_activated())
    state = reduce_glossary_events([first, second], mode="permissive")
    assert state.mission_id == MISSION


def test_senses_are_keyed_by_scope_and_surface() -> None:
    events = [
        make_event("GlossaryScopeActivated", scope_activated("team_domain")),
        make_event("GlossaryScopeActivated", scope_activated("mission_local")),
        make_event("TermCandidateObserved", term_observed("api")),
        make_event("GlossarySenseUpdated", sense_updated("team_domain", definition="team")),
        make_event("GlossarySenseUpdated", sense_updated("mission_local", definition="local")),
        make_event("GlossarySenseUpdated", sense_updated("team_domain", definition="team v2")),
    ]
    state = reduce_glossary_events(events)
    assert set(state.term_senses) == {("team_domain", "api"), ("mission_local", "api")}
    assert state.term_senses[("team_domain", "api")].new_sense.definition == "team v2"
    assert state.term_senses[("mission_local", "api")].new_sense.definition == "local"


def test_json_serialization_nests_sense_keys() -> None:
    events = [
        make_event("GlossaryScopeActivated", scope_activated()),
        make_event("TermCandidateObserved", term_observed()),
        make_event("GlossarySenseUpdated", sense_updated()),
    ]
    dumped = json.loads(reduce_glossary_events(events).model_dump_json())
    assert dumped["term_senses"]["team_domain"]["api"]["new_sense"]["definition"] == "REST"
    assert list(dumped["term_candidates"]) == ["api"]


def test_term_candidates_need_no_scope_activation() -> None:
    state = reduce_glossary_events([make_event("TermCandidateObserved", term_observed())])
    assert state.anomalies == ()
    assert "api" in state.term_candidates


# ── Scope gating and unobserved terms (SenseUpdated only) ────────────────────


def test_sense_update_in_unactivated_scope_strict_raises() -> None:
    events = [
        make_event("TermCandidateObserved", term_observed()),
        make_event("GlossarySenseUpdated", sense_updated()),
    ]
    with pytest.raises(SpecKittyEventsError, match="unactivated scope"):
        reduce_glossary_events(events)


def test_sense_update_in_unactivated_scope_permissive_records_anomaly() -> None:
    events = [
        make_event("TermCandidateObserved", term_observed()),
        make_event("GlossarySenseUpdated", sense_updated()),
    ]
    state = reduce_glossary_events(events, mode="permissive")
    assert [a.event_type for a in state.anomalies] == ["GlossarySenseUpdated"]
    assert ("team_domain", "api") in state.term_senses


def test_sense_update_for_unobserved_term() -> None:
    events = [
        make_event("GlossaryScopeActivated", scope_activated()),
        make_event("GlossarySenseUpdated", sense_updated()),
    ]
    with pytest.raises(SpecKittyEventsError, match="unobserved term"):
        reduce_glossary_events(events)
    state = reduce_glossary_events(events, mode="permissive")
    assert "unobserved term" in state.anomalies[0].reason


# ── Clarifications: conflict_id correlation and burst grouping ───────────────


def test_request_and_resolution_correlate_by_conflict_id() -> None:
    req = make_event("GlossaryClarificationRequested", clarification_requested())
    res = make_event("GlossaryClarificationResolved", clarification_resolved())
    state = reduce_glossary_events([req, res])
    (record,) = state.clarifications
    assert record.conflict_id == CONFLICT
    assert record.semantic_check_event_id is None
    assert record.resolved is True
    assert record.resolution_event_id == res.event_id


def test_resolution_for_unknown_conflict_id() -> None:
    res = make_event("GlossaryClarificationResolved", clarification_resolved("nope"))
    with pytest.raises(SpecKittyEventsError, match="unknown clarification"):
        reduce_glossary_events([res])
    state = reduce_glossary_events([res], mode="permissive")
    assert len(state.anomalies) == 1
    assert state.clarifications == ()


def _check_and_requests(n: int) -> List[Event]:
    check = make_event(
        "SemanticCheckEvaluated", semantic_check(), event_id="01HXCHECK00000000000000001"
    )
    requests = [
        make_event(
            "GlossaryClarificationRequested",
            clarification_requested(conflict_id=f"c-{i}", check_id=check.event_id),
        )
        for i in range(n)
    ]
    return [check, *requests]


def test_burst_cap_applies_when_check_id_present() -> None:
    assert len(reduce_glossary_events(_check_and_requests(3)).clarifications) == 3
    with pytest.raises(SpecKittyEventsError, match="Burst cap"):
        reduce_glossary_events(_check_and_requests(4))
    state = reduce_glossary_events(_check_and_requests(4), mode="permissive")
    assert len(state.clarifications) == 3
    assert "Burst cap" in state.anomalies[0].reason


def test_resolved_clarification_frees_burst_cap() -> None:
    events = _check_and_requests(3)
    events.append(make_event("GlossaryClarificationResolved", clarification_resolved("c-0")))
    check_id = events[0].event_id
    events.append(
        make_event(
            "GlossaryClarificationRequested",
            clarification_requested(conflict_id="c-3", check_id=check_id),
        )
    )
    state = reduce_glossary_events(events)
    assert len(state.clarifications) == 4
    assert state.anomalies == ()


def test_no_burst_grouping_without_check_id() -> None:
    events = [
        make_event("GlossaryClarificationRequested", clarification_requested(conflict_id=f"c-{i}"))
        for i in range(6)
    ]
    state = reduce_glossary_events(events)
    assert len(state.clarifications) == 6
    assert state.anomalies == ()


def test_unknown_semantic_check_reference() -> None:
    req = make_event(
        "GlossaryClarificationRequested", clarification_requested(check_id="01HXMISSING")
    )
    with pytest.raises(SpecKittyEventsError, match="unknown semantic check"):
        reduce_glossary_events([req])
    state = reduce_glossary_events([req], mode="permissive")
    assert state.clarifications == ()
    assert len(state.anomalies) == 1


# ── Checkpoints ──────────────────────────────────────────────────────────────


def test_checkpoints_keep_latest_per_step() -> None:
    events = [
        make_event("StepCheckpointed", checkpoint("st1", "pre_generation_gate")),
        make_event("StepCheckpointed", checkpoint("st2", "post_gate")),
        make_event("StepCheckpointed", checkpoint("st1", "post_clarification")),
    ]
    state = reduce_glossary_events(events)
    assert set(state.checkpoints) == {"st1", "st2"}
    assert state.checkpoints["st1"].cursor == "post_clarification"


# ── Strictness, dedup, ordering ──────────────────────────────────────────────


def test_strictness_defaults_and_history() -> None:
    assert (
        reduce_glossary_events(
            [make_event("GlossaryScopeActivated", scope_activated())]
        ).current_strictness
        == "medium"
    )
    events = [
        make_event(
            "GlossaryStrictnessSet", {"mission_id": MISSION, "new_strictness": "max", "actor": "a"}
        ),
        make_event(
            "GlossaryStrictnessSet",
            {
                "mission_id": MISSION,
                "new_strictness": "off",
                "previous_strictness": "max",
                "actor": "a",
            },
        ),
    ]
    state = reduce_glossary_events(events)
    assert state.current_strictness == "off"
    assert [h.new_strictness for h in state.strictness_history] == ["max", "off"]


def test_duplicate_event_ids_ignored() -> None:
    event = make_event("GlossaryScopeActivated", scope_activated())
    state = reduce_glossary_events([event, event])
    assert state.event_count == 1


def test_unclocked_events_sort_before_clocked_and_by_timestamp() -> None:
    """Clockless first, as in ADR 0001's total order; unclocked ones by timestamp."""
    clocked = make_event("StepCheckpointed", checkpoint("st1", "post_gate"), lamport_clock=50)
    late = make_event(
        "StepCheckpointed", checkpoint("st1", "post_clarification"), clocked=False, second=30
    )
    early = make_event(
        "StepCheckpointed", checkpoint("st1", "pre_generation_gate"), clocked=False, second=10
    )
    state = reduce_glossary_events([late, clocked, early])
    assert state.checkpoints["st1"].cursor == "post_gate"
    assert state.last_processed_event_id == clocked.event_id
    unclocked_only = reduce_glossary_events([late, early])
    assert unclocked_only.checkpoints["st1"].cursor == "post_clarification"


def _determinism_events() -> List[Event]:
    return [
        make_event("GlossaryScopeActivated", scope_activated(), lamport_clock=101),
        make_event(
            "GlossaryStrictnessSet",
            {"mission_id": MISSION, "new_strictness": "max", "actor": "admin"},
            lamport_clock=102,
        ),
        make_event("TermCandidateObserved", term_observed("api"), lamport_clock=103),
        make_event("TermCandidateObserved", term_observed("endpoint"), lamport_clock=104),
        make_event("GlossarySenseUpdated", sense_updated(), lamport_clock=105),
        make_event(
            "SemanticCheckEvaluated",
            semantic_check(),
            event_id="01HXCHECK00000000000000001",
            lamport_clock=106,
        ),
        make_event(
            "GlossaryClarificationRequested",
            clarification_requested(check_id="01HXCHECK00000000000000001"),
            lamport_clock=107,
        ),
        make_event("GlossaryClarificationResolved", clarification_resolved(), lamport_clock=108),
        make_event("GenerationBlockedBySemanticConflict", blocked(), lamport_clock=109),
        make_event("StepCheckpointed", checkpoint(), lamport_clock=110),
    ]


@given(data=st.data())
@settings(max_examples=100)
def test_reducer_determinism(data: st.DataObject) -> None:
    """Any permutation of glossary events produces identical reduced state."""
    events = _determinism_events()
    shuffled = data.draw(st.permutations(events))
    assert reduce_glossary_events(events) == reduce_glossary_events(list(shuffled))


def test_invalid_payload_raises() -> None:
    bad = make_event("GlossaryScopeActivated", {"mission_id": MISSION})
    with pytest.raises(ValueError):
        reduce_glossary_events([bad])
