# Launcher contract

The process contract between the launcher (core's CLI launcher, spec-kitty/spec-kitty#6676)
and the write service this module describes: how the service is started, how the launcher
knows it is ready, and how it is stopped. It is prose, not OpenAPI: it binds the process
around the HTTP contract in [`openapi.yaml`](./openapi.yaml), and it moves with this
module's `info.version` and its [`CHANGELOG.md`](./CHANGELOG.md).

**Status.** The operator accepted this contract on 2026-10-29. It is recorded here at the
cutover (spec-kitty/spec-kitty-events#147). Parts of it already stood in core
ADR 2026-10-27-1 (D-2, open question 6) and in this module. **`/shutdown` is provisional
until `command-cli` is built**: the operation carries `x-provisional`, and the
[`CHANGELOG.md`](./CHANGELOG.md) lists it under Provisional.

## Start

The launcher runs
`<binary> serve --git-common-dir <absolute path> [--idle-timeout <seconds>]`. There is one
write service per git common dir. Without `--idle-timeout`, the service's default applies.

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

## Stop (provisional)

`POST /shutdown` (bearer) drains and exits: health reads `draining`, the service finishes the
writes it holds, removes its endpoint file and answers `202` before it exits
([`paths/shutdown.yaml`](./paths/shutdown.yaml)). As written, the contract also requires the
`Spec-Kitty-Write-Protocol` header on this operation and marks it `x-provisional`. If the
process is still running after 10 s and its pid still matches the endpoint's instance, the
launcher falls back to `SIGTERM` (`TerminateProcess` on Windows).
