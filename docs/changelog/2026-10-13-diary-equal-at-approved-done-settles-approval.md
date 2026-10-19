---
title: "Reducer: an equal-timestamp approved -> done settles its approval (10.4.6)"
description: "A done WP whose approved -> done shares its approval's timestamp, but sorts first by event_id, no longer reopens when a migration rollback seed follows (spec-kitty-events#87)."
doc_status: active
updated: 2026-10-14T00:00:00Z
type: Changelog
kind: Changelog
sidebar:
  order: -202610132
authors:
  - human:ivan
tags: [diary, reducer, status-events, migration]
related:
  - changelog/2026-10-13-diary-rollback-seed-lane-authority
  - changelog/2026-10-12-diary-concurrent-approval-after-backfilled-rejection
generated:
  by: agent/doc-kitty-write
  at: "2026-10-14T07:53:09Z"
---

# 2026-10-13 — An equal-timestamp `approved -> done` settles its approval

**Package `10.4.5` → `10.4.6`** · spec-kitty-events#87 · PR #91 · `v10.4.6` is
also the first tag carrying the untagged `10.4.5`
([rollback-seed lane authority](./2026-10-13-diary-rollback-seed-lane-authority.md)) · fix to the
`status.events.jsonl` reducer (`spec_kitty_events.diary.reduce` /
`reduce_parsed`, the event-ordering step).

A work package that was approved and merged could travel backwards in a
migrated mission: `done` in the real log, `in_progress` with verdict
`changes_requested` in the reduced snapshot. Public API and serialized shapes
are byte-identical; this is a reduction-ordering fix only, so the on-wire schema
version stays `3.0.0`.

## What changed

- **A real `approved -> …` now sorts after its same-timestamp approval.** The
  reducer used to order events strictly by `(at, event_id)`. It now orders them
  by `(at, follows-a-same-at-approval, event_id)`: a non-seed event that leaves
  `approved` and shares its `at` with a non-seed `-> approved` of the same work
  package sorts after that approval, whatever their event IDs say. The new
  helper is `_order_transitions` in `diary.py`; every other event keeps its
  `(at, event_id)` position.

That tie-break carries the whole fix. Once the `done` sorts after its approval,
arm (ii) continuity applies it, so the work package's final setter is the `done`
event rather than a bare approval, and the later rollback-shaped seed has no
bare-sibling approval left to contest. The bare-sibling test (`_is_bare_sibling`)
is unchanged and plays no part here: it still returns `False` for a non-approval
setter like the `done`.

## Why it mattered

Event IDs are ULIDs, and two events written in the same instant carry no
promise about which ULID is smaller. When a WP's real `in_review -> approved` and
`approved -> done` shared a timestamp and the `done` drew the smaller id, the
reducer:

1. met the `done` first, while the WP was still `in_review`, and dropped it under
   arm (ii) `from_lane` continuity (it claims to leave `approved`, but the WP was
   not there yet);
2. applied the approval, leaving the WP at `approved`;
3. judged that approval **bare**, because no real event left `approved` after it
   in `(at, event_id)` order;
4. let a later rollback-shaped `migration:` seed (`in_review -> in_progress`,
   verdict `changes_requested`) win the bare-sibling contest of
   spec-kitty-events#81 and take the lane.

An approval that a real `done` followed is a settled decision, not a bare
sibling (spec-kitty#6061). The rule was right; the ordering hid the `done` from
it.

## Behavioral contract

| # | Event | `at` | `event_id` |
|---|---|---|---|
| 1–4 | `planned -> claimed -> in_progress -> for_review -> in_review` | 10:00–10:03 | …0001–…0004 |
| 5 | `in_review -> approved` (real reviewer) | 10:04 | …0006 |
| 6 | `approved -> done` (real merge) | 10:04 | …0005 |
| 7 | `in_review -> in_progress`, `changes_requested` (`migration:` rollback seed) | 11:00 | …0090 |

| Package | Resolved lane | Resolved verdict |
|---|---|---|
| `10.4.5` | `in_progress` | `changes_requested` |
| `10.4.6` | `done` | `approved` |

The regression test is
`tests/test_status_diary_reducer.py::TestSeedLaneAuthorityArriving::test_equal_at_done_sorting_before_its_approval_settles_it`
(`@pytest.mark.regression`). It was red through `reduce()` before the fix.

## Scope and non-regression

- Ordering changes only when a same-WP, same-`at` real approval exists.
  Reconstruction seeds never move.
- The `10.4.5` (spec-kitty#6061), `10.4.4` (spec-kitty#6058) and `10.4.3`
  bare-sibling (spec-kitty-events#81) outcomes are unchanged, as are
  real-against-real arbitration, `force=True`, seedless logs and the Lamport
  `reduce_status_events` path.
- The snapshot shape and the `3.0.0` envelope schema are unchanged.

## Downstream

A consumer pinned to `spec-kitty-events>=10.4.4,<11` picks up `10.4.6` on its
next resolve; raise the floor to `>=10.4.6` to require this fix and the
`10.4.5` one it carries. Snapshots
already materialized from migrated missions whose approval and `done` share a
timestamp keep the wrong lane until they are re-materialized from the log.

See the repo-root [`CHANGELOG.md`](https://github.com/spec-kitty/spec-kitty-events/blob/main/CHANGELOG.md) and
[`COMPATIBILITY.md`](https://github.com/spec-kitty/spec-kitty-events/blob/main/COMPATIBILITY.md) for the versioned record and the
compatibility rationale.
