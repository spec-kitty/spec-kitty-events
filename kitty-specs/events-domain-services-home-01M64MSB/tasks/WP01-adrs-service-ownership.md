---
work_package_id: WP01
title: ADRs for events-domain service ownership
dependencies: []
requirement_refs:
- FR-001
- FR-002
- FR-003
planning_base_branch: issue-147-events-services-home
merge_target_branch: issue-147-events-services-home
branch_strategy: Planning artifacts for this mission were generated on issue-147-events-services-home. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into issue-147-events-services-home unless the human explicitly redirects the landing branch.
subtasks:
- T001
- T002
- T003
- T004
history:
- timestamp: '2026-10-29T20:40:00Z'
  lane: planned
  agent: system
  action: Prompt generated via /spec-kitty.tasks
agent_profile: architect-alphonso
authoritative_surface: docs/adr/
create_intent:
- docs/adr/0002-events-domain-owns-its-services.md
- docs/adr/0003-gradle-build-and-service-release-line.md
- docs/adr/0004-dual-artifact-line.md
execution_mode: planning_artifact
owned_files:
- docs/adr/0002-events-domain-owns-its-services.md
- docs/adr/0003-gradle-build-and-service-release-line.md
- docs/adr/0004-dual-artifact-line.md
- docs/adr/README.md
role: implementer
tags: []
tracker_refs: []
---
# Work Package Prompt: WP01 – ADRs for events-domain service ownership

## ⚡ Do This First: Load Agent Profile

Load your assigned profile before reading anything else: `/spk-charter-profile-load architect-alphonso`
(or `spec-kitty agent profile show architect-alphonso`). Adopt its identity, scope and boundaries.

## Branch Strategy

The planning base and the merge target are both `issue-147-events-services-home` (single_branch topology). The PR targets `main`.
The execution workspace comes from `lanes.json`. Implement with
`spec-kitty agent action implement WP01 --agent claude --mission events-domain-services-home-01M64MSB`.

## Context

Read these first:
- In the mission folder: `../spec.md` (decisions D1–D8), `../plan.md`, `../research.md` (R-1…R-8) and `../contracts/service-release-assets.md`.
- In this repo: the format to copy, `docs/adr/0001-cloudevents-envelope.md` (frontmatter: title, description ≤180 chars, doc_status, updated, type: ADR, kind: ADR, authors; body: **Status/Date/Deciders/Reader/Technical Story**, then Context and Problem Statement, Decision Drivers, Considered Options, Decision Outcome, Consequences); and the index `docs/adr/README.md`.
- The precedent, read-only at `/home/user/spec-kitty/spec-kitty-charter/docs/adr/`: `0015-*` (canonical ownership), `0001-*`, `0008-*`, `0010-*`, `0011-*`. Model the ownership argument on 0015, but this repo's ADRs **move code with history**, whereas the charter repo rewrote it.
- Core ADRs, read-only in `/home/user/spec-kitty/spec-kitty/docs/adr/4.x/`:
  - `2026-10-01-2-mission-status-read-api-and-dashboard-extraction.md`
  - `2026-10-14-1-event-native-core-one-canonical-event-writer.md`
  - `2026-10-22-1-the-java-write-service-is-the-canonical-event-writer.md`
  - `2026-10-27-1-the-write-service-protocol-is-loopback-http-with-a-bearer-token.md`

  Link them with absolute GitHub URLs (`https://github.com/spec-kitty/spec-kitty/blob/main/docs/adr/4.x/<file>`).

**Deciders for all three ADRs**: operator stijn-dejongh with the Spec Kitty CEO, 2026-10-29. Status `Accepted`, date 2026-10-29. Technical Story: [#147](https://github.com/spec-kitty/spec-kitty-events/issues/147). Authors: `human:stijn`.

## Subtasks

### T001 ADR 0002: events is canonical for the events domain and its services
File: `docs/adr/0002-events-domain-owns-its-services.md`.

Context to state:
- Core owns `services/mission-status-api/` (the read service plus the `domain-model` and `event-log` libraries) and `contracts/mission-status`, `contracts/mission-write`. Core's 2026-10-22-1 makes the Java write service the canonical event writer.
- The events domain (envelope, schemas, fixtures) already lives here.
- Splitting the domain's contract from its reader and writer across two repos invites drift. That is the same argument charter ADR-0015 made for the charter domain.

Decisions, numbered with a bold lead:
1. spec-kitty-events is canonical for the events domain: the Python package, the Java binding, the Mission Status Read API service, the Mission Write API / event-log write core (the future `command-cli` deployable), and their OpenAPI contracts (`contracts/mission-status`, `contracts/mission-write`).
2. Layout `service/` + `contracts/`, imported from core with history (filter-repo, unrelated-histories merge, source core SHA recorded in the merge commit). The import runs at cutover, after the core freeze.
3. Core consumes only released artifacts: it pins a `service-v*` tag plus a sha256 per asset and fetches the binary on first run with digest verification. Core keeps the Python client (`write_service_protocol.py`), the EventBridge and the CLI launcher side. The read service is pinned in core for CI only.
4. Dependency direction: the services never depend on core at build or run time. Test-only peers (lock interop, corpus oracle, golden regeneration) use a pinned core SHA (D5).
5. Work moves with ownership. Queued Java work is retargeted here (the issue table lives in the runbook, not in the ADR). Core issue #6692 closes as done and links to this ADR.

Also include:
- **Consequences**: Positive / Negative / Risks. Risks include cross-repo CI coupling via the core pin and the freeze window.
- **Alternatives**:
  - keep the services in core (rejected: split domain ownership)
  - a new standalone service repo (rejected: separates the services from the event contract they implement)
  - rewrite from scratch, as the charter repo did (rejected: the code already exists and is tested; history is wanted, D2)
- **Revisit when**: a second consumer besides core needs a different release cadence.

### T002 ADR 0003: Gradle build and `service-v*` release model
File: `docs/adr/0003-gradle-build-and-service-release-line.md`. Decisions:
1. **Build**: Gradle with the Kotlin DSL is the single Java toolchain. A root build at the repo root has an included `build-logic/` of convention plugins, a version catalog, strict dependency verification, wrapper 9.8.1 and JDK 25. The Maven binding migrates (WP03).
2. **Native image**: GraalVM CE 25 builds 4 targets (linux-x64, linux-arm64, macos-arm64, windows-x64). Reachability hints live in code plus Spring AOT; there are no hand-written reflect-config files.
3. **Release**: tags `service-vX.Y.Z[-pre]` must be ancestors of `main`. The publish job verifies the sha256 sums, attests build provenance, and creates the GitHub release. The asset names are those in `kitty-specs/events-domain-services-home-01M64MSB/contracts/service-release-assets.md`; copy the table into the ADR, because the ADR is the durable home. Names are stable and only `<ver>` changes; renaming one needs a new ADR.
4. **Contract releases**: core's `contract-<module>-v*` tag line retires. Contract bundles and contract tools ride on `service-v*`.
5. **Precedent note**: the charter repo has no native or attestation pipeline. The native and attestation design comes from core's `mission-status-api.yml`; the tag→ancestor→verify→publish gate comes from the charter's `contract-release.yml`.

Alternatives: Maven (rejected, D3), a jlink JVM-only distribution (rejected: native-first per core 2026-10-22-1), and a separate contracts tag line (rejected, D4).

### T003 ADR 0004: dual artifact line
File: `docs/adr/0004-dual-artifact-line.md`.

Decisions:
1. There are two independent release lines:
   - the Python package on PyPI, tag `vX.Y.Z` (unchanged, `publish-pypi.yml`)
   - the services, tag `service-vX.Y.Z` (`service-release.yml`)

   Neither tag pattern matches the other.
2. Versions are independent. A service release states which package version's schemas and fixtures it was built and tested against (in the release notes).
3. The Java **binding** (`java/`, ai.spec-kitty:spec-kitty-events-java) is versioned with the Python package, because it is a binding of the same contract (charter Language Bindings). It is not published yet.
4. Breaking changes: package SemVer is unchanged. Service SemVer covers the HTTP contracts and the asset contract. A breaking `mission-write` protocol change needs a service major and core's handshake version negotiation (core 2026-10-27-1).

Consequences must include how core pins both lines: the PyPI or SHA pin per the programme exception, plus the service tag + sha256.

### T004 Index + checks
- Add rows 0002–0004 to `docs/adr/README.md` (ID link, title, `Accepted`, 2026-10-29) and bump its `updated`.
- Run, from the repo root (doc-kitty is cloned at `/home/user/spec-kitty/doc-kitty`):
  - `node /home/user/spec-kitty/doc-kitty/src/scripts/validate-frontmatter.mjs docs`
  - `node /home/user/spec-kitty/doc-kitty/src/scripts/check-links.mjs docs`

  Both must report 0 errors. If the scripts need `npm ci` in doc-kitty, run it there; it is not part of this repo.
- Check that every `description` is ≤180 characters.

## Definition of Done
- The three ADRs exist in the 0001 format with status Accepted, and each cross-links the 4 core ADRs. 0002 also links the charter ADR-0015.
- The index is updated, and both doc checks show 0 errors (paste the output in the review hand-off).
- There is no change outside `owned_files`.

## Reviewer guidance
- Check that every decision traces to spec D1–D8, with nothing invented.
- Check that no ADR states something has shipped (the import is at cutover).
- Check that the asset table in 0003 matches the contract file exactly.
