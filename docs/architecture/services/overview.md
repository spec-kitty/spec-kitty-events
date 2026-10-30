---
title: Services overview
description: The Mission Status Read API and the future write service at C4 levels 1 and 2, the onion layering the ArchUnit tests enforce, and where the code lives before and after cutover.
doc_status: active
updated: 2026-10-29T00:00:00Z
type: Architecture
kind: Explanation
---

# Services overview

> **Where this code lives.** The services described here move into this repository at
> cutover ([ADR-0002](../../adr/0002-events-domain-owns-its-services.md)). Until then the
> source of truth is core, `spec-kitty/spec-kitty` at `4eecb124`, and every citation on this
> page points there. Core's counterpart record is ADR 2026-10-29-1,
> [the events domain and its Java services live in spec-kitty-events](https://github.com/spec-kitty/spec-kitty/blob/main/docs/adr/4.x/2026-10-29-1-the-events-domain-and-its-java-services-live-in-spec-kitty-events.md)
> (accepted in core PR spec-kitty/spec-kitty#6782; the link resolves once that PR merges into
> core `main`).

The events domain has two services, one per side of a CQRS split
([read-write split](./read-write-split.md)):

- **The Mission Status Read API** (`mission-status-api`) exists. It is a read-only,
  loopback-only HTTP service
  ([`spec-kitty/spec-kitty@4eecb124:services/mission-status-api/README.md`](https://github.com/spec-kitty/spec-kitty/blob/4eecb12414dac53432f4af21ba958d199d5ce808/services/mission-status-api/README.md)).
- **The write service** (`command-cli`) is **not built yet**. Its protocol contract exists
  ([`contracts/mission-write`](https://github.com/spec-kitty/spec-kitty/tree/4eecb12414dac53432f4af21ba958d199d5ce808/contracts/mission-write),
  #6675), and so do the lock and torn-tail parts of the `event-log` library (#6193). See
  [event-log lock and append](./event-log-lock-and-append.md) and the
  [write-service protocol](./write-service-protocol.md).

## C4 level 1: context

```mermaid
flowchart LR
  cli["Spec Kitty CLI (core)"]
  ui["Dashboard and desktop consumers"]
  read["Mission Status Read API<br/>(core pins it for CI only)"]
  write["Write service<br/>(one per repository, future)"]
  logs[("Event log files<br/>in the repository")]
  ui -->|HTTP, loopback| read
  cli -.->|"core CI pins a release"| read
  cli -.->|"loopback HTTP + bearer token (future)"| write
  read -->|reads| logs
  write -.->|appends| logs
  cli -->|appends today| logs
```

- **Read API.** Its consumers are external UIs: the dashboard in `spec-kitty-mission-ui` and
  Kitty Desktop (core
  [ADR 2026-10-01-2](https://github.com/spec-kitty/spec-kitty/blob/4eecb12414dac53432f4af21ba958d199d5ce808/docs/adr/4.x/2026-10-01-2-mission-status-read-api-and-dashboard-extraction.md),
  Amendment 2026-10-03). No spec-kitty command starts it, and it is not in the PyPI wheel
  (README, first paragraph). Core pins its release for CI only
  ([ADR-0002](../../adr/0002-events-domain-owns-its-services.md), decision 3).
- **Write service.** One resident service per repository, keyed on the git common dir,
  reached through the CLI's EventBridge port (core
  [ADR 2026-10-22-1](https://github.com/spec-kitty/spec-kitty/blob/4eecb12414dac53432f4af21ba958d199d5ce808/docs/adr/4.x/2026-10-22-1-the-java-write-service-is-the-canonical-event-writer.md)
  D-2, D-3).
- **Today the CLI still appends** to every event log itself
  ([`docs/architecture/event-ledger-writers-and-readers.md`](https://github.com/spec-kitty/spec-kitty/blob/4eecb12414dac53432f4af21ba958d199d5ce808/docs/architecture/event-ledger-writers-and-readers.md),
  "Current state: writers").

Core's own C4 pages
([`docs/architecture/diagrams/01_context`](https://github.com/spec-kitty/spec-kitty/blob/4eecb12414dac53432f4af21ba958d199d5ce808/docs/architecture/diagrams/01_context/README.md),
[`02_containers`](https://github.com/spec-kitty/spec-kitty/blob/4eecb12414dac53432f4af21ba958d199d5ce808/docs/architecture/diagrams/02_containers/README.md))
still say `services/mission-status-api/` "does not exist yet". That is stale: the service
ships. The diagrams on this page follow the code.

## C4 level 2: containers

```mermaid
flowchart TB
  subgraph build["One Gradle build (after cutover)"]
    contracts["contracts/<br/>mission-status, mission-write<br/>(OpenAPI 3.1)"]
    msa["mission-status-api<br/>Spring Boot 4, Java 25, GraalVM native<br/>(query-service deployable)"]
    cmd["command-cli<br/>(write deployable, future)"]
    dm["domain-model<br/>(library)"]
    el["event-log<br/>(library)"]
  end
  msa --> el --> dm
  msa --> dm
  cmd -.-> el
  cmd -.-> dm
  contracts -. constrains .-> msa
  contracts -. constrains .-> cmd
```

| Container | What it is | Evidence |
|---|---|---|
| `mission-status-api` | The root Gradle project in core (`:mission-status-api` in `service/` after cutover): the `query-service` deployable. Spring Boot 4 (Spring MVC on virtual threads), Java 25, a GraalVM native binary for four targets plus a JVM jar. | README, "Package layout" and "Release and binaries"; core ADR 2026-10-01-2 Amendment 2026-10-03 (D-4) |
| `domain-model` | The Mission status types (Mission, work package, lane, status event, snapshot). No declared dependency, no `java.io` or `java.nio.file`. | README, "Package layout"; `DomainModelBoundaryTest` |
| `event-log` | The line codec, the event ordering and `StatusReducer`, and the ledger file mechanics (`LedgerLocks`, `TornTailRepair`). Depends on `domain-model` and the Jackson streaming parser only. | README, "Package layout"; `EventLogBoundaryTest` |
| contracts | `contracts/mission-status/openapi.yaml` (read) and `contracts/mission-write/openapi.yaml` (write), both `1.0.0-SNAPSHOT`. | [`contracts/mission-write/CHANGELOG.md`](https://github.com/spec-kitty/spec-kitty/blob/4eecb12414dac53432f4af21ba958d199d5ce808/contracts/mission-write/CHANGELOG.md); writers-and-readers page, "The read API contract" |
| `command-cli` | The write-service deployable named by #6117 decision 12. **Not built.** | core ADR 2026-10-22-1 D-1 |

The four module names are #6117 decision 12's: `domain-model`, `event-log`, `query-service`
and `command-cli` (core ADR 2026-10-22-1 D-1). The read service and the write service share
`domain-model`; the read service stays read-only and separate (D-8).

## Onion layering

```mermaid
flowchart LR
  adapters["adapters<br/>adapter.in.http · adapter.out.filesystem"] --> app["application<br/>application.query + ports"] --> domain["domain<br/>domain.model · domain.readmodel"]
```

Dependencies point inward only. The domain is pure, the application holds the query
services and their ports, and the adapters hold HTTP in and the file system out. The
read service's package layout:

- `domain.model` (the `domain-model` library), `domain.readmodel` and `domain.redaction`.
- `application.query` (`MissionStatusQueryService`, `ProjectionCache`, `stream`), with
  `application.query.port.in` and `application.query.port.out` holding interfaces only.
  `application.command` is reserved and empty.
- `adapter.in.http`, `adapter.out.filesystem` (with its `codec`), and `config`.

### The rules, and the tests that enforce them

[`ArchitectureRulesTest`](https://github.com/spec-kitty/spec-kitty/blob/4eecb12414dac53432f4af21ba958d199d5ce808/services/mission-status-api/src/test/java/org/speckitty/missionstatus/architecture/ArchitectureRulesTest.java)
runs the fourteen rules defined in
[`ArchitectureRules`](https://github.com/spec-kitty/spec-kitty/blob/4eecb12414dac53432f4af21ba958d199d5ce808/services/mission-status-api/src/test/java/org/speckitty/missionstatus/architecture/ArchitectureRules.java).
It also fails when the number of rules run differs from the number defined, and every rule
has a positive control in `ArchitectureControlsTest`. The rule texts, shortened:

1. The domain is free of frameworks, JSON, file APIs and concurrency, and creates no thread.
2. The application depends only on itself, the domain and the JDK, and creates no thread
   (the executor is injected).
3. `adapter.in.http` reaches, among project packages, only `application.query.port.in`, the
   domain and itself.
4. `adapter.out.filesystem` reaches only `application.query.port.out`, the domain and itself.
5. No adapter depends on an adapter of the other direction.
6. Nothing outside `config` depends on `config`.
7. File APIs are used only in `adapter.out.filesystem` (not its codec) and in `event-log`'s
   `adapter.out.ledger`.
8. JSON libraries and Spring are used only in adapters and `config`.
9. Contract DTO packages are used only in `adapter.in.http`.
10. No `ProcessBuilder` and no `Runtime.exec`.
11. The port-level conformance subset is source-neutral (a test-scope rule).
12. The codec is pure decoding: no file API, used only by `adapter.out.filesystem` and test
    support.
13. A class directly in a query port package is an interface.
14. Nothing in `application.query` depends on `application.command`.

The two libraries carry their own boundary tests, because they build on their own:

- [`DomainModelBoundaryTest`](https://github.com/spec-kitty/spec-kitty/blob/4eecb12414dac53432f4af21ba958d199d5ce808/services/mission-status-api/domain-model/src/test/java/org/speckitty/missionstatus/architecture/DomainModelBoundaryTest.java):
  `domain-model` depends only on itself and the rule 1b JDK packages, and all 18 types it
  held at extraction (#6192) are still checked.
- [`EventLogBoundaryTest`](https://github.com/spec-kitty/spec-kitty/blob/4eecb12414dac53432f4af21ba958d199d5ce808/services/mission-status-api/event-log/src/test/java/org/speckitty/missionstatus/architecture/EventLogBoundaryTest.java):
  the codec depends only on itself, `domain-model`, the Jackson streaming parser and the
  JDK; the reducer has no parser, codec or I/O; the ledger package uses the JDK alone; and
  the module holds those three packages and no other.

## Where it lives

| Today, in core at `4eecb124` | After cutover, in this repository |
|---|---|
| `services/mission-status-api/` (with `domain-model/` and `event-log/`) | `service/` |
| `contracts/mission-status/`, `contracts/mission-write/`, `_shared`, `tools`, `lint` | `contracts/` |
| CLI client shapes `src/specify_cli/status/write_service_protocol.py`, the EventBridge, the launcher | stay in core |

The import keeps git history (`git filter-repo` from a frozen core SHA) and runs only on
the operator's go-signal ([ADR-0002](../../adr/0002-events-domain-owns-its-services.md),
decision 2). The build and release line are in
[ADR-0003](../../adr/0003-gradle-build-and-service-release-line.md).
