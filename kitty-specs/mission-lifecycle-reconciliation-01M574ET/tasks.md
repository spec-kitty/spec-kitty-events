# Tasks: Mission-lifecycle event reconciliation

**Mission**: mission-lifecycle-reconciliation-01M574ET
**Branch**: `issue-119-mission-lifecycle-reconciliation` (planning base) → merges to `main`
**Spec**: [spec.md](./spec.md) · **Plan**: [plan.md](./plan.md)

## Subtask Index

| ID | Description | WP | Parallel |
|---|---|---|---|
| T001 | Add `ReviewerSelfApproval` payload model to `project_lifecycle` (type constant + registry membership), mirroring the producer-contract row shape | WP01 | |
| T002 | Register all three events in the conformance validator dispatch so `validate_event` gates them (schema-backed, fail-closed) | WP01 | |
| T003 | Generate committed JSON Schemas for the three events via `schemas/generate.py` | WP01 | |
| T004 | Add valid + invalid conformance fixtures for `MissionReopened`, `FollowUpRecorded`, `ReviewerSelfApproval` | WP01 | |
| T005 | Verify: schema-drift check, packaged conformance gate, mypy --strict, ruff; additivity diff (additions only, import set unchanged) | WP01 | |

## Work Packages

### WP01 — Mission-lifecycle event schema + fixture reconciliation

- **Goal**: Every mission-lifecycle event core currently emits has a committed JSON Schema and at least one valid and one invalid fixture, validated fail-closed, with no existing contract modified.
- **Priority**: P1 (the whole mission).
- **Independent test**: `validate_event` accepts a valid payload and refuses an invalid one for each of `MissionReopened`, `FollowUpRecorded`, `ReviewerSelfApproval`, using committed fixtures; the schema-drift check and packaged conformance gate are green.
- **Included subtasks**: T001, T002, T003, T004, T005.
- **Requirement refs**: FR-001, FR-002, FR-003, FR-004, FR-005, FR-006, FR-007, FR-008, FR-009, NFR-001, C-001, C-002.
- **Implementation sketch**:
  1. Read the authoritative shapes (research.md / data-model.md) — `MissionReopened`/`FollowUpRecorded` models exist in `lifecycle.py`; `ReviewerSelfApproval` field set from `producer_contract/fixtures/rows/valid/wp.reviewer.selfapproved.v1.json` + core's emit.
  2. Add the `ReviewerSelfApproval` model to `project_lifecycle.py` (frozen, `extra="forbid"`), its type constant and registry membership.
  3. Register the three in the validator dispatch (`conformance/validators.py`) so they are schema-backed and fail-closed.
  4. Generate the three JSON Schemas via `schemas/generate.py`; add valid + invalid fixtures to the packaged corpus.
  5. Verify: `tests/integration/test_schema_drift.py`, `uv run pytest --pyargs spec_kitty_events.conformance`, mypy --strict, ruff; prove additions-only with a diff vs the pre-mission commit.
- **Dependencies**: none.
- **Risks**: `ReviewerSelfApproval` field drift between core's emit and the producer-contract row (mirror the producer, record any discrepancy in research.md); regenerating schemas must not alter existing schema files (additivity).
- **Prompt**: [tasks/WP01-lifecycle-event-schema-fixtures.md](./tasks/WP01-lifecycle-event-schema-fixtures.md)

## Notes

- Out of scope (do not touch): `LOCAL_ONLY_EVENT_TYPES` (Mission A #118); `SignificanceEvaluated`/`DecisionTimeoutExpired` (deferred, operator decision).
- Subtask completion is event-sourced via `spec-kitty agent tasks mark-status`.
