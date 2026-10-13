---
title: Architectural Decision Records
description: Index of architectural decision records for the spec-kitty-events contract package, with the naming convention and status vocabulary.
doc_status: active
updated: 2026-10-13
type: ADR
kind: Hub
---

# Architectural Decision Records

Architectural decisions for `spec-kitty-events`, the package that owns the Spec Kitty
event envelope, payload schemas, conformance fixtures and compatibility policy. This
folder was opened by the CloudEvents envelope ADR below; the package had no ADR folder
before it.

## Naming

ADRs are flat and sequentially numbered: `NNNN-descriptive-title-with-dashes.md`
(`0001`, `0002`, ...), with no date in the filename. Each ADR carries its status in a
`**Status:**` line in the body and in the table below. This `README.md` is the section
index.

## Status vocabulary

- `Proposed`: written, direction not yet confirmed.
- `Accepted`: the decision is current policy.
- `Superseded`: a newer ADR replaced it; keep the file for history.
- `Deprecated`: being retired; take no new work from it.

## Records

| ID | Title | Status | Date |
|----|-------|--------|------|
| [0001](./0001-cloudevents-envelope.md) | ADR-0001: Adopt CloudEvents 1.0 as the event envelope | Proposed | 2026-10-13 |

Design detail for the records above lives under [`docs/architecture/`](../architecture/).
