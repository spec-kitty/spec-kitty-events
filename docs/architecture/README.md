---
title: Architecture
description: How the spec-kitty-events contract is shaped — the envelope, the total order and reduction pipeline, the journal-versus-volatile split, and the read and write services.
doc_status: active
updated: 2026-10-29T00:00:00Z
type: Architecture
kind: Hub
okf_version: "0.2"
---

# Architecture

How the event contract is shaped. These pages describe the design that the payload families
in [Event families](/events/) and the surface in [API & Contracts](/api/) sit on. Decisions
these documents support live in the [decision records](/adr/).

## The map

- [Overview](/architecture/overview/) — the shape of the package and the producer → contract
  → consumer flow. **Read this first.**
- [Ordering and reduction](/architecture/ordering-and-reduction/) — the Lamport clock, the
  total order over rows, and the deterministic reducer pipeline every family shares.
- [Durability and the relay](/architecture/durability-and-relay/) — the journal-versus-volatile
  split, which families are durable, and the Zeitgeist `attrs` codec for volatile moments.
- [Services](/architecture/services/) — the Mission Status Read API and the future write
  service: the read-write split, the event-log lock and append, and the write-service
  protocol. The code moves here from core at cutover.

## Proposed and in-flight

- [CloudEvents envelope design](/architecture/cloudevents-envelope/) — adopting CloudEvents
  1.0 as the event-log envelope, supporting [ADR-0001](/adr/0001-cloudevents-envelope/).
  The registry, envelope profiles, upcaster and Java binding shipped in `11.3.0`; the
  `12.0.0` cutover is next.
- [Producer row schemas and the total order](/architecture/producer-row-schemas/) — the
  per-row-kind data schemas, the producer registry and the total-order comparator.
- [Lifecycle reconciliation](/architecture/lifecycle-reconciliation/) — how the lifecycle,
  project_lifecycle, mission_audit and mission_next modules line up with core's use.
