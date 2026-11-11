---
work_package_id: WP04
title: 'validate_transition = core edge graph; 11.5.0 (D4, #231 4.6)'
dependencies: []
requirement_refs:
- FR-008
- FR-011
- FR-012
- FR-013
- SC-006
planning_base_branch: issue-237-write-fold-encoder-parity
merge_target_branch: issue-237-write-fold-encoder-parity
branch_strategy: Single-branch mission. Planning, base and merge target are issue-237-write-fold-encoder-parity; the finished branch goes to main through one PR.
subtasks:
- T013
- T017
- T018
- T019
- T020
- T021
history:
- timestamp: '2026-11-11T11:30:00Z'
  lane: planned
  agent: system
  action: Prompt generated via /spec-kitty.tasks
agent_profile: python-pedro
authoritative_surface: src/spec_kitty_events/status.py
create_intent:
- tests/unit/test_validate_transition_core_edges.py
- docs/changelog/unreleased/237-validate-transition-core-edges.md
execution_mode: code_change
owned_files:
- src/spec_kitty_events/status.py
- tests/unit/test_status.py
- tests/unit/test_validate_transition_core_edges.py
- tests/unit/test_conformance_semantic.py
- tests/unit/test_fixtures.py
- docs/events/work-and-status.md
- pyproject.toml
- uv.lock
- java/build.gradle.kts
- README.md
- COMPATIBILITY.md
- src/spec_kitty_events/__init__.py
- docs/changelog/unreleased/237-validate-transition-core-edges.md
role: implementer
tags: []
tracker_refs: []
---
# Work Package Prompt: WP04 — `validate_transition` = core's edge graph; 11.5.0 (D4, #231 4.6)

## ⚡ Do This First: Load Agent Profile

Load your assigned profile before reading anything else: `/spk-charter-profile-load python-pedro`
(or `spec-kitty agent profile show python-pedro`). Adopt its identity, scope and boundaries.

## Objective

Maintainer ruling **D4** (2026-11-09): align the public `validate_transition()` with core's lane
machine and take a minor version bump. Operator ruling **M2-R3**: the bump (11.4.0 → 11.5.0) lands in
this PR. Scope is the **unforced edge graph**; force semantics (force bypasses the edge check, as in
core's `check_transition`) and the guard checks stay as they are.

You also own `src/spec_kitty_events/__init__.py`: bump `__version__` and add a `status_log` bullet
to the package docstring's module inventory (T013, #231 4.3; do not re-export `status_log`,
consistent with `total_order` and `producer_contract`).

## Context

- Spec: User Story 3, FR-011–FR-013, SC-006. Research D-6 (the literal core graph), D-8.
- `src/spec_kitty_events/status.py:592-622` `_ALLOWED_TRANSITIONS`; `:665-760` `validate_transition`:
  terminal check, review-rejection family check, then (unforced only) matrix ∪ `to_blocked` ∪
  `to_canceled` (any non-terminal, non-None `from_lane` → blocked/canceled), then guards.
- Core at the pin `512567eee71e31bb39f580c4f7acf8b865e9d1b2`: `src/specify_cli/status/wp_state.py`
  `allowed_targets()` per state. Read it statically (`git show 512567ee:src/specify_cli/status/wp_state.py`
  in a core clone, or `https://raw.githubusercontent.com/spec-kitty/spec-kitty/512567ee.../src/specify_cli/status/wp_state.py`).
  Do **not** execute core code. research.md D-6 lists the graph as read at core `main`; core `main`
  changed `wp_state.py` by 128 lines since the pin, so re-derive at the pin and record any
  difference in the activity log.
- Expected removals (from research D-6): `for_review → approved`, `for_review → done`,
  `for_review → in_progress`, `for_review → planned`, `in_review → for_review`, `genesis → blocked`,
  `blocked → blocked`. The package's `(None, planned)` initial edge has no core counterpart
  (core has no `None` lane) and stays.
- Note the review-rejection family (`_REVIEW_REJECTION_FAMILY`, :625-632): `for_review → planned` is
  in it; once the edge is gone unforced, the family's force-required message and the matrix message
  both fire for an unforced attempt. Decide which violations an unforced `for_review → planned`
  now reports and pin it in a test; keep messages stable where core-equivalent.
- Docs: `docs/events/work-and-status.md:48` presents the matrix as the permitted moves.
- Version files (as #230 bumped them): `pyproject.toml` `version`, `src/spec_kitty_events/__init__.py`
  `__version__`, `java/build.gradle.kts` `version`, `README.md` (package version line + two
  `pip install` lines), `COMPATIBILITY.md` (`**Current package version**` + a new `## 11.5.0` section),
  `uv.lock` (`uv lock` refresh of the project's own version only). `tests/test_compatibility_doc.py`
  enforces COMPATIBILITY ↔ `__version__`. Do **not** bump `min_version` fields in
  `conformance/fixtures/manifest.json` or `loader.py` (those name the version a fixture appeared in).

## Subtasks

### T013 — Package docstring
Add a `status_log` bullet to the module inventory in `src/spec_kitty_events/__init__.py`'s docstring
(#231 4.3). No re-export. Include it in the `Changed` fragment of T021 or WP03's fragment wording.

### T017 — Edge graph
Rewrite `_ALLOWED_TRANSITIONS` as core's graph plus `(None, PLANNED)`, and replace the generic
`to_blocked`/`to_canceled` rules by the explicit edges (core lists blocked/canceled per state).
Keep a comment naming the core file, the pin SHA and that the write service is the write authority.

### T018 — Exhaustive matrix test
`tests/unit/test_validate_transition_core_edges.py`: a literal `CORE_EDGES` frozenset (copied from
`wp_state.py` at the pin, with a comment citing file and SHA). For every `(from, to)` over
`[None] + list(Lane)` × `list(Lane)`, build a minimal unforced payload that satisfies every guard
(review_ref, evidence, reason… use the existing test helpers in `tests/unit/test_status.py`) and
assert "no edge violation" iff the pair is in `CORE_EDGES ∪ {(None, planned)}`. Assert on the
**edge** violation message only (`"Transition X -> Y is not allowed"`), so guard messages do not
confound it. Add: forced moves give the same result as before for a representative sample.

### T019 — Existing tests
Fix tests in `tests/unit/test_status.py`, `test_conformance_semantic.py`, `test_fixtures.py` that
assumed a removed edge. For each changed assertion, note in the activity log which edge and why.
If a **packaged** conformance fixture asserts a removed edge as valid, stop and escalate (that is a
contract fixture change needing review) — do not edit it silently.

### T020 — Docs [P]
`docs/events/work-and-status.md`: describe `validate_transition` as checking core's lane machine
(edges + the package's guard checks), name the write service as the write authority, and list the
edge table or link to it. Keep frontmatter valid; bump `updated`.

### T021 — 11.5.0 + fragment
1. Bump the version files listed above; `COMPATIBILITY.md` gets a short `## 11.5.0` section:
   minor; `validate_transition` admits only core's unforced edges; consumers relying on the removed
   edges must pass `force=True` with a reason, as core requires. Plus the encoder refusal subclasses
   from WP03 can be mentioned as additive (coordinate wording; WP03's fragment is separate).
2. Fragment `docs/changelog/unreleased/237-validate-transition-core-edges.md`, section `Changed`,
   naming each removed edge and the version (#237; #231 item 4.6; ruling D4).

## Definition of Done
- `uv run pytest tests/unit/test_status.py tests/unit/test_validate_transition_core_edges.py tests/unit/test_conformance_semantic.py tests/unit/test_fixtures.py tests/test_compatibility_doc.py -q` green; counts recorded.
- `uv run pytest --pyargs spec_kitty_events.conformance` green.
- `ruff`, `mypy --strict` clean; `cd site && npm run validate` passes, then `python3 scripts/docs_index.py --clean`.
- `grep -rn "11\.4\.0" pyproject.toml src/spec_kitty_events/__init__.py java/build.gradle.kts README.md` shows none.

## Risks and reviewer guidance
- Reviewer: diff `_ALLOWED_TRANSITIONS` against `wp_state.py` at the pin yourself.
- Breaking-ness: narrowing is consumer-visible; D4 rules it a minor. Make sure the fragment says so.

## Do not
- Do not change guard semantics or force semantics.
- Implementation command: `spec-kitty agent action implement WP04 --agent <name> --mission write-fold-encoder-parity-01M74ZY0`
