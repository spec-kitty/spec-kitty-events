---
title: "11.0.0: decision reopen, core-shaped glossary contracts, optional envelope clock"
description: "Major release: decisions and glossary records core writes now append and replay through the contract; the envelope clock is optional and clockless rows sort first."
doc_status: active
updated: 2026-10-18T00:00:00Z
type: Changelog
kind: Changelog
sidebar:
  order: -202610181
tags: [release, decisionpoint, glossary, envelope, ordering, breaking]
---

# 2026-10-18 — 11.0.0: decisions and glossary records replay as core writes them

**Package `10.8.0` → `11.0.0` (major)** · spec-kitty-events#120 · PR #128 · envelope
`schema_version` unchanged (`3.0.0`).

Before this release, three of core's habits sat outside the contract:
- **Reopened decisions replayed wrong.** Core reopens a deferred decision by resolving it, but the package reducer flagged that as `invalid_transition` and stayed deferred.
- **Glossary records didn't validate.** None of the eight glossary records core builds passed the package models.
- **The clock was required.** The envelope required a Lamport clock that core's local ledgers never keep.

Each one meant a ledger could not be written through `append_event` and folded back to the state core sees.

## What changed

- **Decisions.** One deferred plus one resolved now fold to RESOLVED in either order, and the reduced state reports `reopened_from_deferred`. Any other set of several Resolved events reports `unfoldable_terminal_combination`. `reduce_decision_point_records` folds ledger records, and by default it raises on malformed or unfoldable input, as core's own fold does.
- **Glossary.** The payloads take core's emitted shapes, with no aliases (COMPATIBILITY has the field map), and `StepCheckpointed` is registered. The reducer is re-keyed: terms by `term`, senses by `(scope, term_surface)`, clarifications by `conflict_id`.
- **Envelope clock.** `lamport_clock` is optional.
  - Unclocked envelopes sort **before** clocked ones, by `(timestamp, event_id)`. This is the same rule as ADR 0001's total order, and a test pins the agreement.
  - They are never concurrent with another envelope.
  - The strict journal profile and `work_replay` still require a clock.
  - `status_event_sort_key` now returns a 4-tuple.
- **Removed:** `LOCAL_ONLY_EVENT_TYPES`, `SemanticConflictEntry` and `semantic_conflict_entry.schema.json`.
- **Packaging:** the wheel now ships every conformance fixture, and `tests/test_wheel_contents.py` guards the globs.

## For consumers

Core adopts 11.0.0 through spec-kitty/spec-kitty#6411, which raises the pin to `>=11,<12`. Its children cover:
- full `Event` envelopes for decisions (#6412);
- the glossary import (#6413);
- an audit of code that assumes `lamport_clock` is always set (#6414).

Existing reducer golden outputs changed only by the added `reopened_from_deferred: false` key.
