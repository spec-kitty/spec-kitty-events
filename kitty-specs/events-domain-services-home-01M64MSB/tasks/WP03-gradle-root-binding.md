---
work_package_id: WP03
title: Root Gradle build and Java binding migration
dependencies: []
requirement_refs:
- FR-005
- NFR-001
- NFR-002
- NFR-003
planning_base_branch: issue-147-events-services-home
merge_target_branch: issue-147-events-services-home
branch_strategy: Planning artifacts for this mission were generated on issue-147-events-services-home. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into issue-147-events-services-home unless the human explicitly redirects the landing branch.
subtasks:
- T009
- T010
- T011
- T012
- T013
- T014
history:
- timestamp: '2026-10-29T20:40:00Z'
  lane: planned
  agent: system
  action: Prompt generated via /spec-kitty.tasks
agent_profile: java-jenny
authoritative_surface: build-logic/
create_intent:
- settings.gradle.kts
- build.gradle.kts
- gradle.properties
- gradle/libs.versions.toml
- gradle/verification-metadata.xml
- gradle/wrapper/gradle-wrapper.properties
- gradle/wrapper/gradle-wrapper.jar
- gradlew
- gradlew.bat
- java/build.gradle.kts
- build-logic/settings.gradle.kts
- build-logic/build.gradle.kts
execution_mode: code_change
owned_files:
- settings.gradle.kts
- build.gradle.kts
- gradle.properties
- gradle/**
- gradlew
- gradlew.bat
- build-logic/**
- java/**
- Makefile
- scripts/ensure_jdk25.sh
- .gitignore
role: implementer
tags: []
tracker_refs: []
---
# Work Package Prompt: WP03 – Root Gradle build and Java binding migration

## ⚡ Do This First: Load Agent Profile

Load your assigned profile before reading anything else: `/spk-charter-profile-load java-jenny`
(or `spec-kitty agent profile show java-jenny`). Adopt its identity, scope and boundaries.

## Branch Strategy

The planning base and the merge target are both `issue-147-events-services-home`; the PR targets `main`. The workspace comes from `lanes.json`.
`spec-kitty agent action implement WP03 --agent claude --mission events-domain-services-home-01M64MSB`

## Objective

Replace the Maven build of `java/` with a root Gradle Kotlin DSL build that the services join at cutover. The binding's behaviour and test count must not change (NFR-002), and the Python line must stay untouched (NFR-001).

## Context
- The current build is `java/pom.xml`: Java 25 (`maven.compiler.release`), `-Xlint:all -Werror`, and resources read in place from `../src/spec_kitty_events/producer_contract` with includes `*.json`, `data/*.json`, `composed/rows/*.json`, `composed/read/*.json` and targetPath `spec_kitty_events/producer_contract`.
  - Surefire sets system property `upcast.cases=${contract.dir}/fixtures/upcast/cases`.
  - Dependencies: jackson-databind 3.2.4 (`tools.jackson.core`), networknt json-schema-validator 3.0.8, junit-jupiter 6.1.3 (test), slf4j-nop 2.0.17 (test).
  - Coordinates: `ai.spec-kitty:spec-kitty-events-java:11.3.0`.
  - Wrapper files: `java/mvnw`, `java/mvnw.cmd`, `java/.mvn/`.
- `Makefile` target `test-java` runs `scripts/ensure_jdk25.sh` and then `java/mvnw ... verify`. `test-full` depends on it.
- **Precedent to copy** (read-only), `/home/user/spec-kitty/spec-kitty-charter`:
  - `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `gradle/wrapper/gradle-wrapper.properties` (9.8.1 with `distributionSha256Sum`)
  - `build-logic/` (kotlin-dsl, catalog access via `versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } }`, plugins `charter.java-base` etc.)
- JDK 25 is at `/usr/lib/jvm/temurin-25`. `JAVA_TOOL_OPTIONS` is set by the sandbox proxy; it is harmless. Gradle is at `/opt/gradle/bin/gradle`: check its version, and use it only to generate the wrapper (`gradle wrapper --gradle-version 9.8.1 --gradle-distribution-sha256-sum <sum>`; take the sum from the charter repo's `gradle-wrapper.properties`).

## Subtasks

### T009 Record the Maven baseline (before deleting anything)
Run `JAVA_HOME=/usr/lib/jvm/temurin-25 java/mvnw -B -f java/pom.xml --strict-checksums verify` and record:
- tests run, failures, errors, skipped (the surefire summary line)
- wall time

Put them in the hand-off. If Maven cannot download through the proxy, record that and use the per-case count: `find src/spec_kitty_events/producer_contract/fixtures/upcast/cases -name '*.json' | wc -l`, plus 3 `@Test` methods, as the expected total.

### T010 Root build skeleton
- `settings.gradle.kts`:
  - `rootProject.name = "spec-kitty-events"`
  - `pluginManagement { includeBuild("build-logic") }`
  - `dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { mavenCentral() } }`
  - `include(":java-binding"); project(":java-binding").projectDir = file("java")`
  - a comment: the service modules join at cutover (ADR 0002)
- `gradle.properties`: `org.gradle.caching=true`, `org.gradle.configuration-cache=true`, `org.gradle.parallel=true`, `org.gradle.java.installations.auto-download=false`, `org.gradle.jvmargs=-Xmx2g`.
- `gradle/libs.versions.toml`: versions jackson 3.2.4, json-schema-validator 3.0.8, junit 6.1.3, slf4j 2.0.17. Libraries: `junit-bom` and junit-jupiter, plus `junit-platform-launcher` (needed by Gradle with JUnit 6).
- Wrapper 9.8.1 with `distributionSha256Sum`, `gradlew`, `gradlew.bat`. Commit `gradle-wrapper.jar`; it is validated in CI by WP04.
- Root `build.gradle.kts`: an aggregate `verifyAll` task depending on every subproject's `check` (as in the charter repo).
- `.gitignore`: add `.gradle/`, `build/`, `build-logic/build/`, `java/build/`, and remove `java/target/` only if it is listed.

### T011 build-logic conventions
- `build-logic/settings.gradle.kts` (catalog from `../gradle/libs.versions.toml`) and `build-logic/build.gradle.kts` (`kotlin-dsl`).
- Plugin `events.java-base` (`build-logic/src/main/kotlin/events.java-base.gradle.kts`):
  - `java` plugin, toolchain `JavaLanguageVersion.of(25)` (overridable with `-Pevents.javaVersion`), `options.release = 25`, `options.encoding = "UTF-8"`, `-parameters`
  - `tasks.withType<Test> { useJUnitPlatform() }`
  - reproducible archives: `isPreserveFileTimestamps = false`, `isReproducibleFileOrder = true`
- Plugin `events.library`: applies `events.java-base` and `java-library`, and adds `-Xlint:all -Werror` to compile tasks.
- Keep the plugins minimal; the service conventions are added at cutover (WP07).

### T012 `:java-binding` module (`java/build.gradle.kts`)
- `plugins { id("events.library") }`, `group = "ai.spec-kitty"`, `version = "11.3.0"`, `base.archivesName = "spec-kitty-events-java"`.
- Add a comment that the version must equal `pyproject.toml` (ADR 0004). Optionally add a `check`-wired task that reads `../pyproject.toml` and fails on mismatch; it is cheap and prevents drift.
- Resources: `sourceSets.main { resources { srcDir(...) } }` does not support a target path, so use `tasks.processResources { from(contractDir) { include("*.json","data/*.json","composed/rows/*.json","composed/read/*.json"); into("spec_kitty_events/producer_contract") } }`, with `val contractDir = rootProject.layout.projectDirectory.dir("src/spec_kitty_events/producer_contract")`. Declare it as an input so caching stays correct.
- Test: `systemProperty("upcast.cases", contractDir.dir("fixtures/upcast/cases").asFile.absolutePath)`. Also add the cases directory as `inputs.dir(...)` with `PathSensitivity.RELATIVE`, so a fixture change reruns the tests.
- Dependencies from the catalog. Tests: junit-jupiter, `testRuntimeOnly` junit-platform-launcher and slf4j-nop.

### T013 Strict dependency verification
- Generate `gradle/verification-metadata.xml` with `./gradlew --write-verification-metadata sha256 verifyAll`, then set `<verify-metadata>true</verify-metadata>` and `<verify-signatures>false</verify-signatures>` (sha256 only, as in the charter repo and core).
- Check that a clean run with `--refresh-dependencies` passes verification.
- Inspect the generated file: every artifact must come from Maven Central or the Gradle plugin portal (for kotlin-dsl). Record the artifact count in the hand-off.

### T014 Remove Maven; update the Makefile and JDK helper; parity
- Delete `java/pom.xml`, `java/mvnw`, `java/mvnw.cmd` and `java/.mvn/`.
- In the `Makefile`, the `test-java` recipe becomes `jh="$$(scripts/ensure_jdk25.sh)" && JAVA_HOME="$$jh" ./gradlew --no-daemon -Porg.gradle.java.installations.paths="$$jh" :java-binding:check`, and its comment is updated (Gradle wrapper, verification metadata). `test-full` keeps depending on it.
- `scripts/ensure_jdk25.sh`: change only the comments that mention Maven. The behaviour stays.
- Update the comments in `java/src/**` that mention Maven/pom (grep `pom\|mvn\|Maven`).
- **Parity**: `./gradlew :java-binding:check` must report the same total as T009 with 0 failures. Read the counts from `java/build/test-results/test/*.xml` (sum the `tests=` attributes).
- Also run:
  - `make test-java`
  - `make test-fast`
  - `uv run pytest --pyargs spec_kitty_events.conformance`
  - `ruff check . && ruff format --check .`

  Record the counts. `git diff --stat -- src/` must be empty.

## Definition of Done
- `./gradlew verifyAll` is green from a clean checkout with JDK 25. Test count equals the Maven baseline.
- No Maven files remain. `make test-java` uses Gradle.
- `verification-metadata.xml` is committed and strict. The wrapper is 9.8.1 with a sha256.
- The Python gates are green and `src/` is untouched.

## Risks
- Configuration cache plus `rootProject` access inside `processResources` can break; use layout and providers.
- `-Werror` combined with Gradle-generated warnings: keep the flags scoped to `JavaCompile` of main and test.
- JUnit 6 needs `junit-platform-launcher` on the test runtime classpath, or Gradle finds 0 tests silently. The parity check catches that.

## Reviewer guidance
- Diff the surefire and Gradle test totals.
- Grep for leftovers: `grep -rn 'mvnw\|pom.xml' --exclude-dir=kitty-specs --exclude-dir=.venv .` (CHANGELOG history may mention them; that is fine).
