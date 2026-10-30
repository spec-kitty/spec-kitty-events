# Tasks: events domain services home

**Mission**: `events-domain-services-home-01M64MSB` | **Branch**: `issue-147-events-services-home` (PR → `main`) | **Issue**: #147
**Inputs**: [spec.md](./spec.md), [plan.md](./plan.md), [research.md](./research.md), [data-model.md](./data-model.md), [contracts/service-release-assets.md](./contracts/service-release-assets.md), [quickstart.md](./quickstart.md)

WP01–WP06 are pre-cutover and can be implemented now. WP07 and WP08 are cutover packages: they stay `planned` until the operator's go-signal (spec C-001), and WP08's tag also needs explicit operator approval.

## Subtask Index

| ID | Description | WP | Parallel |
|---|---|---|---|
| T001 | ADR 0002: events is canonical for the events domain and its services | WP01 | [P] |
| T002 | ADR 0003: Gradle build and `service-v*` release model | WP01 | [P] |
| T003 | ADR 0004: dual artifact line | WP01 | [P] |
| T004 | ADR index rows + doc checks | WP01 | |
| T005 | Locate charter sources; choose regenerate-safe edit path | WP02 | |
| T006 | Amend charter policy sections (languages, testing, gates, deployment, bindings) | WP02 | |
| T007 | Service ownership + Package Boundary test-only exception + doc conventions | WP02 | |
| T008 | Amendment-log row; CLAUDE.md/AGENTS.md Gradle commands | WP02 | |
| T009 | Record the Maven baseline (test count, wall time) | WP03 | |
| T010 | Root Gradle build: wrapper 9.8.1, settings, properties, version catalog | WP03 | |
| T011 | `build-logic` convention plugins | WP03 | |
| T012 | `:java-binding` module (resources in place, upcast.cases, lint flags) | WP03 | |
| T013 | Strict dependency verification metadata | WP03 | |
| T014 | Remove Maven; Makefile `test-java` + `ensure_jdk25.sh` on Gradle; parity check | WP03 | |
| T015 | `service.yml`: wrapper validation + `./gradlew check` now; guarded service jobs | WP04 | |
| T016 | Native-image matrix job (4 targets, GraalVM CE 25.0.2) | WP04 | |
| T017 | `scripts/release/service-release-assets.sh` (deterministic assets, SHA256SUMS) | WP04 | [P] |
| T018 | `service-release.yml`: tag gate, assemble, attest, publish | WP04 | |
| T019 | Workflow static checks: SHA-pin audit, YAML parse, PyPI tag isolation | WP04 | |
| T020 | Bump `@spec-kitty/doc-toolkit` to 0.5.0 + lockfile | WP05 | [P] |
| T021 | Services overview: C4 context/container + onion modules | WP05 | |
| T022 | Read/write CQRS split page | WP05 | |
| T023 | Event-log lock and append contract page | WP05 | |
| T024 | Write-service protocol page | WP05 | |
| T025 | Architecture index + doc checks + site build | WP05 | |
| T026 | Guide: running the service | WP06 | [P] |
| T027 | Guide: CLI↔service protocol and releases/pins | WP06 | [P] |
| T028 | Guide: service cutover runbook | WP06 | |
| T029 | Guides index, CHANGELOG + docs/changelog entry, doc checks | WP06 | |
| T030 | Frozen-SHA filter-repo extraction + unrelated-histories merge | WP07 | |
| T031 | Post-import path fixes + `contracts/README.md` merge | WP07 | |
| T032 | Join `service/` and `contracts/` to the root Gradle build | WP07 | |
| T033 | `service/core-pin.json`; enable guarded jobs; move core architecture doc | WP07 | |
| T034 | Full Java suite + Python gates green; record counts | WP07 | |
| T035 | Pre-release tag `service-v0.1.0-rc.1` (operator-approved) and run | WP08 | |
| T036 | Verify assets, sha256, attestations; record binary sizes | WP08 | |
| T037 | Handshake to the core session; issue moves per runbook (operator-approved) | WP08 | |

## Phase 1: Governance

### WP01: ADRs for events-domain service ownership (P1)
Prompt: [tasks/WP01-adrs-service-ownership.md](./tasks/WP01-adrs-service-ownership.md)
- **Goal**: three accepted-format ADRs (0002–0004) plus index rows (FR-001, FR-002, FR-003).
- **Independent test**: the doc-kitty frontmatter and link checks show 0 errors; the ADR index lists 0002–0004.
- **Subtasks**:
T001 ADR 0002: events is canonical for the events domain and its services (WP01)
T002 ADR 0003: Gradle build and service-v* release model (WP01)
T003 ADR 0004: dual artifact line (WP01)
T004 ADR index rows + doc checks (WP01)
- **Dependencies**: none. **Parallel**: with WP03 and WP05's toolkit bump.
- **Risks**: restating core ADRs instead of linking them; ADR format drift (follow `docs/adr/0001`).

### WP02: Charter amendment for Java services (P1)
Prompt: [tasks/WP02-charter-amendment.md](./tasks/WP02-charter-amendment.md)
- **Goal**: the charter and agent guides cover Java + Gradle, both release lines, service ownership, the Java test policy and doc conventions (FR-004).
- **Subtasks**:
T005 Locate charter sources; choose regenerate-safe edit path (WP02)
T006 Amend charter policy sections (WP02)
T007 Service ownership + Package Boundary test-only exception + doc conventions (WP02)
T008 Amendment-log row; CLAUDE.md/AGENTS.md Gradle commands (WP02)
- **Dependencies**: WP01 (cites ADR numbers).
- **Risks**: `spec-kitty charter generate` overwriting hand edits; scoping the core-pin exception too broadly.

## Phase 2: Build and CI

### WP03: Root Gradle build and Java binding migration (P1)
Prompt: [tasks/WP03-gradle-root-binding.md](./tasks/WP03-gradle-root-binding.md)
- **Goal**: one Gradle Kotlin DSL toolchain; the binding runs the same tests as under Maven (FR-005, NFR-002, NFR-003).
- **Subtasks**:
T009 Record the Maven baseline (WP03)
T010 Root Gradle build (WP03)
T011 build-logic convention plugins (WP03)
T012 :java-binding module (WP03)
T013 Strict dependency verification metadata (WP03)
T014 Remove Maven; Makefile + ensure_jdk25 on Gradle; parity check (WP03)
- **Dependencies**: none.
- **Risks**: losing `-Werror` or the `upcast.cases` property silently (the test count shows it); verification metadata missing a platform-specific artifact.

### WP04: Service CI and release workflows (P2)
Prompt: [tasks/WP04-service-ci-release.md](./tasks/WP04-service-ci-release.md)
- **Goal**: `service.yml` and `service-release.yml` with SHA-pinned actions, inert for service jobs until `service/` exists (FR-008, NFR-003, C-003, C-004).
- **Subtasks**:
T015 service.yml (WP04)
T016 Native-image matrix job (WP04)
T017 service-release-assets.sh (WP04)
T018 service-release.yml (WP04)
T019 Workflow static checks (WP04)
- **Dependencies**: WP03.
- **Risks**: a `v*` tag triggering service workflows or the reverse; unpinned actions; non-deterministic tarballs.

## Phase 3: Docs

### WP05: Docsite bump and service architecture docs (P2)
Prompt: [tasks/WP05-docsite-architecture.md](./tasks/WP05-docsite-architecture.md)
- **Goal**: doc-toolkit 0.5.0 and the architecture pages of User Story 4 (FR-006, FR-007, NFR-004).
- **Subtasks**:
T020 Bump doc-toolkit to 0.5.0 (WP05)
T021 Services overview (WP05)
T022 Read/write CQRS split (WP05)
T023 Event-log lock and append contract (WP05)
T024 Write-service protocol (WP05)
T025 Architecture index + checks + site build (WP05)
- **Dependencies**: WP01.
- **Risks**: docs describing behaviour the code does not have (cite core files and SHAs); npm registry auth for the build.

### WP06: Service usage guides, cutover runbook and changelog (P2)
Prompt: [tasks/WP06-guides-runbook-changelog.md](./tasks/WP06-guides-runbook-changelog.md)
- **Goal**: the usage guides, the cutover runbook (FR-009) and the changelog entries.
- **Subtasks**:
T026 Guide: running the service (WP06)
T027 Guide: CLI↔service protocol and releases/pins (WP06)
T028 Guide: service cutover runbook (WP06)
T029 Guides index, CHANGELOG, doc checks (WP06)
- **Dependencies**: WP01, WP03, WP04.
- **Risks**: a runbook that drifts from the dry run (re-run the dry run from it).

## Phase 4: Cutover (blocked until the operator's go-signal)

### WP07: History-preserving import and build integration (P1 at cutover)
Prompt: [tasks/WP07-cutover-import.md](./tasks/WP07-cutover-import.md)
- **Goal**: `service/` and `contracts/` imported with history and green under the root build (FR-010).
- **Subtasks**:
T030 Frozen-SHA extraction + merge (WP07)
T031 Path fixes + README merge (WP07)
T032 Root Gradle integration (WP07)
T033 core-pin.json, enable jobs, move the core architecture doc (WP07)
T034 Full suite green; record counts (WP07)
- **Dependencies**: WP03, WP04, WP06. **Blocked by**: C-001.

### WP08: First service pre-release and core handshake (P1 at cutover)
Prompt: [tasks/WP08-first-service-release.md](./tasks/WP08-first-service-release.md)
- **Goal**: the first `service-v0.x` pre-release with all assets, and the core session pinned (FR-011).
- **Subtasks**:
T035 Pre-release tag (operator-approved) (WP08)
T036 Verify assets, sha256, attestations; binary sizes (WP08)
T037 Handshake + issue moves per runbook (operator-approved) (WP08)
- **Dependencies**: WP07. **Blocked by**: C-001 and operator tag approval.

## Dependency graph

```
WP01 ─┬─> WP02
      ├─> WP05
      └─> WP06 <─ WP03 ─> WP04 ─> WP06
WP06 ─> WP07 ─> WP08   (cutover-gated)
```

MVP: WP01 + WP03 (governance and the one toolchain).
