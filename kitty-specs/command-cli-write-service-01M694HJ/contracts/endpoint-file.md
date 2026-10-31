# Contract: `<gcd>/spec-kitty-write-service/endpoint.json`

The HTTP surface is the module contract `contracts/mission-write/openapi.yaml` (`1.0.0-SNAPSHOT`). The process surface is `contracts/mission-write/LAUNCHER.md`. This page pins the one document between them: the endpoint file, as core's reader enforces it (core `src/specify_cli/status/write_service_protocol.py`, `read_endpoint_file` and `_refuse_exposed`).

## File rules

- The file is a regular file and never a symbolic link.
- It is owned by the user running the service.
- Mode `& 0o077 == 0` on the file and on its directory. On Windows, an ACL that grants only the owner.
- It is at most 65,536 bytes of UTF-8 JSON, and is an object.
- It is published by writing `endpoint.json.<instance_id>.tmp` (exclusive create, owner-only), then fsync, then an atomic rename over `endpoint.json`.

## Fields

| Field | Type | Rule |
|---|---|---|
| `schema_version` | int | `1` |
| `host` | string | exactly `127.0.0.1` |
| `port` | int | 1..65535, the bound port |
| `token` | string | 43 characters of `[A-Za-z0-9_-]` (32 random bytes, base64url, no padding); new each start |
| `instance_id` | string | ULID, equal to the handshake's `instanceId` and health's `instanceId` |
| `pid` | int | `ProcessHandle.current().pid()` |
| `protocol_versions` | int[] | `[1]` |
| `started_at` | string | ISO-8601 with offset, taken at publish time (after the port is bound) |

## Example

```json
{"host":"127.0.0.1","instance_id":"01M694HJ0000000000000000AA","pid":4242,"port":51234,"protocol_versions":[1],"schema_version":1,"started_at":"2026-10-31T14:30:00.123+00:00","token":"q3v9…43 chars…"}
```
