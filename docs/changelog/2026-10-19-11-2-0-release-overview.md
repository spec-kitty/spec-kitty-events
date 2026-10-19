---
title: "11.2.0 release overview: everything new since v11.0.0"
description: "v11.2.0 is a cumulative, fully additive minor release over v11.0.0: decision-significance contracts, lifecycle conformance breadth, a timeout producer row, and RACI sentinels."
doc_status: active
updated: 2026-10-19T00:00:00Z
type: Changelog
kind: Changelog
sidebar:
  order: -202610193
tags: [release, decision-significance, raci, lifecycle, conformance, producer-contract]
---

# 2026-10-19 — 11.2.0 release overview: everything new since v11.0.0

**v11.2.0 is a cumulative, fully additive minor release over v11.0.0.** It is the
first tag since v11.0.0, so it ships everything landed in the unreleased 11.1.0 and
in 11.2.0 as one release — there is no separate v11.1.0 tag. The envelope
`schema_version` is unchanged (`3.0.0`), and nothing already in the contract changes
its accept or reject boundary. **A pure consumer who does not adopt the new types
needs to do nothing.**

Everything below is new surface you can choose to adopt, grouped by what it gives you.

## What's new

### Decision-significance contracts (from 11.1.0)

A new `spec_kitty_events.decision_significance` module gives core's decision-significance
payloads a typed contract:

- `SignificanceEvaluated` mirrors the event core persists.
- `DecisionTimeoutExpired` mirrors core's `TimeoutExpiredPayload`. Core has no producer
  for it yet; the name is this package's.
- A `RaciRoleBinding` actor model and a typed `ResolvedRaciSnapshot`.

Two narrowings versus core are deliberate: every binding's `actor_id` is required and
non-empty (core allows `None`), and `raci_snapshot` is typed as a resolved snapshot (core
types it as a plain dict).

### Lifecycle conformance breadth (from 11.1.0)

The conformance corpus now proves the package refuses malformed lifecycle events across
the board:

- Invalid fixtures for every lifecycle event type that lacked one.
- A replay golden for `reduce_lifecycle_events`.
- Valid `WPStatusChanged` fixtures for every lane, including the #62 `summary` cases on
  rework and `done`.
- A reconciliation record, [lifecycle reconciliation](/architecture/lifecycle-reconciliation/),
  mapping the four lifecycle modules against what core uses today.

### Decision-timeout producer row (11.2.0)

11.1.0 gave `DecisionTimeoutExpired` a typed payload but no producer row. 11.2.0 adds the
row `run.decision.timeoutexpired.v1` (type `ai.spec-kitty.run.decision.timeoutexpired.v1`,
legacy discriminator `DecisionTimeoutExpired`), following the `run.significance.evaluated.v1`
conventions. It is cross-anchored to the package payload by
`tests/test_decision_timeout_producer_row.py`, so a transcription slip between the row and
the payload fails a gate. The registry now holds 28 row kinds.

### RACI actor-id sentinels (11.2.0)

Two constants are now exported from `spec_kitty_events` and
`spec_kitty_events.decision_significance`, giving producers one shared value for a RACI
binding they resolved with no id:

- `HUMAN_IN_CHARGE_ACTOR_ID = "human-in-charge"` — an inferred human binding with no id.
- `UNKNOWN_ACTOR_ID = "UNKNOWN"` — any other unattributable binding (llm or service).

This is no validation change: `actor_id` is still any non-empty string.

## Adoption guidance

A pure consumer needs nothing: widen no pin, change no code. To adopt the new types or to
produce RACI bindings, widen the pin to `>=11.2.0,<12` and follow the "Adopting 11.1.0"
and "Mapping rule for producers" sections in
[`COMPATIBILITY.md`](https://github.com/spec-kitty/spec-kitty-events/blob/main/COMPATIBILITY.md).

## Known deliberate divergences / follow-ups

The producer row's `actor_id` is nullable while the package model requires it non-empty
(operator ruling R2). Converging the two is tracked in spec-kitty-events#131, which waits
on core always setting `actor_id`; core adopts this in spec-kitty/spec-kitty#6445.

## Detail pages

- [11.1.0: decision-significance events and lifecycle conformance breadth](./2026-10-19-11-1-0-decision-significance-lifecycle-conformance.md)
- [11.2.0: producer row for the decision timeout](./2026-10-19-11-2-0-decision-timeout-producer-row.md)
- [11.2.0: RACI actor-id sentinels human-in-charge and UNKNOWN](./2026-10-19-11-2-0-raci-actor-id-sentinels.md)

The full semantic-versioned history lives in
[`CHANGELOG.md`](https://github.com/spec-kitty/spec-kitty-events/blob/main/CHANGELOG.md);
the compatibility policy and per-version rationale live in
[`COMPATIBILITY.md`](https://github.com/spec-kitty/spec-kitty-events/blob/main/COMPATIBILITY.md).
</content>
</invoke>
