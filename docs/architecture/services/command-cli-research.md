---
title: command-cli research note
description: 'Research for #156: what the command-cli write service must implement, what core binds, what this repo already has, and the open decisions.'
doc_status: draft
updated: 2026-10-31T00:00:00Z
type: Architecture
kind: Explanation
---

# command-cli research note (#156)

Read-only research done before the `command-cli` mission. The local paths are in this repository. The core paths are in `spec-kitty/spec-kitty` at `d75e0bc5fe`.
Core issues #6194, #6195 and #6117 could not be read from this session. GitHub API
access to core was not granted. Core's code, ADRs and plan were read from a clone instead.

## 1. What core expects (the binding interface)

- **Port.** The binder is `bind_event_bridge(*, emitter_factory, transition_port)`, at core
  `src/runtime/next/_internal_runtime/events.py:308`. It binds both halves or neither.
  - The write half is `WorkPackageTransitionPort.record_work_package_transition(transition) -> None`
    (core `src/runtime/next/event_bridge.py`). It is synchronous: it persists one unit or raises.
  - `WorkPackageTransition` carries `write_location`, `rows` (the transition first, then its
    annotations) and an opaque `write_contract`.
  - The emitter half, `RuntimeEventEmitter` (events.py:73-95), has no operation in protocol major 1
    (core ADR 2026-10-27-1 D-3). The client can satisfy it with a no-op.
  - Plan §4 step 7 (core `docs/plans/events-domain-move/plan.md:145`): "`command-cli` write
    service released → core bumps the write-service pin → #6195 binds `bind_event_bridge` to the
    client". That binding is out of scope here.
- **Wire shapes.** Core `src/specify_cli/status/write_service_protocol.py`:
  - `transition_unit_from` (line 300) builds the request;
  - `TransitionBatch` takes at most 64 units;
  - `SUPPORTED_PROTOCOL_VERSIONS = (1,)`;
  - `repository_id(gcd) = sha256(gcd.resolve().as_posix())`;
  - the `endpoint.json` reader is at lines 611-649.
- **Pin and resolution.**
  - Pin model: core `src/kernel/service_pin.py`. The write-service asset prefix is
    `command-cli` (`DEFAULT_ASSET_PREFIXES`, lines 61-70). Targets: `linux-x64`, `linux-arm64`,
    `macos-arm64`, `windows-x64` and `jvm`.
  - Asset names: `command-cli-<ver>-<target>[.exe]` and `command-cli-<ver>.jar`.
  - Host target selection: core `src/kernel/service_hosts.py:24-58`. A pinned native build is
    used if one exists for the host, otherwise the jar under Java 25 (`services/host.py`,
    `find_java(25)`).
  - Resolution order (core `src/specify_cli/services/resolution.py:1-35`):
    1. env override;
    2. entry point;
    3. the cached native binary, fetched on first use and digest-checked;
    4. the jar;
    5. a refusal.
  - `verify_before_exec` re-hashes the binary just before spawn.
- **Launch.** Core `src/specify_cli/services/policies.py:44-58` and `launcher.py`:
  - Command: `<argv_prefix> serve --git-common-dir <abs> [--idle-timeout <s>]`, run detached.
  - stdin is DEVNULL. stdout and stderr are appended to `<gcd>/spec-kitty-write-service/service.log`.
  - For the jar, `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS` and `_JAVA_OPTIONS` are stripped.
  - The CLI passes no port.
  - Readiness: a valid `endpoint.json`, then `GET /handshake` with the bearer token (instance,
    `repositoryId` and the protocol majors must intersect), then `GET /health` reads `ready`.
    The launcher allows 15 s and polls every 50 ms, with a 2 s client timeout and a 64 KiB body cap.
  - Stop: `POST /shutdown` with the protocol header. After 10 s, `SIGTERM` or `TerminateProcess`,
    sent only once the process is proven to be the published instance.
- **Locks.**
  - The per-Mission status lock is `<gcd>/spec-kitty-locks/<feature_dir.name>.status.lock`
    (core `status/locking.py:179-191`).
  - Until the Python writers are removed, the service must take the same files with `flock(2)`
    semantics (core ADR 2026-10-22-1 D-4).
- **Encoding.**
  - Status rows: `json.dumps(sanitize_event_for_log(row), sort_keys=True)` (core
    `status/store.py:293-300`; sanitizer `events/sanitizer.py:126`). The service never writes
    the bytes as it received them (ADR 2026-10-22-1 D-7).

## 2. What the contract here already fixes

- `contracts/mission-write/openapi.yaml` is `1.0.0-SNAPSHOT`, protocol major 1.
  - Six operations, bearer auth on all of them.
  - The `Spec-Kitty-Write-Protocol` header is required on all but `/handshake` and `/health`.
  - Checks run in this order: 403 guard, 401 token, 400 protocol, 413 size, 400 shape, then the
    domain (404, 409, 422).
- `contracts/mission-write/LAUNCHER.md` (accepted 2026-10-29) sets the start command, the
  single-instance lock (exit 0 if held), `endpoint.json` by rename, `service.log`, the three-part
  readiness check, and the provisional `/shutdown`.
- `docs/architecture/services/write-service-protocol.md` and `event-log-lock-and-append.md`
  set the token (32 random bytes, base64url), the `endpoint.json` fields (0600 file in a 0700
  directory) and the lock interop rules (flock through FFM on POSIX, `FileChannel.lock` on Windows).
- Durability: a unit is one `O_APPEND` write of full lines plus `fsync` before the 201. A batch is
  one write and one fsync. A refusal writes nothing. `event_id` is the idempotency key, so a retry
  is a replay (provisional) and `EVENT_ID_CONFLICT` is 409.
- The service is authoritative. It validates each unit against the lane state machine, its
  guards and dependency gating (core ADR 2026-10-27-1 line 76). Parity is proven by the #6194
  corpus (line 80). Core's source for this is `status/wp_state.py` (769 lines, about 90
  functions and classes), `transitions.py` and `dependency_verdict.py`.

## 3. What this repo already has to build on

- `service/event-log` (`adapter.out.ledger`):
  - `FlockLedgerLock`, which uses FFM flock with per-architecture constants. The native-image
    downcall is not registered yet (`FlockLedgerLock.java:18-30`).
  - `ChannelLedgerLock`, for Windows.
  - `LedgerLocks.forThisOs()`.
  - `TornTailRepair`.
  - `StatusReducer`, `Lane` and `WpState` (in `domain-model`), for the current-state fold.
  - These are checked against Python by the `lock-interop` CI job.
- No Java code writes event rows yet. There is no appender, no encoder that matches Python's
  bytes, and no lane state machine.
- `src/spec_kitty_events/persistence.py`, the Python reference appender, and
  `conformance/fixtures/ledger_append/` (20 valid and 7 invalid cases, byte-exact) cover
  contract rows. **No `status_append` fixture family exists here yet.**
- Native build pattern: `service/build.gradle.kts` (Spring Boot with AOT,
  `org.graalvm.buildtools.native`, `toolchainDetection=false`).
  - `.github/workflows/service.yml` runs the `native` matrix (lines 216-343) with GraalVM CE
    25.0.2 on four targets.
  - `scripts/release/service-release-assets.sh:24-46` hardcodes `mission-status-api-*` asset names.
  - `service.yml` paths and `tests/test_service_workflows.py` hardcode `:mission-status-api`.
  - ADR-0003 says a future deployable follows the same scheme (lines 129-130). Adding one is
    additive (lines 95-96).
- Locally, JDK 25 is at `/usr/lib/jvm/temurin-25`. **GraalVM `native-image` is not installed**,
  so native builds are verified in CI only.

## 4. Inconsistencies found

1. **Path prefix.** The contract's `servers` entry is `/api/v1`. Core's launcher client sends bare
   `/handshake`, `/health` and `/shutdown` (core `src/specify_cli/services/client.py:94,107,121`).
2. **Draining code.** `schemas/HealthStatus.yaml:3-4` says draining refuses with `SERVICE_BUSY`.
   `WriteRefusalCode.yaml` and `responses/ServiceBusy.yaml` say `SERVICE_DRAINING`.
3. **Byte rule.** The `ledger_append` byte rule (Python's default separators) is asserted to equal
   core's `serialize_event_line` output (`persistence.py:189-190`), but no status-row fixture
   proves it.

## 5. Size

The deliverable has four parts, each a reviewable change on its own:

1. the launcher and process contract;
2. the native and jar release;
3. the byte-exact durable appender with lock interop;
4. the port of the lane state machine, guards and dependency gating, with a parity corpus.

Together they do not make one reviewable PR. Section 6 proposes a stack.

## 6. Proposed slicing (stacked PRs, one mission)

- **PR 1, the runnable service.** A new `:command-cli` module (`service/command-cli`).
  - `serve --git-common-dir [--idle-timeout]`, `service.lock` (exit 0 if held), `endpoint.json`
    by rename, `service.log`, the loopback bind guard, the Host and Origin guard, the bearer token
    and the protocol header.
  - `/handshake`, `/health`, `/shutdown` (drain) and the idle timeout.
  - The native matrix and release assets for `command-cli`, the jar, and FFM reachability.
  - The write operations answer a fail-closed refusal until PR 2.
- **PR 2, the canonical writer.** The append path in `event-log`.
  - The sanitizer plus a sorted-key encoder, with a new `status_append` conformance family.
  - `O_APPEND` plus `fsync`, the status lock taken through flock, torn-tail refusal,
    replay and `EVENT_ID_CONFLICT`, batch and annotation, and verification of the write location.
- **PR 3, authority.** The lane state machine, guards and dependency gating, ported from core
  `wp_state.py`, with the 15 refusal reasons and a parity corpus.
