# Feature Specification: events domain services home

**Feature Branch**: `issue-147-events-services-home`
**Created**: 2026-10-29
**Status**: Draft
**Input**: Operator decision (stijn-dejongh with the Spec Kitty CEO, 2026-10-29). The Java event-handling services move out of `spec-kitty/spec-kitty` core and into `spec-kitty/spec-kitty-events`, which becomes the canonical root and owner of the events domain. These services are the Mission Status Read API and the Mission Write API / event-log write core. This mirrors the extraction of the charter domain into `spec-kitty/spec-kitty-charter`. Tracking issue: #147.

## Intent Summary (confirmed with the operator, 2026-10-29)

- **Primary actors:**
  - the operator and maintainers of spec-kitty-events
  - core (`spec-kitty/spec-kitty`), which consumes the released services and contracts
  - the core-side session that pins releases
- **Trigger:** the operator decided the events domain owns its Java services. Active core missions finish first, and then a freeze gate lands in core.
- **Desired outcome:**
  - spec-kitty-events holds the service code with its git history, the contracts, one Gradle build, CI, and a `service-v*` release line that publishes native binaries.
  - Its governance (ADRs, charter) and docs state that it owns these services.
  - Core consumes the services only by pinning a released tag and sha256.
- **Rules / invariants:**
  - The Python package and its packaged conformance gate keep working without change.
  - The service never depends on core Python at build or compile time. Test-only couplings go through a pinned core SHA.
  - Release asset names stay stable across releases; only `<ver>` changes.
  - Nothing is imported, tagged or transferred before the operator gives the cutover go-signal.
- **Canonical terms:**
  - *service release line* (`service-vX.Y.Z`) versus *package release line* (PyPI `vX.Y.Z`)
  - *deployable* (`mission-status-api` now; `command-cli` later)
  - *cutover*: the moment the import runs from a frozen core SHA

Confirmed operator decisions:
- **D1 Layout:** `service/` (imported from core `services/mission-status-api/`, with its `domain-model` and `event-log` modules) and `contracts/` (`mission-status`, `mission-write`, `_shared`, `tools`, `lint`, plus their root build files).
- **D2 History:** history is preserved with `git filter-repo` and merged with `--allow-unrelated-histories`. The source core SHA is recorded.
- **D3 Build:** Gradle with the Kotlin DSL is the single toolchain. The Maven `java/` binding (#92) migrates to it.
- **D4 Releases:** there are two release lines, PyPI `v*` and `service-v*`. Contract bundles ride on `service-v*`, and core's `contract-<module>-v*` line retires.
- **D5 Coupling:** events CI pins a core SHA for the lock-interop peer, the nightly corpus compare and golden regeneration.
- **D6 Core shipping:** core fetches the binary on first run and verifies its digest. The wheel stays pure. The read service is pinned in core for CI only. `acceptance-jvm` fetches `contract-tools-<ver>.tar.gz`.
- **D7 Phasing:** drafts happen now. The import and the issue transfers wait for the cutover go-signal.
- **D8 Issue #6692:** at cutover, close core #6692 as done and link it to this repo's ownership ADR.

## User Scenarios & Testing *(mandatory)*

### User Story 1: Governance states the events repo owns its services (Priority: P1)

A maintainer reads the ADRs and charter of spec-kitty-events and learns three things: this repo is canonical for the events domain and its Java services, how those services are built and released, and how the two release lines relate.

**Why this priority**: every later change (import, CI, issue moves) cites these records. Without them, core and events disagree about who owns what.

**Independent Test**: The ADR index lists the new records. The charter's amendment log has a dated row. Doc-kitty's frontmatter and link checks pass.

**Acceptance Scenarios**:

1. **Given** the docs site, **When** a reader opens the ADR index, **Then** it lists an ADR making events canonical for the events domain and its services, an ADR on the build and service release model, and an ADR on the dual artifact line. Each one cross-links core ADRs 2026-10-01-2, 2026-10-14-1, 2026-10-22-1 and 2026-10-27-1.
2. **Given** the charter, **When** a contributor checks languages, testing, quality gates and deployment constraints, **Then** the charter names Java 25 and Gradle, the Java test policy, and both release lines.

---

### User Story 2: One Gradle toolchain (Priority: P1)

A contributor builds and tests every Java artifact in the repo with one Gradle wrapper. The existing Java binding is included.

**Why this priority**: D3 is binding, and the import lands into this build.

**Independent Test**: `./gradlew check` from the repo root runs the binding's upcast corpus tests green. No Maven files remain. `make test-java` invokes Gradle.

**Acceptance Scenarios**:

1. **Given** a clean checkout with JDK 25, **When** `./gradlew check` runs, **Then** the binding's tests pass with the same case count as under Maven.
2. **Given** the Python package, **When** `uv run pytest --pyargs spec_kitty_events.conformance` runs, **Then** it passes unchanged.

---

### User Story 3: Service CI and the release line are designed and ready (Priority: P2)

A maintainer can read the service CI design and the `service-v*` release workflow before the code arrives. The workflow builds, tests and checks contracts. It produces per-OS native binaries and publishes a release with stable asset names, sha256 files and provenance.

**Why this priority**: core's pin schema and session B depend on the asset names. The workflow can only run green after the import.

**Independent Test**: the workflow files parse and use SHA-pinned actions only. They are inert until `service/` exists (path filters or a guard). The design doc lists every asset name.

**Acceptance Scenarios**:

1. **Given** a pushed tag `service-vX.Y.Z` that is an ancestor of `main`, **When** the release workflow runs, **Then** it publishes these assets, each listed in `SHA256SUMS` and covered by a build-provenance attestation:
   - `mission-status-api-<ver>-{linux-x64,linux-arm64,macos-arm64,windows-x64.exe}`
   - `mission-status-api-<ver>.jar`
   - `contract-bundles-<ver>.tar.gz`
   - `contract-tools-<ver>.tar.gz`
   - `conformance-fixtures-<ver>.tar.gz`
   - a `.sha256` for each asset
2. **Given** a tag `vX.Y.Z`, **When** it is pushed, **Then** only `publish-pypi.yml` runs and the service workflows do not.

---

### User Story 4: Docs describe the services (Priority: P2)

A reader of the docs site finds the service architecture and the usage guides:
- **Architecture:** C4/onion, the read/write CQRS split, the event-log lock and append contract, and the write-service protocol.
- **Usage:** running the service, the CLI↔service protocol, and releases and pins.

**Independent Test**: the doc-kitty frontmatter validator, the link checker and the site build pass on `@spec-kitty/doc-toolkit` 0.5.0.

**Acceptance Scenarios**:

1. **Given** `site/package.json`, **When** it is inspected, **Then** it pins `@spec-kitty/doc-toolkit` 0.5.0 and the lockfile resolves 0.5.0.

---

### User Story 5: Cutover is a runbook, not an improvisation (Priority: P2)

At the go-signal, a maintainer runs the history-preserving import and the issue moves from a written runbook. Before the go-signal they verify the dry run in a scratch clone.

**Independent Test**: the runbook's dry-run commands reproduce 141 commits with a content-identical tree for the frozen SHA used in planning (core `4eecb124`).

**Acceptance Scenarios**:

1. **Given** a frozen core SHA, **When** the runbook's filter-repo pass runs, **Then** `service/` and `contracts/` match core's trees at that SHA byte for byte, and the merge commit message records the SHA.
2. **Given** the issue list, **When** cutover runs, **Then** each core issue is transferred, split or closed as the runbook says. Nothing moves before operator approval.

### Edge Cases

- The core freeze is breached after the frozen SHA. The runbook re-runs the extraction from the new SHA. It never cherry-picks.
- A transferred issue loses labels that do not exist in events. The matching labels are created first.
- A `service-v*` tag points at a commit that is not on `main`. The release workflow refuses it.
- A native binary is larger than expected. Sizes are recorded on the first build; with the first-run fetch (D6) there is no PyPI size limit.

## Requirements *(mandatory)*

### Functional Requirements

| ID | Requirement | Status | Delivery |
|---|---|---|---|
| FR-001 | ADR 0002 records that spec-kitty-events is canonical for the events domain and its Java services (read and write). It is modelled on charter ADR-0015 and cross-links core ADRs 2026-10-01-2, 2026-10-14-1, 2026-10-22-1 and 2026-10-27-1. | Draft | [now] |
| FR-002 | ADR 0003 records the build and service release model: Gradle Kotlin DSL, JDK 25, GraalVM native image for 4 targets, `service-v*` tags, the stable asset names of D4/D6, sha256 files and attestation. | Draft | [now] |
| FR-003 | ADR 0004 records the dual artifact line: the PyPI package and the `service-v*` services are versioned independently, the Java binding is versioned with the package, and core pins tag + sha256. | Draft | [now] |
| FR-004 | The charter is amended through its own process (charter.yaml/interview, regenerate, amendment-log row) to cover Java + Gradle, both release lines, service ownership, the Java test policy and doc conventions. CLAUDE.md and AGENTS.md list the Gradle commands. | Draft | [now] |
| FR-005 | The `java/` Maven binding is built by a root Gradle Kotlin DSL build (wrapper, version catalog, strict dependency verification, `build-logic` conventions). Its test behaviour and case count are unchanged. Maven files are removed and `make test-java` uses Gradle. | Draft | [now] |
| FR-006 | `@spec-kitty/doc-toolkit` is bumped to 0.5.0 and `docs-site.yml` keeps building. | Draft | [now] |
| FR-007 | Service architecture docs and usage docs (User Story 4) are added. They include the moved core material: `event-ledger-writers-and-readers.md`, the mission-status-api README, and lock-interop-spike. | Draft | [now] |
| FR-008 | `service.yml` (build, test, contract checks, lock interop at a pinned core SHA, native matrix) and `service-release.yml` (triggered by `service-v*`) are added with SHA-pinned actions only. They are inert until `service/` exists. | Draft | [now] |
| FR-009 | A cutover runbook covers the frozen-SHA filter-repo commands, the path fixes for `service/`, the core pin file, the label pre-creation and the issue table (transfer/split/close), and the session-B handshake. | Draft | [now] |
| FR-010 | At cutover, `service/` and `contracts/` are imported with history and join the root Gradle build. Their ArchUnit and contract conformance tests pass. | Draft | [cutover] |
| FR-011 | At cutover, the first `service-v0.x` pre-release tag, with operator approval, publishes the assets of FR-002. The asset names and sha256 sums are sent to the core session. | Draft | [cutover] |

### Non-Functional Requirements

| ID | Requirement | Threshold | Status |
|---|---|---|---|
| NFR-001 | Python line untouched | `uv run pytest --pyargs spec_kitty_events.conformance`, `make test-fast` and `ruff check . && ruff format --check .` pass with 0 failures and no change under `src/spec_kitty_events/`, apart from version metadata if needed. | Draft |
| NFR-002 | Binding parity | The Gradle run of the binding executes the same number of tests as the Maven run (± 0), and all pass. | Draft |
| NFR-003 | Supply chain | 100% of `uses:` lines in new or changed workflows are pinned to a 40-character commit SHA. Gradle dependency verification is strict. | Draft |
| NFR-004 | Docs validity | The doc-kitty frontmatter validator and link checker report 0 errors on `docs/`. | Draft |

### Constraints

| ID | Constraint | Status |
|---|---|---|
| C-001 | No code import, tag push, issue transfer or layout-changing PR before the operator's cutover go-signal (D7). | Active |
| C-002 | The service build must not depend on core Python. Test-only couplings use a pinned core SHA (D5). | Active |
| C-003 | Asset names are stable across releases; only `<ver>` changes (D6). | Active |
| C-004 | The PyPI `v*` tag pattern and `publish-pypi.yml` remain separate from the service line. | Active |
| C-005 | Programme process: one issue (#147), one branch, a PR to `main`. Never push `main`; the operator merges. | Active |

### Key Entities

- **Deployable**: a released service binary family (`mission-status-api`, later `command-cli`).
- **Service release**: a `service-vX.Y.Z` tag and GitHub release with stable asset names.
- **Core pin**: a frozen core SHA that events CI uses for the test-only Python peers.
- **Contract bundle / contract tools / conformance fixtures**: release tarballs that core consumes.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: The 3 ADRs and the charter amendment are merged, and doc checks show 0 errors.
- **SC-002**: `./gradlew check` is green with the binding's full case count, and the Maven toolchain is gone.
- **SC-003**: The cutover runbook dry run reproduces 141 commits and content-identical trees for core `4eecb124`.
- **SC-004**: After cutover, a `service-v0.x` pre-release exposes every listed asset with matching sha256 sums, and core pins it.
