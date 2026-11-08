# Decision Moment `01M6X2Q4YK41J0Q03F55NAPS8Y`

- **Mission:** `events-write-core-01M6TP6W`
- **Origin flow:** `plan`
- **Slot key:** `plan.contract.stale-from-lane-reason`
- **Input key:** `stale_from_lane_reason`
- **Status:** `resolved`
- **Created:** `2026-11-08T07:59:40.499034+00:00`
- **Resolved:** `2026-11-08T07:59:41.941925+00:00`
- **Resolved by:** `maintainer`
- **Opened by:** `claude`
- **Other answer:** `false`

## Question

Does a stale from_lane refusal (TRANSITION_REJECTED) carry a reason URN?

## Options

_(none)_

## Final answer

Yes. Maintainer ruling 2026-11-08: add urn:spec-kitty:write-refusal:stale_from_lane to TRANSITION_REJECTED and to the mission-write 1.0.1-SNAPSHOT provisional code list, noted as an events-side addition with no core equivalent; if a code proves impossible, return a clear human-readable detail. Either outcome is covered by a test. Supersedes research §11 decision 5's no-type default.

## Rationale

A client needs to tell a stale from_lane (re-read and retry) from an illegal edge; core has no equivalent because its client reads the lane itself.

## Change log

- `2026-11-08T07:59:40.499034+00:00` - opened
- `2026-11-08T07:59:41.941925+00:00` - resolved (final_answer="Yes. Maintainer ruling 2026-11-08: add urn:spec-kitty:write-refusal:stale_from_lane to TRANSITION_REJECTED and to the mission-write 1.0.1-SNAPSHOT provisional code list, noted as an events-side addition with no core equivalent; if a code proves impossible, return a clear human-readable detail. Either outcome is covered by a test. Supersedes research §11 decision 5's no-type default.")
