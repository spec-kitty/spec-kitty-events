# Implementation Plan: command-cli, the runnable events write service and its native release

**Branch**: `issue-156-command-cli` (planning base and merge target: `issue-156-command-cli`; the PR then targets `main`)
**Date**: 2026-10-31
**Spec**: [spec.md](./spec.md)
**Input**: spec-kitty/spec-kitty-events#156, the operator rulings of 2026-10-31, and `docs/architecture/services/command-cli-research.md`

## Summary

Add a new Gradle module, `:command-cli` (`service/command-cli`). It is a Spring Boot 4 service that:
- builds as a jar and as a GraalVM native image;
- implements the launcher process contract (`contracts/mission-write/LAUNCHER.md`);
- implements the six operations of `contracts/mission-write/openapi.yaml` under `/api/v1`, with the contract's order of checks.

The three write operations end in a fail-closed refusal: nothing is ever acknowledged. The service CI native matrix, the release asset script and the workflow tests are extended so that a `service-v*` release carries `command-cli-<ver>-<target>[.exe]` and `command-cli-<ver>.jar`. The contract loses `x-provisional` on `/shutdown` and `HealthStatus`, and the docs describe the built service and how core binds it.

## Technical Context

**Language/Version**: Java 25 (Temurin locally; GraalVM CE 25.0.2 for native in CI)
**Primary Dependencies**:
- Spring Boot 4.1.1: `spring-boot-starter-web` with embedded Tomcat, virtual threads and AOT;
- `org.graalvm.buildtools.native` 1.1.8;
- openapi-generator (interfaces only, from `contracts/mission-write`);
- Jackson 3;
- tests: JUnit 6, AssertJ, ArchUnit 1.4.2 and networknt json-schema-validator.

All of these are already in `gradle/libs.versions.toml` and `gradle/verification-metadata.xml`. No new dependency is planned.
**Storage**: files only. Under `<gcd>/spec-kitty-write-service/`: `service.lock`, `endpoint.json`, and `service.log` (stdout/stderr, redirected by the launcher). No ledger is written.
**Testing**: JUnit and ArchUnit on the JVM (`./gradlew :command-cli:check`). Process tests start the jar as a child process. Contract conformance validates responses against `contracts/mission-write/schemas`. `@Tag("native")` tests and a native smoke run in the CI matrix.
**Target Platform**: linux-x64, linux-arm64, macos-arm64, windows-x64 (native), and any OS with Java 25 (jar).
**Project Type**: a single Gradle module inside the existing multi-project build.
**Performance Goals**: see the spec. Native ready in ≤ 2 s, jar ready in ≤ 10 s, exit ≤ 10 s after `/shutdown`, handshake and health at p95 ≤ 50 ms.
**Constraints**:
- no build or runtime dependency on core;
- loopback only;
- the token is never logged;
- strict dependency verification;
- SHA-pinned actions in changed workflows.
**Scale/Scope**: one process per git common dir, with a handful of requests per CLI command.

## Charter Check

| Charter rule | How this plan meets it |
|---|---|
| Java 25, Gradle Kotlin DSL, GraalVM native plus jar (Services) | New module on the `events.service` convention, with the same plugins as `:mission-status-api` |
| JUnit and ArchUnit; onion and boundary rules mandatory for service modules | `ArchitectureRules` for `org.speckitty.commandcli` (domain ← application ← adapters/config), plus a boundary test |
| Contract conformance tests against `contracts/` | Response schemas are validated against `contracts/mission-write/schemas/*.yaml`, and refusal codes against `WriteRefusalCode.yaml` |
| Native smoke tests for release builds | `nativeSmoke` for `command-cli` in the `service.yml` native matrix |
| Every PR: conformance gate, `./gradlew verifyAll`, strict verification, SHA-pinned actions | `verifyAll` picks up `:command-cli:check` automatically. The workflow edits keep SHA pins. No new dependency |
| No dependency on core | None. Contract parity with core's `write_service_protocol.py` is stated in the docs, not imported |
| Compatibility review for contract changes | Only provisional markers and description text change, on a `1.0.0-SNAPSHOT` contract. Recorded in the `CHANGELOG.md` Changed section |
| Keep documentation synchronized | ADR-0002/0003 wording, `docs/architecture/services/*`, the guides, and a new `service/command-cli/README.md` |

Result: no violations.

## Project Structure

### Documentation (this feature)

```
kitty-specs/command-cli-write-service-01M694HJ/
├── spec.md
├── plan.md            # this file
├── research.md        # design decisions D-1 … D-12
├── data-model.md      # endpoint file, service state, refusals
├── quickstart.md      # run it by hand and against the launcher sequence
├── contracts/
│   └── endpoint-file.md  # endpoint.json shape as core's reader enforces it
└── checklists/requirements.md
```

### Source Code (repository root)

```
settings.gradle.kts                       # + include(":command-cli"), projectDir service/command-cli
service/command-cli/
├── build.gradle.kts                      # events.service + boot + openapi(mission-write) + graalvm native
├── README.md
└── src/
    ├── main/java/org/speckitty/commandcli/
    │   ├── domain/service/               # ServiceState, InstanceId, RepositoryId, ProtocolSupport, RefusalCode (no framework)
    │   ├── application/lifecycle/        # ServiceLifecycle (ready → draining → stopped), IdleWatch, WriteGate
    │   │   └── port/{in,out}/            # Handshake/Health/Shutdown/Write use cases; EndpointPublisher, Exit, Clock
    │   ├── adapter/in/http/              # controllers (generated interfaces), HostOriginFilter (copied), BearerTokenFilter,
    │   │                                 #   ProtocolHeaderFilter, BodySizeFilter, ProblemAdvice, RefusalBody
    │   ├── adapter/out/filesystem/       # StateDirectory, ServiceLock, EndpointFile, RepositoryIdentity
    │   └── config/                       # CommandCliMain (argv → lock → Spring), ServeOptions, BindGuard, ConfigPin, wiring, hints
    ├── main/resources/application.properties
    └── test/java/org/speckitty/commandcli/…  # unit, web-slice, process (jar child), architecture, conformance, native-tagged
.github/workflows/service.yml             # detect paths, native matrix steps for :command-cli, artifact naming
scripts/release/service-release-assets.sh # + command-cli assets
tests/test_service_workflows.py           # + command-cli expectations
contracts/mission-write/{paths/shutdown.yaml,schemas/HealthStatus.yaml,LAUNCHER.md,CHANGELOG.md}
docs/…                                    # architecture/services, guides, ADR-0002/0003 wording
```

**Structure Decision**: one new module that mirrors `:mission-status-api`'s onion layout and build. It does not depend on `:event-log` or `:domain-model` yet; #160 adds that. The read service's guard is copied with its tests, as ruled, not extracted.

## Implementation Concern Map

| Concern | Spec rows | Notes |
|---|---|---|
| IC-1: Module skeleton and build (jar, AOT, native config, ArchUnit, JaCoCo) | FR-017, C-002, C-003, NFR-005 | The first red test is ArchUnit plus a boot smoke |
| IC-2: `serve` command line, state directory, single-instance lock | FR-001, FR-002 | The lock is taken before Spring starts, so a second instance exits 0 fast |
| IC-3: Endpoint file (atomic publish, owner-only, own-instance removal) and token | FR-003, FR-013 | Matches core `read_endpoint_file` exactly (contracts/endpoint-file.md) |
| IC-4: HTTP edge: bind guard, Host/Origin guard (copied), bearer, protocol header, size cap, problem bodies | FR-004–FR-007, FR-015 | Filter order equals the contract's order of checks |
| IC-5: Handshake, health, and fail-closed writes with shape validation | FR-008–FR-010 | Shape validation uses the generated models plus a strict JSON check |
| IC-6: Drain, shutdown, idle exit, signal exit | FR-011–FR-013, NFR-003 | One `ServiceLifecycle` state machine |
| IC-7: Logging hygiene | FR-014, SC-004 | stdout only; a test scans captured logs for the token |
| IC-8: Contract settle (provisional off, HealthStatus text, LAUNCHER, CHANGELOG) | FR-016 | Run the contract tools (`provisional_check`, `breaking_check`, `leak_scan`, the bundle) |
| IC-9: CI native matrix, release assets, workflow tests | FR-018, FR-019, NFR-001/002 | The native build is verified only in CI (no local native-image) |
| IC-10: Docs and the core binding interface | FR-020 | Cite `bind_event_bridge` and the pin prefix |

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|---|---|---|
| Copying the guard instead of sharing it | Operator ruling 2 | Extracting a shared library touches the read service, which is out of scope |

## Parallel Work Analysis

### Dependency Graph

```
IC-1 → IC-2 → IC-3 → IC-6
IC-1 → IC-4 → IC-5 → IC-6
IC-6 → IC-9 (native smoke needs serve + shutdown)
IC-8, IC-10 independent (docs/contract), IC-7 rides on IC-4
```

### Work Distribution

One implementer works sequentially. The work is small enough that parallel lanes would cost more in coordination than they save. IC-8 and IC-10 can run in a separate lane.

### Coordination Points

- IC-9 edits `service.yml`, which is shared with the read service. Keep `:mission-status-api` jobs byte-identical in behaviour.
- The sibling rc.1 release-prep session may touch `service-release.yml` and `CHANGELOG.md`. Whoever lands second rebases.
