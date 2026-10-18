---
work_package_id: WP01
title: persistence module + unit tests
dependencies: []
requirement_refs:
- FR-001
- FR-002
- FR-003
- FR-004
- FR-005
- FR-006
- NFR-002
- NFR-003
- NFR-004
- C-001
- C-005
planning_base_branch: issue-118-ledger-write-contract-seam
merge_target_branch: issue-118-ledger-write-contract-seam
branch_strategy: Planning artifacts for this mission were generated on issue-118-ledger-write-contract-seam. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into issue-118-ledger-write-contract-seam unless the human explicitly redirects the landing branch.
base_branch: kitty/mission-ledger-write-contract-seam-01M57P5Q
base_commit: 6a688db4a0d5d53c77c8b8a22287fb3adbe0f60e
created_at: '2026-10-18T14:27:48.892544+00:00'
subtasks:
- T001
- T002
- T003
- T004
- T005
history:
- '2026-10-18: created by /spec-kitty.tasks (semi-auto drive)'
agent_profile: python-pedro
authoritative_surface: src/spec_kitty_events/persistence.py
create_intent:
- src/spec_kitty_events/persistence.py
- tests/test_persistence.py
execution_mode: code_change
owned_files:
- src/spec_kitty_events/persistence.py
- tests/test_persistence.py
tags: []
tracker_refs: []
---
# WP01: persistence module and unit tests

## Objective
Create `src/spec_kitty_events/persistence.py`, following plan.md §Design / Module, and its unit tests. It covers FR-001–FR-006, FR-010 (via the registry), NFR-002, NFR-003 and NFR-004.

## Branch Strategy
Planning branch and merge target: `issue-118-ledger-write-contract-seam`. The execution worktree is the lane allocated from `lanes.json`.

## Subtasks
- **T001 (red-first):** In `tests/test_persistence.py`, add an issue-pinned `@pytest.mark.regression` test (issue #118) that imports `append_event` from `spec_kitty_events.persistence` and appends a valid `MissionReopened` dict. Run it RED before creating the module, and record the RED output in the commit message. Once the module exists, convert it into a focused unit test with no `regression` marker.
- **T002:** `LedgerAppendError(SpecKittyEventsError)`, `LedgerValidationError(LedgerAppendError, ValueError)` and `LedgerTornTailError(LedgerAppendError)`, each with a docstring.
- **T003:** `build_record(event) -> dict[str, Any]`:
  - Dispatch on the input kind through small helpers (complexity ≤ 15).
  - Build the payload-class → `event_type` reverse map lazily from `conformance.validators._EVENT_TYPE_TO_MODEL`, keeping only classes that map to exactly one type.
  - An envelope (an `Event`, or a dict with a dict `payload` and an `aggregate_id`) is validated as `"Event"` plus its payload's type. A flat dict is validated as its own `event_type`.
  - Use `validate_event(..., strict=True)`.
  - The JSON-native walk refuses non-JSON-native values, non-finite floats and non-str keys.
- **T004:** `serialize_record(record) -> bytes` is exactly `json.dumps(record, sort_keys=True, ensure_ascii=True, allow_nan=False).encode("ascii") + b"\n"`. `append_event(event, path)` works in this order:
  1. Build and serialise the record (no I/O yet).
  2. Run `mkdir -p` on the parent directory.
  3. Open with `os.open(O_WRONLY|O_APPEND|O_CREAT|getattr(os,"O_BINARY",0), 0o644)`.
  4. Check the tail through a separate read-only open (`rb`, seek to −1) when the size is above 0; a last byte other than LF raises `LedgerTornTailError`.
  5. Write with a single `os.write`; a short write raises `LedgerAppendError`.
  6. Close in a `finally`.
- **T005:** Write the tests:
  - A test for every refusal branch, with the file byte-unchanged or absent.
  - A test for each of the three kinds.
  - Byte equality with `json.dumps(d, sort_keys=True, default=str) + "\n"` for a JSON-native core-shaped glossary dict.
  - Two appends in order.
  - A directory as the target path raises.
  - A hypothesis property (≥ 200 examples) asserting no partial write.
  - A benchmark that prints the median, ungated.

## Definition of Done
- `uv run pytest tests/test_persistence.py` passes.
- `uv run mypy --strict src/spec_kitty_events/persistence.py` reports nothing, and `ruff check` and `ruff format --check` are clean on both files.
- Coverage of `persistence.py` is at least 95 %.
- No imports from core, saas, tracker or runtime.

## Reviewer guidance
Check that no I/O happens before validation completes, that the byte rule matches FR-005 exactly, and that the T001 repro was RED first and has been converted.
