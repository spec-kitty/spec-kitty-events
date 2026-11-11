# Tasks: Sonar coverage and workflow hygiene

**Mission**: sonar-coverage-hygiene-01M70TKQ | **Branch**: `issue-238-sonar-coverage-hygiene` (owned checkout, `single_branch`) | **Lands as**: one PR to `main` closing #234 and #235 (ruling D9) | **Spec**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md)

Implementation follows red-first where a Python test can fail first (WP02). WP01 is build wiring: its proof is `./gradlew verifyAll` plus the report files.

## Subtask Index

| ID | Description | WP | Parallel |
|---|---|---|---|
| T001 | Shared JaCoCo XML convention (report on, `test` finalized by `jacocoTestReport`, catalog tool version) applied by `events.library` and `events.service` | WP01 | |
| T002 | Root `sonarLibraries` task writing per-project `build/sonar/libraries.txt` and `build/sonar/test-libraries.txt` | WP01 | |
| T003 | Run `verifyAll` (bundle exported) and show an XML per module with main sources; gates unchanged | WP01 | |
| T004 | `scripts/sonar_java_modules.py`: modules with main sources and expected report paths from `settings.gradle.kts`, plus tests | WP02 | |
| T005 | `sonar.yml`: `!cancelled()`, drop `GITHUB_TOKEN`, collect step per-module report check, classpath files, failed-step summary | WP02 | |
| T006 | `sonar-project.properties`: `sonar.test.inclusions=**/test_*.py` | WP02 | [P] |
| T007 | `tests/test_sonar_workflow.py`: FR-009 assertions, derived crons, module-wiring check, mutation check recorded | WP02 | |
| T008 | `docs/guides/contributing.md` Sonar section | WP03 | [P] |
| T009 | Changelog fragment `docs/changelog/unreleased/238-sonar-coverage-hygiene.md` | WP03 | [P] |

## Work packages

### WP01 - Gradle JaCoCo XML for every module and the classpath task
Requirements: FR-001, FR-002, FR-008 (Gradle half), NFR-001. Subtasks: T001, T002, T003. Dependencies: none.

### WP02 - Sonar workflow, properties, helper and tests
Requirements: FR-003, FR-004, FR-005, FR-006, FR-007, FR-008 (workflow half), FR-009, NFR-002, NFR-003. Subtasks: T004, T005, T006, T007. Dependencies: WP01.

### WP03 - Docs and changelog
Requirements: FR-010. Subtasks: T008, T009. Dependencies: WP02.
