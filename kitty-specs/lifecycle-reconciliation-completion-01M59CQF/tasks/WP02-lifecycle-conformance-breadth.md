---
work_package_id: WP02
title: Lifecycle conformance breadth, replay and WPStatusChanged lanes
dependencies:
- WP01
requirement_refs:
- FR-008
- FR-009
- FR-010
- FR-011
- NFR-001
- NFR-004
- C-001
- C-003
planning_base_branch: issue-119-lifecycle-reconciliation-completion
merge_target_branch: issue-119-lifecycle-reconciliation-completion
branch_strategy: Planning artifacts were generated on issue-119-lifecycle-reconciliation-completion; completed changes must merge back into issue-119-lifecycle-reconciliation-completion. Execution worktrees are allocated per computed lane from lanes.json.
subtasks:
- T008
- T009
- T010
- T011
- T012
- T013
phase: Phase 2 - Lifecycle evidence
agent: ''
history:
- '2026-10-19: created by /spec-kitty.tasks'
agent_profile: python-pedro
authoritative_surface: src/spec_kitty_events/conformance/fixtures/lifecycle/
create_intent:
- src/spec_kitty_events/conformance/test_lifecycle_reducer_replay.py
- tests/test_lifecycle_conformance.py
execution_mode: code_change
owned_files:
- src/spec_kitty_events/conformance/fixtures/lifecycle/**
- src/spec_kitty_events/conformance/test_lifecycle_reducer_replay.py
- tests/test_lifecycle_conformance.py
- tests/unit/test_zeitgeist_attrs.py
role: implementer
---

# WP02 - Lifecycle conformance breadth, replay and WPStatusChanged lanes

## ⚡ Do This First: Load Agent Profile

Load profile `python-pedro` with `/spk-charter-profile-load`, then `spec-kitty charter context --action implement`. Read the mission's spec.md, plan.md, research.md (R-3..R-6) and `research/mission-brief.md`.

## Objective

Close #119's evidence gaps and #62: invalid fixtures for the 18 lifecycle types without one (FR-008), a replay stream that folds through `reduce_lifecycle_events` to a committed golden (FR-009), summary on rework and done (FR-010), and valid WPStatusChanged fixtures for the uncovered lanes (FR-011). Also NFR-001, NFR-004, C-001, C-003, C-007.

## Branch Strategy

Base and merge target `issue-119-lifecycle-reconciliation-completion`; lane worktree from `lanes.json`. Depends on WP01 (the `lifecycle` category in `loader.py` and the shared `manifest.json`).

```bash
spec-kitty agent action implement WP02 --agent <name> --mission lifecycle-reconciliation-completion-01M59CQF
```

Out-of-map edit (justified, recorded here): this WP appends entries to `src/spec_kitty_events/conformance/fixtures/manifest.json`, which WP01 owns; the edit is append-only and WP02 runs after WP01, so no concurrent conflict is possible.

## STOP conditions

- Any edit to an existing test function, parametrize list or count pin (in particular `tests/unit/test_fixtures.py` 33/18 and `tests/test_zeitgeist_attrs_conformance.py` 78/51/27). That is why new fixtures go into the new `lifecycle` category, never into `events/` or `zeitgeist_attrs/`.
- An invalid fixture can only be made to fail by a rule the model does not already enforce (that would mean a new rule, i.e. a changed accept boundary).
- The reducer's output for the stream reveals behaviour that contradicts its docstring.

## Subtasks

### T008 - Invalid fixtures for 18 lifecycle types [P]

Types: MissionCreated, MissionStarted, MissionClosed, MissionCompleted, MissionCancelled, MissionOriginBound, PhaseEntered, ReviewRollback, SpecifyStarted, SpecifyCompleted, PlanStarted, PlanCompleted, TasksStarted, TasksCompleted, WPAssigned, HistoryAdded, ErrorLogged, DependencyResolved.

For each: start from its valid fixture in `conformance/fixtures/events/valid/<name>.json`, read its model (in `lifecycle.py` / `project_lifecycle.py` or wherever `_EVENT_TYPE_TO_MODEL` points), and produce at least one invalid variant that breaks a rule the model already has: a missing required field (preferred), an extra key where `extra="forbid"`, or an out-of-enum value. Name `lifecycle/invalid/<type_snake>_<violation>.json`. Confirm by calling `validate_event(payload, event_type)` that each is refused and that the refusal names the intended field. Register each in `manifest.json` with `expected_result: "invalid"`, `min_version: "11.1.0"`.

### T009 - Valid WPStatusChanged lane fixtures [P]

`lifecycle/valid/wp_status_changed_<lane>.json` for: `genesis -> planned` (genesis as from_lane; legal edges only), `-> canceled` from genesis, `for_review -> in_review`, `in_review -> approved`, `* -> blocked`, `* -> canceled`. Use only transitions `StatusTransitionPayload` accepts today (check its validators: force/reason rules, done evidence). Together with the existing fixture, every one of the 10 `status.Lane` members must appear as a from_lane or to_lane in some valid fixture (SC-004); add a test in T012 that asserts this coverage.

### T010 - Summary on rework and done (#62)

- In `tests/unit/test_zeitgeist_attrs.py` add NEW test functions after `test_wp_status_changed_summary_rides_as_the_bounded_attr` (do not edit it): (a) `in_review -> in_progress` with a summary (rework), (b) `in_review -> planned` (rejection, the canonical shape), (c) `-> done` with a summary and valid `DoneEvidence`. Each asserts the same projection properties as the existing test (bounded, normalized `summary`; no raw `reason`; decode round-trip).
- Fixtures: `lifecycle/valid/wp_status_changed_summary_rework.json`, `..._summary_done.json`.

### T011 - Replay stream and golden

- `lifecycle/replay/mission_lifecycle.jsonl`: complete Event envelopes (one per line) for one mission covering the types `reduce_lifecycle_events` handles (read `lifecycle.py:651-761`), including one duplicated event_id and one unknown event type; deterministic ids and timestamps.
- `lifecycle/replay/mission_lifecycle_output.json`: the reducer's `ReducedMissionState` dump in the `reducer_output` format used by `status_diary` (see `load_reducer_output`).
- Manifest entries with `fixture_type: "replay_stream"` / `"reducer_output"` and ids `lifecycle-replay-mission-lifecycle` / `lifecycle-replay-mission-lifecycle-output`.
- Derive the golden by running the reducer, then review it field by field against the reducer's documented semantics before committing.

### T012 - Tests

- `src/spec_kitty_events/conformance/test_lifecycle_reducer_replay.py` (packaged, pattern `test_status_diary_reducer_replay.py`): fold the stream, compare with the golden; also assert that permuting the lines gives the same result and that removing the unknown-type line does not change it (C-003).
- `tests/test_lifecycle_conformance.py`: load `lifecycle` fixtures; every valid accepted, every invalid refused; the FR-008 type list is fully covered by invalid fixtures; the lane coverage assertion from T009.

### T013 - Gates

```bash
uv run --frozen pytest tests/test_lifecycle_conformance.py tests/unit/test_zeitgeist_attrs.py tests/integration/test_lifecycle_replay.py tests/unit/test_fixtures.py tests/test_zeitgeist_attrs_conformance.py tests/unit/test_lifecycle.py -q
uv run --frozen pytest --pyargs spec_kitty_events.conformance -q
make test-fast
uv run --frozen mypy --strict src && uv run --frozen ruff check . && uv run --frozen ruff format --check .
```

## Definition of Done

FR-008..FR-011 met; gates green; diff is additions only plus manifest appends; no existing fixture changes verdict.

## Reviewer guidance

Check each invalid fixture fails for the stated reason and not for an unrelated one. Check the golden by hand against the reducer docstring. Check no existing test was edited.
