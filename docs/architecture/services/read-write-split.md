---
title: The read-write split
description: 'The CQRS split of the events domain: the read-only Mission Status Read API, the Java write service as the one canonical writer, and the writers and readers today versus the target.'
doc_status: active
updated: 2026-10-31T00:00:00Z
type: Architecture
kind: Explanation
---

# The read-write split

> **Where this code lives.** The code described here lives in this repository, under
> [`service/`](https://github.com/spec-kitty/spec-kitty-events/tree/main/service) and
> [`contracts/`](https://github.com/spec-kitty/spec-kitty-events/tree/main/contracts),
> imported from core at `512567eee71e31bb39f580c4f7acf8b865e9d1b2` with its history
> ([ADR-0002](../../adr/0002-events-domain-owns-its-services.md)). Citations of those files
> point here; citations of what stays in core (its ADRs, the CLI, the Python appenders) point
> at core at `4eecb124`, where they were checked.

The events domain splits commands from queries. One service reads the event logs and never
writes them; one service will write them and is the only writer. Both share the
`domain-model` library ([overview](./overview.md)). Core
[ADR 2026-10-22-1](https://github.com/spec-kitty/spec-kitty/blob/4eecb12414dac53432f4af21ba958d199d5ce808/docs/adr/4.x/2026-10-22-1-the-java-write-service-is-the-canonical-event-writer.md)
calls the write service "the command side of a CQRS split; the read API is the query side"
(quoting #6117).

## The read side: the Mission Status Read API

Core
[ADR 2026-10-01-2](https://github.com/spec-kitty/spec-kitty/blob/4eecb12414dac53432f4af21ba958d199d5ce808/docs/adr/4.x/2026-10-01-2-mission-status-read-api-and-dashboard-extraction.md)
replaced the CLI-bundled dashboard with this API. What it decides, and what ships
([`service/README.md`](https://github.com/spec-kitty/spec-kitty-events/blob/main/service/README.md)):

- **Two granularities** (D-2): an overview of many Missions and the detail of one. The
  service serves `GET /api/v1/project`, `GET /api/v1/missions` (the overview page),
  `GET /api/v1/events` (a server-sent change stream), the two artifact reads of one Mission
  and `GET /api/v1/openapi.yaml`. The single-Mission and work package detail routes answer
  `404` by design until #957 is finished, held there by `OutOfScopeRoutesTest`.
- **Read-only and independent of drain** (D-6): it never writes to the project, takes no
  lock, starts no process and reads no git state beyond the one `HEAD` file.
- **Loopback only:** it binds `127.0.0.1` (BindGuard) and refuses a foreign `Host`, a
  foreign `Origin` and `Sec-Fetch-Site: cross-site` with `403 request_refused`.
- **Fail-loud reads:** a fault is never answered as a healthy success. The README's table
  gives each condition (L1 to L11) an observable and a diagnostic code. For example, an
  unterminated last line is a `500` with `ledger_torn_tail`, and the partial line is never
  served.
- **Allowlists:** the status reads open exactly the paths of the "Read allowlist" and the
  artifact reads exactly those of the "Artifact read allowlist". A test compares each list
  with its constant in the code (`Allowlist`, `ArtifactAllowlist`), entry for entry.
- **Conformance:** the Java reducer is checked against the Python reducer on a golden set
  and, nightly, on the whole corpus; the known divergences are listed in the README
  ("Recorded divergences from the Python reference").

No spec-kitty command starts the service. Core pins its release for CI only
([ADR-0002](../../adr/0002-events-domain-owns-its-services.md), decision 3).

## The write side: one canonical writer

Core
[ADR 2026-10-14-1](https://github.com/spec-kitty/spec-kitty/blob/4eecb12414dac53432f4af21ba958d199d5ce808/docs/adr/4.x/2026-10-14-1-event-native-core-one-canonical-event-writer.md)
decides that one module creates and persists every event, behind an EventBridge port the
domain calls (D-1, D-2). Core ADR 2026-10-22-1 makes that module a Java service:

- **One writer** (D-1): the write service's `event-log` module is the only code that
  persists events, for every log ADR 2026-10-14-1 names. Python keeps no in-process append
  once a log has moved.
- **The CLI decides when and what; the service decides how and where** (D-3). The CLI's
  EventBridge adapter sends the request; the service validates it against the lane state
  machine and guards, resolves the write location, appends and answers with the persisted
  event or a named refusal.
- **One resident service per repository** (D-2), keyed on the git common dir, so every
  worktree of a clone reaches the same service. Not per worktree, and not a process per
  command.
- **Native first, JVM jar as fallback, no Python writer** (D-5). With neither a native
  image nor a JDK, every write refuses with a named code and nothing is written.

**Status: the write service runs, and the write core is not built yet.**
- **The service.** `service/command-cli` (#156) is the write-service deployable. It
  implements the launcher contract and the protocol surface of its contract,
  [`contracts/mission-write`](https://github.com/spec-kitty/spec-kitty-events/tree/main/contracts/mission-write)
  (#6675, PR #6729; see [write-service protocol](./write-service-protocol.md)).
  - It answers every write with a fail-closed refusal and writes nothing.
  - The canonical writer (#160) and the lane authority (#161) are the write core.
- **The library parts.** The write-side parts of the `event-log` library from #6193 exist:
  the ledger lock and the torn-tail repair
  ([event-log lock and append](./event-log-lock-and-append.md)). The Java append
itself exists only in the lock-interop spike's test harness; the README says the append
adapter "goes into this module under a rule of its own".

## Writers and readers: today and target

A summary of
[event ledger writers and readers](./event-ledger-writers-and-readers.md),
which holds the full writer table, reader table and gap table (G-1 to G-19) with file and
line citations. That page was written in core and came into this repository with its
history at cutover.

| Concern | Today (core `main`) | Target (the ADRs) |
|---|---|---|
| Who writes | About fifteen append implementations in more than twelve modules, at least six row shapes. The EventBridge port is a skeleton that only the work package transition doors call (G-1). | The Java write service alone (ADR 2026-10-22-1 D-1). |
| Lane transitions in `status.events.jsonl` | `status/store.py` atomic primitives (temp file, fsync, `os.replace`) under the Mission status lock. | A single `O_APPEND` write and `fsync` in the service (ADR 2026-10-27-1 D-5). |
| Other event logs | `kernel.event_log_append`: one interim lock, torn-tail refusal, one `O_APPEND` write plus `fsync` (#6606). Glossary writes through `spec_kitty_events.persistence.append_event`, the only contract writer. | Handed to the service one log at a time, glossary first (ADR 2026-10-22-1, migration order). |
| Row shape | Flat `StatusEvent` rows with no version, a `"5.0.0"` lifecycle envelope, and other own shapes. | `spec-kitty-events` row envelopes with a row and a payload version (ADR 2026-10-14-1 D-3, D-3a). |
| Who reads | CLI readers through `diary.reduce_parsed` (wall clock); some raw readers bypass it (G-13); the Java read API with its own port of the reducer, without the approved-departure lift (G-4, #6268). | One read port for every CLI status read, with the Java service conformance-gated against the Python reducer (ADR 2026-10-01-2 D-5 as amended). |
| Proof of one writer | `tests/architectural/test_status_events_writes_gate.py` and `test_event_log_writes_gate.py` ledger today's writers. | A Python writes gate with an empty allowlist and an ArchUnit rule that only `event-log` opens a ledger for writing (ADR 2026-10-22-1, Confirmation). |
