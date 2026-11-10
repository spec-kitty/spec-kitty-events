---
work_package_id: WP05
title: 'Drain-aware wait (D10, #231 4.7)'
dependencies:
- WP03
- WP04
requirement_refs:
- FR-015
- NFR-004
- SC-005
planning_base_branch: kitty/mission-write-service-cutover-readiness-01M70THX
merge_target_branch: main
branch_strategy: Planning artifacts for this mission were generated on kitty/mission-write-service-cutover-readiness-01M70THX. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into main unless the human explicitly redirects the landing branch.
subtasks:
- T025
- T026
- T027
- T028
history:
- timestamp: '2026-11-09T20:30:00Z'
  lane: planned
  agent: system
  action: Prompt generated via /spec-kitty.tasks
agent_profile: java-jenny
authoritative_surface: service/command-cli/src/main/java/org/speckitty/commandcli/application/write/
create_intent:
- service/event-log/src/main/java/org/speckitty/missionstatus/adapter/out/ledger/LockAbandonedException.java
- service/event-log/src/test/java/org/speckitty/missionstatus/adapter/out/ledger/LedgerLockAbandonTest.java
execution_mode: code_change
owned_files:
- service/command-cli/src/main/java/org/speckitty/commandcli/application/write/WriteService.java
- service/command-cli/src/main/java/org/speckitty/commandcli/application/write/WriteTurn.java
- service/command-cli/src/main/java/org/speckitty/commandcli/application/lifecycle/**
- service/event-log/src/main/java/org/speckitty/missionstatus/adapter/out/ledger/LedgerLock.java
- service/event-log/src/main/java/org/speckitty/missionstatus/adapter/out/ledger/LockAbandonedException.java
- service/event-log/src/test/java/org/speckitty/missionstatus/adapter/out/ledger/LedgerLockAbandonTest.java
- service/command-cli/src/test/java/org/speckitty/commandcli/application/write/WriteTurnTest.java
- service/command-cli/src/test/java/org/speckitty/commandcli/application/write/WriteServiceTest.java
- service/command-cli/src/test/java/org/speckitty/commandcli/adapter/in/http/operations/DrainTest.java
- service/command-cli/src/test/java/org/speckitty/commandcli/application/lifecycle/**
role: implementer
tags: []
tracker_refs: []
---
# Work Package Prompt: WP05 — Drain-aware wait (D10, #231 4.7)

## ⚡ Do This First: Load Agent Profile

Load your assigned profile before reading anything else: `/spk-charter-profile-load java-jenny`
(or `spec-kitty agent profile show java-jenny`). Adopt its identity, scope and boundaries.

## Objective

The launcher contract stays exactly as it is: a 5 s drain, then SIGTERM after 10 s (`contracts/mission-write/LAUNCHER.md:50-57`; core `services/launcher.py:83` `STOP_TIMEOUT_S = 10.0`; `LauncherContractProcessTest` NFR-003).

Within it, no request may lose its answer at shutdown (maintainer-confirmed ruling D10, which replaces #236 AC 5's "drain budget > write budget"):

- **Waiting requests.** Once draining starts, a write or a repair still **waiting** for the in-process turn or the Mission lock stops waiting. It answers `503 SERVICE_DRAINING` (no `Retry-After`) within **1 s**, having written nothing.
- **Holders.** A request already **holding** the Mission lock is never interrupted. It finishes and gets its normal answer.
- **No timing changes.** `DRAIN_BUDGET` stays 5 s. The write budget stays 10 s.

## Context (read first)

- Spec: [spec.md](../spec.md) D10, User Story 3 scenarios 2 and 3, FR-015, NFR-004, SC-005.
- Plan: [plan.md](../plan.md) D-E and **"Post-plan adversarial squad → Abandon plumbing, Drain test, Constructor stability"**.
- Seams (base `62af21d0`, plus WP02, WP03 and WP04 merged):
  - **`application/lifecycle/ServiceLifecycle.java`:**
    - `DRAIN_BUDGET` is at :19;
    - `draining()` is at :38, `enter`/`exit` at 43-56, `beginDrain` at :72;
    - `finishDrain` is at 80-99; it waits for in-flight requests to reach 0.

    Callers: `ServiceController.java:43-56` and `IdleTicker.java:29-43`. `ProcessShutdown` and `application.properties:19-21` cap Boot's shutdown phase at 2 s.
  - **`application/write/WriteTurn.java`:** a fair `ReentrantLock`, `tryAcquire(remainingNanos)` returns `Optional<Turn>`.
  - **`application/write/WriteService.java`:** `lockedWrite` is at 126-141. An empty turn gives `SERVICE_BUSY` (:129), and `WriteController:88` adds `Retry-After: 1` for `SERVICE_BUSY`. That is **wrong for drain**.
  - **`application/write/port/StatusLog.java`:** the `LockResult` sealed interface and `withLock(mission, deadline, work)`.
  - **`adapter/out/statuslog/EventLogStatusLog.java`** (`StatusLockHeldException` → LockHeld; `IOException` → PersistenceFailed at about :52).
  - `application/repair/StatusLogRepairService` (WP03): the same turn and lock path.
  - **event-log `adapter/out/ledger/LedgerLock.java`:** `acquire(lockFile, timeout)` polls `tryAcquire` every `RETRY_INTERVAL` = 100 ms until the deadline (about 37-50). Callers and overriders: `StatusLogAppender.withMissionLock` (statuslog), and `TornTailRepairTest.RecordingLock` (overrides `acquire(Path, Duration)` at :100). There are about 24 `withMissionLock` call sites in tests.
  - `ActivityFilter` counts in-flight requests and decrements in `finally` after `flushBuffer`. `exit()` wakes `finishDrain`, and the squad found no lost-wakeup race.
  - **`DrainTest.java:39-96`** drives `finishDrain` through `POST /shutdown` with a fake `lifecycle.enter()`. It has no real write. Its path list is at :63.

## Subtasks

### T025 — Three-state turn acquisition

- **The method.** `WriteTurn` gains `Acquisition acquire(long remainingNanos, BooleanSupplier abandon)`, returning `Acquired(Turn)`, `TimedOut` or `Abandoned`.
  - Implement it by waiting in slices of at most 50 ms (`lock.tryLock(min(slice, remaining))`), checking `abandon` between slices.
  - **Check `abandon` only when not holding the lock.** Once `tryLock` succeeds, return `Acquired`, even if draining began meanwhile: holders finish.
- **Compatibility.** Keep `tryAcquire(long)` as a delegating overload (abandon = `() -> false`).
- **Order.** Document in the javadoc that slicing loses strict FIFO among waiters, in normal operation too. That is accepted, because waiter order is not a contract.
- **`WriteTurnTest`:**
  - acquire, then time out (no abandon) gives `TimedOut`;
  - a waiter, then abandon set, gives `Abandoned` within 100 ms;
  - abandon set while the lock is free gives `Acquired`. A free lock is taken: the request is not yet waiting, and the controller's draining check already refuses new requests.

### T026 — Event-log: abandon-aware lock wait

- **Exception.** New public `LockAbandonedException extends IOException` in `adapter/out/ledger`. If `EventLogBoundaryTest` lists ledger types, add it.
- **`LedgerLock`** gains a `default` method:
  ```java
  default void acquire(Path lockFile, Duration timeout, BooleanSupplier abandon) throws IOException
  ```
  It polls like `acquire(lockFile, timeout)`: after each **failed** `tryAcquire`, it throws `LockAbandonedException` if `abandon.getAsBoolean()`. Then it sleeps `RETRY_INTERVAL` and retries until the deadline (`LedgerLockTimeoutException`). **Never** check `abandon` after a successful `tryAcquire`: dropping a lock it holds would leak the descriptor and keep core blocked.
  - Keep the 2-arg `acquire` behaving exactly as before.
  - `TornTailRepairTest.RecordingLock` must keep compiling unchanged; the `default` method ensures that.
- **`StatusLogAppender`** (statuslog, WP02's package; **a declared out-of-map edit**) gains a `withMissionLock(gcd, missionDirectory, deadline, abandon, work)` overload passing `abandon` to `acquire`. The existing overload delegates with `() -> false`.
- **`LedgerLockAbandonTest`:**
  - a lock held by another channel or process (reuse the existing ledger lock-test pattern), then abandon set after 200 ms, gives `LockAbandonedException` within 300 ms of setting it;
  - an uncontended lock with abandon already true is **acquired**, and released by the caller.

### T027 — Command-cli: map abandonment to 503

- **Port** (`StatusLog.java`, WP03's file; **a declared out-of-map edit**). Add `LockResult.Abandoned<T>()`, and a `withLock(mission, deadline, abandon, work)` overload; the old one delegates with `() -> false`.
- **Adapter** (`EventLogStatusLog`, **declared out-of-map**). Calls the new appender overload, and catches `LockAbandonedException` **before** `IOException`, mapping it to `Abandoned`.
- **`WriteService.lockedWrite`:**
  - uses `turn.acquire(remaining, draining)`, where `TimedOut` gives `SERVICE_BUSY` (unchanged) and `Abandoned` gives `SERVICE_DRAINING`;
  - uses `statusLog.withLock(mission, deadline, draining, …)`, where `Abandoned` gives `SERVICE_DRAINING`, and the exhaustive switch covers it.
- **Repair service** (`StatusLogRepairService`, WP03's; **declared out-of-map**): the same mapping.
- **The draining signal.** `ServiceLifecycle::draining`, injected as a `BooleanSupplier`.
  - Add **delegating** constructors to `WriteService` and `StatusLogRepairService` that default to `() -> false`, so the existing 5 call sites and tests keep compiling.
  - `ServiceWiring` (WP03's file; **declared out-of-map**, one line each) passes `lifecycle::draining`. Check how `ServiceLifecycle` is exposed as a bean.
- **Controllers.** `SERVICE_DRAINING` from the service answers `503` **without** `Retry-After`. Check `WriteController.refuse` (86-90) and the repair controller. Write a test for each.

### T028 — Tests

**`DrainTest`** (HTTP, real rig):

1. **Waiting write:** hold the Mission lock externally (the lock-held pattern of the write tests), start a write on a thread so it waits, then `POST /shutdown`. Assert:
   - the write answers `503 SERVICE_DRAINING`, no `Retry-After`, within 1 s of the shutdown call;
   - the log is byte-identical;
   - the drain completes (stop is invoked; in the rig, assert `finishDrain` returned) within 5 s.

   Positive control: the same setup without a shutdown answers `409 STATUS_LOCK_HELD` after the budget (short test budget).
2. **Waiting repair:** the same with a repair request.
3. **Waiting on the turn:** a write holds the turn through a test hook that blocks inside the lock, and a second write waits on the turn. Shutdown gives the second write `503` within 1 s. The first, a holder, is released and answers `201`.
4. **Holder finishes:** a write that already holds the lock when shutdown arrives answers `201`, and its bytes are in the log.
5. **Path list:** add the repair path to `DrainTest`'s list (:63), so a request arriving while draining is refused.

**Unit tests:** `WriteServiceTest` for both abandon paths, using a fake `StatusLog` returning `Abandoned` and a fake turn. Lifecycle tests stay green, and `DRAIN_BUDGET` is still 5 s (assert it, citing D10).

## Declared out-of-map edits (rationale: the abandon signal must reach every wait)

- `service/event-log/.../statuslog/StatusLogAppender.java` (WP02): the `withMissionLock` overload.
- `service/command-cli/.../application/write/port/StatusLog.java`, `adapter/out/statuslog/EventLogStatusLog.java`, `application/repair/StatusLogRepairService.java` and `config/ServiceWiring.java` (WP03): the overload, the mapping and the wiring.
- `adapter/in/http/operations/WriteController.java` (WP06): only if the no-`Retry-After` mapping needs it.

## Definition of Done

- **Tests:** `./gradlew :event-log:check :command-cli:check` is green, including `LauncherContractProcessTest` and `ServiceLifecycleTest`.
- **Timings:** no change to `DRAIN_BUDGET`, the write budget, or `LAUNCHER.md`.
- **Refusal codes:** no new refusal code.
- **Commits:** commit messages end with `Co-Authored-By:` (your model).

## Risks and reviewer guidance

- **Dropping a held flock:** check that `abandon` is consulted only after a failed `tryAcquire`.
- **Wrong mapping:** check that abandonment never maps to `500` (caught as `IOException`) or to `SERVICE_BUSY` (with `Retry-After`).
- **Flaky timing:** the 1 s assertions must have margin. The worst case is about 50 ms (turn slice) plus 100 ms (poll).
- **Reviewer:** run `DrainTest` 5 times (`--rerun-tasks`) to check for flakiness.

## Do not

- Do not touch logging (WP06), docs (WP07), the contract, or the timings.
- Do not run `spec-kitty` lifecycle commands, push, or open a PR.
