---
title: The strict profile
description: validate_strict_envelope, the opt-in fail-closed envelope gate for the durable journal and TeamSpace ingress, and exactly what it enforces.
doc_status: active
updated: 2026-10-18
type: API
kind: Reference
related:
  - api/envelope
  - api/python-package
  - architecture/durability-and-relay
---

# The strict profile

`validate_strict_envelope` is the opt-in, deterministic, **fail-closed** gate for the durable
journal and TeamSpace ingress. It is layered over the envelope and payload contracts and is
intentionally separate from `validate_event`: the default validator checks a payload against
its type, while the strict profile admits or rejects a whole envelope for the durable record.

**Source:** [`src/spec_kitty_events/strict.py`](https://github.com/spec-kitty/spec-kitty-events/blob/main/src/spec_kitty_events/strict.py).
Profile id: `STRICT_PROFILE_ID = "journal/v1"`.

```python
from spec_kitty_events.strict import validate_strict_envelope

errors = validate_strict_envelope(envelope)  # a tuple of ValidationError
if errors:
    reject(errors)  # fail closed — do not admit the row
```

## What it enforces

Given an envelope record, the profile checks (in order):

1. the record is a mapping with a known `event_type`;
2. every member of `STRICT_ENVELOPE_KEYS` is present — an explicit `null` counts, so a
   defaulted field such as `schema_version` or `data_tier` must be written out;
3. no key outside `STRICT_ENVELOPE_KEYS` appears at the envelope level;
4. no forbidden key appears anywhere, by a recursive walk: `FORBIDDEN_LEGACY_KEYS` always,
   plus `FORBIDDEN_OBSERVATION_KEYS` when the event is a `HarnessObservation` and
   `FORBIDDEN_WORK_KEYS` when it is a `WorkObservation`;
5. no forbidden legacy aggregate name (`FORBIDDEN_LEGACY_AGGREGATE_NAMES` — `feature`,
   `feature_catalog`) in `aggregate_id`;
6. `schema_version` is present, a string, and exactly `"3.0.0"`.

`STRICT_ENVELOPE_KEYS` is the full 14-key envelope set (every field on
[the envelope](/api/envelope/), including the defaulted `schema_version` and `data_tier`).
Because an explicit `null` satisfies presence, a strict-profile envelope carries all 14 keys
even where a value is absent.

## Which event types are journaled

`STRICT_EVENT_TYPES` is the frozenset of event types the journal profile admits. The package
also ships `SUPPORT_MATRIX` — a per-type, per-schema-version support table — and
`support_matrix_digest()`, a SHA-256 over its canonical serialization, so a consumer can pin
the exact matrix it validated against. Which families are durable (journaled) versus volatile
(relay-only) is covered in [durability and the relay](/architecture/durability-and-relay/).

## Why fail-closed, and where

The journal and ingress admit only canonical envelopes, so a malformed or legacy-shaped row
is rejected rather than silently stored. This is distinct from local history, which the CLI
reads permissively so users never lose their own data — see
[versioning & compatibility](/api/versioning-and-compatibility/).
