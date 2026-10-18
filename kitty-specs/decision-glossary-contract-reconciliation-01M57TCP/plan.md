# Implementation Plan: Decision and glossary contract reconciliation (11.0)

**Branch**: `issue-120-decision-moment-reconciliation` | **Date**: 2026-10-18 | **Spec**: [spec.md](spec.md) | **Issue**: #120

## Summary

This mission ships one major release, 11.0.0, in five sequential work packages:

1. Make the envelope clock optional, with a single canonical ordering key and unclocked-safe concurrency and grouping.
2. Fold the DecisionPoint reopen in two passes and add a ledger-record read-side fold.
3. Re-model the glossary payloads on core's emitted shapes, register `StepCheckpointed`, and re-key the glossary reducer.
4. Bring the conformance registries up to date: validator maps, regenerated schemas, every new or rewritten fixture, the manifest and the runners.
5. Release 11.0.0: remove `LOCAL_ONLY_EVENT_TYPES`, update COMPATIBILITY and CHANGELOG, bump the version and update the docs.

## Technical Context

**Language/Version**: Python ≥ 3.10, Pydantic v2
**Primary Dependencies**: no new ones (stdlib, pydantic, jsonschema as today)
**Storage**: local JSONL ledgers via `persistence.append_event` (10.8.0)
**Testing**: pytest, hypothesis (order independence, ≥100 shuffles), the packaged conformance runners, `python -m spec_kitty_events.schemas.generate --check`, `mypy --strict`, ruff
**Target Platform**: library (POSIX and Windows)
**Project Type**: single package
**Performance Goals**: reducers stay O(n log n) in stream length; no regression in `tests/test_validation_benchmark.py`
**Constraints**: C-001 (mirror, don't depend), C-002 (no aliases), C-003 (envelope `schema_version` stays 3.0.0), C-004 (events repo only), C-005 (no `glossary.events` module)
**Scale/Scope**: about 14 source modules touched, 9 schemas regenerated plus 1 added, about 45 fixtures new or rewritten

## Charter Check

| Rule | How this plan meets it |
|---|---|
| Testing: hypothesis, schema drift, fixture validation | Order-independence property; `schemas.generate --check`; packaged runners |
| Quality gates: pytest, committed schemas, `mypy --strict` | Every WP runs them; WP04 is the schema/fixture gate |
| Review: deliberate compatibility review | The envelope, payload and fixture changes are a major; COMPATIBILITY has an old→new map for each role |
| Directive 1: no silent aliases | C-002, and the cutover is documented as a major |
| Package boundary | Core shapes are committed as static JSON with provenance; no core imports |
| Language Bindings | All new behaviour is pinned by fixtures |

**Gate result:** PASS. This is a deliberate major release; the operator decided D6, D8 and D9.

## Design

### WP01: envelope clock
- **Model:** `models.Event.lamport_clock: Optional[int] = Field(None, ge=0)`.
- **Ordering:** a new module, `ordering.py`, holds `canonical_order_key(event) -> tuple`, which returns `(0 if clocked else 1, clock or 0, timestamp_iso, event_id)`, and `is_clocked(event)`.
- **Sort sites:** `status_event_sort_key` delegates to the canonical key. So do the sorts in `mission_next`, `collaboration`, `dossier` and `lifecycle`. The glossary sort moves in WP03, because WP03 owns `glossary.py`.
- **Storage:** the storage adapters sort by `(0 if clocked else 1, clock or 0, node_id)`, then `event_id`.
- **Concurrency and grouping:** `conflict.is_concurrent` and the `merge` precondition return False for an unclocked envelope, or raise the existing error. The lifecycle grouping and the status rollback grouping (`status.py:885-890`) treat an unclocked envelope as a singleton group.
- **Clock-required surfaces:** `work_replay.WorkStreamItem.from_event` refuses an unclocked envelope with `ValueError("work replay requires lamport_clock (LW-10)")`. The observation contract docs stay unchanged, and their validators keep requiring a clock where they read the envelope.
- **Strict profile:** `strict.py` keeps `lamport_clock` in its required set.

### WP02: DecisionPoint reopen and records fold (`decisionpoint.py`)
- **Constant:** `DECISION_POINT_TERMINAL_REOPEN: frozenset[tuple[TerminalOutcome, TerminalOutcome]] = {(DEFERRED, RESOLVED)}`.
- **Pass 1:** `_select_effective_terminal(resolved_events)` returns `(effective, reopen_point | None, unfoldable: bool)`.
- **Pass 2:** the existing walk, extended:
  - the reopen-point deferred event is applied as a non-terminal state entry, so the decision goes back to its pre-terminal state and Widened or Discussing events after it apply normally;
  - the extra Resolved events of an unfoldable set become `unfoldable_terminal_combination` anomalies;
  - an Overridden that sorts before the effective resolve becomes `invalid_transition`.
- **Reduced state:** `ReducedDecisionPointState` gains `reopened_from_deferred: bool = False`. The anomaly literal gains `unfoldable_terminal_combination`.
- **Records fold:** `reduce_decision_point_records(records, *, strict=True) -> dict[str, ReducedDecisionPointState]`.
  - Each record goes through `persistence.build_record`, then `Event.model_validate`.
  - A record whose `event_type` is not a DecisionPoint type is skipped.
  - Records are grouped by `payload["decision_point_id"]`, and `reduce_decision_point_events` runs on each group.
  - With `strict=True`, a record refused by `LedgerAppendError` propagates, and an unfoldable anomaly raises `DecisionPointFoldError(SpecKittyEventsError)`.
- **Sorting:** both reducers sort by the canonical key from WP01.

### WP03: glossary 11.0 (`glossary.py`)
- **Models:** the 8 payload models are re-modelled per research.md §G, with the nested `GlossaryFinding`, `SenseRef`, `GlossaryActor`, `GlossaryProvenance` and `ScopeRef` models and the enums.
  - Class names keep the `*Payload` convention, and each class is unique to one event type.
  - `StepCheckpointedPayload` and `STEP_CHECKPOINTED` are added, and `GLOSSARY_EVENT_TYPES` is updated.
  - `GlossaryStrictnessSetPayload` is unchanged.
  - The old `SemanticConflictEntry` is removed and replaced by `GlossaryFinding`.
- **Reducer:**
  - term candidates are keyed by `term`, senses by `(scope, term_surface)`;
  - clarifications are correlated by `conflict_id`;
  - burst grouping on `semantic_check_event_id` applies only when the field is present;
  - scope gating applies only to SenseUpdated;
  - checkpoints are added (the latest per `step_id`);
  - the reducer sorts by the canonical key.
- **Package root:** WP03 is the **single owner of `__init__.py`**, because the glossary rename breaks root imports immediately. In that file WP03:
  - updates the glossary re-exports;
  - re-exports WP02's new names (`reduce_decision_point_records`, `DECISION_POINT_TERMINAL_REOPEN`, `DecisionPointFoldError`);
  - removes `LOCAL_ONLY_EVENT_TYPES`, replacing `tests/test_local_only_deprecation.py` with `tests/test_local_only_removed.py`;
  - drops the reference in `persistence.py`'s docstring;
  - sets `__version__ = "11.0.0"`.
- **WP02 exports** its new names only from `spec_kitty_events.decisionpoint`.

### WP04: conformance registries (shared files, single owner)
- **Validator maps:** add `StepCheckpointed` and repoint the glossary types in `validators.py`.
- **Schemas:** regenerate with `python -m spec_kitty_events.schemas.generate`.
- **Fixtures:** rewrite the 9 glossary valid and 3 invalid fixtures from static captures of core's builders, with provenance (core SHA and builder name) in the manifest `notes`. Add the StepCheckpointed fixtures.
- **DecisionPoint replay streams and outputs:**
  - the reopen in both orders, plus an Overridden after it;
  - an Overridden between the two Resolved events, and a Discussing after the deferred;
  - an unfoldable set, two deferred, and deferred plus canceled.
- **Envelope fixtures:** clock absent (valid), clock absent under the strict profile (invalid), and clock -1 (still invalid).
- **Ordering fixture:** for unclocked and mixed streams.
- **`ledger_append` cases:**
  - full DecisionPoint envelopes;
  - core-shaped glossary records;
  - the 10.8.0 glossary cases regenerated, with `min_version` 11.0.0;
  - the `payload_model` case now uses the new model.
- **Audit and manifest:** audit `cutover_boundary/accepted_by_validate_event`; manifest entries use `min_version` 11.0.0.
- **Support matrix:** update the rows and digest when strict rows change.

### WP05: release 11.0.0
- **Version:** bump to 11.0.0 in pyproject, the README masthead and pip lines, COMPATIBILITY and `uv.lock`. The removal and `__version__` are done in WP03.
- **CHANGELOG `### Breaking`:** the clock change, the glossary shapes, the `LOCAL_ONLY_EVENT_TYPES` removal and `SemanticConflictEntry` → `GlossaryFinding`.
- **CHANGELOG `### Added`:** the reopen, the records fold, `StepCheckpointed` and `ordering.py`. Add the note that no committed replay output changed.
- **COMPATIBILITY:** an `11.0.0 (breaking)` section with a migration for each role and the old→new glossary field map.
- **Docs:** the FR-014 pages, plus re-syncing `docs/api/versioning-and-compatibility.md` with `contracts/`.

## Project Structure

```
src/spec_kitty_events/
├── ordering.py          # NEW (WP01)
├── models.py status.py storage.py conflict.py merge.py lifecycle.py work_replay.py
│   mission_next.py collaboration.py dossier.py strict.py        # WP01
├── decisionpoint.py     # WP02
├── glossary.py          # WP03 (+ glossary block of __init__.py)
├── conformance/validators.py, schemas/**, conformance/fixtures/**, conformance/test_*.py  # WP04
└── __init__.py, persistence.py docstring (WP03) · pyproject, README, COMPATIBILITY, CHANGELOG, uv.lock, docs/** (WP05)
tests/
├── test_ordering.py (WP01) · test_decisionpoint_reducer.py (WP02) · test_glossary.py, test_glossary_reducer.py (WP03)
└── test_glossary_conformance.py, test_decisionpoint_conformance.py (WP04) · test_local_only_deprecation.py → test_local_only_removed.py (WP05)
```

## Complexity Tracking
The two-pass fold keeps each helper at complexity 15 or below: `_select_effective_terminal` is pure, and the walk keeps its current shape.

## Parallel Work Analysis

### Dependency Graph
```
WP01 → WP02 → WP03 → WP04 → WP05
```
### Work Distribution
The WPs run sequentially. WP02 and WP03 both depend on WP01's ordering key, and WP04 needs both new model sets. A strict chain avoids conflicts in `__init__.py` and the manifest.
### Coordination Points
- **Schema drift:** the drift check is red from WP01 until WP04 regenerates the schemas. The green bar is the WP04 head, and the reviews of WP01–WP03 check unit tests and mypy only.
- **`__init__.py`:** WP03 is its single owner.

## Implementation Concern Map

| Concern | Requirements | WP |
|---|---|---|
| Optional clock, ordering, concurrency | FR-001, FR-002, FR-002a, FR-002b, FR-003 | WP01 |
| Reopen and records fold | FR-004, FR-005, FR-006, NFR-001 | WP02 |
| Glossary shapes and reducer | FR-007, FR-008, FR-009, NFR-002 | WP03 |
| Schemas and fixtures | FR-011, FR-012, NFR-004 | WP04 |
| Removal | FR-010 | WP03 |
| Release and docs | FR-013, FR-014 | WP05 |
