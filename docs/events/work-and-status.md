---
title: Work and Status Events
description: The work-package lane-transition family and the append-only status diary reducer that together track where every work package sits on the kanban board.
doc_status: active
updated: 2026-10-18
type: Integration
kind: Reference
related:
  - events/mission-lifecycle
  - events/review-audit-decisions
---

# Work and Status Events

Work-and-status events record where each work package sits on the mission's
kanban board and how it got there. They are emitted by the Spec Kitty CLI as a
work package is claimed, worked, reviewed and finished, and are consumed by the
status snapshot (`status.json`), the mission dashboard, and the lifecycle
reducer.

Two modules cover this concern. `status.py` defines the single
`WPStatusChanged` event, the canonical lane vocabulary, transition validation
and a reference reducer. `diary.py` is the deterministic reducer over the
append-only `status.events.jsonl` diary — the on-disk log the CLI actually
writes and merges.

## Lane transitions — `status.py`

One event type carries every work-package lane change.

| Event type | Payload model | Meaning |
|---|---|---|
| `WPStatusChanged` | `StatusTransitionPayload` | A work package moves from one lane to another (`from_lane` → `to_lane`), with actor, optional force/reason and done-evidence. |

The canonical lane vocabulary is the `Lane` enum: `genesis`, `planned`,
`claimed`, `in_progress`, `for_review`, `in_review`, `approved`, `done`,
`blocked`, `canceled`. Two narrower sync projections exist for consumers that
need the coarser board — `SyncLaneV1` (`planned`, `doing`, `for_review`,
`done`) and `SyncLaneV2` (adds `approved`), reached through
`canonical_to_sync_v1` / `canonical_to_sync_v2`. `ExecutionMode` records
whether the transition happened in a `worktree` or `direct_repo`.
`StatusTransitionPayload` accepts a `doing` alias that resolves to
`in_progress`, and its `actor` may be a plain string or a structured
`{role, profile, tool, model}` dict (use `actor_label` for the canonical string
form). See the [lane vocabulary](/api/lane-vocabulary/) for the full
transition semantics.

**Validation.** `validate_transition(payload)` returns a
`TransitionValidationResult` describing whether a `from_lane` → `to_lane` move
is permitted, including the review-rejection backward transitions.

**Reducer.** `reduce_status_events` filters to `WPStatusChanged`, deduplicates,
applies rollback-aware ordering, and folds into a frozen `ReducedStatus` — a
`wp_states` map of `wp_id` → `WPState` (current lane, last event id, last
transition time, optional `DoneEvidence`), a list of `TransitionAnomaly`, an
`event_count` and `last_processed_event_id`. This is the reducer the mission
[lifecycle reducer](/events/mission-lifecycle/) delegates WP events to.

**Durability.** Journaled and reduced into durable work-package state.
`WPStatusChanged` is also projected onto the volatile Zeitgeist relay
(`VOLATILE_EVENT_TYPES` in `zeitgeist_attrs.py`) as a live lane moment.

## The status diary — `diary.py`

The diary reducer is the on-disk counterpart to `status.py`: it reads the
append-only `status.events.jsonl` log, one JSON object per line, and
materializes the kanban state the CLI writes as `status.json`. It is the module
with the most subtle ordering and merge rules, settling several precedence
corner cases for concurrent and rolled-back rows.

A diary file mixes two row shapes plus pass-through rows:

| Row shape | Discriminator | Parsed as | Meaning |
|---|---|---|---|
| Lane transition | no `kind`, no top-level `event_type` | `StatusEvent` | A work-package lane change (the diary form of `WPStatusChanged`). |
| Annotation | `kind: "annotation"` (`ANNOTATION_KIND`) | `InnerStateChanged` | A runtime-slot delta (`WPInnerStateDelta`) that does not move the lane. |
| Non-lane row | top-level `event_type`, or a retrospective lifecycle type | passed through | Protocol rows (e.g. `RetrospectiveCaptured`) carried unchanged, never folded. |

`parse_diary(rows)` partitions decoded rows into an `EventStream`
(`transitions` + `annotations`), raising `DiaryError` on a malformed row or an
unknown `kind`. `reduce(events)` is the entry point most consumers want; it
decodes, parses and folds in one call. `reduce_parsed(transitions, annotations)`
is the lower-level fold for callers that already hold parsed records (for
example a reader that resolves a legacy `mission_id` against `meta.json` first).

The fold is kind-partitioned, not a single timestamp-interleaved pass:
transitions are deduplicated by `event_id`, sorted by `(at, event_id)`, folded
with rollback-aware precedence, and annotations are applied as runtime-slot
deltas. The result is a `State` — `mission_slug`, `materialized_at`,
`event_count`, `last_event_id`, a `work_packages` map and a `summary` of lane
counts — which serializes via `to_dict()` to the exact `status.json` shape
(minus the CLI-side `retrospective` attachment).

**Durability.** The diary is the durable, append-only source of truth for
work-package state; it is never rewritten, and rows for event types the reducer
does not know are passed through unchanged.

## See also

- [Lane vocabulary](/api/lane-vocabulary/) — the full lane set and permitted
  transitions.
- [Mission lifecycle events](/events/mission-lifecycle/) — the reducer that
  delegates WP reduction here.
- [Review, audit and decision events](/events/review-audit-decisions/) — the
  review events that drive backward lane transitions.
- [The Python package](/api/python-package/) — how to import, validate and
  reduce these events.
- [Ordering and reduction](/architecture/ordering-and-reduction/) — the ordering
  and dedup rules the reducers apply.
