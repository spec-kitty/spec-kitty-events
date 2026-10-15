---
work_package_id: WP02
title: Diary mapping docs and 10.5.2 release
dependencies:
- WP01
requirement_refs:
- FR-004
- FR-005
- NFR-002
- C-002
planning_base_branch: issue-48-review-ref-gated-rewinds
merge_target_branch: issue-48-review-ref-gated-rewinds
branch_strategy: Planning artifacts for this mission were generated on issue-48-review-ref-gated-rewinds. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into issue-48-review-ref-gated-rewinds unless the human explicitly redirects the landing branch.
base_branch: kitty/mission-review-ref-gated-rewinds-01M4ZJJ0
base_commit: 7194025a921223cd95d765d54efdd3cacf1270be
created_at: '2026-10-15T10:50:40.092174+00:00'
subtasks:
- T005
- T006
- T007
history:
- timestamp: '2026-10-15T11:00:00Z'
  lane: planned
  agent: system
  action: Prompt generated via /spec-kitty.tasks
agent_profile: python-pedro
authoritative_surface: src/spec_kitty_events/
create_intent: []
execution_mode: code_change
model: sonnet
owned_files:
- src/spec_kitty_events/diary.py
- src/spec_kitty_events/__init__.py
- pyproject.toml
- uv.lock
- CHANGELOG.md
- COMPATIBILITY.md
- README.md
role: implementer
tags: []
tracker_refs: []
---

# WP02 – Diary mapping docs and 10.5.2 release

## ⚡ Do This First: Load Agent Profile

Load the `python-pedro` profile via the `/spk-charter-profile-load` skill before reading anything else, then run `spec-kitty charter context --action implement --json`.

## Objective

FR-004 and FR-005 of spec-kitty-events#48: document how the status-diary reducer treats the three review-ref-gated rewinds (named in WP01's `status.py` docstring), and cut patch 10.5.2. **No behaviour change.**

## Branch Strategy

The planning branch and merge target are both `issue-48-review-ref-gated-rewinds`. The worktree comes from `lanes.json`. Implement with `spec-kitty agent action implement WP02 --agent claude --mission review-ref-gated-rewinds-01M4ZJJ0`.

## Test economy

This WP adds no tests. The version-pin tests that already exist (`tests/test_compatibility_doc.py`, `tests/test_version_not_amended.py`) are the guards.

## T005 – Diary docstring

Extend the docstring of `_is_rollback_event` in `src/spec_kitty_events/diary.py` (~1083), or add a comment block above `_REJECTION_ROLLBACK_TARGETS` (~1071), with a short "Review-ref-gated rewinds in the diary" note. Verify each claim against the code before writing it:
- `in_review -> in_progress` is a rejection rollback (`_REJECTION_ROLLBACK_TARGETS`). It takes rollback precedence and is exempt from arm (ii) `from_lane` continuity.
- `for_review -> in_progress` is a rollback only when it carries a `review_ref`, which the contract requires.
- `approved -> in_progress` is not a rollback. It is an approved departure: it is ordered after its approval (`_approved_departure_sort_at`, 10.5.1) and judged by arm (ii) continuity **unless forced**. `force=True` skips continuity, so a stale forced reopen arriving after `approved -> done` would move a `done` WP back to `in_progress`. This is why the contract keeps the family force-exempt (status.py docstring, spec-kitty-events#48).

Cross-check the forced-reopen claim with a throwaway script, and put the evidence in your report, not the commit. Use `_row` from `tests/test_status_diary_reducer.py` and pass `force=True, reason=...`.

## T006 – Release 10.5.2

Follow the 10.5.1 bump pattern: `git show b0f35f5 --stat` on `main` shows which files to touch (`pyproject.toml`, `src/spec_kitty_events/__init__.py`, `README.md`, `COMPATIBILITY.md`, `CHANGELOG.md`, `uv.lock`).
- **CHANGELOG** `## [10.5.2] - 2026-10-15`, `### Changed` (documentation and contract clarification). The bold lead states the user impact: **"The status contract now says which backward review moves need `force` and which need a review reference (spec-kitty-events#48)."** Follow it with a short before → after, then a `### Compatibility` line: no validator change, envelope `3.0.0`, patch.
- **COMPATIBILITY.md**: add a 10.5.2 section in the existing style.
- **uv.lock**: run `uv lock` (a version-only change).

## T007 – Verify

```
uv run --frozen pytest --pyargs spec_kitty_events.conformance -q
uv run --frozen pytest tests/test_compatibility_doc.py tests/test_version_not_amended.py tests/test_status_diary_reducer.py -q
ruff check . && ruff format --check .
mypy src/spec_kitty_events/diary.py
```

`test_version_not_amended` may need `git fetch --depth=1000 skupstream main` in a shallow worktree.

Commits: `docs(diary): map the review-ref-gated rewinds in the reducer (spec-kitty-events#48)` and `chore(release): 10.5.2, review-ref-gated rewind family (spec-kitty-events#48)`.

## Definition of Done

The diary note is accurate (with evidence), 10.5.2 is consistent across all pins, and all listed commands are green.
