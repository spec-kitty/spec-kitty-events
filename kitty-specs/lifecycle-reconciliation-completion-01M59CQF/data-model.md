# Data Model

All models: Pydantic v2, `frozen=True`, `extra="forbid"`, in `src/spec_kitty_events/decision_significance.py`.

## RaciRoleBinding (new)

| Field | Type | Rule |
|---|---|---|
| actor_type | Literal["human","llm","service"] | required |
| actor_id | str | required, min_length 1 (R2; narrower than core's `str \| None`) |

## ResolvedRaciSnapshot (new; mirrors core `ResolvedRACIBinding`)

| Field | Type | Rule |
|---|---|---|
| step_id | str | required |
| responsible | RaciRoleBinding | required |
| accountable | RaciRoleBinding | required; mirror core's accountable/provenance rules exactly as read from `schema.py` |
| consulted | list[RaciRoleBinding] | default [] |
| informed | list[RaciRoleBinding] | default [] |
| source | Literal["inferred","explicit"] | required |
| inferred_rule | str \| None | default None |
| override_reason | str \| None | default None |

## SignificanceEvaluatedPayload (new; event type `SignificanceEvaluated`)

| Field | Type | Rule |
|---|---|---|
| run_id, decision_id, step_id | str | min_length 1 |
| significance_score | dict[str, Any] | required, open object |
| hard_trigger_classes | tuple[str, ...] | default () |
| effective_band | Literal["low","medium","high"] | required |
| actor | RaciRoleBinding | required |

## DecisionTimeoutExpiredPayload (new; event type `DecisionTimeoutExpired`)

| Field | Type | Rule |
|---|---|---|
| run_id, decision_id, step_id | str | min_length 1 |
| significance_score | dict[str, Any] | required |
| effective_band | Literal["medium","high"] | never "low" |
| timeout_configured_seconds | int | > 0 |
| escalation_targets | tuple[RaciRoleBinding, ...] | default () |
| raci_snapshot | ResolvedRaciSnapshot | required (D6) |
| actor | RaciRoleBinding | required |

## Constants

`SIGNIFICANCE_EVALUATED = "SignificanceEvaluated"`, `DECISION_TIMEOUT_EXPIRED = "DecisionTimeoutExpired"`, `DECISION_SIGNIFICANCE_EVENT_TYPES: FrozenSet[str]` of both. Not added to `MISSION_NEXT_EVENT_TYPES`, `STRICT_EVENT_TYPES` or the support matrix.

## Unchanged (ratchet)

`RuntimeActorIdentity`, every lifecycle / project_lifecycle / mission_audit / mission_next model, `StatusTransitionPayload`, every existing schema and fixture, the producer-row schemas.

## Fixture families (new)

- `decision_significance/{valid,invalid}`: per type at least one valid; invalid incl. `actor_id: null`, `actor_id: ""`, missing `actor_id`, band out of range, timeout `<= 0`, extra key.
- `lifecycle/invalid`: one or more per FR-008 type; `lifecycle/valid`: WPStatusChanged per uncovered lane + summary-on-rework/done; `lifecycle/replay`: stream + `-output` golden.
