---
title: 'ADR-0003: one Gradle build and the service-v* release line'
description: 'Accepted: Gradle Kotlin DSL on JDK 25 builds every Java artifact; GraalVM native images for 4 targets ship on service-v* tags with stable asset names, sha256 files and provenance.'
doc_status: active
updated: 2026-10-31T00:00:00Z
type: ADR
kind: ADR
authors:
  - human:stijn
---

# ADR-0003: one Gradle build and the service-v* release line

**Status:** Accepted

**Date:** 2026-10-29

**Deciders:** operator stijn-dejongh with the Spec Kitty CEO, 2026-10-29.

**Reader:** a maintainer changing how a Java artifact of this repository is built, or how a
service release is cut and what it contains; a core maintainer writing the pin that fetches
a service release.

**Technical Story:** [#147](https://github.com/spec-kitty/spec-kitty-events/issues/147).
Mission `events-domain-services-home-01M64MSB`, operator decisions D3, D4 and D6; research
R-3 and R-4.

Related records: [ADR-0002](./0002-events-domain-owns-its-services.md) (why the services
live here) and [ADR-0004](./0004-dual-artifact-line.md) (how this release line relates to the
Python package line).

**Status note (2026-10-31).** When this record was accepted (2026-10-29), nothing in it had
shipped: the binding's build was moving, the services were to join the build at cutover, and
the first `service-v0.x` pre-release was to be cut only with operator approval. Since then:

- The root Gradle build covers the binding, the services and the contracts:
  `./gradlew verifyAll` checks `:java-binding`, `:mission-status-api`, `:domain-model`,
  `:event-log` and `:contracts`
  ([#150](https://github.com/spec-kitty/spec-kitty-events/pull/150), merged 2026-10-31).
- `service.yml` runs its service jobs on `main`: the four native images (linux-x64,
  linux-arm64, macos-arm64, windows-x64) with their native tests and golden replay, and the
  JVM jar.
- The first pre-release, `service-v0.1.0-rc.1`, has the operator's approval of 2026-10-31
  (rulings on
  [spec-kitty/spec-kitty#6117](https://github.com/spec-kitty/spec-kitty/issues/6117#issuecomment-6544221340)).
  The operator pushes the tag, and `service-release.yml` publishes the asset set of
  [Release assets](#release-assets).

The decisions below are unchanged.

---

## Context and Problem Statement

[ADR-0002](./0002-events-domain-owns-its-services.md) brings the Mission Status Read API and
the Mission Write API write core into this repository. They arrive with build and release
machinery that does not match what is here:

- The Java binding under `java/` builds with Maven (#92).
- Core's service builds with its own Gradle 9.7.1 wrapper, without convention plugins.
- Core's `contracts/` build is Groovy Gradle on 8.14.5.
- Core releases contracts on its own `contract-<module>-v*` tag line.
- Core's `mission-status-api.yml` builds GraalVM native images on a 4-OS matrix with
  build-provenance attestation.

Core [ADR 2026-10-22-1](https://github.com/spec-kitty/spec-kitty/blob/main/docs/adr/4.x/2026-10-22-1-the-java-write-service-is-the-canonical-event-writer.md)
D-5 makes the service native first, with a JVM jar as the fallback. Core fetches the binary on
first run and verifies its digest (D6), so the asset names and their checksums are an
interface core codes against. This record decides the toolchain, the native targets, the
release gate and the asset contract.

## Decision Drivers

- One toolchain for every Java artifact in the repository (D3).
- Native first, JVM jar as fallback (core ADR 2026-10-22-1 D-5).
- Core pins a tag and a sha256 per asset, so names must be stable and digests reproducible.
- A release can only be cut from reviewed code on `main`.
- Supply chain: SHA-pinned actions, strict dependency verification, provenance.

## Considered Options

1. **Maven** for every Java artifact.
2. **Gradle with the Kotlin DSL** as the single toolchain (chosen).
3. For distribution: **a jlink JVM-only image** instead of native binaries.
4. For contracts: **a separate contracts tag line** beside the service line.

## Decision Outcome

**Chosen option:** Gradle with the Kotlin DSL, GraalVM native images plus a JVM jar, and one
`service-v*` release line that also carries the contract bundles and tools.

1. **Build.** Gradle with the Kotlin DSL is the single Java toolchain (D3).
   - A root build at the repository root includes `build-logic/`, a build of convention
     plugins.
   - Dependencies come from a version catalog, under strict dependency verification.
   - Gradle wrapper 9.8.1 (sha256-pinned) and JDK 25.
   - The Maven binding migrates to this build (mission WP03), with an unchanged test count.
   - The services and `contracts/` join it at cutover.
2. **Native image.** GraalVM CE 25 builds 4 targets: linux-x64, linux-arm64, macos-arm64
   and windows-x64. Reachability hints live in code (`@ImportRuntimeHints`) plus Spring
   AOT. There are no hand-written reflect-config files.
3. **Release.** A release is a tag `service-vX.Y.Z[-pre]`.
   - The tag must be an ancestor of `main`. The workflow refuses any other tag.
   - Assets are built in a read-only job.
   - The publish job verifies the sha256 sums and attests build provenance. It re-checks
     that the tag still names the built commit, then creates the GitHub release. A `-`
     suffix makes it a pre-release.
   - The asset names are those in the table below, copied from the mission contract
     `contracts/service-release-assets.md`; this record is their durable home.
   - Names are stable: only `<ver>` changes. Adding a deployable or an asset kind is
     additive. Renaming or removing an asset needs a new ADR.
4. **Contract releases.** Core's `contract-<module>-v*` tag line retires. Contract bundles
   and contract tools ride on `service-v*` (D4).
5. **Precedent.** The spec-kitty-charter repository has no native or attestation pipeline.
   - The native and attestation design comes from core's `mission-status-api.yml`.
   - The tag, ancestor, verify and publish gate comes from the charter's
     `contract-release.yml`.

### Release assets

`<ver>` is the tag without the `service-v` prefix. For example, tag `service-v0.1.0-rc.1`
gives `<ver>` = `0.1.0-rc.1`.

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

- **Provenance:** every asset except the `.sha256` files and `SHA256SUMS` has a
  build-provenance attestation
  (`gh attestation verify <asset> --repo spec-kitty/spec-kitty-events --signer-workflow spec-kitty/spec-kitty-events/.github/workflows/service-release.yml --source-ref
  refs/tags/<tag>`; without the signer and ref flags any workflow in the repository would pass).
- **Tarballs** are built with a deterministic file order and owner
  (`tar --sort=name --owner=0 --group=0 --numeric-owner --mtime=@<commit-time>`, then
  `gzip -n`). Rebuilding from the same commit gives the same sha256.
- **Future deployable** (`command-cli`, the write core) follows the same scheme:
  `command-cli-<ver>-<target>[.exe]` and `command-cli-<ver>.jar`.

## Consequences

### Positive

- One wrapper, one catalog and one verification file for every Java artifact.
- Core codes against fixed asset names and verifies every download by digest.
- Provenance can be checked independently of this repository's CI logs.
- `acceptance-jvm` in core gets its contract tools from a release
  (`contract-tools-<ver>.tar.gz`) instead of a source checkout (D6).

### Negative

- Contributors need JDK 25. Native builds need GraalVM, which runs in CI, not locally.
- At cutover the service's 9.7.1 build and the contracts' 8.14.5 build must be re-wrapped
  onto 9.8.1, and their verification metadata merged into the root file.
- A contract-only fix now costs a service release, because contracts ride on `service-v*`.

### Risks

- **Native image drift.** A missing reachability hint shows up only in the native build.
  Mitigation: the native tests run in CI on all four targets. Publishing the reachability
  metadata they generate as a build artifact for review is a cutover follow-up (runbook
  section 4).
- **Platform runners.** The arm64 and macOS runners may lag or fail. Every asset in the
  table is part of the contract, so a failed target blocks the release rather than
  shipping a partial set.
- **Binary size.** Native binaries are large. Sizes are recorded on the first build; the
  first-run fetch (D6) keeps them out of the PyPI wheel.

## Alternatives considered

### Maven

Rejected (D3). The service and the contracts already build with Gradle; keeping Maven for the
binding means two toolchains.

### A jlink JVM-only distribution

Rejected. Core ADR 2026-10-22-1 D-5 makes the service native first, with the JVM jar only as
the fallback. Each release still carries the jar, for that fallback.

### A separate contracts tag line

Rejected (D4). The contracts are versioned with the services that implement them; a second
tag line would let a contract release and the service that serves it disagree.

## Revisit when

- A target platform is added or dropped.
- A consumer needs contract bundles on a cadence the service line does not give.

## References

- Core ADRs:
  [2026-10-01-2](https://github.com/spec-kitty/spec-kitty/blob/main/docs/adr/4.x/2026-10-01-2-mission-status-read-api-and-dashboard-extraction.md),
  [2026-10-14-1](https://github.com/spec-kitty/spec-kitty/blob/main/docs/adr/4.x/2026-10-14-1-event-native-core-one-canonical-event-writer.md),
  [2026-10-22-1](https://github.com/spec-kitty/spec-kitty/blob/main/docs/adr/4.x/2026-10-22-1-the-java-write-service-is-the-canonical-event-writer.md),
  [2026-10-27-1](https://github.com/spec-kitty/spec-kitty/blob/main/docs/adr/4.x/2026-10-27-1-the-write-service-protocol-is-loopback-http-with-a-bearer-token.md).
- Mission artifacts: `kitty-specs/events-domain-services-home-01M64MSB/spec.md` (D3, D4, D6),
  `research.md` (R-3, R-4), `contracts/service-release-assets.md`.
