# Tasks: Write-service cut-over readiness

**Mission**: `write-service-cutover-readiness-01M70THX` · **Spec**: [spec.md](./spec.md) · **Plan**: [plan.md](./plan.md) (read its "Post-plan adversarial squad: folded findings" section; it overrides the Design section where they differ)
**Branch**: planning on `kitty/mission-write-service-cutover-readiness-01M70THX`; merge target `main` via `issue-236-write-service-cutover-readiness` (one PR, D9).

## Subtask Index

| ID | Description | WP | Parallel |
|---|---|---|---|
| T001 | Repair path file + `openapi.yaml` path entry | WP01 | |
| T002 | Repair schemas (request, ack, action enum), index, enum pin, examples | WP01 | |
| T003 | `TransitionUnit.implementationEvidencePresent` | WP01 | [P] |
| T004 | Descriptions: WriteOperation x-provisional, refusal reasons/codes, drain wording | WP01 | [P] |
| T005 | Contract CHANGELOG `1.0.1-SNAPSHOT` | WP01 | |
| T006 | Run every contract tool against a real baseline; record output | WP01 | |
| T007 | `FileOps` additions + Nth-call fault fake + existing fakes updated | WP02 | |
| T008 | `StatusLogSession.repairTail` + `TailClassifier` + `TailRepair` result | WP02 | |
| T009 | `LockedStatusLogSession` repair: size re-check, complete, quarantine, rollback | WP02 | |
| T010 | Quarantine file-type guard (lstat: absent or regular) | WP02 | |
| T011 | Fault matrix (NFR-001) + action tests + torn-write regression | WP02 | |
| T012 | Shared beans in `ServiceWiring` (turn, status log, catalog, budget) | WP03 | |
| T013 | command-cli `StatusLog.Session.repairTail` port + `EventLogStatusLog` adapter | WP03 | |
| T014 | `StatusLogRepairService` (location, turn, lock, classifier-in-context, readability) | WP03 | |
| T015 | `StatusLogRepairController` + `Operations.needsProtocol` + ordered-map ack | WP03 | |
| T016 | HTTP acceptance tests (User Story 1), concurrency, shared-turn, multi-row retry | WP03 | |
| T017 | Wire parsing: `WriteBodies`, `UnitRequest`, `UnitInput` (delegating ctors) | WP04 | |
| T018 | Authority: `UnitJudge` → `Guards.context` evidence override | WP04 | |
| T019 | Corpus schema + generator + new oracle-backed cases | WP04 | |
| T020 | `unreachable.json` + corpus READMEs | WP04 | [P] |
| T021 | Java corpus consumers (HTTP representative per reason×field, all `UnitInput` readers) | WP04 | |
| T022 | Byte-parity + replay tests for the field; package changelog fragment | WP04 | |
| T023 | Root into `Authority`/`UnitJudge` construction; `Guards.context` shape (#223) | WP04 | |
| T024 | Wire the root in `ServiceWiring`; tests (exact string, no byte change) | WP04 | |
| T025 | Three-state `WriteTurn` acquisition (acquired / timed out / abandoned) | WP05 | |
| T026 | Event-log abandon-aware `LedgerLock.acquire` / `withMissionLock` overloads + exception | WP05 | |
| T027 | command-cli `withLock` overload + `LockResult.Abandoned` → 503 in write and repair | WP05 | |
| T028 | `DrainTest`: waiting write/repair → 503 ≤ 1 s; holder finishes 201; drain ≤ 5 s | WP05 | |
| T029 | Class-chain formatter (cycle-safe, depth-capped) | WP06 | |
| T030 | `System.Logger` WARNING in the four catch-alls | WP06 | |
| T031 | Log-capture tests: one record per failure; marker hygiene | WP06 | |
| T032 | `event-log-lock-and-append.md` + `service-protocol-and-releases.md` corrections | WP07 | [P] |
| T033 | command-cli README + `write-service-protocol.md` (surface, steps, reasons, drain, stale #6910) | WP07 | [P] |
| T034 | Cut-over runbook: repair stopgap, multi-row retry, quarantine file | WP07 | [P] |
| T035 | Changelog fragments `236-*.md` | WP07 | |
| T036 | Prior mission record: #222 issue-matrix row | WP07 | [P] |
| T037 | Docs validation + index clean | WP07 | |

## WP01 — Contract: repair operation, evidence field, wording, CHANGELOG

**Goal**: every `mission-write` contract change of the mission, additive within `1.0.1-SNAPSHOT`, proven by the contract tools against a real baseline. **Priority**: P1 (foundation). **Depends on**: none. **Prompt**: [tasks/WP01-contract.md](./tasks/WP01-contract.md)

T001 Repair path file + `openapi.yaml` path entry (WP01)
T002 Repair schemas (request, ack, action enum), index, enum pin, examples (WP01)
T003 `TransitionUnit.implementationEvidencePresent` (WP01)
T004 Descriptions: WriteOperation x-provisional, refusal reasons/codes, drain wording (WP01)
T005 Contract CHANGELOG `1.0.1-SNAPSHOT` (WP01)
T006 Run every contract tool against a real baseline; record output (WP01)

Risks: `breaking_check` with no baseline is vacuous; Java citations fail `citation_check`; property enums cannot be pinned.

## WP02 — Event-log: locked status-log tail repair

**Goal**: the only code that mutates a torn log, under the Mission lock, conserving every byte under any single fault. **Priority**: P1. **Depends on**: none. **Prompt**: [tasks/WP02-event-log-tail-repair.md](./tasks/WP02-event-log-tail-repair.md)

T007 `FileOps` additions + Nth-call fault fake + existing fakes updated (WP02)
T008 `StatusLogSession.repairTail` + `TailClassifier` + `TailRepair` result (WP02)
T009 `LockedStatusLogSession` repair: size re-check, complete, quarantine, rollback (WP02)
T010 Quarantine file-type guard (lstat: absent or regular) (WP02)
T011 Fault matrix (NFR-001) + action tests + torn-write regression (WP02)

Risks: byte loss; truncating foreign bytes; blocking on a FIFO under the flock.

## WP03 — Command-cli: the repair operation end to end

**Goal**: `POST /api/v1/missions/{missionId}/status-log-repairs` behaves as User Story 1. **Priority**: P1. **Depends on**: WP01, WP02. **Prompt**: [tasks/WP03-repair-operation.md](./tasks/WP03-repair-operation.md)

T012 Shared beans in `ServiceWiring` (turn, status log, catalog, budget) (WP03)
T013 command-cli `StatusLog.Session.repairTail` port + `EventLogStatusLog` adapter (WP03)
T014 `StatusLogRepairService` (location, turn, lock, classifier-in-context, readability) (WP03)
T015 `StatusLogRepairController` + `Operations.needsProtocol` + ordered-map ack (WP03)
T016 HTTP acceptance tests (User Story 1), concurrency, shared-turn, multi-row retry (WP03)

Risks: a second turn (FR-006 silently lost); completing an unfoldable row.

## WP04 — Evidence wire field, corpus parity, and the real root (#224, #223)

**Goal**: `implementationEvidencePresent` decides the evidence half exactly as core's authority does, with oracle-backed corpus cases over HTTP and in the domain; the guard context carries the real root instead of `<root>` (same files, so one WP). **Priority**: P2. **Depends on**: WP01. **Prompt**: [tasks/WP04-evidence-wire-field.md](./tasks/WP04-evidence-wire-field.md)

T017 Wire parsing: `WriteBodies`, `UnitRequest`, `UnitInput` (delegating ctors) (WP04)
T018 Authority: `UnitJudge` → `Guards.context` evidence override (WP04)
T019 Corpus schema + generator + new oracle-backed cases (WP04)
T020 `unreachable.json` + corpus READMEs (WP04)
T021 Java corpus consumers (HTTP representative per reason×field, all `UnitInput` readers) (WP04)
T022 Byte-parity + replay tests for the field; package changelog fragment (WP04)
T023 Root into `Authority`/`UnitJudge` construction; `Guards.context` shape (#223) (WP04)
T024 Wire the root in `ServiceWiring`; tests (exact string, no byte change) (WP04)

Risks: the new case displacing the subtask case over HTTP; silent field drops in corpus readers.

## WP05 — Drain-aware wait (D10, #231 4.7)

**Goal**: a request waiting at drain answers 503 within 1 s; a holder finishes; the launcher contract is unchanged. **Priority**: P2. **Depends on**: WP03, WP04. **Prompt**: [tasks/WP05-drain-aware-wait.md](./tasks/WP05-drain-aware-wait.md)

T025 Three-state `WriteTurn` acquisition (acquired / timed out / abandoned) (WP05)
T026 Event-log abandon-aware `LedgerLock.acquire` / `withMissionLock` overloads + exception (WP05)
T027 command-cli `withLock` overload + `LockResult.Abandoned` → 503 in write and repair (WP05)
T028 `DrainTest`: waiting write/repair → 503 ≤ 1 s; holder finishes 201; drain ≤ 5 s (WP05)

Risks: dropping a held flock; core blocked; mapping abandonment to 500 or SERVICE_BUSY.

## WP06 — Catch-all logging (#231 4.14)

**Goal**: one content-free WARNING per unexpected failure. **Priority**: P2. **Depends on**: WP05. **Prompt**: [tasks/WP06-catch-all-logging.md](./tasks/WP06-catch-all-logging.md)

T029 Class-chain formatter (cycle-safe, depth-capped) (WP06)
T030 `System.Logger` WARNING in the four catch-alls (WP06)
T031 Log-capture tests: one record per failure; marker hygiene (WP06)

## WP07 — Docs, runbook, changelog fragments, records

**Goal**: the docs say what the service does; fragments and records are complete. **Priority**: P3. **Depends on**: WP03, WP04, WP05, WP06. **Prompt**: [tasks/WP07-docs-and-records.md](./tasks/WP07-docs-and-records.md)

T032 `event-log-lock-and-append.md` + `service-protocol-and-releases.md` corrections (WP07)
T033 command-cli README + `write-service-protocol.md` (surface, steps, reasons, drain, stale #6910) (WP07)
T034 Cut-over runbook: repair stopgap, multi-row retry, quarantine file (WP07)
T035 Changelog fragments `236-*.md` (WP07)
T036 Prior mission record: #222 issue-matrix row (WP07)
T037 Docs validation + index clean (WP07)

## Dependencies and waves

WP01 ∥ WP02 → WP03 ∥ WP04 → WP05 → WP06 → WP07.

**File ownership is disjoint.** Shared hot files have one owner each: `ServiceWiring.java` → WP03; `WriteService.java`, `WriteTurn.java`, `LedgerLock.java` → WP05; `WriteController.java` → WP06. A later WP's small edit to an earlier WP's file is a *declared out-of-map edit*, listed in that WP's prompt with its rationale.

MVP: WP01 + WP02 + WP03 (the repair operation, the hard gate before core #6195).
