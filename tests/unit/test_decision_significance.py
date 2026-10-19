"""Unit tests for the decision-significance (RACI) event contracts."""

from __future__ import annotations

import copy
import json
from pathlib import Path
from typing import Any, Dict

import pytest
from pydantic import ValidationError

import spec_kitty_events
from spec_kitty_events import (
    DECISION_SIGNIFICANCE_EVENT_TYPES,
    DECISION_TIMEOUT_EXPIRED,
    SIGNIFICANCE_EVALUATED,
    DecisionTimeoutExpiredPayload,
    RaciRoleBinding,
    ResolvedRaciSnapshot,
    RuntimeActorIdentity,
    SignificanceEvaluatedPayload,
)
from spec_kitty_events.conformance import load_fixtures, validate_event

SCHEMAS = Path(spec_kitty_events.__file__).parent / "schemas"
SVC: Dict[str, Any] = {"actor_type": "service", "actor_id": "runtime"}
HUM: Dict[str, Any] = {"actor_type": "human", "actor_id": "user-1"}
LLM: Dict[str, Any] = {"actor_type": "llm", "actor_id": "agent-1"}


def _snapshot(**kw: Any) -> Dict[str, Any]:
    base: Dict[str, Any] = {
        "step_id": "plan",
        "responsible": LLM,
        "accountable": HUM,
        "consulted": [HUM],
        "informed": [SVC],
        "source": "inferred",
        "inferred_rule": "default",
    }
    base.update(kw)
    return base


def _se(**kw: Any) -> Dict[str, Any]:
    base: Dict[str, Any] = {
        "run_id": "r1",
        "decision_id": "d1",
        "step_id": "s1",
        "significance_score": {"score": 3},
        "hard_trigger_classes": ["security"],
        "effective_band": "medium",
        "actor": SVC,
    }
    base.update(kw)
    return base


def _to(**kw: Any) -> Dict[str, Any]:
    base: Dict[str, Any] = {
        "run_id": "r1",
        "decision_id": "d1",
        "step_id": "s1",
        "significance_score": {"score": 3},
        "effective_band": "high",
        "timeout_configured_seconds": 900,
        "escalation_targets": [HUM, LLM],
        "raci_snapshot": _snapshot(),
        "actor": SVC,
    }
    base.update(kw)
    return base


def test_constants() -> None:
    assert SIGNIFICANCE_EVALUATED == "SignificanceEvaluated"
    assert DECISION_TIMEOUT_EXPIRED == "DecisionTimeoutExpired"
    assert DECISION_SIGNIFICANCE_EVENT_TYPES == {
        "SignificanceEvaluated",
        "DecisionTimeoutExpired",
    }


def test_round_trip() -> None:
    se = SignificanceEvaluatedPayload.model_validate(_se())
    assert SignificanceEvaluatedPayload.model_validate(se.model_dump()) == se
    to = DecisionTimeoutExpiredPayload.model_validate(_to())
    assert DecisionTimeoutExpiredPayload.model_validate(to.model_dump()) == to
    assert se.hard_trigger_classes == ("security",)
    assert len(to.escalation_targets) == 2


def test_defaults() -> None:
    se = SignificanceEvaluatedPayload.model_validate(
        {k: v for k, v in _se().items() if k != "hard_trigger_classes"}
    )
    assert se.hard_trigger_classes == ()
    to = DecisionTimeoutExpiredPayload.model_validate(
        {k: v for k, v in _to().items() if k != "escalation_targets"}
    )
    assert to.escalation_targets == ()


def test_frozen() -> None:
    se = SignificanceEvaluatedPayload.model_validate(_se())
    with pytest.raises(ValidationError):
        setattr(se, "run_id", "x")
    with pytest.raises(ValidationError):
        setattr(RaciRoleBinding(actor_type="human", actor_id="a"), "actor_id", "b")


@pytest.mark.parametrize(
    "model,payload",
    [
        (RaciRoleBinding, HUM),
        (ResolvedRaciSnapshot, _snapshot()),
        (SignificanceEvaluatedPayload, _se()),
        (DecisionTimeoutExpiredPayload, _to()),
    ],
)
def test_extra_forbidden(model: Any, payload: Dict[str, Any]) -> None:
    with pytest.raises(ValidationError):
        model.model_validate({**payload, "surprise": 1})


def test_literal_bounds() -> None:
    with pytest.raises(ValidationError):
        RaciRoleBinding.model_validate({"actor_type": "robot", "actor_id": "a"})
    with pytest.raises(ValidationError):
        SignificanceEvaluatedPayload.model_validate(_se(effective_band="critical"))
    with pytest.raises(ValidationError):
        DecisionTimeoutExpiredPayload.model_validate(_to(effective_band="low"))
    with pytest.raises(ValidationError):
        ResolvedRaciSnapshot.model_validate(_snapshot(source="guessed"))


@pytest.mark.parametrize("value", [0, -1])
def test_timeout_must_be_positive(value: int) -> None:
    with pytest.raises(ValidationError):
        DecisionTimeoutExpiredPayload.model_validate(_to(timeout_configured_seconds=value))


@pytest.mark.parametrize("field", ["run_id", "decision_id", "step_id"])
def test_identifiers_non_empty(field: str) -> None:
    with pytest.raises(ValidationError):
        SignificanceEvaluatedPayload.model_validate(_se(**{field: ""}))
    with pytest.raises(ValidationError):
        DecisionTimeoutExpiredPayload.model_validate(_to(**{field: ""}))


def _bad_actor(kind: str) -> Dict[str, Any]:
    if kind == "missing":
        return {"actor_type": "service"}
    return {"actor_type": "service", "actor_id": None if kind == "null" else ""}


@pytest.mark.parametrize("kind", ["null", "empty", "missing"])
@pytest.mark.parametrize(
    "build",
    [
        lambda a: (SignificanceEvaluatedPayload, _se(actor=a)),
        lambda a: (DecisionTimeoutExpiredPayload, _to(actor=a)),
        lambda a: (DecisionTimeoutExpiredPayload, _to(escalation_targets=[a])),
        lambda a: (DecisionTimeoutExpiredPayload, _to(raci_snapshot=_snapshot(responsible=a))),
        lambda a: (DecisionTimeoutExpiredPayload, _to(raci_snapshot=_snapshot(accountable=a))),
        lambda a: (DecisionTimeoutExpiredPayload, _to(raci_snapshot=_snapshot(consulted=[a]))),
        lambda a: (DecisionTimeoutExpiredPayload, _to(raci_snapshot=_snapshot(informed=[a]))),
    ],
)
def test_r2_actor_id_required_everywhere(kind: str, build: Any) -> None:
    model, payload = build(_bad_actor(kind))
    with pytest.raises(ValidationError):
        model.model_validate(payload)


def test_whitespace_actor_id_follows_package_rule() -> None:
    """Only min_length=1 applies, as for RuntimeActorIdentity.actor_id."""
    assert RaciRoleBinding(actor_type="human", actor_id=" ").actor_id == " "


def test_provenance_rules_mirror_core() -> None:
    ResolvedRaciSnapshot.model_validate(_snapshot())
    ResolvedRaciSnapshot.model_validate(
        _snapshot(source="explicit", inferred_rule=None, override_reason="why")
    )
    for bad in (
        _snapshot(source="explicit", inferred_rule=None, override_reason=None),
        _snapshot(source="explicit", inferred_rule=None, override_reason=""),
        _snapshot(inferred_rule=None),
        _snapshot(inferred_rule=""),
        _snapshot(override_reason="x"),
        _snapshot(override_reason=""),
    ):
        with pytest.raises(ValidationError):
            ResolvedRaciSnapshot.model_validate(bad)


def test_accountable_need_not_be_human_on_resolved_snapshot() -> None:
    """Core enforces accountable-is-human on RACIAssignment only, not here."""
    ResolvedRaciSnapshot.model_validate(_snapshot(accountable=SVC))


def test_validate_event_accepts_and_refuses() -> None:
    assert validate_event(_se(), "SignificanceEvaluated").valid
    assert validate_event(_to(), "DecisionTimeoutExpired").valid
    assert not validate_event(_se(actor=_bad_actor("null")), "SignificanceEvaluated").valid
    assert not validate_event(_to(effective_band="low"), "DecisionTimeoutExpired").valid
    result = validate_event(_se(actor=_bad_actor("empty")), "SignificanceEvaluated")
    assert not result.valid and not result.schema_check_skipped


def test_decision_significance_fixtures() -> None:
    cases = load_fixtures("decision_significance")
    assert len(cases) >= 16
    for case in cases:
        res = validate_event(copy.deepcopy(case.payload), case.event_type)
        assert res.valid == (case.expected_valid), case.id


def test_runtime_actor_identity_schema_unchanged() -> None:
    """R1 ratchet: RuntimeActorIdentity is not touched by this mission."""
    committed = json.loads((SCHEMAS / "runtime_actor_identity.schema.json").read_text())
    live = RuntimeActorIdentity.model_json_schema()
    for key in ("properties", "required", "additionalProperties"):
        assert live.get(key) == committed.get(key)
