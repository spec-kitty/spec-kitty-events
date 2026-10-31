---
work_package_id: WP04
title: 'Operations: handshake, health, fail-closed writes'
dependencies:
- WP02
- WP03
requirement_refs:
- FR-008
- FR-009
- FR-010
- FR-011
- SC-003
- C-004
- NFR-004
planning_base_branch: issue-156-command-cli
merge_target_branch: issue-156-command-cli
branch_strategy: Planning artifacts for this mission were generated on issue-156-command-cli. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into issue-156-command-cli unless the human explicitly redirects the landing branch.
base_branch: kitty/mission-command-cli-write-service-01M694HJ
base_commit: 5fe79409fbde4387864c3b742a2495cce802cc96
created_at: '2026-10-31T15:14:45.461932+00:00'
subtasks:
- T019
- T020
- T021
- T022
- T023
history:
- timestamp: '2026-10-31T14:30:00Z'
  lane: planned
  agent: system
  action: Prompt generated via /spec-kitty.tasks
agent_profile: java-jenny
authoritative_surface: service/command-cli/src/main/java/org/speckitty/commandcli/adapter/in/http/operations/
create_intent: []
execution_mode: code_change
owned_files:
- service/command-cli/src/main/java/org/speckitty/commandcli/adapter/in/http/operations/**
- service/command-cli/src/main/java/org/speckitty/commandcli/application/**
- service/command-cli/src/test/java/org/speckitty/commandcli/adapter/in/http/operations/**
- service/command-cli/src/test/java/org/speckitty/commandcli/application/**
role: implementer
tags: []
tracker_refs: []
---
# Work Package Prompt: WP04 – Operations

## ⚡ Do This First: Load Agent Profile

Load your assigned profile before reading anything else: `/spk-charter-profile-load java-jenny`
(or `spec-kitty agent profile show java-jenny`). Adopt its identity, scope and boundaries.

## Branch Strategy

The planning base and the merge target are both `issue-156-command-cli`; the PR targets `main`. The workspace comes from `lanes.json`.
`spec-kitty agent action implement WP04 --agent claude --mission command-cli-write-service-01M694HJ`

## Ground rules (all WPs)

- ATDD, red first: write the acceptance tests of each subtask before the code, and see them fail for the right reason.
- Java 25 at `/usr/lib/jvm/temurin-25`. Build with `jh=$(scripts/ensure_jdk25.sh); JAVA_HOME=$jh ./gradlew --no-daemon -Dorg.gradle.java.installations.paths=$jh :command-cli:check`.
- No new dependency. Everything needed is already in `gradle/libs.versions.toml` and `gradle/verification-metadata.xml`. If you hit Central HTTP 429, wait and retry; never reroute.
- No dependency on core (`spec-kitty/spec-kitty`). Core paths are cited for parity only.
- Never log or echo the token. No body names a host path.
- Commits carry `Co-Authored-By: Stijn Dejongh <stijn.dejongh@sddevelopment.be>`. No AI model identifier anywhere.

## Objective

Implement the six operations of `contracts/mission-write/openapi.yaml` on the generated interfaces.
- Handshake and health answer the identity and the state.
- The three writes validate shape, then refuse fail-closed and write nothing.
- Shutdown flips the lifecycle to draining. WP05 adds the exit.

Spec: FR-008 to FR-011, FR-015, SC-003; User Story 3. Research: D-6, D-7, D-8.

## Subtasks

### T019 Handshake and health
- **Use cases:** `application.lifecycle.ServiceIdentity` (instanceId, repositoryId, serviceVersion, protocol versions) and `ServiceLifecycle.state()`.
- **Handshake:** `{protocolVersions:[1], serviceVersion, instanceId, repositoryId}`.
- **Health:** `{status: ready|draining, instanceId}`.
- **Tests:** a WebMvc slice plus a full-context test with a temporary gcd.

### T020 Write controllers
- Bind with the generated models.
- Jackson: `FAIL_ON_UNKNOWN_PROPERTIES` on, and nulls for required fields refused.
- Bean-validate the contract's patterns and bounds: ULIDs, `minItems: 1`, batch `maxItems: 64`, and the relative-path patterns of `RequestContext`.
- Row rules: every row is an object with `event_id` (a ULID) and `wp_id == wpId`. A row with `mission_id` must equal the path's `missionId`.
- The path `missionId` must match the MissionId pattern.
- Any failure: 400 `REQUEST_MALFORMED` with a fixed detail. A batch names the failing unit in `instance` (`#/units/<i>`) if the contract example does so for malformed input; check `examples/WriteRefusal.batch-unit.yaml`.
- A valid body goes through `WriteGate.persist(...)`, which in PR1 always returns `Refused(STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED, "The write core is not built yet; nothing was written.")`, giving 500.
- Tests use every example in `contracts/mission-write/examples/` as a valid input, plus malformed variants.

### T021 ServiceLifecycle
- States `READY → DRAINING → STOPPED`, kept in an atomic reference.
- `beginDrain()` is idempotent.
- In-flight counting goes through an `enter()`/`exit()` pair that a filter calls. `awaitIdle(Duration)`.
- While draining, writes get 503 `SERVICE_DRAINING` with no `Retry-After`.
- `/shutdown` calls `beginDrain()` and returns 202. The exit sequence is a `ShutdownPort` that WP05 implements; here it is a no-op.

### T022 Contract conformance
- Load the schemas from `contracts/mission-write/schemas` (YAML) through networknt with a `$ref` resolver over the directory, and validate each response body: Handshake, Health and WriteRefusal.
- Refusal codes must be members of `WriteRefusalCode.yaml`, and their statuses must match `WriteRefusal.yaml`'s bindings.
- Precedent: the read service's contract tests under `service/src/test/.../adapter/in/http/contract`.

### T023 Nothing written
- Snapshot the temporary repository tree (paths, sizes and mtimes) before and after every write case, and assert equality.
- The state directory is excluded, but its files must not change during a write either.

## Definition of Done
- Every operation answers per the contract.
- 100% of write cases are refused and nothing is written.
- The schemas validate.

## Risks
- Generated models plus Jackson 3: keep `openApiNullable=false`, as the read build does.
