---
work_package_id: WP02
title: ledger_append fixtures + packaged runner
dependencies:
- WP01
requirement_refs:
- FR-007
- FR-008
- FR-010
- NFR-001
- C-003
planning_base_branch: issue-118-ledger-write-contract-seam
merge_target_branch: issue-118-ledger-write-contract-seam
branch_strategy: Planning artifacts for this mission were generated on issue-118-ledger-write-contract-seam. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into issue-118-ledger-write-contract-seam unless the human explicitly redirects the landing branch.
base_branch: kitty/mission-ledger-write-contract-seam-01M57P5Q
base_commit: 8c780bc4ec793375a4af33a0f4e82514e744f9ec
created_at: '2026-10-18T14:34:36.357299+00:00'
subtasks:
- T006
- T007
- T008
- T009
history:
- '2026-10-18: created by /spec-kitty.tasks (semi-auto drive)'
agent_profile: python-pedro
authoritative_surface: src/spec_kitty_events/conformance/
create_intent:
- src/spec_kitty_events/conformance/test_ledger_append.py
execution_mode: code_change
owned_files:
- src/spec_kitty_events/conformance/loader.py
- src/spec_kitty_events/conformance/test_ledger_append.py
- src/spec_kitty_events/conformance/test_pyargs_entrypoint.py
- src/spec_kitty_events/conformance/fixtures/manifest.json
- src/spec_kitty_events/conformance/fixtures/ledger_append/**
tags: []
tracker_refs: []
---
# WP02: `ledger_append` fixtures and packaged runner

## Objective
Pin the WP01 bytes in language-neutral fixtures shipped in the wheel (FR-007, FR-008, FR-010, NFR-001, SC-002).

## Branch Strategy
Planning branch and merge target: `issue-118-ledger-write-contract-seam`. Lane worktree from `lanes.json`.

## Subtasks
- **T006:** Add `"ledger_append"` to `_VALID_CATEGORIES` and `_SPECIAL_FIXTURE_TYPES` in `conformance/loader.py`. Add a loader helper `load_ledger_append_cases()` that returns the raw documents and their manifest metadata.
- **T007:** Valid documents under `fixtures/ledger_append/valid/`, following the shape in plan.md §Fixture category:
  - an `event`;
  - a core-shaped flat `GlossaryScopeActivated` dict;
  - a `MissionReopened` dict and a `FollowUpRecorded` dict, built from the existing valid fixtures' payloads;
  - a `GlossarySenseUpdated` dict;
  - a `model`;
  - non-ASCII text;
  - a float `confidence` (0.95 and 1e-05);
  - an append onto an `existing_ledger`.

  Generate each `expected_line` once with WP01's `serialize_record`, then hand-inspect it.
- **T008:** Invalid documents under `fixtures/ledger_append/invalid/`: an unknown type, a missing `event_type`, an invalid payload, an unregistered model name, NaN (written as an `input_kind: "dict"` document with a `"nan_field"` directive the runner turns into `float("nan")`, because JSON cannot hold NaN), and a torn tail (an `existing_ledger` with no trailing LF).
- **T009:** Add manifest entries (`fixture_type: "ledger_append"`, `min_version: "10.8.0"`). In the packaged runner `conformance/test_ledger_append.py`:
  - for valid cases, assert the file bytes equal `existing_ledger + expected_line`;
  - for invalid cases, assert the error type and that the file is byte-unchanged;
  - add a guard that the category is non-empty.

  Make sure `test_pyargs_entrypoint.py` keeps these documents out of the generic event gate, and add a regression guard mirroring the `zeitgeist_attrs` one.

## Definition of Done
- `uv run pytest --pyargs spec_kitty_events.conformance` is green, with the new cases collected.
- `uv run pytest tests/test_fixture_determinism.py tests/unit/test_fixtures.py` is green.
- mypy and ruff are clean.
