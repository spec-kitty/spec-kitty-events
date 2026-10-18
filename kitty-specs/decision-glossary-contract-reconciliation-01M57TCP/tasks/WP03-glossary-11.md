---
work_package_id: WP03
title: Glossary 11.0 models and reducer, StepCheckpointed, package root
dependencies:
- WP02
requirement_refs:
- FR-007
- FR-008
- FR-009
- FR-010
- NFR-002
- C-001
- C-002
- C-005
planning_base_branch: issue-120-decision-moment-reconciliation
merge_target_branch: issue-120-decision-moment-reconciliation
branch_strategy: Planning artifacts for this mission were generated on issue-120-decision-moment-reconciliation. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into issue-120-decision-moment-reconciliation unless the human explicitly redirects the landing branch.
base_branch: kitty/mission-decision-glossary-contract-reconciliation-01M57TCP
base_commit: 1c25f7a8fb2a8f0b5312778f2610fb92ebb03722
created_at: '2026-10-18T16:05:28.169523+00:00'
subtasks:
- T011
- T012
- T013
- T014
- T015
history:
- '2026-10-18: created by /spec-kitty.tasks'
agent_profile: python-pedro
authoritative_surface: src/spec_kitty_events/glossary.py
create_intent:
- tests/test_local_only_removed.py
execution_mode: code_change
owned_files:
- src/spec_kitty_events/glossary.py
- src/spec_kitty_events/__init__.py
- src/spec_kitty_events/persistence.py
- tests/test_glossary.py
- tests/test_glossary_reducer.py
- tests/test_local_only_deprecation.py
- tests/test_local_only_removed.py
- tests/unit/test_seven_event_contracts.py
tags: []
tracker_refs: []
---
# WP03: glossary 11.0, StepCheckpointed, package root

## Objective
Covers FR-007, FR-008, FR-009, FR-010 and NFR-002; the design is in plan.md §WP03. The authoritative field tables are in research.md §G. Core's builders are at `/home/user/spec-kitty/src/glossary/events.py:215-530`, with the models in `src/glossary/models.py`; read them, do not import them.

## Branch Strategy
Planning branch and merge target: `issue-120-decision-moment-reconciliation`. The lane worktree comes from `lanes.json`.

## Common rules
- Red-first per ADR 2026-07-17-1: write an issue-pinned `@pytest.mark.regression` repro (issue #120), show it RED, then convert it into focused unit tests.
- Keep complexity at 15 or below. `mypy --strict` and ruff must be clean on the touched files.
- Import nothing from core (C-001). Add no aliases (C-002).
- The schema drift check stays red until WP04 regenerates the schemas; do not regenerate schemas in this WP.

## Subtasks
- **T011:** the repro holds 8 static dicts, one per core glossary record, captured by running core's builders once by hand. They are written into the test as literals with a provenance comment (core SHA and builder name). `persistence.build_record` must accept each one; all are RED today.
- **T012:** re-model the payloads in `glossary.py`.
  - Add the nested models `GlossaryFinding`, `SenseRef`, `GlossaryActor`, `GlossaryProvenance` and `ScopeRef`, and the enums (severity, strictness, recommended_action including `proceed`, conflict_type, scope, update_type, resolution_mode, actor_type).
  - Set `ConfigDict(frozen=True, extra="forbid")` on the new models.
  - Add `STEP_CHECKPOINTED` and `StepCheckpointedPayload`, and update `GLOSSARY_EVENT_TYPES`.
  - Keep `GlossaryStrictnessSetPayload` unchanged.
  - Remove `SemanticConflictEntry`.
- **T013:** reducer (`:327+`) per FR-009. It sorts by `ordering.canonical_order_key`, and `ReducedGlossaryState` gains `checkpoints`.
- **T014:** `__init__.py`:
  - update the glossary imports and `__all__`;
  - re-export `reduce_decision_point_records`, `DECISION_POINT_TERMINAL_REOPEN` and `DecisionPointFoldError`;
  - remove `LOCAL_ONLY_EVENT_TYPES` (`:271-280` and `:1080`);
  - set `__version__ = "11.0.0"`;
  - drop the `LOCAL_ONLY` mention in `persistence.py`'s docstring.
- **T015:** rewrite `tests/test_glossary.py` and `tests/test_glossary_reducer.py` for the new shapes. Delete `tests/test_local_only_deprecation.py` and add `tests/test_local_only_removed.py`, which asserts `ImportError` and absence from `__all__`. Fix `tests/unit/test_seven_event_contracts.py` (FR-013 tests).

## Definition of Done
- The targeted tests pass, and `python -c "import spec_kitty_events"` works.
- mypy is clean on `src`.
- The allowed reds are the schema drift check and the glossary conformance fixtures; WP04 owns those. List them in the handoff.
