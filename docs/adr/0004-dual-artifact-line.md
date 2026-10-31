---
title: 'ADR-0004: two independent release lines, the PyPI package and the services'
description: 'Accepted: the PyPI package (v*) and the services (service-v*) are versioned independently; the Java binding follows the package; core pins each line separately.'
doc_status: active
updated: 2026-10-31T00:00:00Z
type: ADR
kind: ADR
authors:
  - human:stijn
---

# ADR-0004: two independent release lines, the PyPI package and the services

**Status:** Accepted

**Date:** 2026-10-29

**Deciders:** operator stijn-dejongh with the Spec Kitty CEO, 2026-10-29.

**Reader:** a maintainer cutting a release of this repository, deciding which version number
a change moves; a core maintainer pinning this repository's artifacts.

**Technical Story:** [#147](https://github.com/spec-kitty/spec-kitty-events/issues/147).
Mission `events-domain-services-home-01M64MSB`, operator decisions D4 and D6.

Related records: [ADR-0001](./0001-cloudevents-envelope.md) (the envelope and the Java
binding), [ADR-0002](./0002-events-domain-owns-its-services.md) (service ownership) and
[ADR-0003](./0003-gradle-build-and-service-release-line.md) (the `service-v*` line and its
assets).

**Status note (2026-10-31).** When this record was accepted (2026-10-29), nothing in it had
shipped: the package line existed, and the service line was to start with the first
`service-v0.x` pre-release at cutover. The cutover import merged on 2026-10-31
([#150](https://github.com/spec-kitty/spec-kitty-events/pull/150)), and the first service
release, `service-v0.1.0-rc.1`, has the operator's approval of 2026-10-31 (rulings on
[spec-kitty/spec-kitty#6117](https://github.com/spec-kitty/spec-kitty/issues/6117#issuecomment-6544221340)).
The operator pushes the tag. It is built against package version `11.3.0`; the package
line is unchanged and has no release of its own in this step.

The decisions below are unchanged.

---

## Context and Problem Statement

Until now this repository released one artifact: the Python package on PyPI, tagged
`vX.Y.Z` and published by `publish-pypi.yml`. Its version follows the package SemVer rules in
`contracts/versioning-and-compatibility.md`.

[ADR-0002](./0002-events-domain-owns-its-services.md) adds services, and
[ADR-0003](./0003-gradle-build-and-service-release-line.md) gives them a `service-v*` release
line. The two artifacts change for different reasons:

- The package moves when the envelope, a payload schema or a fixture changes.
- The services move when their HTTP contracts, their behaviour or their asset contract
  change.

A Java **binding** also exists under `java/` (`ai.spec-kitty:spec-kitty-events-java`). It is
a binding of the event contract, not a service
([ADR-0001](./0001-cloudevents-envelope.md); charter, Language Bindings).

This record decides how the lines are tagged and versioned, where the binding belongs, what
counts as breaking on each line, and how core pins both.

## Decision Drivers

- A tag must trigger exactly one release workflow (spec constraint C-004).
- A service fix must not force a package release, and the reverse.
- A reader of a service release must be able to tell which contract it was tested against.
- The charter's Language Bindings rule: every binding passes the same fixtures and changes
  with them.
- Core's write-service handshake negotiates a protocol major (core
  [ADR 2026-10-27-1](https://github.com/spec-kitty/spec-kitty/blob/main/docs/adr/4.x/2026-10-27-1-the-write-service-protocol-is-loopback-http-with-a-bearer-token.md)
  D-4).

## Considered Options

1. **One version for everything,** the package and the services released together.
2. **Two independent lines,** with the binding on the package line (chosen).
3. **Two independent lines, with the binding on the service line.**

## Decision Outcome

**Chosen option:** 2.

1. **Two independent release lines.**
   - The Python package on PyPI: tag `vX.Y.Z`, published by `publish-pypi.yml`. This is
     unchanged.
   - The services: tag `service-vX.Y.Z`, published by `service-release.yml`
     ([ADR-0003](./0003-gradle-build-and-service-release-line.md)).

   Neither tag pattern matches the other: a `v*` tag runs only the package workflow, and a
   `service-v*` tag runs only the service workflow.
2. **Independent versions.** The two version numbers move independently. The release notes
   of each service release state which package version's schemas and fixtures it was built
   and tested against.
3. **The Java binding is on the package line.** `java/`
   (`ai.spec-kitty:spec-kitty-events-java`) is versioned with the Python package, because it
   is a binding of the same contract and passes the same fixtures (charter, Language
   Bindings). It is not published to a registry yet.
4. **Breaking changes.**
   - **Package:** the package SemVer rules are unchanged.
   - **Services:** service SemVer covers the HTTP contracts (`mission-status`,
     `mission-write`) and the asset contract of
     [ADR-0003](./0003-gradle-build-and-service-release-line.md). A breaking change to
     either is a service major.
   - **Write protocol:** a breaking `mission-write` protocol change needs both a service
     major and a new protocol major in core's handshake version negotiation (core ADR
     2026-10-27-1 D-4). That way a client and a service on different majors refuse each
     other instead of misreading each other.

## Consequences

### How core pins both lines

Core pins each line on its own:

- **The package.** Core pins it by PyPI version. While the `EXPERIMENTAL-spec-kitty-*`
  programme runs, the charter's programme exception applies instead: an exact git SHA of
  this repository, moved only by a reviewed PR.
- **The services.** Core pins a `service-vX.Y.Z` tag and the sha256 of each asset it
  fetches. It verifies the digest on first-run fetch (D6). It never fetches an asset by a
  moving reference.

A core change that needs both a new package and a new service release bumps both pins in
one PR.

### Positive

- A service fix ships without a package release and its compatibility review, and the
  reverse.
- The `v*` workflow and the `service-v*` workflow cannot trigger each other.
- The binding stays in lockstep with the Python package, so one version names one contract
  in both languages.

### Negative

- Two version numbers to explain. A reader must check a service release's notes to learn
  which package version it was tested against.
- Core maintains two pins with different mechanisms.

### Risks

- **Skew between the lines.** A service release may lag the package, and the services may
  then reject envelopes the package already accepts. Mitigation: each service release names
  the package version whose schemas and fixtures it was built and tested against (Decision 2).
- **Mistagging.** A `v*` tag on a service change, or the reverse, releases the wrong
  artifact. Mitigation: the tag patterns are disjoint, and the `service-v*` workflow checks
  its tag format and that the tag is an ancestor of `main`.

## Alternatives considered

### One version for everything

Rejected. Every service fix would force a package release, and every package release would
force a service release, coupling two cadences that change for different reasons.

### The binding on the service line

Rejected. The binding implements the event contract, not a service; versioning it with the
services would let the Python and Java bindings of one contract carry different numbers.

## Revisit when

- The Java binding is published to a registry, and its consumers need a cadence of their
  own.
- The programme exception for SHA pins ends, and core moves to PyPI version pins.

## References

- Core ADRs:
  [2026-10-01-2](https://github.com/spec-kitty/spec-kitty/blob/main/docs/adr/4.x/2026-10-01-2-mission-status-read-api-and-dashboard-extraction.md),
  [2026-10-14-1](https://github.com/spec-kitty/spec-kitty/blob/main/docs/adr/4.x/2026-10-14-1-event-native-core-one-canonical-event-writer.md),
  [2026-10-22-1](https://github.com/spec-kitty/spec-kitty/blob/main/docs/adr/4.x/2026-10-22-1-the-java-write-service-is-the-canonical-event-writer.md),
  [2026-10-27-1](https://github.com/spec-kitty/spec-kitty/blob/main/docs/adr/4.x/2026-10-27-1-the-write-service-protocol-is-loopback-http-with-a-bearer-token.md).
- Charter: Package Boundary (programme exception) and Language Bindings.
- Mission artifacts: `kitty-specs/events-domain-services-home-01M64MSB/spec.md` (D4, D6,
  C-004), `data-model.md` (ServiceRelease).
