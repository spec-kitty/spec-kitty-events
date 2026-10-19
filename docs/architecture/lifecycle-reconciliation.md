---
title: Lifecycle reconciliation
description: Record of how the lifecycle, project_lifecycle, mission_audit and mission_next modules line up with what spec-kitty core consumes, with citations and open follow-ups.
doc_status: active
updated: 2026-10-19
type: Architecture
kind: Reference
---

# Lifecycle reconciliation

This page records the 11.1.0 reconciliation of four payload modules against how
spec-kitty core (the `spec-kitty` repository, cited below as spec-kitty `path:line`)
consumes them. It closes
[#119](https://github.com/spec-kitty/spec-kitty-events/issues/119). Core lines were
re-verified against the sibling checkout on 2026-10-19; they will drift, so treat them as
a snapshot.

**Current core pin: `spec-kitty-events>=11.0.0,<12`** (spec-kitty `pyproject.toml:80`).
The `>=10.5.1,<11` figure in #119 and in the completed mission
`mission-lifecycle-reconciliation-01M574ET` is stale. That mission's artifacts stay
immutable; this page is the correction.

## Summary

| Module | Core consumer | Result |
|---|---|---|
| `lifecycle` | spec-kitty `src/specify_cli/core/mission_payload.py:30` | Fields agree. New invalid fixtures and a replay golden added. |
| `project_lifecycle` | spec-kitty `src/specify_cli/status/lifecycle_events.py:661,830,966` | Consumed by import. Core hand-copies the type constants (follow-up). |
| `mission_audit` | none found | Recorded; module unchanged. |
| `mission_next` | spec-kitty `src/runtime/next/_internal_runtime/engine.py:26`, `src/runtime/next/_internal_runtime/events.py:17`, `src/runtime/next/_internal_runtime/schema.py:15` | `RuntimeActorIdentity` unchanged. New sibling `decision_significance` module. |

## `lifecycle`

Core imports `MissionCreatedPayload` from `spec_kitty_events.lifecycle`
(spec-kitty `src/specify_cli/core/mission_payload.py:30`) and shapes its payload through
it. The field set agrees with the model, so no model changed.

As of 11.1.0 (the fixture manifest is the source for these counts), what 11.1.0 adds, all test and fixture coverage with no change to an accept boundary:

- Invalid conformance fixtures for 18 lifecycle event types that had none (the Mission*
  types, `PhaseEntered`, `ReviewRollback`, the Specify/Plan/Tasks pairs, `WPAssigned`,
  `HistoryAdded`, `ErrorLogged`, `DependencyResolved`). Each violates a rule the current
  model already enforces.
- A replay stream and golden under the `lifecycle` fixture category, folded by
  `reduce_lifecycle_events` through the packaged conformance runner.
- Valid `WPStatusChanged` fixtures for the lanes no fixture covered (genesis, in_review,
  approved, blocked, canceled) and `summary` coverage on rework and `done` transitions
  ([#62](https://github.com/spec-kitty/spec-kitty-events/issues/62)).

An unknown event type in a replay stream is counted in `event_count` but changes no state;
the reducer skips it (state ignores it; the count includes it).

## `project_lifecycle`

Core imports the payload models at spec-kitty `src/specify_cli/status/lifecycle_events.py:661`
(`ProjectInitializedPayload`), `:830` (the Specify/Plan started and completed payloads) and `:966` (`WPCreatedPayload`). Core
also hand-copies event-type string constants at `lifecycle_events.py:77-93`
(for example `MISSION_CREATED`, `FOLLOW_UP_RECORDED`). The strings currently match; the
copy is a drift risk, filed as a follow-up below. The module is unchanged.

## `mission_audit`

No consumer of `spec_kitty_events.mission_audit` was found in spec-kitty `src`. The module
is unchanged and stays public; absence of a consumer is recorded, not treated as a defect.

## `mission_next`

Core consumes `RuntimeActorIdentity` and the mission-next payloads in
spec-kitty `src/runtime/next/_internal_runtime/engine.py:26`, `events.py:17` and
`schema.py:15`. `RuntimeActorIdentity` is unchanged (operator ruling R1).

Core persists one decision event the package did not model: `SignificanceEvaluated`
(spec-kitty `engine.py:494`, payload at `significance.py:485`). For the timeout, core has only
the payload model `TimeoutExpiredPayload` (`significance.py:506`) and an emitter-protocol
method (`events.py:96`) whose default `NullEmitter` implementation is a no-op (`events.py:178`);
nothing in core writes a timeout event to the log, and the string `DecisionTimeoutExpired`
appears nowhere in core. 11.1.0 therefore mirrors `SignificanceEvaluated` as core persists it,
and mirrors core's `TimeoutExpiredPayload` model ahead of any core producer, under a name
chosen by this package: `DecisionTimeoutExpired`. Both live in the new `decision_significance`
module beside `mission_next`, with a new RACI actor model (`RaciRoleBinding`,
`ResolvedRaciSnapshot`) rather than `RuntimeActorIdentity`.

### Deliberate difference (R2)

Core's `RACIRoleBinding` (spec-kitty `schema.py:79-84`) has `actor_id: str | None = None`,
and the producer row `run.significance.evaluated.v1` in this package agrees with core
(nullable, not required). The new package model requires `actor_id` as a non-empty string,
so it refuses a null or empty `actor_id` that core and the producer row still allow. This is
a conscious narrowing; the producer row is unchanged because changing it would move an
accept boundary. A test compares the two shapes and pins `actor_id` as the single exception.
The rule applies to nested bindings too (`raci_snapshot`, `escalation_targets`), not only the top-level `actor`.
Core must always set `actor_id` before it can adopt these models. Since `11.2.0` the package
exports the sentinels core uses for that (operator ruling 2026-10-19, #136): an inferred human
binding with no id gets `HUMAN_IN_CHARGE_ACTOR_ID` (`"human-in-charge"`, the built-in
`human-in-charge` profile), and any other binding with no id (`llm` or `service`) gets
`UNKNOWN_ACTOR_ID` (`"UNKNOWN"`). The rule itself is unchanged: both are ordinary non-empty strings.

A second documented narrowing is D6: core types `raci_snapshot` as `dict[str, Any]`, while the package types it as
`ResolvedRaciSnapshot`, so a dict that is not a resolved RACI binding is refused.

## Follow-ups to file

1. core: import the lifecycle event-type constants from `spec_kitty_events` instead of the
   hand-copied list (`lifecycle_events.py:77-93`).
2. core: derive `CANONICAL_LANES` (`status_lanes.py:8`) from `spec_kitty_events.status.Lane`.
3. events: bind `strict._REQUIRED_SCHEMA_VERSION` (`strict.py:144`) to
   `lifecycle.SCHEMA_VERSION` (`lifecycle.py:24`).
4. core: build the migration dry-run `WPStatusChanged`
   (`migration/mission_state.py:1320-1340`) through `StatusTransitionPayload`.
5. core: adopt the package's `SignificanceEvaluated`, `DecisionTimeoutExpired` and RACI
   models, and always set `actor_id` (R2).
6. events: valid, invalid and replay fixtures for payload modules with no consumer (merge,
   gates, build_lifecycle, topology and others) and for `decision_moment`.
7. core: persist the timeout event as `DecisionTimeoutExpired` using the package model.
8. events: converge the producer-row `run.significance.evaluated.v1` `actor_id` rule with the package model once core always sets `actor_id`.
