# Data model: command-cli (PR1)

## ServeOptions (value object, `config`)

| Field | Type | Rule |
|---|---|---|
| gitCommonDir | absolute path | Required. Must be absolute and an existing directory, and is resolved with `toRealPath`. Otherwise the process exits 2 with a usage message. |
| idleTimeout | Duration | Optional. A positive integer number of seconds. Default `PT1800S`. Otherwise the process exits 2. |

The command line is `serve --git-common-dir <abs> [--idle-timeout <s>]`. Any other subcommand or flag exits 2.

## StateDirectory (`adapter.out.filesystem`)

`<gcd>/spec-kitty-write-service/`, mode 0700, owner-only.

- It must not be a symbolic link. A symbolic link is a refusal: exit 1 with a message that names no path other than the directory.
- If it exists with group or other bits set, the service tightens it to 0700, or exits 1 if that fails.

It holds `service.lock`, `endpoint.json` and `service.log`. The log is written by the launcher's redirect, not by the service.

## ServiceLock

An exclusive `FileChannel.tryLock()` on `service.lock`, held for the life of the process.

- **Held by another process**: exit 0, with nothing published and nothing bound.
- **Opened once**: the lock file is opened only by this class (see research D-2).

## EndpointFile (published document)

See [contracts/endpoint-file.md](./contracts/endpoint-file.md).

**Invariant**: the file only ever names a live instance that holds the lock. Removing it is conditional on the file's `instance_id` being this instance's.

## InstanceId (`domain.service`)

A Crockford base-32 ULID of 26 characters, minted at start. The pattern is `^[0-9A-HJKMNP-TV-Z]{26}$`.

## RepositoryId (`domain.service`)

The lowercase hex SHA-256 of the forward-slash real path of the git common dir. The pattern is `^[0-9a-f]{64}$`.

## ServiceState (`domain.service`)

```
READY --(shutdown | idle timeout)--> DRAINING --(in-flight = 0 or 5 s)--> STOPPED
```

- READY: health is `ready`, and writes reach the write gate.
- DRAINING: health is `draining`, and writes get 503 `SERVICE_DRAINING`. Handshake and health still answer.
- STOPPED: the endpoint file is removed, the context is closed, and the process exits 0.
- These transitions only move forward. A repeated shutdown is idempotent.

## Refusal (`domain.service.RefusalCode` and `adapter.in.http.RefusalBody`)

`application/problem+json`: `{type: "about:blank", title, status, code, detail?}`, matching `WriteRefusal`. Codes used in PR1:

| Code | Status | When |
|---|---|---|
| REQUEST_REFUSED | 403 | Host, Origin or Sec-Fetch-Site guard |
| WRITE_TOKEN_INVALID | 401 | Bearer token missing or wrong; carries `WWW-Authenticate: Bearer` |
| PROTOCOL_VERSION_UNSUPPORTED | 400 | Protocol header missing, not an integer, or ≠ 1 |
| REQUEST_TOO_LARGE | 413 | Body > 1 MiB |
| REQUEST_MALFORMED | 400 | Shape fault, a row naming another WP or Mission, or an ill-formed missionId |
| SERVICE_DRAINING | 503 | A write while draining; no Retry-After |
| STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED | 500 | Every valid write in PR1 (fail-closed) |

An unknown route that has passed the guard and the token is 404, using the shared Problem without a `code`. That route is not a contract operation, so the 404 is not a `WriteRefusal`.
