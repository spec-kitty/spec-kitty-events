# Implementation Plan: Subtask skipped-status vocabulary in the diary contract

**Branch**: `feat/subtask-skipped-status` | **Date**: 2026-10-17 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `kitty-specs/subtask-skipped-status-01M54C8D/spec.md`

## Summary

Give the diary contract a public subtask-status vocabulary that matches the
already-shipped `wp.innerstate.changed.v1` producer-contract schema (whose
`subtasks` enum already lists `skipped`), so every consumer reads a `skipped`
subtask through the shared package instead of crashing on `Status(value)`.

**Technical approach** (pinned by the spec + Decision Moment
`01M54CFGJ91EEZ0QNT3D26PSHZ`): introduce a dedicated `SubtaskStatus` enum —
the ten active `Lane` values plus a terminal `skipped` — in `diary.py` beside
`Lane`, with a `SubtaskValue` alias and the existing `Status` alias repointed to
it for backward compatibility. Retype `WPInnerStateDelta.subtasks` to
`SubtaskStatus`, accept the input alias `not_applicable` at the parse site and
normalize it to `skipped` (never persisted), add a terminal-subtask predicate,
add a conformance fixture + round-trip test, and ship as package minor
**10.6.0**.

## Technical Context

**Language/Version**: Python 3.10+ (`(str, Enum)`, not `StrEnum`, per the
`Lane` precedent at `diary.py:148`).
**Primary Dependencies**: stdlib `enum`/`typing` only; no new runtime deps.
Pydantic models and committed JSON Schemas are the existing contract surface.
**Storage**: N/A — the persisted artifact is the append-only
`status.events.jsonl` diary; the canonical on-wire vocabulary is the committed
`wp.innerstate.changed.v1` schema (already `skipped`-aware).
**Testing**: pytest; the packaged conformance gate
(`uv run pytest --pyargs spec_kitty_events.conformance`); existing diary-parse
and reducer suites as the backward-compat ratchet.
**Target Platform**: Python library consumed by spec-kitty / spec-kitty-saas /
spec-kitty-tracker / spec-kitty-runtime.
**Project Type**: single (library package).
**Performance Goals**: no change — parse/reduce stay deterministic and O(events);
one enum lookup per subtask value is unchanged in cost.
**Constraints**: additive, SemVer **minor** (10.6.0); on-wire envelope version
stays `3.0.0`; package stays independent of consumer repos (charter Package
Boundary); fail-closed on unknown values preserved.
**Scale/Scope**: one module (`diary.py`) plus one fixture + one test; no public
API removed.

## Charter Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Charter rule | Status | Note |
|---|---|---|
| **Package Boundary** — package independent of consumer repos | ✅ PASS | Change is self-contained in the events package; the CLI re-export/backfill switch is explicitly downstream (Out of Scope). |
| **Quality Gates** — pytest, committed schema-generation checks, `mypy --strict` | ✅ PASS | New enum is fully typed; `subtasks: Mapping[str, SubtaskStatus]` keeps `--strict` clean. Schema already contains `skipped`, so the schema-generation/drift check stays green (binding catches up to schema, not vice-versa). |
| **Review Policy** — envelope/payload/version/fixture changes need deliberate compatibility review | ✅ PASS | Additive vocabulary + new fixture → compatibility review required; backward-compat ratchet (FR-006/NFR-001) is the gate. |
| **Byte-identical packaged contract copy** invariant | ✅ PASS | No change to `contracts/total-order.md` / packaged copy; untouched by this mission. |
| **PROGRAM.md §2** — any `src/` change needs a new unspent version | ✅ PASS | Ships as 10.6.0 (SC-004: `check_version_not_amended` green). |

No conflicts to escalate.

## Project Structure

### Documentation (this feature)

```
kitty-specs/subtask-skipped-status-01M54C8D/
├── plan.md              # This file
├── research.md          # Phase 0 — decision record (below)
├── data-model.md        # Phase 1 — SubtaskStatus vocabulary + entities
├── quickstart.md        # Phase 1 — consumer-facing usage
└── contracts/           # Phase 1 — points at the existing shipped schema (no new contract)
```

### Source Code (repository root)

```
src/spec_kitty_events/
├── diary.py                       # SubtaskStatus enum + SubtaskValue alias + Status repoint;
│                                  #   WPInnerStateDelta.subtasks retype; not_applicable normalize
│                                  #   at parse (~:726); is_terminal_subtask predicate; __all__ exports
├── producer_contract/
│   └── data/wp.innerstate.changed.v1.schema.json   # already lists `skipped` — unchanged
├── conformance/
│   └── fixtures/status_diary/     # add a skipped-subtask fixture
├── __init__.py                    # version pin 10.6.0 (diary surface already re-exported via diary module)
└── (pyproject.toml, README.md, uv.lock, CHANGELOG.md, COMPATIBILITY.md — version bump + changelog)

tests/
└── (diary / status_diary parse + round-trip test for the skipped fixture)
```

## SPDD / REASONS

- **Approach (A)**: A fresh `SubtaskStatus(str, Enum)` duplicating `Lane`'s ten
  active members plus `skipped`. Chosen over (a) adding `skipped` to `Lane`
  (rejected — conflates subtask status with work-package lane transitions and
  would widen the lane state machine / transition matrix), and (b) a bare
  `set[str]` (rejected — loses type safety and the `mypy --strict` guarantee).
  Trade-off: the member list is duplicated from `Lane`; a unit assertion pins
  `{m.value for m in SubtaskStatus} == {m.value for m in Lane active} | {"skipped"}`
  so the duplication cannot silently drift.
- **Structure (S)**: Everything lives in `diary.py` (the subtask-status contract
  module). `Status` stays as a backward-compat alias now pointing at
  `SubtaskStatus`; `SubtaskValue` is the published alias; both join diary
  `__all__`. `WPInnerStateDelta.subtasks: Mapping[str, SubtaskStatus]`.
- **Norms (N)**: Deterministic parse/reduce preserved; fail-closed on unknown
  values preserved (an unknown string still raises `DiaryError`/`ValueError`);
  SemVer minor; mypy --strict; charter Package Boundary.
- **Safeguards (S)**: `not_applicable` is accepted **only** at the parse
  boundary and normalized to `skipped` immediately, so it can never reach the
  reducer, serialization, or the canonical enum (C-002). Backward-compat ratchet
  (FR-006 / NFR-001): every existing fixture and diary-parse test must stay green
  with 0 regressions, with the new skipped fixture as the non-vacuity positive
  control. Round-trip test proves `skipped` serializes back to `skipped` and
  `not_applicable` serializes to `skipped`.

## Implementation Concerns

- **IC-01 — Subtask status vocabulary + skipped**: the whole change — the
  `SubtaskStatus`/`SubtaskValue`/`Status` types in `diary.py`, the
  `not_applicable` parse normalization, the retype of
  `WPInnerStateDelta.subtasks`, the terminal predicate, the conformance fixture
  + round-trip test, and the 10.6.0 release. One cohesive concern; no
  parallelization seam.

## Phase 0: Research

All clarifications are resolved — no outstanding `[NEEDS CLARIFICATION]`. The one
design question (how to treat `not_applicable`) was resolved as Decision Moment
`01M54CFGJ91EEZ0QNT3D26PSHZ`: **input alias, normalized to `skipped` at parse,
never persisted**, grounded in the CLI alias semantics (spec-kitty#5953) and the
shipped schema enum (which contains `skipped`, not `not_applicable`). See
[research.md](./research.md).

## Phase 1: Design & Contracts

- **data-model.md**: the `SubtaskStatus` / `SubtaskValue` vocabulary, the
  `not_applicable` input alias, the terminal-subtask predicate, and
  `WPInnerStateDelta.subtasks` as the `{subtask_id: SubtaskStatus}` map.
- **contracts/**: no new contract — the canonical contract is the already-shipped
  `wp.innerstate.changed.v1` producer-contract schema, which already lists
  `skipped`. The data-model records that the Python binding is being brought into
  line with the existing schema (binding-catches-schema, not a schema change).
- **quickstart.md**: how a consumer parses a skipped subtask and checks
  terminality through the package predicate.

## Mandatory Stop

This plan ends after Phase 1 design artifacts. Work packages are generated by
`/spec-kitty.tasks` (next phase of the control loop).
