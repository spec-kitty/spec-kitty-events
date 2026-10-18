---
title: Durability and the relay
description: The journal-versus-volatile split in spec-kitty-events — which families are durable, which are broadcast, and the Zeitgeist attrs codec for volatile moments.
doc_status: active
updated: 2026-10-18
type: Architecture
kind: Explanation
related:
  - api/strict-profile
  - events/runtime-observation
  - context/system-context
---

# Durability and the relay

Not every event is kept forever. The contract splits event families along a **durability**
axis, and the split decides which transport an event travels on and whether it is reduced
into durable state.

## Three durability classes

| Class | What it means | Transport |
|---|---|---|
| **Journal (durable)** | admitted to the durable record, fail-closed | durable journal, gated by the [strict profile](/api/strict-profile/) |
| **Volatile (broadcast)** | a moment worth relaying now, not kept | Zeitgeist relay, bounded `attrs` wire shape |
| **Durable live-work** | durable observations of live work | SaaS durable journal / relay fan-out |

- **Journal** families are the ones `STRICT_EVENT_TYPES` admits — mission lifecycle
  transitions, work-package creation, the project/artifact phases. They carry the durable
  record and are gated fail-closed.
- **Volatile** families are presence and in-the-moment signals: the six `HarnessObservation`
  kinds, `CoordinationMessage`, decision-point broadcasts, run-execution steps, and the
  catalog/phase moments. `zeitgeist_attrs.VOLATILE_EVENT_TYPES` is the authoritative set.
- **Durable live-work** is the 25-kind `WorkObservation` family, which deliberately rides its
  own durable path and **never** the lossy volatile codec, so a durable work record is never
  silently truncated. See [runtime observation](/events/runtime-observation/).

## The Zeitgeist attrs codec

A volatile moment is projected onto the relay's bounded `attrs` wire shape by
`spec_kitty_events.zeitgeist_attrs` — the single owner of the mapping between the volatile
payload vocabulary and the relay's attributes:

- `to_zeitgeist_attrs(payload, envelope)` encodes a volatile payload to bounded `attrs`;
  `from_zeitgeist_attrs(...)` decodes it back to a typed `VolatileMoment`.
- The projection is **bounded**: `ZEITGEIST_ATTRS_MAX_BYTES`, `ZEITGEIST_ATTRS_MAX_KEYS` and
  `ZEITGEIST_ATTR_KEY_MAX_CHARS` cap it, and an overflow is a typed error
  (`ZeitgeistAttrsOverflowError`), never a silent drop of data that mattered.
- It is a **projection, not a reduction**: the relay carries a lossy, retention-window view,
  so nothing durable is reconstructed from it.

## Why the split matters to a consumer

A consumer reading the **relay** gets a fast, bounded, now-view and must not treat it as the
record. A consumer reading the **journal** gets the durable, fail-closed-gated record. A
consumer reading **local history** (`status.events.jsonl`) gets the user's own permissively
read data. The same `event_type` can appear on more than one transport; the durability class,
not the type name alone, tells a consumer what guarantees it has. See
[system context](/context/system-context/) for the transports and
[the strict profile](/api/strict-profile/) for the journal gate.
