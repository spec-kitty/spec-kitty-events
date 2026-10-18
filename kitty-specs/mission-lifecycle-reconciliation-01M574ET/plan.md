# Implementation Plan: Mission-lifecycle event reconciliation

**Branch**: `issue-119-mission-lifecycle-reconciliation` | **Date**: 2026-10-18 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `kitty-specs/mission-lifecycle-reconciliation-01M574ET/spec.md`

## Summary

Close the mission-lifecycle schema+fixture gaps found by brownfield grounding, additively and
mirror-not-depend: generate committed JSON Schemas and add valid/invalid conformance fixtures
for the already-modelled `MissionReopened` and `FollowUpRecorded`, and add a lifecycle-layer
`ReviewerSelfApproval` payload model (mirroring core's emit and the existing `producer_contract`
row schema) with its schema and fixtures. Schemas are generated from the canonical Pydantic
models via `src/spec_kitty_events/schemas/generate.py`; fidelity and additivity are proven by
the existing `tests/integration/test_schema_drift.py` and the packaged conformance gate.

## Technical Context

**Language/Version**: Python 3.10+ (support floor 3.10 per `make test-floor`)
**Primary Dependencies**: Pydantic (frozen event payload models); committed JSON Schemas; packaged conformance fixtures. No new runtime dependency is added.
**Storage**: Files — committed JSON Schemas under `src/spec_kitty_events/schemas/`, fixtures under `src/spec_kitty_events/conformance/fixtures/`.
**Testing**: pytest + the packaged conformance gate `uv run pytest --pyargs spec_kitty_events.conformance`; `tests/integration/test_schema_drift.py` for schema≡model fidelity.
**Target Platform**: Pure-Python library consumed by core/SaaS/future Java binding via released artifacts.
**Project Type**: single (library package)
**Performance Goals**: N/A — validation/generation is additive and deterministic; no hot path changed.
**Constraints**: Strictly additive (no existing type/model/schema/fixture modified); fail-closed validation; SemVer minor; the package imports nothing from `spec-kitty`/`-saas`/`-tracker`/`-runtime` (mirror-not-depend).
**Scale/Scope**: 3 event payloads (2 schema+fixture only, 1 new model+schema+fixture).

## Charter Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **Schemas+fixtures are the single source of truth** → satisfied: schemas generated from models, fixtures added to the packaged corpus.
- **Additive-only / deliberate compatibility review** → satisfied: additions only, proven by a diff showing no existing artifact modified (SC-004). No envelope change.
- **Fail-closed** → satisfied: new events registered so invalid payloads are refused by `validate_event`; reducers skip unknown types.
- **Package boundary** → satisfied: shapes mirrored from core by reading, not importing; import set unchanged.
- **mypy --strict + committed schema generation checks + pytest** → run as quality gates before PR.
- No violations → Complexity Tracking empty.

## Project Structure

### Documentation (this feature)

```
kitty-specs/mission-lifecycle-reconciliation-01M574ET/
├── plan.md              # This file
├── research.md          # Fidelity findings: exact shapes mirrored from core
├── data-model.md        # The three payloads' field sets
└── tasks.md             # Created by /spec-kitty.tasks
```

### Source Code (repository root)

```
src/spec_kitty_events/
├── lifecycle.py                 # MissionReopened / FollowUpRecorded payload models (exist) — unchanged
├── project_lifecycle.py         # + ReviewerSelfApproval payload model, type constant, registry membership (ADD)
├── conformance/
│   ├── validators.py            # register ReviewerSelfApproval in the dispatch/model map (ADD entry)
│   └── fixtures/
│       └── events/              # + valid/invalid fixtures for the three events (ADD)
└── schemas/
    ├── mission_reopened_payload.schema.json        # (ADD, generated)
    ├── follow_up_recorded_payload.schema.json      # (ADD, generated)
    ├── reviewer_self_approval_payload.schema.json  # (ADD, generated)
    └── generate.py              # generator — run to emit the three schemas

tests/integration/test_schema_drift.py   # proves schema≡model for the additions
```

**Structure Decision**: Single library package; additions slot into the existing `lifecycle`/
`project_lifecycle` modules, the `schemas/` output, and the `conformance/fixtures/` corpus,
using the established generation + drift-check mechanism. Exact fixture subdirectory is
confirmed against the existing layout during implementation.

## Implementation Concern Map

| Concern | What it covers | Depends on |
|---|---|---|
| **IC-1 Shape fidelity (research)** | Read core's emit sites + the `producer_contract` ReviewerSelfApproval row schema; record the exact field set/optionality/types each of the 3 events must match. | — |
| **IC-2 MissionReopened/FollowUpRecorded schema+fixtures** | Generate the two schemas from the existing models; add valid + invalid fixtures; confirm drift check + conformance gate green. | IC-1 |
| **IC-3 ReviewerSelfApproval model+schema+fixtures** | Add payload model + type constant + registry/dispatch membership in `project_lifecycle`; generate schema; add valid + invalid fixtures; register in `validate_event`. | IC-1 |
| **IC-4 Additivity + fail-closed proof** | Diff vs pre-mission commit (additions only, import set unchanged); invalid fixtures refused; reducers skip unknown; mypy --strict + ruff + conformance gate. | IC-2, IC-3 |

## Parallel Work Analysis

### Dependency Graph

```
IC-1 (shape fidelity, research) → IC-2 ∥ IC-3 (parallel: disjoint events/files) → IC-4 (integration proof)
```

### Work Distribution

- **Sequential first**: IC-1 — the exact mirrored shapes gate both schema/fixture tasks.
- **Parallel**: IC-2 (lifecycle events) and IC-3 (project_lifecycle event) touch different models/schemas/fixtures and can proceed independently.
- **Integration**: IC-4 runs the drift check, conformance gate, and additivity diff over the combined result.

### Coordination Points

- Both IC-2 and IC-3 regenerate via `schemas/generate.py`; regenerate once at integration to avoid a stale schema race.
- `validators.py` dispatch-map edit is owned solely by IC-3 to avoid a merge conflict.

## Complexity Tracking

*No Charter Check violations — none to justify.*
