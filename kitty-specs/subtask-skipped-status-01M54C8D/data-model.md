# Phase 1 Data Model: Subtask status vocabulary

## Entity: `SubtaskStatus` (enum, `(str, Enum)`)

The completion vocabulary carried by `WPInnerStateDelta.subtasks`. Distinct from
`Lane` (which governs work-package lane transitions), even though the values
overlap today.

| Member | Value | Terminal? | Source |
|---|---|---|---|
| `GENESIS` | `genesis` | no | from `Lane` |
| `PLANNED` | `planned` | no | from `Lane` |
| `CLAIMED` | `claimed` | no | from `Lane` |
| `IN_PROGRESS` | `in_progress` | no | from `Lane` |
| `FOR_REVIEW` | `for_review` | no | from `Lane` |
| `IN_REVIEW` | `in_review` | no | from `Lane` |
| `APPROVED` | `approved` | no | from `Lane` |
| `DONE` | `done` | **yes** | from `Lane` |
| `BLOCKED` | `blocked` | no | from `Lane` |
| `CANCELED` | `canceled` | no | from `Lane` |
| `SKIPPED` | `skipped` | **yes** | **new** |

Canonical member set MUST equal the active `Lane` value set plus `skipped`,
matching the shipped `wp.innerstate.changed.v1` schema `subtasks` enum. A unit
assertion pins this equality so the duplicated member list cannot drift.

> `Lane.UNINITIALIZED` is a read-only sentinel, never persisted and never a
> subtask value — it is excluded from `SubtaskStatus`, consistent with the
> schema enum (which omits `uninitialized`).

## Aliases

- **`SubtaskValue`** — published alias of `SubtaskStatus` (forward-looking name
  consumers import).
- **`Status`** — existing type alias (`diary.py:207`), repointed from `Lane` to
  `SubtaskStatus` for backward compatibility.

## Input alias: `not_applicable`

- Accepted **only** at the parse boundary (`WPInnerStateDelta.from_dict`,
  `diary.py:~726`).
- Normalized to `SubtaskStatus.SKIPPED` immediately on parse.
- NEVER a canonical enum member; NEVER serialized (re-serialization emits
  `skipped`); NEVER reaches the reducer.

## Terminal-subtask predicate

A package-exported predicate (e.g. `is_terminal_subtask(value: SubtaskStatus) ->
bool`) or constant set identifying the terminal subtask statuses
`{DONE, SKIPPED}`, so consumers (CLI `for_review` gate, status views) consume
the contract instead of hard-coding the terminal set.

## `WPInnerStateDelta.subtasks`

`Mapping[str, SubtaskStatus] | None` — the `{subtask_id: SubtaskStatus}` map on
the inner-state annotation.

- **Parse** (`from_dict`): each value → `SubtaskStatus(value)`, with
  `not_applicable` → `SubtaskStatus.SKIPPED` normalization first; unknown values
  still fail closed.
- **Serialize** (`to_dict`): `{sid: str(status)}` — unchanged shape; `skipped`
  round-trips as `skipped`.
- **Reduce** (`diary.py:1373-1377`): per-subtask replace, status-agnostic — no
  change needed; `skipped` folds like any other value.

## Validation rules

- Unknown subtask value (not a `Lane` value, not `skipped`, not the
  `not_applicable` alias) → fail closed (`ValueError` / `DiaryError`), unchanged
  from today.
- Empty or absent `subtasks` map → parses unchanged.
- Every pre-existing log (subtask values drawn from the current `Lane` set) →
  parses and reduces identically (FR-006 / NFR-001).
