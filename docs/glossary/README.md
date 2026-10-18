---
title: Glossary
description: The vocabulary of the Spec Kitty event contract — envelope, aggregate, Lamport clock, lane, reducer, journal and volatile, the strict profile and the relay.
doc_status: active
updated: 2026-10-18
kind: Glossary
---

# Glossary

The terms the event contract uses, with a pointer to where each is defined in depth.

### Envelope (`Event`)
The single top-level structure every event rides in: stable context fields carrying an opaque
`payload`. Immutable. See [the envelope](/api/envelope/).

### Payload
The event-specific data inside the envelope, opaque to the envelope and validated against the
family's own typed model. See [Event families](/events/).

### Event type
The string (for example `WPStatusChanged`) that selects which payload contract applies. Each
family exports its event-type constants and an `*_EVENT_TYPES` frozenset.

### Aggregate (`aggregate_id`)
The identity of the entity an event modifies, for example `mission/WP01` or `repo/<id>`.

### Build id / Node id
`build_id` is the canonical checkout/worktree that produced an event — the only canonical
build identity, build-scoped not team-scoped. `node_id` is the causal emitter, used for
ordering and tie-breaking.

### Lamport clock
The logical clock (`lamport_clock`) that, with `node_id`, owns event ordering. Wall-clock
`timestamp` is never used to order. See [ordering and reduction](/architecture/ordering-and-reduction/).

### Correlation id / Causation id
`correlation_id` ties related events together; `causation_id` names the parent event
(`None` for roots).

### Schema version
`schema_version`, the on-wire compatibility signal, held at `3.0.0` across package majors.
Distinct from the package version — see [versioning & compatibility](/api/versioning-and-compatibility/).

### Data tier
`data_tier` (0–4): the progressive data-sharing tier, from local (0) to telemetry (4).

### Lane
A canonical work-package state (`planned`, `claimed`, `in_progress`, …). One vocabulary, owned
by the `Lane` enum. See [lane vocabulary](/api/lane-vocabulary/).

### Reducer
A pure function that folds an ordered event stream into a frozen state snapshot. All reducers
share one filter → sort → dedup → fold → freeze pipeline. See
[ordering and reduction](/architecture/ordering-and-reduction/).

### Reduced state
The immutable snapshot a reducer produces, e.g. `ReducedStatus`, `ReducedMissionState`.

### Anomaly
A typed record of something a reducer could not reconcile (out-of-order transition, authority
violation, late event). Recorded on the reduced state rather than thrown.

### Status diary
The CLI's append-only `status.events.jsonl` log, folded by the standalone
`spec_kitty_events.diary` reducer. See [work & status](/events/work-and-status/).

### Journal (durable) / Volatile (broadcast)
The durability split. Journal events are admitted to the durable record fail-closed; volatile
events are relayed as in-the-moment signals and not kept. See
[durability and the relay](/architecture/durability-and-relay/).

### Durable live-work
The `WorkObservation` family — durable observations of live work that ride their own durable
path, never the lossy volatile codec. See [runtime observation](/events/runtime-observation/).

### Strict profile
`validate_strict_envelope` (`journal/v1`), the opt-in fail-closed gate for the durable journal
and ingress. See [the strict profile](/api/strict-profile/).

### Zeitgeist relay
The volatile transport carrying bounded `attrs`-shaped moments, encoded by
`zeitgeist_attrs`. A lossy, retention-window projection, never a durable record.

### Total order
The lexicographic `(h, c, t, i, s)` key over CloudEvents-form rows that makes reduction
deterministic. See [ordering and reduction](/architecture/ordering-and-reduction/).

### Conformance corpus
The packaged valid/invalid payloads, replay streams and golden reducer outputs — the
authoritative compatibility surface every consumer validates against.

### Forbidden keys
Legacy key names and aggregate prefixes the strict profile rejects anywhere in an envelope,
by a recursive walk. See [the strict profile](/api/strict-profile/).

### Producer / Consumer
The producer is the one sanctioned emitter (the Spec Kitty CLI / runtime). A consumer is any
system that imports the contract to validate, order and reduce events. See [Context](/context/).
