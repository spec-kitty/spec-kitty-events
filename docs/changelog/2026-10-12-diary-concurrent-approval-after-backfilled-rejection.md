---
title: "Reducer: a backfilled rejection is never overwritten by a bare concurrent approval (10.4.3)"
description: "A bare in_review -> approved approval no longer overwrites a backfilled rejection it is causally concurrent with (spec-kitty#4941)."
doc_status: active
updated: 2026-10-14T00:00:00Z
type: Changelog
kind: Changelog
sidebar:
  order: -202610121
tags: [diary, reducer, status-events, review-verdict]
---

# 2026-10-12 — A backfilled rejection is never overwritten by a bare concurrent approval

**Package `10.4.2` → `10.4.3`** · spec-kitty#4941 · fix to the
`status.events.jsonl` reducer (`spec_kitty_events.diary.reduce` /
`reduce_parsed`, arm (ii) of `_should_apply_event`).

This closes the **approval after rejection** sibling of the `10.4.0` (#69)
**rejection after approval** mirror. Public API and serialized shapes are
byte-identical; this is a reduction-decision fix only, so the on-wire schema
version stays `3.0.0`.

## What changed

- **A reconstruction seed that is itself a committed rejection now keeps the
  rejection's authority.** `10.4.1`'s seed exemption (#5967 follow-up) — a
  `migration:` seed as current setter must not reject a work package's real
  later lifecycle — was unconditional, so it also lifted arm (ii)'s
  `from_lane`-continuity drop when the seed was a rollback/rejection. The
  reducer now narrows the exemption: it yields to the real lifecycle only for a
  seed that is a *forward restatement*, never for a seed that is a
  rollback/rejection.

## Why it mattered

A backfilled rejection cycle leaves the WP in `in_progress` while the rejection
itself branched off `in_review`. A real approval `from_lane=in_review ->
to_lane=approved` that carries **no rework** between it and the rejection
branches off that *same* `in_review` state — it is causally concurrent with the
rejection, not a rework following it. Under the unconditional seed exemption the
approval was applied anyway, resolving the WP to verdict `approved` when it must
stay `changes_requested`. The rule is causal (Lamport / `from_lane`-contiguity),
not wall-clock: re-timestamping the approval to be the newest event does not
change the outcome.

## Behavioral contract

Backfilled rejection + bare `in_review -> approved` (no rework) → resolved
verdict `changes_requested`.

## Scope and non-regression

- The `10.4.1` #5967 cancel/rework cases are unaffected: their seeds are not
  rollbacks, and their real setters (a cancel, a rework claim) are not seeds.
- The rollback and `force=True` exemptions are untouched.

## Downstream

The spec-kitty consumer xfails this exact contract pending the fix
(`tests/migration/test_verdict_provenance_backfill.py::TestReducerOrdering::test_bare_approval_after_rejection_resolves_changes_requested`,
`xfail(reason=#4941, strict=True)`). Once `10.4.3` is released, the consumer
bumps its pin from `spec-kitty-events>=10.4.2,<11` to `>=10.4.3,<11`; the strict
xfail then flips to XPASS (CI red), the signal to remove the marker.

See the repo-root [`CHANGELOG.md`](https://github.com/spec-kitty/spec-kitty-events/blob/main/CHANGELOG.md) and
[`COMPATIBILITY.md`](https://github.com/spec-kitty/spec-kitty-events/blob/main/COMPATIBILITY.md) for the versioned record and the
compatibility rationale.
