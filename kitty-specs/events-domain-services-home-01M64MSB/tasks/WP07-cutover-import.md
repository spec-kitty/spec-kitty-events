---
work_package_id: WP07
title: History-preserving import and build integration (cutover-gated)
dependencies:
- WP03
- WP04
- WP06
requirement_refs:
- FR-010
- C-001
- C-002
- SC-003
planning_base_branch: issue-147-events-services-home
merge_target_branch: issue-147-events-services-home
branch_strategy: Planning artifacts for this mission were generated on issue-147-events-services-home. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into issue-147-events-services-home unless the human explicitly redirects the landing branch.
subtasks:
- T030
- T031
- T032
- T033
- T034
history:
- timestamp: '2026-10-29T20:40:00Z'
  lane: planned
  agent: system
  action: Prompt generated via /spec-kitty.tasks
agent_profile: java-jenny
authoritative_surface: service/
create_intent:
- service/build.gradle.kts
- service/core-pin.json
- contracts/build.gradle
- contracts/settings.gradle
- docs/architecture/event-ledger-writers-and-readers.md
execution_mode: code_change
owned_files:
- service/**
- contracts/mission-status/**
- contracts/mission-write/**
- contracts/_shared/**
- contracts/tools/**
- contracts/lint/**
- contracts/gradle/**
- contracts/build.gradle
- contracts/settings.gradle
- contracts/README.md
- docs/architecture/event-ledger-writers-and-readers.md
role: implementer
tags:
- cutover-gated
tracker_refs: []
---
# Work Package Prompt: WP07 – History-preserving import and build integration

## ⛔ GATE: do not start without the operator's cutover go-signal

Spec C-001 applies: no code import before the operator's explicit cutover go-signal. The go-signal requires all of the following:
- the active core missions are merged
- core's freeze gate has landed (session B)
- the operator states the go in this session

If any of these is missing, **stop** and report this WP as blocked. An implementer that claims this WP early must release it.

## ⚡ Do This First: Load Agent Profile

Load your assigned profile before reading anything else: `/spk-charter-profile-load java-jenny`
(or `spec-kitty agent profile show java-jenny`). Adopt its identity, scope and boundaries.

## Branch Strategy

The planning base and the merge target are both `issue-147-events-services-home`. The PR targets `main`, possibly as a separate PR if the operator wants the import reviewed apart. The workspace comes from `lanes.json`.
`spec-kitty agent action implement WP07 --agent claude --mission events-domain-services-home-01M64MSB`

## Procedure

Follow `docs/guides/service-cutover-runbook.md` (WP06) step by step. This prompt lists what this WP owns.

### T030 Extraction and merge
Run runbook steps 2–3 at the frozen core SHA. The merge commit records the SHA and the filter-repo command. **Imported commits stay unmodified**: no rebase or squash across them.

### T031 Path fixes and README merge
- `service/build.gradle.kts`: change the `contractRoot` default from `../../contracts` to `../contracts`.
- `service/src/test/java/org/speckitty/missionstatus/adapter/in/http/contractdoc/ContractDocumentTest.java`: change `../../contracts` to `../contracts`.
- Verify that `ArchitectureControlsTest.java:174` (a synthetic URL) still passes.
- Grep the imported tree for `services/mission-status-api` and update any path that is real and not just a comment. Leave historical comments alone.
- Grep for `github.repository == 'spec-kitty/spec-kitty'` (none should be imported, because workflows are not imported).
- Resolve the `contracts/README.md` conflict as the runbook says.

### T032 Root Gradle integration
- Root `settings.gradle.kts`: include `:mission-status-api` (`service/`), `:domain-model` (`service/domain-model`) and `:event-log` (`service/event-log`). The service's own `settings.gradle.kts` and wrapper are removed, and its root project becomes `:mission-status-api`. Fix the cross-module `project(":…")` references.
- Merge `service/gradle/libs.versions.toml` into the root catalog and unify the versions (Spring Boot 4.1.1, ArchUnit, json-schema 3.0.8 shared with the binding). Regenerate the root `verification-metadata.xml` and drop the service's own copy.
- Fold the per-module toolchain, JaCoCo and test boilerplate into `build-logic` conventions (`events.service`, `events.library`) only where behaviour is identical. Keep every custom task (`nativeTagJvmTest`, `nativeSmoke`, `nativePerf`, `perfTest`, `kitTest`, `jacocoScopeCheck`, `bundleContract`) working.
- `contracts/`: run its Gradle build under the root wrapper (9.8.1), either included or as a separate settings file, whichever the dry run shows works. Update `contracts/tools/pins.json` and `gradle_pin_check.py` expectations from 8.14.5 to match.
- `VerificationTamperTest` and `ContractDriftNestedBuildTest` (TestKit) may assume a standalone build. Adapt them minimally and record each change.

### T033 Core pin, jobs, doc move
- `service/core-pin.json`: `{"repository": "spec-kitty/spec-kitty", "sha": "<frozen SHA>", "purpose": "CI-only peers: lock interop, corpus oracle, golden regeneration (ADR 0002 D4)"}`.
- Give `PythonPeer.java` a way to find the peer script outside the repo (a system property or env, defaulting to the old relative path). Wire it in `service.yml`'s lock-interop job; that edit is out of map, so record a one-line rationale.
- `git mv docs/architecture/event-ledger-writers-and-readers.md docs/architecture/services/event-ledger-writers-and-readers.md`. Add DocKitty frontmatter and link it from the services index (out of map, with rationale).

### T034 Verification
- Run `./gradlew verifyAll`. Record the counts per module; the expected totals are about 883 `@Test` + 66 parameterized + 6 factories, more at runtime.
- Run `make test-fast`, the conformance gate, ruff and the doc checks.
- Push the branch only; no tag. `service.yml`'s guarded jobs must now run green on the PR.

## Definition of Done
- The history is present: `git log --follow service/build.gradle.kts` reaches into 2026-10.
- The root build is green, with the ArchUnit and contract conformance tests passing.
- The Python gates are green and the core pin is recorded.
