---
title: "Unreleased: the events domain takes ownership of its services"
description: "ADRs 0002–0004 and a charter amendment make this repo the home of the events services; the Java binding moves to Gradle; service workflows, guides and a cutover runbook land."
doc_status: active
updated: 2026-10-29T00:00:00Z
type: Changelog
kind: Changelog
sidebar:
  order: -202610291
tags: [services, gradle, release, adr, charter]
---

# 2026-10-29 — Unreleased: the events domain takes ownership of its services

**Package unchanged (`11.3.0`)** · spec-kitty-events#147 · no schema, fixture, model or
envelope change, so no package release. The services themselves are not imported yet: that
happens at cutover, on the operator's go-signal.

## What changed

- **Three ADRs, all Accepted.**
  - [ADR-0002](../adr/0002-events-domain-owns-its-services.md): this repository is canonical
    for the events domain and its services: the Mission Status Read API, the Mission Write
    API write core and their OpenAPI contracts. They move here from core with their history.
  - [ADR-0003](../adr/0003-gradle-build-and-service-release-line.md): one Gradle build for
    every Java artifact, GraalVM native images for four targets, and the `service-v*` release
    line with fixed asset names.
  - [ADR-0004](../adr/0004-dual-artifact-line.md): the PyPI package (`v*`) and the services
    (`service-v*`) are released and versioned independently; the Java binding stays on the
    package line.
- **Charter amendment.** A Services section and a Documentation section, and a test-only,
  SHA-pinned core checkout for service CI as a scoped exception to the Package Boundary.
- **The Java binding builds with Gradle.** A root Kotlin DSL build (Gradle 9.8.1, a
  `build-logic/` of conventions, a version catalog, strict dependency verification) replaces
  Maven. Run it with `./gradlew verifyAll` or `make test-java`. The test count is unchanged.
- **Service workflows.** `service.yml` runs the Gradle build today; its service jobs (native
  images, lock interop, nightly corpus compare) stay inert until `service/` is imported.
  `service-release.yml` is not inert: until the import it refuses every `service-v*` tag
  and fails loudly, so no release can be cut by mistake.
- **Docs.** The docsite moves to `@spec-kitty/doc-toolkit` 0.5.0. New service architecture
  pages under [services](../architecture/services/README.md), and three guides:
  [running the service](../guides/running-the-service.md),
  [service protocol and releases](../guides/service-protocol-and-releases.md) and the
  [service cutover runbook](../guides/service-cutover-runbook.md).

## For consumers

- **Package consumers, core included:** no action. Nothing in the package changed.
- **Contributors building `java/`:** use `./gradlew verifyAll` or `make test-java`; `mvn` no
  longer builds anything here.
- **Core:** after cutover, pin `service-v*` releases by tag and sha256 per asset, as
  [service protocol and releases](../guides/service-protocol-and-releases.md#how-core-pins-a-release)
  describes.
