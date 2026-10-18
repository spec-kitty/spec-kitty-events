"""DecisionPoint Lifecycle Event Contracts domain module.

Events are ordered by ``ordering.canonical_order_key`` (unclocked first,
then by clock; ties by timestamp and event_id).

Provides enums, event type constants, payload models,
the ReducedDecisionPointState output model, and a deterministic reducer
for the DecisionPoint Lifecycle contract.

Covers FR-001, FR-002, FR-003, FR-004, FR-005, FR-006, FR-007, FR-008,
FR-010, FR-011, FR-014, FR-015.

V1 changes (spec-kitty-events 4.0.0):
  - DecisionPointWidened event type + WIDENED state.
  - Discriminated-union payloads for Opened, Discussing, Resolved keyed on
    origin_surface ∈ {adr, planning_interview}.
  - Optional origin_surface on DecisionPointOverriddenPayload.
  - ReducedDecisionPointState extended with V1 projection fields.
  - Reducer branches on origin_surface; idempotently absorbs duplicate Widened;
    detects origin_mismatch and invalid_transition (closed_locally_without_widening).
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from enum import Enum
from typing import (
    Annotated,
    Any,
    Dict,
    FrozenSet,
    Iterable,
    List,
    Mapping,
    Literal,
    Optional,
    Sequence,
    Tuple,
    Union,
    cast,
)

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    TypeAdapter,
    model_validator,
)

from spec_kitty_events.collaboration import ParticipantIdentity
from spec_kitty_events.decision_moment import (
    ClosureMessageRef,
    DefaultChannelRef,
    DiscussingSnapshotKind,
    OriginFlow,
    OriginSurface,
    SummaryBlock,
    TeamspaceRef,
    TerminalOutcome,
    ThreadRef,
    WideningChannel,
    WideningProjection,
)
from spec_kitty_events.models import Event, SpecKittyEventsError
from spec_kitty_events.ordering import canonical_order_key
from spec_kitty_events.persistence import LedgerValidationError, build_record
from spec_kitty_events.status import dedup_events

# ── Section 1: Schema Version ─────────────────────────────────────────────────

DECISIONPOINT_SCHEMA_VERSION: str = "3.0.0"

# ── Section 2: Event Type Constants (FR-001) ─────────────────────────────────

DECISION_POINT_OPENED: str = "DecisionPointOpened"
DECISION_POINT_WIDENED: str = "DecisionPointWidened"
DECISION_POINT_DISCUSSING: str = "DecisionPointDiscussing"
DECISION_POINT_RESOLVED: str = "DecisionPointResolved"
DECISION_POINT_OVERRIDDEN: str = "DecisionPointOverridden"

DECISION_POINT_EVENT_TYPES: FrozenSet[str] = frozenset(
    {
        DECISION_POINT_OPENED,
        DECISION_POINT_WIDENED,
        DECISION_POINT_DISCUSSING,
        DECISION_POINT_RESOLVED,
        DECISION_POINT_OVERRIDDEN,
    }
)

# ── Section 3: Enums (FR-001) ────────────────────────────────────────────────


class DecisionPointState(str, Enum):
    OPEN = "open"
    WIDENED = "widened"
    DISCUSSING = "discussing"
    RESOLVED = "resolved"
    OVERRIDDEN = "overridden"


class DecisionAuthorityRole(str, Enum):
    MISSION_OWNER = "mission_owner"
    ADVISORY = "advisory"
    INFORMED = "informed"


# Map event types to states
_EVENT_TO_STATE: dict[str, DecisionPointState] = {
    DECISION_POINT_OPENED: DecisionPointState.OPEN,
    DECISION_POINT_WIDENED: DecisionPointState.WIDENED,
    DECISION_POINT_DISCUSSING: DecisionPointState.DISCUSSING,
    DECISION_POINT_RESOLVED: DecisionPointState.RESOLVED,
    DECISION_POINT_OVERRIDDEN: DecisionPointState.OVERRIDDEN,
}

# Allowed transitions: from_state -> set of valid to_states
_ALLOWED_TRANSITIONS: dict[Optional[DecisionPointState], FrozenSet[DecisionPointState]] = {
    None: frozenset({DecisionPointState.OPEN}),
    DecisionPointState.OPEN: frozenset(
        {
            DecisionPointState.WIDENED,
            DecisionPointState.DISCUSSING,
            DecisionPointState.RESOLVED,
        }
    ),
    DecisionPointState.WIDENED: frozenset(
        {
            DecisionPointState.WIDENED,
            DecisionPointState.DISCUSSING,
            DecisionPointState.RESOLVED,
        }
    ),
    DecisionPointState.DISCUSSING: frozenset(
        {
            DecisionPointState.DISCUSSING,
            DecisionPointState.RESOLVED,
        }
    ),
    DecisionPointState.RESOLVED: frozenset({DecisionPointState.OVERRIDDEN}),
    DecisionPointState.OVERRIDDEN: frozenset(),
}

# States that require human mission-owner authority
_AUTHORITY_REQUIRED_STATES: FrozenSet[DecisionPointState] = frozenset(
    {
        DecisionPointState.RESOLVED,
        DecisionPointState.OVERRIDDEN,
    }
)

#: Legal terminal-to-terminal reopens as ``(current, target)`` outcome pairs.
#: Today the only one is a deferred decision later being explicitly resolved.
DECISION_POINT_TERMINAL_REOPEN: FrozenSet[Tuple[TerminalOutcome, TerminalOutcome]] = frozenset(
    {(TerminalOutcome.DEFERRED, TerminalOutcome.RESOLVED)}
)

_ANOMALY_UNFOLDABLE_TERMINAL = "unfoldable_terminal_combination"


class DecisionPointFoldError(SpecKittyEventsError):
    """A strict ledger-records fold met a decision it cannot fold."""


@dataclass(frozen=True)
class _TerminalPlan:
    """Pass-1 verdict over one decision's terminal ``Resolved`` events."""

    demoted: FrozenSet[str] = frozenset()  # reopen-point deferred event ids
    extras: FrozenSet[str] = frozenset()  # unfoldable surplus Resolved event ids
    effective: Optional[str] = None  # event id of the reopen's resolving event


def _select_effective_terminal(
    candidates: Sequence[Tuple[str, TerminalOutcome]],
) -> _TerminalPlan:
    """Pure: decide which terminal events fold, from ``(event_id, outcome)`` pairs.

    *candidates* are in canonical order. Zero or one candidate needs no plan.
    Exactly two (a duplicate deferred with a different event_id plus a resolved
    is three, hence unfoldable by design) whose outcomes form a pair in
    :data:`DECISION_POINT_TERMINAL_REOPEN` (in either arrival order) demote the
    deferred event to a non-terminal entry and make the resolved one effective.
    Any other multi-candidate set is unfoldable: the first stands, the rest
    are surplus.
    """
    if len(candidates) < 2:
        return _TerminalPlan()
    if len(candidates) == 2:
        (id_a, out_a), (id_b, out_b) = candidates
        if (out_a, out_b) in DECISION_POINT_TERMINAL_REOPEN:
            return _TerminalPlan(demoted=frozenset({id_a}), effective=id_b)
        if (out_b, out_a) in DECISION_POINT_TERMINAL_REOPEN:
            return _TerminalPlan(demoted=frozenset({id_b}), effective=id_a)
    return _TerminalPlan(extras=frozenset(eid for eid, _ in candidates[1:]))


# ── Section 4: Anomaly Model ─────────────────────────────────────────────────


class DecisionPointAnomaly(BaseModel):
    """Non-fatal issue recorded during DecisionPoint reduction.

    Valid kind values: "invalid_transition", "authority_policy_violation",
    "llm_policy_violation", "malformed_payload", "event_after_terminal",
    "origin_mismatch", "missing_summary", "unfoldable_terminal_combination".
    """

    model_config = ConfigDict(frozen=True, extra="forbid")

    kind: str
    event_id: str
    message: str


# ── Section 5: Payload Models (FR-002) ───────────────────────────────────────

# ── 5a: DecisionPointOpened — discriminated union ────────────────────────────


class DecisionPointOpenedAdrPayload(BaseModel):
    """3.x-compatible ADR-style Opened payload."""

    model_config = ConfigDict(frozen=True, extra="forbid")

    origin_surface: Literal[OriginSurface.ADR] = Field(...)

    decision_point_id: str = Field(..., min_length=1)
    mission_id: str = Field(..., min_length=1)
    run_id: str = Field(..., min_length=1)
    mission_slug: str = Field(..., min_length=1)
    mission_type: str = Field(..., min_length=1)
    phase: str = Field(..., min_length=1)

    actor_id: str = Field(..., min_length=1)
    actor_type: Literal["human", "llm", "service"] = Field(...)
    authority_role: DecisionAuthorityRole
    mission_owner_authority_flag: bool
    mission_owner_authority_path: str

    rationale: str = Field(..., min_length=1)
    alternatives_considered: Tuple[str, ...] = Field(..., min_length=1)
    evidence_refs: Tuple[str, ...] = Field(..., min_length=1)

    state_entered_at: datetime
    recorded_at: datetime


class DecisionPointOpenedInterviewPayload(BaseModel):
    """V1 interview-origin Opened payload (ask-time)."""

    model_config = ConfigDict(frozen=True, extra="forbid")

    origin_surface: Literal[OriginSurface.PLANNING_INTERVIEW] = Field(...)

    decision_point_id: str = Field(..., min_length=1)
    mission_id: str = Field(..., min_length=1)
    run_id: str = Field(..., min_length=1)
    mission_slug: str = Field(..., min_length=1)
    mission_type: str = Field(..., min_length=1)
    phase: str = Field(..., min_length=1)

    origin_flow: OriginFlow = Field(...)
    question: str = Field(..., min_length=1)
    options: Tuple[str, ...] = Field(..., min_length=0)
    input_key: str = Field(..., min_length=1)
    step_id: str = Field(..., min_length=1)

    actor_id: str = Field(..., min_length=1)
    actor_type: Literal["human", "llm", "service"] = Field(...)

    state_entered_at: datetime
    recorded_at: datetime


# The discriminated union type (for TypeAdapter and type annotations)
_DecisionPointOpenedUnion = Annotated[
    Union[DecisionPointOpenedAdrPayload, DecisionPointOpenedInterviewPayload],
    Field(discriminator="origin_surface"),
]

_OPENED_ADAPTER: TypeAdapter[Any] = TypeAdapter(_DecisionPointOpenedUnion)


class _DecisionPointOpenedPayloadFactory:
    """Callable factory for DecisionPointOpenedPayload.

    Backward-compat: when called without origin_surface, defaults to ADR variant.
    Supports model_validate (discriminated union) and direct construction (ADR default).
    """

    _ReturnType = Union[DecisionPointOpenedAdrPayload, DecisionPointOpenedInterviewPayload]

    def __call__(self, **kwargs: Any) -> _ReturnType:
        if "origin_surface" not in kwargs:
            kwargs["origin_surface"] = OriginSurface.ADR.value
        return cast(
            _DecisionPointOpenedPayloadFactory._ReturnType,
            _OPENED_ADAPTER.validate_python(kwargs),
        )

    @staticmethod
    def model_validate(
        obj: Any,
    ) -> Union[DecisionPointOpenedAdrPayload, DecisionPointOpenedInterviewPayload]:
        if isinstance(obj, dict) and "origin_surface" not in obj:
            obj = {**obj, "origin_surface": OriginSurface.ADR.value}
        return cast(
            Union[DecisionPointOpenedAdrPayload, DecisionPointOpenedInterviewPayload],
            _OPENED_ADAPTER.validate_python(obj),
        )


DecisionPointOpenedPayload = _DecisionPointOpenedPayloadFactory()

# ── 5b: DecisionPointWidened ─────────────────────────────────────────────────


class DecisionPointWidenedPayload(BaseModel):
    """V1-only: one Slack thread created for an interview-origin Decision Moment."""

    model_config = ConfigDict(frozen=True, extra="forbid")

    origin_surface: Literal[OriginSurface.PLANNING_INTERVIEW] = Field(...)

    decision_point_id: str = Field(..., min_length=1)
    mission_id: str = Field(..., min_length=1)
    run_id: str = Field(..., min_length=1)
    mission_slug: str = Field(..., min_length=1)
    mission_type: str = Field(..., min_length=1)

    channel: Literal[WideningChannel.SLACK] = Field(...)
    teamspace_ref: TeamspaceRef
    default_channel_ref: DefaultChannelRef
    thread_ref: ThreadRef
    invited_participants: Tuple[ParticipantIdentity, ...] = Field(default_factory=tuple)

    widened_by: str = Field(..., min_length=1)
    widened_at: datetime
    recorded_at: datetime


# ── 5c: DecisionPointDiscussing — discriminated union ────────────────────────


class DecisionPointDiscussingAdrPayload(BaseModel):
    """3.x-compatible ADR-style Discussing payload."""

    model_config = ConfigDict(frozen=True, extra="forbid")

    origin_surface: Literal[OriginSurface.ADR] = Field(...)

    decision_point_id: str = Field(..., min_length=1)
    mission_id: str = Field(..., min_length=1)
    run_id: str = Field(..., min_length=1)
    mission_slug: str = Field(..., min_length=1)
    mission_type: str = Field(..., min_length=1)
    phase: str = Field(..., min_length=1)

    actor_id: str = Field(..., min_length=1)
    actor_type: Literal["human", "llm", "service"] = Field(...)
    authority_role: DecisionAuthorityRole
    mission_owner_authority_flag: bool
    mission_owner_authority_path: str

    rationale: str = Field(..., min_length=1)
    alternatives_considered: Tuple[str, ...] = Field(..., min_length=1)
    evidence_refs: Tuple[str, ...] = Field(..., min_length=1)

    state_entered_at: datetime
    recorded_at: datetime


class DecisionPointDiscussingInterviewPayload(BaseModel):
    """V1: synthesized contribution snapshot for interview-origin discussion."""

    model_config = ConfigDict(frozen=True, extra="forbid")

    origin_surface: Literal[OriginSurface.PLANNING_INTERVIEW] = Field(...)

    decision_point_id: str = Field(..., min_length=1)
    mission_id: str = Field(..., min_length=1)
    run_id: str = Field(..., min_length=1)
    mission_slug: str = Field(..., min_length=1)
    mission_type: str = Field(..., min_length=1)

    snapshot_kind: DiscussingSnapshotKind
    contributions: Tuple[str, ...] = Field(default_factory=tuple)

    actor_id: str = Field(..., min_length=1)
    actor_type: Literal["human", "llm", "service"] = Field(...)

    state_entered_at: datetime
    recorded_at: datetime


_DecisionPointDiscussingUnion = Annotated[
    Union[DecisionPointDiscussingAdrPayload, DecisionPointDiscussingInterviewPayload],
    Field(discriminator="origin_surface"),
]

_DISCUSSING_ADAPTER: TypeAdapter[Any] = TypeAdapter(_DecisionPointDiscussingUnion)


class _DecisionPointDiscussingPayloadFactory:
    """Callable factory for DecisionPointDiscussingPayload.

    Backward-compat: when called without origin_surface, defaults to ADR variant.
    """

    _ReturnType = Union[DecisionPointDiscussingAdrPayload, DecisionPointDiscussingInterviewPayload]

    def __call__(self, **kwargs: Any) -> _ReturnType:
        if "origin_surface" not in kwargs:
            kwargs["origin_surface"] = OriginSurface.ADR.value
        return cast(
            _DecisionPointDiscussingPayloadFactory._ReturnType,
            _DISCUSSING_ADAPTER.validate_python(kwargs),
        )

    @staticmethod
    def model_validate(
        obj: Any,
    ) -> Union[DecisionPointDiscussingAdrPayload, DecisionPointDiscussingInterviewPayload]:
        if isinstance(obj, dict) and "origin_surface" not in obj:
            obj = {**obj, "origin_surface": OriginSurface.ADR.value}
        return cast(
            Union[DecisionPointDiscussingAdrPayload, DecisionPointDiscussingInterviewPayload],
            _DISCUSSING_ADAPTER.validate_python(obj),
        )


DecisionPointDiscussingPayload = _DecisionPointDiscussingPayloadFactory()

# ── 5d: DecisionPointResolved — discriminated union ──────────────────────────


class DecisionPointResolvedAdrPayload(BaseModel):
    """3.x-compatible ADR-style Resolved payload."""

    model_config = ConfigDict(frozen=True, extra="forbid")

    origin_surface: Literal[OriginSurface.ADR] = Field(...)

    decision_point_id: str = Field(..., min_length=1)
    mission_id: str = Field(..., min_length=1)
    run_id: str = Field(..., min_length=1)
    mission_slug: str = Field(..., min_length=1)
    mission_type: str = Field(..., min_length=1)
    phase: str = Field(..., min_length=1)

    actor_id: str = Field(..., min_length=1)
    actor_type: Literal["human", "llm", "service"] = Field(...)
    authority_role: DecisionAuthorityRole
    mission_owner_authority_flag: bool
    mission_owner_authority_path: str

    rationale: str = Field(..., min_length=1)
    alternatives_considered: Tuple[str, ...] = Field(..., min_length=1)
    evidence_refs: Tuple[str, ...] = Field(..., min_length=1)

    state_entered_at: datetime
    recorded_at: datetime


class DecisionPointResolvedInterviewPayload(BaseModel):
    """V1 interview-origin Resolved payload."""

    model_config = ConfigDict(frozen=True, extra="forbid")

    origin_surface: Literal[OriginSurface.PLANNING_INTERVIEW] = Field(...)

    decision_point_id: str = Field(..., min_length=1)
    mission_id: str = Field(..., min_length=1)
    run_id: str = Field(..., min_length=1)
    mission_slug: str = Field(..., min_length=1)
    mission_type: str = Field(..., min_length=1)

    terminal_outcome: TerminalOutcome = Field(...)
    final_answer: Optional[str] = None
    other_answer: bool = False
    rationale: Optional[str] = None
    summary: Optional[SummaryBlock] = None
    actual_participants: Tuple[ParticipantIdentity, ...] = Field(default_factory=tuple)

    resolved_by: str = Field(..., min_length=1)
    closed_locally_while_widened: bool = False
    closure_message: Optional[ClosureMessageRef] = None

    state_entered_at: datetime
    recorded_at: datetime

    @model_validator(mode="after")
    def _enforce_outcome_fields(self) -> "DecisionPointResolvedInterviewPayload":
        if self.terminal_outcome == TerminalOutcome.RESOLVED:
            if self.final_answer is None or len(self.final_answer) == 0:
                raise ValueError("final_answer is required when terminal_outcome=resolved")
        else:
            if self.final_answer is not None:
                raise ValueError(
                    f"final_answer must be absent when terminal_outcome={self.terminal_outcome.value}"
                )
            if self.rationale is None or len(self.rationale) == 0:
                raise ValueError(
                    f"rationale is required when terminal_outcome={self.terminal_outcome.value}"
                )
            if self.other_answer:
                raise ValueError(
                    f"other_answer must be False when terminal_outcome={self.terminal_outcome.value}"
                )
        return self


_DecisionPointResolvedUnion = Annotated[
    Union[DecisionPointResolvedAdrPayload, DecisionPointResolvedInterviewPayload],
    Field(discriminator="origin_surface"),
]

_RESOLVED_ADAPTER: TypeAdapter[Any] = TypeAdapter(_DecisionPointResolvedUnion)


class _DecisionPointResolvedPayloadFactory:
    """Callable factory for DecisionPointResolvedPayload.

    Backward-compat: when called without origin_surface, defaults to ADR variant.
    """

    _ReturnType = Union[DecisionPointResolvedAdrPayload, DecisionPointResolvedInterviewPayload]

    def __call__(self, **kwargs: Any) -> _ReturnType:
        if "origin_surface" not in kwargs:
            kwargs["origin_surface"] = OriginSurface.ADR.value
        return cast(
            _DecisionPointResolvedPayloadFactory._ReturnType,
            _RESOLVED_ADAPTER.validate_python(kwargs),
        )

    @staticmethod
    def model_validate(
        obj: Any,
    ) -> Union[DecisionPointResolvedAdrPayload, DecisionPointResolvedInterviewPayload]:
        if isinstance(obj, dict) and "origin_surface" not in obj:
            obj = {**obj, "origin_surface": OriginSurface.ADR.value}
        return cast(
            Union[DecisionPointResolvedAdrPayload, DecisionPointResolvedInterviewPayload],
            _RESOLVED_ADAPTER.validate_python(obj),
        )


DecisionPointResolvedPayload = _DecisionPointResolvedPayloadFactory()

# ── 5e: DecisionPointOverridden ──────────────────────────────────────────────


class DecisionPointOverriddenPayload(BaseModel):
    """Payload for DecisionPointOverridden events."""

    model_config = ConfigDict(frozen=True, extra="forbid")

    origin_surface: Optional[OriginSurface] = None

    decision_point_id: str = Field(..., min_length=1)
    mission_id: str = Field(..., min_length=1)
    run_id: str = Field(..., min_length=1)
    mission_slug: str = Field(..., min_length=1)
    mission_type: str = Field(..., min_length=1)
    phase: str = Field(..., min_length=1)
    actor_id: str = Field(..., min_length=1)
    actor_type: Literal["human", "llm", "service"] = Field(...)
    authority_role: DecisionAuthorityRole
    mission_owner_authority_flag: bool
    mission_owner_authority_path: str
    rationale: str = Field(..., min_length=1)
    alternatives_considered: Tuple[str, ...] = Field(..., min_length=1)
    evidence_refs: Tuple[str, ...] = Field(..., min_length=1)
    state_entered_at: datetime
    recorded_at: datetime


# Map event types to their payload models (or TypeAdapters for unions)
_EVENT_TO_PAYLOAD: dict[str, Any] = {
    DECISION_POINT_OPENED: _OPENED_ADAPTER,
    DECISION_POINT_WIDENED: DecisionPointWidenedPayload,
    DECISION_POINT_DISCUSSING: _DISCUSSING_ADAPTER,
    DECISION_POINT_RESOLVED: _RESOLVED_ADAPTER,
    DECISION_POINT_OVERRIDDEN: DecisionPointOverriddenPayload,
}

# ── Section 6: Reducer Output Model ──────────────────────────────────────────


class ReducedDecisionPointState(BaseModel):
    """Deterministic projection output of reduce_decision_point_events()."""

    model_config = ConfigDict(frozen=True)

    # 3.x fields (preserved, unchanged semantics)
    state: Optional[DecisionPointState] = None
    decision_point_id: Optional[str] = None
    mission_id: Optional[str] = None
    run_id: Optional[str] = None
    mission_slug: Optional[str] = None
    mission_type: Optional[str] = None
    phase: Optional[str] = None
    last_actor_id: Optional[str] = None
    last_actor_type: Optional[str] = None
    last_authority_role: Optional[DecisionAuthorityRole] = None
    last_rationale: Optional[str] = None
    last_alternatives_considered: Optional[Tuple[str, ...]] = None
    last_evidence_refs: Optional[Tuple[str, ...]] = None
    last_state_entered_at: Optional[datetime] = None
    anomalies: Tuple[DecisionPointAnomaly, ...] = ()
    event_count: int = 0

    # V1 projection fields (NEW)
    origin_surface: Optional[OriginSurface] = None
    origin_flow: Optional[OriginFlow] = None
    question: Optional[str] = None
    options: Optional[Tuple[str, ...]] = None
    input_key: Optional[str] = None
    step_id: Optional[str] = None
    widening: Optional[WideningProjection] = None
    terminal_outcome: Optional[TerminalOutcome] = None
    final_answer: Optional[str] = None
    other_answer: bool = False
    summary: Optional[SummaryBlock] = None
    actual_participants: Tuple[ParticipantIdentity, ...] = ()
    resolved_by: Optional[str] = None
    closed_locally_while_widened: bool = False
    closure_message: Optional[ClosureMessageRef] = None
    reopened_from_deferred: bool = False


# ── Section 7: Reducer (FR-003, FR-014) ─────────────────────────────────────


def _policy_violation(
    event_type: str, target_state: DecisionPointState, payload: Any
) -> Optional[Tuple[str, str]]:
    """Pure: ``(anomaly_kind, message)`` when *payload* breaks authority/LLM policy.

    Shared by pass 1 (so only policy-valid terminals compete) and pass 2.
    Applies to ADR variants only (they carry ``authority_role``).
    """
    if not isinstance(
        payload,
        (
            DecisionPointOpenedAdrPayload,
            DecisionPointDiscussingAdrPayload,
            DecisionPointResolvedAdrPayload,
            DecisionPointOverriddenPayload,
        ),
    ):
        return None
    role = payload.authority_role
    if target_state in _AUTHORITY_REQUIRED_STATES and (
        payload.actor_type != "human"
        or role != DecisionAuthorityRole.MISSION_OWNER
        or not payload.mission_owner_authority_flag
    ):
        return (
            "authority_policy_violation",
            f"{event_type!r} requires actor_type='human', "
            f"authority_role='mission_owner', and "
            f"mission_owner_authority_flag=True; got "
            f"actor_type={payload.actor_type!r}, "
            f"authority_role={role.value!r}, "
            f"mission_owner_authority_flag={payload.mission_owner_authority_flag!r}",
        )
    if payload.actor_type != "llm":
        return None
    if payload.phase != "P0":
        return (
            "llm_policy_violation",
            f"LLM actor only allowed in phase='P0'; got phase={payload.phase!r}",
        )
    if role not in (DecisionAuthorityRole.ADVISORY, DecisionAuthorityRole.INFORMED):
        return (
            "llm_policy_violation",
            f"LLM actor must have advisory or informed role; got authority_role={role.value!r}",
        )
    if payload.mission_owner_authority_flag:
        return ("llm_policy_violation", "LLM actor must not carry mission-owner authority")
    return None


def _unfoldable_anomaly(event_id: str) -> DecisionPointAnomaly:
    return DecisionPointAnomaly(
        kind=_ANOMALY_UNFOLDABLE_TERMINAL,
        event_id=event_id,
        message=(
            "Unfoldable combination of DecisionPointResolved events for one "
            "decision_point_id; the first Resolved stands"
        ),
    )


def _terminal_candidate(
    event: Event, state_before: Optional[DecisionPointState]
) -> Optional[Tuple[str, TerminalOutcome]]:
    """``(event_id, outcome)`` for a terminal Resolved that pass 2 could apply.

    Valid means: payload parses, policy holds, and RESOLVED is reachable from
    *state_before* (the state after the non-terminal events preceding it).
    """
    if event.event_type != DECISION_POINT_RESOLVED or not isinstance(event.payload, dict):
        return None
    if DecisionPointState.RESOLVED not in _ALLOWED_TRANSITIONS.get(state_before, frozenset()):
        return None
    try:
        payload = _RESOLVED_ADAPTER.validate_python(event.payload)
    except Exception:
        return None
    outcome = getattr(payload, "terminal_outcome", None)
    if not isinstance(outcome, TerminalOutcome):
        return None
    if _policy_violation(event.event_type, DecisionPointState.RESOLVED, payload) is not None:
        return None
    return (event.event_id, outcome)


def _is_applicable(event: Event, target: DecisionPointState) -> bool:
    """True when pass 2 would parse *event*'s payload and accept it under policy."""
    payload_dict = event.payload if isinstance(event.payload, dict) else {}
    handler = _EVENT_TO_PAYLOAD[event.event_type]
    try:
        if isinstance(handler, TypeAdapter):
            if "origin_surface" not in payload_dict:
                payload_dict = {**payload_dict, "origin_surface": OriginSurface.ADR.value}
            payload: Any = handler.validate_python(payload_dict)
        else:
            payload = handler.model_validate(payload_dict)
    except Exception:
        return False
    return _policy_violation(event.event_type, target, payload) is None


def _plan_terminals(ordered: Sequence[Event]) -> _TerminalPlan:
    """Pass 1: pick the effective terminal among VALID terminals (canonical order)."""
    state: Optional[DecisionPointState] = None
    candidates: List[Tuple[str, TerminalOutcome]] = []
    for event in ordered:
        candidate = _terminal_candidate(event, state)
        if candidate is not None:
            candidates.append(candidate)
            continue
        target = _EVENT_TO_STATE.get(event.event_type)
        if target in (None, DecisionPointState.RESOLVED, DecisionPointState.OVERRIDDEN):
            continue
        if target in _ALLOWED_TRANSITIONS.get(state, frozenset()) and _is_applicable(event, target):
            state = target
    return _select_effective_terminal(candidates)


def reduce_decision_point_events(
    events: Sequence[Event],
) -> ReducedDecisionPointState:
    """Deterministic reducer: Sequence[Event] -> ReducedDecisionPointState.

    Pipeline: sort -> dedup -> filter(DECISION_POINT_EVENT_TYPES) -> fold -> freeze.

    Transition rules (V1):
      None -> open
      open -> widened | discussing | resolved
      widened -> widened (idempotent no-op) | discussing | resolved
      discussing -> discussing | resolved
      resolved -> overridden
      overridden -> (terminal, no further transitions)

    Authority policy (ADR variants only):
      resolved and overridden require actor_type="human", authority_role="mission_owner",
      and mission_owner_authority_flag=True.

    LLM policy (ADR variants only):
      LLM actors allowed only when phase="P0", authority_role in {advisory, informed},
      and mission_owner_authority_flag=False.

    V1 branching:
      - Opened(adr): projects ADR fields + origin_surface=adr.
      - Opened(interview): projects interview fields; skips ADR fields.
      - Widened: if already WIDENED, no-op (idempotent). Otherwise builds widening projection.
      - Discussing(adr): projects ADR fields.
      - Discussing(interview): updates actor/timestamp fields only.
      - Resolved(adr): projects ADR fields.
      - Resolved(interview): projects terminal_outcome and outcome fields.
      - Overridden: unchanged ADR-style projection.

    Anomaly kinds:
      - "invalid_transition": state machine violation.
      - "authority_policy_violation": missing mission-owner authority on resolved/overridden.
      - "llm_policy_violation": LLM actor policy breach.
      - "malformed_payload": payload validation failed.
      - "event_after_terminal": event arrived after OVERRIDDEN.
      - "origin_mismatch": event's origin_surface differs from first-seen for this decision_point_id.
    """
    # Step 1: Sort for determinism
    sorted_events = sorted(events, key=canonical_order_key)

    # Step 2: Deduplicate by event_id
    deduped = dedup_events(sorted_events)

    # Step 3: Count post-dedup (before filter)
    event_count = len(deduped)

    # Step 4: Filter to DecisionPoint family
    dp_events = [e for e in deduped if e.event_type in DECISION_POINT_EVENT_TYPES]

    # Step 4b: pass 1 -- choose the effective terminal (deferred -> resolved reopen)
    plan = _plan_terminals(dp_events)
    reopened = False

    # Step 5: Mutable accumulator for fold
    anomalies: List[DecisionPointAnomaly] = []
    current_state: Optional[DecisionPointState] = None
    decision_point_id: Optional[str] = None
    mission_id: Optional[str] = None
    run_id: Optional[str] = None
    mission_slug: Optional[str] = None
    mission_type: Optional[str] = None
    phase: Optional[str] = None
    last_actor_id: Optional[str] = None
    last_actor_type: Optional[str] = None
    last_authority_role: Optional[DecisionAuthorityRole] = None
    last_rationale: Optional[str] = None
    last_alternatives_considered: Optional[Tuple[str, ...]] = None
    last_evidence_refs: Optional[Tuple[str, ...]] = None
    last_state_entered_at: Optional[datetime] = None

    # V1 projection fields
    origin_surface_seen: Optional[OriginSurface] = None
    proj_origin_surface: Optional[OriginSurface] = None
    proj_origin_flow: Optional[OriginFlow] = None
    proj_question: Optional[str] = None
    proj_options: Optional[Tuple[str, ...]] = None
    proj_input_key: Optional[str] = None
    proj_step_id: Optional[str] = None
    proj_widening: Optional[WideningProjection] = None
    proj_terminal_outcome: Optional[TerminalOutcome] = None
    proj_final_answer: Optional[str] = None
    proj_other_answer: bool = False
    proj_summary: Optional[SummaryBlock] = None
    proj_actual_participants: Tuple[ParticipantIdentity, ...] = ()
    proj_resolved_by: Optional[str] = None
    proj_closed_locally_while_widened: bool = False
    proj_closure_message: Optional[ClosureMessageRef] = None

    for event in dp_events:
        event_type = event.event_type
        event_id = event.event_id
        payload_dict = event.payload if isinstance(event.payload, dict) else {}

        # Determine target state from event type
        target_state = _EVENT_TO_STATE.get(event_type)
        if target_state is None:
            anomalies.append(
                DecisionPointAnomaly(
                    kind="malformed_payload",
                    event_id=event_id,
                    message=f"Unknown event type in DecisionPoint family: {event_type!r}",
                )
            )
            continue

        # Pass-1 verdict: reopen-point deferred is skipped; surplus terminals are anomalies.
        if event_id in plan.demoted:
            continue
        if event_id in plan.extras:
            anomalies.append(_unfoldable_anomaly(event_id))
            continue

        # Check: event after terminal (overridden)
        if current_state == DecisionPointState.OVERRIDDEN:
            anomalies.append(
                DecisionPointAnomaly(
                    kind="event_after_terminal",
                    event_id=event_id,
                    message=f"Event {event_type!r} arrived after terminal state 'overridden'",
                )
            )
            continue

        # Idempotent Widened: before transition check, absorb duplicate silently
        if (
            target_state == DecisionPointState.WIDENED
            and current_state == DecisionPointState.WIDENED
        ):
            continue

        # Check: valid transition
        allowed = _ALLOWED_TRANSITIONS.get(current_state, frozenset())
        if target_state not in allowed:
            anomalies.append(
                DecisionPointAnomaly(
                    kind="invalid_transition",
                    event_id=event_id,
                    message=(
                        f"Invalid transition: {current_state.value if current_state else 'None'} "
                        f"-> {target_state.value}"
                    ),
                )
            )
            continue

        # Parse payload using appropriate adapter or class.
        # 3.x backward-compat: if origin_surface is absent and the event uses a
        # discriminated union, default to "adr" so 3.x payloads still validate.
        payload_handler = _EVENT_TO_PAYLOAD[event_type]
        parse_dict = payload_dict
        if isinstance(payload_handler, TypeAdapter) and "origin_surface" not in payload_dict:
            parse_dict = {**payload_dict, "origin_surface": OriginSurface.ADR.value}
        try:
            if isinstance(payload_handler, TypeAdapter):
                payload: Any = payload_handler.validate_python(parse_dict)
            else:
                payload = payload_handler.model_validate(parse_dict)
        except Exception as exc:
            anomalies.append(
                DecisionPointAnomaly(
                    kind="malformed_payload",
                    event_id=event_id,
                    message=f"Payload validation failed for {event_type!r}: {exc}",
                )
            )
            continue

        # Origin surface tracking: detect mismatch across events for same decision_point_id
        event_origin_surface: Optional[OriginSurface] = getattr(payload, "origin_surface", None)
        if event_origin_surface is not None:
            if origin_surface_seen is None:
                origin_surface_seen = event_origin_surface
            elif origin_surface_seen != event_origin_surface:
                anomalies.append(
                    DecisionPointAnomaly(
                        kind="origin_mismatch",
                        event_id=event_id,
                        message=(
                            f"Event origin_surface={event_origin_surface!r} differs from prior "
                            f"{origin_surface_seen!r} for decision_point_id="
                            f"{payload_dict.get('decision_point_id', '?')!r}"
                        ),
                    )
                )
                # still apply the event per spec

        # Authority (FR-003) and LLM policy -- shared with pass 1
        violation = _policy_violation(event_type, target_state, payload)
        if violation is not None:
            anomalies.append(
                DecisionPointAnomaly(kind=violation[0], event_id=event_id, message=violation[1])
            )
            continue

        # Apply transition
        current_state = target_state
        if event_id == plan.effective:
            reopened = True

        # Branch per event type for projection
        if isinstance(payload, DecisionPointOpenedAdrPayload):
            # ADR-origin Opened: project all ADR fields
            decision_point_id = payload.decision_point_id
            mission_id = payload.mission_id
            run_id = payload.run_id
            mission_slug = payload.mission_slug
            mission_type = payload.mission_type
            phase = payload.phase
            last_actor_id = payload.actor_id
            last_actor_type = payload.actor_type
            last_authority_role = payload.authority_role
            last_rationale = payload.rationale
            last_alternatives_considered = payload.alternatives_considered
            last_evidence_refs = payload.evidence_refs
            last_state_entered_at = payload.state_entered_at
            proj_origin_surface = OriginSurface.ADR

        elif isinstance(payload, DecisionPointOpenedInterviewPayload):
            # Interview-origin Opened: project interview fields
            decision_point_id = payload.decision_point_id
            mission_id = payload.mission_id
            run_id = payload.run_id
            mission_slug = payload.mission_slug
            mission_type = payload.mission_type
            phase = payload.phase
            last_actor_id = payload.actor_id
            last_actor_type = payload.actor_type
            last_state_entered_at = payload.state_entered_at
            proj_origin_surface = OriginSurface.PLANNING_INTERVIEW
            proj_origin_flow = payload.origin_flow
            proj_question = payload.question
            proj_options = payload.options
            proj_input_key = payload.input_key
            proj_step_id = payload.step_id

        elif isinstance(payload, DecisionPointWidenedPayload):
            # Widened: build widening projection (idempotent case already handled above)
            decision_point_id = payload.decision_point_id
            mission_id = payload.mission_id
            run_id = payload.run_id
            mission_slug = payload.mission_slug
            mission_type = payload.mission_type
            proj_widening = WideningProjection(
                channel=payload.channel,
                teamspace_ref=payload.teamspace_ref,
                default_channel_ref=payload.default_channel_ref,
                thread_ref=payload.thread_ref,
                invited_participants=payload.invited_participants,
                widened_by=payload.widened_by,
                widened_at=payload.widened_at,
            )

        elif isinstance(payload, DecisionPointDiscussingAdrPayload):
            # ADR-origin Discussing: project ADR fields
            decision_point_id = payload.decision_point_id
            mission_id = payload.mission_id
            run_id = payload.run_id
            mission_slug = payload.mission_slug
            mission_type = payload.mission_type
            phase = payload.phase
            last_actor_id = payload.actor_id
            last_actor_type = payload.actor_type
            last_authority_role = payload.authority_role
            last_rationale = payload.rationale
            last_alternatives_considered = payload.alternatives_considered
            last_evidence_refs = payload.evidence_refs
            last_state_entered_at = payload.state_entered_at

        elif isinstance(payload, DecisionPointDiscussingInterviewPayload):
            # Interview-origin Discussing: update actor/timestamp only; don't touch ADR fields
            decision_point_id = payload.decision_point_id
            mission_id = payload.mission_id
            run_id = payload.run_id
            mission_slug = payload.mission_slug
            mission_type = payload.mission_type
            last_actor_id = payload.actor_id
            last_actor_type = payload.actor_type
            last_state_entered_at = payload.state_entered_at

        elif isinstance(payload, DecisionPointResolvedAdrPayload):
            # ADR-origin Resolved: project ADR fields
            decision_point_id = payload.decision_point_id
            mission_id = payload.mission_id
            run_id = payload.run_id
            mission_slug = payload.mission_slug
            mission_type = payload.mission_type
            phase = payload.phase
            last_actor_id = payload.actor_id
            last_actor_type = payload.actor_type
            last_authority_role = payload.authority_role
            last_rationale = payload.rationale
            last_alternatives_considered = payload.alternatives_considered
            last_evidence_refs = payload.evidence_refs
            last_state_entered_at = payload.state_entered_at

        elif isinstance(payload, DecisionPointResolvedInterviewPayload):
            # Interview-origin Resolved: project outcome fields
            decision_point_id = payload.decision_point_id
            mission_id = payload.mission_id
            run_id = payload.run_id
            mission_slug = payload.mission_slug
            mission_type = payload.mission_type
            last_state_entered_at = payload.state_entered_at
            last_rationale = (
                payload.rationale
            )  # may be None for terminal=resolved; required for deferred/canceled
            proj_terminal_outcome = payload.terminal_outcome
            proj_final_answer = payload.final_answer
            proj_other_answer = payload.other_answer
            proj_summary = payload.summary
            proj_actual_participants = payload.actual_participants
            proj_resolved_by = payload.resolved_by
            proj_closure_message = payload.closure_message
            # FR-009: summary is required when a widening event preceded Resolved.
            if proj_widening is not None and payload.summary is None:
                anomalies.append(
                    DecisionPointAnomaly(
                        kind="missing_summary",
                        event_id=event_id,
                        message=(
                            "DecisionPointResolved with origin=planning_interview requires "
                            "summary when a prior DecisionPointWidened exists for this decision_point_id"
                        ),
                    )
                )
            # closed_locally_while_widened: validate against widening state
            if payload.closed_locally_while_widened:
                if proj_widening is None:
                    anomalies.append(
                        DecisionPointAnomaly(
                            kind="invalid_transition",
                            event_id=event_id,
                            message=(
                                f"closed_locally_while_widened=True without prior DecisionPointWidened "
                                f"(event_id={event_id!r})"
                            ),
                        )
                    )
                    proj_closed_locally_while_widened = False
                else:
                    proj_closed_locally_while_widened = True
            else:
                proj_closed_locally_while_widened = False

        elif isinstance(payload, DecisionPointOverriddenPayload):
            # Overridden: ADR-style projection
            decision_point_id = payload.decision_point_id
            mission_id = payload.mission_id
            run_id = payload.run_id
            mission_slug = payload.mission_slug
            mission_type = payload.mission_type
            phase = payload.phase
            last_actor_id = payload.actor_id
            last_actor_type = payload.actor_type
            last_authority_role = payload.authority_role
            last_rationale = payload.rationale
            last_alternatives_considered = payload.alternatives_considered
            last_evidence_refs = payload.evidence_refs
            last_state_entered_at = payload.state_entered_at

    # Step 6: Freeze and return
    return ReducedDecisionPointState(
        state=current_state,
        decision_point_id=decision_point_id,
        mission_id=mission_id,
        run_id=run_id,
        mission_slug=mission_slug,
        mission_type=mission_type,
        phase=phase,
        last_actor_id=last_actor_id,
        last_actor_type=last_actor_type,
        last_authority_role=last_authority_role,
        last_rationale=last_rationale,
        last_alternatives_considered=last_alternatives_considered,
        last_evidence_refs=last_evidence_refs,
        last_state_entered_at=last_state_entered_at,
        anomalies=tuple(anomalies),
        event_count=event_count,
        origin_surface=proj_origin_surface,
        origin_flow=proj_origin_flow,
        question=proj_question,
        options=proj_options,
        input_key=proj_input_key,
        step_id=proj_step_id,
        widening=proj_widening,
        terminal_outcome=proj_terminal_outcome,
        final_answer=proj_final_answer,
        other_answer=proj_other_answer,
        summary=proj_summary,
        actual_participants=proj_actual_participants,
        resolved_by=proj_resolved_by,
        closed_locally_while_widened=proj_closed_locally_while_widened,
        closure_message=proj_closure_message,
        reopened_from_deferred=reopened,
    )


# ── Section 8: Ledger-records fold ───────────────────────────────────────────


def _record_to_decision_event(record: Mapping[str, Any]) -> Optional[Event]:
    """Validate one ledger record; return its DecisionPoint envelope or ``None``.

    Non-DecisionPoint records yield ``None``. A DecisionPoint record must be a
    full envelope (a flat payload record lacks ``aggregate_id`` and friends),
    otherwise :class:`LedgerValidationError` is raised.
    """
    normalised = build_record(record)
    if normalised.get("event_type") not in DECISION_POINT_EVENT_TYPES:
        return None
    if not (isinstance(normalised.get("payload"), dict) and "aggregate_id" in normalised):
        raise LedgerValidationError(
            f"{normalised.get('event_type')!r} record is not a full envelope "
            "(missing aggregate_id or payload object)"
        )
    try:
        event = Event.model_validate(normalised)
    except ValueError as exc:
        raise LedgerValidationError(f"record is not a valid Event envelope: {exc}") from exc
    if not isinstance(event.payload.get("decision_point_id"), str):
        raise LedgerValidationError("record payload has no string 'decision_point_id'")
    return event


def _group_decision_events(
    records: Iterable[Mapping[str, Any]], strict: bool
) -> Dict[str, List[Event]]:
    groups: Dict[str, List[Event]] = {}
    for record in records:
        try:
            event = _record_to_decision_event(record)
        except LedgerValidationError:
            if strict:
                raise
            continue
        if event is not None:
            groups.setdefault(event.payload["decision_point_id"], []).append(event)
    return groups


def reduce_decision_point_records(
    records: Iterable[Mapping[str, Any]], *, strict: bool = True
) -> Dict[str, ReducedDecisionPointState]:
    """Fold ledger records into one reduced state per ``decision_point_id``.

    Each record is validated with :func:`spec_kitty_events.persistence.build_record`
    and must be a full :class:`Event` envelope when it is a DecisionPoint event;
    records of other event types are skipped. Order of *records* is irrelevant.

    ``strict=True`` raises :class:`LedgerValidationError` for a malformed or
    non-envelope record and :class:`DecisionPointFoldError` when a decision has
    an ``unfoldable_terminal_combination`` anomaly. ``strict=False`` skips
    malformed records and returns the reduced states with anomalies recorded.
    """
    result: Dict[str, ReducedDecisionPointState] = {}
    for decision_id, events in _group_decision_events(records, strict).items():
        state = reduce_decision_point_events(events)
        if strict and any(a.kind == _ANOMALY_UNFOLDABLE_TERMINAL for a in state.anomalies):
            raise DecisionPointFoldError(
                f"unfoldable terminal combination for decision_point_id={decision_id!r}"
            )
        result[decision_id] = state
    return result
