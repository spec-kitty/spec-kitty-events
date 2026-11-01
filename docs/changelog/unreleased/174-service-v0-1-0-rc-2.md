---
section: Added
---

- The second service release, `service-v0.1.0-rc.2` (#174): the first on the line to carry the
  write service. It adds the `command-cli` natives (linux-x64, linux-arm64, macos-arm64,
  windows-x64) and its JVM jar next to the `mission-status-api` assets, the contract bundles,
  the contract tools and the conformance fixtures. Each asset has a `.sha256` file and build
  provenance, and `SHA256SUMS` lists them all. Every `command-cli` write still refuses
  fail-closed until #160 and #161 land. It is on the `service-v*` line (ADR 0004) and changes
  nothing on the package line. Notes: `docs/changelog/2026-11-01-service-v0-1-0-rc-2.md`.
