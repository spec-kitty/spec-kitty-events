---
section: Changed
---

- `contracts/mission-write` `1.0.1-SNAPSHOT` gains `WriteOperation` and the optional
  `TransitionUnit.operation`, adds the events-side `WriteRefusalReason.stale_from_lane` reason
  for a unit whose `from_lane` is not the work package's current lane, and widens the
  descriptions of `TRANSITION_REJECTED`, `DEPENDENCIES_NOT_SATISFIED`, `REQUEST_MALFORMED`,
  `STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED`, `WRITE_LOCATION_UNVERIFIED`, `SERVICE_BUSY`
  and the refusal-reason vocabulary (spec-kitty/spec-kitty-events#160, #161 and #155).
