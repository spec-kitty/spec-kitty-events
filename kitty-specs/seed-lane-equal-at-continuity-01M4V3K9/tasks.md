# Tasks: Seed-lane equal-`at` continuity fix

**Mission**: seed-lane-equal-at-continuity-01M4V3K9
**Spec**: [spec.md](./spec.md) · **Plan**: [plan.md](./plan.md)

Single work package; no dependencies.

## WP01 — Equal-`at` `approved -> done` settles its approval (spec-kitty-events#87)

Phase 1 — Fix. Depends on: none.
**Requirement Refs**: FR-001, FR-002, FR-003, FR-004.

- **T001**: Add a red-first regression test (`@pytest.mark.regression`, issue 87) in `tests/test_status_diary_reducer.py` that reduces the #87 case through `reduce()` and asserts `("done", "approved")`. It MUST fail on current `diary.py` (proves it witnesses the bug).
- **T002**: In `src/spec_kitty_events/diary.py`, make the "built upon by a real `approved -> done`" determination (`_is_bare_sibling`, and arm-(ii) continuity as needed) robust to an `approved -> done` that shares its approval's `at` but sorts before it by `event_id`, so the done settles the approval regardless of id order. Keep complexity ≤ 15.
- **T003**: Verify — T001 now green; full `tests/test_status_diary_reducer.py` + packaged conformance replay green; `ruff` + `ruff format --check` + `mypy --strict` clean on changed files. No public API / schema / version change.

## Requirement Mapping

| WP   | Requirements                       |
|------|------------------------------------|
| WP01 | FR-001, FR-002, FR-003, FR-004     |
