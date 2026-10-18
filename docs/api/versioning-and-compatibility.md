---
title: Versioning & compatibility
description: The two version numbers in spec-kitty-events, what makes a change major versus additive, the artifacts a major bump must ship, and the two validity domains.
doc_status: active
updated: 2026-10-18
type: API
kind: Reference
related:
  - api/lane-vocabulary
  - api/strict-profile
---

# Versioning & compatibility

> **This page summarises. The governing contract is**
> [`contracts/versioning-and-compatibility.md`](https://github.com/spec-kitty/spec-kitty-events/blob/main/contracts/versioning-and-compatibility.md),
> and the public policy plus the single declaration of the current package version is
> [`COMPATIBILITY.md`](https://github.com/spec-kitty/spec-kitty-events/blob/main/COMPATIBILITY.md).

## Two version numbers, deliberately distinct

| Axis | Where it lives | Note |
|---|---|---|
| **Package version** | `pyproject.toml` `[project].version`, `spec_kitty_events.__version__` | signals a contract-behaviour change for some role |
| **On-wire envelope schema version** | `Event.schema_version` (default; required value enforced by the strict profile) | held at `3.0.0` across package majors |

They move independently. Conflating them is the most common misreading of this package. A
major **package** bump does **not** imply a wire-format change — producers keep emitting
`schema_version="3.0.0"`, and the [strict profile](/api/strict-profile/) rejects anything
else. `5.0.0`, `6.0.0` and `11.0.0` were major package bumps that left the envelope at
`3.0.0`. Likewise the "4.0.0" in Decision Moment V1 is the package release that introduced it
(spec-kitty-events 4.0.0); its envelope `schema_version` is `3.0.0`.

For the current release number, read `COMPATIBILITY.md`'s single declaration rather than any
figure restated elsewhere.

## Major versus additive

A change is **major** if it flips the accept/reject boundary for any role — producer or
consumer:

- widening or narrowing the [lane vocabulary](/api/lane-vocabulary/);
- changing a typed payload's required fields, or renaming a field;
- adding to the forbidden-key set, or widening where it is enforced;
- any change that makes a previously-accepted envelope rejected, or a previously-rejected one
  accepted (`11.0.0` made `lamport_clock` optional and reshaped the glossary payloads);
- removing a public export (`11.0.0` removed `LOCAL_ONLY_EVENT_TYPES`).

Adding a **new** event type with its own payload model is **additive** (a minor bump): the
new family is a new capability while every existing contract keeps its boundary. `6.1.0`
added `MissionReopened` and `FollowUpRecorded` this way. A minor version does not make an
unknown event safe for every consumer — producers must capability-gate emission until each
intended consumer can validate the new type.

## Artifacts a major bump must ship

`CHANGELOG.md` (`### Breaking`, one line per change plus its consequence), `COMPATIBILITY.md`
(the version line plus the migration per role, and an old-to-new field map for any renamed
field), `pyproject.toml`, `__version__`, the README masthead and pip lines and `uv.lock`,
every regenerated `*.schema.json`, and conformance fixtures covering the new accept/reject
boundary. Forbidden: bumping without updating `CHANGELOG.md`, changing models without
regenerating schemas, documenting a breaking change as additive, or weakening the
local-CLI compatibility wording.

## Two validity domains

A row acceptable in one domain is not necessarily acceptable in the other:

- **Local-CLI compatibility** — the CLI reads historical `status.events.jsonl` rows off disk
  permissively, so users never lose their own history. Not weakened by any package bump.
- **TeamSpace ingress validity** — only canonical envelopes pass; the full fail-closed
  [strict profile](/api/strict-profile/). Ingress will not accept raw historical rows; a
  consumer reading locally must not assume its rows have been canonicalized.
