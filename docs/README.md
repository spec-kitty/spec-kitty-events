---
title: spec-kitty-events documentation
description: Root index of the spec-kitty-events docs tree, the contract package for Spec Kitty event envelopes, payload schemas, conformance fixtures and replay.
doc_status: active
updated: 2026-10-14
kind: Hub
okf_version: "0.2"
---

# spec-kitty-events documentation

`spec-kitty-events` owns the Spec Kitty event envelope, the payload schemas, the
status-diary reducer, the packaged conformance fixtures and the compatibility
policy. This tree follows the DocKitty convention: each section's `README.md` is
its index and carries frontmatter.

## Sections

| Section | What it holds |
|---|---|
| [Architecture](./architecture/README.md) | Current and proposed design of the package's contracts. |
| [Decision Records](./adr/README.md) | Architectural decisions, numbered `NNNN-title.md`. |
| [Changelog](./changelog/README.md) | Dated pages for notable changes, newest first. |

## Other references

- [Consumer contract: mission dossier events (v2.4.0)](./consumer-contract-dossier-v2.4.0.md):
  the archived 2.4.0 dossier contract and the current review-rejection
  backward-transition family.
- The repo-root [`README.md`](https://github.com/spec-kitty/spec-kitty-events/blob/main/README.md), [`CHANGELOG.md`](https://github.com/spec-kitty/spec-kitty-events/blob/main/CHANGELOG.md)
  and [`COMPATIBILITY.md`](https://github.com/spec-kitty/spec-kitty-events/blob/main/COMPATIBILITY.md) hold the package overview, the
  full release history and the public compatibility policy.
