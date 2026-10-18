---
title: The envelope
description: Reference for Event, the single top-level envelope every spec-kitty-events family rides in, and the meaning of each of its context fields.
doc_status: active
updated: 2026-10-18
type: API
kind: Reference
related:
  - architecture/cloudevents-envelope
  - architecture/ordering-and-reduction
  - api/strict-profile
---

# The envelope

`Event` is the single top-level envelope. Every event family rides in it: a stable set of
context fields identifying, ordering and scoping the event, carrying an opaque per-family
`payload`. It is an immutable (`frozen=True`) Pydantic model; the package validates the
context fields and leaves the `payload` to the family's own model.

**Source:** [`src/spec_kitty_events/models.py`](https://github.com/spec-kitty/spec-kitty-events/blob/main/src/spec_kitty_events/models.py).

## Fields

| Field | Type | Meaning |
|---|---|---|
| `event_id` | `str` | Unique id. Accepts a 26-char ULID, 32-char hex, or 36-char hyphenated UUID; normalised by `normalize_event_id` (ULID upper-cased, UUID lower-cased and hyphenated). |
| `event_type` | `str` | The event-type string, e.g. `"WPStatusChanged"`. Selects the payload contract. |
| `aggregate_id` | `str` | Identity of the entity this event modifies, e.g. `mission/WP01`. |
| `payload` | `dict[str, Any]` | Event-specific data, opaque to the envelope (default `{}`). Validated against the family's payload model. |
| `timestamp` | `datetime` | Producer-assigned wall-clock occurrence time (ISO-8601 UTC). Consumers preserve it and record receipt time separately. **Not used for ordering.** |
| `build_id` | `str` | Canonical checkout/worktree identity that produced the event. |
| `node_id` | `str` | Causal emitter identity; used for Lamport ordering and deterministic tie-breaking. |
| `lamport_clock` | `int \| None` (≥0) | Optional since 11.0.0. Lamport logical clock; owns ordering together with `node_id` when present. Required by the strict profile. |
| `causation_id` | `str \| None` | Parent event id (ULID/UUID); `None` for roots. |
| `project_uuid` | `uuid.UUID` | The project this event belongs to. |
| `project_slug` | `str \| None` | Human-readable project id. |
| `correlation_id` | `str` | Correlation id (ULID/UUID) tying related events together. |
| `schema_version` | `str` | On-wire envelope schema version, default `"3.0.0"`. The strict profile requires exactly `3.0.0`. |
| `data_tier` | `int` (0–4) | Progressive data-sharing tier (0 = local … 4 = telemetry), default `0`. |

`to_dict()` / `from_dict()` move between the model and a plain JSON-ready mapping.

## Identity and ordering

- **`build_id` is the only canonical build identity.** It is build/checkout scoped, never
  team scoped; private-versus-team routing is host-product policy and not part of the
  envelope. See [system context](/context/system-context/).
- **Unclocked envelopes (11.0.0).** `lamport_clock` may be `None` for local ledgers. They
  order before clocked envelopes by `(timestamp, event_id)`, as in ADR 0001's total order, and are never concurrent with
  another envelope. The durable journal and TeamSpace ingress reject them.
- **Ordering is by `(lamport_clock, node_id)`, not time, for clocked envelopes.** Wall-clock `timestamp` can skew
  between nodes, so it never orders a stream. For the total order over CloudEvents-form rows,
  see [ordering and reduction](/architecture/ordering-and-reduction/).

## Schema version versus package version

`schema_version` is the on-wire compatibility signal and is held at `3.0.0` across package
major versions. It is **not** the package version. The two move independently; see
[versioning & compatibility](/api/versioning-and-compatibility/).

## See also

- [CloudEvents envelope design](/architecture/cloudevents-envelope/) — the proposed
  CloudEvents 1.0 form and the read-time upcaster.
- [The strict profile](/api/strict-profile/) — the fail-closed envelope gate and its full key
  set.
