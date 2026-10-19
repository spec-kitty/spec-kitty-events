"""Decision-significance event contracts (RACI-aware).

Mirrors, by shape and never by import (C-002), the spec-kitty runtime payloads
``SignificanceEvaluatedPayload`` and ``TimeoutExpiredPayload`` from
``src/runtime/next/_internal_runtime/significance.py`` (lines 485-522) and the
RACI value objects from ``schema.py`` (lines 79-140).

Deliberate differences from the producer (operator rulings):

* R1: ``RuntimeActorIdentity`` is untouched; a new ``RaciRoleBinding`` is used.
* R2: ``RaciRoleBinding.actor_id`` is REQUIRED and non-empty, where core emits
  ``str | None = None``. This is the single narrowing; a payload with a
  null, empty or absent ``actor_id`` is refused until core sets it.
* D6: core carries ``raci_snapshot`` as an untyped ``dict``; here it is the
  typed ``ResolvedRaciSnapshot`` so nested actors are validated.
"""

from __future__ import annotations

from typing import Any, Dict, FrozenSet, List, Literal, Optional, Tuple

from pydantic import BaseModel, ConfigDict, Field, model_validator

SIGNIFICANCE_EVALUATED: str = "SignificanceEvaluated"
DECISION_TIMEOUT_EXPIRED: str = "DecisionTimeoutExpired"

DECISION_SIGNIFICANCE_EVENT_TYPES: FrozenSet[str] = frozenset(
    {SIGNIFICANCE_EVALUATED, DECISION_TIMEOUT_EXPIRED}
)


class RaciRoleBinding(BaseModel):
    """Single actor-role binding; ``actor_id`` is required (R2)."""

    model_config = ConfigDict(frozen=True, extra="forbid")

    actor_type: Literal["human", "llm", "service"]
    actor_id: str = Field(..., min_length=1)


class ResolvedRaciSnapshot(BaseModel):
    """Fully resolved RACI binding with provenance (core ``ResolvedRACIBinding``).

    Core enforces no accountable-is-human rule on the resolved binding (that
    belongs to ``RACIAssignment``), so none is enforced here.
    """

    model_config = ConfigDict(frozen=True, extra="forbid")

    step_id: str
    responsible: RaciRoleBinding
    accountable: RaciRoleBinding
    consulted: List[RaciRoleBinding] = Field(default_factory=list)
    informed: List[RaciRoleBinding] = Field(default_factory=list)
    source: Literal["inferred", "explicit"]
    inferred_rule: Optional[str] = None
    override_reason: Optional[str] = None

    @model_validator(mode="after")
    def _validate_provenance(self) -> "ResolvedRaciSnapshot":
        # Mirrors core schema.py _validate_provenance (three rules).
        if self.source == "explicit" and not self.override_reason:
            raise ValueError("source='explicit' requires non-empty override_reason")
        if self.source == "inferred" and not self.inferred_rule:
            raise ValueError("source='inferred' requires non-empty inferred_rule")
        if self.source == "inferred" and self.override_reason is not None:
            raise ValueError("source='inferred' must not have override_reason")
        return self


class SignificanceEvaluatedPayload(BaseModel):
    """A decision's significance was scored and routed."""

    model_config = ConfigDict(frozen=True, extra="forbid")

    run_id: str = Field(..., min_length=1)
    decision_id: str = Field(..., min_length=1)
    step_id: str = Field(..., min_length=1)
    significance_score: Dict[str, Any]
    hard_trigger_classes: Tuple[str, ...] = ()
    effective_band: Literal["low", "medium", "high"]
    actor: RaciRoleBinding


class DecisionTimeoutExpiredPayload(BaseModel):
    """A decision exceeded its configured timeout window."""

    model_config = ConfigDict(frozen=True, extra="forbid")

    run_id: str = Field(..., min_length=1)
    decision_id: str = Field(..., min_length=1)
    step_id: str = Field(..., min_length=1)
    significance_score: Dict[str, Any]
    effective_band: Literal["medium", "high"]  # never "low": low auto-proceeds
    timeout_configured_seconds: int = Field(..., gt=0)
    escalation_targets: Tuple[RaciRoleBinding, ...] = ()
    raci_snapshot: ResolvedRaciSnapshot
    actor: RaciRoleBinding


__all__ = [
    "SIGNIFICANCE_EVALUATED",
    "DECISION_TIMEOUT_EXPIRED",
    "DECISION_SIGNIFICANCE_EVENT_TYPES",
    "RaciRoleBinding",
    "ResolvedRaciSnapshot",
    "SignificanceEvaluatedPayload",
    "DecisionTimeoutExpiredPayload",
]
