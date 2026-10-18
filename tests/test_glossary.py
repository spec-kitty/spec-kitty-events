"""Tests for the glossary 11.0 payload models (core-shaped, #120)."""

from __future__ import annotations

import copy
from typing import Any, Dict, Type

import pytest
from pydantic import BaseModel, ValidationError

import spec_kitty_events
from spec_kitty_events import (
    GLOSSARY_EVENT_TYPES,
    STEP_CHECKPOINTED,
    GenerationBlockedBySemanticConflictPayload,
    GlossaryClarificationRequestedPayload,
    GlossaryClarificationResolvedPayload,
    GlossaryFinding,
    GlossaryScopeActivatedPayload,
    GlossarySenseUpdatedPayload,
    GlossaryStrictnessSetPayload,
    SemanticCheckEvaluatedPayload,
    StepCheckpointedPayload,
    TermCandidateObservedPayload,
)
from spec_kitty_events.persistence import LedgerValidationError, build_record

_IDENTITY_KEYS = ("event_type", "event_id", "timestamp")

# ── Issue #120: core-shaped glossary records ─────────────────────────────────
#
# Static captures of the records core's builders emit, run once by hand. Each
# also carries a top-level ``event_id`` (uuid, added by core's persistence).
# captured from spec-kitty 0746bec3 src/glossary/events.py:build_glossary_scope_activated,
# build_term_candidate_observed, build_semantic_check_evaluated, build_generation_blocked,
# build_clarification_requested, build_clarification_resolved, build_sense_updated,
# build_step_checkpointed (the builder is named by each record's ``event_type``).
CORE_GLOSSARY_RECORDS: Dict[str, Dict[str, Any]] = {
    "GenerationBlockedBySemanticConflict": {
        "conflicts": [
            {
                "candidate_senses": [
                    {
                        "confidence": 0.9,
                        "definition": "Shipping a build to an environment",
                        "scope": "team_domain",
                        "surface": "deployment",
                    },
                    {
                        "confidence": 0.6,
                        "definition": "A rollout step",
                        "scope": "mission_local",
                        "surface": "deployment",
                    },
                ],
                "confidence": 0.8,
                "conflict_type": "ambiguous",
                "context": "step input: description field",
                "severity": "medium",
                "term": {"surface_text": "deployment"},
            }
        ],
        "effective_strictness": "max",
        "event_id": "42af37f9-3f97-5b03-91a7-25ad08e8ef02",
        "event_type": "GenerationBlockedBySemanticConflict",
        "mission_id": "01M57TCP-mission",
        "run_id": "run-0001",
        "step_id": "specify",
        "strictness_mode": "max",
        "timestamp": "2026-10-18T12:00:00+00:00",
    },
    "GlossaryClarificationRequested": {
        "conflict_id": "0b1c2d3e-4f50-4a6b-8c7d-9e0f1a2b3c4d",
        "event_id": "ebf6605c-3f61-5368-82ee-8c8e18d15076",
        "event_type": "GlossaryClarificationRequested",
        "mission_id": "01M57TCP-mission",
        "options": ["Shipping a build", "A rollout step"],
        "question": "Which sense of deployment?",
        "run_id": "run-0001",
        "step_id": "specify",
        "term": "deployment",
        "timestamp": "2026-10-18T12:00:00+00:00",
        "urgency": "high",
    },
    "GlossaryClarificationResolved": {
        "actor": {"actor_id": "agent-1", "actor_type": "llm", "display_name": "Claude"},
        "conflict_id": "0b1c2d3e-4f50-4a6b-8c7d-9e0f1a2b3c4d",
        "event_id": "664f03d5-ce2c-5651-8f70-88a93dbaff1b",
        "event_type": "GlossaryClarificationResolved",
        "provenance": {
            "actor_id": "agent-1",
            "source": "user_clarification",
            "timestamp": "2026-10-18T12:00:00+00:00",
        },
        "resolution_mode": "interactive",
        "selected_sense": {
            "confidence": 0.9,
            "definition": "Shipping a build to an environment",
            "scope": "team_domain",
            "surface": "deployment",
        },
        "term_surface": "deployment",
        "timestamp": "2026-10-18T12:00:00+00:00",
    },
    "GlossaryScopeActivated": {
        "event_id": "83f49bb3-70e7-58e9-8d57-a6686f81284c",
        "event_type": "GlossaryScopeActivated",
        "glossary_version_id": "v3",
        "mission_id": "01M57TCP-mission",
        "run_id": "run-0001",
        "scope_id": "team_domain",
        "timestamp": "2026-10-18T12:00:00+00:00",
    },
    "GlossarySenseUpdated": {
        "actor": {"actor_id": "agent-1", "actor_type": "llm", "display_name": "Claude"},
        "event_id": "58224786-c5c9-553f-912c-a4b0a8e1ca55",
        "event_type": "GlossarySenseUpdated",
        "new_sense": {
            "confidence": 0.9,
            "definition": "Shipping a build to an environment",
            "scope": "team_domain",
            "status": "active",
            "surface": "deployment",
        },
        "provenance": {
            "actor_id": "agent-1",
            "source": "user_clarification",
            "timestamp": "2026-10-18T12:00:00+00:00",
        },
        "scope": "team_domain",
        "term_surface": "deployment",
        "timestamp": "2026-10-18T12:00:00+00:00",
        "update_type": "create",
    },
    "SemanticCheckEvaluated": {
        "blocked": False,
        "confidence": 0.8,
        "effective_strictness": "medium",
        "event_id": "83b1d59b-1092-5db0-a7e0-0bae0db70222",
        "event_type": "SemanticCheckEvaluated",
        "findings": [
            {
                "candidate_senses": [
                    {
                        "confidence": 0.9,
                        "definition": "Shipping a build to an environment",
                        "scope": "team_domain",
                        "surface": "deployment",
                    },
                    {
                        "confidence": 0.6,
                        "definition": "A rollout step",
                        "scope": "mission_local",
                        "surface": "deployment",
                    },
                ],
                "confidence": 0.8,
                "conflict_type": "ambiguous",
                "context": "step input: description field",
                "severity": "medium",
                "term": {"surface_text": "deployment"},
            }
        ],
        "mission_id": "01M57TCP-mission",
        "overall_severity": "medium",
        "recommended_action": "warn",
        "run_id": "run-0001",
        "step_id": "specify",
        "timestamp": "2026-10-18T12:00:00+00:00",
    },
    "StepCheckpointed": {
        "cursor": "pre_generation_gate",
        "event_id": "6aac9ca5-94c2-5c5c-a0d3-e2bc5bc18292",
        "event_type": "StepCheckpointed",
        "input_hash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "mission_id": "01M57TCP-mission",
        "retry_token": "5f6a7b8c-9d0e-4f1a-8b2c-3d4e5f6a7b8c",
        "run_id": "run-0001",
        "scope_refs": [{"scope": "team_domain", "version_id": "v3"}],
        "step_id": "specify",
        "strictness": "medium",
        "timestamp": "2026-10-18T12:00:00+00:00",
    },
    "TermCandidateObserved": {
        "actor_id": "agent-1",
        "confidence": 0.85,
        "context": "step input: description field",
        "event_id": "3a151d22-95f0-5dac-a5bf-75c1a740f110",
        "event_type": "TermCandidateObserved",
        "extraction_method": "metadata_hint",
        "mission_id": "01M57TCP-mission",
        "run_id": "run-0001",
        "source_step": "specify",
        "term": "deployment",
        "timestamp": "2026-10-18T12:00:00+00:00",
    },
}


_MODELS: Dict[str, Type[BaseModel]] = {
    "GlossaryScopeActivated": GlossaryScopeActivatedPayload,
    "TermCandidateObserved": TermCandidateObservedPayload,
    "SemanticCheckEvaluated": SemanticCheckEvaluatedPayload,
    "GenerationBlockedBySemanticConflict": GenerationBlockedBySemanticConflictPayload,
    "GlossaryClarificationRequested": GlossaryClarificationRequestedPayload,
    "GlossaryClarificationResolved": GlossaryClarificationResolvedPayload,
    "GlossarySenseUpdated": GlossarySenseUpdatedPayload,
    "StepCheckpointed": StepCheckpointedPayload,
}


def _payload(event_type: str) -> Dict[str, Any]:
    """Payload part of a captured core record (a deep copy, safe to mutate)."""
    record = copy.deepcopy(CORE_GLOSSARY_RECORDS[event_type])
    return {k: v for k, v in record.items() if k not in _IDENTITY_KEYS}


# ── Core-shape fidelity (NFR-002, #120) ──────────────────────────────────────


@pytest.mark.parametrize("event_type", sorted(_MODELS))
def test_core_record_validates_and_round_trips(event_type: str) -> None:
    """Each captured core record validates strictly and round-trips losslessly."""
    model = _MODELS[event_type].model_validate(_payload(event_type))
    assert model.model_dump(mode="json", exclude_none=True) == _payload(event_type)


@pytest.mark.parametrize(
    "event_type",
    sorted(t for t in CORE_GLOSSARY_RECORDS if t != STEP_CHECKPOINTED),
)
def test_build_record_accepts_core_glossary_record(event_type: str) -> None:
    """#120: persistence.build_record accepts what core's builders emit."""
    record = CORE_GLOSSARY_RECORDS[event_type]
    assert build_record(record) == record


def test_step_checkpointed_is_registered() -> None:
    assert STEP_CHECKPOINTED == "StepCheckpointed"
    assert STEP_CHECKPOINTED in GLOSSARY_EVENT_TYPES
    assert len(GLOSSARY_EVENT_TYPES) == 9


def test_removed_semantic_conflict_entry() -> None:
    assert not hasattr(spec_kitty_events, "SemanticConflictEntry")
    assert "SemanticConflictEntry" not in spec_kitty_events.__all__


def test_each_event_type_has_its_own_model_class() -> None:
    assert len(set(_MODELS.values())) == len(_MODELS)


@pytest.mark.parametrize("event_type", sorted(_MODELS))
def test_models_are_frozen_and_forbid_extra(event_type: str) -> None:
    model = _MODELS[event_type].model_validate(_payload(event_type))
    with pytest.raises(ValidationError):
        _MODELS[event_type].model_validate({**_payload(event_type), "surprise": 1})
    with pytest.raises(ValidationError):
        setattr(model, "mission_id", "other")


@pytest.mark.parametrize("event_type", sorted(_MODELS))
def test_missing_required_field_rejected(event_type: str) -> None:
    for key in _payload(event_type):
        payload = _payload(event_type)
        del payload[key]
        with pytest.raises(ValidationError):
            _MODELS[event_type].model_validate(payload)


@pytest.mark.parametrize(
    "event_type,field,value",
    [
        ("GlossaryScopeActivated", "run_id", ""),
        ("TermCandidateObserved", "confidence", 1.5),
        ("TermCandidateObserved", "term", ""),
        ("SemanticCheckEvaluated", "overall_severity", "critical"),
        ("SemanticCheckEvaluated", "recommended_action", "pass"),
        ("SemanticCheckEvaluated", "effective_strictness", "strict"),
        ("GenerationBlockedBySemanticConflict", "strictness_mode", "none"),
        ("GlossaryClarificationRequested", "urgency", "urgent"),
        ("GlossaryClarificationRequested", "conflict_id", ""),
        ("GlossaryClarificationResolved", "resolution_mode", "batch"),
        ("GlossarySenseUpdated", "scope", "galaxy"),
        ("GlossarySenseUpdated", "update_type", "delete"),
        ("StepCheckpointed", "input_hash", "abc"),
        ("StepCheckpointed", "cursor", "somewhere"),
        ("StepCheckpointed", "retry_token", "not-a-uuid"),
        ("StepCheckpointed", "strictness", "strict"),
    ],
)
def test_invalid_values_rejected(event_type: str, field: str, value: Any) -> None:
    payload = _payload(event_type)
    payload[field] = value
    with pytest.raises(ValidationError):
        _MODELS[event_type].model_validate(payload)
    if event_type != STEP_CHECKPOINTED:
        with pytest.raises(LedgerValidationError):
            build_record({"event_type": event_type, **payload})


@pytest.mark.parametrize(
    "event_type", ["SemanticCheckEvaluated", "GenerationBlockedBySemanticConflict"]
)
def test_old_field_names_are_rejected(event_type: str) -> None:
    payload = _payload(event_type)
    payload["scope_id"] = "s1"
    with pytest.raises(ValidationError):
        _MODELS[event_type].model_validate(payload)


# ── Optionality (FR-007) ─────────────────────────────────────────────────────


def test_semantic_check_event_id_is_optional_but_not_empty() -> None:
    payload = _payload("GlossaryClarificationRequested")
    assert (
        GlossaryClarificationRequestedPayload.model_validate(payload).semantic_check_event_id
        is None
    )
    payload["semantic_check_event_id"] = ""
    with pytest.raises(ValidationError):
        GlossaryClarificationRequestedPayload.model_validate(payload)
    payload["semantic_check_event_id"] = "01HXCHECK"
    parsed = GlossaryClarificationRequestedPayload.model_validate(payload)
    assert parsed.semantic_check_event_id == "01HXCHECK"


def test_ambiguous_finding_requires_candidate_senses() -> None:
    finding = copy.deepcopy(_payload("SemanticCheckEvaluated")["findings"][0])
    finding["candidate_senses"] = []
    with pytest.raises(ValidationError):
        GlossaryFinding.model_validate(finding)
    finding["conflict_type"] = "unknown"
    assert GlossaryFinding.model_validate(finding).candidate_senses == ()


def test_provenance_timestamp_must_be_iso() -> None:
    payload = _payload("GlossarySenseUpdated")
    payload["provenance"]["timestamp"] = "yesterday"
    with pytest.raises(ValidationError):
        GlossarySenseUpdatedPayload.model_validate(payload)
    payload["provenance"]["timestamp"] = "2026-10-18T12:00:00Z"
    GlossarySenseUpdatedPayload.model_validate(payload)


# ── GlossaryStrictnessSet (package-only, unchanged) ──────────────────────────


class TestGlossaryStrictnessSetPayload:
    @pytest.mark.parametrize("mode", ["off", "medium", "max"])
    def test_valid_modes(self, mode: str) -> None:
        p = GlossaryStrictnessSetPayload(mission_id="m", new_strictness=mode, actor="a")  # type: ignore[arg-type]
        assert p.previous_strictness is None

    def test_invalid_mode_rejected(self) -> None:
        with pytest.raises(ValidationError):
            GlossaryStrictnessSetPayload(mission_id="m", new_strictness="strict", actor="a")  # type: ignore[arg-type]

    def test_frozen(self) -> None:
        p = GlossaryStrictnessSetPayload(mission_id="m", new_strictness="max", actor="a")
        with pytest.raises(ValidationError):
            p.actor = "b"  # type: ignore[misc]
