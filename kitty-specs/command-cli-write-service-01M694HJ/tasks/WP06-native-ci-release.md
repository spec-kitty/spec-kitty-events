---
work_package_id: WP06
title: Native build, CI matrix and release assets
dependencies:
- WP05
requirement_refs:
- FR-018
- FR-019
- NFR-001
- SC-002
planning_base_branch: issue-156-command-cli
merge_target_branch: issue-156-command-cli
branch_strategy: Planning artifacts for this mission were generated on issue-156-command-cli. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into issue-156-command-cli unless the human explicitly redirects the landing branch.
base_branch: kitty/mission-command-cli-write-service-01M694HJ
base_commit: ba4071307c88fb02173864be997e17b4c7ed9ab1
created_at: '2026-10-31T15:38:03.463312+00:00'
subtasks:
- T030
- T031
- T032
- T033
- T034
history:
- timestamp: '2026-10-31T14:30:00Z'
  lane: planned
  agent: system
  action: Prompt generated via /spec-kitty.tasks
agent_profile: java-jenny
authoritative_surface: .github/workflows/
create_intent: []
execution_mode: code_change
owned_files:
- .github/workflows/service.yml
- scripts/release/service-release-assets.sh
- tests/test_service_workflows.py
- service/command-cli/src/test/java/org/speckitty/commandcli/nativesmoke/**
- Makefile
- scripts/verify_all.sh
role: implementer
tags: []
tracker_refs: []
---
# Work Package Prompt: WP06 – Native build, CI matrix and release assets

## ⚡ Do This First: Load Agent Profile

Load your assigned profile before reading anything else: `/spk-charter-profile-load java-jenny`
(or `spec-kitty agent profile show java-jenny`). Adopt its identity, scope and boundaries.

## Branch Strategy

The planning base and the merge target are both `issue-156-command-cli`; the PR targets `main`. The workspace comes from `lanes.json`.
`spec-kitty agent action implement WP06 --agent claude --mission command-cli-write-service-01M694HJ`

## Ground rules (all WPs)

- ATDD, red first: write the acceptance tests of each subtask before the code, and see them fail for the right reason.
- Java 25 at `/usr/lib/jvm/temurin-25`. Build with `jh=$(scripts/ensure_jdk25.sh); JAVA_HOME=$jh ./gradlew --no-daemon -Dorg.gradle.java.installations.paths=$jh :command-cli:check`.
- No new dependency. Everything needed is already in `gradle/libs.versions.toml` and `gradle/verification-metadata.xml`. If you hit Central HTTP 429, wait and retry; never reroute.
- No dependency on core (`spec-kitty/spec-kitty`). Core paths are cited for parity only.
- Never log or echo the token. No body names a host path.
- Commits carry `Co-Authored-By: Stijn Dejongh <stijn.dejongh@sddevelopment.be>`. No AI model identifier anywhere.

## Objective

The `service.yml` native matrix builds, native-tests and native-smokes `command-cli` on linux-x64, linux-arm64, macos-arm64 and windows-x64. The gradle job uploads the jar. A `service-v*` release publishes `command-cli-<ver>-<target>[.exe]` and `command-cli-<ver>.jar` with `.sha256`, `SHA256SUMS` and provenance.

Spec: FR-017 to FR-019, NFR-001, SC-002; User Story 5. Research: D-11, D-12. Charter: SHA-pinned actions in changed workflows.

**Note:** `native-image` is NOT available locally. The native build is verified only in CI on the draft PR. Read `service.yml` in full before editing it.

## Subtasks

### T030 Native tests and smoke
- Tag the JVM-safe tests that must also run natively with `@Tag("native")` (the edge, the operations, the endpoint file). They are run through the module's `nativeTagJvmTest`/`nativeTest` wiring from WP01.
- `NativeSmokeTest` (`@Tag("native-binary")`, run by `nativeSmoke -PnativeBinary=<path>`) does what process test (a) and (c) do against the binary, and asserts NFR-001 (ready ≤ 2 s).
- Runtime hints: register anything reflection needs through `@ImportRuntimeHints`. Never hand-write reflect-config (ADR-0003).

### T031 service.yml
- **detect:** add `service/command-cli/**` to the service paths.
- **gradle job:** build `:command-cli:bootJar` and upload `command-cli-${ver}.jar`, named like the read service's jar step.
- **native matrix:** for each target, after the read service's steps, run `:command-cli:nativeCompile :command-cli:nativeTest` and then the smoke with `-PnativeBinary`. Rename to `command-cli-${ver}-${target}${exe}` and upload `native-command-cli-<target>` and `digest-command-cli-<target>`, mirroring the read service's naming.
- Keep the read service's steps and artifact names unchanged. Every `uses:` keeps its 40-hex SHA pin.
- Make the `service-gate` job include the new results.

### T032 Release asset script
Extend `scripts/release/service-release-assets.sh`:
- add a second deployable family, `command-cli`, for the 4 targets plus the jar, with `.sha256` per asset and inclusion in `SHA256SUMS`;
- keep deterministic tarballs, as the script does;
- check `service-release.yml`'s download step: if it downloads artifacts by name pattern, make sure the new names are included.

### T033 Workflow tests
Extend `tests/test_service_workflows.py` with the expectations:
- the matrix targets build `command-cli`;
- the asset names follow ADR-0003 lines 129-130;
- the SHA pins hold;
- the detect paths include `service/command-cli/**`.

Run `uv run pytest tests/test_service_workflows.py -q`.

### T034 Make and verify script
- `make test-gradle` and `scripts/verify_all.sh` run `verifyAll`, which includes `:command-cli:check` through the root task. Confirm this.
- The process tests need the bootJar: wire `test` to depend on `bootJar`, or use a dedicated `processTest` task that `check` depends on, following the read service's `kitTest` precedent.

## Definition of Done
- The workflow tests are green locally.
- The service CI is green on the draft PR for all four native targets and the jar.

## Risks
- Matrix time doubles. Accept this, or split into a second job per target if the timeout bites.
- Windows `.exe` naming and the smoke process handling (no SIGTERM): the smoke uses `/shutdown` only.
