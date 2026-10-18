"""Glossary semantic integrity event contracts (11.0.0).

Defines event type constants, nested value objects, enums, payload models,
reducer output models, and the glossary reducer for mission-level semantic
integrity enforcement.

The eight core-emitted payloads (``GlossaryScopeActivated`` ...
``StepCheckpointed``) mirror the records spec-kitty core's glossary builders
produce (#120). ``GlossaryStrictnessSet`` is a package-only contract that core
does not emit today. The nested value objects and enums are the
single contract definition; each payload class belongs to exactly one event
type.
"""

from __future__ import annotations

from datetime import datetime
from enum import Enum
from typing import Any, Dict, FrozenSet, List, Literal, Optional, Sequence, Set, Tuple

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    field_serializer,
    field_validator,
    model_validator,
)

from spec_kitty_events.models import Event, SpecKittyEventsError
from spec_kitty_events.ordering import canonical_order_key

# ── Section 1: Constants ─────────────────────────────────────────────────────

GLOSSARY_SCOPE_ACTIVATED: str = "GlossaryScopeActivated"
TERM_CANDIDATE_OBSERVED: str = "TermCandidateObserved"
SEMANTIC_CHECK_EVALUATED: str = "SemanticCheckEvaluated"
GLOSSARY_CLARIFICATION_REQUESTED: str = "GlossaryClarificationRequested"
GLOSSARY_CLARIFICATION_RESOLVED: str = "GlossaryClarificationResolved"
GLOSSARY_SENSE_UPDATED: str = "GlossarySenseUpdated"
GENERATION_BLOCKED_BY_SEMANTIC_CONFLICT: str = "GenerationBlockedBySemanticConflict"
GLOSSARY_STRICTNESS_SET: str = "GlossaryStrictnessSet"
STEP_CHECKPOINTED: str = "StepCheckpointed"

GLOSSARY_EVENT_TYPES: FrozenSet[str] = frozenset(
    {
        GLOSSARY_SCOPE_ACTIVATED,
        TERM_CANDIDATE_OBSERVED,
        SEMANTIC_CHECK_EVALUATED,
        GLOSSARY_CLARIFICATION_REQUESTED,
        GLOSSARY_CLARIFICATION_RESOLVED,
        GLOSSARY_SENSE_UPDATED,
        GENERATION_BLOCKED_BY_SEMANTIC_CONFLICT,
        GLOSSARY_STRICTNESS_SET,
        STEP_CHECKPOINTED,
    }
)

# Burst cap: at most this many unresolved clarifications per semantic check.
_CLARIFICATION_BURST_CAP = 3

_STRICT_MODEL_CONFIG = ConfigDict(frozen=True, extra="forbid")

# ── Section 2: Enums and Value Objects ───────────────────────────────────────


class GlossarySeverity(str, Enum):
    """Severity of a conflict finding or of a whole semantic check."""

    LOW = "low"
    MEDIUM = "medium"
    HIGH = "high"


class GlossaryStrictness(str, Enum):
    """Glossary enforcement strictness."""

    OFF = "off"
    MEDIUM = "medium"
    MAX = "max"


class GlossaryRecommendedAction(str, Enum):
    """Action a semantic check recommends (``proceed`` is core's spelling of pass)."""

    PROCEED = "proceed"
    WARN = "warn"
    BLOCK = "block"


class GlossaryConflictType(str, Enum):
    """Kind of semantic conflict a finding reports."""

    UNKNOWN = "unknown"
    AMBIGUOUS = "ambiguous"
    INCONSISTENT = "inconsistent"
    UNRESOLVED_CRITICAL = "unresolved_critical"


class GlossaryScope(str, Enum):
    """Glossary scope levels."""

    MISSION_LOCAL = "mission_local"
    TEAM_DOMAIN = "team_domain"
    AUDIENCE_DOMAIN = "audience_domain"
    SPEC_KITTY_CORE = "spec_kitty_core"


class GlossaryUpdateType(str, Enum):
    """Whether a sense update created or changed a sense."""

    CREATE = "create"
    UPDATE = "update"


class GlossaryResolutionMode(str, Enum):
    """How a clarification was resolved."""

    INTERACTIVE = "interactive"
    ASYNC = "async"


class GlossaryActorType(str, Enum):
    """Kind of actor behind a glossary action."""

    HUMAN = "human"
    LLM = "llm"
    SERVICE = "service"


class GlossarySenseStatus(str, Enum):
    """Lifecycle status of a term sense."""

    DRAFT = "draft"
    ACTIVE = "active"
    DEPRECATED = "deprecated"


class GlossaryTermSurface(BaseModel):
    """A term as written in the text under check."""

    model_config = _STRICT_MODEL_CONFIG

    surface_text: str = Field(..., min_length=1, description="Raw text form of the term")


class SenseRef(BaseModel):
    """Reference to one sense (meaning) of a term within a scope."""

    model_config = _STRICT_MODEL_CONFIG

    surface: str = Field(..., min_length=1, description="Term surface text")
    scope: GlossaryScope = Field(..., description="Scope that owns the sense")
    definition: str = Field(..., min_length=1, description="Definition of the sense")
    confidence: float = Field(..., ge=0.0, le=1.0, description="Confidence (0.0-1.0)")


class GlossaryNewSense(SenseRef):
    """A sense as written by a ``GlossarySenseUpdated`` event (adds ``status``)."""

    status: GlossarySenseStatus = Field(..., description="Lifecycle status of the sense")


class GlossaryActor(BaseModel):
    """Identity of the actor behind a glossary action."""

    model_config = _STRICT_MODEL_CONFIG

    actor_id: str = Field(..., min_length=1, description="Stable actor identifier")
    actor_type: GlossaryActorType = Field(..., description="Kind of actor")
    display_name: str = Field(..., min_length=1, description="Human-readable actor name")


class GlossaryProvenance(BaseModel):
    """Where a clarification or sense update came from."""

    model_config = _STRICT_MODEL_CONFIG

    source: str = Field(..., min_length=1, description="Origin, e.g. user_clarification")
    timestamp: str = Field(..., min_length=1, description="ISO-8601 time the action happened")
    actor_id: str = Field(..., min_length=1, description="Actor who produced the record")

    @field_validator("timestamp")
    @classmethod
    def _timestamp_is_iso(cls, value: str) -> str:
        try:
            datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError as exc:
            raise ValueError(f"provenance timestamp is not ISO-8601: {value!r}") from exc
        return value


class ScopeRef(BaseModel):
    """A glossary scope pinned to a version, as recorded in a checkpoint."""

    model_config = _STRICT_MODEL_CONFIG

    scope: GlossaryScope = Field(..., description="Scope level")
    version_id: str = Field(..., min_length=1, description="Glossary version of the scope")


class GlossaryFinding(BaseModel):
    """One semantic-conflict finding within a check or a generation block."""

    model_config = _STRICT_MODEL_CONFIG

    term: GlossaryTermSurface = Field(..., description="The conflicting term")
    conflict_type: GlossaryConflictType = Field(..., description="Kind of conflict")
    severity: GlossarySeverity = Field(..., description="Severity of this finding")
    confidence: float = Field(..., ge=0.0, le=1.0, description="Confidence in the detection")
    candidate_senses: Tuple[SenseRef, ...] = Field(
        default=(), description="Candidate senses (required for ambiguous findings)"
    )
    context: str = Field(default="", description="Where the term was used")

    @model_validator(mode="after")
    def _ambiguous_needs_candidates(self) -> "GlossaryFinding":
        if self.conflict_type is GlossaryConflictType.AMBIGUOUS and not self.candidate_senses:
            raise ValueError("an ambiguous finding must carry candidate_senses")
        return self


# ── Section 3: Payload Models ────────────────────────────────────────────────


class GlossaryScopeActivatedPayload(BaseModel):
    """Payload for GlossaryScopeActivated event."""

    model_config = _STRICT_MODEL_CONFIG

    mission_id: str = Field(..., min_length=1, description="Mission context")
    run_id: str = Field(..., min_length=1, description="Run context")
    scope_id: str = Field(..., min_length=1, description="Activated glossary scope")
    glossary_version_id: str = Field(
        ..., min_length=1, description="Version of the glossary being activated"
    )


class TermCandidateObservedPayload(BaseModel):
    """Payload for TermCandidateObserved event."""

    model_config = _STRICT_MODEL_CONFIG

    mission_id: str = Field(..., min_length=1, description="Mission context")
    run_id: str = Field(..., min_length=1, description="Run context")
    term: str = Field(..., min_length=1, description="Extracted term surface text")
    source_step: str = Field(..., min_length=1, description="Step that produced the term")
    actor_id: str = Field(..., min_length=1, description="Actor who triggered extraction")
    confidence: float = Field(..., ge=0.0, le=1.0, description="Extraction confidence (0.0-1.0)")
    extraction_method: str = Field(..., min_length=1, description="How the term was extracted")
    context: str = Field(..., description="Where the term was found")


class GlossarySenseUpdatedPayload(BaseModel):
    """Payload for GlossarySenseUpdated event."""

    model_config = _STRICT_MODEL_CONFIG

    term_surface: str = Field(..., min_length=1, description="Term being updated")
    scope: GlossaryScope = Field(..., description="Scope of the sense")
    new_sense: GlossaryNewSense = Field(..., description="The sense after the update")
    actor: GlossaryActor = Field(..., description="Actor who made the change")
    update_type: GlossaryUpdateType = Field(..., description="Created or updated")
    provenance: GlossaryProvenance = Field(..., description="Where the change came from")


class GlossaryStrictnessSetPayload(BaseModel):
    """Payload for GlossaryStrictnessSet event (mission-wide policy change)."""

    model_config = ConfigDict(frozen=True)

    mission_id: str = Field(..., min_length=1, description="Mission context")
    new_strictness: Literal["off", "medium", "max"] = Field(
        ..., description="The new strictness mode"
    )
    previous_strictness: Optional[Literal["off", "medium", "max"]] = Field(
        None, description="Previous mode (None if initial setting)"
    )
    actor: str = Field(..., min_length=1, description="Actor who changed the setting")


class SemanticCheckEvaluatedPayload(BaseModel):
    """Payload for SemanticCheckEvaluated event (step-level semantic check)."""

    model_config = _STRICT_MODEL_CONFIG

    mission_id: str = Field(..., min_length=1, description="Mission context")
    run_id: str = Field(..., min_length=1, description="Run context")
    step_id: str = Field(..., min_length=1, description="Step being evaluated")
    findings: Tuple[GlossaryFinding, ...] = Field(..., description="Detected conflicts")
    overall_severity: GlossarySeverity = Field(
        ..., description="Overall check severity (max of findings)"
    )
    confidence: float = Field(..., ge=0.0, le=1.0, description="Overall confidence score")
    effective_strictness: GlossaryStrictness = Field(
        ..., description="Strictness mode used for this evaluation"
    )
    recommended_action: GlossaryRecommendedAction = Field(
        ..., description="Action recommendation based on severity and strictness"
    )
    blocked: bool = Field(..., description="Whether generation was blocked")


class GlossaryClarificationRequestedPayload(BaseModel):
    """Payload for GlossaryClarificationRequested event."""

    model_config = _STRICT_MODEL_CONFIG

    mission_id: str = Field(..., min_length=1, description="Mission context")
    run_id: str = Field(..., min_length=1, description="Run context")
    step_id: str = Field(..., min_length=1, description="Step that triggered clarification")
    conflict_id: str = Field(
        ..., min_length=1, description="Tracking id correlating request and resolution"
    )
    semantic_check_event_id: Optional[str] = Field(
        default=None,
        min_length=1,
        description="Event ID of the triggering SemanticCheckEvaluated (burst-window key)",
    )
    term: str = Field(..., min_length=1, description="The ambiguous term")
    question: str = Field(..., description="Clarification question text")
    options: Tuple[str, ...] = Field(..., description="Available answer options")
    urgency: Literal["low", "medium", "high"] = Field(..., description="Urgency level")


class GlossaryClarificationResolvedPayload(BaseModel):
    """Payload for GlossaryClarificationResolved event."""

    model_config = _STRICT_MODEL_CONFIG

    conflict_id: str = Field(
        ..., min_length=1, description="conflict_id of the originating request"
    )
    term_surface: str = Field(..., min_length=1, description="Term that was clarified")
    selected_sense: SenseRef = Field(..., description="The chosen sense")
    actor: GlossaryActor = Field(..., description="Resolving actor")
    resolution_mode: GlossaryResolutionMode = Field(..., description="How it was resolved")
    provenance: GlossaryProvenance = Field(..., description="Where the resolution came from")


class GenerationBlockedBySemanticConflictPayload(BaseModel):
    """Payload for GenerationBlockedBySemanticConflict event."""

    model_config = _STRICT_MODEL_CONFIG

    mission_id: str = Field(..., min_length=1, description="Mission context")
    run_id: str = Field(..., min_length=1, description="Run context")
    step_id: str = Field(..., min_length=1, description="Step that was blocked")
    conflicts: Tuple[GlossaryFinding, ...] = Field(..., description="Blocking findings")
    strictness_mode: GlossaryStrictness = Field(..., description="Configured strictness mode")
    effective_strictness: GlossaryStrictness = Field(
        ..., description="Strictness in force when the block happened"
    )


class StepCheckpointedPayload(BaseModel):
    """Payload for StepCheckpointed event (resumable step checkpoint)."""

    model_config = _STRICT_MODEL_CONFIG

    mission_id: str = Field(..., min_length=1, description="Mission context")
    run_id: str = Field(..., min_length=1, description="Run context")
    step_id: str = Field(..., min_length=1, description="Checkpointed step")
    strictness: GlossaryStrictness = Field(..., description="Resolved strictness mode")
    scope_refs: Tuple[ScopeRef, ...] = Field(..., description="Active scope versions")
    input_hash: str = Field(
        ..., pattern=r"^[0-9a-f]{64}$", description="SHA-256 hex digest of the step inputs"
    )
    cursor: Literal["pre_generation_gate", "post_clarification", "post_gate"] = Field(
        ..., description="Execution stage reached"
    )
    retry_token: str = Field(
        ...,
        min_length=36,
        max_length=36,
        description="Idempotent retry token; 36 characters, as core enforces (a UUID string)",
    )


# ── Section 4: Reducer Output Models ─────────────────────────────────────────


class GlossaryAnomaly(BaseModel):
    """Non-fatal issue encountered during glossary event reduction."""

    model_config = ConfigDict(frozen=True)

    event_id: str = Field(..., description="ID of the event that caused the anomaly")
    event_type: str = Field(..., description="Type of the problematic event")
    reason: str = Field(..., description="Human-readable explanation")


class ClarificationRecord(BaseModel):
    """Tracks clarification lifecycle (correlated by ``conflict_id``)."""

    model_config = ConfigDict(frozen=True)

    request_event_id: str = Field(..., description="Event ID of the clarification request")
    conflict_id: str = Field(..., description="Tracking id shared by request and resolution")
    semantic_check_event_id: Optional[str] = Field(
        default=None, description="Burst-window grouping key (None when ungrouped)"
    )
    term: str = Field(..., description="The ambiguous term")
    resolved: bool = Field(default=False, description="Whether resolution received")
    resolution_event_id: Optional[str] = Field(
        default=None, description="Event ID of the resolution (if resolved)"
    )


class ReducedGlossaryState(BaseModel):
    """Projected glossary state from reduce_glossary_events()."""

    model_config = ConfigDict(frozen=True)

    mission_id: str = Field(default="", description="Mission context (first event carrying one)")
    active_scopes: Dict[str, GlossaryScopeActivatedPayload] = Field(
        default_factory=dict, description="scope_id → activation payload"
    )
    current_strictness: Literal["off", "medium", "max"] = Field(
        default="medium", description="Latest mission-wide strictness mode"
    )
    strictness_history: Tuple[GlossaryStrictnessSetPayload, ...] = Field(
        default_factory=tuple, description="Ordered history of strictness changes"
    )
    term_candidates: Dict[str, Tuple[TermCandidateObservedPayload, ...]] = Field(
        default_factory=dict, description="term → observed candidate payloads"
    )
    term_senses: Dict[Tuple[str, str], GlossarySenseUpdatedPayload] = Field(
        default_factory=dict,
        description="(scope, term_surface) → latest sense update",
    )
    clarifications: Tuple[ClarificationRecord, ...] = Field(
        default_factory=tuple, description="Ordered clarification timeline"
    )
    semantic_checks: Tuple[SemanticCheckEvaluatedPayload, ...] = Field(
        default_factory=tuple, description="Ordered check history"
    )
    generation_blocks: Tuple[GenerationBlockedBySemanticConflictPayload, ...] = Field(
        default_factory=tuple, description="Ordered block history"
    )
    checkpoints: Dict[str, StepCheckpointedPayload] = Field(
        default_factory=dict, description="step_id → latest checkpoint"
    )
    anomalies: Tuple[GlossaryAnomaly, ...] = Field(
        default_factory=tuple, description="Non-fatal issues encountered"
    )
    event_count: int = Field(default=0, description="Total glossary events processed")
    last_processed_event_id: Optional[str] = Field(
        default=None, description="Last event_id in processed sequence"
    )

    @field_serializer("term_senses", when_used="json")
    def _serialize_term_senses_json(
        self,
        value: Dict[Tuple[str, str], GlossarySenseUpdatedPayload],
    ) -> Dict[str, Dict[str, Dict[str, Any]]]:
        """Serialize composite keys as nested maps to avoid JSON key collisions."""
        nested: Dict[str, Dict[str, Dict[str, Any]]] = {}
        for (scope, term_surface), sense in sorted(value.items()):
            scope_bucket = nested.setdefault(scope, {})
            scope_bucket[term_surface] = sense.model_dump(mode="json")
        return nested


# ── Section 5: Glossary Reducer ──────────────────────────────────────────────


class _GlossaryFold:
    """Mutable accumulator the reducer feeds one event at a time."""

    def __init__(self, mode: str) -> None:
        self.mode = mode
        self.active_scopes: Dict[str, GlossaryScopeActivatedPayload] = {}
        self.current_strictness: Literal["off", "medium", "max"] = "medium"
        self.strictness_history: List[GlossaryStrictnessSetPayload] = []
        self.term_candidates: Dict[str, List[TermCandidateObservedPayload]] = {}
        self.term_senses: Dict[Tuple[str, str], GlossarySenseUpdatedPayload] = {}
        self.semantic_checks: List[SemanticCheckEvaluatedPayload] = []
        self.semantic_check_event_ids: Set[str] = set()
        self.clarifications: List[ClarificationRecord] = []
        self.generation_blocks: List[GenerationBlockedBySemanticConflictPayload] = []
        self.checkpoints: Dict[str, StepCheckpointedPayload] = {}
        self.anomalies: List[GlossaryAnomaly] = []

    def _refuse(self, event: Event, reason: str) -> None:
        """Raise in strict mode, record an anomaly in permissive mode."""
        if self.mode == "strict":
            raise SpecKittyEventsError(f"Event {event.event_id}: {reason}")
        self.anomalies.append(
            GlossaryAnomaly(event_id=event.event_id, event_type=event.event_type, reason=reason)
        )

    def apply(self, event: Event) -> None:
        handler = getattr(self, _HANDLERS[event.event_type])
        handler(event)

    def _scope_activated(self, event: Event) -> None:
        payload = GlossaryScopeActivatedPayload(**event.payload)
        self.active_scopes[payload.scope_id] = payload

    def _strictness_set(self, event: Event) -> None:
        payload = GlossaryStrictnessSetPayload(**event.payload)
        self.current_strictness = payload.new_strictness
        self.strictness_history.append(payload)

    def _term_candidate(self, event: Event) -> None:
        payload = TermCandidateObservedPayload(**event.payload)
        self.term_candidates.setdefault(payload.term, []).append(payload)

    def _sense_updated(self, event: Event) -> None:
        payload = GlossarySenseUpdatedPayload(**event.payload)
        scope = payload.scope.value
        if scope not in self.active_scopes:
            self._refuse(event, f"References unactivated scope '{scope}'")
        if payload.term_surface not in self.term_candidates:
            self._refuse(event, f"Sense update for unobserved term '{payload.term_surface}'")
        self.term_senses[(scope, payload.term_surface)] = payload

    def _semantic_check(self, event: Event) -> None:
        self.semantic_checks.append(SemanticCheckEvaluatedPayload(**event.payload))
        self.semantic_check_event_ids.add(event.event_id)

    def _clarification_requested(self, event: Event) -> None:
        payload = GlossaryClarificationRequestedPayload(**event.payload)
        check_id = payload.semantic_check_event_id
        if check_id is not None:
            if check_id not in self.semantic_check_event_ids:
                self._refuse(event, f"References unknown semantic check '{check_id}'")
                return
            active = sum(
                1
                for c in self.clarifications
                if c.semantic_check_event_id == check_id and not c.resolved
            )
            if active >= _CLARIFICATION_BURST_CAP:
                self._refuse(
                    event,
                    f"Burst cap exceeded: >{_CLARIFICATION_BURST_CAP} active "
                    f"clarifications for semantic check '{check_id}'",
                )
                return
        self.clarifications.append(
            ClarificationRecord(
                request_event_id=event.event_id,
                conflict_id=payload.conflict_id,
                semantic_check_event_id=check_id,
                term=payload.term,
            )
        )

    def _clarification_resolved(self, event: Event) -> None:
        payload = GlossaryClarificationResolvedPayload(**event.payload)
        for i, record in enumerate(self.clarifications):
            if record.conflict_id == payload.conflict_id:
                self.clarifications[i] = record.model_copy(
                    update={"resolved": True, "resolution_event_id": event.event_id}
                )
                return
        self._refuse(event, f"Resolution for unknown clarification '{payload.conflict_id}'")

    def _generation_blocked(self, event: Event) -> None:
        self.generation_blocks.append(GenerationBlockedBySemanticConflictPayload(**event.payload))

    def _step_checkpointed(self, event: Event) -> None:
        payload = StepCheckpointedPayload(**event.payload)
        self.checkpoints[payload.step_id] = payload


_HANDLERS: Dict[str, str] = {
    GLOSSARY_SCOPE_ACTIVATED: "_scope_activated",
    GLOSSARY_STRICTNESS_SET: "_strictness_set",
    TERM_CANDIDATE_OBSERVED: "_term_candidate",
    GLOSSARY_SENSE_UPDATED: "_sense_updated",
    SEMANTIC_CHECK_EVALUATED: "_semantic_check",
    GLOSSARY_CLARIFICATION_REQUESTED: "_clarification_requested",
    GLOSSARY_CLARIFICATION_RESOLVED: "_clarification_resolved",
    GENERATION_BLOCKED_BY_SEMANTIC_CONFLICT: "_generation_blocked",
    STEP_CHECKPOINTED: "_step_checkpointed",
}


def _first_mission_id(events: Sequence[Event]) -> str:
    """Return the first non-empty ``mission_id`` payload value, or ``""``."""
    for event in events:
        value = event.payload.get("mission_id")
        if value:
            return str(value)
    return ""


def reduce_glossary_events(
    events: Sequence[Event],
    *,
    mode: Literal["strict", "permissive"] = "strict",
) -> ReducedGlossaryState:
    """Fold glossary events into projected glossary state.

    Pipeline:
    1. Filter to glossary event types only
    2. Sort by :func:`spec_kitty_events.ordering.canonical_order_key`
       (unclocked first by timestamp, then clocked by ``lamport_clock``)
    3. Deduplicate by event_id
    4. Process each event, mutating intermediate state
    5. Assemble frozen ReducedGlossaryState

    Terms are keyed by ``term``, senses by ``(scope, term_surface)`` and
    clarifications correlate on ``conflict_id``; scope gating applies only to
    records that carry a ``scope`` (``GlossarySenseUpdated``). Burst grouping
    applies only to requests that carry ``semantic_check_event_id``.

    Pure function. No I/O. Deterministic for any permutation.
    """
    from spec_kitty_events.status import dedup_events

    glossary_events = [e for e in events if e.event_type in GLOSSARY_EVENT_TYPES]
    if not glossary_events:
        return ReducedGlossaryState()

    unique_events = dedup_events(sorted(glossary_events, key=canonical_order_key))

    fold = _GlossaryFold(mode)
    for event in unique_events:
        fold.apply(event)

    return ReducedGlossaryState(
        mission_id=_first_mission_id(unique_events),
        active_scopes=fold.active_scopes,
        current_strictness=fold.current_strictness,
        strictness_history=tuple(fold.strictness_history),
        term_candidates={k: tuple(v) for k, v in fold.term_candidates.items()},
        term_senses=fold.term_senses,
        clarifications=tuple(fold.clarifications),
        semantic_checks=tuple(fold.semantic_checks),
        generation_blocks=tuple(fold.generation_blocks),
        checkpoints=fold.checkpoints,
        anomalies=tuple(fold.anomalies),
        event_count=len(unique_events),
        last_processed_event_id=unique_events[-1].event_id,
    )
