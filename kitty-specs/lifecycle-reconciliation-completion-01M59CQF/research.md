# Research: Lifecycle reconciliation completion and RACI decision events

Extends the discovery notes in `research/` (grounding.md, discovery-issues.md, discovery-surfaces.md, mission-brief.md); those remain authoritative for scope.

## R-1 Core shapes to mirror (read 2026-10-19 from the sibling `spec-kitty` checkout)

- `src/runtime/next/_internal_runtime/significance.py:485-501` `SignificanceEvaluatedPayload`: `run_id`, `decision_id`, `step_id` (str, min 1); `significance_score: dict[str, Any]`; `hard_trigger_classes: tuple[str, ...] = ()`; `effective_band: Literal["low","medium","high"]`; `actor: RACIRoleBinding`; `frozen=True, extra="forbid"`.
- `significance.py:506-522` `TimeoutExpiredPayload`: same identifiers + score; `effective_band: Literal["medium","high"]`; `timeout_configured_seconds: int > 0`; `escalation_targets: tuple[RACIRoleBinding, ...] = ()`; `raci_snapshot: dict[str, Any]` (serialized `ResolvedRACIBinding`); `actor: RACIRoleBinding`.
- `schema.py:79-84` `RACIRoleBinding`: `actor_type: Literal["human","llm","service"]`, `actor_id: str | None = None`.
- `schema.py:109-120` `ResolvedRACIBinding`: `step_id`, `responsible`, `accountable`, `consulted: list`, `informed: list`, `source: Literal["inferred","explicit"]`, `inferred_rule: str | None`, `override_reason: str | None`, plus a provenance validator (line 122+) and the accountable-is-human rule on `RACIAssignment`. The implementer mirrors exactly the rules that model enforces, read from source, no more.
- Event type strings: core persists `"SignificanceEvaluated"` (`engine.py:494`); the timeout emitter is `emit_decision_timeout_expired` (`events.py:96,178`) and the operator names the type `DecisionTimeoutExpired` (R1).

Decision: mirror field-for-field, with R2 (`actor_id` required, `min_length=1`) as the single deliberate narrowing. Rationale: R1/R2. Alternatives: reuse `RuntimeActorIdentity` (rejected by R1: different shape, would couple two contracts); nullable `actor_id` (rejected by R2).

## R-2 Producer-row agreement (FR-007)

`producer_contract/data/run.significance.evaluated.v1.schema.json` matches core field-for-field, including nullable, not-required `actor_id`. Core emit shape and producer row AGREE, so the brief's escalation trigger ("disagreement between core's emit shape and the producer-row schema") is not met. The new package model is narrower on `actor_id` only, by R2.
Decision: leave the producer row unchanged (changing it would move an accept boundary, C-001); add a test that compares property names, required sets (minus `actor_id`), enums and constraints, and pins the `actor_id` exception. There is no producer row for the timeout event; none is added (out of scope).

## R-3 Count-pinned tests (escalation avoidance)

Pinned counts that a naive placement would force us to edit: `tests/unit/test_fixtures.py:112,474,485` (flat `events` 33 valid / 18 invalid), `tests/test_zeitgeist_attrs_conformance.py:155-157` (78/51/27), `tests/unit/test_strict.py:129` (`STRICT_EVENT_TYPES` 26), `tests/test_support_matrix.py:124,342` (55 rows), `tests/unit/test_lifecycle.py:154` (`MISSION_EVENT_TYPES` 9), `tests/test_producer_contract.py:88,148` (27 producer types).
Decision: new fixture categories (`decision_significance`, `lifecycle`); no membership change of any pinned set. Precedent: PR #124 omitted a support-matrix row for `ReviewerSelfApproval` and left `STRICT_EVENT_TYPES` unchanged. If an implementer finds any of these tests must change, STOP and escalate.

## R-4 Lifecycle invalid-fixture targets (FR-008)

Valid flat fixtures exist for every target type (`events/valid/`); invalid ones exist only for MissionReopened, FollowUpRecorded, ReviewerSelfApproval, ProjectInitialized, WPCreated, WPStatusChanged, GateFailed. Targets: MissionCreated, MissionStarted, MissionClosed, MissionCompleted, MissionCancelled, MissionOriginBound, PhaseEntered, ReviewRollback, SpecifyStarted, SpecifyCompleted, PlanStarted, PlanCompleted, TasksStarted, TasksCompleted, WPAssigned, HistoryAdded, ErrorLogged, DependencyResolved (18 types). Each invalid fixture violates a rule the CURRENT model already enforces (missing required field, extra key under `extra="forbid"`, out-of-enum value); no fixture may rely on a rule the model does not have.

## R-5 Replay (FR-009)

`reduce_lifecycle_events` (`lifecycle.py:651`) sorts by canonical ordering, dedups by `event_id`, skips unknown types. The replay stream covers create -> start -> phase -> rollback -> complete (exact sequence per the reducer's handled types) with one duplicate and one unknown-type event; the golden is the `ReducedMissionState` dump. Packaged runner pattern: `conformance/test_status_diary_reducer_replay.py`.

## R-6 WPStatusChanged lanes (FR-010/011)

`status.Lane` (`status.py:233`) has 10 members. Only `wp_status_changed.json` (valid) exists in the flat family. New valid fixtures for genesis (legal edges only: genesis->planned, genesis->canceled), in_review, approved, blocked, canceled. Summary evidence: existing unit tests cover `doing->in_review` and `in_review->approved` (~`tests/unit/test_zeitgeist_attrs.py:641,662`); add rework (`in_review->in_progress` and `in_review->planned`) and `->done`.

## R-7 Stale pin (FR-014)

Core `pyproject.toml:80` pins `spec-kitty-events>=11.0.0,<12`. Stale `>=10.5.1,<11` claims in maintained text: `kitty-specs/mission-lifecycle-reconciliation-01M574ET/spec.md:11` (add a dated correction note, do not rewrite history) and the #119 issue text (corrected by comment at closeout, not in this repo). Dated changelog entries and ADR 0001 describe their own date and are left alone.

## Adversarial evidence

No security-impacting dependency decision; the PR-level adversarial-squad review (C-008) covers the contract review.
