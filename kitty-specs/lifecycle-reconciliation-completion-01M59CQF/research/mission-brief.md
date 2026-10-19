# Mission brief: finish mission-lifecycle reconciliation (#119), fold in #62, mirror the RACI decision events

## Issues
- Closes spec-kitty/spec-kitty-events#119 (4.x rework scope B, epic #117). PR #124 delivered part of it but did not close it.
- Closes #62 (WPStatusChanged summary lane-breadth coverage). Test-only, same surface.
- Refs #117, #118, #120 (template), #114 (two Lane enums, not fixed here), #92, core #6185, #6438, #6411, #6117.

## Operator rulings (2026-10-19)
- R1: Mirror both `SignificanceEvaluated` and `DecisionTimeoutExpired` into the package, with a NEW RACI actor model. `RuntimeActorIdentity` stays unchanged.
- R2: The new RACI actor's `actor_id` is REQUIRED and non-empty, which is narrower than core's `RACIRoleBinding` (`actor_id: str | None`). A core follow-up must make core always set it.
- Release class: minor (11.1.0). New event types move no existing accept boundary (COMPATIBILITY.md 6.1.0 precedent). The envelope `schema_version` is unchanged.

## Already on main (from #124): do not redo
- `MissionReopened`, `FollowUpRecorded` and `ReviewerSelfApproval` already have schemas, fixtures and validator entries.

## Gaps this mission closes
1. A written reconciliation of `lifecycle`, `project_lifecycle`, `mission_audit` and `mission_next` against core's current use:
   - `mission_next`: `src/runtime/next/_internal_runtime/{engine,events,schema}.py`;
   - `MissionCreatedPayload`: `specify_cli/core/mission_payload.py:30`;
   - `project_lifecycle`: `specify_cli/status/lifecycle_events.py:661,830,966`, plus the hand-copied constants at lines 77-93;
   - `mission_audit` has no core consumer. Record that and keep the module unchanged.
2. Invalid conformance fixtures for each lifecycle event type that lacks one: the Mission* types, PhaseEntered, ReviewRollback, the Specify/Plan/Tasks pairs, WPAssigned, HistoryAdded, ErrorLogged and DependencyResolved.
3. A replay stream (valid sequence) that folds through `reduce_lifecycle_events` (`lifecycle.py:651`), with the expected snapshot.
4. #62: `WPStatusChanged` with `summary` on a rework/rejection lane and on `to_lane=done`, in `tests/unit/test_zeitgeist_attrs.py` (~lines 641 and 662) plus conformance fixtures. Add valid fixtures for the lanes no fixture covers yet (genesis, in_review, approved, blocked, canceled).
5. RACI events (R1, R2):
   - a `RaciRoleBinding` model (`actor_type: Literal[human|llm|service]`, `actor_id: str` min 1);
   - a resolved-RACI snapshot model mirroring core's `ResolvedRACIBinding` (`schema.py:109-120`);
   - `SignificanceEvaluatedPayload` mirroring core `significance.py:485-501`;
   - `DecisionTimeoutExpiredPayload` mirroring `significance.py:506-522`;
   - type constants, registry and validator dispatch, generated JSON Schemas, and valid/invalid fixtures. The invalid fixtures include `actor_id: null` and an empty `actor_id`.
   - Reuse the shape pinned in `producer_contract/data/run.significance.evaluated.v1.schema.json` and its row fixtures, and check that the two agree.
   - The decision-event module should sit beside `mission_next`.
6. CHANGELOG and COMPATIBILITY entries for 11.1.0, plus the version bump. Correct the stale "core pins >=10.5.1,<11" claims; core is on `>=11.0.0,<12`.

## Invariants (binding)
- Additive only:
  - no existing schema, fixture or model changes its accept/reject boundary;
  - a diff against main shows additions only, apart from the version, docs and dispatch registration.
- No import of `spec-kitty`, `-saas`, `-tracker` or `-runtime`. The package mirrors core's shapes; it never depends on core.
- Fail-closed: `validate_event` refuses invalid payloads, and reducers skip unknown types.
- JSON schemas are generated from the models through `schemas/generate.py`, never hand-edited. `tests/integration/test_schema_drift.py` must stay green.
- The packaged conformance gate stays green: `uv run --frozen pytest --pyargs spec_kitty_events.conformance` (baseline 727 passed, about 8s).
- mypy --strict, ruff check and ruff format must all be clean.
- Charter: the conformance gate runs on every PR, and there is one adversarial-squad review per PR.

## File and link (out of scope; file as issues at closeout)
- core: import the lifecycle event-type constants from spec_kitty_events instead of the hand-copied list (`lifecycle_events.py:77-93`).
- core: derive `CANONICAL_LANES` (`status_lanes.py:8`) from `spec_kitty_events.status.Lane`.
- events: bind `strict._REQUIRED_SCHEMA_VERSION` (`strict.py:144`) to `lifecycle.SCHEMA_VERSION` (`lifecycle.py:24`).
- core: build the migration dry-run `WPStatusChanged` (`migration/mission_state.py:1320-1340`) through `StatusTransitionPayload`.
- core: adopt the package's SignificanceEvaluated, DecisionTimeoutExpired and RACI models, and always set `actor_id` (R2).
- events: valid/invalid/replay fixtures for the payload modules with no consumer (merge, gates, build_lifecycle, topology, …) and for `decision_moment`.

## Escalate (pause for the operator)
- Any gap that needs a new field on an existing model, or a changed accept boundary.
- A test-expectation edit, as opposed to new coverage.
- Any disagreement between core's emit shape and the producer-row schema.
