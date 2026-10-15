---
work_package_id: WP01
title: 'Contract: name and pin the review-ref-gated rewind family'
dependencies: []
requirement_refs:
- FR-001
- FR-002
- FR-003
- NFR-001
- C-001
- C-003
- SC-001
- SC-002
- SC-003
planning_base_branch: issue-48-review-ref-gated-rewinds
merge_target_branch: issue-48-review-ref-gated-rewinds
branch_strategy: Planning artifacts for this mission were generated on issue-48-review-ref-gated-rewinds. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into issue-48-review-ref-gated-rewinds unless the human explicitly redirects the landing branch.
base_branch: kitty/mission-review-ref-gated-rewinds-01M4ZJJ0
base_commit: 05d66c35d717680cd82f521aa6e051fd60cdbc4f
created_at: '2026-10-15T10:47:17.807973+00:00'
subtasks:
- T001
- T002
- T003
- T004
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
- src/spec_kitty_events/status.py
- src/spec_kitty_events/conformance/fixtures/manifest.json
- src/spec_kitty_events/conformance/fixtures/edge_cases/valid/wp_status_changed_*_to_in_progress*.json
- src/spec_kitty_events/conformance/fixtures/edge_cases/invalid/wp_status_changed_*_to_in_progress*.json
- tests/unit/test_status.py
role: implementer
tags: []
tracker_refs: []
---

# WP01 – Contract: name and pin the review-ref-gated rewind family

## ⚡ Do This First: Load Agent Profile

Load the `python-pedro` profile via the `/spk-charter-profile-load` skill before reading anything else, then run `spec-kitty charter context --action implement --json`.

## Objective

spec-kitty-events#48. The operator decided (option b) to **document** the three backward rewinds to `in_progress` as a named, **force-exempt**, `review_ref`-gated family. Consumers must be able to see the rule in the contract and must hit a red conformance fixture if they generalise the force rule onto these edges. **The validator's behaviour must not change.**

The family is three edges:
- `for_review -> in_progress`
- `in_review -> in_progress`
- `approved -> in_progress`

All three are in `_ALLOWED_TRANSITIONS` (`status.py` ~555–561). `validate_transition`'s `review_ref_required` guard (~650–662) requires a non-empty `review_ref` on them whether or not they are forced. They are not in `_REVIEW_REJECTION_FAMILY`.

## Branch Strategy

The planning branch and merge target are both `issue-48-review-ref-gated-rewinds`. The execution worktree is allocated per lane from `lanes.json`. Implement with `spec-kitty agent action implement WP01 --agent claude --mission review-ref-gated-rewinds-01M4ZJJ0`.

## Test economy (DIRECTIVE_041), read before writing any test

Add a test only when it guards real behaviour through the existing entry point (`validate_transition`, the packaged conformance harness) and would fail on a real regression. The fixtures are data rows for the existing harness, not new test functions. Add **at most one** new test function: the T003 truth table. Prefer extending an existing test in `tests/unit/test_status.py` if one already parametrises over transitions.

## T001 – Contract docstring section

In the `status.py` module docstring, directly after the "Review-Rejection Transition Family" section and its subsections, add `## Review-Ref-Gated Rewind Family`. It must contain:
- A table of the three edges.
- **Wire requirements:** `review_ref` must be non-empty, whether forced or not; `force` is NOT required and is allowed; if forced, the payload's existing rule still applies (`force=True requires a non-empty reason`).
- **Force-exempt by contract:** consumers MUST NOT require `force=True` on these edges. The validator accepts them unforced.
- **Violation routing:** a missing `review_ref` produces a violation containing `requires review_ref`.
- **Rationale (one short paragraph):** forcing would bypass the status diary's `from_lane` continuity. In particular, a forced stale `approved -> in_progress` can reopen a WP that is already `done`. Cite spec-kitty-events#48.
- **Contrast with the review-rejection family:** `* -> planned` requires `force` plus `reason`, and `review_ref` is optional when forced.

Keep the prose plain and impact-first.

## T002 – Private constant

Add `_REVIEW_REF_GATED_REWINDS: FrozenSet[Tuple[Lane, Lane]]` holding the three pairs, next to `_REVIEW_REJECTION_FAMILY`, with a one-line comment pointing at the docstring section.

Refactor `review_ref_required` only if the refactor is behaviour-identical. The current expression also requires `review_ref` for unforced `(for_review|in_review|approved) -> planned`, and that message must stay. One acceptable shape:

```python
pair = (payload.from_lane, payload.to_lane)
review_ref_required = pair in _REVIEW_REF_GATED_REWINDS or (
    payload.from_lane is not None
    and _is_review_rejection_pair(payload.from_lane, payload.to_lane)
    and payload.from_lane is not Lane.IN_PROGRESS
    and not payload.force
)
```

Check this against the original: `in_progress -> planned` is NOT in the original set, because `from` must be in `{for_review, in_review, approved}`. If the refactor is not clearer, leave the guard alone and make the constant purely documentary. Either way, T003 must pass. No new public symbol; do not touch `__all__`.

## T003 – Truth-table guard

Add one parametrised test (or extend an existing one) that iterates every `(from_lane, to_lane)` with `from_lane` in `[None, *Lane]` and `to_lane` in `Lane`, crossed with `force` in `{False, True}` and `review_ref` in `{None, "ref://x"}`. For forced cases, set `reason="r"`, because the model requires it.

For each combination, assert that `validate_transition(...)` gives the same `(valid, sorted(violations))` as a frozen copy of the pre-change rule. The simplest approach is to embed the original `review_ref_required` expression in the test as a reference oracle. Skip combinations whose payload construction raises a model `ValidationError`, and skip them identically on both sides.

Red-first evidence for the report, not the commit: show that the test fails if you deliberately drop `for_review -> in_progress` from the new constant (only when T002 refactors the guard).

## T004 – Fixtures and manifest rows

Follow `edge_cases/invalid/wp_status_changed_unforced_in_review_to_planned.json` and its manifest row (`manifest.json` ~1671). For each edge `E ∈ {for_review, in_review, approved}` create:
- `edge_cases/valid/wp_status_changed_unforced_<E>_to_in_progress_with_review_ref.json`: `force: false`, `review_ref` set, `reason` set. `expected_result: "valid"`.
- `edge_cases/invalid/wp_status_changed_unforced_<E>_to_in_progress_missing_review_ref.json`: `force: false`, `review_ref: null`. Expected `invalid`; the notes say the violation contains `requires review_ref`.
- `edge_cases/invalid/wp_status_changed_forced_<E>_to_in_progress_missing_review_ref.json`: `force: true`, `reason` non-empty, `review_ref: null`. Expected `invalid`; the notes say force does not waive `review_ref` for the review-ref-gated rewind family.

Manifest rows need `id`, `path`, `expected_result`, `event_type: "WPStatusChanged"`, `notes` (cite the family name and #48) and `min_version: "3.0.0"`. Insert them next to the existing WPStatusChanged edge-case rows. Check how `tests/unit/test_fixtures.py` and the conformance loader assert invalid fixtures, for example whether they check violation substrings. Make sure the invalid fixtures actually fail via `validate_transition` and not via a schema error, by running them.

## Definition of Done

- Docstring section present, with the plain-language wire rules and rationale.
- 9 fixtures plus 9 manifest rows. `uv run --frozen pytest --pyargs spec_kitty_events.conformance -q` passes, with the count up by the new cases.
- `uv run --frozen pytest tests/unit/test_status.py tests/unit/test_fixtures.py tests/unit/test_conformance_semantic.py -q` passes.
- `ruff check` and `ruff format --check` on the changed files; `mypy src/spec_kitty_events/status.py` clean.
- Commit message: `docs(status): name the review-ref-gated rewind family and pin it with fixtures (spec-kitty-events#48)`.

## Reviewer guidance

Verify that no validator outcome changed (T003 is non-vacuous), that each invalid fixture fails for the stated reason, and that the docstring does not claim anything the validator does not enforce.
