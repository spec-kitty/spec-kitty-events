---
title: "11.2.0: RACI actor-id sentinels"
description: "Minor release: the package exports human-in-charge and UNKNOWN, the actor_id values a producer uses for a RACI binding it resolved with no id."
doc_status: active
updated: 2026-10-19T00:00:00Z
type: Changelog
kind: Changelog
sidebar:
  order: -202610192
tags: [release, decision-significance, raci]
---

# 2026-10-19 — 11.2.0: RACI actor-id sentinels

**Package `11.1.0` → `11.2.0` (minor)** · spec-kitty-events#136 · envelope
`schema_version` unchanged (`3.0.0`).

`11.1.0` made `actor_id` required on every `RaciRoleBinding` (R2), but core
resolves some bindings with no id. Without a shared value, every producer would
invent its own placeholder.

## What changed

- **Two constants** in `spec_kitty_events.decision_significance`, re-exported from the package:
  - `HUMAN_IN_CHARGE_ACTOR_ID = "human-in-charge"`: an inferred human binding with no id, the human operator (the built-in `human-in-charge` profile).
  - `UNKNOWN_ACTOR_ID = "UNKNOWN"`: any other binding with no id (`llm` or `service`) that cannot be attributed to the human operator.
- **Two valid fixtures** that use the sentinels, including nested `raci_snapshot` and `escalation_targets` bindings.
- **No validation change.** `actor_id` is still any non-empty string.

## For consumers

You need nothing unless you produce RACI bindings. Producers widen the pin to `>=11.2.0,<12`
and map a missing `actor_id` with the constants; see "Mapping rule for producers" in
`COMPATIBILITY.md`. Core adopts this in spec-kitty#6445.
