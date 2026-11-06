# Changelog

All notable changes to the `mission-write` contract are recorded here, newest first. The module
version is the `info.version` of `openapi.yaml`.

## 1.0.1-SNAPSHOT

The coordination write without a coordination worktree (spec-kitty/spec-kitty#7284, epic
spec-kitty/spec-kitty#7276; [ADR-0007](../../docs/adr/0007-write-service-commits-coordination-status-by-ref.md)).
Not yet released. `1.0.0-SNAPSHOT` shipped inside the `service-v0.1.0-rc.1` and `rc.2`
pre-releases, so by operator ruling of 2026-11-06 it is treated like a release candidate: this
change opens `1.0.1-SNAPSHOT` instead of rewriting it, and a new `service-v*` release candidate
can carry it. The change removes enum values, so read against a released `1.0.0` it would be a
major; no `1.0.0` was ever cut, and `breaking_check.py` still has no baseline
(`NO_BASELINE_INITIAL_VERSION`, #151). A consumer pinned to `service-v0.1.0-rc.1` or `rc.2`
keeps the old bundle until it moves its pin.

### Added

- `WriteSurface` value `coordination_ref_commit`: the service commits the appended event log
  straight onto the coordination branch named by `destinationRef`, from git objects, with a
  compare-and-swap update of the ref; no coordination worktree is read or written.
- `RequestContext`: `destinationRef` is a non-empty string whenever `writeSurface` is
  `coordination_ref_commit` (an `if`/`then` in the schema).
- Three protocol-only refusal codes: `COORDINATION_REF_MOVED` (409, the compare-and-swap lost;
  nothing written, no retry), `COORDINATION_REF_CHECKED_OUT` (409, the coordination branch is
  checked out in a worktree) and `COORDINATION_LEDGER_UNREADABLE` (500, the event log in the tree
  of the branch tip cannot be read or parsed).
- Examples `WriteRefusal.coordination-ref-moved.yaml` and `DurabilityAck.ref-commit.yaml`.

### Changed

- `checkout_append` names the repository root checkout only; a lane worktree is never a status
  surface (operator rulings of 2026-11-06 on spec-kitty/spec-kitty#7276).
- `RequestContext.checkout` names the caller's checkout; `missionDirectory` is, for
  `coordination_ref_commit`, the Mission directory's path in the tree of `destinationRef`.
- `DurabilityAck`: durability of a `coordination_ref_commit` means the commit's objects and the
  moved ref are synced; `statusCommit` names the commit the ref moved to. `statusCommit` stays
  provisional until migration step 4 of core ADR 2026-10-22-1.
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

### Compatibility

- Core's client (`src/specify_cli/status/write_service_protocol.py`) and its parity fixture
  (`tests/contract/fixtures/mission_write_refusal_parity.yaml`) follow when core moves its pin
  to a `service-v*` release that carries this version; the pinned `rc.1` bundle is unaffected.

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
