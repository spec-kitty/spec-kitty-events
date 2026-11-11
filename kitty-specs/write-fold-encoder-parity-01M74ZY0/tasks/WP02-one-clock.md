---
work_package_id: WP02
title: One clock for the write budget and the Mission lock (#231 4.8)
dependencies: []
requirement_refs:
- FR-005
- NFR-004
planning_base_branch: issue-237-write-fold-encoder-parity
merge_target_branch: issue-237-write-fold-encoder-parity
branch_strategy: Planning artifacts for this mission were generated on issue-237-write-fold-encoder-parity. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into issue-237-write-fold-encoder-parity unless the human explicitly redirects the landing branch.
subtasks:
- T007
- T008
- T009
- T010
history:
- timestamp: '2026-11-11T11:30:00Z'
  lane: planned
  agent: system
  action: Prompt generated via /spec-kitty.tasks
agent_profile: java-jenny
authoritative_surface: service/event-log/src/main/java/org/speckitty/missionstatus/adapter/out/statuslog/
create_intent:
- service/event-log/src/test/java/org/speckitty/missionstatus/adapter/out/statuslog/StatusLogAppenderClockTest.java
- service/command-cli/src/test/java/org/speckitty/commandcli/adapter/out/statuslog/EventLogStatusLogClockTest.java
- docs/changelog/unreleased/237-one-clock.md
execution_mode: code_change
owned_files:
- service/event-log/src/main/java/org/speckitty/missionstatus/adapter/out/statuslog/StatusLogAppender.java
- service/event-log/src/test/java/org/speckitty/missionstatus/adapter/out/statuslog/StatusLogAppenderClockTest.java
- service/command-cli/src/main/java/org/speckitty/commandcli/config/ServiceWiring.java
- service/command-cli/src/main/java/org/speckitty/commandcli/adapter/out/statuslog/EventLogStatusLog.java
- service/command-cli/src/test/java/org/speckitty/commandcli/adapter/out/statuslog/EventLogStatusLogClockTest.java
- service/command-cli/src/test/java/org/speckitty/commandcli/config/ServiceWiringTest.java
- docs/changelog/unreleased/237-one-clock.md
role: implementer
tags: []
tracker_refs: []
---
# Work Package Prompt: WP02 — One clock for the write budget and the Mission lock (#231 4.8)

## ⚡ Do This First: Load Agent Profile

Load your assigned profile before reading anything else: `/spk-charter-profile-load java-jenny`
(or `spec-kitty agent profile show java-jenny`). Adopt its identity, scope and boundaries.

## Objective

The write path computes its deadline from an injected nano clock
(`WriteService.java:122`: `long deadline = nanoClock.getAsLong() + budget.toNanos()`), but the
Mission lock's remaining wait is computed from the real clock
(`service/event-log/.../StatusLogAppender.java:55`: `deadlineNanos - System.nanoTime()`).
Production passes `System::nanoTime` (`ServiceWiring.java:89-91`), so it is correct today, but any
fake-clock test gets the wrong lock wait. Make the appender read the **same** injected clock (FR-005).

## Context

- Spec: FR-005; research.md row "Two clocks"; plan RD-3.
- `StatusLogAppender` constructors: `public StatusLogAppender()` (:24) and package-private
  `StatusLogAppender(LedgerLock, FileOps)` (:28). Callers: `ServiceWiring.java:107` (production) and
  ~10 tests under `service/event-log/src/test/.../statuslog/`.
- `withMissionLock(gcd, dir, deadlineNanos, abandon, work)` (:52) — the deadline is absolute in the
  caller's clock.
- command-cli: `EventLogStatusLog.java:33` calls `appender.withMissionLock(...)` with the deadline
  it receives from `WriteService.lockedWrite` and `StatusLogRepairService` (both get `nanoClock`).
- `WriteTurn.acquire(remainingNanos, abandon)` already takes a relative budget computed from the
  injected clock (`WriteService.java:123`); its internal slicing loop on `System.nanoTime()` is
  relative and stays.
- `event-log` is a library shared with the read service (`service/src`, owned by another session).
  The change must be **additive**: existing constructors keep their behaviour.

## Subtasks

### T007 — Appender clock
1. Add a `LongSupplier nanoClock` field. `public StatusLogAppender()` → `System::nanoTime`.
   Add `public StatusLogAppender(LongSupplier nanoClock)` and extend the package-private
   constructor (keep the old two-arg one delegating with `System::nanoTime`, so no test changes).
2. `withMissionLock`: `long remaining = Math.max(0L, deadlineNanos - nanoClock.getAsLong());`.
3. Javadoc: the deadline is in the injected clock's time base.

### T008 — Wiring
`ServiceWiring.statusLog(...)` takes the `LongSupplier nanoClock` bean and constructs
`new StatusLogAppender(nanoClock)`. Adjust `ServiceWiringTest` if it checks bean signatures.

### T009 — Fake-clock tests
1. `StatusLogAppenderClockTest` (event-log): a fake clock at `t0`; deadline `t0 + 3 s`; a recording
   `LedgerLock` (see `StatusLogTailRepairFaultTest`/`TornTailRepairTest` for existing recording
   locks) captures the `Duration` passed to `acquire`. Assert it equals `3 s` exactly, independent
   of wall time (advance the fake clock between deadline computation and the call to show the
   appender reads the fake). Also: a deadline in the fake past gives `Duration.ZERO`.
2. `EventLogStatusLogClockTest` (command-cli): through the adapter (and, if cheap, through
   `WriteService` with a fake clock), assert the same for the write path; and for the repair path
   either the same adapter test or a `StatusLogRepairService` test. Keep it deterministic: no
   sleeps, no wall-clock assertions.
3. A mutation check you run once and note in the activity log: reverting `withMissionLock` to
   `System.nanoTime()` makes T009's tests fail.

### T010 — Fragment
`docs/changelog/unreleased/237-one-clock.md`, section `Changed`: "- The write and repair paths now
compute the Mission lock's wait from the same clock as the write budget's deadline. Production
behaviour is unchanged; a test with a fake clock now gets the right wait (#237; #231 item 4.8)."

## Definition of Done
- `./gradlew :event-log:test :command-cli:test` green (record counts).
- No existing `StatusLogAppender` caller had to change except `ServiceWiring`.
- Fragment passes `scripts/changelog.py check`.

## Risks and reviewer guidance
- Reviewer: confirm the default constructors still use `System::nanoTime`; confirm the read service
  (`service/src`) does not construct `StatusLogAppender` (`grep -rn "new StatusLogAppender" service/src`).
- Do not touch `WriteService`/`WriteTurn` semantics (drain behaviour from #236 must be unchanged).

## Do not
- Do not touch `service/src`.
- Implementation command: `spec-kitty agent action implement WP02 --agent <name> --mission write-fold-encoder-parity-01M74ZY0`
