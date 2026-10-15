---
title: "Reducer: a departure from approved outranks a clock-ahead approval (10.5.1)"
description: "A rejection or done recorded after an approval is no longer lost when the approval row carries a later wall-clock timestamp (spec-kitty-events#101)."
doc_status: active
updated: 2026-10-15
type: Changelog
kind: Changelog
sidebar:
  order: -202610151
tags: [diary, reducer, status-events, clock-skew]
related:
  - changelog/2026-10-13-diary-equal-at-approved-done-settles-approval
---

# 2026-10-15 — A departure from `approved` outranks a clock-ahead approval

**Package `10.5.0` → `10.5.1`** · spec-kitty-events#101 · PR #102 · root fix for
spec-kitty/spec-kitty#4941 · fix to the `status.events.jsonl` reducer
(`spec_kitty_events.diary.reduce` / `reduce_parsed`, the event-ordering step).

A reviewer sent a work package back, or merged it to `done`, after approving
it, and the board still showed `approved`. This happened whenever the approval row
carried a later wall-clock `at` than the departure that followed it. The usual
causes are a reviewer whose clock runs ahead, or a teammate's approval arriving
through `git pull` and the status-log union merge driver, which also orders rows by
`at`. Public API and serialized shapes are unchanged, and the on-wire schema
version stays `3.0.0`.

## What changed

- **A real `approved -> …` never sorts before the approval it left.** The
  reducer first binds each non-seed departure to the latest approval of the same
  work package stamped at or before it, with no reordering. Only a departure that
  has no such approval is moved: its **sort** `at` is lifted to the earliest
  later approval (`_approved_departure_sort_at`). So a clock-ahead approval in an
  earlier review cycle cannot capture a later cycle's departure.
- **Rework that follows a moved departure moves with it.** Events of the same
  work package stamped below the lifted value now sort after the departure, in
  their original order (`_carry_lifted_sort_at`), instead of being dropped as stale.
- Stored `at` values are never rewritten. Only the sort order changes.

## Why it mattered

The reducer ordered events by `(at, event_id)`. A departure stamped earlier than
its own approval sorted first. At that point the work package was still
`in_review`, so arm (ii) `from_lane` continuity dropped the departure as
causally stale, and the approval won. Because a work package can only leave a lane
it has entered, the departure must come after its approval, whatever the clocks
say. This extends the equal-timestamp tie-break of
[10.4.6](./2026-10-13-diary-equal-at-approved-done-settles-approval.md) to the
later-timestamp case.

## Behavioral contract

| # | Event | `at` |
|---|---|---|
| 1–4 | `planned -> claimed -> in_progress -> for_review -> in_review` | 12:00–12:15 |
| 5 | `in_review -> approved` (reviewer clock ahead) | 12:40 |
| 6 | `approved -> in_progress` (rejection) | 12:30 |
| 7 | `in_progress -> for_review` (rework) | 12:32 |

| Package | Resolved lane |
|---|---|
| `10.5.0` | `approved` |
| `10.5.1` | `for_review` |

The regression tests are in
`tests/test_status_diary_reducer.py::TestApprovedDepartureFollowsItsApproval`.
They cover the rejection and `done` departures, a two-cycle rework whose first
approval clock ran ahead, and the un-skewed control.

## Scope and non-regression

- Logs with no clock skew reduce exactly as before. The #87 equal-`at` tie-break,
  the #69/#81/#6061 rulings, seedless logs and the Lamport
  `reduce_status_events` path are unchanged.
- Reconstruction seeds are never paired or lifted. They may be carried behind a
  lifted departure, which changes only their sort position.

## Downstream

A consumer pinned to `spec-kitty-events>=10.5,<11` picks up `10.5.1` on its next
resolve. Raise the floor to `>=10.5.1` to require this fix. Snapshots already
materialized from a skewed log keep the wrong lane until they are re-materialized.

See the repo-root [`CHANGELOG.md`](https://github.com/spec-kitty/spec-kitty-events/blob/main/CHANGELOG.md) and
[`COMPATIBILITY.md`](https://github.com/spec-kitty/spec-kitty-events/blob/main/COMPATIBILITY.md) for the versioned record.
