---
title: "Reducer: a reconstruction seed that loses the lane still folds its payload (10.4.4)"
description: "A migration seed dropped by from_lane continuity now folds the claim attribution and verdict no real event wrote, so migrated missions keep them (spec-kitty#6058)."
doc_status: active
updated: 2026-10-14
type: Changelog
kind: Changelog
authors:
  - ivan@spec-kitty.ai
tags: [diary, reducer, status-events, migration]
related:
  - changelog/2026-10-12-diary-concurrent-approval-after-backfilled-rejection
  - changelog/2026-10-13-diary-rollback-seed-lane-authority
generated:
  by: agent/doc-kitty-write
  at: "2026-10-14T08:20:00Z"
---

# 2026-10-12 — A reconstruction seed that loses the lane still folds its payload

**Package `10.4.3` → `10.4.4`** · spec-kitty#6058 · fix to the
`status.events.jsonl` reducer (`spec_kitty_events.diary.reduce` /
`reduce_parsed`, the arm (ii) continuity drop).

A corpus migration mints `migration:` reconstruction seeds
(`backfill_runtime_state`, `verdict_provenance_backfill`) to preserve a work
package's historical agent, shell pid, process-start baseline and review
verdict. When a real event already held the lane, the seed failed `from_lane`
continuity and was dropped whole, so the data it was minted to keep vanished
from the snapshot and the mission-state audit reported it as drift. Lanes and
the snapshot shape are unchanged; only slot values on migrated logs differ.

## What changed

- **A losing seed folds the slots no real event wrote.** `agent`, `shell_pid`
  and `shell_pid_created_at` fold with the same acceptance as an applied
  `planned -> claimed` (pids when not `None`, `agent` when non-empty), through
  one shared helper. `review_result` folds when no real event has written one;
  a real verdict, or the explicit `None` of a verdict-less `in_review` exit,
  counts as written.
- **Real events always win their slot**, whichever side of the seed they sort.
  A real write of `shell_pid` voids a seed-supplied `shell_pid_created_at`, so
  a live pid is never paired with a legacy baseline.
- **Same-`event_id` seeds resolve by content** (the canonical-minimum row), not
  by file order. Real events keep first-occurrence deduplication.

## What a losing seed never does

- It never changes `lane`, `actor`, `last_event_id`, `last_transition_at` or
  `force_count`.
- A seed that loses a same-timestamp contest to a rollback folds nothing.

## Scope and non-regression

- Real events in a log with no seeds reduce exactly as in `10.4.2`.
- An applied seed behaves as in `10.4.2`, with one exception: a real `shell_pid`
  write voids a seed-supplied `shell_pid_created_at` whether the seed was
  applied or folded.
- A rollback-shaped seed such as `in_review -> in_progress` still applied
  through the rollback exemption. That was closed in `10.4.5`
  ([rollback-seed lane authority](./2026-10-13-diary-rollback-seed-lane-authority.md)).

## Downstream

New packaged golden replay `status-diary-replay-seed-payload-fold`
(`conformance/fixtures/status_diary/replay/status_diary_seed_payload_fold.jsonl`).
Consumers re-materializing migrated missions pick up the preserved attribution
and verdicts.

See the repo-root [`CHANGELOG.md`](../../CHANGELOG.md) and
[`COMPATIBILITY.md`](../../COMPATIBILITY.md) for the versioned record and the
compatibility rationale.
