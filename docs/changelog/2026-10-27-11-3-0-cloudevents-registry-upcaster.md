---
title: "11.3.0: CloudEvents profiles, the full registry, the legacy-row upcaster and a Java binding"
description: "Every strict type registered, read and write envelope profiles, a read-time upcaster for legacy rows with a 363-case corpus, and a Java 25 binding on the same corpus."
doc_status: active
updated: 2026-10-27
type: Changelog
kind: Changelog
sidebar:
  order: -202610271
tags: [cloudevents, registry, upcaster, java, conformance]
---

# 2026-10-27 — 11.3.0: CloudEvents profiles, the full registry, the legacy-row upcaster and a Java binding

**Package `11.2.1` → `11.3.0` (minor)** · spec-kitty-events#92 (step 2 of
spec-kitty/spec-kitty#6117) · envelope `schema_version` unchanged (`3.0.0`). Nothing that was
accepted before is rejected now, and nothing that was rejected is accepted. The diary reducer
is unchanged.

## What changed

- **ADR 0001 is Accepted, with every question decided.** Writers switch on the 4.0 GA critical
  path. A later major retires `3.0.0`. The further choices are confirmed. The `dataschema` base
  is `urn:ai.spec-kitty:schema:`. The reducer switch to Lamport ordering comes after GA.
- **The full type registry.** All 26 strict types now have a `transport` mapping. Eight of them
  are new types with data schemas pinned to their payload models.
- **Read and write envelope profiles.** The read profile is what a reader meets: no clock
  required, legacy UUID ids, and the 3.0.0 extensions.
- **`spec_kitty_events.cloudevents`.** `upcast` and `upcast_line` read legacy rows as
  CloudEvents and never rewrite them. Alongside them are `canonical_bytes`,
  `validate_cloudevent` and `diary_row`.
- **The upcast corpus.** 363 legacy lines, each with an expected outcome, named issue codes
  and a golden CloudEvent. The packaged gate runs it, including the equal reduced state of
  every diary replay.
- **A Java 25 binding** (`java/`). It reproduces every golden byte for byte. `make test-java`
  runs it as part of `make test-full`.

## For consumers

- **Core:** refresh the lock to `11.3.0` within `<12`. No code change is needed until core
  adopts the shared reader.
- **Producers writing CloudEvents rows:** switch `dataschema` to the URN.
- **Everyone else:** no action needed. Details are in
  [`COMPATIBILITY.md`](https://github.com/spec-kitty/spec-kitty-events/blob/main/COMPATIBILITY.md).
