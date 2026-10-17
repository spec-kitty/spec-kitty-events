# Decision Moment `01M54CFGJ91EEZ0QNT3D26PSHZ`

- **Mission:** `subtask-skipped-status-01M54C8D`
- **Origin flow:** `specify`
- **Slot key:** `specify.subtask-status.not-applicable-alias`
- **Input key:** `not_applicable_handling`
- **Status:** `resolved`
- **Created:** `2026-10-17T07:33:33.385019+00:00`
- **Resolved:** `2026-10-17T07:33:35.043847+00:00`
- **Opened by:** `cli`
- **Other answer:** `false`

## Question

Should the events package persist 'not_applicable' as a distinct subtask value, or treat it as an input alias for 'skipped'?

## Options

- alias-normalize-to-skipped
- distinct-persisted-value
- reject-strict

## Final answer

Accept 'not_applicable' on input as an alias and normalize it to 'skipped'; never persist it as a distinct value (serialization emits 'skipped'). Rationale: the CLI (spec-kitty#5953) and the shipped producer-contract schema both treat 'not_applicable' as an alias for 'skipped' with no distinct persisted meaning (the schema subtasks enum contains 'skipped', not 'not_applicable'). Defensive input acceptance avoids a crash on a stray unresolved alias while keeping the canonical/persisted surface aligned with the source-of-truth schema.

## Rationale

_(none)_

## Change log

- `2026-10-17T07:33:33.385019+00:00` — opened
- `2026-10-17T07:33:35.043847+00:00` — resolved (final_answer="Accept 'not_applicable' on input as an alias and normalize it to 'skipped'; never persist it as a distinct value (serialization emits 'skipped'). Rationale: the CLI (spec-kitty#5953) and the shipped producer-contract schema both treat 'not_applicable' as an alias for 'skipped' with no distinct persisted meaning (the schema subtasks enum contains 'skipped', not 'not_applicable'). Defensive input acceptance avoids a crash on a stray unresolved alias while keeping the canonical/persisted surface aligned with the source-of-truth schema.")
