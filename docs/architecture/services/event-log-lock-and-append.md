---
title: Event-log lock and append
description: 'How the Java event-log library shares the CLI''s event-log lock, appends atomically and durably, detects and repairs a torn tail, and why CI pins a core SHA for the interop test.'
doc_status: active
updated: 2026-10-30T00:00:00Z
type: Architecture
kind: Explanation
---

# Event-log lock and append

> **Where this code lives.** The code described here lives in this repository, under
> [`service/`](https://github.com/spec-kitty/spec-kitty-events/tree/main/service) and
> [`contracts/`](https://github.com/spec-kitty/spec-kitty-events/tree/main/contracts),
> imported from core at `512567eee71e31bb39f580c4f7acf8b865e9d1b2` with its history
> ([ADR-0002](../../adr/0002-events-domain-owns-its-services.md)). Citations of those files
> point here; citations of what stays in core (its ADRs, the CLI, the Python appenders) point
> at core at `4eecb124`, where they were checked.

While the CLI still writes a log, a Java writer that touches the same log must exclude the
CLI's writer and write the same bytes. Even after a log is handed over whole, an older CLI
installed in another checkout of the same repository can keep appending to it, so the lock
must hold across languages
([`docs/development/reference/lock-interop-spike.md`](https://github.com/spec-kitty/spec-kitty/blob/4eecb12414dac53432f4af21ba958d199d5ce808/docs/development/reference/lock-interop-spike.md)).

## The lock the Python appender takes

[`src/kernel/event_log_append.py`](https://github.com/spec-kitty/spec-kitty/blob/4eecb12414dac53432f4af21ba958d199d5ce808/src/kernel/event_log_append.py)
is the one interim lock contract for every event log except `status.events.jsonl` and
`canonical-events.jsonl` (#6606). In its own words:

1. **Anchor:** the log itself, or the family directory for one-file-per-record logs
   (`kitty-ops/<op>.jsonl`).
2. **Lock file:** `<git-common-dir>/spec-kitty-locks/event-logs/<digest>.lock`, where
   `<digest>` is the first 32 hex characters of the SHA-256 of the anchor's resolved,
   `os.path.normcase`-d path. Outside a git repository it is `<anchor>.event-log.lock`; when
   git cannot be invoked, the append refuses.
3. **Lock:** an exclusive `kernel.locks.machine_file_lock` with a bounded wait.
4. **Torn tail:** under the lock, a log whose last byte is not LF is refused
   (`EVENT_LOG_TORN_TAIL`) and nothing is written.
5. **Write:** the whole unit in one `os.write` on an `O_APPEND` descriptor, then `os.fsync`.
   A short or failed write cuts the log back to its size before the write.

The OS lock under it is in
[`src/kernel/locks.py`](https://github.com/spec-kitty/spec-kitty/blob/4eecb12414dac53432f4af21ba958d199d5ce808/src/kernel/locks.py):
`fcntl.flock` on POSIX and `msvcrt.locking` on Windows. `status.events.jsonl` keeps the
Mission status lock (`<key>.status.lock`), built on the same primitive.

## The Java side

The `event-log` library's `adapter.out.ledger` package holds the Java half
([README, "Package layout"](https://github.com/spec-kitty/spec-kitty-events/blob/main/service/README.md)):

- **`LedgerLocks.forThisOs()`** picks `flock(2)` through the Foreign Function and Memory
  API on Linux and macOS, and `FileChannel.lock` on Windows, so it excludes the CLI's
  `kernel.locks` holder.
- **`TornTailRepair`** is the Java port of the glossary ledger's `_repair_torn_tail`.

### What the lock-interop spike found (#6193)

Verdict: **GO** on all three operating systems, from CI run
[40557031791](https://github.com/spec-kitty/spec-kitty/actions/runs/40557031791) (the
spike page's evidence table):

| OS | Java lock | Against | Result |
|---|---|---|---|
| Linux | `flock(2)` through FFM | `fcntl.flock` | GO: 0 overlaps in 1730 rounds |
| Linux | `FileChannel.lock` | `fcntl.flock` | **NO-GO**: an `fcntl` record lock, which Linux keeps independent of `flock`; 172 overlaps in 495 rounds |
| macOS | `flock(2)` through FFM | `fcntl.flock` | GO |
| macOS | `FileChannel.lock` | `fcntl.flock` | GO (the macOS lock manager makes the two conflict) |
| Windows | `FileChannel.lock` | `msvcrt.locking` | GO: both are byte-range locks that overlap at byte 0 |

Under load, appends from both languages landed as whole lines, each byte-identical to what
its writer wrote, and both the Python reader and the Java `LedgerLineDecoder` accepted the
whole log. The spike lists what the write service still needs, among them: computing the
lock path itself, porting the status lock's path rule, `O_CLOEXEC` and `O_NOFOLLOW` on the
lock file, checking and writing through one descriptor, and registering the FFM downcall
for the native image.

## Atomic, durable append

The append contract of the write service is set by core
[ADR 2026-10-27-1](https://github.com/spec-kitty/spec-kitty/blob/4eecb12414dac53432f4af21ba958d199d5ce808/docs/adr/4.x/2026-10-27-1-the-write-service-protocol-is-loopback-http-with-a-bearer-token.md)
D-5:

- A unit (one transition and its annotations) is **one `O_APPEND` write of full JSON lines**,
  synced with `fsync` before the answer. A batch is one write and one `fsync`, all or
  nothing. A refusal writes nothing.
- The service writes the bytes the CLI's encoder writes: `sanitize_event_for_log`, then
  `json.dumps(..., sort_keys=True)`.
- On Windows there is no `O_APPEND` atomicity between processes, so the service holds the
  repository's write lock around the write.

The production Java append does not exist yet. The spike's harness appends under its lock
(refuse a torn tail, write the line and its LF in one write on an `APPEND` channel, force it
to disk), and lives in the `event-log` test sources, package
`org.speckitty.missionstatus.spike.lockinterop`.

## Torn-tail detection and repair

A torn tail is what a write cut short by a crash leaves after the last newline.

- **Detection.** The Python appender refuses to append after one (above). The read service
  answers an unterminated last line with `500` and the diagnostic `ledger_torn_tail`, and
  never serves the partial line (README, "Fail-loud reads", L4).
- **Repair.**
  [`TornTailRepair`](https://github.com/spec-kitty/spec-kitty-events/blob/main/service/event-log/src/main/java/org/speckitty/missionstatus/adapter/out/ledger/TornTailRepair.java)
  appends the fragment as one line to `<ledger>.torn` and makes that durable, then cuts the
  ledger back to its last newline and makes that durable too, so no byte is lost. It runs
  under the leaf lock `<ledger>.lock`, the one the Python repair takes, and re-reads the
  tail inside it. The caller decides when to repair.
- **Shared goldens.**
  [`event-log/src/test/resources/torn-tail/repair-cases.json`](https://github.com/spec-kitty/spec-kitty-events/blob/main/service/event-log/src/test/resources/torn-tail/repair-cases.json)
  holds the cases both the Java and the Python repair are tested against, byte for byte.
  It ships in the `conformance-fixtures` release asset
  ([ADR-0003](../../adr/0003-gradle-build-and-service-release-line.md)).

Which policy the write service applies, refuse or repair, is open: the glossary writer
repairs, while the spike's Java append refuses (spike page, "What the write service
needs"). Core ADR 2026-10-27-1 open question 2 defaults to refusing until the tail is
repaired.

## Why CI pins a core SHA for the interop test

The interop test drives core's production Python code, never a copy: the peer
`scripts/ci/lock_interop_peer.py` calls `kernel.event_log_append` and
`kernel.locks.machine_file_lock` (spike page, "What was tested"). That code
stays in core, so this repository's CI checks core out to run it.

[ADR-0002](../../adr/0002-events-domain-owns-its-services.md) decision 4 sets the terms:

- The services never depend on core at build time or at run time.
- Test-only peers, the lock-interop peer among them, use a core SHA pinned in this
  repository and moved only by a reviewed PR.
- The pinned SHA never reaches a shipped artifact.

A pin rather than core `main` keeps this CI from depending on a moving reference. The cost
is named in the ADR's risks: a stale pin hides a lock change in core until the pin moves.
In core today, the `lock-interop` job re-runs whenever `src/kernel/locks.py` or
`src/kernel/event_log_append.py` change (spike page, "Evidence").
