---
title: "Schema: producer envelope (read profile)"
description: "Attribute rules for a CloudEvents row as a reader may meet it, including the upcast form of a legacy row (ADR 0001, read profile): the id may be a normalized legacy UUID"
doc_status: active
updated: 2026-11-03T00:00:00Z
type: Architecture
kind: Schematic
schematic_path: schemas/envelope.read.schema.json
---

# Schema: producer envelope (read profile)

**CloudEvents profile** (non-normative until 12.0.0) — the READ profile: attribute rules for CloudEvents rows as a reader meets them, including upcast legacy rows. The normative envelope is [the 3.0.0 event envelope](/api/envelope/event-envelope/); the write-side sibling is the [write profile](/architecture/producer-row-schemas/producer-envelope-write/).

Generated from `src/spec_kitty_events/producer_contract/envelope.read.schema.json` by `scripts/schema_pages.py`; never hand-edit (C-001).

## Family

Listed on the [Producer row schemas and the total order](/architecture/producer-row-schemas/) hub.
