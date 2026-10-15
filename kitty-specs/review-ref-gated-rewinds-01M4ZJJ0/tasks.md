# Tasks: Review-ref-gated rewinds

**Mission**: review-ref-gated-rewinds-01M4ZJJ0 · **Issue**: spec-kitty-events#48 · **Branch**: `issue-48-review-ref-gated-rewinds`

## Subtask Index

| ID | Description | WP | Parallel |
|---|---|---|---|
| T001 | Contract docstring: "Review-ref-gated rewind family" section in `status.py` | WP01 | |
| T002 | Private `_REVIEW_REF_GATED_REWINDS` constant, used by the `review_ref` guard behaviour-identically | WP01 | |
| T003 | Truth-table guard proving the validator's outcomes are unchanged | WP01 | |
| T004 | 9 conformance fixtures + manifest rows | WP01 | [P] |
| T005 | Diary docstring: per-edge reducer mapping and the forced-reopen hazard | WP02 | |
| T006 | Release 10.5.2: version bump, CHANGELOG, COMPATIBILITY, README pin, `uv.lock` | WP02 | |
| T007 | Verify gates (conformance, ruff, mypy, version tests) | WP02 | |

## WP01 – Contract: name and pin the review-ref-gated rewind family (P1)

**Goal**: FR-001, FR-002, FR-003. Prompt: [tasks/WP01-contract-family-and-fixtures.md](tasks/WP01-contract-family-and-fixtures.md)

T001 Contract docstring section (WP01)
T002 Guard constant (WP01)
T003 Truth-table guard (WP01)
T004 Fixtures + manifest (WP01)

**Dependencies**: none. **Risk**: an unintended validator change. T003 is the guard.

## WP02 – Diary mapping docs and 10.5.2 release (P2)

**Goal**: FR-004, FR-005. Prompt: [tasks/WP02-diary-docs-and-release.md](tasks/WP02-diary-docs-and-release.md)

T005 Diary docstring (WP02)
T006 Release 10.5.2 (WP02)
T007 Gate verification (WP02)

**Dependencies**: WP01.
