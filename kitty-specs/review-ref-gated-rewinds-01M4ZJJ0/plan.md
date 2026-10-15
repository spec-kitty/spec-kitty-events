# Implementation Plan: Review-ref-gated rewinds

**Branch**: `issue-48-review-ref-gated-rewinds` | **Date**: 2026-10-15 | **Spec**: [spec.md](./spec.md)
**Input**: spec-kitty/spec-kitty-events#48, operator decision (b): document the rewinds as force-exempt.

## Summary

Name the three backward rewinds to `in_progress` (`for_review`, `in_review`, `approved` → `in_progress`) as a force-exempt, `review_ref`-gated family in the status contract. Pin the family with 9 packaged conformance fixtures, document how the diary reducer treats the edges, and ship it as patch **10.5.2**. The validator's behaviour stays exactly as it is.

## Technical Context

**Language/Version**: Python 3.10+ (package `spec-kitty-events`, typed with mypy, linted with ruff)
**Primary Dependencies**: pydantic v2 (existing; no dependency change)
**Storage**: N/A (JSON fixtures under `src/spec_kitty_events/conformance/fixtures/edge_cases/`, indexed in `fixtures/manifest.json`)
**Testing**: pytest; the packaged conformance gate (`uv run pytest --pyargs spec_kitty_events.conformance`); `tests/unit/test_fixtures.py`
**Target Platform**: PyPI library consumed by the spec-kitty CLI and SaaS
**Project Type**: single library
**Performance Goals**: N/A
**Constraints**: no validator behaviour change; no new public symbol; envelope `3.0.0` unchanged; patch release
**Scale/Scope**: about 4 source/docs files, 9 fixture JSON files, and 9 manifest rows

## Charter Check

- Run the conformance gate on every PR (CLAUDE.md): planned.
- One issue → one branch → one PR targeting `main`: `issue-48-review-ref-gated-rewinds`.
- Test economy (DIRECTIVE_041): the new tests are data fixtures in the existing conformance harness, not new test functions.
- Versioning policy (`contracts/versioning-and-compatibility.md`): docs and fixtures with no behaviour change count as a patch.

No violations.

## Design

1. **Contract docstring** (`src/spec_kitty_events/status.py` module docstring, lines 1–56): add a "Review-ref-gated rewind family" section after the review-rejection family. It covers:
   - a table of the three edges;
   - wire requirements: `review_ref` non-empty, whether forced or not; `force` not required, and allowed;
   - the violation substring consumers can route on (`requires review_ref`);
   - an explicit statement that the family is force-exempt by contract;
   - the rationale: forcing would bypass diary continuity.

   Add a private frozenset `_REVIEW_REF_GATED_REWINDS` next to `_REVIEW_REJECTION_FAMILY`, and use it in the `review_ref_required` guard of `validate_transition` in place of the inline lane sets. This only works if it is behaviour-identical; a truth-table test over all lane pairs × force × `review_ref` must prove that. The inline expression also covers `(for_review|in_review|approved) -> planned` when unforced. Those pairs are already rejected by the family guard, but their `review_ref` violation message must be kept, so the refactor keeps that clause. If keeping it makes the code less clear, the constant is documentary only and the guard stays as it is.
2. **Fixtures**: for each edge, add one valid fixture (unforced, with `review_ref`), one invalid fixture (unforced, no `review_ref`) and one invalid fixture (forced with a reason, no `review_ref`). Add manifest rows with notes and `min_version: "3.0.0"`, following the `wp_status_changed_unforced_in_review_to_planned` pattern.
3. **Diary docs** (`src/spec_kitty_events/diary.py`, near `_REJECTION_ROLLBACK_TARGETS`, lines 1071–1096): add a docstring note mapping each edge:
   - `in_review -> in_progress`: rollback.
   - `for_review -> in_progress`: confirm in implementation whether it is in the rollback targets, and document what is true.
   - `approved -> in_progress`: an approved departure, subject to arm (ii) continuity unless forced. A forced stale reopen overrides `done`.
4. **Release**: bump to 10.5.2 and add CHANGELOG `[10.5.2]` and COMPATIBILITY entries. Also update the README/COMPATIBILITY version pins that tests check, plus `uv.lock`.

## Project Structure

### Documentation (this feature)

```
kitty-specs/review-ref-gated-rewinds-01M4ZJJ0/
├── spec.md
├── plan.md
├── research.md
└── tasks.md (next)
```

### Source Code (repository root)

```
src/spec_kitty_events/status.py
src/spec_kitty_events/diary.py
src/spec_kitty_events/conformance/fixtures/manifest.json
src/spec_kitty_events/conformance/fixtures/edge_cases/{valid,invalid}/wp_status_changed_*_to_in_progress*.json
tests/test_status*.py (truth-table guard for the refactor)
CHANGELOG.md, COMPATIBILITY.md, README.md, pyproject.toml, src/spec_kitty_events/__init__.py, uv.lock
```

## Complexity Tracking

None.

## Parallel Work Analysis

### Dependency Graph

WP01 (contract docstring, guard constant, fixtures) → WP02 (diary docs, release bump, changelog). The two are small and sequential, and WP02 cites WP01's wording.

### Work Distribution

One lane, two WPs.

### Coordination Points

The CHANGELOG and version pins are touched only in WP02.
