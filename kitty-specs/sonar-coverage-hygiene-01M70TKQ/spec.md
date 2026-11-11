# Feature Specification: Sonar coverage and workflow hygiene

**Feature Branch**: `issue-238-sonar-coverage-hygiene`
**Created**: 2026-11-09
**Status**: Draft
**Input**: spec-kitty/spec-kitty-events#238 (mission slice M3), which closes #234 (JaCoCo XML for every Java module) and #235 (Sonar workflow minors from the #233 review). Context: PR #233 (merged as `20cc5031`, the informational nightly SonarCloud scan) and its adversarial review. Squad notes: `ci-release.md` and `synthesis.md` from the 2026-11-09 programme squad.

## Intent Summary

- **Primary actor:** the maintainer who reads the nightly SonarCloud analysis of `spec-kitty_spec-kitty-events`, and the maintainer who edits `.github/workflows/sonar.yml`.
- **Trigger:** the nightly `schedule` run, or a manual `workflow_dispatch` of `sonar.yml`.
- **Outcome:** the analysis imports one JaCoCo XML report for every Gradle project that has main Java sources, attributes non-zero coverage to `java/`, counts the packaged conformance tests as tests, and resolves library types from explicit Gradle classpaths. The workflow no longer scans after a cancel, no longer passes an unused token, and says in the step summary which build steps failed. A deeper workflow test pins these properties.
- **Invariant:** the scan stays informational. `sonar.yml` keeps only the `schedule` and `workflow_dispatch` triggers, `permissions: contents: read`, and `SONAR_TOKEN` on the guard, scan and gate steps alone. Every Gradle coverage gate (the `service` rules and `jacocoScopeCheck`, `command-cli`'s four rules, `event-log`'s complexity rule) is unchanged and still passes.
- **Boundary:** no `sonar.yml` trigger or required check is added. The Gradle sonar plugin (a new plugin dependency under strict dependency verification) is out of scope; explicit classpaths from Gradle come first. The "198 analysed files" figure in #234 could not be reproduced (56 main `.java` files in three modules). This mission does not promise a file count and rechecks once a main-branch SonarCloud run exists.

### Maintainer rulings that govern this spec

1. **D9 (2026-11-09): one PR closes #234 and #235.** This relaxes the one-issue-one-PR rule of AGENTS.md. #234 lands first inside the branch because #235's test assertions and classpath work reason about which reports exist.
2. **Source/test overlap.** `sonar.sources` must list `java/src/main/java`, never `java/src/`, which would cover `java/src/test/java` (also in `sonar.tests`). On `main` at `28b8f851` the properties file already holds `java/src/main/java`; this mission adds the test that pins it (no overlap) rather than editing the value.
3. **Report coverage.** JaCoCo XML covers every project with main sources, derived from `settings.gradle.kts` (not a fixed "all five"), including `:mission-status-api`. The wiring lives in `build-logic/src/main/kotlin/events.library.gradle.kts`. `domain-model` and `event-log` apply `events.service`, not `events.library`, so they need the same wiring through the shared convention.
4. **Conformance tests.** The packaged conformance tests are counted as tests with the glob `**/test_*.py` in `sonar.test.inclusions`. No other `test_*.py` file sits under the scanned sources, so the glob selects exactly the twelve `src/spec_kitty_events/conformance/` test files.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Every Java module reports coverage to Sonar (Priority: P1)

`./gradlew verifyAll` writes `build/reports/jacoco/test/jacocoTestReport.xml` in every Gradle project that has main sources: `:java-binding`, `:mission-status-api`, `:domain-model`, `:event-log` and `:command-cli`. The set is read from `settings.gradle.kts`, so a future module is covered without editing a list.

**Why this priority**: today only `service/` and `service/command-cli/` produce XML. `java/` has no coverage input at all, so Sonar shows it at zero.

**Independent Test**: build with `CONTRACT_BUNDLE` set (runbook §5), run `./gradlew verifyAll`, and list the XML files. A checked-in test derives the module set from `settings.gradle.kts` and checks every module resolves to a convention that enables the XML report and finalizes `test` with `jacocoTestReport`.

**Acceptance Scenarios**:

1. **Given** a clean build, **When** `verifyAll` finishes, **Then** a `jacocoTestReport.xml` exists for each project that has `src/main`.
2. **Given** the existing coverage gates, **When** `verifyAll` runs, **Then** they pass unchanged and `service/build.gradle.kts`'s aggregation of `domain-model` and `event-log` is untouched.
3. **Given** a new `include(...)` in `settings.gradle.kts` for a project with main sources and no coverage wiring, **When** the structure test runs, **Then** it fails and names the project.

---

### User Story 2 - The nightly run proves what it imported (Priority: P1)

The collect step lists the expected report of every module with main sources, warns for each one that is missing, and the step summary and the scan arguments list every report that exists. A dispatched run's log shows a report imported for every module.

**Why this priority**: the collect step today warns only when there are zero reports, so a module can silently lose attribution.

**Independent Test**: dispatch `sonar.yml` on the branch and read the log (acceptance criterion 4).

**Acceptance Scenarios**:

1. **Given** all modules built, **When** the collect step runs, **Then** the step summary lists five reports and the scan imports five JaCoCo reports.
2. **Given** a module's report is absent, **When** the collect step runs, **Then** a `::warning::` names that module and the scan still runs.

---

### User Story 3 - The workflow is hardened and says what failed (Priority: P2)

A cancelled run does not scan. The scan carries no token it does not use. A pytest or Gradle step that failed under `continue-on-error` shows in the step summary.

**Why this priority**: PR #233's review minors (#235 items 2, 3, 5). Low risk, small.

**Independent Test**: the workflow test (User Story 5).

**Acceptance Scenarios**:

1. **Given** `sonar.yml`, **When** it is parsed, **Then** the string `always()` appears nowhere and the collect, scan, gate and report steps use `!cancelled()`.
2. **Given** the scan step, **When** its `env` is read, **Then** it holds `SONAR_TOKEN` only.
3. **Given** the pytest, JDK, bundle or Gradle step failed, **When** the report step runs, **Then** the step summary lists each failed build step by name.

---

### User Story 4 - Conformance tests count as tests and types resolve (Priority: P2)

`sonar.test.inclusions=**/test_*.py` marks the packaged conformance tests as test code. The scan receives explicit library classpaths written by Gradle instead of the whole `modules-2` cache glob.

**Why this priority**: #235 items 1 and 4. The classpath is the largest piece and is verified against a real scan log.

**Independent Test**: dispatch the workflow; the log shows no unresolved-type warnings, or the PR records the agreed residue (acceptance criterion 5).

**Acceptance Scenarios**:

1. **Given** the properties file, **When** it is parsed, **Then** `sonar.test.inclusions` equals `**/test_*.py`.
2. **Given** a Gradle build, **When** the classpath task runs, **Then** each Java project writes its compile and test classpath to a file under `build/`, and the collect step joins the files into `sonar.java.libraries` and `sonar.java.test.libraries`, falling back to the `modules-2` glob only when no file exists.

---

### User Story 5 - The workflow test pins the hygiene (Priority: P1)

`tests/test_sonar_workflow.py` fails when the hygiene regresses, and derives the existing crons from the sibling workflow files.

**Why this priority**: without the test the fixes drift. The hard-coded `EXISTING_CRONS` already went stale once the programme added a workflow.

**Independent Test**: run the test; mutate each pinned property and see it fail.

**Acceptance Scenarios**:

1. **Given** the workflow files, **When** the test runs, **Then** it asserts: no `always()`; the scan step's env is `SONAR_TOKEN` only; permissions are `contents: read`; no `${{` inside any `run:`; `SONAR_TOKEN` is absent from every build step; `sonar.sources` and `sonar.tests` do not overlap (by path-prefix, not string equality); the sonar cron differs from every cron parsed from the other workflows.
2. **Given** a module list derived from `settings.gradle.kts`, **When** the test runs, **Then** it asserts the coverage wiring of every module with main sources.

### Edge Cases

- `:contracts` is a Gradle project without Java main sources; it needs no JaCoCo report and the derivation must not demand one.
- `service/`'s report is an aggregate that includes `domain-model` and `event-log`; per-module XML adds attribution and must not weaken any gate. Sonar merges coverage for a file reported twice.
- Strict Gradle dependency verification (`gradle/verification-metadata.xml`) must still pass; the change adds no dependency.
- A failed Gradle step means no classpath files; the collect step falls back and warns rather than failing the informational scan.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Every Gradle project with main sources, derived from `settings.gradle.kts` (including `:mission-status-api`), produces `build/reports/jacoco/test/jacocoTestReport.xml` under `verifyAll`, with the XML report enabled and `test` finalized by `jacocoTestReport`, wired in `build-logic/src/main/kotlin/events.library.gradle.kts` and, for the modules that apply `events.service`, through the shared convention.
- **FR-002**: Every existing coverage gate is unchanged and passes.
- **FR-003**: `sonar.yml`'s collect step derives the expected module list from `settings.gradle.kts`, warns for each missing report, and records the imported reports in the step summary.
- **FR-004**: `sonar.yml` uses `!cancelled()` instead of `always()` on the collect, scan, gate and report steps.
- **FR-005**: The scan step's `env` holds `SONAR_TOKEN` only (`GITHUB_TOKEN` removed).
- **FR-006**: The step summary lists each failed build step (pytest, JDK, bundle, Gradle).
- **FR-007**: `sonar.sources` lists `java/src/main/java` and no source root overlaps a test root; `sonar.test.inclusions` is `**/test_*.py`.
- **FR-008**: Gradle writes explicit per-project library classpaths; the scan consumes them for `sonar.java.libraries` and `sonar.java.test.libraries`, with the `modules-2` glob as the labelled fallback.
- **FR-009**: `tests/test_sonar_workflow.py` pins FR-004, FR-005, FR-007, the permissions, the no-`${{`-in-`run:` rule, the build-step token rule, the module-to-report wiring, and derives the existing crons from the other workflow files.
- **FR-010**: The docs (`docs/guides/contributing.md` Sonar section) and a changelog fragment describe the change.

### Non-Functional Requirements

- **NFR-001**: No new Gradle dependency; strict dependency verification stays green.
- **NFR-002**: Every action pin in `sonar.yml` stays a 40-hex SHA.
- **NFR-003**: New Python is typed (`mypy --strict` clean) and `ruff` clean.

### Key Entities

- **Module with main sources**: a project in `settings.gradle.kts` whose `projectDir` holds `src/main`.
- **Coverage report**: `<projectDir>/build/reports/jacoco/test/jacocoTestReport.xml`.

## Success Criteria *(mandatory)*

1. `verifyAll` produces a `jacocoTestReport.xml` for every project with main sources, derived from `settings.gradle.kts`.
2. The coverage gates are unchanged and pass.
3. A test asserts no `always()` in `sonar.yml`, that the scan's env is `SONAR_TOKEN` only, and that `sonar.sources` and `sonar.tests` do not overlap.
4. A dispatched run's log lists every report, shows a JaCoCo report imported for every Java module and a scan with no source/test overlap, and shows `java/` with non-zero coverage.
5. Unresolved-type warnings are gone, or an agreed residue is recorded in the PR.

## Assumptions

- `workflow_dispatch` can target the branch because `sonar.yml` already exists on `main`.
- The repository's `SONAR_TOKEN` secret authorizes analysis of this branch (it did for `issue-232-sonar-nightly`).
- Merging `jacocoTestReport` for `java/` needs no change to `java/build.gradle.kts` beyond what `events.library` provides.

## Out of Scope

- The Gradle `sonarqube` plugin.
- Changing any coverage floor or rule.
- Release-protection work (#221) and the other M-slices.
