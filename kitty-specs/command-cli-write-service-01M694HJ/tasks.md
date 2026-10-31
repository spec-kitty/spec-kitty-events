# Tasks: command-cli, the runnable events write service and its native release

**Mission**: command-cli-write-service-01M694HJ · **Branch**: `issue-156-command-cli` (planning base and merge target) · **Spec**: [spec.md](./spec.md) · **Plan**: [plan.md](./plan.md)

Implementation follows ATDD, red first. Each WP writes its acceptance tests before the code and runs them red.

## Subtask Index

| ID | Description | WP | Parallel |
|---|---|---|---|
| T001 | Register `:command-cli` in `settings.gradle.kts` at `service/command-cli` | WP01 | |
| T002 | `service/command-cli/build.gradle.kts`: events.service, Boot, openapi (mission-write interfaces), graalvm, jacoco gates | WP01 | |
| T003 | `application.properties` with loopback, logging hygiene and pinned config | WP01 | |
| T004 | `CommandCliApplication` and `ServeOptions` (argv parsing, usage exit 2) | WP01 | |
| T005 | ArchUnit onion rules for `org.speckitty.commandcli` | WP01 | |
| T006 | `ServeOptions` tests (valid, missing, relative, non-dir, bad idle timeout) | WP01 | |
| T007 | Domain values: `InstanceId` (ULID), `RepositoryId`, `ProtocolSupport`, `RefusalCode`, `ServiceState` | WP02 | [P] |
| T008 | `StateDirectory`: create 0700, refuse a symlink, tighten loose modes | WP02 | |
| T009 | `ServiceLock`: `FileChannel.tryLock`, single open, exit-0 path | WP02 | |
| T010 | `EndpointFile`: temp, fsync, atomic rename, owner-only; conditional removal | WP02 | |
| T011 | `WriteToken`: 32 bytes, base64url, constant-time match | WP02 | [P] |
| T012 | Tests mirroring core `read_endpoint_file` rules (a Python peer check in a pytest) | WP02 | |
| T013 | Copy `BindGuard` and `ConfigPin` with their tests | WP03 | |
| T014 | Copy `HostOriginFilter` and its tests (code `REQUEST_REFUSED`, 403) | WP03 | |
| T015 | `BearerTokenFilter` (401, `WWW-Authenticate: Bearer`) | WP03 | |
| T016 | `ProtocolHeaderFilter` (400 `PROTOCOL_VERSION_UNSUPPORTED`; exempt handshake and health) | WP03 | |
| T017 | `BodySizeFilter` (413 `REQUEST_TOO_LARGE` at 1 MiB) | WP03 | |
| T018 | `RefusalBody` and the error controller: problem+json, order-of-checks test matrix | WP03 | |
| T019 | `HandshakeController` and `HealthController` from the generated interfaces | WP04 | |
| T020 | Write controllers: shape validation (`REQUEST_MALFORMED`) and the fail-closed refusal | WP04 | |
| T021 | `ServiceLifecycle` (READY, DRAINING, STOPPED) and `SERVICE_DRAINING` on writes | WP04 | |
| T022 | Contract conformance: response bodies against the `contracts/mission-write/schemas` | WP04 | |
| T023 | "Nothing written" assertion: repository tree unchanged after every write case | WP04 | |
| T024 | Startup sequence: lock, then Spring on port 0, then publish endpoint (the `WebServerInitializedEvent` listener) | WP05 | |
| T025 | `/shutdown`: 202, drain, remove endpoint, close, exit 0 | WP05 | |
| T026 | `IdleWatch`: default 1800 s, measured from the last request | WP05 | |
| T027 | Shutdown hook removes the own-instance endpoint on SIGTERM | WP05 | |
| T028 | Process tests: the jar child drives the exact `LAUNCHER.md` sequence, second instance exits 0, idle exit, SIGTERM | WP05 | |
| T029 | Log hygiene test: captured stdout never contains the token | WP05 | |
| T030 | Native: `@Tag("native")` set, `nativeSmoke` against the binary, runtime hints | WP06 | |
| T031 | `service.yml`: detect paths, gradle-job jar artifact, native matrix steps and artifacts for `command-cli` | WP06 | |
| T032 | `scripts/release/service-release-assets.sh`: the `command-cli` asset family | WP06 | |
| T033 | `tests/test_service_workflows.py`: pin the new expectations | WP06 | |
| T034 | Make and verify script: run `:command-cli:check` within `make test-gradle` and `verifyAll` | WP06 | |
| T035 | Contract settle: `/shutdown` and `HealthStatus` provisional off, `SERVICE_DRAINING` text, `LAUNCHER.md`, `CHANGELOG.md` | WP07 | [P] |
| T036 | Run the contract tools (provisional, breaking, example, leak, bundle) and the contract tests | WP07 | |
| T037 | `service/command-cli/README.md`: run, configuration, the core binding interface | WP07 | [P] |
| T038 | Docs: `docs/architecture/services/*`, the guides, ADR-0002 and ADR-0003 "future" wording, the services README | WP07 | |
| T039 | The repo `CHANGELOG.md` entry (service line) | WP07 | |

## WP01 – Module skeleton, build and command line

Prompt: [tasks/WP01-module-skeleton.md](./tasks/WP01-module-skeleton.md). Priority P1. Dependencies: none.

T001 Register module (WP01)
T002 Module build (WP01)
T003 Application properties (WP01)
T004 Application and ServeOptions (WP01)
T005 ArchUnit rules (WP01)
T006 ServeOptions tests (WP01)

## WP02 – On-disk process surface: state dir, lock, endpoint file, token, identity

Prompt: [tasks/WP02-process-surface-on-disk.md](./tasks/WP02-process-surface-on-disk.md). Priority P1. Depends on WP01.

T007 Domain values (WP02)
T008 StateDirectory (WP02)
T009 ServiceLock (WP02)
T010 EndpointFile (WP02)
T011 WriteToken (WP02)
T012 Endpoint reader parity tests (WP02)

## WP03 – HTTP edge: bind, guard, token, protocol, size, problems

Prompt: [tasks/WP03-http-edge.md](./tasks/WP03-http-edge.md). Priority P1. Depends on WP01.

T013 BindGuard and ConfigPin (WP03)
T014 HostOriginFilter (WP03)
T015 BearerTokenFilter (WP03)
T016 ProtocolHeaderFilter (WP03)
T017 BodySizeFilter (WP03)
T018 RefusalBody and the order matrix (WP03)

## WP04 – Operations: handshake, health, fail-closed writes

Prompt: [tasks/WP04-operations.md](./tasks/WP04-operations.md). Priority P1. Depends on WP02 and WP03.

T019 Handshake and health (WP04)
T020 Write controllers (WP04)
T021 ServiceLifecycle (WP04)
T022 Contract conformance (WP04)
T023 Nothing-written assertion (WP04)

## WP05 – Lifecycle: start, shutdown, idle and signal exit, process tests

Prompt: [tasks/WP05-lifecycle-and-process.md](./tasks/WP05-lifecycle-and-process.md). Priority P1. Depends on WP04.

T024 Startup sequence (WP05)
T025 Shutdown (WP05)
T026 IdleWatch (WP05)
T027 Shutdown hook (WP05)
T028 Process tests (WP05)
T029 Log hygiene (WP05)

## WP06 – Native build, CI matrix and release assets

Prompt: [tasks/WP06-native-ci-release.md](./tasks/WP06-native-ci-release.md). Priority P1. Depends on WP05.

T030 Native tests and smoke (WP06)
T031 service.yml (WP06)
T032 Release asset script (WP06)
T033 Workflow tests (WP06)
T034 Make and verify script (WP06)

## WP07 – Contract settle and documentation

Prompt: [tasks/WP07-contract-and-docs.md](./tasks/WP07-contract-and-docs.md). Priority P2. Depends on WP05.

T035 Contract settle (WP07)
T036 Contract tools (WP07)
T037 Module README (WP07)
T038 Docs (WP07)
T039 Changelog (WP07)
