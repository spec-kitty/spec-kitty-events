# Research: command-cli write service (PR1 scope)

The pre-mission research is in `docs/architecture/services/command-cli-research.md`. This file records the plan's design decisions.

## D-1: The stack is the read service's

- **Decision**: Spring Boot 4.1.1 web (Tomcat, virtual threads), Spring AOT, and GraalVM native through `org.graalvm.buildtools.native`. Server interfaces are generated from `contracts/mission-write/openapi.yaml`.
- **Rationale**: operator ruling 2; core ADR 2026-10-27-1 ("reuses the read service's HTTP stack, guard and contract tooling").
- **Alternatives**: the JDK `HttpServer` without Spring (smaller image, faster cold start). Rejected by the ruling.

## D-2: The lock is taken before Spring starts

- **Decision**: `CommandCliMain` parses argv, resolves `--git-common-dir`, prepares `<gcd>/spec-kitty-write-service/` (0700, not a symlink) and calls `FileChannel.tryLock` on `service.lock`. If the lock is held, it exits 0 at once. Otherwise it boots Spring and keeps the channel open for the life of the process.
- **Rationale**: launch contract step 2 ("exits 0 without publishing"). Locking first means a losing instance never binds a port or touches the endpoint file.
- **Why not flock through FFM**: no Python process takes `service.lock`. Core's launcher never writes it (core `services/launcher.py:31`), so only `command-cli` instances compete for it. `FileChannel.tryLock` (fcntl on POSIX, LockFileEx on Windows) excludes across processes, and it needs no native-image downcall registration. The FFM flock path, which does interoperate with Python, is #160's, for the status lock.
- **Caveat**: a POSIX record lock is released when any descriptor of the file closes in the same process. The lock file is therefore opened exactly once, by `ServiceLock`, and never opened elsewhere. An ArchUnit or test guard enforces this.

## D-3: Endpoint file

- **Decision**: once Tomcat has bound its port, `EndpointFile` writes `endpoint.json.<instance>.tmp`. The temp file is created with `O_CREAT|O_EXCL`, owner-only (`PosixFilePermissions` 0600, or an ACL with only the owner on Windows). The service fsyncs it, then `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)` puts it in place.
- **Fields and checks**: they follow core's `read_endpoint_file` (core `status/write_service_protocol.py:530-649`):
  - regular file, mode `& 0o077 == 0` on the file and its directory, owned by the user;
  - `schema_version` int 1;
  - `host` exactly `127.0.0.1`;
  - `port` 1..65535;
  - `token` ≥ 43 characters of `[A-Za-z0-9_-]`;
  - `instance_id`, a non-empty string (a ULID);
  - `pid`, an int;
  - `started_at`, a string;
  - `protocol_versions`, a list of positive ints;
  - total size ≤ 64 KiB.
- **`started_at`**: the publish time, ISO-8601 with offset. Core's `creation_proof` requires it to be after the process creation time and within the start window, so it is taken at publish time, not at JVM start.
- **Removal**: at exit, re-read the file and delete it only if its `instance_id` is this instance.

## D-4: Token

- **Decision**: 32 bytes from `SecureRandom`, URL-safe base64 without padding (43 characters). It is compared with `MessageDigest.isEqual`. It lives only in memory and in the endpoint file, and it is never logged or echoed.

## D-5: Order of checks, as servlet filters

- **Decision**: a single ordered filter chain, mirroring the contract (`openapi.yaml` description):
  1. `HostOriginFilter`: copied from the read service, with code `REQUEST_REFUSED`, 403.
  2. `BearerTokenFilter`: 401 `WRITE_TOKEN_INVALID` with `WWW-Authenticate: Bearer`.
  3. `ProtocolHeaderFilter`: every path except `/api/v1/handshake` and `/api/v1/health`; 400 `PROTOCOL_VERSION_UNSUPPORTED`.
  4. `BodySizeFilter`: write paths, Content-Length or streamed count > 1 MiB → 413 `REQUEST_TOO_LARGE`.
  5. The controller: shape (400 `REQUEST_MALFORMED`), then the domain.
- An unknown path that passes 1 and 2 is 404 as a Problem. Spring's default error body is replaced, as the read service does with `ProblemErrorController`.

## D-6: The fail-closed refusal

- **Decision**: a valid write is refused with `500 STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED` and the fixed detail "The write core is not built yet; nothing was written."
- **Rationale**: it is the only existing code whose meaning is "the unit is not acknowledged and nothing is persisted". Adding a code would break the 20+9 parity with core's `WriteRefusalCode` (ADR 2026-10-27-1) and need a core change. A 500 makes core's client refuse loudly (fail-closed), never retry silently.
- **Alternatives**:
  - `SERVICE_BUSY` (503 with Retry-After). Rejected: it invites retries that can never succeed.
  - A new `WRITE_CORE_UNAVAILABLE` code. Rejected: it is a contract and core change for a temporary state.

## D-7: Shape validation of write bodies

- **Decision**: the body is bound to the generated models (`TransitionUnit`, `TransitionBatch`, `AnnotationRequest`) with Jackson set to `FAIL_ON_UNKNOWN_PROPERTIES` and `FAIL_ON_NULL_FOR_PRIMITIVES`, plus bean validation (the contract's `pattern`, `minItems` and `maxItems`).
- Every row must carry `event_id` and `wp_id` equal to `wpId`. A `mission_id`, when present, must equal the path's. Any failure is 400 `REQUEST_MALFORMED`, with a fixed detail and no echo of the body.
- The `missionId` path parameter must match the MissionId pattern, or the answer is 400 `REQUEST_MALFORMED` (an ill-formed id is a shape fault, not a 404).

## D-8: Drain, shutdown and idle exit

- **Decision**: `ServiceLifecycle` has the states `READY → DRAINING → STOPPED`.
  - `/shutdown` (or the idle watchdog) moves it to `DRAINING`. Health reads `draining`, and a write path answers 503 `SERVICE_DRAINING` without `Retry-After`. Handshake and health still answer.
  - In-flight requests are counted. When the count reaches 0, or after 5 s, the service removes its endpoint file, closes the Spring context and exits 0.
  - `/shutdown` answers 202 before the exit runs. A second `/shutdown` while draining also answers 202 and starts nothing new.
  - The idle watchdog measures from the last request completion and checks every second. The default is 1800 s (ruling 3). `--idle-timeout 0` is refused at parse time.
- **Signal exit**: a JVM shutdown hook (which also runs in native images) removes the own-instance endpoint file.

## D-9: Logging

- **Decision**: log to stdout only (Spring default console). Core's launcher redirects stdout and stderr into `<gcd>/spec-kitty-write-service/service.log` in append mode (core `services/launcher.py:255-263`). Opening the file from the service as well would double every line.
- Logging hygiene is copied from the read service: banner off, no startup info line, Tomcat's parser and cookie loggers raised to WARN, the PageNotFound logger to ERROR. The token is never a log argument.

## D-10: Version and identity

- `serviceVersion`: from `META-INF/build-info.properties` (`build.version`), which the build writes from `-PreleaseVersion` (default `0.0.0-dev`; CI passes `0.0.0-dev+<sha7>`). A native image has no jar manifest, so the manifest is not read (as built; it said `Implementation-Version` before). It must match `^[0-9A-Za-z][0-9A-Za-z.+_-]{0,63}$`.
- `repositoryId`: `sha256(toRealPath(gcd).toString().replace('\\', '/'))`, as lowercase hex. This matches Python's `Path.resolve().as_posix()`.
  - **Windows note**: Python gives a drive-letter path `C:/…`, and `toRealPath` gives `C:\…`, which is normalised.
  - The case of the drive letter can differ, since `resolve()` keeps the case on disk. This is accepted as-is and covered by a Windows-only test in CI (native-tag).

## D-11: Native image

- **Decision**: the configuration is copied from `:mission-status-api`: AOT, `toolchainDetection=false`, the `nativeTagJvmTest` → `nativeTest` flow and `nativeSmoke` with `-PnativeBinary`. Runtime hints go in code (`@ImportRuntimeHints`), with no hand-written reflect-config (ADR-0003).
- The native smoke starts the binary with `serve` against a temporary git common dir, runs the readiness check, posts `/shutdown`, and checks the exit and the removed endpoint file.

## D-12: Release and CI

- The `service.yml` `native` matrix builds both deployables per target, with one job per target that builds them sequentially. `detect` paths gain `service/command-cli/**`.
- `scripts/release/service-release-assets.sh` gains the `command-cli` asset family. `tests/test_service_workflows.py` pins the new expectations.
- All new or changed `uses:` keep 40-hex SHA pins.

## Supply chain (DIRECTIVE_051)

No dependency is added, upgraded or removed. Every library used is already in `gradle/libs.versions.toml` and verified by `gradle/verification-metadata.xml`, so there is no new attack surface to challenge. If the build turns out to need a new artifact (for example a test-only one), the dependency decision is re-opened here and challenged before it lands.
