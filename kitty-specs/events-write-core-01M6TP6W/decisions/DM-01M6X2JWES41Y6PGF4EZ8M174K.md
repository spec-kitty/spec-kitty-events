# Decision Moment `01M6X2JWES41Y6PGF4EZ8M174K`

- **Mission:** `events-write-core-01M6TP6W`
- **Origin flow:** `plan`
- **Slot key:** `plan.contract.operation-discriminator`
- **Input key:** `operation_discriminator`
- **Status:** `resolved`
- **Created:** `2026-11-08T07:57:20.729529+00:00`
- **Resolved:** `2026-11-08T07:57:29.984167+00:00`
- **Resolved by:** `maintainer`
- **Opened by:** `claude`
- **Other answer:** `false`

## Question

Does the mission-write 1.0.1-SNAPSHOT contract keep the optional operation field (WriteOperation, TransitionUnit.operation)?

## Options

_(none)_

## Final answer

Yes. Maintainer ruling 2026-11-08: keep the optional operation field in the mission-write 1.0.1-SNAPSHOT contract (research §11 decision 2, FR-024).

## Rationale

Core's claim conflicts and start_rejected come only from start_implementation_status and start_review_status; the emit pipeline accepts the same rows, so the service needs the client to name the operation. The field is optional and additive.

## Change log

- `2026-11-08T07:57:20.729529+00:00` - opened
- `2026-11-08T07:57:29.984167+00:00` - resolved (final_answer="Yes. Maintainer ruling 2026-11-08: keep the optional operation field in the mission-write 1.0.1-SNAPSHOT contract (research §11 decision 2, FR-024).")
