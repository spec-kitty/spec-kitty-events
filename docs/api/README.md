---
title: API & Contracts
description: The consumable contract surface of spec-kitty-events — the envelope, the Python package, the lane vocabulary, the versioning policy and the strict profile.
doc_status: active
updated: 2026-10-18T00:00:00Z
type: API
kind: Hub
okf_version: "0.2"
---

# API & Contracts

This section is the surface a **consumer** depends on: how to emit a well-formed envelope,
validate a payload, reduce a stream, gate a durable record, and know what a version number
promises. The event families the payloads belong to are in [Event families](/events/); this
section is the machinery around them.

The normative contract lives in code (`src/spec_kitty_events/`) and in the repository-root
[`contracts/`](https://github.com/spec-kitty/spec-kitty-events/blob/main/contracts/README.md)
policy files. These pages explain and link to them; where a page summarises a durable
contract, that root file governs.

## Pages

- [The envelope](/api/envelope/) — `Event`, the single top-level envelope every family rides
  in, and each of its context fields.
- [The Python package](/api/python-package/) — the import surface: emitting, validating,
  reducing, and loading JSON Schemas and conformance fixtures.
- [Lane vocabulary](/api/lane-vocabulary/) — the canonical `Lane` enum, the display subset,
  and why a lane change is always a major bump.
- [Versioning & compatibility](/api/versioning-and-compatibility/) — the two version numbers,
  what makes a change major, and the artifacts a bump must ship.
- [Ledger write contract](/api/ledger-write-contract/) — `persistence.append_event`, the
  fail-closed, fixture-pinned append to a local JSONL ledger, unclocked envelopes, and
  the removed `LOCAL_ONLY_EVENT_TYPES`.
- [The strict profile](/api/strict-profile/) — `validate_strict_envelope`, the fail-closed
  gate for the durable journal and TeamSpace ingress.
- [Consumer contract: mission dossier events (v2.4.0)](/api/consumer-contract-dossier-v2.4.0/)
  — the archived 2.4.0 dossier contract and the current review-rejection backward-transition
  family.

## The three operations a consumer uses

| Operation | Entry point | When |
|---|---|---|
| Validate a payload | `spec_kitty_events.conformance.validate_event(payload, event_type)` | checking one event's payload against its contract |
| Gate a full envelope | `spec_kitty_events.strict.validate_strict_envelope(envelope)` | admitting a row to the durable journal / ingress |
| Reduce a stream | a family reducer, e.g. `reduce_status_events(events)` | folding an ordered stream into state |

See [The Python package](/api/python-package/) for worked examples of each.
