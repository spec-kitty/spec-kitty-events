---
work_package_id: WP02
title: DecisionPoint two-pass reopen fold and ledger-records fold
dependencies:
- WP01
requirement_refs:
- FR-004
- FR-005
- FR-006
- NFR-001
planning_base_branch: issue-120-decision-moment-reconciliation
merge_target_branch: issue-120-decision-moment-reconciliation
branch_strategy: Planning artifacts for this mission were generated on issue-120-decision-moment-reconciliation. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into issue-120-decision-moment-reconciliation unless the human explicitly redirects the landing branch.
base_branch: kitty/mission-decision-glossary-contract-reconciliation-01M57TCP
base_commit: 76864c882ca717d4e9562a7d887b7b6a364a6b92
created_at: '2026-10-18T15:51:37.860929+00:00'
subtasks:
- T006
- T007
- T008
- T009
- T010
history:
- '2026-10-18: created by /spec-kitty.tasks'
agent_profile: python-pedro
authoritative_surface: src/spec_kitty_events/decisionpoint.py
create_intent:
- tests/test_decisionpoint_records_fold.py
execution_mode: code_change
owned_files:
- src/spec_kitty_events/decisionpoint.py
- tests/test_decisionpoint_reducer.py
- tests/test_decisionpoint_records_fold.py
tags: []
tracker_refs: []
---
# WP02: DecisionPoint reopen and records fold

## Objective
Covers FR-004, FR-005, FR-006 and NFR-001; the design is in plan.md §WP02. Core's reference behaviour is `/home/user/spec-kitty/src/specify_cli/decisions/index_fold.py:87,215-280`; read it, do not import it.

## Branch Strategy
Planning branch and merge target: `issue-120-decision-moment-reconciliation`. The lane worktree comes from `lanes.json`.

## Common rules
- Red-first per ADR 2026-07-17-1: write an issue-pinned `@pytest.mark.regression` repro (issue #120), show it RED, then convert it into focused unit tests.
- Keep complexity at 15 or below. `mypy --strict` and ruff must be clean on the touched files.
- Import nothing from core (C-001). Add no aliases (C-002).
- The schema drift check stays red until WP04 regenerates the schemas; do not regenerate schemas in this WP.

## Subtasks
- **T006:** the repro is a stream of Opened, Resolved(deferred) and Resolved(resolved), built from the existing decisionpoint fixture payloads. It must reduce to RESOLVED. Today it reduces to DEFERRED with `invalid_transition`.
- **T007:** add `DECISION_POINT_TERMINAL_REOPEN` and `_select_effective_terminal` (pure).
  - Add `unfoldable_terminal_combination` to the anomaly literal (`decisionpoint.py:145`).
  - Add `reopened_from_deferred: bool = False` to `ReducedDecisionPointState`.
  - Add `DecisionPointFoldError(SpecKittyEventsError)`.
- **T008:** pass 2 (`:619-713`):
  - the reopen-point deferred event is a non-terminal state entry, so the state returns to the pre-terminal one (the last one before the deferred);
  - the effective resolve sets the projection and `last_state_entered_at`;
  - an Overridden is valid only after the effective resolve by the canonical key, otherwise `invalid_transition`;
  - extra Resolved events are `unfoldable_terminal_combination`, and the first Resolved stands.
  - Sort with `ordering.canonical_order_key`.
- **T009:** `reduce_decision_point_records(records, *, strict=True)`, per plan.md. Export it from `decisionpoint`; WP03 re-exports it at the root.
- **T010:** tests in `tests/test_decisionpoint_reducer.py` and the new `tests/test_decisionpoint_records_fold.py` cover:
  - both orders, two deferred, deferred plus canceled, three Resolved;
  - Overridden after, between and before;
  - Discussing after a deferred;
  - non-decision records skipped;
  - strict and non-strict errors;
  - a hypothesis shuffle property (at least 100 examples) asserting byte-identical `model_dump` output.
  Existing reducer tests must stay green unchanged.

## Definition of Done
Targeted tests and `tests/test_decisionpoint_conformance.py` are green, and mypy and ruff are clean.
