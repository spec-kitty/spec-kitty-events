# Feature Specification: Write-service cut-over readiness: status-log repair, evidence wire field, operator hardening

**Feature Branch**: `kitty/mission-write-service-cutover-readiness-01M70THX` (lands as `issue-236-write-service-cutover-readiness`, one PR to `main`)
**Created**: 2026-11-09
**Status**: Draft
**Input**: Issue #236 (Mission M1: Write-service cut-over readiness). It closes #222, #223 and #224, and partly closes #231 (items 4.7, 4.12, 4.14 and 4.15). Inputs are the maintainer rulings of 2026-11-09 (D1–D4, D9), the squad synthesis and write-service research, and the record of mission `events-write-core-01M6TP6W` (research §11, rulings R15–R31).

## Intent Summary

Core spec-kitty/spec-kitty#6195 deletes core's Python status-log writer. Every status write then goes through the events write service. Until then, core's writer quietly heals a torn status log and honours an explicit "no implementation evidence" flag. After #6195 neither happens unless the service does it. This mission makes the service ready to be the only writer:

- **Primary actor:** the operator of a Spec Kitty project (through core's CLI, and through `curl` until core ships its verb), plus core's #6195 client.
- **Triggers:**
  - A Mission whose `status.events.jsonl` ends in a torn tail (a last line without LF), so every write and strict read fails.
  - A handoff (`in_progress → for_review`) that the user marks as having no implementation evidence.
- **Desired outcome:**
  - One explicit request restores a torn log without losing a byte.
  - A healthy log is never touched.
  - The evidence refusal behaves over the wire exactly as it does in core today.
  - The service gives the operator enough to diagnose a failure: a log line on an unexpected 500, no lost answer at shutdown, and contracts and docs that say what the service really does.
- **Invariant:** the write path never repairs implicitly; it keeps refusing a torn tail (prior mission C-006). Repair is a separate, explicit, locked operation.

Intent is confirmed by the maintainer's brief (#236) and the binding rulings below. No discovery questions were open.

## Maintainer Rulings (binding, 2026-11-09)

| Ruling | Decision | Decision Moment |
|---|---|---|
| **D1** | Repair on a healthy log returns `200` with `action: none`. The log stays byte-identical. | `DM-01M70TJ9BH1NX4KGH0B8NGSK7Y` |
| **D2** | Repair is a core CLI verb that calls a new HTTP operation on the service. This mission builds the service operation and its contract. The core verb is a core follow-up, filed in spec-kitty/spec-kitty and linked to #6195. | `DM-01M70TJC0BFPXF1K8FWZQFB533` |
| **D3** | Add the optional wire field for #224. #6195 must document rolling-upgrade behaviour: an older service answers `400` to the unknown field, because `TransitionUnit` is closed. This is commented on #6195. | `DM-01M70TJENKYFJP8TAX04SQR3ZT` |
| **D4** | Align `validate_transition` with core and take the minor version bump. **Scope:** this belongs to sibling mission M2 (#237, item 4.6), not to this mission; it is recorded here because it was ruled together. | `DM-01M70TJHAYRAZ7293C10BWXQM4` |
| **D9** | One PR for the whole of M1. | `DM-01M70TJKYZH27H8AJGMZBCQ816` |
| **D10** (orchestrator ruling, confirmed by the maintainer) | #236 AC 5 asked for a drain budget greater than the write budget. That conflicts with two things: the published launcher contract (`contracts/mission-write/LAUNCHER.md:50-57`: the drain finishes held writes in "at most 5 s", and the launcher SIGTERMs after 10 s), and core's launcher (`launcher.py:83`, `STOP_TIMEOUT_S = 10.0`; `LauncherContractProcessTest` NFR-003). So the 5 s drain and the launcher contract stay. Once draining starts, a write or repair still **waiting** for the turn or the Mission lock answers `503 SERVICE_DRAINING` and writes nothing. One already **holding** the lock finishes. AC 5 is rewritten to match (commented on #236). | `DM-01M70VBEVRGGBE4BJGMFSFDYH2` |
| **D11** (maintainer ruling, 2026-11-10, from the post-PR squad) | #236 AC 6 ("`breaking_check.py` is clean") conflicted with `x-provisional` on `WriteOperation` (#231 4.12). Stripping the provisional schema removed the inlined, stable `TransitionUnit.operation` from the comparison and reported 2 false breaks. Fix: keep `x-provisional`, and change `contracts/tools/breaking_check.py` so that a stable property which only references a provisional schema is kept. Its presence and requiredness are still compared; only the provisional body and enum are skipped. Tool tests pin both the kept property and its removal. AC 6 is then met literally: exit 0, no breaks. Recorded on #236; the tooling change is noted on #151. | `DM-01M723SBFP2Y9MTA2JYF654RGQ` |

**Correction to the research behind D3 (scout, 2026-11-09).** Core's `--implementation-evidence-present` is a flag-only option (`bool | None` with no `--no-` form) at `agent/status.py:419-425` and `orchestrator_api/wp_lifecycle.py:853` on core `main` @ `366ff233`. No core CLI door can send `false`; only the Python API that #6195 deletes can. So dropping the flag at cut-over would change nothing a CLI user can see. D3 still stands, as **additive parity with core's `TransitionRequest`**: the wire can say what core's authority can decide, and a future client door can use it. User Story 2 is P2, not a cut-over regression.

**Blank workspace context (#223), routed to core.** `agent status emit --workspace-context ""` is refused today, because core does not normalise the value and its guard fires. After #6195 the wire carries no context and the service synthesises one, so that malformed input is accepted. The service cannot see a blank value it is never sent, so this is not a service change. It goes into the core follow-up (FR-022): core's client should refuse a blank `--workspace-context` at argument parsing, or #6195 should accept and document the change.

## Divergence Analysis (judged by *why* core behaves as it does)

| Difference from core | Why core behaves so | Ruling for this mission |
|---|---|---|
| Core heals a torn tail on every write by adding a LF before its atomic replace. | A side effect of temp-and-rename, not a policy. Core's own writer cannot tear the log. | Keep the service's refusal on the write path. Add an explicit repair. Where the fragment is one complete, readable row, the repair completes it exactly as core's heal would (`completed`). Otherwise it quarantines the fragment, never deletes it (`quarantined`). |
| Core honours an explicit `implementation_evidence_present=false` and infers only when the flag is absent. | Deliberate. Inference is a convenience; the explicit flag is a real capability of `agent status emit` and the orchestrator API. | Match. Add an optional wire field honoured the same way. |
| `workspace_context_required` cannot be produced by any wire request. | Deliberate. A fail-closed guard for core's own batch API (operator decision 2026-09-07, #946, DRIFT-3); every wire door supplies or synthesises a context. | Match. Keep the reason, and say why in the contract and docs. |
| The service builds the guard's workspace context from a literal `<root>`; core's default synthesis uses the real root (`transition_pipeline.py:364-367` on core main). | Accidental. A placeholder in the service, not a core behaviour. Its only consumer is a blank check, so the change is honesty, not behaviour. Core's CLI doors set their own door-prefixed values (`move-task:`, `orchestrator-api:`, `merge:`), which the wire does not carry. | Fix: use the resolved root in core's default shape. The value is never persisted, so no bytes change. |

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Operator repairs a torn status log (Priority: P1)

After a host crash, a disk fault or a hand edit, a Mission's status log ends in an unterminated fragment. Every write answers `500` and core's strict reads fail. The operator asks the service to repair that Mission's status log. The service takes the same locks as a write. It moves the fragment aside (or completes it when it is one whole row), and leaves the log ending at a line boundary. It reports exactly what it did.

**Why this priority:** after #6195 this is the only way out of a torn log. Without it, a Mission stays blocked until someone edits the file by hand. It must ship in a `service-v*` rc before #6195.

**Independent Test:** tear a Mission's log in a test rig, call the repair operation, then call a normal write. The repair answers `quarantined` with the fragment length, the fragment is in the quarantine file, and the next write succeeds.

**Acceptance Scenarios:**

1. **Given** a log whose last line is a mid-JSON fragment, **When** the operator requests a repair, **Then** the answer is `200` with `action: quarantined` and `movedBytes` equal to the fragment length. The fragment plus a LF is appended to `status.events.jsonl.torn` beside the log, the log is truncated to its last LF, and both files and the directory are made durable.
2. **Given** the same torn log and a fault injected into any durability step before the truncation is durable, **When** the operator requests a repair, **Then** the answer is a coded `500`, the log is byte-identical to before, and no byte of the fragment is lost.
3. **Given** a log whose last line is exactly one complete status row that the service can read, but has no LF, **When** the operator requests a repair, **Then** the answer is `200` with `action: completed` and `movedBytes: 0`. Exactly one LF is added, and no quarantine file is created.
4. **Given** a healthy log (ends in LF), an empty log, a missing log, or a log already repaired, **When** the operator requests a repair, **Then** the answer is `200` with `action: none` and `repaired: false`. The log is byte-identical and no file is created (D1).
5. **Given** a torn log, **When** a normal write arrives, **Then** it still answers `500 STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED` and changes nothing (the write path never repairs).
6. **Given** another process holds the Mission's status lock for longer than the shared budget, **When** a repair is requested, **Then** it answers `409 STATUS_LOCK_HELD` and nothing changes.
7. **Given** the service is draining, **When** a repair is requested, **Then** it answers `503 SERVICE_DRAINING`.
8. **Given** a coordination-routed Mission (or any location the write path cannot verify), **When** a repair is requested, **Then** it answers `422 WRITE_LOCATION_UNVERIFIED` and nothing changes. This is a documented gap until migration step 4.
9. **Given** a repair and a write for the same Mission arrive together, **When** both run, **Then** they are serialised: the write sees either the torn log (and refuses) or the repaired log (and appends), never a mixture.

---

### User Story 2 - A caller can declare "no implementation evidence" over the wire, as core's authority allows (Priority: P2)

Core's transition request carries an explicit implementation-evidence flag, and core's guard refuses an unforced `in_progress → for_review` when the flag is not true. Inference is used only when the flag is absent. Today only core's Python API can pass `false`; the CLI option is flag-only. The wire cannot carry the flag at all, so the service's authority can never decide that refusal (#224). This story gives the wire the same input core's authority has (D3).

**Why this priority:** this is parity of the authority's inputs, not a cut-over regression (see the correction under D3). It must land before #6195, so core's client can map the flag when it needs to.

**Independent Test:** send an unforced `in_progress → for_review` unit with a complete subtask roster and `implementationEvidencePresent: false`. Expect `409 TRANSITION_REJECTED` with reason `implementation_evidence_required` and nothing written.

**Acceptance Scenarios:**

1. **Given** a WP in `in_progress` with a complete roster, **When** a unit `in_progress → for_review` carries `implementationEvidencePresent: false`, **Then** the answer is `409 TRANSITION_REJECTED` with reason `urn:spec-kitty:write-refusal:implementation_evidence_required`, and the log is unchanged.
2. **Given** the same WP, **When** the unit carries `implementationEvidencePresent: true` or omits the field, **Then** it is accepted, and the appended bytes are identical in both cases and identical to today's bytes.
3. **Given** an incomplete roster, **When** the unit carries `implementationEvidencePresent: true`, **Then** it is still refused for incomplete subtasks (the field never affects the subtask half).
4. **Given** a forced unit, **When** it carries `implementationEvidencePresent: false`, **Then** the guard is skipped exactly as core skips it under force.
5. **Given** an accepted unit that carried the field, **When** it is retried with or without the field, **Then** the retry is a replay (`appendedLines: 0`).
6. **Given** a misspelt field (for example `implementationEvidence`), **When** sent, **Then** the answer is `400 REQUEST_MALFORMED`. The rolling-upgrade failure mode is loud, not silent.

---

### User Story 3 - The operator can diagnose an unexpected failure and never loses an answer at shutdown (Priority: P2)

An unexpected exception on the write path (or the repair path) answers a coded `500` today but leaves no trace. A shutdown that arrives while a write waits for the Mission lock can exit before the write is answered, because the drain budget (5 s) is shorter than the write budget (10 s).

**Why this priority:** this is operator recovery. The operator must be able to tell a bug from a disk fault, and a client must get an answer rather than a dropped connection.

**Independent Test:**
- Inject a failure into the write path. Exactly one warning record names the exception classes and the coded refusal, and it contains none of the request's content.
- Hold the Mission lock from outside, start a write that waits on it, then request shutdown. The waiting write answers `503 SERVICE_DRAINING` with nothing written, before the process stops.

**Acceptance Scenarios:**

1. **Given** an unexpected runtime failure inside a write or a repair, **When** the service answers the coded `500`, **Then** it emits one warning-level record naming the operation, the coded refusal and the exception class chain. The record carries no file path, token, row value or request content.
2. **Given** a write waiting for the Mission lock (or for the turn) when a shutdown is requested, **When** draining starts, **Then** the write answers `503 SERVICE_DRAINING` within 1 s and writes nothing. The drain then completes within its 5 s budget (D10; replaces #236 AC 5's "drain budget > write budget").
3. **Given** a write that already holds the Mission lock when a shutdown is requested, **When** the drain runs, **Then** that write finishes and is answered `201` before the process stops.

---

### User Story 4 - Contracts and docs say what the service really does (Priority: P3)

Several statements about the service are out of step with what it does or will do:

- An integrator reads the `mission-write` contract and the service docs, and should see that repair exists and lands before #6195.
- The docs should say why `workspace_context_required` can never come back over the wire.
- `WriteOperation` is still provisional.
- A write is read back and verified before it is appended, which the step lists omit.

**Why this priority:** the changes are mostly documentation, but integrators (core's #6195 client) build against these words.

**Independent Test:** read the contract and docs. Run the contract break check and the docs validators.

**Acceptance Scenarios:**

1. **Given** the `mission-write` contract, **When** it is checked for breaking changes against its previous state, **Then** it reports none. The contract CHANGELOG lists every addition under `1.0.1-SNAPSHOT`.
2. **Given** `WriteRefusalReason`, **When** a reader looks up `workspace_context_required`, **Then** the description says no wire request can produce it and names core's fail-closed guard and the 2026-09-07 operator decision.
3. **Given** `WriteOperation`, **When** read, **Then** it carries `x-provisional` with its open decision, and the contract CHANGELOG has a matching provisional entry.
4. **Given** the command-cli README and `write-service-protocol.md`, **When** a reader follows the write's check order, **Then** the read-back verification is its own step between the authority and the append. `REQUEST_MALFORMED`'s description names the read-back refusal cases.
5. **Given** `docs/architecture/services/event-log-lock-and-append.md`, **When** read, **Then** it says the status-log repair lands **before** #6195 through the repair operation, not "after core #6195".

### Edge Cases

- **Torn log of one fragment and no LF at all:** the fragment is the whole file. A quarantine truncates the log to zero bytes; a completion adds the LF.
- **Quarantine file already exists** from an earlier repair: the new fragment is appended as a new line; the earlier content is never overwritten.
- **Fragment that is valid JSON but not a readable status row** (for example `{}` or an array): quarantined, not completed.
- **Fragment that is two concatenated objects or carries trailing bytes after one object:** quarantined.
- **Fragment that is one complete row but collides with the log** (a duplicate `event_id`, or a row the fold rejects in context): quarantined, so the log is never left LF-terminated but unfoldable.
- **The log grows between the repair's read and its mutation** (a writer that ignores the lock): the repair changes nothing and answers `500`.
- **`status.events.jsonl.torn` is a symlink, a directory or a FIFO:** the repair refuses with `500` and changes nothing. It never follows a link or blocks on a special file.
- **A torn multi-row unit:** the repair keeps the earlier LF-terminated rows, so a client retry of that unit is a partial replay and answers `409 EVENT_ID_CONFLICT`. The runbook says to re-issue with new ids.
- **The quarantine file is untracked:** it appears in the Mission directory, which is tracked. The runbook tells the operator to inspect it, then remove it or commit it.
- **Log that is torn and also unreducible for another reason:** the tail is still repaired. The answer reports `readableAfterRepair: false`, so the operator knows writes will still fail.
- **Crash after the quarantine is durable but before the truncation is durable:** the fragment is in both files. Recovery is a re-run of the repair, which appends the fragment to the quarantine file again and truncates. A byte may be duplicated across files, but none is lost.
- **Unknown Mission or unverifiable location:** answered exactly as the write path answers it.
- **Repair request without the protocol header, token, Host or Origin guard:** refused exactly as a write is refused.
- **`implementationEvidencePresent` on a transition other than `in_progress → for_review`:** accepted and ignored, as core ignores the flag outside that guard.
- **`implementationEvidencePresent: null`:** treated as absent (infer), as core treats `None`.

## Domain Language

| Canonical term | Meaning | Avoid |
|---|---|---|
| **torn tail** | A status log whose last byte is not LF. | "corrupt log", "broken log" |
| **fragment** | The bytes after the last LF of a torn log. | "partial row" (it may not be a row) |
| **quarantine file** | `status.events.jsonl.torn` beside the log. It receives fragments, one per line, and is append-only. | "backup", "trash" |
| **repair action** | `none`, `completed` or `quarantined`, the closed vocabulary of the repair answer. | "fixed", "healed" |
| **turn** | The service's in-process serialisation of mutations of one Mission. | — |
| **Mission lock** | The per-Mission status `flock` that core and the service share. | "file lock" (ambiguous with the ledger lock) |
| **write budget** | The one deadline a mutation shares across the turn and the Mission lock. | — |
| **drain budget** | How long a shutdown waits for in-flight requests before the process stops. | — |

## Requirements *(mandatory)*

### Functional Requirements

| ID | Requirement | Status | Delivery | No-op passable? |
|---|---|---|---|---|
| FR-001 | The `mission-write` contract and the service MUST offer an explicit status-log repair operation for one Mission (`POST /missions/{missionId}/status-log-repairs`, #222, D2). Its request carries the same caller context a write carries and no row content. | Proposed | [build] | no |
| FR-002 | A repair MUST classify the log as: `none` when the log is missing, empty or ends in LF; `completed` when the fragment decodes as exactly one status row **and** the log prefix plus that row folds as the next write would fold it (the write path's read-back check, applied in context); otherwise `quarantined`. A complete row that would leave the log unfoldable (for example an `event_id` that collides with an earlier row) is quarantined, not completed. | Proposed | [build] | no |
| FR-003 | A `quarantined` repair MUST append the fragment plus a LF to the quarantine file and make it durable before it truncates the log to its last LF. It then makes the log and its directory durable. Before mutating, the repair MUST re-check that the log's size still equals the bytes it read; if not, it changes nothing and answers a coded `500`. A failure at any step before the truncate MUST leave the log byte-identical, roll back any partial quarantine write, and answer a coded `500`. A failure after the truncate (its force or the directory sync) answers `500`, with the fragment durable in quarantine, and a re-run answers `none`. | Proposed | [build] | no |
| FR-004 | A `completed` repair MUST append exactly one LF to the log and make it durable, creating no quarantine file. | Proposed | [build] | no |
| FR-005 | A repair of a missing, empty, healthy or already-repaired log MUST answer `200` with `action: none` and `repaired: false`, and leave every file byte-identical, creating none (D1). | Proposed | [build] | no — paired with FR-003's torn fixture |
| FR-006 | A repair MUST take the same in-process turn and the same Mission lock as a write, in the same order and under the same shared write budget. It answers `409 STATUS_LOCK_HELD` on timeout and `503 SERVICE_DRAINING` while draining. | Proposed | [build] | no |
| FR-007 | A repair MUST go through the same transport guards (token, Host and Origin, protocol header, body cap) and the same location verification as a write. A location the write path cannot verify (including a coordination-routed Mission) answers `422 WRITE_LOCATION_UNVERIFIED`. | Proposed | [build] | no |
| FR-008 | A repair MUST answer with a new closed response schema carrying `repaired`, `action`, `movedBytes`, `quarantinedFile` (the fixed file name or null, never a host path) and `readableAfterRepair` (whether the repaired log reads and folds as the next write would). | Proposed | [build] | no |
| FR-009 | The write path MUST keep refusing a torn tail with `500 STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED` and change nothing; no write ever repairs implicitly. | Proposed | [ratchet] | yes — paired with FR-003 (same torn fixture repaired by the operation) |
| FR-010 | `TransitionUnit` MUST accept an optional `implementationEvidencePresent` (boolean or null) (#224, D3). For an unforced `in_progress → for_review`, a present boolean is used verbatim as the evidence flag. Absent or null keeps today's inference. The field never affects the subtask half of the guard. | Proposed | [build] | no |
| FR-011 | `implementationEvidencePresent` MUST be request-only. The appended bytes for a unit are identical with and without it, and a retry with or without it is a replay of the accepted unit. | Proposed | [build] | no — paired with FR-010's refusal case |
| FR-012 | The parity corpus MUST express the new field. At least one case MUST exercise the evidence refusal over HTTP and through the domain authority, and `unreachable.json` MUST drop its `partial` entry for `implementation_evidence_required`. | Proposed | [build] | no |
| FR-013 | `workspace_context_required` MUST stay in the vocabulary. Its contract description and `write-service-protocol.md` MUST say no wire request can produce it, and why: core's fail-closed guard for its own batch API, operator decision 2026-09-07 (#946, DRIFT-3). `unreachable.json` keeps it, with that reason (#223). | Proposed | [build] | no |
| FR-014 | The workspace context the service gives its guard MUST carry the resolved root checkout instead of the literal `<root>`, in core's default-synthesis shape `<execution_mode>:<root>` (#223). No encoded row changes. | Proposed | [build] | yes — the only consumer is a blank check, so the test asserts the built value directly (positive control: the exact `<mode>:<root>` string) |
| FR-015 | Once draining starts, a write or repair still waiting for the turn or the Mission lock MUST stop waiting, answer `503 SERVICE_DRAINING` and write nothing. A request already holding the Mission lock finishes. The 5 s drain budget and the launcher contract stay unchanged (D10, #231 4.7). | Proposed | [build] | no |
| FR-016 | The last-resort catch-alls on the write path, its HTTP controller and the repair path MUST each emit exactly one warning-level record on an unexpected failure. The record names the operation, the coded refusal and the exception class chain, and carries no exception message, path, token, row value or request content (#231 4.14). | Proposed | [build] | no |
| FR-017 | `WriteOperation` MUST carry `x-provisional` with its open decision (the vocabulary follows core's operation doors), with a matching provisional entry in the contract CHANGELOG (#231 4.12). | Proposed | [build] | no |
| FR-018 | The command-cli README and `write-service-protocol.md` MUST list read-back verification as its own step between the authority and the append. The `REQUEST_MALFORMED` description MUST name the read-back refusal cases (#231 4.15, #231 comment on `64ba3483`). | Proposed | [build] | no |
| FR-019 | The docs MUST describe the repair operation and say it lands **before** #6195. This means correcting `docs/architecture/services/event-log-lock-and-append.md` ("after core #6195"), linking the operation from the command-cli README and `write-service-protocol.md`, and adding a cut-over runbook stopgap: calling the operation directly until core's verb ships. | Proposed | [build] | no |
| FR-020 | The contract CHANGELOG MUST list every addition of this mission under `1.0.1-SNAPSHOT`: the repair path and its schemas, the evidence field, `x-provisional` on `WriteOperation`, and the description changes. The contract break check MUST report no break. | Proposed | [build] | no |
| FR-021 | The mission record MUST track #222. Add a row to `kitty-specs/events-write-core-01M6TP6W/issue-matrix.json` (a record-only fix) pointing at this mission, plus this mission's own issue-matrix rows for #222, #223, #224 and #231. | Proposed | [build] | no |
| FR-022 | The core follow-ups MUST be filed and linked: (a) a spec-kitty/spec-kitty issue for the repair CLI verb and for mapping the evidence flag onto the new wire field, linked to #6195's checklist (D2), which also carries the blank `--workspace-context` finding; (b) a comment on #6195 documenting rolling-upgrade behaviour for the new optional field (D3): the launcher reuses any running protocol-1 service without comparing `serviceVersion`, so an older resident service answers `400`. | Proposed | [build] | no |

### Non-Functional Requirements

| ID | Requirement | Threshold | Status |
|---|---|---|---|
| NFR-001 | Repair durability under faults: inject a failure at each file operation of a `quarantined` repair, in turn. | 100% of injection points leave either the original log intact, or the log at its last LF with the fragment present in the quarantine file. 0 bytes lost. | Proposed |
| NFR-002 | Byte parity of the evidence field. | 0 byte differences between appends with and without `implementationEvidencePresent` across every accepted corpus case that exercises it. | Proposed |
| NFR-003 | Log hygiene. | 0 occurrences of a planted marker path, token or row value in captured log output across the catch-all tests. | Proposed |
| NFR-004 | Answered before exit. | A request waiting on the turn or the Mission lock when draining starts is answered within 1 s of the drain starting, which is well inside the 5 s drain budget, in 100% of test runs. | Proposed |
| NFR-005 | Code quality on new Java code. | Cyclomatic complexity ≤ 15 per method; the existing coverage gates of `./gradlew verifyAll` stay unchanged and pass. | Proposed |
| NFR-006 | Repair cost. | A repair reads the log once and folds it at most twice (the in-context completion check, then `readableAfterRepair` for a quarantine), and holds the Mission lock no longer than the shared write budget. | Proposed |

### Constraints

| ID | Constraint | Status |
|---|---|---|
| C-001 | The write path never repairs (prior mission C-006). Repair is explicit, operator-invoked, and separate from every write. | Active |
| C-002 | The service stays the only status-log writer (FR-010 / ADR 2026-10-22-1 D-1 of the prior mission). Repair code lives where the write-ownership rules already allow writes; those rules are not relaxed. | Active |
| C-003 | Contract changes are additive inside `1.0.1-SNAPSHOT`. Existing response schemas are not changed, and no response carries a host path. | Active |
| C-004 | No build-time or run-time dependency on core (charter: Services, Package Boundary). Core is consulted only through the test-only pin. | Active |
| C-005 | Maintainer rulings D1–D4 and D9 (2026-11-09) are binding. D4's implementation is out of scope (M2, #237). | Active |
| C-006 | The divergence principle: each difference from core is judged by why core behaves so, and the judgements above are recorded. | Active |
| C-007 | One PR (D9) from `issue-236-write-service-cutover-readiness` to `main`. Never merge; never push `main`. Changelog fragments only, never `[Unreleased]` by hand. | Active |
| C-008 | Coordination-routed Missions cannot be repaired until migration step 4 (ADR-0007 D-1) serves the coordination ref. The contract and docs state this gap. | Active |
| C-009 | Ordering: this mission must merge and ship in a `service-v*` rc before core #6195. | Active |

### Key Entities

- **Status-log repair request:** identifies the Mission and carries the caller context a write carries. It has no row content.
- **Status-log repair answer:** `repaired`, `action` (`none`, `completed` or `quarantined`), `movedBytes`, `quarantinedFile`, `readableAfterRepair`.
- **Quarantine file:** `status.events.jsonl.torn` in the Mission directory. It is append-only and holds one fragment per line.
- **Transition unit:** gains the optional, request-only `implementationEvidencePresent`.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: An operator restores writes to a Mission with a torn status log with one request, losing 0 bytes of the fragment. — [build] · no-op passable: no
- **SC-002**: Re-running the repair on any non-torn log changes 0 bytes and creates 0 files. — [build] · no-op passable: no — paired with SC-001's torn fixture
- **SC-003**: 100% of handoffs that core's authority refuses for an explicit "no evidence" flag are refused identically over the wire, with 0 byte changes to accepted handoffs. — [build] · no-op passable: no
- **SC-004**: Every unexpected `500` leaves exactly 1 diagnostic record, and 0 of those records contain request content. — [build] · no-op passable: no
- **SC-005**: A write queued at shutdown is answered (`503 SERVICE_DRAINING`, nothing written) before the process stops in 100% of test runs. — [build] · no-op passable: no
- **SC-006**: The contract break check reports 0 breaks, while the CHANGELOG lists each addition. — [ratchet] · no-op passable: yes — paired with the CHANGELOG-lists-additions check

## Assumptions

- **No exception messages in logs.** The catch-all record names exception *classes* (the cause chain), not messages, because JDK I/O exception messages carry host paths. That is enough to tell a bug from a disk fault. The ledger's `TornTailRepair` is **not** the precedent here: it logs host paths (`TornTailRepair.java:85`). The new records follow the stricter rule.
- **Contract checks are run by hand.** `breaking_check.py`, `citation_check.py`, `provisional_check.py`, `layout_check.py` and `structure_check.py` are not CI gates (#151). The mission runs them by hand and records the output.
- **`readableAfterRepair` checks what the next write checks.** The repaired log decodes, and folds with the write fold. It does not validate anything a write would not.
- **Quarantine file name.** `status.events.jsonl.torn` is a fixed, well-known name in the Mission directory, mirroring the ledger's `TornTailRepair` convention.
- **No contract version change.** `1.0.1-SNAPSHOT` is still unreleased on the `service-v*` line, so the additions stay inside it. If it has been released by the time this lands, they open `1.0.2-SNAPSHOT` instead (contract README rule).
- **The REQUEST_MALFORMED item rides along.** The `REQUEST_MALFORMED` description item (a #231 comment) describes the read-back refusal, so it lands with item 4.15 here. #237 is told.

## Out of Scope

- The core CLI verb that calls the repair operation, and core's forwarding of the evidence flag. Both are a core follow-up (D2); this mission files them.
- Everything in M2 (#237), including `validate_transition` (D4), the null `event_id` fold fix, clock injection, the Python encoder items and the assurance items.
- The read `StatusReducer`'s missing approved-departure lift (G-4); a separate events issue.
- Repairing coordination-routed Missions (C-008).
- Any change to how the write path treats a torn tail (C-001).
