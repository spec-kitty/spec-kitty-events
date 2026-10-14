---
title: "Reducer: a rollback seed never takes a lane against the real lifecycle (10.4.5)"
description: "A migration seed restating a rejection applies only when it continues the lane in force, so it can no longer reopen a done WP or undo a later repair (spec-kitty#6061)."
doc_status: active
updated: 2026-10-14
type: Changelog
kind: Changelog
sidebar:
  order: -202610131
authors:
  - ivan@spec-kitty.ai
tags: [diary, reducer, status-events, migration]
related:
  - changelog/2026-10-12-diary-losing-seed-folds-payload
  - changelog/2026-10-13-diary-equal-at-approved-done-settles-approval
generated:
  by: agent/doc-kitty-write
  at: "2026-10-14T08:20:00Z"
---

# 2026-10-13 — A rollback seed never takes a lane against the real lifecycle

**Package `10.4.4` → `10.4.5`** (never tagged; first released in `v10.4.6`) · spec-kitty#6061 · fix to the
`status.events.jsonl` reducer (`spec_kitty_events.diary.reduce` /
`reduce_parsed`, both arbitration arms).

A rollback-shaped `migration:` seed restates a rejection, for example
`in_review -> in_progress` with a `changes_requested` verdict. It used to take
the lane through the rollback exemption whatever the work package had done
since, so it could reopen a `done` WP or override a later compatibility repair
back to `planned`. The snapshot shape and the `3.0.0` envelope schema are
unchanged; only lane, actor and verdict values on migrated logs differ.

## What changed

- **R1, arriving.** A rollback seed applies only when its `from_lane` continues
  the lane in force. The exceptions are its bare sibling approval (a
  non-rollback `-> approved` from the same lane that no real event left
  `approved` after, spec-kitty-events#81), an earlier rollback seed in force,
  and `force`. A rollback seed that loses is a continuity loser, so its payload
  still folds (`10.4.4`), also at an equal `at`.
- **R2, setter.** An applied rollback seed yields the lane to a later real
  event, as a forward seed does, except to its bare sibling approval. A later
  real claim or `done` no longer leaves the WP stranded at the seed's lane.
- **R3.** A seed never uses the forward-seed-setter exemption.

Equal-`at` outcomes follow from R1 and R2 and no longer depend on which
`event_id` sorts first.

## One verdict change

A rejection seed that loses, followed by an approval seed that also loses, now
records `approved`: the later cycle's verdict folds. `10.4.4` recorded
`changes_requested` because the rejection seed had taken the lane and owned the
verdict. This pair is synthetic; no mission in the spec-kitty corpus has it.

## Scope and non-regression

- Real-against-real arbitration, `force` and logs with no seeds are unchanged.
- The `10.4.3` bare-sibling ruling and the `10.4.4` seed-owns-its-verdict ruling
  are unchanged.
- One equal-`at` case remained: a real `approved -> done` that shares its
  approval's `at` but sorts first by `event_id`. That was closed in `10.4.6`
  ([equal-timestamp `approved -> done`](./2026-10-13-diary-equal-at-approved-done-settles-approval.md)).

## Downstream

New packaged golden replay `status-diary-replay-seed-lane-authority`
(`conformance/fixtures/status_diary/replay/status_diary_seed_lane_authority.jsonl`).
`10.4.5` was never tagged, so take this fix from `10.4.6` (raise the floor to
`spec-kitty-events>=10.4.6`). Consumers with non-terminal migrated missions may
need to re-materialize their snapshots to pick up the corrected lanes.

See the repo-root [`CHANGELOG.md`](https://github.com/spec-kitty/spec-kitty-events/blob/main/CHANGELOG.md) and
[`COMPATIBILITY.md`](https://github.com/spec-kitty/spec-kitty-events/blob/main/COMPATIBILITY.md) for the versioned record and the
compatibility rationale.
