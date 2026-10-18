---
work_package_id: WP01
title: 'Envelope clock: optional lamport_clock, canonical ordering'
dependencies: []
requirement_refs:
- FR-001
- FR-002
- FR-002a
- FR-002b
- FR-003
- C-003
planning_base_branch: issue-120-decision-moment-reconciliation
merge_target_branch: issue-120-decision-moment-reconciliation
branch_strategy: Planning artifacts for this mission were generated on issue-120-decision-moment-reconciliation. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into issue-120-decision-moment-reconciliation unless the human explicitly redirects the landing branch.
base_branch: kitty/mission-decision-glossary-contract-reconciliation-01M57TCP
base_commit: 99dbe12d37cd70a130e5f9da81b9b330af9a0cd0
created_at: '2026-10-18T15:42:15.423429+00:00'
subtasks:
- T001
- T002
- T003
- T004
- T005
history:
- '2026-10-18: created by /spec-kitty.tasks'
agent_profile: python-pedro
authoritative_surface: src/spec_kitty_events/ordering.py
create_intent:
- src/spec_kitty_events/ordering.py
- tests/test_ordering.py
execution_mode: code_change
owned_files:
- src/spec_kitty_events/ordering.py
- src/spec_kitty_events/models.py
- src/spec_kitty_events/status.py
- src/spec_kitty_events/storage.py
- src/spec_kitty_events/conflict.py
- src/spec_kitty_events/merge.py
- src/spec_kitty_events/lifecycle.py
- src/spec_kitty_events/work_replay.py
- src/spec_kitty_events/mission_next.py
- src/spec_kitty_events/collaboration.py
- src/spec_kitty_events/dossier.py
- src/spec_kitty_events/strict.py
- tests/test_ordering.py
tags: []
tracker_refs: []
---
# WP01: envelope clock

## Objective
Covers FR-001, FR-002, FR-002a, FR-002b and FR-003 (spec.md); the design is in plan.md §WP01.

## Branch Strategy
Planning branch and merge target: `issue-120-decision-moment-reconciliation`. The lane worktree comes from `lanes.json`.

## Common rules
- Red-first per ADR 2026-07-17-1: write an issue-pinned `@pytest.mark.regression` repro (issue #120), show it RED, then convert it into focused unit tests.
- Keep complexity at 15 or below. `mypy --strict` and ruff must be clean on the touched files.
- Import nothing from core (C-001). Add no aliases (C-002).
- The schema drift check stays red until WP04 regenerates the schemas; do not regenerate schemas in this WP.

## Subtasks
- **T001:** the repro builds `Event(...)` without `lamport_clock` and expects acceptance, and sorts a mixed list with `status_event_sort_key` and expects no TypeError. Both are RED today.
- **T002:** in `models.py:120`, the field becomes `Optional[int] = Field(None, ge=0, ...)`. Update `__repr__` (`:163`). New `ordering.py` contains:
  - `canonical_order_key(event) -> tuple[int, int, str, str]`, which returns `(0 if clocked else 1, clock or 0, timestamp.isoformat(), event_id)`;
  - `is_clocked(event) -> bool`;
  - `node_order_key(event)` for the storage adapters, which returns `(0|1, clock or 0, node_id, event_id)`.
- **T003:** `status.status_event_sort_key` (`status.py:752`) delegates to the canonical key. So do the sorts in `mission_next.py:316`, `collaboration.py:439`, `dossier.py:300` and `lifecycle.py:655`; find each site with grep. The storage adapters (`storage.py:156,160`) use `node_order_key`. Leave `glossary.py` alone (WP03).
- **T004:**
  - `conflict.py`: an unclocked event is never concurrent with anything.
  - `merge.py:45`: unclocked input raises the existing ValueError family, with a clear message.
  - `lifecycle.py:704-707` and `status.py:885-890`: grouping puts each unclocked event in its own singleton group.
- **T005:** `work_replay` (`:70,96`) raises `ValueError("work replay requires lamport_clock (LW-10)")` on an unclocked event. Confirm `strict.py:92` still requires the field; it should need no change, so pin that with a test.
- **Tests:** `tests/test_ordering.py` has a test for every site above, including mixed streams and the singleton grouping.

## Definition of Done
- The targeted tests pass, along with `tests/test_total_order.py`, `tests/test_status_diary_reducer.py` and `make test-fast`. The schema drift test is the only allowed red; list it in the handoff.
- mypy and ruff are clean.
