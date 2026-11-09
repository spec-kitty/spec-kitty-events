# Decision Moment `01M6TP7M0131HW6XEAHR0GE8C3`

- **Mission:** `events-write-core-01M6TP6W`
- **Origin flow:** `specify`
- **Slot key:** `specify.scope.adr-0007-commit-path`
- **Input key:** `adr_0007_commit_path`
- **Status:** `resolved`
- **Created:** `2026-11-07T09:42:59.841266+00:00`
- **Resolved:** `2026-11-07T09:43:01.064182+00:00`
- **Opened by:** `cli`
- **Other answer:** `false`

## Question

How does the write core treat ADR-0007/0008 and mission-write 1.0.1-SNAPSHOT (statusCommit, coordination_ref_commit)?

## Options

_(none)_

## Final answer

Operator ruling 2026-11-07: they are accepted and honoured. statusCommit stays null and coordination_ref_commit is not served before migration step 4; the writer must not foreclose the ref-commit path.

## Rationale

_(none)_

## Change log

- `2026-11-07T09:42:59.841266+00:00` - opened
- `2026-11-07T09:43:01.064182+00:00` - resolved (final_answer="Operator ruling 2026-11-07: they are accepted and honoured. statusCommit stays null and coordination_ref_commit is not served before migration step 4; the writer must not foreclose the ref-commit path.")
