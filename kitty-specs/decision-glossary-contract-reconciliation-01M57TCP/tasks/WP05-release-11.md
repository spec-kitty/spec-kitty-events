---
work_package_id: WP05
title: 11.0.0 release surfaces and docs
dependencies:
- WP04
requirement_refs:
- FR-013
- FR-014
- NFR-003
- C-004
planning_base_branch: issue-120-decision-moment-reconciliation
merge_target_branch: issue-120-decision-moment-reconciliation
branch_strategy: Planning artifacts for this mission were generated on issue-120-decision-moment-reconciliation. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into issue-120-decision-moment-reconciliation unless the human explicitly redirects the landing branch.
base_branch: kitty/mission-decision-glossary-contract-reconciliation-01M57TCP
base_commit: 955640b6ae48516ca68cbf091c47b2cf3ef086d5
created_at: '2026-10-18T16:30:13.382502+00:00'
subtasks:
- T021
- T022
- T023
- T024
- T025
history:
- '2026-10-18: created by /spec-kitty.tasks'
agent_profile: python-pedro
authoritative_surface: CHANGELOG.md
create_intent: [tests/test_wheel_contents.py]
execution_mode: code_change
owned_files:
- pyproject.toml
- README.md
- COMPATIBILITY.md
- CHANGELOG.md
- uv.lock
- docs/**
tags: []
tracker_refs: []
---
# WP05: 11.0.0 release surfaces and docs

## Objective
Covers FR-013 and FR-014, following the major rules in `contracts/versioning-and-compatibility.md:49-58`. The precedent is the 10.0.0 and 9.0.0 sections in COMPATIBILITY.md.

## Branch Strategy
Planning branch and merge target: `issue-120-decision-moment-reconciliation`. The lane worktree comes from `lanes.json`.

## Common rules
- Red-first per ADR 2026-07-17-1: write an issue-pinned `@pytest.mark.regression` repro (issue #120), show it RED, then convert it into focused unit tests.
- Keep complexity at 15 or below. `mypy --strict` and ruff must be clean on the touched files.
- Import nothing from core (C-001). Add no aliases (C-002).
- The schema drift check stays red until WP04 regenerates the schemas; do not regenerate schemas in this WP.

## Subtasks
- **T021:** pyproject `version = "11.0.0"`, the README masthead and pip lines (`:5,73,75`), the COMPATIBILITY version line, and `uv lock`.
- **T022:** add COMPATIBILITY `## \`11.0.0\` — decision reopen, core-shaped glossary contracts, optional envelope clock (breaking)`. It covers:
  - what changed and why it is a major;
  - who is unaffected;
  - the migration for each role (producer, reader, binding author, core);
  - a field-map table (old → new) for every glossary model;
  - the unclocked envelope rules (local ledgers yes; journal and TeamSpace no);
  - "envelope schema_version unchanged (3.0.0)".
- **T023:** CHANGELOG `## [11.0.0]` with `### Breaking` (one line per change plus its consequence), `### Added` and `### Removed`. Note that no committed replay output changed.
- **T024:** update these pages:
  - `docs/events/semantics-and-analytics.md:22-46` (field table, type count);
  - `docs/events/README.md:34,46`;
  - `docs/events/review-audit-decisions.md` (reopen, fold);
  - `docs/api/python-package.md`;
  - `docs/api/ledger-write-contract.md`;
  - Decision Moment V1 wording;
  - re-sync `docs/api/versioning-and-compatibility.md` with `contracts/`.
- **Packaging (from the WP04 review, MAJOR):** the `[tool.setuptools.package-data]` section of `pyproject.toml` has no glob for `conformance/fixtures/ordering/*.json`, so the installed wheel lacks the ordering fixtures that the packaged `test_event_ordering.py` reads. Add that glob, plus the globs for files that were already missing: `dossier/replay/canonical_output_snapshot.json`, `timestamp_semantics/{valid,invalid}/*.json` and `zeitgeist_attrs/README.md`. Add `tests/test_wheel_contents.py`: it builds the wheel (or reads the package-data globs) and asserts that every `git ls-files src/spec_kitty_events/conformance/fixtures` path ships. Prove the fix with a clean wheel install running the packaged gate.
- **T025:** run every gate and record the counts.

## Definition of Done
All gates are green: `make test-fast`, the packaged gate, schema `--check`, `tests/test_version_not_amended.py` (with full history), `tests/test_compatibility_doc.py`, mypy and ruff.
