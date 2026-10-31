# Launcher contract

The process contract between the launcher (core's CLI launcher, spec-kitty/spec-kitty#6676)
and the write service this module describes: how the service is started, how the launcher
knows it is ready, and how it is stopped. It is prose, not OpenAPI: it binds the process
around the HTTP contract in [`openapi.yaml`](./openapi.yaml), and it moves with this
module's `info.version` and its [`CHANGELOG.md`](./CHANGELOG.md).

**Status.** The operator accepted this contract on 2026-10-29. It is recorded here at the
cutover (spec-kitty/spec-kitty-events#147). Parts of it already stood in core
ADR 2026-10-27-1 (D-2, open question 6) and in this module. `/shutdown` and the `draining`
health status were provisional until `command-cli` was built; they were settled with it
(spec-kitty/spec-kitty-events#156, operator rulings of 2026-10-31), and the
[`CHANGELOG.md`](./CHANGELOG.md) records the change. The service in this repository,
`service/command-cli`, implements this contract; its writes refuse fail-closed until the
canonical writer (#160) and the lane authority (#161) land.

Every path below is under the contract's server prefix `/api/v1` (`GET /api/v1/handshake`,
`GET /api/v1/health`, `POST /api/v1/shutdown`). The prefix wins over core's launcher client,
which still calls the bare paths; spec-kitty/spec-kitty#6910 moves it.

## Start

The launcher runs
`<binary> serve --git-common-dir <absolute path> [--idle-timeout <seconds>]`. There is one
write service per git common dir. Without `--idle-timeout`, the service's default applies:
1800 seconds. Any other command line exits 2 and publishes nothing. The service logs to
stdout and stderr, which the launcher appends to `service.log`.

## Single instance

The service takes `<gcd>/spec-kitty-write-service/service.lock` and publishes
`endpoint.json` by writing a temporary file and renaming it into place (D-2). If the lock is
held, it exits 0 without publishing. Its log file is
`<gcd>/spec-kitty-write-service/service.log`.

## Ready

Ready means all three:

1. `endpoint.json` is present;
2. `GET /handshake` (bearer) returns a `repositoryId` equal to the SHA-256 of the real path
   of the git common dir, and `protocolVersions` that intersect the CLI's;
3. `GET /health` returns `status: ready`
   ([`schemas/HealthStatus.yaml`](./schemas/HealthStatus.yaml): `ready` or `draining`).

## Stop

`POST /shutdown` (bearer and the `Spec-Kitty-Write-Protocol` header) drains and exits: it
answers `202` at once, health reads `draining`, a write is refused with `SERVICE_DRAINING`, the
service finishes the writes it holds (at most 5 s), removes its endpoint file while it still names
its own instance and exits 0 ([`paths/shutdown.yaml`](./paths/shutdown.yaml)). After its idle
timeout without a request, the service drains and exits the same way. If the process is still
running after 10 s and its pid still matches the endpoint's instance, the launcher falls back to
`SIGTERM` (`TerminateProcess` on Windows); on `SIGTERM` the service still removes its own
endpoint file.
