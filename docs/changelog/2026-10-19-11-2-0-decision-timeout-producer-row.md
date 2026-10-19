---
title: "11.2.0: producer row for the decision timeout"
description: "Minor release: the decision-timeout payload gets a producer-row contract, cross-anchored to the package payload so a transcription slip fails a gate."
doc_status: active
updated: 2026-10-19T00:00:00Z
type: Changelog
kind: Changelog
sidebar:
  order: -202610192
tags: [release, producer-contract, decision-significance]
---

# 2026-10-19 — 11.2.0: the decision timeout gets a producer row

**Package `11.1.0` → `11.2.0` (minor)** · spec-kitty-events#134 · envelope
`schema_version` unchanged (`3.0.0`).

11.1.0 gave `DecisionTimeoutExpired` a typed payload, but no producer row: a producer had
no row schema to write the timeout line against, and nothing tied the payload to what core
writes. 11.2.0 adds the row `run.decision.timeoutexpired.v1`
(`ai.spec-kitty.run.decision.timeoutexpired.v1`, legacy discriminator
`DecisionTimeoutExpired`), following the `run.significance.evaluated.v1` conventions.

## What it accepts

The row's `data` mirrors core's `TimeoutExpiredPayload`: `run_id`, `decision_id` and
`step_id` (non-empty), `significance_score` (object), `effective_band` (`medium` or `high`),
`timeout_configured_seconds` (integer above zero), `raci_snapshot` (object), `actor`, and an
optional `escalation_targets` list of RACI bindings. Unknown members are refused.

## Cross-anchored to the payload

`tests/test_decision_timeout_producer_row.py` fails when the row and the package payload
disagree on property names, required members, enums or bounds. Two differences are
deliberate and pinned:

- `actor_id` may be null or absent in the row; the package requires it non-empty (ruling R2).
  Converging it is #131, which waits on core always setting `actor_id`
  (spec-kitty/spec-kitty#6445).
- `raci_snapshot` is any object in the row; the package validates it as a
  `ResolvedRaciSnapshot`.

## Naming

The issue proposed `run.decision.timeout_expired.v1`. The registry's name grammar allows only
`[a-z0-9]` segments, as in `capturefailed` and `selfapproved`, so the row is
`run.decision.timeoutexpired.v1`.
