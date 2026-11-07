---
work_package_id: WP08
title: 'Cut-over: WriteService, adapters, acks and serialization'
dependencies:
- WP02
- WP03
- WP05
- WP07
requirement_refs:
- FR-006
- FR-008
- FR-009
- FR-010
- FR-017
- FR-018
- FR-019
- FR-020
- FR-025
- FR-026
- NFR-001
- NFR-004
- NFR-005
- SC-003
- SC-006
- C-002
- C-005
- C-009
- C-010
planning_base_branch: kitty/mission-events-write-core-01M6TP6W
merge_target_branch: main
branch_strategy: Planning artifacts for this mission were generated on kitty/mission-events-write-core-01M6TP6W. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into main unless the human explicitly redirects the landing branch.
subtasks:
- T044
- T045
- T046
- T047
- T048
- T049
- T050
- T051
history:
- timestamp: '2026-11-07T10:30:00Z'
  lane: planned
  agent: system
  action: Prompt generated via /spec-kitty.tasks
agent_profile: java-jenny
authoritative_surface: service/command-cli/src/main/java/org/speckitty/commandcli/application/write/WriteService.java
create_intent:
- service/command-cli/src/main/java/org/speckitty/commandcli/application/write/WriteService.java
- service/command-cli/src/main/java/org/speckitty/commandcli/application/write/WriteOutcome.java
- service/command-cli/src/main/java/org/speckitty/commandcli/application/write/ReplayClassifier.java
- service/command-cli/src/main/java/org/speckitty/commandcli/application/write/WriteTurn.java
- service/command-cli/src/main/java/org/speckitty/commandcli/application/write/port/
- service/command-cli/src/main/java/org/speckitty/commandcli/adapter/out/mission/
- service/command-cli/src/main/java/org/speckitty/commandcli/adapter/out/statuslog/
- service/command-cli/src/main/java/org/speckitty/commandcli/adapter/in/http/operations/AckBody.java
- service/command-cli/src/test/java/org/speckitty/commandcli/application/write/
- service/command-cli/src/test/java/org/speckitty/commandcli/adapter/out/mission/
- service/command-cli/src/test/java/org/speckitty/commandcli/adapter/out/statuslog/
- service/command-cli/src/test/java/org/speckitty/commandcli/adapter/in/http/operations/WriteIntegrationTest.java
- service/command-cli/src/test/java/org/speckitty/commandcli/adapter/in/http/operations/ProtocolCorpusTest.java
- service/command-cli/src/test/java/org/speckitty/commandcli/adapter/in/http/operations/WriteBenchmarkTest.java
- service/command-cli/src/test/java/org/speckitty/commandcli/adapter/in/http/operations/AckBodyTest.java
- service/command-cli/src/test/java/org/speckitty/commandcli/adapter/in/http/operations/WriteRig.java
- service/command-cli/src/test/java/org/speckitty/commandcli/architecture/WriteOwnershipRulesTest.java
- service/command-cli/src/test/java/org/speckitty/commandcli/architecture/fixture/writes/
execution_mode: code_change
owned_files:
- service/command-cli/src/main/java/org/speckitty/commandcli/application/write/WriteService.java
- service/command-cli/src/main/java/org/speckitty/commandcli/application/write/WriteOutcome.java
- service/command-cli/src/main/java/org/speckitty/commandcli/application/write/ReplayClassifier.java
- service/command-cli/src/main/java/org/speckitty/commandcli/application/write/WriteTurn.java
- service/command-cli/src/main/java/org/speckitty/commandcli/application/write/port/**
- service/command-cli/src/main/java/org/speckitty/commandcli/application/lifecycle/WriteGate.java
- service/command-cli/src/main/java/org/speckitty/commandcli/adapter/out/mission/**
- service/command-cli/src/main/java/org/speckitty/commandcli/adapter/out/statuslog/**
- service/command-cli/src/main/java/org/speckitty/commandcli/adapter/in/http/operations/WriteController.java
- service/command-cli/src/main/java/org/speckitty/commandcli/adapter/in/http/operations/AckBody.java
- service/command-cli/src/main/java/org/speckitty/commandcli/adapter/in/http/operations/OperationsWiring.java
- service/command-cli/src/main/java/org/speckitty/commandcli/config/ServiceWiring.java
- service/command-cli/src/main/java/org/speckitty/commandcli/config/CommandCliHints.java
- service/command-cli/build.gradle.kts
- service/command-cli/src/test/resources/jacoco-class-floor.properties
- service/command-cli/src/test/java/org/speckitty/commandcli/application/write/**
- service/command-cli/src/test/java/org/speckitty/commandcli/application/lifecycle/ServiceLifecycleTest.java
- service/command-cli/src/test/java/org/speckitty/commandcli/adapter/out/mission/**
- service/command-cli/src/test/java/org/speckitty/commandcli/adapter/out/statuslog/**
- service/command-cli/src/test/java/org/speckitty/commandcli/adapter/in/http/operations/OperationsTest.java
- service/command-cli/src/test/java/org/speckitty/commandcli/adapter/in/http/operations/WriteIntegrationTest.java
- service/command-cli/src/test/java/org/speckitty/commandcli/adapter/in/http/operations/ProtocolCorpusTest.java
- service/command-cli/src/test/java/org/speckitty/commandcli/adapter/in/http/operations/WriteBenchmarkTest.java
- service/command-cli/src/test/java/org/speckitty/commandcli/adapter/in/http/operations/AckBodyTest.java
- service/command-cli/src/test/java/org/speckitty/commandcli/adapter/in/http/operations/WriteRig.java
- service/command-cli/src/test/java/org/speckitty/commandcli/architecture/WriteOwnershipRulesTest.java
- service/command-cli/src/test/java/org/speckitty/commandcli/architecture/fixture/writes/**
- service/command-cli/src/test/java/org/speckitty/commandcli/nativesmoke/**
- service/command-cli/src/test/java/org/speckitty/commandcli/config/CommandCliHintsTest.java
role: implementer
tags: []
tracker_refs: []
---
# Work Package Prompt: WP08 - Cut-over: WriteService, adapters, acks and serialization

## ⚡ Do This First: Load Agent Profile

Load your assigned profile before reading anything else: `/spk-charter-profile-load java-jenny`
(or `spec-kitty agent profile show java-jenny`). Adopt its identity, scope and boundaries.

## Objective

Replace `command-cli`'s fail-closed refusal of every write with the real write path (#155):

- **`WriteService`** (application) runs the spec's check order (FR-026) behind three ports,
  `MissionCatalog`, `StatusLog` and `RowCodec`, and returns a `WriteOutcome`;
- **adapters**: `adapter.out.mission` resolves the root checkout from the git common dir, finds the
  Mission by `meta.json`, finds the work package's tasks file, and reads frontmatter with SnakeYAML
  `SafeConstructor` into `MissionFacts`; `adapter.out.statuslog` drives WP02's appender and WP05's
  codec;
- **replay classification**, the contract's **acks** (`DurabilityAck`, `BatchDurabilityAck`) and
  refusals (with `type` and `instance`), the **controller** wiring, `WriteGate` removed;
- **one fair in-process turn** per service and **one 10 s deadline** shared by the turn and the
  Mission lock (`503 SERVICE_BUSY` with `Retry-After: 1`; `409 STATUS_LOCK_HELD`);
- the command-cli half of **FR-010** (file-write ownership rule, planted violation);
- **HTTP integration tests** for every scenario of US1 to US5 and the corpus's `edge`/`service` cases,
  the **NFR-001 benchmark**, and a **native smoke** that performs a real locked write, with native
  access enabled in the boot jar and the image.

`statusCommit` stays `null`; `coordination_ref_commit` is refused before any Mission lookup
(C-002, ADR-0007).

## Context

- Spec: FR-006 (native locked write), FR-008, FR-009, FR-010, FR-017, FR-018, FR-019, FR-020,
  FR-025, FR-026 (binding order), NFR-001, NFR-004, NFR-005, SC-003, SC-006, C-002, C-005, C-010;
  US1 to US5 and every edge case.
- Plan: "Placement decisions", **"Request flow (FR-026)"** (binding, steps 1 to 11), "Supply chain"
  (SnakeYAML 2.6 moves from `testImplementation` to `implementation`; `SafeConstructor` only,
  bounded block, default alias limit), "Coordination points".
- Data model: `WriteRequest` (WP03), `StatusLogSession` (WP02), `MissionFacts` and `Judgement`
  (WP06/WP07), "Response side".
- Research: §3.2 (Repo A shapes), §5.3 (native: `Enable-Native-Access` missing in the boot
  manifest; command-cli has no dependency on event-log), §6 (write-location verification steps
  1 to 5), §7 (idempotency: inside the lock; byte equality; first occurrence wins), §8.1 (seams:
  `WriteGate`, `WriteController`, `ServiceWiring`, `OperationsWiring`), §8.3 (onion: store
  orchestration behind application ports, file/git in outbound adapters, composition in config),
  §10 risks 5, 6, 8 and 14, §11.1, §11.3, §11.4, §11.6, §11.7, §11.10, §11.11, §11.14.
- Existing seams:
  - `service/command-cli/src/main/java/org/speckitty/commandcli/application/lifecycle/WriteGate.java:10-22`
    (remove), `.../adapter/in/http/operations/WriteController.java:22-68` (drain, then shape,
    then the gate), `.../config/ServiceWiring.java:61-64` (`writeGate()` bean) and `:26-44`
    (`ServeRuntime` carries the gcd: `ServeOptions.gitCommonDir()`, `ServeOptions.java:16`),
    `.../operations/OperationsWiring.java:22-25` (`WriteBodies` bean), `.../config/CommandCliHints.java`.
  - WP03: `WriteBodies.parseUnit/parseBatch/parseAnnotation` → `WriteRequest`;
    `RefusalCode`, `RefusalReason`, `RefusalBody.withType`.
  - WP07: `domain.authority.batch.Authority.standard()` and `judge(...)`; WP06: `LogLine`,
    `UnitInput`, `Operation`, `MissionFacts`, `DependencyDeclaration`, `Refusal` (`detail` is the
    text to show).
  - WP02: `org.speckitty.missionstatus.adapter.out.statuslog.StatusLogAppender.withMissionLock`,
    `StatusLogSession`, `TornTailException`, `StatusLockHeldException`,
    `PersistenceFailedException`.
  - WP05: `StatusRowEncoder.encodeLine`, `StatusRowSanitizer.check`, `StatusRowDecoder.parse`,
    `StatusRowRefusedException`.
  - `service/command-cli/build.gradle.kts:36-43` (dependencies), `:67-72` (boot jar manifest),
    `:83-92` (test tasks), `:148-171` (native), `:177-217` (JaCoCo: 90 % lines per class in
    `application.*` and `domain.*`); `src/test/resources/jacoco-class-floor.properties`.
  - `service/command-cli/src/test/java/org/speckitty/commandcli/architecture/ArchitectureRulesTest.java:40-89`
    (onion: the application uses no web framework, no Jackson, no `java.nio.file`, no channels;
    adapters never depend on each other).
  - `.../operations/OperationsTest.java:100-198` (fail-closed assertions to retire),
    `.../edge/EdgeRig.java` (how tests start the app), `.../operations/ContractSchemas.java`
    (schema validation helper), `.../nativesmoke/NativeSmokeTest.java:18-70`,
    `.../application/lifecycle/ServiceLifecycleTest.java:157-160` (references `WriteGate`).
  - `gradle/verification-metadata.xml` lines 3069-3074 (SnakeYAML 2.6 already pinned).

## Branch Strategy

Planning happens on `kitty/mission-events-write-core-01M6TP6W` in an owned checkout
(`single_branch` topology). The mission lands as one PR to `main` from `issue-160-write-core`;
the planning base and merge target recorded here are `main`.
`spec-kitty agent action implement WP08 --agent <name> --mission events-write-core-01M6TP6W`

## Ground rules

- ATDD, red first: `WriteIntegrationTest`'s US1 scenario 1 is the first test you write; it is red
  against the fail-closed gate for the right reason (500 instead of 201).
- Java 25. Build and test with
  `jh=$(scripts/ensure_jdk25.sh); JAVA_HOME=$jh ./gradlew --no-daemon -Dorg.gradle.java.installations.paths=$jh :command-cli:check`.
- Cyclomatic complexity ≤ 15 per method (NFR-004): `WriteService` is a sequence of small steps,
  one method each; JaCoCo floors met for every new `application.*` class.
- No new third-party dependency. SnakeYAML moves to `implementation` (already sha-pinned,
  NFR-005). Module dependencies `implementation(project(":event-log"))` (it brings domain-model via
  `api`) are added here.
- No dependency on core (C-001).
- Onion: ports and the use case in `application.write` (no `java.nio.file`, no Jackson);
  file and git in `adapter.out.*`; composition in `config`. Adapters do not depend on each other.
- No refusal detail echoes a path, a token or a row value (FR-025); paths never appear in bodies.
- The lock is never held across the HTTP answer: the session ends before the controller writes.
- The edge order and every existing edge refusal stay unchanged (FR-019).
- Declared out-of-map edit allowed: delete `WriteBodies`' three `Optional<Malformed>` delegates once
  nothing calls them (WP03's file); say so in the PR.
- Do not run `make test-full`.
- Record the test commands you ran and their pass counts (and the benchmark's p50/p95) in your
  final commit message.

## Subtasks

### T044 `WriteService`, its ports and `WriteOutcome`

**Files**: `application/write/WriteService.java`, `WriteOutcome.java`, `port/MissionCatalog.java`,
`port/StatusLog.java`, `port/RowCodec.java`, `port/MissionRef.java` (plus small port value types);
tests under `src/test/.../application/write/` with in-memory fakes.

**The order** (FR-026; the first failure answers):
1. Edge and shape: done by the controller and `WriteBodies` before the service is called. The
   service's first step is the codec's second line of defence: `RowCodec.refusal(row)` (WP05's
   `check`) → `REQUEST_MALFORMED` with the unit's instance for a batch.
2. Surface: `coordination_ref_commit` → `422 WRITE_LOCATION_UNVERIFIED`, detail naming migration
   step 4 (fixed text), before any Mission lookup (§11.6).
3. `MissionCatalog.find(missionId)` → none or ambiguous → `404 MISSION_NOT_FOUND` (FR-018).
4. Location: `checkout` must be `.`, `destinationRef` null, and the real path of
   `missionDirectory` must equal the resolved Mission directory → else `422
   WRITE_LOCATION_UNVERIFIED` (FR-009, §11.7: not `OWNED_MISSION_PATH_REFUSED`).
5. `WP_NOT_FOUND` for each distinct `wpId` of the request (FR-018), before the lock.
6. A repeated `event_id` anywhere in the request → `409 BOOKKEEPING_DOUBLE_EVENT_ID`.
7. The turn, then the lock (T048), on one deadline.
8. Inside the lock: the current bytes; a torn tail or an unreducible log (`RowCodec.decode` gives
   `LogLine`s; WP06's fold reports unreducible) → `500 STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED`;
   read `MissionFacts`.
9. Replay classification (T047) → replayed units, new units, or `409 EVENT_ID_CONFLICT`.
10. `Authority.judge(log, newUnits, batch, facts)` → the first refusal answers with its code,
    reason URN (`type`), `detail` and, for a batch, `instance: #/units/<i>`.
11. One `appendAndSync` of every new unit's encoded rows, in request order; a failure (rolled back
    by WP02) → `500`. No new units → no append, no sync.

`WriteOutcome`: `Acked(List<UnitAck> units, boolean batch, int fsyncs)` with
`UnitAck(List<String> eventIds, int appendedLines, boolean replayed)`, or
`Refused(RefusalCode code, RefusalReason reason, String detail, Integer unitIndex)`.

Tests first: one test per step proving the step's refusal and that **no later port was called**
(fakes record calls); the ordering test that a `coordination_ref_commit` to an unknown Mission
answers 422, not 404.

### T045 `adapter.out.mission`

**Files**: `adapter/out/mission/FilesystemMissionCatalog.java`, `RootCheckout.java`,
`Frontmatter.java`, `TasksFiles.java`, `DiskMissionFacts.java`; tests with temp git repositories.

**Steps**:
1. `RootCheckout.of(gcd)`: the gcd's real path must be named `.git`, its parent's `.git` must
   resolve to the same real path (so the parent is the root checkout, not a linked worktree whose
   `.git` is a file), and the repository must not be bare (`core.bare`). Anything else: no
   Mission is found (404), never a guess. Resolve once at startup.
2. `find(missionId)`: scan `<root>/kitty-specs/*/meta.json` (Jackson tree read, no binding), match
   `mission_id`; zero or several → absent. Cache nothing that can go stale across writes, or
   invalidate on each write (a Mission can be created while the service runs).
3. Location check support: the real path of `<root>/<missionDirectory>` versus the found
   directory (symlinks resolved; a path escaping the root is a mismatch).
4. `wpExists(missionRef, wpId)`: `tasks/<wpId>.md` or `tasks/<wpId>-*.md` exists (FR-018, core's
   lookup).
5. `DiskMissionFacts` (`MissionFacts` for the authority), read **inside** the lock (step 8):
   - dependencies: `tasks/*.md` excluding `readme.md`, matched by `^<wpId>(?:[-_.]|\.md$)`; zero
     → `None`, several → `Unresolvable`; the frontmatter's raw `dependencies`: `[]`, a list of
     ids, a comma-separated string, a bare id; anything else or an unreadable file →
     `Unresolvable` (research §1.6; mirror the expectations WP07 pinned through
     `CorpusMissionFacts`);
   - subtask roster: as WP06's `CorpusMissionFacts` derives it from the frontmatter (core's
     `emit.py:492-548`).
6. `Frontmatter`: the block between a leading `---` line and the next `---` line, capped (64 KiB),
   loaded with `new Yaml(new SafeConstructor(new LoaderOptions()))` (default alias limit, no type
   tags). Malformed YAML → `Unresolvable`, never an exception out of the adapter.

### T046 `adapter.out.statuslog`

**Files**: `adapter/out/statuslog/EventLogStatusLog.java`, `EventLogRowCodec.java`; tests.

**Steps**:
1. `EventLogStatusLog implements StatusLog`: `withLock(MissionRef, long deadlineNanos, work)`
   calls `StatusLogAppender.withMissionLock(gcd, missionDirectory, deadline, ...)` and translates
   `StatusLockHeldException`, `TornTailException`, `PersistenceFailedException` into the port's own
   outcome types (the application does not import event-log exceptions).
2. `EventLogRowCodec implements RowCodec`: `encodeLine(Object row)` →
   `StatusRowEncoder.encodeLine`; `refusal(Object row)` → `StatusRowSanitizer.check`;
   `decode(byte[] log)` → `List<LogLine>` (split on `\n`; strip one trailing `\r` per line,
   §11.10; blank → `Blank`; `StatusRowDecoder.parse` failure → `Unparseable`; the final empty
   segment after the last LF is not a line) plus each line's raw bytes for the replay comparison.
3. Tests: the bytes appended for the `status_append` valid fixtures equal their `expected_line`
   (read the fixture directory the way WP05's runner does, via a repo-root walk from `user.dir`).

### T047 Replay classification, acks, controller, `WriteGate` removed

**Files**: `application/write/ReplayClassifier.java`, `adapter/in/http/operations/AckBody.java`,
`WriteController.java`, `OperationsWiring.java`, `config/ServiceWiring.java`; delete
`application/lifecycle/WriteGate.java`; tests `ReplayClassifierTest`, `AckBodyTest`,
`OperationsTest`, `ServiceLifecycleTest`.

**Steps**:
1. `ReplayClassifier` (FR-008, §11.11): index the log's first occurrence of each `event_id`; for
   each unit, encode each row and compare its bytes with that line's bytes (trailing CR ignored).
   All rows present and identical → replay; none present → new; anything else →
   `EVENT_ID_CONFLICT`. In a batch, a replayed unit **after** a new unit → `EVENT_ID_CONFLICT`.
   Replay is decided before the authority, so a retry with a now-stale `from_lane` is a replay
   (§11.1).
2. `AckBody` (native-friendly, an ordered map like `RefusalBody.java:17-52`, never bean
   introspection): `DurabilityAck` `{appendedLines, eventIds, fsynced: true, replayed,
   statusCommit: null}`; `BatchDurabilityAck` `{fsyncs, units}` with `fsyncs` 0 only when every
   unit is replayed. Status `201`. `AckBodyTest` validates both against
   `contracts/mission-write/schemas/DurabilityAck.yaml` and `BatchDurabilityAck.yaml` with
   `ContractSchemas`.
3. `WriteController`: drain (unchanged), then the typed parse, then `writeService.write(request)`,
   then `AckBody` or `RefusalBody` (with `withType(reason.urn())` when a reason is present,
   `withInstance("#/units/" + i)` for a batch unit, `Retry-After: 1` on `SERVICE_BUSY`).
4. `ServiceWiring`: build `FilesystemMissionCatalog(runtime.options().gitCommonDir())`,
   `EventLogStatusLog(new StatusLogAppender(), gcd)`, `EventLogRowCodec`, `WriteTurn`,
   `Authority.standard()`, `WriteService`; remove the `writeGate()` bean and the class; update
   `ServiceLifecycleTest` (it referenced `WriteGate`).
5. `OperationsTest`: retire the fail-closed assertions (FR-019 allows exactly these); a
   well-formed write to the rig's repository, which holds no Mission, now answers
   `404 MISSION_NOT_FOUND` with nothing written; every other existing assertion stays.

### T048 The in-process turn and the shared deadline

**Files**: `application/write/WriteTurn.java`; tests.

**Steps**:
1. One fair `ReentrantLock(true)` per service (the contract serializes writes to one repository,
   FR-020). `WriteService` takes `deadline = now + budget` when it reaches step 7;
   `turn.tryLock(remaining)` fails → `503 SERVICE_BUSY` (the controller adds `Retry-After: 1`);
   then the Mission lock gets the **remaining** time → timeout → `409 STATUS_LOCK_HELD`.
2. The budget is 10 s (core's bounded status-lock timeout, §11.4). Inject the budget and a
   `LongSupplier` nano clock through the constructor so tests use 200 ms; production wiring passes
   10 s.
3. Tests: two concurrent writes to one Mission are serialized and the second is judged against the
   first's result; a held turn past the budget → `SERVICE_BUSY`; a Mission lock held by another
   holder (WP02's `LedgerLocks` from the test) → `STATUS_LOCK_HELD`, nothing written.

### T049 File-write ownership rule (FR-010, command-cli half)

**Files**: `architecture/WriteOwnershipRulesTest.java` (new), planted fixture
`architecture/fixture/writes/RogueWriter.java` (new).

**Steps**:
1. Rule over command-cli's main classes: no class outside the allowlist
   `org.speckitty.commandcli.adapter.out.filesystem..` (the state directory: endpoint file and
   service lock) calls a JDK file-write API (`Files.write*`, `Files.newOutputStream`,
   `Files.newBufferedWriter`, `Files.copy`, `Files.move`, `Files.createFile`, `FileOutputStream`,
   `FileWriter`, `RandomAccessFile`, `FileChannel.open`, `AsynchronousFileChannel.open`). Status
   logs are written only through event-log's appender (WP02 holds the event-log side). Name the
   allowlist in `.because(...)`.
2. Planted violation: `RogueWriter` calls `Files.write`; a test asserts the rule fires on it; a
   conforming fixture holds.

### T050 Integration tests over HTTP and the benchmark

**Files**: `WriteRig.java` (test helper: a temp git repository with `kitty-specs/<dir>/meta.json`,
`tasks/WP01.md`, `tasks/WP02-*.md` with frontmatter, and the service started against its gcd, as
`EdgeRig` starts it), `WriteIntegrationTest.java`, `ProtocolCorpusTest.java`,
`WriteBenchmarkTest.java`.

**Scenarios** (one test each; each asserts the response **and** the log bytes):
- US1: 1 legal claim → 201, bytes equal core's encoding (WP05 encoder / `status_append`
  expectations), `appendedLines`, `fsynced`, `replayed: false`, `statusCommit: null`; 2 the same
  unit again → replay, log unchanged; 3 a changed row with a logged id, and a partially logged
  unit → `EVENT_ID_CONFLICT`; 4 a repeated id in one request → `BOOKKEEPING_DOUBLE_EVENT_ID`; 5 the
  Mission lock held by another holder (a Python peer is not needed: take the lock from the test
  through `LedgerLocks`) → waits, then writes after release, or `STATUS_LOCK_HELD` after the
  (test-sized) budget; 6 a torn tail → 500, file unchanged, not repaired.
- US2: 1 `planned → done` unforced → `TRANSITION_REJECTED` + `illegal_transition` URN in `type`;
  2 unsatisfied dependency → `DEPENDENCIES_NOT_SATISFIED`; 3 another actor's claim with
  `operation: start_implementation` → `WP_ALREADY_CLAIMED`; 5 unknown Mission / unknown WP → 404.
- US3: 1 a legal batch (unit 2 legal only after unit 1) → `BatchDurabilityAck`, `fsyncs: 1`,
  rows in order; an all-replayed batch → `fsyncs: 0`; 2 unit 3 illegal → `instance: #/units/3`,
  nothing written; 3 mixed locations → 400 (unchanged); 64 units accepted, 65 → 400.
- US4: 1 the root checkout → proceeds; 2 `checkout` not `.`, a `missionDirectory` outside the
  repository or of another Mission → 422; 3 `coordination_ref_commit` → 422, no file touched
  anywhere (hash the repository tree before and after).
- US5: an annotation → 201 `appendedLines: 1`, byte-exact; the same again → replay; an unknown WP
  → 404.
- Edge cases: the first write creates the log (and it is readable as one line); an empty log is
  an empty history; drain during a write (the started write is acknowledged; a write after drain
  begins → 503 `SERVICE_DRAINING`); a CRLF log replays.
- `ProtocolCorpusTest`: every WP04 case with `layer: edge` or `layer: service`, materialized into a
  `WriteRig` repository and posted over HTTP; compare code, reason, unit index, and for acks
  `replayed`/`appendedLines`/`fsyncs`.
- `WriteBenchmarkTest` (`@Tag("benchmark")`): a Mission whose log holds 10,000 rows; ≥ 50
  acknowledged units (a legal chain across work packages); record p50 and p95 to the test output
  and to `build/benchmark/write-latency.json`; assert p95 ≤ 1,000 ms (NFR-001).

### T051 Native smoke, native access and the build

**Files**: `service/command-cli/build.gradle.kts`, `config/CommandCliHints.java` (+ test),
`nativesmoke/NativeSmokeTest.java`, `jacoco-class-floor.properties`.

**Steps**:
1. Build: `implementation(project(":event-log"))` (keep WP03's domain-model line or drop it as
   redundant through event-log's `api`); move `libs.snakeyaml` from `testImplementation` to
   `implementation`; boot-jar manifest gains `"Enable-Native-Access" to "ALL-UNNAMED"`; every
   `Test` task gets `jvmArgs("--enable-native-access=ALL-UNNAMED")`; the native `main` (and test)
   binary gets `buildArgs.add("--enable-native-access=ALL-UNNAMED")`; `test` and
   `nativeTagJvmTest` exclude the `benchmark` tag; a `benchmarkTest` task runs it and `check`
   depends on it (the gate runs in CI's Gradle job).
2. `NativeSmokeTest` (`@Tag("native-binary")`): replace the fail-closed assertion: start the binary
   against a temp repository holding a Mission, post one legal unit, expect `201` with a valid
   `DurabilityAck`, check the log line and that `<gcd>/spec-kitty-locks/<dir>.status.lock` exists
   (the FFM `open`/`flock`/`close` downcalls ran in the image, FR-006). `native-image` is not
   installed locally; this runs in the `native-command-cli` CI legs.
3. `CommandCliHints`: register what the image needs that AOT does not see (nothing for SnakeYAML's
   `SafeConstructor` maps in principle; add hints only for failures the native legs show).
4. JaCoCo floor file: raise `domain` and `application` to the new class counts (the floors are
   minimums; raising them keeps the rules from passing vacuously).

## Test strategy

```bash
jh=$(scripts/ensure_jdk25.sh); JAVA_HOME=$jh ./gradlew --no-daemon -Dorg.gradle.java.installations.paths=$jh :command-cli:test --tests '*application.write*' --tests '*adapter.out*' --tests '*operations*' --tests '*architecture*'
jh=$(scripts/ensure_jdk25.sh); JAVA_HOME=$jh ./gradlew --no-daemon -Dorg.gradle.java.installations.paths=$jh :command-cli:benchmarkTest
jh=$(scripts/ensure_jdk25.sh); JAVA_HOME=$jh ./gradlew --no-daemon -Dorg.gradle.java.installations.paths=$jh :command-cli:check
jh=$(scripts/ensure_jdk25.sh); JAVA_HOME=$jh ./gradlew --no-daemon -Dorg.gradle.java.installations.paths=$jh :event-log:check :mission-status-api:test
uv run pytest --pyargs spec_kitty_events.conformance -q
```

`make test-gradle` (or `./gradlew verifyAll` with the contract bundle, runbook §5) is the CI
agent's; run it only if the reviewer asks.

## Definition of Done

- [ ] `WriteService` runs FR-026's eleven steps in order, each proven by a test that no later port
      ran; `WriteGate` is gone.
- [ ] Every scenario of US1 to US5 and every listed edge case passes over HTTP with byte checks; the
      corpus's `edge` and `service` cases pass.
- [ ] Acks validate against the contract; `fsyncs` is 0 only for an all-replayed batch;
      `statusCommit` is null.
- [ ] Turn and lock share one deadline; `SERVICE_BUSY` carries `Retry-After: 1`.
- [ ] `WriteOwnershipRulesTest` holds and fires on its planted violation.
- [ ] `benchmarkTest` p95 ≤ 1 s, numbers recorded; native smoke performs a locked write (CI).
- [ ] `:command-cli:check` green; the final commit message lists commands, counts and p50/p95.

## Risks

- **Order mistakes** (replay after authority; 404 before the surface check): the per-step tests
  catch them.
- **Lock lifetime**: never respond while the session is open.
- **Native access**: forgetting the manifest attribute shows only in the jar process tests;
  forgetting the build arg only in the native legs. Both are in this WP.
- **Stale Mission cache**: a Mission created after start must be found.

## Reviewer guidance

- Walk `WriteService` against FR-026 step by step.
- Check `AckBody` uses an ordered map, not reflection.
- Check the location check uses real paths and refuses a symlinked escape.
- Check the benchmark numbers and that the gate actually asserts.
- Check the integration tests assert bytes, not just status codes.
