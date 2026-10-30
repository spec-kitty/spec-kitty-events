# Implementation Plan: events domain services home

**Branch**: `issue-147-events-services-home` (PR targets `main`) | **Date**: 2026-10-29 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `kitty-specs/events-domain-services-home-01M64MSB/spec.md`

## Summary

Make spec-kitty-events ready to own the events-domain Java services that move out of core, and settle every decision that cutover depends on. The Mission Status Read API and the Mission Write API / event-log write core are the services in question.

What happens now:
- Write governance: three ADRs and a charter amendment.
- Build one Gradle toolchain and move the existing `java/` binding onto it.
- Bump the docsite to doc-toolkit 0.5.0 and add the service docs.
- Design the service CI and the `service-v*` release workflow, both inert until `service/` exists.
- Write a cutover runbook covering the history-preserving import and the issue moves.

The import itself, and the first `service-v0.x` pre-release, run only at the operator's cutover go-signal (spec C-001).

## Engineering Alignment

These defaults follow from the operator decisions D1–D8 recorded in spec.md.
- **Gradle root build**: `settings.gradle.kts` at the repo root.
  - `build-logic/` is an included build of convention plugins, following the charter repo's pattern: `events.java-base` (toolchain 25, `-Xlint:all -Werror` where the module opts in, JUnit Platform) and `events.library`.
  - The version catalog lives at `gradle/libs.versions.toml`.
  - Dependency verification is strict, via `gradle/verification-metadata.xml`.
  - Wrapper Gradle 9.8.1 (sha256-pinned), the same version as spec-kitty-charter.
  - `org.gradle.java.installations.auto-download=false`, so CI provides the JDK.
- **Binding module**: `:java-binding` with `projectDir = file("java")`. The directory stays put, so docs and links keep working. The artifact coordinates stay `ai.spec-kitty:spec-kitty-events-java`, and the version stays in lockstep with `pyproject.toml` (11.3.0).
- **Service modules at cutover**: `:mission-status-api` (`service/`), `:domain-model` (`service/domain-model`), `:event-log` (`service/event-log`). `contracts/` joins the same root build as `:contracts` at cutover (its Groovy `build.gradle` stays), so `verifyAll` covers its checks (ADR 0003). The service's per-module toolchain blocks fold into `build-logic` conventions at cutover, not before.
- **Workflows**:
  - `service.yml` uses path filters on `service/**`, `contracts/**`, `java/**`, `build-logic/**`, `gradle/**`, `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradlew*`, `src/spec_kitty_events/producer_contract/**` and the workflow itself.
  - Its service jobs are guarded by a `detect` job output (`service/build.gradle.kts` exists), so they no-op until the import; `hashFiles` cannot be used in a job-level `if`.
  - The binding's Gradle job runs now.
  - `service-release.yml` triggers only on `service-v*`.
- **Core pin**: `service/core-pin.json` (`{"repository": "spec-kitty/spec-kitty", "sha": "<40-hex>"}`) is added at cutover and read by the lock-interop and corpus jobs.

## Technical Context

**Language/Version**: Java 25 (Temurin 25.0.5 locally; GraalVM CE 25.0.2 for native-image in CI); Python 3.10+ (unchanged package); Node 22 (docsite)
**Primary Dependencies**:
- Gradle 9.8.1 (Kotlin DSL)
- for the binding: Jackson 3 (`tools.jackson.core:jackson-databind` 3.2.4), networknt json-schema-validator 3.0.8, JUnit 6.1.3
- for the service, at cutover: Spring Boot 4.1.1, ArchUnit, GraalVM Native Build Tools
- `@spec-kitty/doc-toolkit` 0.5.0
**Storage**: N/A. The event log is file-based and is part of the imported service.
**Testing**:
- `./gradlew check`: the binding's UpcastCorpusTest, about 366 tests; at cutover, the service's JUnit, ArchUnit and contract tests, about 950.
- `make test-fast`
- `uv run pytest --pyargs spec_kitty_events.conformance`
- ruff
- doc-kitty `validate-frontmatter`, `check-links` and the site build
**Target Platform**: library plus native service binaries for linux-x64, linux-arm64, macos-arm64 and windows-x64
**Project Type**: polyglot single repository: Python package, Java libraries/services, contracts, docsite
**Performance Goals**: the Gradle binding test run takes at most as long as the Maven baseline + 20% on the same machine
**Constraints**:
- no code import, tag or issue transfer before the go-signal
- the Python line is untouched
- every action is SHA-pinned
- asset names are stable
**Scale/Scope**:
- now: about 10 files of build config, 3 ADRs, the charter, about 6 doc pages, 2 workflows and a runbook
- at cutover: about 1,900 files and 141 imported commits

## Charter Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Charter rule | Status | Note |
|---|---|---|
| Testing Standards / Quality Gates (pytest, schema checks, mypy --strict) | Pass | Python line untouched (NFR-001). The amendment adds Gradle `check` as a gate. |
| Language Bindings: every binding passes the same fixtures | Pass | The binding still reads `src/spec_kitty_events/producer_contract` (NFR-002). |
| Package Boundary: "should not import or depend on `spec-kitty`" | **Tension, resolved by amendment** | Decision D5 makes events CI check out core at a pinned SHA for **test-only** peers (lock interop, corpus oracle, golden regeneration). The amendment scopes an explicit exception: CI-only and SHA-pinned, with no build/runtime/packaging dependency. Shipped artifacts never depend on core (spec C-002). |
| Package Boundary: consumers use released artifacts, no moving refs | Pass | Core pins `service-v*` tag + sha256. |
| Directive 1: no silent aliases or live-path mutation without a cutover decision | Pass | No live path changes in this mission. Cutover is operator-gated. |
| Directive 2: docs stay synchronized | Pass | FR-006 and FR-007. |
| Amendment Process: PR review plus a log row | Pass | FR-004 follows it. |

Re-check after design: no new violations. The Package Boundary exception is the only justified deviation (see Complexity Tracking).

## Project Structure

### Documentation (this feature)

```
kitty-specs/events-domain-services-home-01M64MSB/
├── spec.md
├── plan.md            # this file
├── research.md        # Phase 0
├── data-model.md      # Phase 1: release assets, pins, issue moves
├── quickstart.md      # Phase 1: how to build and verify
├── contracts/         # Phase 1: service release asset manifest contract
└── tasks.md           # /spec-kitty.tasks
```

### Source Code (repository root)

```
settings.gradle.kts            # NEW root build (binding now; service + contracts at cutover)
build.gradle.kts               # NEW aggregate verifyAll
gradle.properties              # NEW
gradle/libs.versions.toml      # NEW
gradle/verification-metadata.xml  # NEW
gradle/wrapper/                # NEW (9.8.1, sha256)
gradlew, gradlew.bat           # NEW
build-logic/                   # NEW convention plugins
java/                          # binding sources stay; pom.xml, mvnw*, .mvn removed
service/                       # AT CUTOVER (filter-repo import)
contracts/                     # existing dir; mission-status/mission-write/... AT CUTOVER
.github/workflows/service.yml          # NEW
.github/workflows/service-release.yml  # NEW
docs/adr/0002-*.md, 0003-*.md, 0004-*.md  # NEW
docs/architecture/services/*.md, docs/guides/service-*.md  # NEW
docs/guides/cutover-runbook.md  # NEW (or kitty-specs runbook; see research R-6)
site/package.json, site/package-lock.json  # doc-toolkit 0.5.0
.kittify/charter/charter.md (+ charter.yaml)  # amendment
CLAUDE.md, AGENTS.md, Makefile, scripts/ensure_jdk25.sh  # Gradle commands
```

**Structure Decision**: polyglot single repo, with one root Gradle build that the Java artifacts join. Python stays under `src/` and `tests/`. Note: `contracts/` already exists in events, holding the Python package's contract material. Research R-1 settles the coexistence with the imported core `contracts/` subtree.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|---|---|---|
| CI-only checkout of core at a pinned SHA | The lock-interop test proves that the Java and Python appenders agree on the lock protocol. The corpus compare uses core's Python oracle. | Vendoring the peer scripts would let them drift from the real core appender (operator rejected this, D5). |
| A root Gradle build next to the Python build | D3 requires one toolchain for every Java artifact. | Keeping per-directory standalone builds means several wrappers and catalogs; that was the reason to unify. |

## Parallel Work Analysis

### Dependency Graph

```
WP01 ADRs + charter ─┐
WP02 Gradle root + binding ──> WP03 service CI + release workflows
WP04 docsite bump + service docs (cites WP01 ADRs)
WP05 cutover runbook (cites WP01-WP04)
--- go-signal ---
WP06 history import + build integration (blocked: C-001)
WP07 first service-v0.x pre-release + core handshake (blocked: C-001, operator tag approval)
```

### Work Distribution

- **Sequential**: WP02 before WP03 (the workflows call the root wrapper). WP05 last among the "now" packages.
- **Parallel**: WP01, WP02 and WP04 touch disjoint files.
- **Ownership**:
  - WP01: `docs/adr/`, `.kittify/charter/`
  - WP02: Gradle files, `java/`, `Makefile`, `scripts/ensure_jdk25.sh`, CLAUDE.md/AGENTS.md command tables
  - WP03: `.github/workflows/service*.yml`
  - WP04: `site/`, `docs/architecture/`, `docs/guides/`
  - WP05: the runbook

### Coordination Points

- Every WP keeps `make test-fast`, the conformance gate and ruff green.
- WP04 runs the doc checks after WP01 lands, because the ADRs are under `docs/`.
