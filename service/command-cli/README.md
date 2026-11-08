# command-cli: the write service

`command-cli` is the events domain's write service, the canonical event writer of core
ADR 2026-10-22-1 (D-1). One resident process serves one git common dir. Core's launcher starts
it, checks that it is ready, talks to it over loopback HTTP and stops it. It ships as a GraalVM
native binary for four targets and as a jar for Java 25, on the `service-v*` release line
([ADR 0003](../../docs/adr/0003-gradle-build-and-service-release-line.md)).

**State of this release.** The write core is built and served (#160, #161):

- the durable status writer: one `O_APPEND` write of encoded JSON lines plus `fsync`, under the
  Mission lock core's Python writer takes, with rollback and a torn-tail refusal;
- the lane authority: the fold, the lane machine and its guards, dependency gating, the claim
  and start rules, in `domain.authority`;
- the protocol edge and its typed body checks (`adapter.in.http.operations`), the write service
  (`application.write.WriteService`) and the durability acknowledgements.

`checkout_append` is served. `coordination_ref_commit` is refused `422 WRITE_LOCATION_UNVERIFIED`
before any Mission lookup until migration step 4, so `statusCommit` is null in every
acknowledgement this release sends. The process contract and the protocol surface are unchanged:
the launcher contract, the handshake, the health check, the request guard, the bearer token, the
protocol version and the size cap, shutdown, idle exit and signal exit.

The lane authority is deliberately separate from the read service's `StatusReducer`: the write
fold in `domain.authority.WriteFold` ports the checks it needs at the pinned core commit, and the
read side is untouched (spec constraint C-010).

## How to run

```bash
java -jar build/libs/command-cli.jar serve --git-common-dir <absolute git common dir> [--idle-timeout <seconds>]
```

The native binary takes the same arguments. That is the whole command line
([`contracts/mission-write/LAUNCHER.md`](../../contracts/mission-write/LAUNCHER.md)):

- `--git-common-dir` is required. It must be absolute and an existing directory.
- `--idle-timeout` is a positive number of seconds. The default is 1800.
- Anything else exits 2 and publishes nothing.

Startup runs in this order:

1. **State directory.** The service prepares `<gcd>/spec-kitty-write-service/` with owner-only
   access (0700, or an owner-only ACL on Windows).
   - If the directory is a symbolic link or not a directory, the process exits 1.
2. **Single-instance lock.** It takes `service.lock` in that directory for its whole life.
   - If another instance holds the lock, it exits 0 without binding or publishing anything.
3. **Identity.** It mints an instance id (ULID) and a token (32 random bytes, base64url).
4. **Server.** It binds `127.0.0.1` on a port the OS picks. BindGuard refuses any other address.
5. **Endpoint file.** It publishes `endpoint.json`: it writes a temporary file, forces it to
   disk and renames it into place, owner-only. The fields are `schema_version` 1, `host`, `port`,
   `token`, `instance_id`, `pid`, `protocol_versions` `[1]` and `started_at`. This matches what
   core's reader accepts (core `src/specify_cli/status/write_service_protocol.py`,
   `read_endpoint_file`).

The service logs to stdout and stderr, which the launcher appends to `service.log`. No log line
carries the token, a request path or a header the caller sent.

## The HTTP surface

Every path is under `/api/v1`, the contract's server prefix
([`contracts/mission-write/openapi.yaml`](../../contracts/mission-write/openapi.yaml)).

| Operation | Answer |
|---|---|
| `GET /api/v1/handshake` | `protocolVersions` `[1]`, `serviceVersion`, `instanceId`, `repositoryId` (sha256 of the forward-slash real path of the git common dir) |
| `GET /api/v1/health` | `status` `ready` or `draining`, `instanceId` |
| `POST /api/v1/shutdown` | `202`, then drain and exit 0 |
| `POST /api/v1/missions/{missionId}/work-package-transitions` | `201` `DurabilityAck` after one synced append, or a refusal |
| `POST /api/v1/missions/{missionId}/work-package-transitions/batch` | `201` `BatchDurabilityAck` after one synced append (1 to 64 units; a bad unit is named as `#/units/<i>`), or a refusal |
| `POST /api/v1/missions/{missionId}/inner-state-annotations` | `201` `DurabilityAck` after one synced append, or a refusal |

A request meets the checks in the contract's order. The first one that fails answers:

| Order | Check | Refusal |
|---|---|---|
| 1 | Host/Origin guard, copied from the read service (`Host` is `127.0.0.1:<port>` or `localhost:<port>`; no forwarded headers; a foreign `Origin` or `Sec-Fetch-Site: cross-site` is refused) | `403 REQUEST_REFUSED` |
| 2 | Bearer token, on every path | `401 WRITE_TOKEN_INVALID` with `WWW-Authenticate: Bearer` |
| 3 | A canonical request target: no path parameter (`;`), no percent-encoding, no empty or dot segment, so the checks below decide on the path the dispatcher routes on | `400 REQUEST_MALFORMED` |
| 4 | `Spec-Kitty-Write-Protocol: 1`, on the shutdown and the writes | `400 PROTOCOL_VERSION_UNSUPPORTED` |
| 5 | Body cap of 1 MiB, on every request body | `413 REQUEST_TOO_LARGE` |
| 6 | Draining | `503 SERVICE_DRAINING` (no `Retry-After`) |
| 7 | Body shape | `400 REQUEST_MALFORMED` |

Every refusal is the contract's `WriteRefusal`, as `application/problem+json`. It never echoes
the token, a host path, a header or row content. A guard refusal also carries `type`, the
`urn:spec-kitty:write-refusal:<reason>` of `RefusalReason`; every other refusal omits it.

## The write path

Three operations reach the status log `<missionDirectory>/status.events.jsonl`:

| Operation | Body | What it writes |
|---|---|---|
| one unit | `TransitionUnit` | the first row is the transition, later rows are its annotations |
| a batch | `TransitionBatch`, 1 to 64 units | the same, for several work packages, in one write |
| a standalone annotation | `AnnotationRequest` | one `kind: annotation` row |

A unit's optional `operation` is `transition` (the default), `start_implementation` or
`start_review`; see [the operation field](#the-operation-field). The body checks in
`adapter.in.http.operations.WriteBodies` are written against the contract's schemas and add the
cross-field and row rules: the members are exactly the schema's, a unit's `wpId` must match every
row's `wp_id`, a transition row carries only persisted lane spellings and no `kind`, an
annotation row carries `kind: annotation`, a `delta` with a non-empty value, and a number is
finite. A batch's units must share `missionDirectory`, `writeSurface` and `destinationRef`,
because a batch is one write (one commit, once the coordination surface is served).

### The checks, in order (FR-026)

The first failure answers, and nothing before the lock is written. `WriteService.write` runs
them in this order:

1. **Edge and shape** (outside `WriteService`): the Host/Origin guard, the bearer token, a
   canonical target, the protocol header, the 1 MiB cap, draining, then the body's schema and
   row rules. A failure is one of `403 REQUEST_REFUSED`, `401 WRITE_TOKEN_INVALID`,
   `400 PROTOCOL_VERSION_UNSUPPORTED`, `413 REQUEST_TOO_LARGE`, `503 SERVICE_DRAINING` or
   `400 REQUEST_MALFORMED`. The log codec's row check (`EventLogRowCodec.refusal`) also runs
   first inside the service and answers `400 REQUEST_MALFORMED`.
2. **Surface.** `coordination_ref_commit` answers `422 WRITE_LOCATION_UNVERIFIED` here, before
   any Mission lookup (ADR-0007 D-4; migration step 4 serves it).
3. **Mission.** A root checkout the service cannot verify answers `422
   WRITE_LOCATION_UNVERIFIED`; no `meta.json` with this `mission_id`, or more than one, answers
   `404 MISSION_NOT_FOUND`.
4. **Location.** `422 WRITE_LOCATION_UNVERIFIED` unless `checkout` is `.`, `destinationRef` is
   null, `missionDirectory` resolves to the Mission's own directory under the verified root, and
   the Mission is not coordination-routed. A separate-git-dir or submodule repository has no
   verifiable root, so it answers here too.
5. **Work packages.** `404 WP_NOT_FOUND` for a `wpId` with no `tasks/<wpId>.md` or
   `tasks/<wpId>-<slug>.md`.
6. **Duplicate ids.** An `event_id` twice in one request answers `409
   BOOKKEEPING_DOUBLE_EVENT_ID`, before the lock.
7. **The turn, then the Mission lock.** One fair in-process turn per service instance, then the
   Mission status lock, both bounded by the same deadline.
8. **Read the log.** A torn tail, a log the fold cannot reduce, and a read fault answer
   `500 STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED`.
9. **Per unit: replay, conflict or new.** Byte equality of the encoded, sanitized rows decides.
10. **The authority over the new units, in order** (`domain.authority.BatchJudge`): unknown lane,
    the lifecycle claim rules, a stale `from_lane`, the force-note and done-evidence checks,
    dependency readiness, then the lane machine and its guards. A later unit sees the earlier
    units of the same request as if they had landed.
11. **One append and one sync** of every new unit, then `201`.

### Durability

`StatusLogAppender` and its `LockedStatusLogSession` (in `service/event-log`, package
`adapter.out.statuslog`) do the write:

- The lock is `<gcd>/spec-kitty-locks/<missionDirectory basename>.status.lock` — the key
  `MissionLockPath` builds from the directory's basename, not its slug or mission id, which is
  the lock core's `feature_status_lock` takes.
- Under the lock the whole log is read and its tail checked. A last byte that is not LF is a
  torn tail: it is refused (`500`), never repaired. The operator ruling of 2026-10-31 keeps
  the refusal; an explicit repair command is the follow-up in
  [#222](https://github.com/spec-kitty/spec-kitty-events/issues/222).
- All new rows — one unit or a whole batch — go out in one `O_APPEND` write of complete JSON
  lines, then `force()`. When the log did not exist, its parent directory is synced too.
- If the write or the sync fails (a short write included), the log is truncated back to the size it
  had before the write and forced, then the answer is `500 STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED`.
  If the log's size is not the one read under the lock, nothing is written and the answer is `500`
  as well. One session permits one append.
- Writes are LF-only on every OS; a CR before the LF is tolerated on read. A log holding a raw U+0085,
  U+2028, U+2029, VT, FF, FS, GS or RS is unreducible (`500`), because core's row splitter
  would break on it. JSON nesting is capped at 256 and integers at 4300 digits; floats are
  unbounded, as in Python. The request parser takes the same limits, set on its own token factory
  (not a JVM-wide default), so the edge and the log reader agree. Before any append the service decodes
  the encoded rows with the log's codec and folds the log plus the new rows with the fold the next
  write uses; a row that fails either is `400 REQUEST_MALFORMED` (naming the unit of a batch) and
  nothing is written, so an acknowledged row can always be read back.

### Idempotency

- A unit whose rows are all already in the log, with the same encoded bytes, is a **replay**:
  `201` with `replayed: true` and `appendedLines: 0`. Nothing is written and nothing is synced.
- Some ids present and some absent, or any present row that differs in bytes, is `409
  EVENT_ID_CONFLICT`; a batch names the failing unit as `instance: #/units/<i>`.
- The first occurrence of an id in the log is the one compared, mirroring the reducer's
  first-wins rule. Equality is byte equality of the sanitized, sorted-key encoding, so `1` and
  `1.0` stay distinct.
- A replayed unit cannot follow a new unit in the same batch, and a fully replayed batch writes
  nothing and syncs zero times (`fsyncs: 0`).
- A retry after a lost answer is therefore safe with the same `event_id`s and the same rows.

### The 10 s budget

The in-process turn and the Mission lock share one deadline, `now + 10 s` (`ServiceWiring`
constructs `WriteService` with `Duration.ofSeconds(10)`). Waiting out the turn answers `503
SERVICE_BUSY` with `Retry-After: 1`; waiting out the Mission lock answers `409 STATUS_LOCK_HELD`.
The service never waits unbounded.

### The operation field

`TransitionUnit.operation` tells the service which core door produced the unit:

- `transition` (or absent) is core's emit pipeline, as `move-task`, `mark-status` and `status
  emit` use it. No claim rules apply.
- `start_implementation` and `start_review` are core's lifecycle starts; they run the claim and
  start rules in `domain.authority.claim.ClaimRules` before the lane machine. A start from
  `planned` is a batch of two units — `planned -> claimed`, then `claimed -> in_progress` —
  both with `operation: start_implementation`. A review re-claim is one `start_review` unit
  whose row is `for_review -> in_review`; a different reviewer already holding `in_review`
  answers `WP_ALREADY_CLAIMED`.

The acknowledgement is a `DurabilityAck` per unit (`eventIds`, `appendedLines`, `fsynced`
always true, `statusCommit` null, `replayed`), and a `BatchDurabilityAck` for a batch
(`fsyncs` plus one `DurabilityAck` per unit, in request order).

### The refusal codes this release answers

It answers 17 of the contract's 27 codes. The ten it does not answer are the coordination,
placement and commit codes that belong to ADR-0007's surface (served from migration step 4):
`COORDINATION_REF_MOVED`, `COORDINATION_REF_CHECKED_OUT`, `COORDINATION_LEDGER_UNREADABLE`,
`PROTECTED_BRANCH_REFUSED`, `DESTINATION_REF_NOT_FOUND`, `DESTINATION_REF_INVALID_SHAPE`,
`DESTINATION_REF_NOT_LOCAL`, `COORDINATION_BRANCH_DELETED`, `OWNED_MISSION_PATH_REFUSED` and
`BOOKKEEPING_COMMIT_FAILED`.

| Code | Status | When |
|---|---|---|
| `REQUEST_REFUSED` | 403 | the Host or Origin guard |
| `WRITE_TOKEN_INVALID` | 401 | the bearer token |
| `PROTOCOL_VERSION_UNSUPPORTED` | 400 | the protocol header |
| `REQUEST_TOO_LARGE` | 413 | a body over 1 MiB |
| `REQUEST_MALFORMED` | 400 | the body's shape or a row rule |
| `SERVICE_DRAINING` | 503 | after `/shutdown` or the idle timeout |
| `TRANSITION_REJECTED` | 409 | the lane machine or a guard, with a reason URN |
| `WP_ALREADY_CLAIMED` | 409 | a lifecycle claim held by another actor |
| `DEPENDENCIES_NOT_SATISFIED` | 409 | a gated dependency is not approved, done or operator-canceled |
| `WP_NOT_FOUND` | 404 | no task file for the id |
| `MISSION_NOT_FOUND` | 404 | no `kitty-specs/*/meta.json` with this `mission_id` |
| `STATUS_LOCK_HELD` | 409 | the Mission lock was not free within the 10 s budget |
| `BOOKKEEPING_DOUBLE_EVENT_ID` | 409 | an `event_id` twice in the request |
| `EVENT_ID_CONFLICT` | 409 | the log holds a different row for one of the ids |
| `WRITE_LOCATION_UNVERIFIED` | 422 | the surface or location, or an unverifiable root |
| `SERVICE_BUSY` | 503 | the in-process turn was not free; `Retry-After: 1` |
| `STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED` | 500 | a torn tail, an unreducible log, or a write/sync that was rolled back |

## Binding notes for core's client

The client is core's `transition_port` (#6195). Five rules keep it on the supported path:

- **Do not call the service while holding the Mission's status lock.** The service takes the
  same lock, so every write would wait out the budget and answer `STATUS_LOCK_HELD`.
- **Set `operation` for the lifecycle starts.** `start_implementation` and `start_review` are
  the only way the claim and start rules run; a `transition` unit does not get them.
- **Send a start from `planned` as a batch of two units**, `planned -> claimed` then `claimed ->
  in_progress`, each with `operation: start_implementation`.
- **Send `checkout_append` with `checkout: "."`, `destinationRef: null` and `missionDirectory`
  the Mission's directory relative to the root.** `coordination_ref_commit` is refused until
  migration step 4, and `statusCommit` stays null.
- **Keep the checks that stay client-side** (C-004): `move-task`'s agent-mismatch guard and the
  lane-specific for-review commit gate. They run above core's emit pipeline, have no protocol
  URN, and stay advisory; the service remains authoritative.

A `201` means the rows are durable. A lost answer is retried with the same `event_id`s and the
same rows: a replay answers `replayed: true` and writes nothing.

## Deliberate divergences from core

Every one is a recorded ruling, and each is enforced in the service. The wire-visible half is
described above; this table is the compact list (R15–R31).

| Where | Behaviour | Ruling |
|---|---|---|
| Log | torn tail refused, never repaired; LF-only writes, a CR before the LF tolerated on read | C-006, decision 10, [#222](https://github.com/spec-kitty/spec-kitty-events/issues/222) |
| Log | a raw U+0085/U+2028/U+2029/VT/FF/FS/GS/RS makes the log unreducible (`500`) | R24, [core #7439](https://github.com/spec-kitty/spec-kitty/issues/7439) |
| Log | JSON nesting capped at 256, integers at 4300 digits, floats unbounded | decision 16, [core #7439](https://github.com/spec-kitty/spec-kitty/issues/7439) |
| Request | a row the log reader or fold cannot read back (nesting over 256, a structured actor core's `decode_actor` refuses) is `REQUEST_MALFORMED`; an unexpected failure is `500 STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED`, never a Problem without a code | PR #230 squad |
| Request | a non-finite number, or a mixed naive/aware session pair, is `REQUEST_MALFORMED` | decision 9, [core #7440](https://github.com/spec-kitty/spec-kitty/issues/7440), [#7441](https://github.com/spec-kitty/spec-kitty/issues/7441) |
| Request | a stale `from_lane` is `TRANSITION_REJECTED` + `stale_from_lane` | decision 17 |
| Location | a coordination-routed Mission answers `422` | R16 |
| Location | `--separate-git-dir` and submodule repositories answer `422` | R17 |
| Location | the root checkout is verified by asking git once at startup; any git failure is `422` | R31 |
| WP facts | an unresolvable subtask roster answers `implementation_evidence_required` | R15 |
| WP facts | a WP file over 16 MiB is unresolvable | R18 |
| WP facts | frontmatter is a plain YAML 1.2 subset: no tags, directives, anchors, aliases, tabs, NEL/LS/PS or `...`; no flow-context `:` without a space; string keys only; no bare `=` | R19–R23, R27, [core #7444](https://github.com/spec-kitty/spec-kitty/issues/7444) |
| WP facts | a block scalar whose first line is spaces-only is unresolvable | R23 |
| WP facts | subtask entries must be strings or integers | R25, [core #7442](https://github.com/spec-kitty/spec-kitty/issues/7442) |
| WP facts | the dependency and roster task-file locators match the id case-insensitively | R26, [core #7443](https://github.com/spec-kitty/spec-kitty/issues/7443) |
| WP facts | `shell_pid` is absent, null, empty, an integer, or 1–4300 ASCII digits with an optional sign | R28 |
| WP facts | no integer may exceed 4300 decimal digits | R29 |
| WP facts | no timestamp may have more than 6 fractional digits | R30 |

Plain scalars resolve by the YAML 1.2.2 core schema (`Yaml12Schema`), not SnakeYAML's YAML 1.1
resolver, so numeric spellings only ruamel reads as numbers are refused rather than copied. The
own `meta.json` BOM behaviour is core's (a BOM means the Mission is not found), tracked as
[core #7445](https://github.com/spec-kitty/spec-kitty/issues/7445). Two guard reasons are not
reachable over the wire; [events #223](https://github.com/spec-kitty/spec-kitty-events/issues/223)
(`workspace_context_required`) and
[events #224](https://github.com/spec-kitty/spec-kitty-events/issues/224) (the evidence half of
`implementation_evidence_required`) track them.

## Lifecycle

- **Shutdown.** `POST /api/v1/shutdown` answers `202` at once. The health check then reads
  `draining` and new writes get `SERVICE_DRAINING`. The service waits up to 5 s for the requests
  it holds, a request whose body is still arriving included, and the application's own shutdown
  phase is capped at 2 s, so the process is gone well inside the launcher's 10 s. Then it removes `endpoint.json`, but only while the file still names this instance,
  and exits 0.
- **Idle exit.** After `--idle-timeout` seconds without a request, the service drains and exits
  the same way.
- **Signal exit.** On `SIGTERM`, the launcher's fallback after 10 s, the service still removes its
  own endpoint file.

## How core binds it

Core binds the service itself, once a `service-v*` release carries this write core. This mission
cuts no tag; a release that carries it is what core's binding (#6195) pins
(core `docs/plans/events-domain-move/plan.md` §4 step 7):

- **Pin.** `spec-kitty service vendor` writes `pins/<service>.source.yaml` from the release's
  `SHA256SUMS`. The asset prefix is `command-cli` (core `src/kernel/service_pin.py`,
  `DEFAULT_ASSET_PREFIXES`). The targets are `linux-x64`, `linux-arm64`, `macos-arm64`,
  `windows-x64` and `jvm`.
- **Resolve.** Core resolves the binary in this order (core
  `src/specify_cli/services/resolution.py`):
  1. `SPEC_KITTY_WRITE_SERVICE_BINARY`;
  2. the entry point;
  3. the native build cached in the XDG cache, fetched on first use and digest-checked;
  4. the jar on Java 25, only when no native build is pinned for the host, or when the native
     build is unavailable and the jar is already in the cache or the asset directory;
  5. otherwise, a refusal.
- **Launch.** Core starts the binary with
  `<argv prefix> serve --git-common-dir <abs> [--idle-timeout <s>]` (core
  `src/specify_cli/services/policies.py` `WRITE_SERVICE` and `launcher.py`).
- **Bind.** Core calls
  `bind_event_bridge(*, emitter_factory, transition_port)` (core
  `src/runtime/next/_internal_runtime/events.py:304`).
  - The transition port's `record_work_package_transition(transition)` (core
    `src/runtime/next/event_bridge.py`) sends one `TransitionUnit` to
    `POST /api/v1/missions/{missionId}/work-package-transitions`.
  - It builds the unit with `transition_unit_from` (core
    `src/specify_cli/status/write_service_protocol.py`).
  - The runtime emitter half has no operation in protocol major 1.
- **Path prefix.** Core's launcher client still calls the bare paths `/handshake`, `/health` and
  `/shutdown`. The contract's `/api/v1` prefix wins, and spec-kitty/spec-kitty#6910 moves the
  client to it.

## Build and test

| Command | What it runs |
|---|---|
| `./gradlew :command-cli:check` | the JVM tests, the `@Tag("native")` twin run, the launcher-contract process tests against the boot jar, and the JaCoCo gates |
| `./gradlew :command-cli:nativeCompile :command-cli:nativeTest` | the native image and the tagged tests in it (GraalVM 25 as `JAVA_HOME`) |
| `./gradlew :command-cli:nativeSmoke -PnativeBinary=<path>` | the launcher contract against a built binary |
| `./gradlew :command-cli:benchmarkTest` | NFR-001: 50 acknowledged units against a Mission log holding exactly 10,000 rows |

Add `-PreleaseVersion=<ver>` to set the version the handshake reports. The `service.yml` legs
`native-command-cli (<target>)` build and smoke the binary on all four targets.

### The benchmark

`benchmarkTest` (`WriteBenchmarkTest`) posts 50 real claim units against a 10,000-row log and
writes `build/benchmark/write-latency.json` (under `service/command-cli/`) with `logRows`,
`acknowledgedUnits`, `p50Ms` and `p95Ms`; it fails when p95 exceeds the 1 s gate. The latest
run measured p50 about 83 ms and p95 about 97 ms.

### Native access

The status-log lock is `flock(2)` through the Foreign Function and Memory API, so every JVM that
loads the module needs native access. The boot jar's manifest carries
`Enable-Native-Access: ALL-UNNAMED`, the test JVMs pass
`--enable-native-access=ALL-UNNAMED`, and the native image is built with the same
`--enable-native-access=ALL-UNNAMED` build arg. GraalVM 25 enables `ForeignAPISupport` by
default, so no experimental flag is needed.
