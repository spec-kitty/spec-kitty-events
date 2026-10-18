---
work_package_id: WP03
title: LOCAL_ONLY deprecation, 10.8.0 release surfaces, docs
dependencies:
- WP02
requirement_refs:
- FR-009
- FR-011
- FR-012
- C-002
- C-004
planning_base_branch: issue-118-ledger-write-contract-seam
merge_target_branch: issue-118-ledger-write-contract-seam
branch_strategy: Planning artifacts for this mission were generated on issue-118-ledger-write-contract-seam. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into issue-118-ledger-write-contract-seam unless the human explicitly redirects the landing branch.
base_branch: kitty/mission-ledger-write-contract-seam-01M57P5Q
base_commit: 7f466cab6009e8463cb2ddec4948f43458fc7462
created_at: '2026-10-18T14:40:58.295268+00:00'
subtasks:
- T010
- T011
- T012
- T013
history:
- '2026-10-18: created by /spec-kitty.tasks (semi-auto drive)'
agent_profile: python-pedro
authoritative_surface: src/spec_kitty_events/__init__.py
create_intent:
- docs/api/ledger-write-contract.md
- tests/test_local_only_deprecation.py
execution_mode: code_change
owned_files:
- src/spec_kitty_events/__init__.py
- pyproject.toml
- README.md
- COMPATIBILITY.md
- CHANGELOG.md
- uv.lock
- docs/api/README.md
- docs/api/ledger-write-contract.md
- docs/changelog/**
- tests/test_local_only_deprecation.py
tags: []
tracker_refs: []
---
# WP03: deprecation, 10.8.0 release surfaces and docs

## Objective
Cover FR-009, FR-011, FR-012, SC-003 and SC-004.

## Branch Strategy
Planning branch and merge target: `issue-118-ledger-write-contract-seam`. Lane worktree from `lanes.json`.

## Subtasks
- **T010:** Rewrite the comment above `LOCAL_ONLY_EVENT_TYPES` in `__init__.py`. It should say the concept is retired and deprecated, that the name will be removed in 11.0, that no type may be added, and that `MissionReopened` and `FollowUpRecorded` are canonical. The value stays `frozenset()`. `tests/test_local_only_deprecation.py` checks that the import is warning-free under `warnings.simplefilter("error")`, that the value is empty, and that `append_event` does not reference the name.
- **T011:** Bump to 10.8.0 everywhere: `__version__`, `pyproject.toml`, the README masthead and pip line, the `COMPATIBILITY.md` version line plus a section on the new surface and the deprecation, `CHANGELOG.md` (10.8.0 entry: bold, impact-first, `(#118)`) and `uv lock`.
- **T012:** Write `docs/api/ledger-write-contract.md`. It needs frontmatter (title, description ≤ 180 characters, `doc_status`, `updated`, `kind`) and must cover the input kinds, byte rules, atomicity, refusals, fixtures and the deprecation. Link it from `docs/api/README.md`.
- **T013:** Run `make test-fast`, the version guard (`tests/test_compatibility_doc.py`, `tests/test_support_matrix.py`), `uv run pytest --pyargs spec_kitty_events.conformance`, `mypy --strict src` and the ruff whole-repo checks. Draft the core follow-up issue body for SC-004 in the PR notes.

## Definition of Done
Every gate above is green, and the version is consistent across all surfaces.
