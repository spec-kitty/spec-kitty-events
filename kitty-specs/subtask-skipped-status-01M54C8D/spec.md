# Feature Specification: Subtask skipped-status vocabulary in the diary contract

**Feature Branch**: `feat/subtask-skipped-status`
**Created**: 2026-10-17
**Status**: Draft
**Input**: spec-kitty-events#76 — "Add a subtask status vocabulary with a terminal 'skipped' value to the diary contract"

## Summary

The CLI already lets an operator mark a work-package subtask deliberately not-done
(`agent tasks mark-status … skipped`, input alias `not_applicable`; spec-kitty#5953,
closes spec-kitty#5067). That writes a `WPInnerStateDelta` annotation whose `subtasks`
map carries `skipped`. The shipped producer-contract JSON schema for
`wp.innerstate.changed.v1` already lists `skipped` in its subtasks enum — but the Python
binding does not: `diary.py` declares `Status: TypeAlias = Lane`, and `Lane` has no
`skipped` member, so `WPInnerStateDelta.from_dict` (which parses each value with
`Status(value)`) raises `ValueError: 'skipped' is not a valid Lane` the moment one skipped
mark reaches a reader. This is the schema-vs-binding drift that ADR 2026-04-25-1 (shared
package boundary) forbids; the CLI only works today by decoding annotations with a local
copy. This mission closes the drift: it gives the diary contract a public subtask-status
vocabulary that matches the schema, so every consumer reads `skipped` through the shared
package.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A reader parses a log that contains a skipped subtask (Priority: P1)

A consumer (the CLI backfill path, the SaaS ingest, or any downstream reader) reads a
`status.events.jsonl` through `parse_diary` / `WPInnerStateDelta.from_dict`. One annotation
marks a subtask `skipped`.

**Why this priority**: This is the crash the mission exists to remove. Until it is fixed,
a single skipped mark makes an entire log unreadable through the shared contract, and the
CLII cannot drop its forbidden local copy.

**Independent Test**: Feed a log containing `{"subtasks": {"T001": "skipped"}}` through
`parse_diary`; it parses without error and the delta's `subtasks["T001"]` is the canonical
skipped value.

**Acceptance Scenarios**:

1. **Given** a `WPInnerStateDelta` annotation whose `subtasks` map carries `skipped`,
   **When** a consumer parses the log via `WPInnerStateDelta.from_dict` / `parse_diary`,
   **Then** parsing succeeds and the subtask value round-trips back to `skipped` on
   serialization.
2. **Given** an annotation whose `subtasks` map carries the input alias `not_applicable`,
   **When** it is parsed, **Then** it is accepted and normalized to `skipped`, and
   re-serialization emits `skipped` (never `not_applicable`).

---

### User Story 2 - A consumer asks whether a subtask is terminal (Priority: P2)

A consumer (the CLI's `for_review` gate, status views) needs to know whether a subtask is
finished — `done` or `skipped` — without hard-coding the set of terminal values.

**Why this priority**: It lets consumers depend on the contract instead of re-deriving
terminality, which is how the drift this mission fixes crept in originally.

**Independent Test**: Call the package-provided terminal-subtask predicate/constant for
each `SubtaskStatus` value and confirm it reports `done` and `skipped` as terminal and the
rest as non-terminal.

**Acceptance Scenarios**:

1. **Given** a subtask value of `done` or `skipped`, **When** a consumer checks terminality
   through the package predicate/constant, **Then** it reports terminal.
2. **Given** a non-terminal value (e.g. `in_progress`), **When** the same check runs,
   **Then** it reports non-terminal.

### Edge Cases

- A subtask value that is neither a `Lane` value, `skipped`, nor `not_applicable` (e.g. a
  typo) is rejected as an unknown value — fail-closed, unchanged from today.
- A pre-existing log whose subtasks only use `Lane` values (e.g. `done`, `in_progress`)
  parses and reduces exactly as before (every current value is a subset of the new
  vocabulary).
- An empty or absent `subtasks` map continues to parse unchanged.

## Requirements *(mandatory)*

### Functional Requirements

| ID | Requirement | Delivery | No-op passable? |
|---|---|---|---|
| **FR-001** | The diary contract MUST expose a public subtask-status vocabulary distinct from `Lane` — the existing `Lane` values plus a terminal `skipped` — as `SubtaskStatus` (with a `SubtaskValue` alias), exported from the package public surface. | `[build]` | no |
| **FR-002** | `WPInnerStateDelta.subtasks` MUST parse and serialize with `SubtaskStatus` in place of `Lane`; `skipped` MUST parse. The input alias `not_applicable` MUST be accepted on parse and normalized to `skipped`, and MUST never be persisted (serialization emits `skipped`). | `[build]` | no |
| **FR-003** | The package MUST expose one predicate or constant identifying a terminal subtask (`done` or `skipped`) so consumers (the CLI `for_review` gate, views) consume the contract instead of hard-coding the terminal set. | `[build]` | no |
| **FR-004** | The reducer MUST fold a `skipped` subtask like any other subtask value (same per-subtask merge semantics). | `[build]` | no |
| **FR-005** | A committed conformance fixture MUST include a `skipped` subtask, and a test MUST round-trip a skipped-subtask annotation through `WPInnerStateDelta.from_dict` / `parse_diary`. | `[build]` | no |
| **FR-006** | Every pre-existing log (subtask values drawn from the current `Lane` set) MUST parse and reduce unchanged — no existing fixture or log regresses. | `[ratchet]` | yes — positive control: the FR-005 `skipped` fixture in the same suite |
| **FR-007** | The change MUST ship as package minor version **10.6.0** with a CHANGELOG entry describing the user-facing impact. | `[build]` | no |

### Non-Functional Requirements

| ID | Requirement | Threshold | Delivery | No-op passable? |
|---|---|---|---|---|
| **NFR-001** | Backward-compatible parse coverage. | 100% of the existing committed fixtures and diary-parse tests pass with 0 regressions after the change. | `[ratchet]` | yes — control: FR-005 skipped case |
| **NFR-002** | Deterministic reduction. | Reducing a log containing `skipped` subtasks yields the same result across input-row permutations (consistent with the existing reducer determinism contract). | `[build]` | no |

### Constraints

| ID | Constraint |
|---|---|
| **C-001** | The package MUST remain independent of consumer repositories (`spec-kitty`, `spec-kitty-saas`, `spec-kitty-tracker`, `spec-kitty-runtime`) — charter Package Boundary. |
| **C-002** | The persisted/canonical subtask vocabulary MUST match the shipped producer-contract JSON schema (schemas and fixtures are the single source of truth); `not_applicable` is input-only and never appears in the canonical enum or serialized output. |
| **C-003** | The on-wire envelope schema version stays `3.0.0`; this ships as a package **minor** (additive vocabulary = SemVer minor, 10.6.0). |

### Key Entities

- **SubtaskStatus / SubtaskValue**: the subtask-completion vocabulary carried by
  `WPInnerStateDelta.subtasks` — the `Lane` values plus terminal `skipped`. Distinct from
  `Lane` (which governs work-package lane transitions).
- **`not_applicable`**: an input-only alias for `skipped`; accepted on parse, normalized to
  `skipped`, never persisted.
- **WPInnerStateDelta.subtasks**: the `{subtask_id: SubtaskStatus}` map on the inner-state
  annotation.

## Domain Language *(canonical terms)*

- **Subtask status** (canonical) — the completion state of a single subtask; NOT a "lane".
  Avoid conflating it with the work-package **Lane** vocabulary, even though the values
  overlap today.
- **Terminal subtask** — a subtask whose status is `done` or `skipped`.
- **`skipped`** (canonical persisted value) — a subtask deliberately not done. The alias
  **`not_applicable`** is input-only and resolves to `skipped`.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A status log containing a `skipped` subtask parses end-to-end via
  `parse_diary` / `WPInnerStateDelta.from_dict` with 0 `ValueError`. — `[build]` · no-op passable: no
- **SC-002**: 100% of pre-existing committed fixtures and logs parse and reduce identically
  (0 regressions). — `[ratchet]` · no-op passable: yes (control: the SC-001 skipped log)
- **SC-003**: A consumer determines subtask terminality (`done` or `skipped`) through one
  package-provided predicate/constant, with 0 hard-coded value sets required. — `[build]` · no-op passable: no
- **SC-004**: The package ships as `10.6.0` with a CHANGELOG entry and the
  `check_version_not_amended` gate green. — `[build]` · no-op passable: no

## Assumptions

- The canonical persisted subtask vocabulary is exactly the current `Lane` member set plus
  `skipped`, matching the already-shipped `wp.innerstate.changed.v1` producer-contract
  schema enum (`genesis, planned, claimed, in_progress, for_review, in_review, approved,
  done, blocked, canceled, skipped`).
- Downstream (NOT in this mission): spec-kitty bumps its `spec-kitty-events` pin, re-exports
  the upstream `SubtaskStatus` instead of its local definition, and switches the backfill
  path to the shared parser; spec-kitty#5953 is held for this.

## Out of Scope

- Any change to the CLI (`spec-kitty`) — the downstream re-export and backfill switch are
  tracked there, not here.
- Any change to the `Lane` vocabulary itself or to lane-transition semantics.
- The on-wire envelope version (`3.0.0`) and the CloudEvents cutover (ADR 0001).

Closes #76.
