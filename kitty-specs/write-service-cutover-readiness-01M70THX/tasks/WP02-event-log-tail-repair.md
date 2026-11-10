---
work_package_id: WP02
title: 'Event-log: locked status-log tail repair'
dependencies: []
requirement_refs:
- FR-003
- FR-004
- FR-009
- NFR-001
- C-001
- C-002
planning_base_branch: kitty/mission-write-service-cutover-readiness-01M70THX
merge_target_branch: main
branch_strategy: Planning artifacts for this mission were generated on kitty/mission-write-service-cutover-readiness-01M70THX. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into main unless the human explicitly redirects the landing branch.
subtasks:
- T007
- T008
- T009
- T010
- T011
history:
- timestamp: '2026-11-09T20:30:00Z'
  lane: planned
  agent: system
  action: Prompt generated via /spec-kitty.tasks
agent_profile: java-jenny
authoritative_surface: service/event-log/src/main/java/org/speckitty/missionstatus/adapter/out/statuslog/
create_intent:
- service/event-log/src/main/java/org/speckitty/missionstatus/adapter/out/statuslog/TailClassifier.java
- service/event-log/src/main/java/org/speckitty/missionstatus/adapter/out/statuslog/TailRepair.java
- service/event-log/src/test/java/org/speckitty/missionstatus/adapter/out/statuslog/StatusLogTailRepairTest.java
- service/event-log/src/test/java/org/speckitty/missionstatus/adapter/out/statuslog/StatusLogTailRepairFaultTest.java
execution_mode: code_change
owned_files:
- service/event-log/src/main/java/org/speckitty/missionstatus/adapter/out/statuslog/**
- service/event-log/src/test/java/org/speckitty/missionstatus/adapter/out/statuslog/**
- service/event-log/src/test/java/org/speckitty/missionstatus/architecture/EventLogBoundaryTest.java
role: implementer
tags: []
tracker_refs: []
---
# Work Package Prompt: WP02 — Event-log: locked status-log tail repair

## ⚡ Do This First: Load Agent Profile

Load your assigned profile before reading anything else: `/spk-charter-profile-load java-jenny`
(or `spec-kitty agent profile show java-jenny`). Adopt its identity, scope and boundaries.

## Objective

Add the **only** code that may mutate a torn status log: a repair that runs inside the existing per-Mission lock (`StatusLogAppender.withMissionLock`). It classifies the tail through a caller-supplied callback, then does one of three things:

- **none:** changes nothing;
- **completed:** appends exactly one LF;
- **quarantined:** moves the fragment to `status.events.jsonl.torn` and truncates the log to its last LF.

Under **any single fault**, every byte of the original log survives either in the log or in the quarantine file (NFR-001). The write path is untouched: `appendAndSync` still refuses a torn tail (FR-009, C-001).

This is the destructive core of the mission. Durability ordering is the whole point. Read every seam before writing code.

## Context (read first)

- Spec: [spec.md](../spec.md) FR-003, FR-004, FR-009, NFR-001, C-001, C-002, and the edge cases.
- Plan: [plan.md](../plan.md) D-A, and **"Post-plan adversarial squad → Durability and concurrency"**, which overrides D-A.
- Data model: [data-model.md](../data-model.md), "Repair action rules" and "Invariants".
- Seams, at mission base `62af21d0`, under `service/event-log/src/main/java/org/speckitty/missionstatus/adapter/out/statuslog/`:
  - `LockedStatusLogSession.java`:
    - `currentBytes` is at 20-26;
    - `appendAndSync` is at 28-50, and its **size re-check at 40-43 is the pattern to copy**;
    - `readAndCheckTail` is at 56-62 (keep it as is);
    - `writeAndSync` is at 64-73;
    - `rollback` (truncate plus force) is at 75-84.
  - `StatusLogAppender.java`: `withMissionLock` is at 42-53; the `MissionLockPath` is at :43; the package-private ctor `(LedgerLock, FileOps)` is at 27-30.
  - `StatusLogSession.java` (the port, 8-12) and `MissionLockPath.java:16-30`.
  - `FileOps.java` (8-31) and `JdkFileOps`:
    - `openForAppend` is CREATE+WRITE+APPEND+NOFOLLOW (39-79);
    - `AppendChannel{size, write, force, truncate, close}`;
    - `exists` follows symlinks (18-20), so do **not** use it for the `.torn` type check.
  - `TornTailException.java` (its message holds the host path; do not reuse it in new messages).
- Precedent, **not** to reuse: `adapter/out/ledger/TornTailRepair.java`. It takes `<ledger>.lock` (wrong lock) and logs host paths. Its quarantine idea is the model; its lock and logging are not.
- Boundary rules (`service/event-log/src/test/.../architecture/EventLogBoundaryTest.java`):
  - `STATUSLOG_TYPES` is at 65-66. Add any new public type.
  - `statusLogRule` (101-103): statuslog depends only on itself, ledger and `java..`. **No codec here.** The "is this one complete row?" decision is the `TailClassifier` callback, implemented in command-cli (WP03).
- Existing tests to keep green and extend:
  - `StatusLogAppenderTest` (torn tail refused at 114-122);
  - `StatusLogFaultInjectionTest` (`FaultyFileOps implements FileOps` at :209; faults by kind, `inconsistentRead`);
  - `StatusLogSessionTest` (`RecordingFileOps` at :98);
  - `StatusLogInteropTest` (core lock interop).

## Subtasks

### T007 — `FileOps` additions and fakes

- **Reuse first.** Most of the repair needs only `read`, `openForAppend` (for both the log and `.torn`), `AppendChannel.size/write/force/truncate` and `syncDirectory`.
- **The one new need** is a no-follow type check of `.torn`. Add `FileOps.regularOrAbsent(Path)` returning `true` when the path does not exist (without following links) or is a regular file not reached through a link. Implement it with `Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)`, catching `NoSuchFileException`.
- **Fakes.** Implement the new method in **every** `FileOps` fake in the same commit: `FaultyFileOps` (`StatusLogFaultInjectionTest:209`), `RecordingFileOps` (`StatusLogSessionTest:98`), and any other you find (`grep -rn "implements FileOps" service/event-log/src/test`).
- **Nth-call fault fake.** Add `NthCallFaultyFileOps` (test-only, in the statuslog test package). It wraps `JdkFileOps` and every `AppendChannel` it opens. It counts **every** mutating call (open, write, force, truncate, syncDirectory) in order, and throws `IOException` on call N. It records the call list, so a test can first run a clean repair to enumerate the calls, then re-run with N = 1..k.

### T008 — The repair API

- **`TailClassifier`** (public functional interface in statuslog):
  ```java
  @FunctionalInterface
  public interface TailClassifier {
    /** True iff {@code fragment} is exactly one status row that folds after {@code prefix}. Never throws for bad input: false. */
    boolean isCompleteRow(byte[] prefix, byte[] fragment);
  }
  ```
- **`TailRepair`** (public record): `Action action` (enum `NONE`, `COMPLETED`, `QUARANTINED`), `long movedBytes`, `byte[] bytesAfter` (the log's content after the repair, so the caller can fold without re-reading). Use a defensive copy in the accessor, or document ownership.
- **The port.** `StatusLogSession` gains `TailRepair repairTail(TailClassifier classifier) throws IOException`. The session is per-lock, so `repairTail` runs only inside `withMissionLock`, reusing its lock path, deadline and `StatusLockHeldException` unchanged.
- **One mutation per session.** A session that has repaired must not also `appendAndSync`, and vice versa. Throw `IllegalStateException` on a second mutation, as the existing "append at most once" invariant does.
- **Boundary test.** Add `TailClassifier` and `TailRepair` to `STATUSLOG_TYPES`.

### T009 — The repair in `LockedStatusLogSession`

Follow this order exactly; the comments in the code must state the invariant at each step.

1. `bytes = fileOps.exists(log) ? fileOps.read(log) : empty`. A missing log means `NONE`: create nothing.
2. If `bytes.length == 0` or `bytes[last] == '\n'`, the result is `NONE`, with `bytesAfter = bytes`. **No open, no write, no file created.**
3. `lastLf` = the index of the last `\n` (−1 if none). `prefix = bytes[0..lastLf]` (inclusive; empty if −1) and `fragment = bytes[lastLf+1..]`.
4. Open the log channel with `openForAppend(log)`, then **size re-check**: `channel.size() == bytes.length`, else `PersistenceFailedException` with nothing changed.
5. `classifier.isCompleteRow(prefix, fragment)`. Catch any `RuntimeException` from the callback and treat it as `false`.
6. **COMPLETED:**
   1. write `"\n"` and require 1 byte written;
   2. `force`;
   3. on any failure, truncate back to `bytes.length`, force, and throw `PersistenceFailedException` (as `rollback` does);
   4. the result is `COMPLETED`, `movedBytes = 0`, `bytesAfter = bytes + '\n'`.
7. **QUARANTINED:**
   1. **Type check:** `fileOps.regularOrAbsent(torn)`, else `PersistenceFailedException` with nothing changed. This covers a symlink, a directory or a FIFO; never open a FIFO, since it would block under the flock.
   2. Open `torn` with `openForAppend` (NOFOLLOW) and note `tornStart = tornChannel.size()`.
   3. Write `fragment + "\n"` and require the full length. Then `force` the torn channel.
   4. **Always** `syncDirectory(parent)`, new file or not.
   5. Size re-check the log again (`channel.size() == bytes.length`), else roll `.torn` back and fail.
   6. `channel.truncate(lastLf + 1)`, then `force` the log channel, then `syncDirectory(parent)`.
   7. **Failure before step 6's truncate:** truncate `.torn` back to `tornStart` and force it, best effort. Log nothing with a path. Throw `PersistenceFailedException`. The log is untouched.
   8. **Failure in step 6 after the truncate call:** throw `PersistenceFailedException`. The fragment is durable in `.torn`, and a re-run answers `NONE`.
   9. The result is `QUARANTINED`, `movedBytes = fragment.length`, `bytesAfter = prefix`.
8. Close both channels in `finally`.

Keep each method's complexity at 15 or less: extract `complete(...)` and `quarantine(...)`. ASCII-only bytes mean LF truncation never splits a code point (`status_log.py` writes `ensure_ascii`). Say so in a comment.

### T010 — The quarantine type guard

This is part of T009 step 7.1. Tests:

- a **dangling symlink** at `.torn`: `PersistenceFailed`, the log is unchanged, and the link target is not created;
- a **live symlink** to a regular file elsewhere: `PersistenceFailed`, and the target is unchanged;
- a **directory**: `PersistenceFailed`;
- a **FIFO**: create it with `mkfifo` through `ProcessBuilder` in the test, POSIX only (`@DisabledOnOs(WINDOWS)`). The result is `PersistenceFailed`, returned promptly; assert under a 2 s `assertTimeoutPreemptively`.

### T011 — Tests

`StatusLogTailRepairTest` drives the real `JdkFileOps` in a temp git-common-dir layout, as `StatusLogAppenderTest` does.

**Actions**
- **Missing log, empty log, LF-terminated log:** `NONE`, byte-identical, and no `.torn` created. Pair each with a positive control on the same fixture: append a torn fragment and the repair answers `QUARANTINED`.
- **Mid-JSON fragment:**
  - the result is `QUARANTINED`, with `movedBytes` equal to the fragment length;
  - the log equals the prefix;
  - `.torn` equals the fragment plus LF.
- **Existing `.torn`:** a second quarantine appends to it, and the earlier content is unchanged.
- **Classifier says true:** `COMPLETED`, exactly one LF added, no `.torn`. A classifier that throws is treated as false and the result is `QUARANTINED`.
- **Whole-file fragment** (no LF anywhere): quarantine truncates the log to 0 bytes; completion adds one LF.
- **Idempotence:** a re-run after each action answers `NONE`.
- **The classifier receives the exact prefix and fragment:** assert the arguments.

**Lock**
- Lock held by another holder (reuse the pattern in `StatusLogAppenderTest`): `StatusLockHeldException`, nothing changed.

**Write-path regression (FR-009)**
- `appendAndSync` on a torn log still throws `TornTailException`, and the file is unchanged. Pair it with the repair of the same fixture succeeding.

**Fault matrix (`StatusLogTailRepairFaultTest`)**
- **Size changed after the read:** use `inconsistentRead` or a fake whose `size()` differs. The result is `PersistenceFailed`, and both files are unchanged.
- **Quarantine run:** enumerate the mutating calls of a clean run with `NthCallFaultyFileOps`. For each N:
  - assert either (log byte-identical and `.torn` at its starting length), or (log equal to the prefix and `.torn` holding the fragment);
  - never anything else, and never a lost byte;
  - assert which outcome applies for each N (before or after the truncate);
  - then re-run without faults: the result is `NONE` if already truncated, otherwise `QUARANTINED`.
- **Completed run:** the same, with the outcomes "log byte-identical" or "log plus LF".
- **Short write:** a fake `write` returning fewer bytes is treated as a failure, and `.torn` is rolled back to its starting size.

## Definition of Done

- **Tests:** `./gradlew :event-log:check` is green: the new tests, `StatusLogAppenderTest`, `StatusLogFaultInjectionTest`, `StatusLogSessionTest`, `EventLogBoundaryTest` and the interop test.
- **Untouched:** no change to `currentBytes()`, `readAndCheckTail`, or the write path's behaviour.
- **Quality:** method complexity at most 15. Javadoc on the public types states the invariants and the durability order. No host path in any exception message added here.
- **Commits:** commit messages end with `Co-Authored-By:` (your model).

## Risks and reviewer guidance

- **Byte loss:** check that the truncate is the last mutation of the log on the quarantine path, and that every pre-truncate failure leaves the log identical.
- **Foreign bytes:** check that the size re-check happens before the first mutation and again before the truncate.
- **Blocking:** the FIFO test must not hang the suite.
- **Reviewer:** read the fault test's per-N expectation table and confirm it covers every call the clean run makes. Mutation check: delete the size re-check or the `.torn` rollback and confirm a test goes red.

## Do not

- Do not touch command-cli (WP03), `LedgerLock` (WP06), or the codec.
- Do not run `spec-kitty` lifecycle commands, push, or open a PR.
