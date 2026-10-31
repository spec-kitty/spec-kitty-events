# command-cli: the write service

`command-cli` is the events domain's write service, the canonical event writer of core
ADR 2026-10-22-1 (D-1). One resident process serves one git common dir. Core's launcher starts
it, checks that it is ready, talks to it over loopback HTTP and stops it. It ships as a GraalVM
native binary for four targets and as a jar for Java 25, on the `service-v*` release line
([ADR 0003](../../docs/adr/0003-gradle-build-and-service-release-line.md)).

**State of this release.** The process contract and the protocol surface are built:

- the launcher contract;
- the handshake and the health check;
- the request guard, the bearer token, the protocol version and the size cap;
- shutdown, idle exit and signal exit.

**Every write refuses fail-closed** with `500 STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED`
("The write core is not built yet; nothing was written.") and writes nothing. Two follow-ups
complete the write core of core spec-kitty/spec-kitty#6194:

- spec-kitty-events#160: the canonical writer, which appends byte-exact, durable, lock-interop rows.
- spec-kitty-events#161: the lane authority, which adds the state machine, the guards and dependency gating.

## How to run

```bash
java -jar build/libs/command-cli.jar serve --git-common-dir <absolute git common dir> [--idle-timeout <seconds>]
```

The native binary takes the same arguments. That is the whole command line
([`contracts/mission-write/LAUNCHER.md`](../../contracts/mission-write/LAUNCHER.md)):

- `--git-common-dir` is required. It must be absolute and an existing directory.
- `--idle-timeout` is a positive number of seconds. The default is 1800.
- Anything else exits 2 and publishes nothing.

Startup runs in this order:

1. **State directory.** The service prepares `<gcd>/spec-kitty-write-service/` with owner-only
   access (0700, or an owner-only ACL on Windows).
   - If the directory is a symbolic link or not a directory, the process exits 1.
2. **Single-instance lock.** It takes `service.lock` in that directory for its whole life.
   - If another instance holds the lock, it exits 0 without binding or publishing anything.
3. **Identity.** It mints an instance id (ULID) and a token (32 random bytes, base64url).
4. **Server.** It binds `127.0.0.1` on a port the OS picks. BindGuard refuses any other address.
5. **Endpoint file.** It publishes `endpoint.json`: it writes a temporary file, forces it to
   disk and renames it into place, owner-only. The fields are `schema_version` 1, `host`, `port`,
   `token`, `instance_id`, `pid`, `protocol_versions` `[1]` and `started_at`. This matches what
   core's reader accepts (core `src/specify_cli/status/write_service_protocol.py`,
   `read_endpoint_file`).

The service logs to stdout and stderr, which the launcher appends to `service.log`. No log line
carries the token, a request path or a header the caller sent.

## The HTTP surface

Every path is under `/api/v1`, the contract's server prefix
([`contracts/mission-write/openapi.yaml`](../../contracts/mission-write/openapi.yaml)).

| Operation | Answer |
|---|---|
| `GET /api/v1/handshake` | `protocolVersions` `[1]`, `serviceVersion`, `instanceId`, `repositoryId` (sha256 of the forward-slash real path of the git common dir) |
| `GET /api/v1/health` | `status` `ready` or `draining`, `instanceId` |
| `POST /api/v1/shutdown` | `202`, then drain and exit 0 |
| `POST /api/v1/missions/{missionId}/work-package-transitions` | the shape is checked, then the write is refused fail-closed |
| `POST /api/v1/missions/{missionId}/work-package-transitions/batch` | the shape is checked (1 to 64 units; a bad unit is named as `#/units/<i>`), then refused fail-closed |
| `POST /api/v1/missions/{missionId}/inner-state-annotations` | the shape is checked, then refused fail-closed |

A request meets the checks in the contract's order. The first one that fails answers:

| Order | Check | Refusal |
|---|---|---|
| 1 | Host/Origin guard, copied from the read service (`Host` is `127.0.0.1:<port>` or `localhost:<port>`; no forwarded headers; a foreign `Origin` or `Sec-Fetch-Site: cross-site` is refused) | `403 REQUEST_REFUSED` |
| 2 | Bearer token, on every path | `401 WRITE_TOKEN_INVALID` with `WWW-Authenticate: Bearer` |
| 3 | A canonical request target: no path parameter (`;`), no percent-encoding, no empty or dot segment, so the checks below decide on the path the dispatcher routes on | `400 REQUEST_MALFORMED` |
| 4 | `Spec-Kitty-Write-Protocol: 1`, on the shutdown and the writes | `400 PROTOCOL_VERSION_UNSUPPORTED` |
| 5 | Body cap of 1 MiB, on every request body | `413 REQUEST_TOO_LARGE` |
| 6 | Draining | `503 SERVICE_DRAINING` (no `Retry-After`) |
| 7 | Body shape | `400 REQUEST_MALFORMED` |

Every refusal is the contract's `WriteRefusal`, as `application/problem+json`. It never echoes
the token, a host path, a header or row content.

## Lifecycle

- **Shutdown.** `POST /api/v1/shutdown` answers `202` at once. The health check then reads
  `draining` and new writes get `SERVICE_DRAINING`. The service waits up to 5 s for the requests
  it holds, a request whose body is still arriving included, and the application's own shutdown
  phase is capped at 2 s, so the process is gone well inside the launcher's 10 s. Then it removes `endpoint.json`, but only while the file still names this instance,
  and exits 0.
- **Idle exit.** After `--idle-timeout` seconds without a request, the service drains and exits
  the same way.
- **Signal exit.** On `SIGTERM`, the launcher's fallback after 10 s, the service still removes its
  own endpoint file.

## How core binds it

Core binds the service itself, once a `service-v*` release carries `command-cli`
(core `docs/plans/events-domain-move/plan.md` §4 step 7; the binding is spec-kitty/spec-kitty#6195):

- **Pin.** `spec-kitty service vendor` writes `pins/<service>.source.yaml` from the release's
  `SHA256SUMS`. The asset prefix is `command-cli` (core `src/kernel/service_pin.py`,
  `DEFAULT_ASSET_PREFIXES`). The targets are `linux-x64`, `linux-arm64`, `macos-arm64`,
  `windows-x64` and `jvm`.
- **Resolve.** Core resolves the binary in this order (core
  `src/specify_cli/services/resolution.py`):
  1. `SPEC_KITTY_WRITE_SERVICE_BINARY`;
  2. the entry point;
  3. the native build cached in the XDG cache, fetched on first use and digest-checked;
  4. the jar on Java 25, only when no native build is pinned for the host, or when the native
     build is unavailable and the jar is already in the cache or the asset directory;
  5. otherwise, a refusal.
- **Launch.** Core starts the binary with
  `<argv prefix> serve --git-common-dir <abs> [--idle-timeout <s>]` (core
  `src/specify_cli/services/policies.py` `WRITE_SERVICE` and `launcher.py`).
- **Bind.** Core calls
  `bind_event_bridge(*, emitter_factory, transition_port)` (core
  `src/runtime/next/_internal_runtime/events.py:304`).
  - The transition port's `record_work_package_transition(transition)` (core
    `src/runtime/next/event_bridge.py`) sends one `TransitionUnit` to
    `POST /api/v1/missions/{missionId}/work-package-transitions`.
  - It builds the unit with `transition_unit_from` (core
    `src/specify_cli/status/write_service_protocol.py`).
  - The runtime emitter half has no operation in protocol major 1.
- **Path prefix.** Core's launcher client still calls the bare paths `/handshake`, `/health` and
  `/shutdown`. The contract's `/api/v1` prefix wins, and spec-kitty/spec-kitty#6910 moves the
  client to it.

## Build and test

| Command | What it runs |
|---|---|
| `./gradlew :command-cli:check` | the JVM tests, the `@Tag("native")` twin run, the launcher-contract process tests against the boot jar, and the JaCoCo gates |
| `./gradlew :command-cli:nativeCompile :command-cli:nativeTest` | the native image and the tagged tests in it (GraalVM 25 as `JAVA_HOME`) |
| `./gradlew :command-cli:nativeSmoke -PnativeBinary=<path>` | the launcher contract against a built binary |

Add `-PreleaseVersion=<ver>` to set the version the handshake reports. The `service.yml` legs
`native-command-cli (<target>)` build and smoke the binary on all four targets.
