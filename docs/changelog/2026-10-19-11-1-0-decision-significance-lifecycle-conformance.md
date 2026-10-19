---
title: "11.1.0: decision-significance events and lifecycle conformance breadth"
description: "Minor release: core's significance and timeout payloads get typed contracts with a RACI actor model, and every lifecycle event type gets invalid fixtures and a replay golden."
doc_status: active
updated: 2026-10-19
type: Changelog
kind: Changelog
sidebar:
  order: -202610191
tags: [release, decision-significance, raci, lifecycle, conformance]
---

# 2026-10-19 — 11.1.0: core's decision-significance events get a contract

**Package `11.0.0` → `11.1.0` (minor)** · spec-kitty-events#119, #62 · envelope
`schema_version` unchanged (`3.0.0`).

Before this release, core persisted `SignificanceEvaluated` rows that the package
could check only as a producer row, with no typed payload. Core's timeout payload
had no contract at all. And most lifecycle event types had no invalid fixture, so
nothing proved the package refuses a malformed lifecycle event.

## What changed

- **Decision significance.**
  - New module `spec_kitty_events.decision_significance`.
  - `SignificanceEvaluated` mirrors the event core persists.
  - `DecisionTimeoutExpired` mirrors core's `TimeoutExpiredPayload` model. Core has no producer for it yet, and the name is this package's.
  - Both events use a new `RaciRoleBinding` actor and a typed `ResolvedRaciSnapshot`; `RuntimeActorIdentity` is unchanged.
- **Two deliberate narrowings.**
  - Every `RaciRoleBinding` needs a non-empty `actor_id`. That includes the nested bindings in `raci_snapshot` and `escalation_targets`. Core allows `None`.
  - `raci_snapshot` must be a resolved RACI binding. Core types it as a plain dict.
- **Lifecycle conformance.**
  - Invalid fixtures for every lifecycle event type that lacked one.
  - A replay golden for `reduce_lifecycle_events`.
  - Valid `WPStatusChanged` fixtures for every lane, including the #62 summary cases on rework and `done`.
- **Docs.** [Lifecycle reconciliation](/architecture/lifecycle-reconciliation/) maps the four lifecycle modules against what core uses today.

## For consumers

You need nothing unless you adopt the new types. To adopt them, widen the pin to `>=11.1.0,<12` and follow "Adopting 11.1.0" in `COMPATIBILITY.md`. Core follow-ups (set `actor_id` on every binding, import the package models) are listed in the reconciliation page.
