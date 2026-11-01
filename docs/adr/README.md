---
title: Architectural Decision Records
description: Index of architectural decision records for the spec-kitty-events contract package, with the naming convention and status vocabulary.
doc_status: active
updated: 2026-11-01T00:00:00Z
type: ADR
kind: Hub
okf_version: "0.2"
---

# Architectural Decision Records

Architectural decisions for `spec-kitty-events`, the package that owns the Spec Kitty
event envelope, payload schemas, conformance fixtures and compatibility policy. This
folder was opened by the CloudEvents envelope ADR below; the package had no ADR folder
before it.

## Naming

ADRs are flat and sequentially numbered: `NNNN-descriptive-title-with-dashes.md`
(`0001`, `0002`, ...), with no date in the filename. Each ADR carries its status in a
`**Status:**` line and its date in a `**Date:**` line in the body; the generated table
below reads both. This `README.md` is the section index.

## Status vocabulary

- `Proposed`: written, direction not yet confirmed.
- `Accepted`: the decision is current policy.
- `Superseded`: a newer ADR replaced it; keep the file for history.
- `Deprecated`: being retired; take no new work from it.

## Records

The table below is generated from each record's file name, title and `**Status:**` and
`**Date:**` lines when the docsite builds (`scripts/docs_index.py`,
[#176](https://github.com/spec-kitty/spec-kitty-events/issues/176)), so a new record needs no edit here.

<!-- BEGIN GENERATED: index -->
<!-- END GENERATED: index -->

Design detail for the records above lives under [`docs/architecture/`](../architecture/).
