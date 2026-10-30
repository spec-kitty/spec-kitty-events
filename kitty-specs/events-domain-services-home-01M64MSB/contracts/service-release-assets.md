# Contract: service release assets (`service-v*`)

**Consumers**: core (`spec-kitty/spec-kitty`), which pins tag + sha256 per asset and fetches on first run with digest verification; core `acceptance-jvm`.
**Stability rule**: asset names are stable across releases. Only `<ver>` changes. Adding a deployable or an asset kind is additive. Renaming or removing one is a breaking change and needs a new ADR.

`<ver>` is the tag without the `service-v` prefix. For example, tag `service-v0.1.0-rc.1` gives `<ver>` = `0.1.0-rc.1`.

| Asset | Content |
|---|---|
| `mission-status-api-<ver>-linux-x64` | GraalVM native executable |
| `mission-status-api-<ver>-linux-arm64` | GraalVM native executable |
| `mission-status-api-<ver>-macos-arm64` | GraalVM native executable |
| `mission-status-api-<ver>-windows-x64.exe` | GraalVM native executable |
| `mission-status-api-<ver>.jar` | Spring Boot executable jar (JVM fallback, core ADR 2026-10-22-1) |
| `contract-bundles-<ver>.tar.gz` | the `bundle/` tree that `contracts/tools/bundle.py --bundle-only` writes: `bundle/mission-status/openapi.yaml` and `bundle/mission-write/openapi.yaml` (deterministic, self-contained) |
| `contract-tools-<ver>.tar.gz` | `contracts/tools/*.py`, `pins.json`, `enum_pins.json` (no test fixtures) |
| `conformance-fixtures-<ver>.tar.gz` | `conformance/**`, `artifact-conformance/{cases,golden}.json`, `nativesmoke/requests.json`, `leak-patterns.json`, `event-log/torn-tail/repair-cases.json`: entry names in the tarball (no top-level directory), staged from `service/` as the [layout table](https://github.com/spec-kitty/spec-kitty-events/blob/main/docs/guides/service-protocol-and-releases.md) lists |
| `<asset>.sha256` | one per asset above, in `sha256sum` format: `<64-hex>  <asset>` |
| `SHA256SUMS` | every asset above, in `sha256sum` format, sorted by name |

**Provenance**: every asset except the `.sha256` files and `SHA256SUMS` has a build-provenance attestation (`gh attestation verify <asset> --repo spec-kitty/spec-kitty-events`).

**Tarballs**: built with a deterministic file order and owner (`tar --sort=name --owner=0 --group=0 --numeric-owner --mtime=@<commit-time>`, then `gzip -n`). Rebuilding from the same commit gives the same sha256.

**Future deployable** (`command-cli`, the write core): it follows the same scheme, `command-cli-<ver>-<target>[.exe]` and `command-cli-<ver>.jar`.
