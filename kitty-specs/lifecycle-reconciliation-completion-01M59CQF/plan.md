# Implementation Plan: Lifecycle reconciliation completion and RACI decision events

**Branch**: `issue-119-lifecycle-reconciliation-completion` | **Date**: 2026-10-19 | **Spec**: [spec.md](spec.md)
**Input**: `kitty-specs/lifecycle-reconciliation-completion-01M59CQF/spec.md`; brief `research/mission-brief.md`

Branch contract: planning/base branch `issue-119-lifecycle-reconciliation-completion`; completed changes consolidate into `issue-119-lifecycle-reconciliation-completion` (branch matches target: true). Publication is a PR from that branch to `main`, owned by the operator.

## Summary

Additive 11.1.0 release of `spec_kitty_events`:
1. a new decision-event module beside `mission_next` that mirrors the runtime's `SignificanceEvaluated` and `DecisionTimeoutExpired` payloads with a new `RaciRoleBinding` (required non-empty `actor_id`, R2) and a `ResolvedRaciSnapshot`;
2. lifecycle conformance breadth: invalid fixtures for every lifecycle type that lacks one, a replay stream with a reducer golden for `reduce_lifecycle_events`, and WPStatusChanged lane breadth plus summary-on-rework/done (#62);
3. a reconciliation document, CHANGELOG/COMPATIBILITY 11.1.0, version bump, stale-pin corrections.

## Technical Context

**Language/Version**: Python 3.10+ (package floor; `pyproject.toml` `target-version = "py310"`)
**Primary Dependencies**: Pydantic v2 (models, generated JSON Schema); no new dependency
**Storage**: committed JSON Schemas under `src/spec_kitty_events/schemas/`, conformance fixtures under `src/spec_kitty_events/conformance/fixtures/` (manifest-registered)
**Testing**: pytest (in-repo `tests/` + packaged `--pyargs spec_kitty_events.conformance`), schema drift test `tests/integration/test_schema_drift.py`, mypy --strict, ruff check/format
**Target Platform**: Python library (wheel) consumed by the Spec Kitty CLI and SaaS
**Project Type**: single library
**Performance Goals**: packaged conformance gate under 20 s (baseline about 8 s, 727 passed)
**Constraints**: additive only (C-001); no consumer import (C-002); fail-closed (C-003); generated schemas (C-004)
**Scale/Scope**: 2 new event types, 4 new models, about 25 to 35 new fixtures, 1 replay stream + golden, 1 doc

Supply chain: no dependency is added, upgraded or removed; DIRECTIVE_051 controls not triggered.

## Charter Check

| Charter rule | How the plan honours it | Verdict |
|---|---|---|
| Testing: pytest, hypothesis, drift, conformance for contract change | New fixtures run in the packaged gate; schemas regenerated and drift test stays green; unit tests for new models | Pass |
| Quality gates: pytest, schema generation check, mypy --strict | NFR-001..003; every WP's DoD runs them | Pass |
| Review policy: payload/fixture changes need deliberate compatibility review | COMPATIBILITY 11.1.0 entry states no boundary moved; one adversarial-squad review on the PR (C-008) | Pass |
| Package boundary: no import of consumers | Shapes are mirrored by hand from core sources cited in research.md; C-002 | Pass |
| Directive 1: no silent compatibility aliases / no mutation of canonical fields | No existing model, schema or fixture is edited; R2 narrowing is new-model-only and documented | Pass |
| Directive 2: docs synchronized | WP03 reconciliation doc + CHANGELOG/COMPATIBILITY | Pass |
| Language bindings: schemas+fixtures are the source of truth | New contracts ship as generated schemas + fixtures; no Java binding exists yet to update | Pass |

Post-design re-check: pass; no charter conflict found.

## Key design decisions (see research.md for rationale)

- **D1 module placement**: new module `src/spec_kitty_events/decision_significance.py` beside `mission_next.py`. It defines its own type constants and a `DECISION_SIGNIFICANCE_EVENT_TYPES` frozenset. It does NOT add the types to `MISSION_NEXT_EVENT_TYPES` (reducer membership), `STRICT_EVENT_TYPES` (strict envelope profile, count pinned at 26), or the support matrix (count pinned at 55) (C-005, C-007).
- **D2 R2 narrowing is new-model-only**: the producer-row schema `producer_contract/data/run.significance.evaluated.v1.schema.json` (nullable `actor_id`) is left unchanged; its agreement with the new model is asserted field by field by a new test, with the `actor_id` narrowing as the single documented exception (FR-007).
- **D3 new fixture categories instead of the flat `events` family**: `tests/unit/test_fixtures.py` pins the flat `events` family at 33 valid / 18 invalid and `tests/test_zeitgeist_attrs_conformance.py` pins `zeitgeist_attrs` at 78. Adding fixtures there would force an edit to existing test expectations (an escalation trigger). New fixtures therefore go into two new categories, `decision_significance` and `lifecycle`, added to `conformance/loader.py::_VALID_CATEGORIES` (precedent: `ledger_append` 10.8.0, `ordering` 11.0.0).
- **D4 reducer golden is packaged**: a `lifecycle/replay/*.jsonl` replay_stream plus `*-output` reducer_output fixture, driven by a new packaged `conformance/test_lifecycle_reducer_replay.py` (pattern: `conformance/test_status_diary_reducer_replay.py`).
- **D5 #62 unit tests are new test functions** in `tests/unit/test_zeitgeist_attrs.py`; no existing parametrize list or assertion is edited.
- **D6 timeout `raci_snapshot`** is typed as `ResolvedRaciSnapshot` (core serializes a `ResolvedRACIBinding`), and `significance_score` stays an open object as in core and the producer row. If typing the snapshot would refuse a shape core actually emits beyond the R2 actor narrowing, the implementer stops and escalates (C-007).

## Project Structure

### Documentation (this mission)

```
kitty-specs/lifecycle-reconciliation-completion-01M59CQF/
├── spec.md  plan.md  research.md  data-model.md  quickstart.md
├── contracts/decision-significance-events.md
├── research/ (brief, grounding, discovery)
└── tasks/ (created by /spec-kitty.tasks)
```

### Source Code (repository root)

```
src/spec_kitty_events/
├── decision_significance.py            # NEW (WP01)
├── __init__.py                         # export additions (WP01) + version (WP03)
├── conformance/validators.py           # dispatch registration (WP01)
├── conformance/loader.py               # _VALID_CATEGORIES additions: decision_significance + lifecycle (WP01)
├── conformance/test_lifecycle_reducer_replay.py   # NEW (WP02)
├── conformance/fixtures/manifest.json  # registrations (WP01, WP02)
├── conformance/fixtures/decision_significance/{valid,invalid}/   # NEW (WP01)
├── conformance/fixtures/lifecycle/{valid,invalid,replay}/        # NEW (WP02)
└── schemas/generate.py + generated *.schema.json                 # (WP01)
tests/unit/test_decision_significance.py      # NEW (WP01)
tests/test_decision_significance_producer_row.py  # NEW (WP01, FR-007)
tests/test_lifecycle_conformance.py           # NEW (WP02)
tests/unit/test_zeitgeist_attrs.py            # new test functions only (WP02)
docs/architecture/lifecycle-reconciliation.md # NEW (WP03) + docs/architecture/README.md index
docs/events/review-audit-decisions.md         # new decision-event entries (WP03)
CHANGELOG.md  COMPATIBILITY.md  pyproject.toml   # (WP03)
```

**Structure Decision**: single library; additions only, registration lists and version excepted.

## Complexity Tracking

No charter violation to justify.

## Parallel Work Analysis

### Dependency Graph

```
WP01 (decision events) ──> WP02 (lifecycle breadth) ──> WP03 (reconciliation doc + release notes + version)
   └────────────────────────────────────────────────────────^
```

### Work Distribution

- **Sequential work**: WP02 after WP01 (WP01 adds the `lifecycle` fixture category in `conformance/loader.py` and owns `conformance/fixtures/manifest.json`, to which WP02 appends); WP03 after WP01 and WP02 (it documents both and bumps the version).
- **Parallel streams**: none. WP01 and WP02 are independent in content but are serialized by the shared append-only registration files (`lanes.json`: lane-b depends on lane-a).
- **Agent assignments**: per WP `owned_files`.

### Coordination Points

- **Integration tests**: after consolidation run the packaged conformance gate, the schema drift test, `make test-fast`, mypy --strict and ruff.
