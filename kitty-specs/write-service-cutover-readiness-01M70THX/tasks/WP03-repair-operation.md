---
work_package_id: WP03
title: 'Command-cli: the status-log repair operation end to end'
dependencies:
- WP01
- WP02
requirement_refs:
- FR-001
- FR-002
- FR-005
- FR-006
- FR-007
- FR-008
- NFR-006
- SC-001
- SC-002
- C-008
planning_base_branch: kitty/mission-write-service-cutover-readiness-01M70THX
merge_target_branch: main
branch_strategy: Planning artifacts for this mission were generated on kitty/mission-write-service-cutover-readiness-01M70THX. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into main unless the human explicitly redirects the landing branch.
subtasks:
- T012
- T013
- T014
- T015
- T016
history:
- timestamp: '2026-11-09T20:30:00Z'
  lane: planned
  agent: system
  action: Prompt generated via /spec-kitty.tasks
agent_profile: java-jenny
authoritative_surface: service/command-cli/src/main/java/org/speckitty/commandcli/application/repair/
create_intent:
- service/command-cli/src/main/java/org/speckitty/commandcli/application/repair/StatusLogRepairService.java
- service/command-cli/src/main/java/org/speckitty/commandcli/application/repair/RepairOutcome.java
- service/command-cli/src/main/java/org/speckitty/commandcli/adapter/in/http/operations/StatusLogRepairController.java
- service/command-cli/src/test/java/org/speckitty/commandcli/application/repair/StatusLogRepairServiceTest.java
- service/command-cli/src/test/java/org/speckitty/commandcli/adapter/in/http/operations/StatusLogRepairHttpTest.java
execution_mode: code_change
owned_files:
- service/command-cli/src/main/java/org/speckitty/commandcli/application/repair/**
- service/command-cli/src/main/java/org/speckitty/commandcli/application/write/port/StatusLog.java
- service/command-cli/src/main/java/org/speckitty/commandcli/adapter/out/statuslog/**
- service/command-cli/src/main/java/org/speckitty/commandcli/adapter/in/http/operations/StatusLogRepairController.java
- service/command-cli/src/main/java/org/speckitty/commandcli/adapter/in/http/edge/Operations.java
- service/command-cli/src/main/java/org/speckitty/commandcli/config/ServiceWiring.java
- service/command-cli/src/test/java/org/speckitty/commandcli/application/repair/**
- service/command-cli/src/test/java/org/speckitty/commandcli/adapter/in/http/operations/StatusLogRepairHttpTest.java
- service/command-cli/src/test/java/org/speckitty/commandcli/adapter/in/http/operations/WriteRig.java
- service/command-cli/src/test/java/org/speckitty/commandcli/adapter/in/http/operations/OperationsTest.java
- service/command-cli/src/test/java/org/speckitty/commandcli/adapter/in/http/edge/EdgeOrderTest.java
- service/command-cli/src/test/java/org/speckitty/commandcli/config/ServiceWiringTest.java
role: implementer
tags: []
tracker_refs: []
---
# Work Package Prompt: WP03 — Command-cli: the status-log repair operation end to end

## ⚡ Do This First: Load Agent Profile

Load your assigned profile before reading anything else: `/spk-charter-profile-load java-jenny`
(or `spec-kitty agent profile show java-jenny`). Adopt its identity, scope and boundaries.

## Objective

Wire `POST /api/v1/missions/{missionId}/status-log-repairs`, as WP01 defined it in `contracts/mission-write`, to WP02's event-log primitive, so that User Story 1 holds end to end:

- `200 {repaired, action, movedBytes, quarantinedFile, readableAfterRepair}`;
- the same transport guards, location verification, **the same in-process turn**, the same Mission lock and the same budget as a write;
- `409 STATUS_LOCK_HELD`, `422 WRITE_LOCATION_UNVERIFIED`, `503 SERVICE_DRAINING`, and `500` for persistence failures;
- **no new refusal code**: core's client parses `WriteRefusalCode` as a closed enum.

## Context (read first)

- Spec: [spec.md](../spec.md) User Story 1 (all 9 scenarios), FR-001, FR-002, FR-005 to FR-008, NFR-006, SC-001, SC-002, C-008, and the edge cases.
- Plan: [plan.md](../plan.md) D-B, and **"Post-plan adversarial squad"**: the classifier in context, the shared turn, `readableAfterRepair` failures, the multi-row retry, constructor stability.
- Contract (merged from WP01): `contracts/mission-write/paths/missions_missionId_status-log-repairs.yaml` and `schemas/StatusLogRepair*.yaml`.
- Event-log API (merged from WP02): `StatusLogSession.repairTail(TailClassifier)`, `TailClassifier.isCompleteRow(prefix, fragment)` and `TailRepair{action, movedBytes, bytesAfter}`.
- Seams in `service/command-cli/src/main/java/org/speckitty/commandcli/` (base `62af21d0`):
  - **`application/write/WriteService.java`:**
    - the catch-all is at 59-65;
    - the surface check is at 70-72 (`coordination_ref_commit` → 422);
    - `find`, `RootUnverifiable` and `locationMatches` are at 73-84;
    - `lockedWrite` is at 126-141 (deadline :127, turn :128-130, `withLock` :131, `LockResult` switch :133-139);
    - `insideLock` is at 143-171.

    **Do not copy the location logic.** Extract the surface + find + location verification into a small package-level helper both services call (for example `application/write/WriteLocation.java`, or a static method on `WriteService`), keeping `WriteService`'s behaviour identical. That one extraction inside `application/write/` is out of map and allowed; record a one-line rationale in the commit.
  - **`application/write/AppendVerifier.java`:** `decodeRows` is at 33-42, `firstUnreadable` at 19-30. These are the read-back strictness to reuse.
  - `application/write/WriteTurn.java` (fair `ReentrantLock`) and `application/write/port/StatusLog.java` (Session, `LockResult`).
  - **`adapter/out/statuslog/EventLogStatusLog.java:23-55`:** the mapping is `StatusLockHeldException` → LockHeld and `IOException` → PersistenceFailed.
  - **`adapter/in/http/operations/WriteController.java`:** routes are at 37-53, the catch-all at 55-61, draining at :64, `refuse` at 86-90. Copy its shape: `@RequestBody byte[]`, an ordered-map body as in `AckBody.java` (records are not serialised, so there are no native hints), and the problem+json refusal.
  - **`adapter/in/http/edge/Operations.java`:** the `WRITE` regex is at :17 and `needsProtocol` at 27-29. Only `needsProtocol` must learn the new path; the body cap and `ActivityFilter` apply to every path.
  - **`config/ServiceWiring.java:71-74`:** the root, catalog, status log, turn and 10 s budget are built **inline** in the `writeService` bean.
  - `adapter/out/mission/FilesystemMissionCatalog.java`: `find` and `locationMatches` (56-64).
- Tests:
  - `WriteRig.java` (`postMission` switch at 88-93; `replaceMission(initialLog)` at 122-138 can seed a torn tail; `tree()` at 104-116 shows that nothing was created);
  - `ContractSchemas.violations(schemaName, json)` (35-45);
  - `OperationsTest` ("six operations"), `EdgeOrderTest` (target lists at :115 and :134), `ServiceWiringTest:22-33`;
  - `WriteServiceTest.FakeLog` (an anonymous `Session` near :486).

## Subtasks

### T012 — Shared beans

- **The change.** Refactor `ServiceWiring` so that `WriteTurn`, `StatusLog` (`EventLogStatusLog`), `MissionCatalog` and the budget (`Duration` 10 s plus `LongSupplier nanoClock`) are each **one bean**, injected into both `WriteService` and `StatusLogRepairService`.
- **Why.** FR-006 is otherwise silently lost: the flock still excludes within one JVM on Linux, so a test with two turns would pass anyway.
- **Unchanged.** `ServiceWiringTest` must still assert the 10 s budget. Keep `WriteService`'s public constructor(s) source-compatible: add, do not change.

### T013 — Port and adapter

- **Port.** `StatusLog.Session` gains:
  ```java
  default RepairResult repairTail(TailCheck check) { throw new UnsupportedOperationException("repair"); }
  ```
  `RepairResult` is a command-cli port record `{Action action, long movedBytes, byte[] bytesAfter}` with `Action{NONE, COMPLETED, QUARANTINED}`. `TailCheck` is a command-cli functional interface `(byte[] prefix, byte[] fragment) -> boolean`.

  The `default` keeps `WriteServiceTest.FakeLog` and other fakes compiling. The application layer must not import event-log types (`ArchitectureRulesTest` 82-90).
- **Adapter.** `EventLogStatusLog` implements it by adapting to `session.repairTail(classifier)`. It maps `TailRepair` to `RepairResult`, and uses the existing exception mapping: `IOException` (including `PersistenceFailedException`) → PersistenceFailed, held → LockHeld.

### T014 — `StatusLogRepairService`

In `application/repair/`:

- **Input:** `missionId` plus a parsed `RequestContext`. **Output:** a `RepairOutcome` (sealed): `Repaired(action, movedBytes, readableAfterRepair)` or `Refused(RefusalCode, detail)`.
- **Steps:**
  1. Location verification, through the extracted helper. It gives the same refusals as a write, including `422 WRITE_LOCATION_UNVERIFIED` for `coordination_ref_commit`, a separate git dir, a mismatched directory, or an unknown Mission (404, whatever the write path answers).
  2. The deadline, as `nanoClock + budget`, then the **shared** turn. A timeout gives `SERVICE_BUSY`, exactly as a write does.
  3. `statusLog.withLock(mission, deadline, session -> session.repairTail(this::isCompleteRow))`, then the readability check. Map LockHeld to `409 STATUS_LOCK_HELD` and PersistenceFailed to `500 STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED` (fixed detail, no path).
- **`isCompleteRow(prefix, fragment)`**, in context: `false` on any exception.
  1. `codec.decode(fragment + "\n")` must yield exactly one row value;
  2. `codec.decode(prefix)` must decode;
  3. `WriteFold.fold(prefixRows ++ [row])` must succeed.

  Reuse `AppendVerifier`'s decode step, or expose a small package-visible helper from it. A row whose `event_id` spells like an earlier one, or a row the fold rejects, is **not** complete.
- **`readableAfterRepair`**, computed inside the lock from `result.bytesAfter()`:
  - `COMPLETED`: `true` by construction;
  - `NONE` or `QUARANTINED`: `decode(bytesAfter)` plus `WriteFold.fold` succeeds;
  - any `RuntimeException` or `UnreducibleLog`: `false`, **never** a 500.

  At most two folds in total (NFR-006).
- **Catch-all.** Any `RuntimeException` outside the above gives a coded 500, mirroring `WriteService:59-65`. WP07 adds logging later; leave a clear seam (one method).

### T015 — Controller and edge

- **`StatusLogRepairController`** (`@RestController` in `adapter.in.http.operations`), `@PostMapping("/api/v1/missions/{missionId}/status-log-repairs")`:
  - draining check first → `503 SERVICE_DRAINING`, no `Retry-After` (copy `WriteController:64`);
  - parse `byte[]` as a closed object `{context}`, using the same JSON strictness and `RequestContext` parsing the write path uses (`WriteBodies`). An unknown member or a bad context gives `400 REQUEST_MALFORMED`;
  - call the service and answer `200` with an ordered map in key order `repaired, action, movedBytes, quarantinedFile, readableAfterRepair`, where `quarantinedFile` is `"status.events.jsonl.torn"` or `null`;
  - catch-all as in `WriteController:55-61`.
- **Edge.** `Operations.needsProtocol` matches the new path. Extend `WRITE`, or add a sibling pattern and include it.
- **Tests.** Update `OperationsTest` (now seven operations) and both `EdgeOrderTest` target lists, so that the order of checks (403/401/400/413/400) is proven for the new path too.

### T016 — Tests

Through `WriteRig`; extend it with `postRepair(missionId, contextJson)` and a raw-log seeder. Every answer body is validated with `ContractSchemas.violations("StatusLogRepairAck", …)` or the refusal schema. Pair every "nothing changed" assertion with a positive control on the same fixture.

| # | Scenario | Assert |
|---|---|---|
| 1 | Mid-JSON tail | 200 `quarantined`; `movedBytes` = fragment length; log = prefix; `.torn` = fragment + LF; `readableAfterRepair: true`; then a normal write succeeds (201) |
| 2 | Complete foldable row without LF | 200 `completed`, `movedBytes: 0`, `quarantinedFile: null`, one LF added, no `.torn` |
| 2b | Complete row colliding with an earlier `event_id` | 200 `quarantined`, not completed (squad MAJOR 1) |
| 3 | Healthy, empty, missing, and already-repaired logs | 200 `none`, `repaired: false`, `tree()` identical, no file created |
| 4 | Torn log, then a normal write | still 500 `STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED`, log unchanged (C-001 regression) |
| 5 | Mission lock held externally (the pattern the write tests use) | 409 `STATUS_LOCK_HELD` after the budget (use a short test budget if the rig allows), nothing changed |
| 6 | Service draining | 503 `SERVICE_DRAINING` |
| 7 | `writeSurface: coordination_ref_commit`, and a mismatched `missionDirectory` | 422 `WRITE_LOCATION_UNVERIFIED`, nothing changed |
| 8 | No protocol header; bad token; foreign Host | 400 / 401 / 403, through `EdgeOrderTest` |
| 9 | Unknown body member | 400 `REQUEST_MALFORMED` |
| 10 | Concurrency: N threads interleaving writes and repairs on a torn-then-repaired log | every write sees the torn log (500) or the repaired one (201); final log = prefix + appended units, no interleaving |
| 11 | Shared turn | hold the turn from a write (a blocking test hook, or a `WriteTurn` exposed by the rig); a repair waits, and times out as `SERVICE_BUSY` when the holder outlasts the budget |
| 12 | Torn two-row batch | repair keeps row 1; retrying the same batch gives 409 `EVENT_ID_CONFLICT`; re-issuing with new ids succeeds (runbook evidence) |
| 13 | `readableAfterRepair: false` | a quarantine of a log whose prefix is unreducible (for example a row the fold rejects) answers 200 with `readableAfterRepair: false`, not 500 |

`StatusLogRepairServiceTest` unit-tests `isCompleteRow` against the decision table above, with a fake `StatusLog`.

## Definition of Done

- **Tests:** `./gradlew :command-cli:check` is green, including `ArchitectureRulesTest`, `WriteOwnershipRulesTest` and the existing write tests, which must be unchanged in behaviour. `./gradlew :event-log:check` is still green.
- **Contract:** every new answer validates against the contract.
- **Quality:** no new refusal code and no host path in any body or message. Method complexity at most 15.
- **Commits:** commit messages end with `Co-Authored-By:` (your model).

## Risks and reviewer guidance

- **The turn:** confirm one `WriteTurn` instance in the Spring context (inspect `ServiceWiring`), then read test 11.
- **The classifier:** confirm it folds **with the prefix**, then read test 2b.
- **Readability:** confirm a readability failure can never become a 500.
- **Byte conservation** is WP02's; the WP03 tests must still check the files after every refusal.

## Do not

- Do not edit event-log (WP02 owns it; ask the orchestrator if its API is insufficient), the contract (WP01), drain plumbing (WP06), or logging (WP07).
- Do not run `spec-kitty` lifecycle commands, push, or open a PR.
