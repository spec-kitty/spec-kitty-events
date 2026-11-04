---
title: "Schema: producer envelope (write profile)"
description: "Attribute rules for a CloudEvents 1.0 structured-JSON row a Spec Kitty producer writes (ADR 0001, write profile): upper-case ULID id, lamportclock and nodeid required."
doc_status: active
updated: 2026-11-03T00:00:00Z
type: Architecture
kind: Schematic
schematic_path: schemas/envelope.schema.json
---

# Schema: producer envelope (write profile)

**CloudEvents profile** (non-normative until 12.0.0) — the WRITE profile: attribute rules for CloudEvents rows a Spec Kitty producer writes. The normative envelope is [the 3.0.0 event envelope](/api/envelope/event-envelope/); the read-side sibling is the [read profile](/architecture/producer-row-schemas/producer-envelope-read/).

Generated from `src/spec_kitty_events/producer_contract/envelope.schema.json` by `scripts/schema_pages.py`; never hand-edit (C-001).

## Family

Listed on the [Producer row schemas and the total order](/architecture/producer-row-schemas/) hub.
