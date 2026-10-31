# Feature Specification: command-cli, the runnable events write service and its native release

**Feature Branch**: `issue-156-command-cli`
**Created**: 2026-10-31
**Status**: Draft
**Input**: spec-kitty/spec-kitty-events#156, with the operator rulings of 2026-10-31 relayed by the orchestrator. Research: `docs/architecture/services/command-cli-research.md`.

## Intent Summary

- **Primary actor:** core's CLI launcher (`spec-kitty` on a user machine). It starts the write service for a repository, waits until the service is ready, talks to it over loopback, and stops it.
- **Trigger:** a CLI command needs the write service of a repository, and no ready service runs for that repository's git common dir.
- **Outcome:**
  - A pinned, digest-checked binary of `command-cli` starts and publishes where it can be reached.
  - It proves which repository it serves and answers healthy.
  - It drains and exits on request or when idle.
  - It ships for linux-x64, linux-arm64, macos-arm64 and windows-x64, and as a jar on Java 25, on the `service-v*` line.
- **Invariant:** the service never acknowledges a write it did not durably persist. In this mission every write operation refuses fail-closed and writes nothing. The canonical writer (#160) and the lane authority (#161) come later.
- **Boundary:** binding core's EventBridge (#6195), the CLI-side launcher, and the core client's path-prefix fix (spec-kitty/spec-kitty#6910) are core's work and out of scope.

Operator rulings (2026-10-31) that govern this spec:

1. This mission delivers the runnable service and its release only. The write core moves to #160 and #161.
2. The transport is loopback HTTP with a bearer token, using the same stack and native build as the read service. The read service's request guard is copied together with its tests.
3. There is one service per git common dir, and the idle timeout defaults to 1800 s.
4. The contract stays at mission-write `1.0.0-SNAPSHOT`. `/shutdown` and `draining` stop being provisional once they are built. No tag is cut.
5. The `/api/v1` path prefix of the contract wins.
6. A draining service refuses with `SERVICE_DRAINING` (503). The `HealthStatus` text is corrected to match.
7. A torn tail is refused, not repaired. This applies to #160 and is recorded here for continuity only.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - The launcher starts one service per repository and finds it ready (Priority: P1)

The CLI launcher runs `command-cli serve --git-common-dir <abs>` for a repository. The service takes the single-instance lock and publishes its endpoint file. Within the launcher's start budget it answers the handshake with the repository's identity and reports `ready`.

**Why this priority**: Without a startable, discoverable service, core cannot pin or bind anything. This is the core of #156.

**Independent Test**: Start the built jar against a temporary git common dir. Wait for `endpoint.json`. Call `/api/v1/handshake` and `/api/v1/health` with the token from the file. Check every readiness rule of `LAUNCHER.md`.

**Acceptance Scenarios**:

1. **Given** no service runs for a git common dir, **When** the launcher runs `serve --git-common-dir <abs>`, **Then**:
   - `<gcd>/spec-kitty-write-service/endpoint.json` appears, written by rename, owner-only;
   - it names `127.0.0.1`, a port, a token, an instance ULID, the pid, the protocol majors and the start time;
   - `GET /api/v1/handshake` with the bearer token returns `repositoryId` = SHA-256 of the real path of the git common dir, the same `instanceId`, and `protocolVersions` `[1]`;
   - `GET /api/v1/health` returns `ready`.
2. **Given** a service already holds `service.lock` for that git common dir, **When** a second `serve` starts for it, **Then** the second process exits 0 without touching the endpoint file.
3. **Given** a running service, **When** it logs, **Then** the log goes to `<gcd>/spec-kitty-write-service/service.log` and never contains the token.

---

### User Story 2 - Only the launcher's own loopback client is answered (Priority: P1)

Any local process can connect to a loopback port. The service answers only requests that pass the Host/Origin guard and carry the bearer token. It checks in the contract's order.

**Why this priority**: The token and the guard are the whole trust boundary of a loopback service (core ADR 2026-10-27-1).

**Independent Test**: Send requests with a foreign Host, a cross-site `Sec-Fetch-Site`, a wrong token, no protocol header and an oversized body. Assert each response's status and code, and that no body echoes the token or a host path.

**Acceptance Scenarios**:

1. **Given** a running service, **When** a request carries `Host` other than `127.0.0.1:<port>` or `localhost:<port>`, a foreign `Origin`, or `Sec-Fetch-Site: cross-site`, **Then** it gets `403 REQUEST_REFUSED`, even with a valid token.
2. **Given** a guarded request, **When** the bearer token is missing or wrong, **Then** it gets `401 WRITE_TOKEN_INVALID` with `WWW-Authenticate: Bearer`.
3. **Given** an authorized write or shutdown request, **When** `Spec-Kitty-Write-Protocol` is missing, not an integer, or not `1`, **Then** it gets `400 PROTOCOL_VERSION_UNSUPPORTED`.
4. **Given** an authorized write request with protocol `1`, **When** the body is over 1 MiB, **Then** it gets `413 REQUEST_TOO_LARGE`.

---

### User Story 3 - Writes refuse fail-closed until the write core lands (Priority: P1)

Each write operation (transition, batch, annotation) is reachable and passes every protocol check. It then refuses with a contract refusal and writes nothing.

**Why this priority**: The invariant is "no acknowledgement without durable persistence". A released binary must never pretend to write.

**Independent Test**: Post a valid unit from the contract's examples. Assert the refusal and the code. Assert that no file under the repository changed.

**Acceptance Scenarios**:

1. **Given** a ready service, **When** a valid `TransitionUnit`, `TransitionBatch` or `AnnotationRequest` arrives, **Then**:
   - it is refused with `500 STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED` as an `application/problem+json` `WriteRefusal`;
   - the fixed detail says that the write core is not built;
   - nothing in the repository is written.
2. **Given** a ready service, **When** a write body does not have the contract's shape, **Then** it gets `400 REQUEST_MALFORMED` before the fail-closed refusal.

---

### User Story 4 - The launcher stops the service, or it stops itself when idle (Priority: P2)

The launcher posts `/api/v1/shutdown`. The service reports `draining`, refuses new writes with `SERVICE_DRAINING`, removes its endpoint file (only if the file still names its own instance), answers 202 and exits. With no request for the idle timeout (default 1800 s), it drains and exits the same way.

**Why this priority**: Without a clean stop, the launcher has to fall back to signals, and a stale endpoint file can mislead it.

**Independent Test**: Start the service with a short `--idle-timeout`. Assert it exits and removes its endpoint file. Then start it again, post `/shutdown`, and assert a 202, then exit, then the file is gone.

**Acceptance Scenarios**:

1. **Given** a ready service, **When** `POST /api/v1/shutdown` arrives with the token and protocol header, **Then**:
   - it answers 202;
   - health reads `draining` until exit;
   - a write in that window gets `503 SERVICE_DRAINING`;
   - the endpoint file is removed;
   - the process exits 0 within 10 s.
2. **Given** a ready service started with `--idle-timeout 2`, **When** no request arrives for 2 s, **Then** it drains and exits 0 and removes its endpoint file.
3. **Given** another instance has replaced the endpoint file, **When** the old instance exits, **Then** it leaves the newer file in place.
4. **Given** the service receives SIGTERM (TerminateProcess on Windows), **When** it can run its exit hook, **Then** it removes its own endpoint file.

---

### User Story 5 - Core can pin native binaries and a jar from a `service-v*` release (Priority: P1)

A `service-v*` release carries `command-cli-<ver>-<target>[.exe]` for the four targets and `command-cli-<ver>.jar`. Each asset is listed in `SHA256SUMS` with a `.sha256` and provenance, so `spec-kitty service vendor` can write a pin with the `command-cli` prefix.

**Why this priority**: Core's resolver (pin → digest → native, then jar) can launch only what is released under that exact naming.

**Independent Test**: In CI, the native matrix builds and runs native tests and a native smoke of `command-cli` on all four targets. The release asset script produces the `command-cli` names. The workflow tests pin the matrix and the names.

**Acceptance Scenarios**:

1. **Given** a `service-v*` release run, **When** assets are assembled, **Then** they include `command-cli-<ver>-linux-x64`, `-linux-arm64`, `-macos-arm64`, `-windows-x64.exe` and `command-cli-<ver>.jar`, each listed in `SHA256SUMS`.
2. **Given** a native build, **When** the native smoke runs, **Then** it starts the binary with `serve`, passes the readiness check and shuts it down.

### Edge Cases

- `--git-common-dir` is missing, relative, not a directory, or not a git common dir. The service exits non-zero with a message that names no other path, and publishes nothing.
- The state directory exists with loose permissions. The service tightens it to 0700, or refuses to publish.
- The endpoint file's directory is a symlink. The service refuses and does not follow it.
- The port is taken. The OS picks the port (port 0), so a collision cannot occur.
- A stale `endpoint.json` from a crashed instance is overwritten by rename once the lock is held.
- A second `/shutdown` while draining is answered without restarting the drain.
- `Host: [::1]` is refused, because the service never listens on IPv6.
- A token comparison does not leak timing.

## Domain Language

| Term | Meaning | Avoid |
|---|---|---|
| write service | The resident `command-cli` process for one repository | daemon, sidecar |
| git common dir (gcd) | The shared `.git` dir of a clone and all its worktrees; the unit of one service | repo root, worktree |
| launcher | Core's CLI component that resolves, starts, checks and stops the service | client (that is the HTTP side) |
| endpoint file | `<gcd>/spec-kitty-write-service/endpoint.json` | port file |
| fail-closed refusal | A contract refusal that writes nothing | no-op, stub success |

## Requirements *(mandatory)*

### Functional Requirements

| ID | Requirement | Status | Delivery | No-op passable? |
|---|---|---|---|---|
| FR-001 | `command-cli serve --git-common-dir <abs> [--idle-timeout <s>]` starts the service for that git common dir. Any other command line exits non-zero with usage. | Proposed | [build] | no |
| FR-002 | The service takes an exclusive `<gcd>/spec-kitty-write-service/service.lock`. If the lock is held, it exits 0 and publishes nothing. | Proposed | [build] | no |
| FR-003 | The service publishes `endpoint.json` by temp file plus rename, with mode 0600 in a 0700 directory (owner-only ACL on Windows). The fields are `schema_version` 1, `host` `127.0.0.1`, `port`, `token` (32 random bytes, base64url, no padding, new at every start), `instance_id` (ULID), `pid`, `protocol_versions` `[1]` and `started_at` (ISO-8601 with offset). | Proposed | [build] | no |
| FR-004 | The service binds `127.0.0.1` only, on an OS-picked port, and refuses any other bind address. | Proposed | [build] | no |
| FR-005 | Every request passes the read service's Host/Origin/`Sec-Fetch-Site` guard (copied with its tests). A refusal is `403 REQUEST_REFUSED`. | Proposed | [build] | no |
| FR-006 | Every operation requires the bearer token from the endpoint file, compared in constant time. A missing or wrong token is `401 WRITE_TOKEN_INVALID` with `WWW-Authenticate: Bearer`. The token is never logged or echoed. | Proposed | [build] | no |
| FR-007 | Every operation except handshake and health requires `Spec-Kitty-Write-Protocol: 1`. Otherwise the answer is `400 PROTOCOL_VERSION_UNSUPPORTED`, with the supported majors in the detail. | Proposed | [build] | no |
| FR-008 | `GET /api/v1/handshake` returns `protocolVersions` `[1]`, `serviceVersion`, `instanceId` and `repositoryId` = lowercase hex SHA-256 of the real path of the git common dir. | Proposed | [build] | no |
| FR-009 | `GET /api/v1/health` returns `{status: ready, instanceId}`. While draining it returns `draining`. | Proposed | [build] | no |
| FR-010 | The three write operations check the size cap (1 MiB → `413 REQUEST_TOO_LARGE`), then the body shape (→ `400 REQUEST_MALFORMED`). They then refuse fail-closed with `500 STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED` and write nothing. | Proposed | [build] | no |
| FR-011 | `POST /api/v1/shutdown` answers 202 and drains: health reads `draining`, new writes get `503 SERVICE_DRAINING` (no `Retry-After`). The endpoint file is removed if it still names this instance, and the process exits 0. | Proposed | [build] | no |
| FR-012 | With no request for the idle timeout (default 1800 s), the service drains and exits as in FR-011. | Proposed | [build] | no |
| FR-013 | On SIGTERM or TerminateProcess, the service removes its own endpoint file where its exit hook runs. | Proposed | [build] | no |
| FR-014 | The service writes its log to `<gcd>/spec-kitty-write-service/service.log`. No log line carries the token, a request body, or a caller-controlled header. | Proposed | [build] | no |
| FR-015 | Every refusal is `application/problem+json` matching the contract's `WriteRefusal`: `code` plus the status that code fixes, and no host path. | Proposed | [build] | no |
| FR-016 | The contract `contracts/mission-write` stays `1.0.0-SNAPSHOT`. `/shutdown` and `HealthStatus` (`draining`) lose `x-provisional`. The `HealthStatus` text names `SERVICE_DRAINING`. `LAUNCHER.md` "Stop" is no longer provisional. `CHANGELOG.md` records the change. | Proposed | [build] | no |
| FR-017 | The build produces `command-cli-<ver>.jar` that runs on Java 25, and a GraalVM native image of the same code. | Proposed | [build] | no |
| FR-018 | The service CI native matrix builds, native-tests and native-smokes `command-cli` on linux-x64, linux-arm64, macos-arm64 and windows-x64, and uploads `command-cli-<ver>-<target>[.exe]` with its digest. | Proposed | [build] | no |
| FR-019 | The `service-v*` release assembles and publishes the `command-cli` native assets and jar, each in `SHA256SUMS` with `.sha256` and provenance, beside the read service's assets. | Proposed | [build] | no |
| FR-020 | Docs (`docs/architecture/services/*`, `service/README.md` or a `command-cli` README, ADR-0002/0003 "future" wording, the guides) describe the built service and how core binds it: `bind_event_bridge`, the `command-cli` pin prefix, and the launcher contract. | Proposed | [build] | no |

### Non-Functional Requirements

| ID | Requirement | Status |
|---|---|---|
| NFR-001 | The native binary reaches ready (endpoint published, handshake and health answered) within 2 s of start on a CI runner. This is well inside the launcher's 15 s budget. | Proposed |
| NFR-002 | The jar reaches ready within 10 s on Java 25 on a CI runner. | Proposed |
| NFR-003 | After `/shutdown` the process exits within 10 s, the launcher's wait before SIGTERM. | Proposed |
| NFR-004 | Handshake and health answer within 50 ms at p95 on a ready native service, under 100 sequential requests. | Proposed |
| NFR-005 | Domain and application code is covered at ≥90 % lines, and domain at ≥80 % branches, as JaCoCo gates as for the read service. | Proposed |

### Constraints

| ID | Constraint | Status |
|---|---|---|
| C-001 | No build or runtime dependency on core (charter: Services). Test peers may use only the pinned `service/core-pin.json`. | Active |
| C-002 | The same stack as the read service: Java 25, Spring Boot 4 with AOT and GraalVM native, Gradle Kotlin DSL, strict dependency verification, and action pins by commit SHA in changed workflows. | Active |
| C-003 | ArchUnit onion and boundary rules apply to the new module. | Active |
| C-004 | No Python fallback writer, and no write is acknowledged (core ADR 2026-10-22-1 D-5). | Active |
| C-005 | No tag is cut or pushed, and no contract release happens. | Active |
| C-006 | Core-side binding (#6195), the CLI launcher (#6676) and the client prefix fix (spec-kitty/spec-kitty#6910) are out of scope. | Active |
| C-007 | The canonical writer (#160) and the lane authority (#161) are out of scope. | Active |

## Success Criteria *(mandatory)*

- SC-001: Core's launcher contract can start, verify readiness of and stop a `command-cli` built from this repo with no change on the service side. A test drives the exact `LAUNCHER.md` sequence against the jar. — [build] · no-op passable: no
- SC-002: A `service-v*` release run yields 5 `command-cli` assets (4 native, 1 jar), all digest-listed. — [build] · no-op passable: no
- SC-003: 100 % of write requests in the test corpus are refused, and no repository byte changes. — [build] · no-op passable: no
- SC-004: 0 occurrences of the token in logs or response bodies across the test suite. — [build] · no-op passable: no

## Key Entities

- **Endpoint file**: `schema_version`, `host`, `port`, `token`, `instance_id`, `pid`, `protocol_versions`, `started_at`.
- **Handshake**, **Health**, **WriteRefusal**: the shapes in `contracts/mission-write/schemas/`.
- **Service state dir**: `<gcd>/spec-kitty-write-service/` with `service.lock`, `endpoint.json` and `service.log`.

## Assumptions

- The fail-closed write refusal reuses `STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED` (500, "the unit is not acknowledged"). Adding a contract code would break the 20+9 parity with core's `WriteRefusalCode`. #160 replaces the refusal with real persistence.
- `serviceVersion` is the build's release version, or `0.0.0-dev+<sha7>` in development, matching the read service's naming.
- GraalVM `native-image` is not available locally, so native behaviour is verified in CI only.
