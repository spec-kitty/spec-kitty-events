# Implementation Plan: Sonar coverage and workflow hygiene

**Mission**: `sonar-coverage-hygiene-01M70TKQ` | **Branch**: `issue-238-sonar-coverage-hygiene` | **Date**: 2026-11-09 | **Spec**: [spec.md](./spec.md)

## Summary

Make `verifyAll` write a JaCoCo XML report in every Gradle project with main sources, harden `sonar.yml` and `sonar-project.properties`, resolve Java library types from explicit Gradle classpaths, and pin all of it in `tests/test_sonar_workflow.py`. One PR closes #234 and #235 (ruling D9). Three work packages.

## Technical Context

- **Build**: Gradle Kotlin DSL, convention plugins under `build-logic/src/main/kotlin/`. `:java-binding` applies `events.library`; `:domain-model`, `:event-log`, `:mission-status-api` and `:command-cli` apply `events.service` (which applies `jacoco` and pins its tool version, with no report or `finalizedBy`). `:mission-status-api` (`service/`) and `:command-cli` already configure the XML report and `finalizedBy` in their own scripts. `:contracts` has no Java main sources.
- **Workflow**: `.github/workflows/sonar.yml`, one job, test-pinned by `tests/test_sonar_workflow.py` (YAML loaded with `BaseLoader`).
- **Properties**: `sonar-project.properties` (already lists `java/src/main/java`, so there is no `java/src` overlap on `main`).
- **Language/Test**: Python 3.10+ with pytest, mypy --strict, ruff; Java 25 under Gradle with strict dependency verification.
- **Charter gates**: pytest, `mypy --strict`, `uv run pytest --pyargs spec_kitty_events.conformance`, `./gradlew verifyAll` (or `make test-gradle`), `ruff`, `scripts/changelog.py check/guard`. SHA-pinned actions.

## Charter Check

| Rule | Status |
|---|---|
| Package boundary, no core dependency | Unaffected |
| Strict Gradle dependency verification | No new dependency (NFR-001) |
| SHA-pinned actions | Unchanged (NFR-002); a test already pins it |
| Docs synchronized; DocKitty checks | WP03 updates `docs/guides/contributing.md`; `npm run validate` then `docs_index.py --clean` |
| Changelog by fragment, never `[Unreleased]` by hand | WP03 adds `docs/changelog/unreleased/238-sonar-coverage-hygiene.md` |

## Design decisions

1. **One shared report convention.** `events.library.gradle.kts` applies `jacoco`, sets `toolVersion` from the catalog, enables the XML report and finalizes `test` with `jacocoTestReport`. The same wiring must reach the modules that apply `events.service` (`domain-model`, `event-log`). It lives in one small precompiled script plugin that both conventions apply, so the report wiring is written once; `events.library.gradle.kts` is where the library path is wired, as the maintainer ruled. The module scripts that already do this themselves keep their gates; a duplicated `finalizedBy` or `xml.required` is idempotent. The aggregation in `service/build.gradle.kts` is not touched.
2. **Module set from `settings.gradle.kts`.** A helper `scripts/sonar_java_modules.py` parses `include(...)` / `project(...).projectDir = file(...)` pairs and prints, for each module whose `projectDir` holds `src/main`, its directory and expected report path. Both the workflow's collect step and `tests/test_sonar_workflow.py` use it, so the list is written nowhere by hand.
3. **Explicit classpaths.** A root-level task `sonarLibraries` (registered for every project that applies the `java` plugin, in the root `build.gradle.kts`) writes `build/sonar/libraries.txt` (compile classpath) and `build/sonar/test-libraries.txt` (test runtime classpath). The workflow runs it after `verifyAll`, and the collect step joins the files; when none exist it falls back to the `modules-2` glob and warns. If a real scan log still shows unresolved types, the residue is recorded in the PR.
4. **Failed-step summary.** The report step reads `steps.<id>.outcome` through `env:` (never `${{ }}` inside `run:`) for pytest, jdk, bundle and gradle, and appends a list of the failed ones.
5. **`!cancelled()`** replaces `always()` on the four steps. The step condition keeps its token guard.
6. **Tests.** `tests/test_sonar_workflow.py` gains the assertions in FR-009, with crons parsed from every other `.github/workflows/*.yml`, and a test of the helper. A mutation check during implementation proves each new assertion can fail.

## Work packages

| WP | Scope | Implementer | Depends on |
|---|---|---|---|
| WP01 | Gradle: shared JaCoCo-XML convention for every module with main sources; `sonarLibraries` task; run `make test-gradle` / `verifyAll` | tokenfactory | none |
| WP02 | `scripts/sonar_java_modules.py`; `sonar.yml` (collect step, `!cancelled()`, token, failed-step summary, classpaths); `sonar-project.properties` (`sonar.test.inclusions`); `tests/test_sonar_workflow.py` | tokenfactory | WP01 |
| WP03 | `docs/guides/contributing.md` Sonar section; changelog fragment | claude-haiku-5-5 | WP02 |

## Verification

- WP01: `./gradlew verifyAll` with `CONTRACT_BUNDLE`/`CONTRACT_BUNDLE_SHA256` (runbook §5); a `jacocoTestReport.xml` under each module; gates green.
- WP02: `uv run pytest tests/test_sonar_workflow.py tests/test_ci_gate_workflows.py tests/test_service_workflows.py -q --no-cov`; `ruff`; `mypy --strict` on new Python.
- Orchestrator: conformance gate, `make lint`, `cd site && npm run validate` then `python3 scripts/docs_index.py --clean`, and the proof run `gh workflow run sonar.yml --ref issue-238-sonar-coverage-hygiene`.

## Risks

- `events.service` stays minimal by design; the shared script plugin must not change compile or test behaviour for the service modules (report wiring only).
- Gradle strict verification: resolving classpaths in a task must not resolve configurations outside the verified set.
- The Sonar proof depends on the repo's `SONAR_TOKEN` and a runner; a failed dispatch is reported, not hidden.
