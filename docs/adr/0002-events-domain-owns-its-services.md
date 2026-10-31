---
title: 'ADR-0002: spec-kitty-events is canonical for the events domain and its services'
description: 'Accepted: this repo owns the events domain, the Mission Status Read API, the Mission Write API write core and their OpenAPI contracts; core consumes released artifacts.'
doc_status: active
updated: 2026-10-31T00:00:00Z
type: ADR
kind: ADR
authors:
  - human:stijn
---

# ADR-0002: spec-kitty-events is canonical for the events domain and its services

**Status:** Accepted

**Date:** 2026-10-29

**Deciders:** operator stijn-dejongh with the Spec Kitty CEO, 2026-10-29.

**Reader:** a maintainer of this repository or of core (`spec-kitty/spec-kitty`) deciding
where a change to the event envelope, the event-log reader or writer, or their HTTP
contracts belongs.

**Technical Story:** [#147](https://github.com/spec-kitty/spec-kitty-events/issues/147).
Mission `events-domain-services-home-01M64MSB`, operator decisions D1, D2, D5, D6, D7 and D8.

Related records: [ADR-0003](./0003-gradle-build-and-service-release-line.md) (how the services
are built and released) and [ADR-0004](./0004-dual-artifact-line.md) (how the service line
relates to the Python package line).

**Status note (2026-10-31).** When this record was accepted (2026-10-29), nothing in it had
shipped: the import was to run at cutover, after the core freeze, and only on the operator's
go-signal (spec constraint C-001). That has since happened:

- [#150](https://github.com/spec-kitty/spec-kitty-events/pull/150) (merged 2026-10-31,
  merge commit `f75022f`) imported the Mission Status Read API with its `domain-model` and
  `event-log` modules into `service/`, and `mission-status`, `mission-write`, `_shared`,
  `tools` and `lint` into `contracts/`, with their history, from core at the frozen SHA
  `512567eee71e31bb39f580c4f7acf8b865e9d1b2` (Decision Outcome 2). `service/core-pin.json`
  pins that SHA for the CI-only peers (Decision Outcome 4).
- The first service release, `service-v0.1.0-rc.1`, has the operator's approval of
  2026-10-31 (rulings on
  [spec-kitty/spec-kitty#6117](https://github.com/spec-kitty/spec-kitty/issues/6117#issuecomment-6544221340)).
  The operator pushes the tag.
- Not yet shipped: the write core's deployable, `command-cli`
  ([#156](https://github.com/spec-kitty/spec-kitty-events/issues/156)), and core's switch
  from its own copy of the services to the released assets (core's cut-over PR).

The decisions below are unchanged.

---

## Context and Problem Statement

The events domain is split across two repositories today.

- **Here:** the event envelope, the committed JSON Schemas, the conformance fixtures and the
  compatibility policy, with a Python binding (the PyPI package) and a Java binding under
  `java/` ([ADR-0001](./0001-cloudevents-envelope.md)).
- **In core:**
  - `services/mission-status-api/`: the Mission Status Read API service, with its
    `domain-model` and `event-log` libraries. Core
    [ADR 2026-10-01-2](https://github.com/spec-kitty/spec-kitty/blob/main/docs/adr/4.x/2026-10-01-2-mission-status-read-api-and-dashboard-extraction.md)
    created it and, in its D-10, planned to extract it "as needed, as an open-core surface (its
    own repository or package)" once its contract is stable.
  - `contracts/mission-status` and `contracts/mission-write`: the OpenAPI contracts of the
    read service and of the write service.
  - The write side. Core
    [ADR 2026-10-14-1](https://github.com/spec-kitty/spec-kitty/blob/main/docs/adr/4.x/2026-10-14-1-event-native-core-one-canonical-event-writer.md)
    puts one canonical event writer behind the CLI's EventBridge port. Core
    [ADR 2026-10-22-1](https://github.com/spec-kitty/spec-kitty/blob/main/docs/adr/4.x/2026-10-22-1-the-java-write-service-is-the-canonical-event-writer.md)
    makes that writer a resident Java service per repository that reuses the read API's
    domain models, with `command-cli` as its deployable. Core
    [ADR 2026-10-27-1](https://github.com/spec-kitty/spec-kitty/blob/main/docs/adr/4.x/2026-10-27-1-the-write-service-protocol-is-loopback-http-with-a-bearer-token.md)
    fixes the CLI-to-service protocol: loopback HTTP with a bearer token and a handshake that
    negotiates the protocol major.

So the contract of the events domain lives here, while its only reader service and its only
writer service, and their HTTP contracts, live in core. Each change to the envelope has to be
made here, released, and then carried into a service in another repository. Two
repositories hold two halves of one domain, and nothing but discipline keeps them in step.
That invites drift.

The charter repository met the same problem for the charter domain and settled it in
[spec-kitty-charter ADR-0015](https://github.com/spec-kitty/spec-kitty-charter/blob/main/docs/adr/0015-charter-repository-is-canonical-for-charter-structure.md):
the repository that owns the domain's contract is canonical for it, and consumers, core
included, adapt. This record makes the same argument for the events domain. It differs in
one point: the charter repository rewrote its service against the contract (its ADR-0008),
while the services here already exist and are tested in core, so they move with their git
history.

## Decision Drivers

- One owner per domain: the contract, its reader and its writer change in one repository
  and one review.
- The services already exist, are tested and carry a useful history (D2).
- Core keeps working during and after the move. It must not depend on a moving reference.
- The services never need core to build or run (spec constraint C-002).
- Nothing moves before the operator's go-signal (D7).

## Considered Options

1. **Keep the services in core.** No move, no new release line.
2. **A new standalone repository for the services.**
3. **Rewrite the services here from scratch,** as the charter repository did.
4. **Move the services here with their history (chosen).**

## Decision Outcome

**Chosen option:** 4. spec-kitty-events is canonical for the events domain and its services,
and the existing code moves here with its history.

1. **Ownership.** spec-kitty-events is canonical for the events domain. That covers:
   - the Python package;
   - the Java binding;
   - the Mission Status Read API service;
   - the Mission Write API / event-log write core (the future `command-cli` deployable);
   - their OpenAPI contracts, `contracts/mission-status` and `contracts/mission-write`.

   A change to any of these is made here and is canonical. Consumers, core included, adapt to
   it, as in spec-kitty-charter ADR-0015 Decision 2.
2. **Layout and import.** The services live under `service/` (from core
   `services/mission-status-api/`, with its `domain-model` and `event-log` modules) and the
   service contracts under `contracts/` (`mission-status`, `mission-write`, `_shared`,
   `tools`, `lint`, and their root build files) (D1). They are imported from core with
   history: `git filter-repo` on a clone of core at a frozen SHA, then a merge with
   `--allow-unrelated-histories`, with the source core SHA recorded in the merge commit
   message (D2). The import runs at cutover, after the core freeze (D7). The commands and
   the post-import path fixes are in the cutover runbook, not here.
3. **Core consumes released artifacts only.** Core pins a `service-v*` tag and a sha256 per
   asset. It fetches the binary on first run and verifies its digest; the Python wheel stays
   pure (D6). Core keeps the Python client (`write_service_protocol.py`), the EventBridge and
   the CLI launcher side. The read service is pinned in core for CI only. The asset names are
   fixed in [ADR-0003](./0003-gradle-build-and-service-release-line.md).
4. **Dependency direction.** The services never depend on core at build time or at run time.
   Test-only peers use a core SHA pinned in this repository and moved only by a reviewed PR:
   the lock-interop peer, the nightly corpus compare against core's Python oracle, and golden
   regeneration (D5). The pinned SHA never reaches a shipped artifact.
5. **Work moves with ownership.** Queued Java work on these services is retargeted to this
   repository: issues are transferred, or split into a core half and an events half. The
   issue table lives in the cutover runbook, not in this record. At cutover core issue
   [#6692](https://github.com/spec-kitty/spec-kitty/issues/6692) closes as done, with a link
   to this record (D8).

## Consequences

### Positive

- An envelope change and the reader and writer that apply it land in one PR, one review and
  one CI run.
- The conformance fixtures and the services that must pass them sit in one tree, so a
  fixture change fails the service build at once instead of after a cross-repo release.
- `git log --follow` and `git blame` keep working on the imported files.

### Negative

- This repository becomes polyglot: a Python package, Java libraries and services, and
  contracts. Contributors need JDK 25 and Gradle as well as Python
  ([ADR-0003](./0003-gradle-build-and-service-release-line.md)).
- Core loses direct edits to the services. A fix needed by core now waits for a
  `service-v*` release and a pin bump in core.
- Core ADR 2026-10-01-2 D-11 keeps `contracts/mission-status/` canonical in core beside the
  Python reducer. This record moves that authority here; core has to amend D-11 (see
  Requests to core).

### Risks

- **Cross-repository CI coupling through the core pin.** A stale pin hides a protocol or
  lock change in core; a bumped pin can break this CI for reasons outside this repository.
  Mitigation: the pin moves only by a reviewed PR that records the SHA, and the coupled jobs
  are test-only.
- **The freeze window.** A commit to the services in core after the frozen SHA would be
  lost. Mitigation: the runbook re-runs the extraction from the new SHA and never
  cherry-picks.
- **Two sources during the window.** Until core removes its copy, both trees exist.
  Mitigation: from cutover, core's copy is no longer edited, and core pins released assets.

## Alternatives considered

### Keep the services in core

Rejected. Domain ownership stays split: the contract here, its reader and writer there.
That is the drift this record removes.

### A new standalone repository for the services

Rejected. It separates the services from the event contract they implement and adds a
third repository to the events domain, with its own release and pin.

### Rewrite from scratch, as the charter repository did

Rejected. The code exists and is tested; a rewrite would spend that work again and lose the
history that D2 keeps.

## Revisit when

- A second consumer besides core needs the services on a different release cadence from
  the package or from core.

## Requests to core

- Amend core ADR 2026-10-01-2 D-10 and D-11 to name this repository as the extraction
  target and as the canonical home of `contracts/mission-status` and
  `contracts/mission-write`.
- Freeze `services/mission-status-api/` and the service contracts before cutover, and pin
  `service-v*` releases afterwards.

## References

- Core ADRs:
  [2026-10-01-2](https://github.com/spec-kitty/spec-kitty/blob/main/docs/adr/4.x/2026-10-01-2-mission-status-read-api-and-dashboard-extraction.md),
  [2026-10-14-1](https://github.com/spec-kitty/spec-kitty/blob/main/docs/adr/4.x/2026-10-14-1-event-native-core-one-canonical-event-writer.md),
  [2026-10-22-1](https://github.com/spec-kitty/spec-kitty/blob/main/docs/adr/4.x/2026-10-22-1-the-java-write-service-is-the-canonical-event-writer.md),
  [2026-10-27-1](https://github.com/spec-kitty/spec-kitty/blob/main/docs/adr/4.x/2026-10-27-1-the-write-service-protocol-is-loopback-http-with-a-bearer-token.md),
  and core's counterpart record
  [2026-10-29-1](https://github.com/spec-kitty/spec-kitty/blob/main/docs/adr/4.x/2026-10-29-1-the-events-domain-and-its-java-services-live-in-spec-kitty-events.md)
  (accepted in core PR spec-kitty/spec-kitty#6782; the link resolves once that PR merges).
- [spec-kitty-charter ADR-0015](https://github.com/spec-kitty/spec-kitty-charter/blob/main/docs/adr/0015-charter-repository-is-canonical-for-charter-structure.md).
- Mission artifacts: `kitty-specs/events-domain-services-home-01M64MSB/spec.md` (D1–D8),
  `research.md` (R-1, R-2, R-5, R-8), `data-model.md` (IssueMove).
