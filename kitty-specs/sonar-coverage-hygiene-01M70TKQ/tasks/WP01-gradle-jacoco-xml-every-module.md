---
work_package_id: WP01
title: Gradle JaCoCo XML for every module and the classpath task
dependencies: []
requirement_refs:
- FR-001
- FR-002
- FR-008
- NFR-001
planning_base_branch: issue-238-sonar-coverage-hygiene
merge_target_branch: issue-238-sonar-coverage-hygiene
branch_strategy: Planning artifacts for this mission were generated on issue-238-sonar-coverage-hygiene. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into issue-238-sonar-coverage-hygiene unless the human explicitly redirects the landing branch.
subtasks:
- T001
- T002
- T003
history:
- timestamp: '2026-11-09T19:00:00Z'
  lane: planned
  agent: system
  action: Prompt generated via /spec-kitty.tasks
agent_profile: java-jenny
authoritative_surface: build-logic/src/main/kotlin/
create_intent: []
execution_mode: code_change
owned_files:
- build-logic/src/main/kotlin/**
- build.gradle.kts
role: implementer
tags: []
tracker_refs: []
---
# Work Package Prompt: WP01 - Gradle JaCoCo XML for every module and the classpath task

## Do This First: Load Agent Profile

Load your profile before anything else: `spec-kitty agent profile show java-jenny`. Adopt its identity, scope and boundaries.

## Objective

`./gradlew verifyAll` writes `build/reports/jacoco/test/jacocoTestReport.xml` in every Gradle project that has main sources, and a new task writes explicit Java library classpaths for the Sonar scan. Gates stay unchanged. Spec: FR-001, FR-002, FR-008. Plan: design decisions 1 and 3.

## Context (verified on main at 28b8f851)

- `settings.gradle.kts` includes `:java-binding` (dir `java`), `:mission-status-api` (dir `service`), `:domain-model`, `:event-log`, `:command-cli` and `:contracts` (no Java main sources; needs nothing).
- `build-logic/src/main/kotlin/events.library.gradle.kts` (applied by `:java-binding`) has no jacoco at all.
- `events.service.gradle.kts` (applied by `:mission-status-api`, `:domain-model`, `:event-log`, `:command-cli`) applies `jacoco` and sets the tool version from the catalog (`libs.findVersion("jacoco")`), with no report and no `finalizedBy`. Its header says it deliberately holds nothing more; do not change what the service compiles, runs or packages.
- `service/build.gradle.kts` (aggregation of domain-model and event-log into one gate, around lines 349-368, `finalizedBy` near 202) and `service/command-cli/build.gradle.kts` (near 95-100, 205-212) already enable the XML report and `finalizedBy`. Do not touch them or any gate: `jacocoScopeCheck`, the 0.90/0.80 rules, `jacoco-class-floor.properties`, event-log's complexity rule.

## Binding rulings

- The XML wiring is written in `build-logic/src/main/kotlin/events.library.gradle.kts`. `domain-model` and `event-log` apply `events.service`, not `events.library`, so the same wiring must reach them. Put the wiring once in a small precompiled script plugin (suggested name `events.jacoco-xml.gradle.kts`) that sets: `jacoco` plugin applied, tool version from the catalog, `tasks.test { finalizedBy("jacocoTestReport") }`, `jacocoTestReport { reports { xml.required.set(true) } }`. `events.library` applies it, and so does `events.service`. A duplicated `finalizedBy` or `xml.required` in the modules that already set them is idempotent.
- The report must cover every project with main sources, derived from `settings.gradle.kts`, including `:mission-status-api`. Do not hard-code "five".
- No new dependency; strict dependency verification (`gradle/verification-metadata.xml`) must stay green. Do not edit that file unless a real verification failure demands it, and then say so.

## Subtasks

### T001 Shared JaCoCo XML convention
As above. Keep `events.java-base` untouched. Confirm `:java-binding` now yields `java/build/reports/jacoco/test/jacocoTestReport.xml`.

### T002 `sonarLibraries` task
In the root `build.gradle.kts` (or the convention, your call, justify in the commit), register for every project that applies the `java` plugin a task `sonarLibraries` that writes, one path per line or comma-joined (state which; the workflow joins the files), `build/sonar/libraries.txt` (the project's main compile classpath files) and `build/sonar/test-libraries.txt` (test compile plus runtime classpath files). It must be configuration-cache and verification safe: resolve only configurations the build already resolves (`compileClasspath`, `testCompileClasspath`, `testRuntimeClasspath`), at execution time, with declared outputs. A project-internal dependency appears as its `build/libs` jar or class dirs.

### T003 Prove it
Export `CONTRACT_BUNDLE` and `CONTRACT_BUNDLE_SHA256` first (docs/guides/service-cutover-runbook.md section 5; or use `make test-gradle`, which does it). Then `./gradlew verifyAll` and `./gradlew sonarLibraries`. Record in your final commit message: the list of `jacocoTestReport.xml` files found (expect java, service, service/domain-model, service/event-log, service/command-cli), the verifyAll result, and the `sonarLibraries` outputs.

## Ground rules

- Run only what you changed plus the blast radius: `make test-gradle` (or `verifyAll` with the bundle). Do not run `make test-full`.
- Kotlin DSL style of the surrounding files; explain non-obvious lines in a comment, briefly.
- Commit on the current branch with a message ending `Co-Authored-By: <your model> <noreply@anthropic.com>`. Do not push. Do not merge.
- Do not edit `.github/`, `tests/`, `scripts/`, docs or `sonar-project.properties` (other packages own them).
