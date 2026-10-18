"""Conformance tests for glossary semantic integrity contracts (11.0.0 shapes).

Tests fixture validation against payload models and end-to-end
reducer scenarios using conformance fixtures. The valid payload fixtures are
static captures of core's glossary builders (core 0746bec3); see the manifest
``notes`` for the per-fixture provenance.
"""

from __future__ import annotations

import json
import uuid
from typing import Any, Dict, List, Optional

import pytest
from pydantic import ValidationError as PydanticValidationError

from spec_kitty_events.conformance.loader import _FIXTURES_DIR, load_fixtures
from spec_kitty_events.conformance.validators import (
    _EVENT_TYPE_TO_MODEL,
    validate_event,
)
from spec_kitty_events.glossary import (
    reduce_glossary_events,
)
from spec_kitty_events.models import Event


# ── Helpers ──────────────────────────────────────────────────────────────────


def _load_fixture(rel_path: str) -> Dict[str, Any]:
    """Load a glossary fixture JSON file."""
    full = _FIXTURES_DIR / rel_path
    with open(full, encoding="utf-8") as f:
        return json.load(f)  # type: ignore[no-any-return]


def _make_event(
    event_type: str,
    payload: Dict[str, Any],
    *,
    lamport_clock: Optional[int],
    event_id: str | None = None,
    aggregate_id: str = "mission-fixture-001",
    timestamp: str = "2026-02-16T10:00:00Z",
) -> Event:
    """Create an Event wrapping a glossary payload (``lamport_clock=None`` is unclocked)."""
    eid = event_id or f"01HX{(lamport_clock if lamport_clock is not None else 99):022d}"
    return Event(
        event_id=eid,
        event_type=event_type,
        aggregate_id=aggregate_id,
        payload=payload,
        timestamp=timestamp,
        build_id="test-build",
        node_id="fixture-node",
        lamport_clock=lamport_clock,
        project_uuid=uuid.UUID("12345678-1234-5678-1234-567812345678"),
        correlation_id="01HTESTC0RRE1AT10N00000001",
        schema_version="3.0.0",
    )


# ── T049/T050: Fixture validation ───────────────────────────────────────────


VALID_GLOSSARY_FILES = [
    ("glossary/valid/glossary_scope_activated.json", "GlossaryScopeActivated"),
    ("glossary/valid/term_candidate_observed.json", "TermCandidateObserved"),
    ("glossary/valid/semantic_check_evaluated_block.json", "SemanticCheckEvaluated"),
    ("glossary/valid/semantic_check_evaluated_warn.json", "SemanticCheckEvaluated"),
    ("glossary/valid/generation_blocked.json", "GenerationBlockedBySemanticConflict"),
    ("glossary/valid/glossary_clarification_requested.json", "GlossaryClarificationRequested"),
    ("glossary/valid/glossary_clarification_resolved.json", "GlossaryClarificationResolved"),
    ("glossary/valid/glossary_sense_updated.json", "GlossarySenseUpdated"),
    ("glossary/valid/glossary_strictness_set.json", "GlossaryStrictnessSet"),
    ("glossary/valid/step_checkpointed.json", "StepCheckpointed"),
]

INVALID_GLOSSARY_FILES = [
    ("glossary/invalid/semantic_check_missing_step_id.json", "SemanticCheckEvaluated"),
    ("glossary/invalid/glossary_sense_updated_invalid_update_type.json", "GlossarySenseUpdated"),
    ("glossary/invalid/clarification_missing_conflict_id.json", "GlossaryClarificationRequested"),
    ("glossary/invalid/clarification_empty_check_ref.json", "GlossaryClarificationRequested"),
    ("glossary/invalid/step_checkpointed_bad_input_hash.json", "StepCheckpointed"),
]

_CORE_CAPTURE_FILES = [f for f in VALID_GLOSSARY_FILES if "strictness_set" not in f[0]]


class TestValidGlossaryFixtures:
    """Verify each valid glossary fixture passes model validation."""

    @pytest.mark.parametrize("path,event_type", VALID_GLOSSARY_FILES)
    def test_valid_fixture_is_valid_json(self, path: str, event_type: str) -> None:
        full = _FIXTURES_DIR / path
        assert full.is_file(), f"Missing fixture: {full}"
        data = _load_fixture(path)
        assert isinstance(data, dict)

    @pytest.mark.parametrize("path,event_type", VALID_GLOSSARY_FILES)
    def test_valid_fixture_passes_model(self, path: str, event_type: str) -> None:
        data = _load_fixture(path)
        model_class = _EVENT_TYPE_TO_MODEL[event_type]
        instance = model_class.model_validate(data)
        assert instance is not None

    @pytest.mark.parametrize("path,event_type", VALID_GLOSSARY_FILES)
    def test_valid_fixture_passes_conformance(self, path: str, event_type: str) -> None:
        data = _load_fixture(path)
        result = validate_event(data, event_type)
        assert result.valid is True, f"Conformance failure for {path}: {result.model_violations}"

    @pytest.mark.parametrize("path,event_type", _CORE_CAPTURE_FILES)
    def test_core_capture_round_trips_exactly(self, path: str, event_type: str) -> None:
        """NFR-002: a core-shaped payload survives validation without any change."""
        data = _load_fixture(path)
        instance = _EVENT_TYPE_TO_MODEL[event_type].model_validate(data)
        assert json.loads(instance.model_dump_json(exclude_none=True)) == data

    def test_ten_valid_glossary_fixtures_exist(self) -> None:
        valid_dir = _FIXTURES_DIR / "glossary" / "valid"
        files = sorted(valid_dir.glob("*.json"))
        assert len(files) == 10, f"Expected 10 valid glossary fixtures, got {len(files)}"

    def test_old_shape_is_not_accepted(self) -> None:
        """C-002: the pre-11.0.0 GlossaryScopeActivated shape has no alias."""
        old = {
            "mission_id": "mission-fixture-001",
            "scope_id": "scope-team-domain",
            "scope_type": "team_domain",
            "glossary_version_id": "v1.0.0",
        }
        assert validate_event(old, "GlossaryScopeActivated").valid is False


class TestInvalidGlossaryFixtures:
    """Verify each invalid glossary fixture fails model validation."""

    @pytest.mark.parametrize("path,event_type", INVALID_GLOSSARY_FILES)
    def test_invalid_fixture_fails_model(self, path: str, event_type: str) -> None:
        data = _load_fixture(path)
        model_class = _EVENT_TYPE_TO_MODEL[event_type]
        with pytest.raises(PydanticValidationError):
            model_class.model_validate(data)

    @pytest.mark.parametrize("path,event_type", INVALID_GLOSSARY_FILES)
    def test_invalid_fixture_fails_conformance(self, path: str, event_type: str) -> None:
        data = _load_fixture(path)
        result = validate_event(data, event_type)
        assert result.valid is False, f"Expected invalid for {path}"
        assert len(result.model_violations) > 0

    def test_five_invalid_glossary_fixtures_exist(self) -> None:
        invalid_dir = _FIXTURES_DIR / "glossary" / "invalid"
        files = sorted(invalid_dir.glob("*.json"))
        assert len(files) == 5, f"Expected 5 invalid glossary fixtures, got {len(files)}"

    def test_absent_semantic_check_ref_is_valid_but_empty_is_not(self) -> None:
        """semantic_check_event_id became optional in 11.0.0; '' is still refused."""
        requested = _load_fixture("glossary/valid/glossary_clarification_requested.json")
        assert "semantic_check_event_id" not in requested
        assert validate_event(requested, "GlossaryClarificationRequested").valid is True
        empty = _load_fixture("glossary/invalid/clarification_empty_check_ref.json")
        assert empty["semantic_check_event_id"] == ""


class TestGlossaryFixtureLoader:
    """Verify load_fixtures() works for the glossary category."""

    def test_load_glossary_returns_cases(self) -> None:
        cases = load_fixtures("glossary")
        assert len(cases) == 15

    def test_glossary_valid_cases(self) -> None:
        cases = load_fixtures("glossary")
        valid_cases = [c for c in cases if c.expected_valid]
        assert len(valid_cases) == 10

    def test_glossary_invalid_cases(self) -> None:
        cases = load_fixtures("glossary")
        invalid_cases = [c for c in cases if not c.expected_valid]
        assert len(invalid_cases) == 5

    def test_rewritten_and_new_fixtures_declare_11_0_0(self) -> None:
        cases = {c.id: c for c in load_fixtures("glossary")}
        # GlossaryStrictnessSet is a package-only contract whose shape did not change.
        for case_id, case in cases.items():
            if case_id == "glossary-strictness-set-valid":
                assert case.min_version == "2.0.0"
            else:
                assert case.min_version == "11.0.0", case_id

    def test_core_provenance_is_recorded_in_notes(self) -> None:
        for case in load_fixtures("glossary"):
            if case.expected_valid and case.id != "glossary-strictness-set-valid":
                assert "core 0746bec3" in case.notes, case.id


# ── T052: High-severity block scenario (FR-022, FR-026) ─────────────────────


class TestHighSeverityBlockScenario:
    """Prove that an unresolved high-severity conflict produces a generation block.

    Uses conformance fixtures fed through the glossary reducer to verify
    the block gate behavior end-to-end.
    """

    def _events(self) -> List[Event]:
        return [
            _make_event(
                "GlossaryScopeActivated",
                _load_fixture("glossary/valid/glossary_scope_activated.json"),
                lamport_clock=1,
            ),
            _make_event(
                "GlossaryStrictnessSet",
                _load_fixture("glossary/valid/glossary_strictness_set.json"),
                lamport_clock=2,
            ),
            _make_event(
                "TermCandidateObserved",
                _load_fixture("glossary/valid/term_candidate_observed.json"),
                lamport_clock=3,
            ),
            _make_event(
                "SemanticCheckEvaluated",
                _load_fixture("glossary/valid/semantic_check_evaluated_block.json"),
                lamport_clock=4,
            ),
            _make_event(
                "GenerationBlockedBySemanticConflict",
                _load_fixture("glossary/valid/generation_blocked.json"),
                lamport_clock=5,
            ),
        ]

    def test_block_scenario_produces_generation_block(self) -> None:
        """Full sequence: scope -> strictness -> term -> check(block) -> blocked."""
        state = reduce_glossary_events(self._events())

        assert len(state.generation_blocks) == 1
        block = state.generation_blocks[0]
        assert block.strictness_mode.value == "max"
        assert block.effective_strictness.value == "max"

        assert len(state.semantic_checks) == 1
        assert state.semantic_checks[0].overall_severity.value == "high"
        assert state.semantic_checks[0].recommended_action.value == "block"
        assert state.semantic_checks[0].blocked is True

        assert state.current_strictness == "max"
        assert list(state.term_candidates) == ["deployment"]

    def test_block_carries_the_conflicting_findings(self) -> None:
        """The block names the conflicting term (core has no conflict_event_ids)."""
        state = reduce_glossary_events(self._events())

        block = state.generation_blocks[0]
        assert [c.term.surface_text for c in block.conflicts] == ["deployment"]
        finding = state.semantic_checks[0].findings[0]
        assert finding.term.surface_text == block.conflicts[0].term.surface_text

    def test_unclocked_stream_reduces_by_timestamp(self) -> None:
        """An unclocked stream (lamport_clock absent) folds in (timestamp, event_id) order."""
        events = [
            _make_event(
                e.event_type,
                e.payload,
                lamport_clock=None,
                timestamp=f"2026-02-16T10:00:0{n}Z",
                event_id=f"01HX{n:022d}",
            )
            for n, e in enumerate(self._events(), start=1)
        ]
        forward = reduce_glossary_events(events)
        backward = reduce_glossary_events(list(reversed(events)))
        assert forward == backward
        assert len(forward.generation_blocks) == 1
        assert forward.current_strictness == "max"


# ── T053: Warn scenario + burst cap (FR-023, FR-024) ────────────────────────


class TestWarnScenario:
    """Prove medium-severity warns without blocking."""

    def test_warn_does_not_block(self) -> None:
        """Warn-level check produces no generation block."""
        scope_data = _load_fixture("glossary/valid/glossary_scope_activated.json")
        term_data = _load_fixture("glossary/valid/term_candidate_observed.json")
        warn_data = _load_fixture("glossary/valid/semantic_check_evaluated_warn.json")

        events = [
            _make_event("GlossaryScopeActivated", scope_data, lamport_clock=1),
            _make_event("TermCandidateObserved", term_data, lamport_clock=2),
            _make_event("SemanticCheckEvaluated", warn_data, lamport_clock=3),
        ]

        state = reduce_glossary_events(events)

        assert len(state.semantic_checks) == 1
        assert state.semantic_checks[0].overall_severity.value == "medium"
        assert state.semantic_checks[0].recommended_action.value == "warn"
        assert state.semantic_checks[0].blocked is False
        assert len(state.generation_blocks) == 0


class TestCoreShapedLifecycle:
    """Core-shaped records fold end to end: terms by ``term``, senses by scope."""

    def test_clarification_correlates_on_conflict_id(self) -> None:
        events = [
            _make_event(
                "GlossaryClarificationRequested",
                _load_fixture("glossary/valid/glossary_clarification_requested.json"),
                lamport_clock=1,
            ),
            _make_event(
                "GlossaryClarificationResolved",
                _load_fixture("glossary/valid/glossary_clarification_resolved.json"),
                lamport_clock=2,
            ),
        ]
        state = reduce_glossary_events(events)
        assert [(c.term, c.resolved) for c in state.clarifications] == [("deployment", True)]
        assert state.anomalies == ()

    def test_sense_update_is_scope_gated_and_keyed_by_scope_and_term(self) -> None:
        events = [
            _make_event(
                "GlossaryScopeActivated",
                _load_fixture("glossary/valid/glossary_scope_activated.json"),
                lamport_clock=1,
            ),
            _make_event(
                "TermCandidateObserved",
                _load_fixture("glossary/valid/term_candidate_observed.json"),
                lamport_clock=2,
            ),
            _make_event(
                "GlossarySenseUpdated",
                _load_fixture("glossary/valid/glossary_sense_updated.json"),
                lamport_clock=3,
            ),
        ]
        state = reduce_glossary_events(events)
        assert list(state.term_senses) == [("team_domain", "deployment")]
        assert state.anomalies == ()

    def test_step_checkpoint_is_kept_per_step(self) -> None:
        events = [
            _make_event(
                "StepCheckpointed",
                _load_fixture("glossary/valid/step_checkpointed.json"),
                lamport_clock=1,
            )
        ]
        state = reduce_glossary_events(events)
        assert list(state.checkpoints) == ["specify"]
        assert state.checkpoints["specify"].cursor == "pre_generation_gate"


class TestBurstCap:
    """Prove clarification burst cap limits to 3 per semantic check."""

    @staticmethod
    def _requests(count: int, check_event_id: str) -> List[Event]:
        events: List[Event] = []
        for i in range(count):
            clar_payload: Dict[str, Any] = {
                "mission_id": "mission-fixture-001",
                "run_id": "run-0001",
                "step_id": "step-002",
                "conflict_id": f"conflict-{i}",
                "semantic_check_event_id": check_event_id,
                "term": f"term-{i}",
                "question": f"What does term-{i} mean?",
                "options": ["Meaning A", "Meaning B"],
                "urgency": "high",
            }
            events.append(
                _make_event("GlossaryClarificationRequested", clar_payload, lamport_clock=3 + i)
            )
        return events

    def _check_events(self, check_event_id: str) -> List[Event]:
        return [
            _make_event(
                "GlossaryScopeActivated",
                _load_fixture("glossary/valid/glossary_scope_activated.json"),
                lamport_clock=1,
            ),
            _make_event(
                "SemanticCheckEvaluated",
                _load_fixture("glossary/valid/semantic_check_evaluated_block.json"),
                lamport_clock=2,
                event_id=check_event_id,
            ),
        ]

    def test_burst_cap_limits_clarifications(self) -> None:
        """5 clarification requests with same check ID: 3 accepted, 2 anomalies."""
        check_id = "01HTESTCHECK00000000000001"
        events = self._check_events(check_id) + self._requests(5, check_id)

        state = reduce_glossary_events(events, mode="permissive")

        assert len(state.clarifications) == 3
        burst_anomalies = [a for a in state.anomalies if "Burst cap exceeded" in a.reason]
        assert len(burst_anomalies) == 2

    def test_burst_cap_strict_raises(self) -> None:
        """In strict mode, 4th clarification raises SpecKittyEventsError."""
        from spec_kitty_events.models import SpecKittyEventsError

        check_id = "01HTESTCHECK00000000000001"
        events = self._check_events(check_id) + self._requests(4, check_id)

        with pytest.raises(SpecKittyEventsError, match="Burst cap exceeded"):
            reduce_glossary_events(events, mode="strict")

    def test_requests_without_check_reference_are_not_grouped(self) -> None:
        """Core emits no semantic_check_event_id: no burst window applies."""
        events: List[Event] = []
        for i in range(5):
            payload = dict(
                _load_fixture("glossary/valid/glossary_clarification_requested.json"),
                conflict_id=f"conflict-{i}",
            )
            events.append(
                _make_event("GlossaryClarificationRequested", payload, lamport_clock=1 + i)
            )
        state = reduce_glossary_events(events)
        assert len(state.clarifications) == 5
        assert state.anomalies == ()


# ── Schema Validation: Dual-Layer Active ─────────────────────────────────────


class TestGlossarySchemaValidation:
    """Assert glossary event types have dual-layer validation (not single-layer)."""

    def test_scope_activated_schema_not_skipped(self) -> None:
        """validate_event for GlossaryScopeActivated returns schema_check_skipped=False."""
        data = _load_fixture("glossary/valid/glossary_scope_activated.json")
        result = validate_event(data, "GlossaryScopeActivated")
        assert result.valid is True
        assert result.schema_check_skipped is False

    def test_term_candidate_schema_not_skipped(self) -> None:
        data = _load_fixture("glossary/valid/term_candidate_observed.json")
        result = validate_event(data, "TermCandidateObserved")
        assert result.valid is True
        assert result.schema_check_skipped is False

    def test_semantic_check_schema_not_skipped(self) -> None:
        data = _load_fixture("glossary/valid/semantic_check_evaluated_block.json")
        result = validate_event(data, "SemanticCheckEvaluated")
        assert result.valid is True
        assert result.schema_check_skipped is False

    def test_clarification_requested_schema_not_skipped(self) -> None:
        data = _load_fixture("glossary/valid/glossary_clarification_requested.json")
        result = validate_event(data, "GlossaryClarificationRequested")
        assert result.valid is True
        assert result.schema_check_skipped is False

    def test_clarification_resolved_schema_not_skipped(self) -> None:
        data = _load_fixture("glossary/valid/glossary_clarification_resolved.json")
        result = validate_event(data, "GlossaryClarificationResolved")
        assert result.valid is True
        assert result.schema_check_skipped is False

    def test_sense_updated_schema_not_skipped(self) -> None:
        data = _load_fixture("glossary/valid/glossary_sense_updated.json")
        result = validate_event(data, "GlossarySenseUpdated")
        assert result.valid is True
        assert result.schema_check_skipped is False

    def test_generation_blocked_schema_not_skipped(self) -> None:
        data = _load_fixture("glossary/valid/generation_blocked.json")
        result = validate_event(data, "GenerationBlockedBySemanticConflict")
        assert result.valid is True
        assert result.schema_check_skipped is False

    def test_strictness_set_schema_not_skipped(self) -> None:
        data = _load_fixture("glossary/valid/glossary_strictness_set.json")
        result = validate_event(data, "GlossaryStrictnessSet")
        assert result.valid is True
        assert result.schema_check_skipped is False
