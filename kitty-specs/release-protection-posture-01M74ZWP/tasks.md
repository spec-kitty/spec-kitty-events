# Tasks: Release-protection posture for the service-v* line

**Mission**: `release-protection-posture-01M74ZWP` (#239, closes #221)
**Branch**: `issue-239-release-protection` (planning base and merge target; one PR to `main`)
**Inputs**: [spec.md](./spec.md), [plan.md](./plan.md), [research.md](./research.md), [data-model.md](./data-model.md), [contracts/audit-cli-and-expected-settings.md](./contracts/audit-cli-and-expected-settings.md), [quickstart.md](./quickstart.md)

Subtask completion is event-sourced: record it with `spec-kitty agent tasks mark-status Txxx --status done`. The rows below are references, not checkboxes.

## Subtask Index

| ID | Description | WP | Parallel |
|---|---|---|---|
| T001 | Capture the live settings and releases as test fixtures | WP01 | |
| T002 | Normalise live settings; diff against the expected file; commit the expected file | WP01 | |
| T003 | Release classification and provenance checks with an injected verifier | WP01 | |
| T004 | `gh` adapter (paginated reads, download → digest → verify → delete), errors, CLI, step summary | WP01 | |
| T005 | Tests: AC 2 (snapshot match, mutate every leaf), AC 3 (release cases), errors, determinism, CLI | WP01 | |
| T006 | ruff, ruff format, mypy --strict on the script | WP01 | |
| T007 | The audit workflow `release-protection-audit.yml` | WP02 | |
| T008 | Workflow-shape tests (AC 1) with negative controls, actionlint | WP02 | |
| T009 | Mention the audit in `service-release.yml`'s header, `AGENTS.md` and `CLAUDE.md` | WP02 | [P] |
| T010 | Guide section: vector → control / accepted-risk table with sign-offs | WP03 | |
| T011 | Guide: run, read and update the audit; consumer check cross-reference; frontmatter `updated` | WP03 | |
| T012 | Changelog fragment `239-release-protection-audit.md` | WP03 | [P] |
| T013 | Docs gates: `npm run validate`, then `scripts/docs_index.py --clean`; changelog check/guard | WP03 | |

## Phase 1 — Foundational

### WP01 — Audit script: settings drift and release provenance (P1)

**Prompt**: [tasks/WP01-audit-script.md](./tasks/WP01-audit-script.md)
**Goal**: `scripts/release/release_protection_audit.py` and `scripts/release/release-protection-expected.json`, driven by fixtures, with the AC 2 and AC 3 tests.
**Independent test**: `uv run pytest tests/test_release_protection_audit.py` is green; mutating any leaf of the expected file fails exactly one comparison; the rc fixtures pass with no warning.
**Requirements**: FR-004, FR-005, FR-006, FR-007, FR-008, FR-009, FR-010, FR-011, NFR-002, NFR-004, C-004
**Dependencies**: none

T001 Capture the live settings and releases as test fixtures (WP01)
T002 Normalise live settings; diff against the expected file; commit the expected file (WP01)
T003 Release classification and provenance checks with an injected verifier (WP01)
T004 `gh` adapter, errors, CLI, step summary (WP01)
T005 Tests: AC 2, AC 3, errors, determinism, CLI (WP01)
T006 ruff, ruff format, mypy --strict (WP01)

**Risks**: a verifier that turns an infrastructure error into a "warning" on a prerelease (FR-010); normalisation that drops a real setting (each leaf test guards it); pagination missed (`--paginate` + `--slurp`).

## Phase 2 — Story phases

### WP02 — Audit workflow and its guard tests (P1)

**Prompt**: [tasks/WP02-audit-workflow.md](./tasks/WP02-audit-workflow.md)
**Goal**: `.github/workflows/release-protection-audit.yml` (schedule + dispatch, `contents: read`, SHA-pinned) and `tests/test_release_protection_workflow.py` (AC 1), plus the mentions in `service-release.yml`, `AGENTS.md`, `CLAUDE.md`.
**Independent test**: the workflow tests pass on the committed workflow and fail on each mutated copy (push trigger, write permission, tag-pinned action).
**Requirements**: FR-001, FR-002, FR-003, FR-015, NFR-001, C-005
**Dependencies**: WP01

T007 The audit workflow (WP02)
T008 Workflow-shape tests (AC 1) with negative controls, actionlint (WP02)
T009 Mentions in `service-release.yml`, `AGENTS.md`, `CLAUDE.md` (WP02)

**Risks**: `on:` parsed by PyYAML as `True` (reuse the `_on` pattern); a job-level `permissions` silently widening the token.

## Phase 3 — Polish

### WP03 — Release guide: vector table, sign-offs, operator how-to; changelog (P2)

**Prompt**: [tasks/WP03-guide-and-changelog.md](./tasks/WP03-guide-and-changelog.md)
**Goal**: the guide section that maps every #221 vector (and the bypass-list gap) to a control or a signed-off accepted risk; how to run, read and update the audit; the changelog fragment.
**Independent test**: every vector in spec SC-004 has exactly one row; each accepted-risk row names the operator and a date; `cd site && npm run validate` passes.
**Requirements**: FR-012, FR-013, FR-014, SC-004
**Dependencies**: WP02

T010 Vector table with sign-offs (WP03)
T011 Run / read / update; consumer cross-reference; `updated` (WP03)
T012 Changelog fragment (WP03)
T013 Docs and changelog gates (WP03)

**Risks**: the guide restating settings that drift from the expected file (point at the file instead of copying values); generated index edits committed by accident (run `docs_index.py --clean`).

## Dependency graph

WP01 → WP02 → WP03 (sequential on one branch).

## MVP

WP01 + WP02: the audit runs and guards itself. WP03 records the posture.
