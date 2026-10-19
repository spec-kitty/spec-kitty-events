---
work_package_id: WP03
title: Reconciliation document, release notes, 11.1.0
dependencies:
- WP01
- WP02
requirement_refs:
- FR-012
- FR-013
- FR-014
- C-006
- C-008
- SC-006
planning_base_branch: issue-119-lifecycle-reconciliation-completion
merge_target_branch: issue-119-lifecycle-reconciliation-completion
branch_strategy: Planning artifacts were generated on issue-119-lifecycle-reconciliation-completion; completed changes must merge back into issue-119-lifecycle-reconciliation-completion. Execution worktrees are allocated per computed lane from lanes.json.
subtasks:
- T014
- T015
- T016
- T017
- T018
phase: Phase 3 - Documentation and release
agent: ''
history:
- '2026-10-19: created by /spec-kitty.tasks'
agent_profile: python-pedro
authoritative_surface: docs/architecture/lifecycle-reconciliation.md
create_intent:
- docs/architecture/lifecycle-reconciliation.md
execution_mode: code_change
owned_files:
- docs/architecture/lifecycle-reconciliation.md
- docs/architecture/README.md
- docs/events/review-audit-decisions.md
- CHANGELOG.md
- COMPATIBILITY.md
- pyproject.toml
- uv.lock
role: implementer
---

# WP03 - Reconciliation document, release notes, 11.1.0

## ⚡ Do This First: Load Agent Profile

Load profile `python-pedro` with `/spk-charter-profile-load`, then `spec-kitty charter context --action implement`. Read spec.md, plan.md, research.md, the brief, and the WP01/WP02 diffs.

## Objective

FR-012 (reconciliation record), FR-013 (11.1.0 release notes and version), FR-014 (stale pin). User Stories 4 and 5.

## Branch Strategy

Base and merge target `issue-119-lifecycle-reconciliation-completion`; lane worktree from `lanes.json`.

```bash
spec-kitty agent action implement WP03 --agent <name> --mission lifecycle-reconciliation-completion-01M59CQF
```

Out-of-map edit (justified): the `__version__ = "11.0.0"` line in `src/spec_kitty_events/__init__.py` (owned by WP01) becomes `"11.1.0"`; that single line only.

## Subtasks

### T014 - `docs/architecture/lifecycle-reconciliation.md` [P]

DocKitty frontmatter (`title`, `description` <= 180 chars, `doc_status: active`, `updated: <date>`, `type: Architecture`, `kind`). One section per module:
- `lifecycle`: core consumer `specify_cli/core/mission_payload.py:30` (`MissionCreatedPayload`); field agreement; the new invalid fixtures and replay golden (WP02).
- `project_lifecycle`: consumers `specify_cli/status/lifecycle_events.py:661,830,966`; the hand-copied constants at lines 77-93 (follow-up).
- `mission_audit`: no core consumer found; module unchanged.
- `mission_next`: consumers `runtime/next/_internal_runtime/{engine,events,schema}.py`; `RuntimeActorIdentity` unchanged (R1); the new sibling `decision_significance` module (WP01) and the R2 `actor_id` narrowing, including the producer-row divergence (research R-2).
Re-verify every cited core line against the sibling `spec-kitty` checkout before writing; if a line moved, cite the current one. Close with the six file-and-link follow-ups from the brief, plus the current core pin `>=11.0.0,<12` and the note that the `>=10.5.1,<11` figure in #119 and in the completed mission `mission-lifecycle-reconciliation-01M574ET` is stale (that mission's artifacts stay immutable).

### T015 - Docs entries and index [P]

- `docs/events/review-audit-decisions.md`: add `SignificanceEvaluated` and `DecisionTimeoutExpired` entries in that page's existing style; bump `updated`.
- `docs/architecture/README.md`: link the new page; bump `updated`.
- If the sibling `doc-kitty` checkout exists, run its frontmatter and link validators on `docs`.

### T016 - CHANGELOG and COMPATIBILITY

- `CHANGELOG.md` `[11.1.0]`: Added (decision-significance module and models; fixtures; categories `decision_significance` and `lifecycle`; lifecycle invalid fixtures; replay golden and packaged runner; WPStatusChanged lane breadth; reconciliation doc). Closes #119, #62.
- `COMPATIBILITY.md` `11.1.0`: minor; no existing accept boundary moved (cite the 6.1.0 precedent); envelope `schema_version` unchanged; `STRICT_EVENT_TYPES`, `MISSION_NEXT_EVENT_TYPES` and the support matrix unchanged; R2: the package refuses a null/empty RACI `actor_id` that the producer row and core still allow; core follow-up named.

### T017 - Version bump

`pyproject.toml` version `11.1.0`; `__version__` line; `uv lock` (only the package's own version entry may change; if `uv lock` changes any dependency, stop and escalate). Run any test that pins version agreement.

### T018 - Stale pins and final gates

- `grep -rn "10.5.1,<11"` over maintained text (exclude dated `docs/changelog/*`, ADRs, `kitty-specs/`, `.kittify/evidence/`, `.worktrees/`); correct any hit to `>=11.0.0,<12`.
- Final gates:

```bash
uv run --frozen pytest --pyargs spec_kitty_events.conformance -q
make test-fast
uv run --frozen pytest tests/integration/test_schema_drift.py tests/test_support_matrix.py -q
uv run --frozen mypy --strict src && uv run --frozen ruff check . && uv run --frozen ruff format --check .
```

## Definition of Done

FR-012..FR-014 met; SC-006 (4/4 modules, 6/6 follow-ups); version 11.1.0 consistent; gates green.

## Reviewer guidance

Spot-check three core line citations against the sibling checkout. Confirm COMPATIBILITY states the R2 divergence plainly.
