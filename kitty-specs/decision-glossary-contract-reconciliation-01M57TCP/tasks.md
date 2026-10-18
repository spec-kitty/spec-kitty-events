# Tasks: Decision and glossary contract reconciliation (11.0, #120)

## WP01: envelope clock: optional `lamport_clock`, canonical ordering, unclocked-safe concurrency
Prompt: [tasks/WP01-envelope-clock.md](tasks/WP01-envelope-clock.md)

T001 Red-first: issue-pinned repro (#120) showing an unclocked `Event` is refused and a mixed sort raises (WP01)
T002 `Event.lamport_clock: Optional[int]` (ge=0); new `ordering.py` with `canonical_order_key` and `is_clocked` (WP01)
T003 Route every sort site through the canonical key: status, mission_next, collaboration, dossier, lifecycle, storage (WP01)
T004 Unclocked is never concurrent and forms a singleton group: conflict, merge, lifecycle grouping, status rollback grouping (WP01)
T005 work_replay refuses unclocked (LW-10); the strict profile keeps the clock required; focused tests for every site (WP01)

## WP02: DecisionPoint two-pass reopen fold and ledger-records fold
Prompt: [tasks/WP02-decisionpoint-reopen.md](tasks/WP02-decisionpoint-reopen.md)

T006 Red-first: issue-pinned repro, where a deferred→resolved pair currently reduces to DEFERRED with `invalid_transition` (WP02)
T007 `DECISION_POINT_TERMINAL_REOPEN`, `_select_effective_terminal`, the anomaly literal `unfoldable_terminal_combination`, `reopened_from_deferred` (WP02)
T008 Pass-2 walk: reopen point, Widened/Discussing after deferred, Overridden ordering rule (WP02)
T009 `reduce_decision_point_records(records, *, strict=True)` and `DecisionPointFoldError` (WP02)
T010 Unit tests, plus a hypothesis order-independence property over at least 100 shuffles (WP02)

## WP03: glossary 11.0 models and reducer, StepCheckpointed, package-root surface
Prompt: [tasks/WP03-glossary-11.md](tasks/WP03-glossary-11.md)

T011 Red-first: issue-pinned repro where core's 8 glossary records are refused by 10.8.0 (WP03)
T012 Re-model the 8 payloads and their nested models and enums per research.md §G; add `StepCheckpointedPayload` (WP03)
T013 Re-key the glossary reducer: term, `(scope, term_surface)`, `conflict_id`, checkpoints; canonical ordering key (WP03)
T014 `__init__.py`: glossary re-exports, WP02 re-exports, remove `LOCAL_ONLY_EVENT_TYPES`, `__version__` 11.0.0; persistence docstring (WP03)
T015 Rewrite `tests/test_glossary.py` and `test_glossary_reducer.py`; add `tests/test_local_only_removed.py` (WP03)

## WP04: conformance registries: validator maps, schemas, fixtures, manifest, runners
Prompt: [tasks/WP04-conformance-registries.md](tasks/WP04-conformance-registries.md)

T016 Add `StepCheckpointed` to the validator maps and repoint glossary; regenerate every schema (event.schema.json included) (WP04)
T017 Glossary fixtures from static core captures with provenance; StepCheckpointed fixtures; audit cutover_boundary (WP04)
T018 DecisionPoint replay streams and outputs (reopen both orders, Overridden placements, Discussing after deferred, unfoldable sets) (WP04)
T019 Envelope clock fixtures, ordering fixture, `ledger_append` decision and glossary cases (10.8.0 ones regenerated) (WP04)
T020 Manifest `min_version` 11.0.0, support-matrix rows, runner and conformance test updates; the full packaged gate (WP04)

## WP05: 11.0.0 release surfaces and docs
Prompt: [tasks/WP05-release-11.md](tasks/WP05-release-11.md)

T021 Version 11.0.0 in pyproject, README masthead and pip lines, COMPATIBILITY version line, `uv.lock` (WP05)
T022 COMPATIBILITY `11.0.0 (breaking)` section: migration for each role and an old→new glossary field map (WP05)
T023 CHANGELOG `### Breaking` and `### Added`, including the note that no committed replay output changed (WP05)
T024 Docs for FR-014, plus the re-sync of `docs/api/versioning-and-compatibility.md` (WP05)
T025 Blast radius: make test-fast, packaged gate, schema `--check`, version guard, mypy, ruff (WP05)
