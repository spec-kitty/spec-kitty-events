---
work_package_id: WP02
title: Sonar workflow, properties, helper and tests
dependencies:
- WP01
requirement_refs:
- FR-003
- FR-004
- FR-005
- FR-006
- FR-007
- FR-008
- FR-009
- NFR-002
- NFR-003
planning_base_branch: issue-238-sonar-coverage-hygiene
merge_target_branch: issue-238-sonar-coverage-hygiene
branch_strategy: Planning artifacts for this mission were generated on issue-238-sonar-coverage-hygiene. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into issue-238-sonar-coverage-hygiene unless the human explicitly redirects the landing branch.
subtasks:
- T004
- T005
- T006
- T007
history:
- timestamp: '2026-11-09T19:00:00Z'
  lane: planned
  agent: system
  action: Prompt generated via /spec-kitty.tasks
agent_profile: python-pedro
authoritative_surface: .github/workflows/sonar.yml
create_intent:
- scripts/sonar_java_modules.py
- tests/test_sonar_java_modules.py
execution_mode: code_change
owned_files:
- .github/workflows/sonar.yml
- sonar-project.properties
- scripts/sonar_java_modules.py
- tests/test_sonar_workflow.py
- tests/test_sonar_java_modules.py
role: implementer
tags: []
tracker_refs: []
---
# Work Package Prompt: WP02 - Sonar workflow, properties, helper and tests

## Do This First: Load Agent Profile

Load your profile before anything else: `spec-kitty agent profile show python-pedro`. Adopt its identity, scope and boundaries.

## Objective

Harden `.github/workflows/sonar.yml` and `sonar-project.properties`, add a helper that derives the Gradle modules with main sources from `settings.gradle.kts`, and deepen `tests/test_sonar_workflow.py`. Spec: FR-003 to FR-009. Plan: design decisions 2, 3, 4, 5 and 6. WP01 (already merged on this branch) gives every module a `jacocoTestReport.xml` and a `sonarLibraries` task writing `build/sonar/libraries.txt` and `build/sonar/test-libraries.txt` per project; read its commit message for the exact format.

## Context

Read first: `.github/workflows/sonar.yml`, `sonar-project.properties`, `tests/test_sonar_workflow.py`, `settings.gradle.kts`. On main, `sonar.sources` already lists `java/src/main/java` (no `java/src` overlap); keep it that way.

## Subtasks

### T004 `scripts/sonar_java_modules.py` (+ `tests/test_sonar_java_modules.py`)
A typed, stdlib-only helper (mypy --strict, ruff clean, complexity <= 15). It parses `settings.gradle.kts` (`include(":name")` plus `project(":name").projectDir = file("dir")`; default dir is the project name when no projectDir line), keeps the modules whose directory holds `src/main`, and exposes: a function returning (name, directory, expected report path `<dir>/build/reports/jacoco/test/jacocoTestReport.xml`) tuples, and a CLI (`python3 scripts/sonar_java_modules.py` printing one `name<TAB>dir<TAB>report` line per module). `:contracts` has no `src/main` and must not appear; `:mission-status-api` (dir `service`) must. Unit tests: the real repository's set equals {java-binding, mission-status-api, domain-model, event-log, command-cli} computed from a tmp copy of the settings text plus real directories where useful, a synthetic settings file with a new module, and a module without `src/main`.

### T005 `sonar.yml`
- `always()` becomes `!cancelled()` on the collect, scan, quality-gate and report steps (keep each step's token-guard condition: `!cancelled() && steps.sonar_token.outputs.enabled == 'true'`). The string `always()` appears nowhere in the file, comments included.
- Remove `GITHUB_TOKEN` from the scan step: its `env` holds `SONAR_TOKEN` only.
- Collect step: call the helper (`python3 scripts/sonar_java_modules.py`) to list expected module reports; for each missing report emit `::warning::sonar: no JaCoCo report for <name> (<report path>)`; list every imported report and each missing module in `$GITHUB_STEP_SUMMARY`. Keep the existing find-based gathering for `sonar.coverage.jacoco.xmlReportPaths` (or switch it to the helper's paths if simpler; the imported set must be the reports that exist).
- Classpaths: add a build step `Write the Java library classpaths` running `./gradlew --no-daemon sonarLibraries` (`continue-on-error: true`, `id: classpaths`, gated like the Gradle step, after the verifyAll step). The collect step joins every `**/build/sonar/libraries.txt` into `-Dsonar.java.libraries=` and every `**/build/sonar/test-libraries.txt` into `-Dsonar.java.test.libraries=`; when no file exists it falls back to the existing `$HOME/.gradle/caches/modules-2/files-2.1/**/*.jar` glob with a `::warning::` saying the fallback is in use.
- Failed-step summary: the report step (or a new step, `!cancelled()`) receives each build step's outcome through `env:` (`PYTEST_OUTCOME`, `JDK_OUTCOME`, `BUNDLE_OUTCOME`, `GRADLE_OUTCOME`, `CLASSPATHS_OUTCOME` from `steps.<id>.outcome`) and appends to `$GITHUB_STEP_SUMMARY` a list of those whose outcome is `failure` (and a line saying none failed when none did). Never write `${{` inside any `run:`.
- Keep: triggers `schedule` + `workflow_dispatch` only, cron `17 4 * * *`, `permissions: contents: read`, every action pinned by 40-hex SHA with a `# vX.Y.Z` comment, `SONAR_TOKEN` only on the guard, scan and gate steps. If you add the `id` to pytest, jdk, bundle and gradle steps, check they exist already (`pytest`, `jdk`, `bundle`, `gradle` do).

### T006 Properties
Add `sonar.test.inclusions=**/test_*.py` (with a short comment: the packaged conformance tests under `src/spec_kitty_events/conformance/` are tests; no other `test_*.py` sits under the scanned sources). No other change to `sonar.sources`/`sonar.tests`.

### T007 `tests/test_sonar_workflow.py`
Red first where possible: write the assertions, see the failures against the unmodified workflow, then make them pass. Add: no `always()` in the file; the four steps use `!cancelled()`; the scan step's env keys are exactly `{SONAR_TOKEN}`; workflow permissions equal `{contents: read}`; no `run:` string contains `${{`; `SONAR_TOKEN` is absent from the env of every build step (all steps except the guard, the scan and the gate); `sonar.sources` and `sonar.tests` do not overlap (split on comma; two entries overlap when one equals or is a path-prefix, on path components, of the other; include a unit test of the overlap function with `java/src` versus `java/src/test/java`); `sonar.test.inclusions == "**/test_*.py"`; the failed-step summary reads outcomes through `env`; the collect step references the helper and the classpath files; the module wiring: for each module from the helper, its build script applies `events.library` or `events.service` (or itself enables the XML report), and the convention applies the shared jacoco-xml wiring. Replace the hard-coded `EXISTING_CRONS` by crons parsed from every other `.github/workflows/*.yml` (the sonar cron must differ from each of them; fail loudly if the parse finds none).

Mutation check (record the result in your final commit message): for each new assertion, break the property (re-add `always()`, add `GITHUB_TOKEN`, make a source overlap a test root, add `SONAR_TOKEN` to a build step, set the sonar cron to another workflow's cron) and see the test fail, then restore.

## Ground rules

- Run what you wrote plus the blast radius and record commands and counts in the commit message: `uv run pytest tests/test_sonar_workflow.py tests/test_sonar_java_modules.py tests/test_ci_gate_workflows.py tests/test_service_workflows.py -q --no-cov`; `uv run ruff check . && uv run ruff format --check .`; `uv run mypy --strict scripts/sonar_java_modules.py` (or the repo's mypy invocation). Do not run `make test-full`.
- Commit on the current branch with a message ending `Co-Authored-By: <your model> <noreply@anthropic.com>`. Do not push. Do not merge.
- Do not edit `build-logic/`, `build.gradle.kts`, docs or the changelog (other packages own them).
