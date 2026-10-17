---
work_package_id: WP01
title: Subtask status vocabulary + skipped
dependencies: []
requirement_refs:
- FR-001
- FR-002
- FR-003
- FR-004
- FR-005
- FR-006
- FR-007
- NFR-001
- NFR-002
- C-001
- C-002
- C-003
planning_base_branch: feat/subtask-skipped-status
merge_target_branch: feat/subtask-skipped-status
branch_strategy: Planning artifacts for this mission were generated on feat/subtask-skipped-status. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into feat/subtask-skipped-status unless the human explicitly redirects the landing branch.
base_branch: kitty/mission-subtask-skipped-status-01M54C8D
base_commit: 2e2d85ca0c47061a0cfaf4b674aeb74dcaa67106
created_at: '2026-10-17T17:13:57.046332+00:00'
subtasks:
- T001
- T002
- T003
- T004
- T005
- T006
phase: Phase 1 - Implementation
history:
- at: '2026-10-17T17:09:00Z'
  actor: system
  action: Prompt generated via /spec-kitty.tasks-packages
agent_profile: python-pedro
authoritative_surface: src/spec_kitty_events/
create_intent: []
execution_mode: code_change
model: ''
owned_files:
- src/spec_kitty_events/diary.py
- src/spec_kitty_events/__init__.py
- src/spec_kitty_events/conformance/fixtures/status_diary/**
- src/spec_kitty_events/conformance/fixtures/manifest.json
- src/spec_kitty_events/conformance/test_status_diary_reducer_replay.py
- tests/test_status_diary_reducer.py
- pyproject.toml
- uv.lock
- README.md
- CHANGELOG.md
- COMPATIBILITY.md
role: implementer
tags: []
task_type: implement
tracker_refs: []
---

# Work Package Prompt: WP01 – Subtask status vocabulary + skipped

## ⚡ Do This First: Load Agent Profile

Use the `/spk-charter-profile-load` skill to load `python-pedro` (role:
implementer) before parsing the rest of this prompt. This is a Python library
change in `src/spec_kitty_events/`; apply TDD discipline and `mypy --strict`
hygiene.

---

## Objectives & Success Criteria

Give the diary contract a public **subtask status vocabulary** matching the
already-shipped `wp.innerstate.changed.v1` producer-contract schema, so every
consumer reads a `skipped` subtask through the shared package instead of
crashing on `Status(value)`. Ship as package minor **10.6.0**.

**Done when:**
- A `status.events.jsonl` / annotation carrying `{"subtasks": {"T001": "skipped"}}`
  parses via `parse_diary` / `WPInnerStateDelta.from_dict` with **0 `ValueError`**,
  and round-trips back to `skipped` on serialization. (SC-001)
- The input alias `not_applicable` is accepted on parse, normalized to
  `skipped`, and **never** serialized as `not_applicable`. (FR-002)
- A package predicate reports `done`/`skipped` terminal, everything else
  non-terminal. (SC-003)
- Every pre-existing fixture/diary-parse/reducer test passes with **0
  regressions**. (FR-006 / NFR-001 / SC-002)
- Package is `10.6.0` with a CHANGELOG entry and `check_version_not_amended`
  green. (FR-007 / SC-004)

## Context & Constraints

- Spec: `kitty-specs/subtask-skipped-status-01M54C8D/spec.md`
- Plan / data model / research / quickstart: same directory.
- Decision Moment `01M54CFGJ91EEZ0QNT3D26PSHZ`: `not_applicable` is an **input
  alias** → normalize to `skipped` at parse, never persist.
- Charter **Package Boundary**: the package stays independent of consumer repos.
  The downstream CLI re-export / backfill switch (spec-kitty#5953) is **Out of
  Scope** here.
- **No schema change**: `src/spec_kitty_events/producer_contract/data/wp.innerstate.changed.v1.schema.json`
  already lists `skipped`. This is a binding-catches-schema change; do not edit
  any `.schema.json`, so the schema-drift/generation checks stay green.
- On-wire envelope version stays `3.0.0`; this is a SemVer **minor**.
- `(str, Enum)`, **not** `StrEnum` — Python 3.10 support (mirror `Lane` at
  `src/spec_kitty_events/diary.py:148`).

## Grounding (verified line references on current main)

- `diary.py:148` — `class Lane(str, Enum)`; active members `GENESIS`,
  `PLANNED`, `CLAIMED`, `IN_PROGRESS`, `FOR_REVIEW`, `IN_REVIEW`, `APPROVED`,
  `DONE`, `BLOCKED`, `CANCELED` (plus the read-only sentinel `UNINITIALIZED`,
  which is **not** a subtask value).
- `diary.py:207` — `Status: TypeAlias = Lane` (this IS the current subtask-status
  alias).
- `diary.py:609` — `subtasks: Mapping[str, Status] | None = None` on
  `WPInnerStateDelta`.
- `diary.py:703-704` — `to_dict`: `d["subtasks"] = {sid: str(status) for ...}`.
- `diary.py:723-726` — `from_dict`: `subtasks = {str(sid): Status(value) for ...}`
  ← the parse site where the alias normalization goes.
- `diary.py:1373-1377` — reducer per-subtask fold (status-agnostic replace) —
  **no change needed**; `skipped` folds like any other value.
- `diary.py __all__` (~:95-118) exports `Status`, `WPInnerStateDelta`,
  `parse_diary` — add the new names here.
- Schema enum confirmed to contain `skipped`:
  `src/spec_kitty_events/producer_contract/data/wp.innerstate.changed.v1.schema.json`.
- Conformance replay fixtures: `src/spec_kitty_events/conformance/fixtures/status_diary/replay/`
  (pattern `<name>.jsonl` + `<name>_output.json`); driver
  `src/spec_kitty_events/conformance/test_status_diary_reducer_replay.py`.

## Subtasks & Detailed Guidance

### T001 — `SubtaskStatus` enum + `SubtaskValue` alias + repoint `Status` (FR-001, C-002)

In `src/spec_kitty_events/diary.py`, beside `Lane`:

1. Add `class SubtaskStatus(str, Enum)` with the ten **active** `Lane` values
   (`genesis, planned, claimed, in_progress, for_review, in_review, approved,
   done, blocked, canceled`) **plus** `SKIPPED = "skipped"`. Give it the same
   `__str__` returning `self.value` as `Lane`. Exclude `UNINITIALIZED` (it is a
   read sentinel, never a subtask value, and is absent from the schema enum).
2. Add `SubtaskValue: TypeAlias = SubtaskStatus` (published forward-looking
   alias).
3. Repoint the existing alias: `Status: TypeAlias = SubtaskStatus` (was `Lane`).
4. Add `SubtaskStatus`, `SubtaskValue` to diary `__all__`.
5. **Non-drift guard** (unit assertion, in the test file — see T005):
   `{m.value for m in SubtaskStatus} == {m.value for m in Lane} - {"uninitialized"} | {"skipped"}`.

### T002 — terminal-subtask predicate (FR-003)

Add a package-exported `def is_terminal_subtask(value: SubtaskStatus) -> bool`
returning `True` iff `value in {SubtaskStatus.DONE, SubtaskStatus.SKIPPED}`.
Accept a `SubtaskStatus` (callers already hold one); keep it a pure predicate.
Export via `__all__`. (A module-level frozenset
`TERMINAL_SUBTASK_STATUSES = frozenset({DONE, SKIPPED})` backing the predicate
is fine and lets consumers import the set directly.)

### T003 — retype `subtasks` + `not_applicable` normalization (FR-002, FR-004)

1. Retype `WPInnerStateDelta.subtasks` to `Mapping[str, SubtaskStatus] | None`
   (`diary.py:609`).
2. At the parse site (`from_dict`, `diary.py:~726`), normalize **before** the
   enum lookup: if the raw value equals `"not_applicable"`, substitute
   `"skipped"`, then `SubtaskStatus(value)`. Any other unknown value must still
   raise (fail closed) — do not broaden the except.
3. `to_dict` (`diary.py:703-704`) is unchanged (`str(status)` already emits the
   canonical value); confirm it emits `skipped` for a normalized
   `not_applicable`.
4. Reducer fold (`diary.py:1373-1377`) is status-agnostic — **leave it**; add a
   one-line comment noting `skipped` folds via the existing per-subtask replace.

### T004 — conformance fixture with a skipped subtask (FR-005)

Add a replay fixture under
`src/spec_kitty_events/conformance/fixtures/status_diary/replay/` following the
existing `<name>.jsonl` + `<name>_output.json` pattern (e.g.
`status_diary_skipped_subtask.jsonl` + `_output.json`): a minimal diary whose
`WPInnerStateDelta` annotation carries at least one `skipped` subtask (and,
ideally, one `not_applicable` input that reduces to `skipped` in the expected
output). Register it the way the suite discovers fixtures (inspect
`test_status_diary_reducer_replay.py` — if it globs the directory, no code
change beyond the files; if it enumerates names, add the new name).

### T005 — round-trip + predicate test (FR-005, FR-003)

In `tests/test_status_diary_reducer.py` (prefer strengthening an existing test
over a brand-new near-duplicate):

- Parse an annotation with `{"subtasks": {"T001": "skipped"}}` →
  `SubtaskStatus.SKIPPED`; re-serialize → `"skipped"`.
- Parse `{"subtasks": {"T002": "not_applicable"}}` → `SubtaskStatus.SKIPPED`;
  re-serialize → `"skipped"` (assert it is **never** `"not_applicable"`).
- `is_terminal_subtask`: `DONE`/`SKIPPED` → `True`; `IN_PROGRESS` → `False`.
- The T001 non-drift assertion.

Test economy: one focused test per distinct failure mode; no inert self-compares
or shape pins. The skipped fixture (T004) is the non-vacuity positive control
for the backward-compat ratchet.

### T006 — version bump 10.6.0 + CHANGELOG (FR-007, SC-004)

Bump **10.5.3 → 10.6.0** in: `pyproject.toml`, `src/spec_kitty_events/__init__.py`
(`__version__`), `README.md`, `uv.lock` (the `spec-kitty-events` package entry).
Add a `## 10.6.0` section to `COMPATIBILITY.md` and a `## [10.6.0] - 2026-10-17`
**Added** section to `CHANGELOG.md` describing the user-facing impact (consumers
no longer crash reading a `skipped` subtask; new `SubtaskStatus` vocabulary +
terminal predicate). Then confirm `check_version_not_amended` passes.

## Test Strategy & Commands

```bash
# Blast-radius baseline + the new tests
uv run --frozen pytest tests/test_status_diary_reducer.py -q
# Packaged conformance gate (required every PR)
uv run pytest --pyargs spec_kitty_events.conformance -q
# Types + lint
uv run mypy --strict src/spec_kitty_events/diary.py
ruff check . && ruff format --check .
# Version gate
uv run python bin/check_version_not_amended.py   # or the repo's invocation
```

Do **not** run the whole-repo suite here — that is the CI agent's job. Record
the exact commands and pass/fail counts in the completion notes.

## Definition of Done

- All six subtasks complete; SC-001..SC-004 demonstrably met.
- `mypy --strict` clean on `diary.py`; ruff clean.
- Conformance gate green including the new skipped fixture; existing suite 0
  regressions.
- Version is 10.6.0 across all pinned files; CHANGELOG + COMPATIBILITY updated;
  `check_version_not_amended` green.
- No `.schema.json` edited; no `kitty-specs/` path modified by this code WP.

## Risks & Reviewer Guidance

- **Member drift** `SubtaskStatus` vs `Lane` → the non-drift assertion must fail
  closed if they diverge; reviewer: verify it goes red if a `Lane` member is
  added without updating `SubtaskStatus`.
- **`not_applicable` leak** → reviewer: confirm normalization is at the single
  parse boundary and that re-serialization can never emit `not_applicable`.
- **Backward compat** → reviewer: confirm every existing fixture still reduces
  identically and `Status` remains importable (now aliasing `SubtaskStatus`).
- **Fail-closed** → reviewer: an unknown subtask value still raises.
