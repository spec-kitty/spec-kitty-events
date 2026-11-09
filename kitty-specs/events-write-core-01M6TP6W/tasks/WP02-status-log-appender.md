---
work_package_id: WP02
title: event-log status-log appender
dependencies: []
requirement_refs:
- FR-004
- FR-005
- FR-006
- FR-007
- FR-010
- FR-028
- NFR-004
- NFR-005
- NFR-006
- SC-004
- SC-005
- C-001
- C-006
- C-009
planning_base_branch: kitty/mission-events-write-core-01M6TP6W
merge_target_branch: main
branch_strategy: Planning artifacts for this mission were generated on kitty/mission-events-write-core-01M6TP6W. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into main unless the human explicitly redirects the landing branch.
subtasks:
- T007
- T008
- T009
- T010
- T011
- T012
- T013
history:
- timestamp: '2026-11-07T10:30:00Z'
  lane: planned
  agent: system
  action: Prompt generated via /spec-kitty.tasks
agent_profile: java-jenny
authoritative_surface: service/event-log/src/main/java/org/speckitty/missionstatus/adapter/out/statuslog/
create_intent:
- service/event-log/src/main/java/org/speckitty/missionstatus/adapter/out/statuslog/
- service/event-log/src/testFixtures/java/org/speckitty/missionstatus/architecture/FileWriteApis.java
- service/event-log/src/test/java/org/speckitty/missionstatus/adapter/out/statuslog/
- service/event-log/src/test/java/org/speckitty/missionstatus/architecture/fixture/statuslog/
- service/event-log/src/test/resources/statuslog/status_writer_peer.py
execution_mode: code_change
owned_files:
- service/event-log/src/main/java/org/speckitty/missionstatus/adapter/out/statuslog/**
- service/event-log/src/testFixtures/java/org/speckitty/missionstatus/architecture/FileWriteApis.java
- service/event-log/build.gradle.kts
- service/src/test/java/org/speckitty/missionstatus/architecture/ArchitectureRules.java
- service/src/test/java/org/speckitty/missionstatus/architecture/LedgerExemptionScopeTest.java
- service/src/test/java/org/speckitty/missionstatus/architecture/ArchitectureControlsTest.java
- service/event-log/src/test/java/org/speckitty/missionstatus/adapter/out/statuslog/**
- service/event-log/src/test/java/org/speckitty/missionstatus/architecture/EventLogBoundaryTest.java
- service/event-log/src/test/java/org/speckitty/missionstatus/architecture/fixture/statuslog/**
- service/event-log/src/test/resources/statuslog/**
role: implementer
tags: []
tracker_refs: []
---
# Work Package Prompt: WP02 - event-log status-log appender

## ⚡ Do This First: Load Agent Profile

Load your assigned profile before reading anything else: `/spk-charter-profile-load java-jenny`
(or `spec-kitty agent profile show java-jenny`). Adopt its identity, scope and boundaries.

## Objective

Build the one production class family that may write a Mission's `status.events.jsonl`: a new
event-log package `org.speckitty.missionstatus.adapter.out.statuslog`. It takes **bytes, never
rows** (the encoder is WP05's, the decisions are WP06 to WP08's) and guarantees:

1. every write holds the per-Mission status lock
   `<git common dir>/spec-kitty-locks/<mission directory name>.status.lock` with core's OS lock
   semantics (flock on POSIX, the channel lock on Windows), waiting at most until a caller's
   deadline, then `StatusLockHeldException` (FR-005);
2. inside the lock a session reads the current bytes and refuses a torn tail (last byte not LF)
   without repairing it (FR-007, C-006); an empty or missing file is an empty history;
3. `appendAndSync(bytes)` is one `O_APPEND` write plus a sync, and a sync of the parent directory
   when the write created the file (FR-004);
4. any failed, short or unsynced write truncates back to the size before the write and throws
   `PersistenceFailedException`; nothing is ever reported durable after an uncertain outcome
   (FR-028, NFR-006, SC-004);
5. the read service's architecture rule 7 (file APIs only in named packages) exempts the new
   package with the same event-log-source scope check the ledger exemption has, so
   `:mission-status-api:check` stays green with event-log on its classpath (ruling R9). The FFM
   native metadata is **not** here: it lives in command-cli's resources and is WP08's (ruling R10),
   so the read service's image is unaffected;
6. a service write and core's Python status writer never interleave (SC-005), proven in the
   `lock-interop` CI job over ≥ 100 contended iterations.

## Context

- Spec: FR-004, FR-005, FR-006, FR-007, FR-010 (event-log half), FR-028 (rollback), NFR-004,
  NFR-005, NFR-006, SC-004, SC-005, C-001, C-006, C-009; edge cases "Mission's first write",
  "Empty log file", "Crash between the sync and the answer", "Windows".
- Plan: "Placement decisions" (the appender takes bytes, depends only on the JDK and
  `adapter.out.ledger`, exposes `withMissionLock(gcd, missionDirectory, deadline, session -> ...)`
  where `missionDirectory` is a `Path` and the lock name is its basename (ruling R9; plan wording fixed),
  a package-private `FileOps` seam). Data model: `MissionLockPath`, `StatusLogSession` and its
  invariant (append at most once, after the torn-tail check).
- Research: §4.4 (core's interim `kernel/event_log_append.py:208-330` pattern: check the last
  byte, one write, roll back to the pre-write size, fsync; core's `status.store` does
  temp-and-rename instead, which is **not** the model), §5.1 (lock path keyed by the Mission
  directory name, 0600, 100 ms retry, 10 s bounded wait, `STATUS_LOCK_HELD`), §5.2 (Repo A's lock
  classes; `TornTailRepair` must **not** be used for status writes), §5.3 (the three downcall
  descriptors), §8.3 (`ONLY_THE_THREE_PACKAGES` fails a fourth package until it has a rule),
  §10 risk 7 (short write, rollback failure).
- Existing seams (read them first):
  - `service/event-log/src/main/java/org/speckitty/missionstatus/adapter/out/ledger/LedgerLock.java:12-53`
    (`tryAcquire`, `acquire(lockFile, timeout)`, `RETRY_INTERVAL`),
    `LedgerLocks.java:5-16` (`forThisOs()`), `LedgerLockTimeoutException.java:7-14`.
  - `FlockLedgerLock.java:48-65`: the `Native` holder with the `open`, `flock` and `close`
    downcall handles (lazy, so nothing is frozen into the image heap).
  - `service/event-log/src/test/java/org/speckitty/missionstatus/spike/lockinterop/SpikeRows.java:52-75`:
    the spike's refuse-torn-tail, one-write, force pattern (test code only; do not move it).
  - `LockInteropSpikeTest.java:43-129` and `PythonPeer.java:17-98`: how the interop test is gated
    (`SPEC_KITTY_LOCK_INTEROP_PYTHON`), how the peer runs, and how results are recorded under
    `build/lock-interop/`.
  - `service/event-log/src/test/java/org/speckitty/missionstatus/architecture/EventLogBoundaryTest.java:57-169`:
    the three package rules, `ONLY_THE_THREE_PACKAGES`, and the planted-violation pattern.
  - `service/src/test/java/org/speckitty/missionstatus/architecture/ArchitectureRules.java:120-135,182-183,234`
    (the read service's rule 7: file APIs only in `adapter.out.filesystem` and event-log's
    `adapter.out.ledger`; it scans event-log's classes because they are on the read service's
    classpath), `LedgerExemptionScopeTest.java:13-23` (the ledger exemption holds only for classes
    whose source is the event-log module), `ArchitectureControlsTest.java:160-170` (every rule has a
    control; counts).
  - `service/event-log/build.gradle.kts:35-43` (**you own it now**, ruling R6): the test task already passes
    `--enable-native-access=ALL-UNNAMED` and `module.mainClasses`. WP05 later adds one test-task
    system property there as a declared out-of-map edit. You should not need to edit
    it (WP05 owns it); if you do, coordinate.
  - `.github/workflows/service.yml:480-580`: the `lock-interop` job (core checkout at the pin,
    `uv sync --frozen`, pack sync, `--tests '*LockInteropSpikeTest'`, a step that requires
    exactly `matrix.results` JSON files under `service/event-log/build/lock-interop`).

## Branch Strategy

Planning happens on `kitty/mission-events-write-core-01M6TP6W` in an owned checkout
(`single_branch` topology). The mission lands as one PR to `main` from `issue-160-write-core`;
the planning base and merge target recorded here are `main`.
`spec-kitty agent action implement WP02 --agent <name> --mission events-write-core-01M6TP6W`

## Ground rules

- ATDD, red first: write each subtask's acceptance tests before the code and see them fail for
  the right reason.
- Java 25. Build and test with
  `jh=$(scripts/ensure_jdk25.sh); JAVA_HOME=$jh ./gradlew --no-daemon -Dorg.gradle.java.installations.paths=$jh :event-log:check`.
- Cyclomatic complexity ≤ 15 per method (NFR-004), **enforced** (ruling R6): add to
  `service/event-log/build.gradle.kts` a JaCoCo `jacocoTestCoverageVerification` rule with
  `element = "METHOD"`, `limit { counter = "COMPLEXITY"; value = "TOTALCOUNT"; maximum = 15 }` over
  the new classes (`org.speckitty.missionstatus.adapter.out.statuslog.*` and WP05's future codec
  classes `...adapter.out.filesystem.codec.StatusRow*` and `...codec.PythonFloatRepr`; patterns that
  match nothing yet are fine), and make `check` depend on it. JaCoCo class floors of the convention
  plugin met for every new class.
- No new dependency (NFR-005, strict dependency verification). Everything is JDK.
- No dependency on core. Core is reached only by the interop peer, in CI, at the pin (C-001).
- The new package depends only on itself, `adapter.out.ledger` and `java..`: no domain-model, no
  codec, no Jackson. It never parses a row.
- `.github/workflows/service.yml` is owned by WP10. Your one declared out-of-map edit is inside the
  `lock-interop` job block (T013). Every action stays pinned by its 40-hex SHA.
- If you need Python for the peer, it is linted by `uv run ruff check . && uv run ruff format --check .`.
- Do not run `make test-full`.
- Record the test commands you ran and their pass counts in your final commit message.

## Subtasks

### T007 `MissionLockPath` and the typed exceptions

**Files** (new, in `adapter/out/statuslog/`): `MissionLockPath.java`, `TornTailException.java`,
`StatusLockHeldException.java`, `PersistenceFailedException.java`; tests in the matching test
package.

**Steps**:
1. Tests first: `MissionLockPath.of(gcd, "kitty-specs-dir-name")` is
   `<gcd>/spec-kitty-locks/<name>.status.lock`; a name that is empty, `.`, `..`, or contains `/`,
   `\` or NUL is refused (`IllegalArgumentException`); the gcd must be absolute.
2. The key is the **Mission directory name** (the basename of `missionDirectory`), never the
   mission slug and never a `mission_id` (research §5.1, `status/locking.py:179-190`).
3. Exceptions: all three extend `IOException` (the session API throws `IOException`), carry no
   row content, and name the log or lock path only in their message (the HTTP layer never echoes
   the message; FR-025). `StatusLockHeldException` wraps the `LedgerLockTimeoutException`.

**Validation**: [ ] T013's interop test also asserts that `MissionLockPath` equals core's own
lock path for the same Mission (the peer prints it).

### T008 Locked session and torn-tail refusal

**Files**: `StatusLogAppender.java`, `StatusLogSession.java` (new).

**API** (adjust names if you must, keep the shape):

```java
public final class StatusLogAppender {
  public StatusLogAppender();                                  // LedgerLocks.forThisOs(), JDK FileOps
  StatusLogAppender(LedgerLock lock, FileOps files);           // package-private, for tests
  public <T> T withMissionLock(Path gcd, Path missionDirectory, long deadlineNanos,
                               SessionWork<T> work) throws IOException;
  @FunctionalInterface public interface SessionWork<T> { T run(StatusLogSession session) throws IOException; }
}
public interface StatusLogSession {
  byte[] currentBytes() throws IOException;   // empty array when the file does not exist
  void appendAndSync(byte[] bytes) throws IOException;
}
```

**Steps**:
1. Tests first (use `@TempDir` as the gcd and a Mission directory under it):
   - the lock is held while `work` runs (a second `tryAcquire` on the same path from the test
     returns null) and released after, also when `work` throws;
   - a lock held by another holder until past the deadline → `StatusLockHeldException`, and
     `work` never runs; the wait uses the **remaining** time to `deadlineNanos` (the caller,
     WP08, shares one 10 s deadline between its in-process turn and this lock);
   - `currentBytes()` of a missing file is empty; of an empty file is empty; otherwise the whole
     file;
   - the session refuses a torn tail: `appendAndSync` on a log whose last byte is not `\n` throws
     `TornTailException` and the file is byte-identical afterwards. Also expose the check so the
     caller can refuse **before** deciding anything: `currentBytes()` itself throws
     `TornTailException` on a torn tail (choose one, document it, test it; the caller in WP08
     maps it to `500 STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED`).
   - A trailing `\r\n` is not torn (the last byte is LF).
2. The log path is `<missionDirectory>/status.events.jsonl`. The appender never creates the
   Mission directory (WP08 verifies it exists before calling); a missing directory is an
   `IOException`.
3. No reentrancy: the lock classes have none (research §5.2). Document that a caller must not
   call `withMissionLock` inside `work`.
4. Session invariant (data model): `appendAndSync` at most once per session; a second call
   throws `IllegalStateException`. The session is unusable after `work` returns.

### T009 `appendAndSync`: one write, sync, parent sync, rollback, `FileOps`

**Files**: `StatusLogSession` implementation (package-private class), `FileOps.java`
(package-private interface) and `JdkFileOps.java`.

**Steps**:
1. Tests first, with a recording `FileOps` fake: exactly one write call carries all the bytes;
   force(true) follows; when the file did not exist before, a directory sync of the parent
   follows the file sync; nothing is reported done before both.
2. Mechanics: one channel `READ` for `currentBytes`/tail check, one channel
   `CREATE, WRITE, APPEND` for the write (the JDK refuses `READ` with `APPEND` on one channel).
   Record `sizeBefore = channel.size()` on the append channel. One `write(ByteBuffer)`; a return
   value short of `bytes.length` is a failure (do not loop: one write per unit or batch,
   FR-004). Then `force(true)`.
3. Rollback: on any `IOException` (or short write) from write or force: `truncate(sizeBefore)`,
   `force(true)`, then throw `PersistenceFailedException`. If the truncate or its force fails,
   add it as suppressed and still throw `PersistenceFailedException`; never return normally.
   When the file was created by this call and the write failed, truncating to 0 leaves an empty
   file, which reads as an empty history (acceptable; do not delete it).
4. Parent-directory sync on create: POSIX: `FileChannel.open(parent, READ).force(true)`. Windows
   cannot open a directory channel: skip there and say so in the Javadoc (NTFS journals the
   directory entry). New file permissions: the platform default (umask applies); do not widen.
5. `FileOps` stays package-private with the JDK implementation as the default, so the fault seam
   is not a public API (plan). A narrow `AppendChannel` view (`size`, `write`, `force`,
   `truncate`) keeps the fault fake small.

**Edge cases**: zero-length `bytes` (reject with `IllegalArgumentException`: a fully replayed
request never calls append, FR-004); bytes not ending in `\n` (reject: the appender only appends
full lines).

### T010 Fault-injection tests (NFR-006)

**Files**: `StatusLogFaultInjectionTest.java` (new) and a test-only faulty `FileOps`.

**Steps**:
1. A fault-injecting `FileOps` whose append channel fails on demand: write throws, write
   returns short, force throws, and "kill after sync" (the session's `work` throws right after a
   successful `appendAndSync`, simulating a crash before the HTTP answer).
2. Run ≥ 100 iterations cycling the four faults over a log that grows across iterations (some
   iterations start from a missing file, some from an existing log). After each iteration
   assert, from the real file on disk:
   - failed write, short write, failed sync: the file equals its bytes before the iteration
     (0 bytes left by a failed unit), and `appendAndSync` threw `PersistenceFailedException`;
   - kill after sync: the new line is present and complete (0 acknowledged units lost: what was
     synced stays);
   - the fake records that `appendAndSync` returned normally **only** when force returned
     normally (0 acknowledgements without a completed sync).
3. Also one test where the rollback truncate itself fails: the exception is still
   `PersistenceFailedException` with the truncate failure suppressed.
4. Record the iteration count and the per-fault counts in the test output (a summary line), so
   the PR can quote them.

### T011 `EventLogBoundaryTest`: the fourth package and event-log write ownership

**Files**: `service/event-log/src/test/java/org/speckitty/missionstatus/architecture/EventLogBoundaryTest.java`;
planted fixtures under `.../architecture/fixture/statuslog/` (new).

**Steps**:
1. Add `STATUSLOG = ROOT + ".adapter.out.statuslog.."` and `statusLogRule(String pkg)`: classes
   there depend only on themselves, `adapter.out.ledger..` and `java..` (no domain-model, no
   codec, no `tools.jackson`).
2. Rename `ONLY_THE_THREE_PACKAGES` to `ONLY_THE_FOUR_PACKAGES` (update its `.as(...)` text, the
   class Javadoc list and every reference). Update `EXTRACTED_TYPES`' non-vacuity: keep the 8
   extracted types and add a second list, `STATUSLOG_TYPES` (`adapter.out.statuslog.StatusLogAppender`,
   `StatusLogSession`, `MissionLockPath`), each asserted "imported and checked".
3. **One shared predicate list (ruling R8).** Create `FileWriteApis` in event-log's **test
   fixtures** source set (`service/event-log/src/testFixtures/java/org/speckitty/missionstatus/architecture/FileWriteApis.java`;
   apply Gradle's built-in `java-test-fixtures` plugin in `service/event-log/build.gradle.kts`,
   `testFixturesImplementation(libs.archunit.junit5)`, no new artifact). It exposes one ArchUnit
   predicate, "calls a JDK open-for-write API": `FileChannel.open` (ArchUnit cannot see the
   `WRITE`/`APPEND` option arguments, so every `FileChannel.open` counts; read-only code uses
   `Files.read*`/`Files.newInputStream`), `Files.write*`, `Files.newOutputStream`,
   `Files.newBufferedWriter`, `Files.createFile`, the `FileOutputStream` and `RandomAccessFile`
   constructors, `FileChannel.write` and `FileChannel.truncate`. WP08 consumes the same class from
   command-cli through `testImplementation(testFixtures(project(":event-log")))`; nobody keeps a
   second copy of the list.
   Write ownership inside event-log (FR-010, event-log half): no class outside `STATUSLOG` and
   `LEDGER` matches `FileWriteApis`. Scope record (put it in the rule's Javadoc): event-log is held
   here, command-cli by WP08, and the read service (`mission-status-api`) by the root
   `ArchitectureRules` rule 7 (T012). `adapter.out.ledger` stays
   allowed because its lock classes open lock files read-write and `TornTailRepair` repairs
   ledgers (pre-existing, never a status log). **Settled (research §11, decision 16)**: FR-010's text names only
   command-cli's state-directory adapter as an exception; the ledger lock files are a necessary,
   pre-existing second entry. Record it in the rule's `.because(...)`.
4. Planted violations, each with its own test, as the file does today: a fixture
   `fixture/statuslog/RowParsingAppender` that imports `tools.jackson.core` breaks
   `statusLogRule`; a fixture `fixture/statuslog/ConformingAppender` holds it; a fixture class
   outside both packages that calls `Files.write` breaks the write-ownership rule, and one that calls
   `FileChannel.open` breaks it too; and `ConformingDecoder` outside the four packages still breaks
   the module rule.

### T012 The read service's rule 7 exempts `adapter.out.statuslog` (ruling R9)

**Files**: `service/src/test/java/org/speckitty/missionstatus/architecture/ArchitectureRules.java`,
`LedgerExemptionScopeTest.java`, `ArchitectureControlsTest.java` (all in the read service's test
tree; you own them for this change).

**Why**: the read service imports event-log's classes (they are on its classpath), and its rule 7
allows file APIs only in `adapter.out.filesystem` and `adapter.out.ledger`
(`ArchitectureRules.java:128-130,182-183,234`). The new `adapter.out.statuslog` would fail
`:mission-status-api:check` the moment it exists.

**Steps**:
1. Red first: run `./gradlew --no-daemon :mission-status-api:test --tests '*Architecture*'` with the
   new package present and see rule 7 fail on it.
2. `ArchitectureRules`: add `String statuslog = p(root, "adapter.out.statuslog..")` beside `ledger`,
   with a comment like the ledger one, and exempt it in rule 7's `resideOutsideOfPackages(...)` and
   in its `Rule(7, ...)` scope and description ("... and event-log's ledger and status-log
   packages").
3. `LedgerExemptionScopeTest`: generalize it (or add a sibling test) so **both** exempted packages
   hold only classes whose source URI contains `event-log`, each non-vacuous (the status-log package
   must be on the classpath). A read-service class placed in `adapter.out.statuslog` would otherwise
   get file APIs.
4. `ArchitectureControlsTest`: keep every rule's control; if you add a control for the new
   exemption, update the counts the test asserts (`:160-170`) and `DEFINED_RULE_COUNT` only if a
   rule is added (it should not be: rule 7 is widened, not duplicated).
5. Green: `:mission-status-api:test --tests '*Architecture*' --tests '*LedgerExemptionScopeTest*'`.

### T013 `StatusLogInteropTest` against core's Python status writer (SC-005)

**Files**: `.../adapter/out/statuslog/StatusLogInteropTest.java` (new);
`service/event-log/src/test/resources/statuslog/status_writer_peer.py` (new); declared
out-of-map edit to the `lock-interop` job in `.github/workflows/service.yml`.

**Steps**:
1. The peer (`status_writer_peer.py`) runs under core's venv interpreter
   (`SPEC_KITTY_LOCK_INTEROP_PYTHON`) and imports **core's real status writer** at the pin:
   the Mission lock (`specify_cli.status.locking`, the bounded variant) and the store's append
   (`specify_cli.status.store`). Read those modules at the pin and use the public functions
   core's own emit path uses; do not re-implement the lock in Python. Commands:
   - `lock-path --gcd <dir> --mission-dir <dir>` → JSON `{"lock_path": ...}` (cross-checks T007);
   - `append --gcd ... --mission-dir ... --writer N --count K` → appends K rows, each under the lock,
     with a deterministic `event_id` per (writer, index), and prints a JSON summary;
   - `hold --gcd ... --mission-dir ... --ready <file> --release <file>` → holds the lock until
     released (to prove exclusion and to drive `StatusLockHeldException` with a short deadline).
2. The test (gated like `LockInteropSpikeTest`: `@EnabledIfEnvironmentVariable(named =
   "SPEC_KITTY_LOCK_INTEROP_PYTHON", matches = ".+")`) on a temp git repository:
   - `MissionLockPath` equals the peer's `lock-path`;
   - while the peer holds the lock, `withMissionLock` with a 500 ms deadline throws
     `StatusLockHeldException` and writes nothing;
   - contention: ≥ 3 Python writers and the Java appender append concurrently for ≥ 100
     iterations each; afterwards every line is complete JSON, every expected `event_id` appears
     exactly once, no line is interleaved, and the Java side found the lock held at least once
     (non-vacuous, as `LockInteropSpikeTest` requires);
   - core's appender rewrites the file by temp-and-rename (research §4.4); the Java session opens
     the file fresh inside each lock hold, so it never writes to a replaced inode. Assert it by
     checking the final line count, not by assumption.
3. Results go to `service/event-log/build/status-log-interop/<os>.json` (**not**
   `build/lock-interop/`: the job's existing step counts JSON files there and must keep its
   count).
4. Out-of-map edit, `lock-interop` job only: run
   `./gradlew --no-daemon :event-log:test --tests '*LockInteropSpikeTest' --tests '*StatusLogInteropTest'`;
   add a step after the existing count check that fails unless exactly one
   `service/event-log/build/status-log-interop/*.json` exists; add that directory to the
   artifact upload. Change nothing else in the job; keep every SHA pin. WP10 owns the file and the
   workflow tests; tell WP10's implementer (or note in the PR) if a workflow test pins the old
   run command.

**Validation**:
- [ ] Locally without the env var the test is skipped, and `:event-log:check` is green.
- [ ] If you can, run it locally against a core checkout at the pin:
      `SPEC_KITTY_LOCK_INTEROP_PYTHON=/path/to/core/.venv/bin/python ./gradlew --no-daemon :event-log:test --tests '*StatusLogInteropTest'`.

## Test strategy

```bash
jh=$(scripts/ensure_jdk25.sh); JAVA_HOME=$jh ./gradlew --no-daemon -Dorg.gradle.java.installations.paths=$jh :event-log:check
jh=$(scripts/ensure_jdk25.sh); JAVA_HOME=$jh ./gradlew --no-daemon -Dorg.gradle.java.installations.paths=$jh :event-log:test --tests '*statuslog*' --tests '*EventLogBoundaryTest'
uv run ruff check . && uv run ruff format --check .
jh=$(scripts/ensure_jdk25.sh); JAVA_HOME=$jh ./gradlew --no-daemon -Dorg.gradle.java.installations.paths=$jh :mission-status-api:test --tests '*Architecture*' --tests '*LedgerExemptionScopeTest*'
jh=$(scripts/ensure_jdk25.sh); JAVA_HOME=$jh ./gradlew --no-daemon -Dorg.gradle.java.installations.paths=$jh :event-log:jacocoTestCoverageVerification   # METHOD COMPLEXITY <= 15
uv run pytest tests/test_service_workflows.py tests/test_ci_gate_workflows.py -q   # after the lock-interop edit
```

Blast radius: `:mission-status-api:check` depends on event-log; run
`./gradlew --no-daemon :mission-status-api:test` once (same `jh` prefix) to prove the read
service is untouched (C-010).

## Definition of Done

- [ ] `adapter.out.statuslog` exists with the API above, JDK-only, and its own ArchUnit rule plus
      planted violations; the module rule names four packages.
- [ ] Torn tail refused, never repaired; empty and missing logs are empty histories.
- [ ] One write, one sync, parent sync on create, truncate rollback; ≥ 100 fault-injected
      iterations with 0 lost acks, 0 acks without a sync, 0 bytes left by a failed unit.
- [ ] The read service's rule 7 exempts `adapter.out.statuslog` with an event-log-source scope
      check; `:mission-status-api:test` architecture tests green.
- [ ] `FileWriteApis` (event-log test fixtures) is the single FR-010 predicate list; the event-log
      write-ownership rule uses it and fires on its planted violations.
- [ ] The JaCoCo METHOD/COMPLEXITY rule (max 15) is in event-log's build and passes.
- [ ] `StatusLogInteropTest` passes against core's Python writer in the `lock-interop` job on
      Linux, macOS and Windows (record the CI run in the PR once it exists).
- [ ] `:event-log:check` green; the final commit message lists the commands and counts.

## Risks

- **Rollback failure**: covered by T009 step 3 and a dedicated test.
- **Lock-file interop on Windows**: the channel lock overlaps `msvcrt.locking` at byte 0 (the
  spike); keep using `LedgerLocks.forThisOs()`, never pick a lock yourself.
- **CI result counting**: writing interop results into `build/lock-interop/` breaks the existing
  count step on every OS.
- **Scope creep**: no encoding, no replay, no Mission lookup here; those are WP05 and WP08.

## Reviewer guidance

- Read `appendAndSync` line by line for: a single `write`, the short-write check, `force(true)`
  before return, truncate-then-throw on every failure path.
- Check the boundary test's fourth rule really fails on the planted fixtures (run them).
- Check the `lock-interop` diff touches only the run step, one new check step and the artifact
  paths.
- Check that the write-ownership rule's ledger exception is stated and justified in the PR, not
  silently widened.
