# Phase 0 Research: Subtask skipped-status vocabulary

All Technical Context unknowns were resolved during specify; this records the
decisions and their grounding.

## Decision 1 — Where does `skipped` live?

- **Decision**: A new `SubtaskStatus(str, Enum)` in `diary.py`, distinct from
  `Lane`, holding `Lane`'s ten active members (`planned`..`canceled`, plus
  `genesis`) and a terminal `skipped`.
- **Rationale**: Subtask status and work-package `Lane` transitions are
  different concepts that happen to share values today (spec Domain Language).
  Adding `skipped` to `Lane` would widen the lane state machine and its
  transition matrix (`status.py` review-rejection family, `validate_transition`)
  for a value that is never a WP lane. The schema already models them as the
  same string enum with `skipped` present only on `subtasks`.
- **Alternatives considered**:
  - Add `skipped` to `Lane` — rejected (conflates vocabularies, widens the lane
    matrix, risks lane consumers accepting an invalid transition target).
  - `set[str]` / `Literal[...]` without an enum — rejected (loses the
    `mypy --strict` guarantee and the single canonical member source).

## Decision 2 — `not_applicable` treatment (Decision Moment `01M54CFGJ91EEZ0QNT3D26PSHZ`)

- **Decision**: `not_applicable` is an **input alias** accepted at the parse
  boundary and normalized to `skipped` immediately; it is never persisted and
  never a canonical enum member.
- **Rationale**: The CLI (`agent tasks mark-status … skipped`, input alias
  `not_applicable`; spec-kitty#5953) already resolves `not_applicable` before
  writing, and the shipped `wp.innerstate.changed.v1` schema enum lists
  `skipped`, not `not_applicable`. Accepting it on input makes the reader
  tolerant of any legacy/raw annotation that still carries the alias, while the
  canonical persisted vocabulary (C-002) stays exactly the schema enum.
- **Alternatives considered**:
  - Make `not_applicable` a first-class enum member — rejected (would diverge
    the binding from the schema and double the terminal vocabulary with no
    added meaning; there is no semantic distinction from `skipped`).
  - Reject `not_applicable` outright — rejected (a raw annotation carrying the
    alias would crash a reader, reintroducing the very drift this mission fixes).

## Decision 3 — Backward compatibility & the `Status` alias

- **Decision**: Keep the existing `Status` type alias (`diary.py:207`) but
  repoint it from `Lane` to `SubtaskStatus`; publish `SubtaskValue` as the
  forward-looking alias. `WPInnerStateDelta.subtasks` retypes to `SubtaskStatus`.
- **Rationale**: `SubtaskStatus` is a strict superset of the current subtask
  vocabulary (every `Lane` value is a `SubtaskStatus` value), so every existing
  log and fixture parses and reduces unchanged (FR-006 / NFR-001). The reducer's
  per-subtask fold (`diary.py:1373-1377`) is a status-agnostic replace, so
  `skipped` folds with no reducer change.
- **Alternatives considered**: drop `Status` — rejected (it is referenced
  internally and is a published name; keeping it as an alias is zero-cost
  backward compatibility).

## Release

Ships as package minor **10.6.0** (SC-004). The issue text says 10.5.0 but
current `main` is already 10.5.3, so the next minor is 10.6.0;
`check_version_not_amended` requires a new unspent version for the `src/` change.
