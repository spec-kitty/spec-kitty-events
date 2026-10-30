# Data Model: events domain services home

There is no runtime data model change. The entities below are release and governance artifacts.

## ServiceRelease
- `tag`: `service-v<ver>`, where `<ver>` matches `MAJOR.MINOR.PATCH[-prerelease]`. It is independent of the PyPI version.
- `commit`: a 40-hex SHA that must be an ancestor of `main`.
- `assets`: a set of `ReleaseAsset`. Its names are fixed by `contracts/service-release-assets.md`.
- `prerelease`: true when `<ver>` has a `-` suffix.
- Invariants:
  - The asset name set is the same for every release except for `<ver>` (spec C-003).
  - Every asset appears in `SHA256SUMS` and has its own `.sha256`.
  - Every binary, jar and tarball has a build-provenance attestation.

## ReleaseAsset
- `name`, `sha256` (64-hex), `kind` ∈ {native-binary, jar, contract-bundles, contract-tools, conformance-fixtures, checksums}
- `deployable` (for native-binary and jar): `mission-status-api` now; `command-cli` added later under the same scheme.
- `target` (for native-binary): ∈ {linux-x64, linux-arm64, macos-arm64, windows-x64}

## CorePin (`service/core-pin.json`, at cutover)
- `repository`: `spec-kitty/spec-kitty`
- `sha`: 40-hex
- `purpose`: CI-only peers: lock interop, corpus oracle, golden regeneration
- Invariant: it is never read by the production build or by packaging.

## IssueMove (cutover)

| Core # | Method | Events side | Core side keeps |
|---|---|---|---|
| 6117 (epic) | transfer | epic | — |
| 6194, 6196, 6197, 6198, 6199 | transfer, together with 6117 | children | — |
| 6409, 6268 | transfer | issues | — |
| 6195 | split | Java cut-over half | binding the CLI client, deleting the Python appenders |
| 6676 | split | native build of the `command-cli` binary the launcher starts | CLI launcher, fail-closed codes |
| 5528 | split | read-API service/contract work | dashboard consumers |
| 957 | split | Java route | Python oracle |
| 6609 | split | Java reducer ordering | Python CLI reducer |
| 6692 | close as done, link ADR 0002 | — | — |
| 6608 | stays; link the moved issues | — | epic |

State transitions are performed once, at cutover, only after operator approval:
- transfer: open in core → open in events
- split: core stays open and an events issue is opened
- 6692: open → closed (completed)
