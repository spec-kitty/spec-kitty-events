---
work_package_id: WP01
title: Module skeleton, build and command line
dependencies: []
requirement_refs:
- FR-001
- FR-017
- C-002
- C-003
- NFR-005
planning_base_branch: issue-156-command-cli
merge_target_branch: issue-156-command-cli
branch_strategy: Planning artifacts were generated on issue-156-command-cli. Completed changes merge back into issue-156-command-cli; the PR then targets main.
subtasks:
- T001
- T002
- T003
- T004
- T005
- T006
history:
- timestamp: '2026-10-31T14:30:00Z'
  lane: planned
  agent: system
  action: Prompt generated via /spec-kitty.tasks
agent_profile: java-jenny
authoritative_surface: service/command-cli/
create_intent:
- service/command-cli/build.gradle.kts
- service/command-cli/src/main/resources/application.properties
execution_mode: code_change
owned_files:
- settings.gradle.kts
- service/command-cli/build.gradle.kts
- service/command-cli/src/main/resources/**
- service/command-cli/src/main/java/org/speckitty/commandcli/config/**
- service/command-cli/src/test/java/org/speckitty/commandcli/architecture/**
- service/command-cli/src/test/java/org/speckitty/commandcli/config/**
role: implementer
tags: []
tracker_refs: []
---
# Work Package Prompt: WP01 – Module skeleton, build and command line

## ⚡ Do This First: Load Agent Profile

Load your assigned profile before reading anything else: `/spk-charter-profile-load java-jenny`
(or `spec-kitty agent profile show java-jenny`). Adopt its identity, scope and boundaries.

## Branch Strategy

The planning base and the merge target are both `issue-156-command-cli`; the PR targets `main`. The workspace comes from `lanes.json`.
`spec-kitty agent action implement WP01 --agent claude --mission command-cli-write-service-01M694HJ`

## Ground rules (all WPs)

- ATDD, red first: write the acceptance tests of each subtask before the code, and see them fail for the right reason.
- Java 25 at `/usr/lib/jvm/temurin-25`. Build with `jh=$(scripts/ensure_jdk25.sh); JAVA_HOME=$jh ./gradlew --no-daemon -Dorg.gradle.java.installations.paths=$jh :command-cli:check`.
- No new dependency. Everything needed is already in `gradle/libs.versions.toml` and `gradle/verification-metadata.xml`. If you hit Central HTTP 429, wait and retry; never reroute.
- No dependency on core (`spec-kitty/spec-kitty`). Core paths are cited for parity only.
- Never log or echo the token. No body names a host path.
- Commits carry `Co-Authored-By: Stijn Dejongh <stijn.dejongh@sddevelopment.be>`. No AI model identifier anywhere.

## Objective

Stand up `:command-cli` as a buildable Spring Boot 4 module that mirrors `:mission-status-api`. When this WP is done, it parses `serve --git-common-dir <abs> [--idle-timeout <s>]`, has ArchUnit onion rules, and passes `:command-cli:check` on the JVM.

Spec: FR-001, FR-017, C-002, C-003, NFR-005. Plan: IC-1. Research: D-1, D-10, D-11.

## Context

- Precedent: `service/build.gradle.kts` (about 480 lines) and `service/src/main/resources/application.properties`.
- Also see `service/src/main/java/org/speckitty/missionstatus/config/MissionStatusApplication.java` and `service/src/test/java/org/speckitty/missionstatus/architecture/ArchitectureRules.java`.
- `settings.gradle.kts` maps modules to directories; `verifyAll` (root `build.gradle.kts`) picks up every subproject's `check`.
- The contract is `contracts/mission-write/openapi.yaml` (split with `$ref`). Generate **interfaces only**, with the same generator options as the read service, but unfiltered: all six operations are in scope. Model package `org.speckitty.commandcli.adapter.in.http.model`, api package `org.speckitty.commandcli.adapter.in.http.api`.
- `group = "org.speckitty.commandcli"` is required, otherwise `processTestAot` fails.

## Subtasks

### T001 Register the module
Add `include(":command-cli")` and `project(":command-cli").projectDir = file("service/command-cli")` beside the existing service modules.

### T002 The module build
Copy the read service build and remove what it does not need:
- **Remove:** `bundleContract` (no contract document is served), the conformance and corpus specifics, and the perf `ServiceTestSlot` specifics that do not apply.
- **Keep:**
  - the `events.service` convention;
  - Spring Boot, dependency-management, openapi-generator and the GraalVM plugin (`toolchainDetection=false`);
  - the AOT guard;
  - `bootJar` named `command-cli.jar`;
  - the JaCoCo gates: 90% lines on `domain.*` and `application.*`, 80% branches on `domain.*`;
  - the test tags (`native`, `native-binary`) and the `nativeTagJvmTest`/`nativeTest`/`nativeSmoke` wiring. WP06 completes the smoke.
- **Implementation-Version:** set the manifest from `-PreleaseVersion`, default `0.0.0-dev`.
- **Verify:** run `:command-cli:dependencies` once and confirm that verification passes with no new entries.

### T003 application.properties
Copy the hygiene lines from the read service:
- `server.address=127.0.0.1`, `server.port=0` and `server.forward-headers-strategy=none`;
- virtual threads;
- banner off and `log-startup-info=false`;
- `spring.web.resources.add-mappings=false`;
- the coyote, Cookie and PageNotFound logger levels.

No `mission-status.*` keys.

### T004 Application and ServeOptions
- **ServeOptions** is a record built by `ServeOptions.parse(String[] argv)`. It returns the options or throws `UsageException(message)`.
  - Accepted forms: `serve --git-common-dir <abs> [--idle-timeout <positive int>]`, or `--git-common-dir=<abs>`.
  - Refused: a relative path, a non-directory, an unknown flag, a repeated flag, a non-positive or non-integer idle timeout, or no subcommand.
- **CommandCliMain.main:** parse; on usage error print usage to stderr and exit 2. WP05 adds the lock and the endpoint publish. For now, start Spring (`CommandCliApplication`) with `server.port=0`.

### T005 ArchUnit rules
Layers:
- `domain..` depends only on `java..`;
- `application..` depends on domain and java;
- `adapter..` and `config..` are outer;
- no adapter depends on another adapter package;
- `domain` has no `java.io` or `java.nio.file`.

Mirror `ArchitectureRules` in the read service.

### T006 ServeOptions tests
Write them first, as a table of argv against outcome, including Windows-style drive paths, which are treated as relative on POSIX.

## Definition of Done
- `:command-cli:check` is green on JVM, and the jar builds.
- `./gradlew verifyAll` includes the module.
- ArchUnit and JaCoCo gates are active.

## Risks
- Configuration cache: task actions must not capture script references (see the read build's comments).
- The JaCoCo class-floor property file may be needed: copy the mechanism only if the gate needs it.
