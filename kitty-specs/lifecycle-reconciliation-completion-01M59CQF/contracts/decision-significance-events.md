# Contract: decision significance events (11.1.0)

Authoritative artifacts once implemented: generated schemas `src/spec_kitty_events/schemas/{raci_role_binding,resolved_raci_snapshot,significance_evaluated_payload,decision_timeout_expired_payload}.schema.json` and the `decision_significance` conformance fixtures. Field tables: [data-model.md](../data-model.md).

## Accept / refuse

| Input | `validate_event(payload, "SignificanceEvaluated")` |
|---|---|
| core-shaped payload, actor `{actor_type: "service", actor_id: "runtime"}` | accept |
| actor `actor_id: null` / `""` / absent | refuse (R2) |
| `effective_band: "critical"` | refuse |
| unknown extra key | refuse |

| Input | `validate_event(payload, "DecisionTimeoutExpired")` |
|---|---|
| core-shaped payload, band medium/high, timeout > 0, snapshot actors with ids | accept |
| `effective_band: "low"` | refuse |
| `timeout_configured_seconds: 0` | refuse |
| escalation target or snapshot actor with null/empty `actor_id` | refuse |

## Producer-row relation

`run.significance.evaluated.v1` producer row is unchanged; it agrees with this contract on every property except `actor_id` (row: nullable, optional; contract: required, non-empty). Consumers emitting a null `actor_id` are accepted by the producer row and refused by `validate_event` until core sets it (core follow-up).
