# Changelog

All notable changes to the `mission-write` contract are recorded here, newest first. The module
version is the `info.version` of `openapi.yaml`.

## 1.0.1-SNAPSHOT

The coordination write without a coordination worktree (spec-kitty/spec-kitty#7284, epic
spec-kitty/spec-kitty#7276; [ADR-0007](../../docs/adr/0007-write-service-commits-coordination-status-by-ref.md)).
Not yet released. `1.0.0-SNAPSHOT`
shipped inside the `service-v0.1.0-rc.1` and `rc.2`
pre-releases, so by operator ruling of 2026-11-06 it is treated like a release candidate: this
change opens `1.0.1-SNAPSHOT` instead of rewriting it, and a new `service-v*` release candidate
can carry it. The change removes enum values, so read against a released `1.0.0` it would be a
major; no `1.0.0` was ever cut. The versioning rule in [`contracts/README.md`](../README.md)
records the pre-final-release line `1.0.Z`, and `breaking_check.py` accepts `1.0.Z[-SNAPSHOT]`
with its CHANGELOG heading while no release exists (`NO_BASELINE_INITIAL_VERSION`; still not run
by a workflow, #151). A consumer pinned to `service-v0.1.0-rc.1` or `rc.2` keeps the old bundle
until it moves its pin.

Write-service cut-over readiness (#236) adds, inside the same unreleased version, an explicit
status-log repair, the implementation-evidence wire input and operator-facing wording, all needed
before spec-kitty/spec-kitty#6195 removes core's status-log writer. These additions are additive.

The window-opening record `policy_metadata.wp_window` (spec-kitty/spec-kitty-events#229) is typed in
the same unreleased version, for `service-v0.1.0-rc.3`. It documents a key the CLI already sends and
the service already relays; no request the service accepted before is refused.

### Added

- `EventRow.policy_metadata.wp_window`, typed by the closed `_shared` schemas `WpWindow`,
  `WpWindowGap`, `WpWindowGapReason` (`capture_failed`, `lane_branch_missing`) and `WpWindowGapCause`
  (`git_error`, `probe_error`), exactly as core's
  `kitty-specs/consolidation-window-provenance-01M6Y84D/contracts/status-event-wp-window.md` states
  `schema_version` 1. The rest of `policy_metadata` stays untyped. The service relays the value
  unchanged and never refuses, delays or retries a unit because of it, even a value outside the
  schema (contract rules W-4, W-7); it takes no lane-head reading of its own. Example
  `TransitionUnit.claim-wp-window.yaml`; both gap vocabularies are pinned in `enum_pins.json`
  (#229).
- `POST /missions/{missionId}/status-log-repairs`: an explicit, operator-invoked and locked repair,
  separate from the write path, with the closed `StatusLogRepairRequest`, `StatusLogRepairAck` and
  `StatusLogRepairAction` schemas. The actions are `none`, `completed` and `quarantined`; examples
  cover the request and all three actions, and `enum_pins.json` pins the action vocabulary
  (#236; #222).
- Optional `TransitionUnit.implementationEvidencePresent` (boolean or null): the unforced
  `in_progress` to `for_review` guard uses an explicit boolean verbatim and infers only when the
  field is absent or null. It is request-only and never persisted (#236; #224).
- `WriteOperation`: the values `transition` (core's emit pipeline, as `move-task`, `mark-status` and
  `status emit` use it), `start_implementation` and `start_review` (core's lifecycle starts, which
  carry the claim rules). A start from `planned` travels as two units of a batch (spec-kitty/spec-kitty-events#160,
  #161 and #155).
- Optional `TransitionUnit.operation`, referencing `WriteOperation`; absent means `transition`. The
  field is additive, and a client that omits it is unaffected (spec-kitty/spec-kitty-events#160, #161
  and #155).
- Example `TransitionUnit.start-implementation.yaml`, a claimed work package resumed by
  `start_implementation` with one `claimed` to `in_progress` row (spec-kitty/spec-kitty-events#160,
  #161 and #155).
- The `WriteOperation` enum pin (spec-kitty/spec-kitty-events#160, #161 and #155).
- `WriteSurface` value `coordination_ref_commit`: the service commits the appended event log
  straight onto the coordination branch named by `destinationRef`, from git objects, with a
  compare-and-swap update of the ref; no coordination worktree is read or written.
- `RequestContext`: `destinationRef` is a non-empty string whenever `writeSurface` is
  `coordination_ref_commit` (an `if`/`then` in the schema).
- Three protocol-only refusal codes: `COORDINATION_REF_MOVED` (409, the compare-and-swap lost;
  nothing written, no retry), `COORDINATION_REF_CHECKED_OUT` (409, the coordination branch is
  checked out in a worktree) and `COORDINATION_LEDGER_UNREADABLE` (500, the event log in the tree
  of the branch tip cannot be read or parsed). They are marked provisional in their descriptions
  until migration step 4 serves the surface.
- `TransitionBatch`: every unit names the same `missionDirectory`, `writeSurface` and
  `destinationRef`, since a batch is one write (one commit for `coordination_ref_commit`);
  `command-cli` refuses a mixed batch as `REQUEST_MALFORMED`.
- Examples `WriteRefusal.coordination-ref-moved.yaml` and `DurabilityAck.ref-commit.yaml`.
- `WriteRefusalReason.stale_from_lane` (`TRANSITION_REJECTED`): a unit's `from_lane` is not the
  work package's current lane, and `WriteRefusal` binds its `type` to `TRANSITION_REJECTED`. This
  is an events-side addition with no core equivalent (spec-kitty/spec-kitty-events#160, #161 and
  #155).

### Changed

- `WriteRefusalReason.workspace_context_required` records that no wire request can produce it: every
  wire door supplies or synthesises a context, while core retains its fail-closed batch-API guard
  (operator decision 2026-09-07, #946, DRIFT-3) so the vocabulary mirrors core (#236; #223).
- `WriteRefusalReason.implementation_evidence_required` names both reachable halves of the handoff
  guard: the roster supplies the subtask half and `implementationEvidencePresent: false` supplies
  the evidence half (#236; #224).
- `REQUEST_MALFORMED` includes read-back verification before append: JSON nesting beyond 256, an
  integer beyond 4300 digits, an actor object the fold cannot decode, or a unit the fold rejects;
  a batch refusal carries `instance` (#236; #231).
- `SERVICE_DRAINING` in `ServiceBusy` and `/shutdown`, together with
  [`LAUNCHER.md`](./LAUNCHER.md), now says that a write or repair still waiting for the turn or
  Mission lock when draining begins answers 503 and writes nothing, while one holding the lock
  finishes. The 5 s drain and 10 s SIGTERM timings are unchanged (#236; #231).
- `STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED` also describes a torn tail (refused, not repaired),
  a log the write fold cannot reduce, and a failed write or sync after which the append was cut
  back (spec-kitty/spec-kitty-events#160, #161 and #155).
- `WRITE_LOCATION_UNVERIFIED` also describes a `coordination_ref_commit` refusal before migration
  step 4, ahead of any Mission lookup, and the `checkout_append` mismatches of `checkout`,
  `destinationRef` and `missionDirectory` (spec-kitty/spec-kitty-events#160, #161 and #155).
- `TRANSITION_REJECTED` carries `WriteRefusalReason.stale_from_lane` for a stale unit whose
  `from_lane` is not the work package's current lane (spec-kitty/spec-kitty-events#160, #161 and
  #155).
- `REQUEST_MALFORMED` also describes a row of the wrong kind for its position, a lane alias or
  non-canonical lane spelling, a non-finite number, and a session-time pair that cannot be
  subtracted (spec-kitty/spec-kitty-events#160, #161 and #155).
- `SERVICE_BUSY` names the 10 s budget shared by the in-process turn and the Mission lock
  (spec-kitty/spec-kitty-events#160, #161 and #155).
- `DEPENDENCIES_NOT_SATISFIED` records that a dependency is satisfied when `approved`, `done`, or
  `canceled` with operator provenance (spec-kitty/spec-kitty-events#160, #161 and #155).
- `WriteRefusalReason.dependencies_unsatisfied` records the same `approved`, `done`, or
  operator-provenanced `canceled` satisfaction rule (spec-kitty/spec-kitty-events#160, #161 and
  #155).
- `WriteRefusalReason.unknown_lane` names the persisted `Lane` vocabulary of 11 values, not the nine
  display lanes (spec-kitty/spec-kitty-events#160, #161 and #155).
- `WriteRefusalReason` records that the CLI raises the core-derived reasons as text today, and that
  the events-side `stale_from_lane` addition is provisional (spec-kitty/spec-kitty-events#160,
  #161 and #155).
- `checkout_append` names the repository root checkout only; a lane worktree is never a status
  surface (operator rulings of 2026-11-06 on spec-kitty/spec-kitty#7276).
- `RequestContext.checkout` names the caller's checkout; `missionDirectory` is, for
  `coordination_ref_commit`, the Mission directory's path in the tree of `destinationRef`.
- `DurabilityAck`: durability of a `coordination_ref_commit` means the commit's objects and the
  moved ref are synced; `statusCommit` is always the commit the ref moved to (the surface is
  served from migration step 4), and stays null for `checkout_append` until that step.
  `statusCommit` stays provisional until migration step 4 of core ADR 2026-10-22-1.
  `BatchDurabilityAck.fsyncs` counts durable writes (a synced append or a synced commit), and its
  two-unit example now names the batch's one commit.
- The operation, batch, `PlacementRefused` and `WriteConflict` descriptions name the ref write;
  `WriteRefusalCode` says the coordination write changes codes on purpose.
- `COORDINATION_BRANCH_DELETED` names the `destinationRef` of a `coordination_ref_commit`.
- The example batch `TransitionBatch.two-units.yaml` uses `coordination_ref_commit`.

### Removed

- `WriteSurface` value `coordination_transaction_append` (an append in the coordination
  worktree), replaced by `coordination_ref_commit`.
- The five refusal codes that presuppose a coordination worktree:
  `COORD_STATUS_SURFACE_DIVERGED`, `COORD_STATUS_SURFACE_UNREADABLE`,
  `BOOKKEEPING_WORKTREE_MISSING`, `FALLBACK_COORD_WORKTREE_UNRESOLVED` and
  `COORDINATION_WORKTREE_UNMATERIALIZED`. With no working tree, the committed tree is the only
  state, so divergence cannot occur and there is nothing to materialize. `WriteRefusalCode` has
  27 codes: 15 are the CLI's codes verbatim and 12 are protocol-only.

### Provisional

- `WriteOperation` is provisional: its three values are settled, but its closure is not because the
  vocabulary follows core's operation doors and a new door adds a value. Consequently the enum of
  `TransitionUnit.operation` leaves `breaking_check` coverage when provisional elements are stripped,
  but the property itself does not: it only references the provisional schema, so its presence and its
  requiredness stay compared (D11, 2026-11-10). `enum_pins.json` still guards the three values (#236; #231).
- The protocol-only codes `COORDINATION_REF_MOVED`, `COORDINATION_REF_CHECKED_OUT` and
  `COORDINATION_LEDGER_UNREADABLE` are provisional until migration step 4 serves the surface.
  As with the provisional codes of `1.0.0-SNAPSHOT`, an enum value cannot
  carry `x-provisional`, so they are marked in their descriptions only and `provisional_check.py`
  does not see them.
- `WriteRefusalReason.stale_from_lane` is a provisional events-side addition with no core
  equivalent. As with the provisional codes above, an enum value cannot carry `x-provisional`, so
  it is marked in its description only and `provisional_check.py` does not see it.
- `statusCommit` of `DurabilityAck` stays provisional until migration step 4 lands.

### Compatibility

- The protocol major stays 1: a client of the `rc.1`/`rc.2` bundle sending
  `coordination_transaction_append` gets `REQUEST_MALFORMED`, not
  `PROTOCOL_VERSION_UNSUPPORTED`. Accepted because no client transport exists yet, and
  `coordination_ref_commit`, the surface that changes, is refused until migration step 4
  (ADR-0007 D-7).
- Core is unaffected until it moves its pin (`service-v0.1.0-rc.1` today). That one tag also
  carries the read-service build, so a read-service pin bump takes this contract change too.
  Core then changes:
  - `src/specify_cli/status/write_service_protocol.py`: `WriteSurface` and the surface-by-target
    map; `WriteRefusalCode`, the status families and `PROTOCOL_ONLY_CODES` (five codes out, three
    in); `transition_unit_from` sets `destination_ref` and a tree-relative `mission_directory`
    for `coordination_ref_commit` (core ADR 2026-10-27-1 D-3 changes; core C-1 records it);
  - `tests/contract/fixtures/mission_write_refusal_parity.yaml`: five `cli` rows out, three
    `protocol` rows in;
  - `tests/contract/test_mission_write_contract.py`: the refusal-example code list gains
    `COORDINATION_REF_MOVED`; the `DurabilityAck` example lookup with `replayed=False` now
    matches two examples; the hard-coded surface list; the unit built for the coordination
    surface; the lane-worktree checkout case.

## 1.0.0-SNAPSHOT

Initial entry (#6675, ADR 2026-10-27-1). This version is not yet released: it becomes `1.0.0` in
the release commit, and per
[ADR-0003](../../docs/adr/0003-gradle-build-and-service-release-line.md) the release ships as this
module's bundle in `contract-bundles-<ver>.tar.gz` on a `service-v*` release; the standalone
`contract-mission-write-v1.0.0` tag line is retired. `breaking_check.py` resolves
baselines only from that retired tag line, so it has no baseline and reports
`NO_BASELINE_INITIAL_VERSION`; no workflow runs it yet (#151).

### Added

- The bearer security scheme `writeToken`, required by every operation (the token is the one in
  the endpoint file of the git common dir).
- `GET /handshake`: the protocol majors the service speaks, its version, its instance id and the
  identity of the clone it serves (`Handshake`). It needs no protocol header.
- `GET /health`: `ready` or `draining`, and the instance id (`Health`, `HealthStatus`). Like the
  handshake it needs no protocol header.
- `POST /missions/{missionId}/work-package-transitions`: one transition unit (`TransitionUnit`:
  the rows in order, the request context) acknowledged after one synced write (`DurabilityAck`).
- `POST /missions/{missionId}/work-package-transitions/batch`: up to 64 units in one write and
  one `fsync`, all or nothing (`TransitionBatch`, `BatchDurabilityAck`).
- `POST /missions/{missionId}/inner-state-annotations`: one standalone annotation
  (`AnnotationRequest`).
- `POST /shutdown`: drain and exit.
- The header parameter `Spec-Kitty-Write-Protocol` (`ProtocolVersion`), required by every
  operation except `handshake` and `health`.
- `EventRow`: the row shape is the spec-kitty-events envelope (spec-kitty/spec-kitty-events#92),
  referenced, not redefined; only `event_id` is required here.
- `RequestContext` and `WriteSurface`: the actor, the caller's checkout and the resolved write
  location the service verifies.
- `WriteRefusal`, `WriteRefusalCode` (29 codes; 20 are the CLI's codes verbatim and 9 are
  protocol-only) and `WriteRefusalReason` (15 guard reasons as URNs, each bound to its code), with
  one response per status family. A refused batch names the failing unit in `instance`.
- [`LAUNCHER.md`](./LAUNCHER.md): the launcher contract, the process lifecycle around this API
  (start with `serve --git-common-dir`, the single-instance lock, the three-part readiness check,
  stop through `/shutdown` with a `SIGTERM` fallback). The operator accepted it on 2026-10-29; it is
  recorded here at the cutover (spec-kitty/spec-kitty-events#147). Prose only: the bundle is unchanged.

### Changed

- `POST /shutdown` and the `draining` value of `HealthStatus` are settled, no longer provisional:
  `command-cli` implements them (spec-kitty/spec-kitty-events#156; operator rulings of
  2026-10-31). The operation's `x-provisional` and the schema's are removed; the shapes are
  unchanged.
- `HealthStatus` names `SERVICE_DRAINING` (503, no Retry-After) as the refusal of a write while
  draining; it said `SERVICE_BUSY`, which contradicted `WriteRefusalCode` and `ServiceBusy`.
- [`LAUNCHER.md`](./LAUNCHER.md): "Stop" is no longer provisional; the paths are under
  `/api/v1` (core's client follows in spec-kitty/spec-kitty#6910); the idle timeout defaults to
  1800 s; the service logs to stdout and stderr, which the launcher redirects into
  `service.log`. Prose only: the bundle changes only by the removed markers and texts.

### Removed

- Nothing yet.

### Provisional

- `statusCommit` and `replayed` of `DurabilityAck`: when the status commit joins the unit, and
  idempotent retry.
- `WriteRefusalReason`: the guard reason vocabulary and its carriage in `type`.
- The protocol-only codes `EVENT_ID_CONFLICT`, `SERVICE_BUSY`, `REQUEST_TOO_LARGE` and
  `WRITE_LOCATION_UNVERIFIED` are provisional (`SERVICE_DRAINING` was settled with `/shutdown`). An enum value cannot carry
  `x-provisional`, so they are marked in their descriptions only, and `provisional_check.py` does not
  see them: removing one of them later is a breaking change.
- Every provisional element is settled before the first release (`1.0.0`): after it, a change to
  a closed response is a major version.
