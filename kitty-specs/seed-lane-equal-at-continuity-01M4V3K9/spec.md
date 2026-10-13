# Feature Specification: Seed-lane equal-`at` continuity fix

**Feature Branch**: `kitty/mission-seed-lane-equal-at-continuity-01M4V3K9`
**Created**: 2026-10-13
**Status**: Draft
**Input**: spec-kitty/spec-kitty-events#87 — a rollback-shaped reconstruction seed can reopen a `done` work package when the real `approved -> done` shares its approval's timestamp but sorts before it by `event_id`.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A migrated `done` WP stays done even at an equal timestamp (Priority: P1)

A consumer reduces a migrated mission's `status.events.jsonl` with
`spec_kitty_events.diary.reduce()`. One work package was approved and then marked
`done` by real lifecycle events that happen to carry the **same** `at` timestamp,
and whose `approved -> done` event sorts **before** its own `in_review -> approved`
event by `event_id`. A later rollback-shaped `migration:` reconstruction seed then
restates a rejection. The consumer must still see the WP as `done`/`approved`; the
seed must not reopen it.

**Why this priority**: This is the whole defect. A WP travelling backwards in time
(`done` → `in_progress`) in a consumer's reduced snapshot is a correctness failure
in the authoritative status model; there is nothing else in scope.

**Independent Test**: Reduce the event sequence (`_SEED_PRE`; `in_review -> approved`
at `T` with the larger `event_id`; `approved -> done` at the same `T` with the
smaller `event_id`; a rollback seed at `T+1h`) and assert the WP resolves to
`("done", "approved")`. On current `main` it resolves to
`("in_progress", "changes_requested")`.

**Acceptance Scenarios**:

1. **Given** an `approved -> done` that shares its approval's `at` and sorts before it by `event_id`, **When** a later rollback seed is reduced, **Then** the WP lane is `done` and the verdict is `approved`.
2. **Given** the same approval/done pair with the normal ordering (`done.at` strictly after `approval.at`, so a larger `event_id`), **When** a later rollback seed is reduced, **Then** the WP lane is `done` and the verdict is `approved` (today's already-correct behaviour, preserved).
3. **Given** a genuinely bare approval (no real `approved -> done` after it) that branched off the same `in_review` as a rollback seed, **When** both are reduced in either order and at an equal `at`, **Then** the existing spec-kitty-events#81 bare-sibling outcome is unchanged.

### Edge Cases

- The equal-`at` pair in **both** input orders (the reducer sorts by `(at, event_id)`, so the outcome must be identical regardless of input order).
- A `done` whose `at` is strictly earlier than its approval's `at` but shares no timestamp (out of scope of this defect; must not regress).
- `force=True` events (break-glass) remain exempt from continuity, unchanged.
- Multiple rollback seeds after the `done` — still must not reopen.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: `reduce()` / `reduce_parsed()` MUST treat a real `approved -> done` event as settling its approval even when the two share an `at` and the `done` sorts before the approval by `event_id` — so the approval is not judged "bare" and a later rollback seed cannot win the bare-sibling contest against it.
- **FR-002**: The fix MUST NOT change the outcome for the normal ordering (`done.at` strictly after `approval.at`) or for a genuinely bare approval (spec-kitty-events#81), real-against-real arbitration, `force`, or seedless logs.
- **FR-003**: The equal-`at` outcome MUST be independent of `event_id` sort order (hold in both input permutations).
- **FR-004**: The snapshot shape, the `3.0.0` envelope schema, and the Lamport `reduce_status_events` path MUST be unchanged. No `spec_kitty_events` public API change.

### Key Entities

- **Rollback reconstruction seed**: a `migration:`-actor event restating a rejection (`in_review -> in_progress|planned|claimed` with a verdict); see `diary._is_rollback_seed`.
- **Bare sibling approval**: a non-rollback `-> approved` on the same lane as a seed that nothing real was built upon; see `diary._is_bare_sibling` (its `(at, event_id) > key` "built-upon" look-ahead is the seam in question).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: The #87 reduced case resolves to `("done", "approved")` after the fix (was `("in_progress", "changes_requested")`).
- **SC-002**: An issue-pinned regression test (`@pytest.mark.regression`, issue 87) is RED through `reduce()` before the fix and GREEN after.
- **SC-003**: The existing `tests/test_status_diary_reducer.py` and the packaged conformance replay suite remain fully green (no regression to spec-kitty#6061/#6058/#4941 behaviour).
- **SC-004**: `ruff` and `mypy --strict` clean on the changed files; cyclomatic complexity of any touched function stays ≤ 15.
