# mission-write 1.0.1-SNAPSHOT: the delta this mission makes

All changes are additive or descriptive (C-008). No closed response changes. The protocol major stays 1.

## Added

- `schemas/WriteOperation.yaml`: a `string` enum with the values `transition`, `start_implementation` and `start_review`.
  - `transition` is core's emit pipeline, as `move-task`, `mark-status` and `status emit` use it.
  - `start_implementation` and `start_review` are core's lifecycle starts (`work_package_lifecycle.start_implementation_status` and `start_review_status`), which carry the claim rules.
- `TransitionUnit.operation`: optional, `$ref: WriteOperation`; absent means `transition`. `TransitionUnit` stays `additionalProperties: false`, so a 1.0.1-SNAPSHOT client that omits the field is unaffected.
- Example `TransitionUnit.start-implementation.yaml`.
- `contracts/tools/enum_pins.json` pins `WriteOperation`.

## Changed (descriptions only)

- `STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED`: also answers a torn tail (refused, not repaired), a log the write fold cannot reduce, and a failed write or sync after which the append was cut back.
- `WRITE_LOCATION_UNVERIFIED`: also answers a `coordination_ref_commit` unit before migration step 4, ahead of any Mission lookup. On `checkout_append` it answers when `checkout` is not `.`, `destinationRef` is not null, or `missionDirectory` is not the directory of the Mission named by `missionId`.
- `TRANSITION_REJECTED`: may carry no reason URN when the unit's `from_lane` is not the work package's current lane (a stale unit).
- `REQUEST_MALFORMED`: also answers a row of the wrong kind for its position, a lane alias, a non-finite number and a session-time pair that cannot be subtracted.
- `SERVICE_BUSY`: names the 10 s budget shared by the in-process turn and the Mission lock.
- `WriteRefusalReason.dependencies_unsatisfied`: a dependency is satisfied when `approved`, `done`, or `canceled` with operator provenance. `unknown_lane`: the lane vocabulary is the persisted `Lane` set.

## Compatibility

- Core's client (core #6195) sets `operation` for the lifecycle starts, and must not hold the status lock while calling the service.
