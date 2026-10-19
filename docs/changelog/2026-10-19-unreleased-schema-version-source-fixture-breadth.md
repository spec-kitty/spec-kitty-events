---
title: "Unreleased: one schema-version source, gate and build invalid fixtures"
description: "Strict mode reads lifecycle.SCHEMA_VERSION instead of its own copy, and GatePassed, BuildRegistered and BuildHeartbeat gain invalid conformance fixtures."
doc_status: active
updated: 2026-10-19
type: Changelog
kind: Changelog
sidebar:
  order: -202610194
tags: [conformance, strict, fixtures]
---

# 2026-10-19 — Unreleased: one schema-version source, gate and build invalid fixtures

**Package version unchanged (`11.2.0`)** · spec-kitty-events#130, #132 · envelope
`schema_version` unchanged (`3.0.0`). No model or schema changes its accept or reject boundary.

## What changed

- **One source for the schema version (#130).** `strict._REQUIRED_SCHEMA_VERSION` is now
  bound to `lifecycle.SCHEMA_VERSION`, so a bump to one can no longer miss the other. A unit
  test pins that the two are equal.
- **Invalid fixtures for gates and build lifecycle (#132).** The packaged conformance gate
  now checks three invalid payloads that had no negative case: `GatePassed` with
  `conclusion="failure"`, `BuildRegistered` with an empty `repo_slug`, and `BuildHeartbeat`
  with `behind_remote=-1`.

## Not covered by #132

The rest of the modules listed in #132 need no new fixtures, for these reasons:

- `merge`, `topology`, `crdt` and `work_replay` define no payload model, so there is nothing to validate.
- `error_log`'s payload (`ErrorLogged`) already has fixtures under `events/` and `lifecycle/`.
- `coordination_message` and `ops_invocation` are covered by the `zeitgeist_attrs` codec fixtures. Their payloads are not registered in `validate_event`, and registering them would widen the public surface.
- `decision_moment` defines only sub-models, which the `decisionpoint` family covers.

## For consumers

No action needed.
