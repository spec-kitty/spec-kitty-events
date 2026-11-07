# Feature Specification: command-cli write core — canonical writer, lane authority and the lane-transition cut-over

**Feature Branch**: `issue-160-write-core`
**Created**: 2026-11-07
**Status**: Draft
**Input**: spec-kitty/spec-kitty-events#160, #161 and #155, with the operator rulings of 2026-11-07 relayed by the orchestrator. Context: core spec-kitty/spec-kitty#6194 (closed as moved), #6195 (core's client half, 4.0.0 scope) and #6915 (the full-stack gap list). Research: `docs/architecture/services/command-cli-research.md` and this mission's `research.md`.

## Intent Summary

- **Primary actor:** core's CLI (`spec-kitty`). Once core binds its EventBridge to the write service (core #6195), the CLI calls the service for every lane transition, batch of transitions and inner-state annotation of a Mission.
- **Trigger:** a CLI command moves a work package between lanes (`move-task`, `mark-status`, `status emit`, the claim and implement moves) or records an annotation. It sends the unit to the running `command-cli` service of its repository.
- **Outcome:** the service decides the unit with the same lane rules and refusal codes the CLI applies today. An accepted unit is appended to the Mission's status event log with the exact bytes core writes and made durable before the answer. A refused unit writes nothing and answers the contract's code and reason.
- **Invariant:** the service never acknowledges a write it did not durably persist, and it never persists a unit the lane rules refuse. While the CLI's own Python writers still run, a service write and a CLI write to the same Mission never interleave: they take the same per-Mission lock.
- **Boundary:** the client binding and the deletion of the Python lane appenders are core's work (#6195). The status commit, cut-on-failure inside the service and the `coordination_ref_commit` surface belong to migration step 4 (ADR-0007). The read routes, the BDD suite and the corpus consumers are other work (see the rulings).

### Operator rulings (2026-11-07) that govern this spec

These are recorded here as AGENTS.md requires for a multi-issue mission. They are also recorded as resolved Decision Moments on this Mission.

1. **One mission, one PR.** #160, #161 and #155 land as one mission, on one branch, in one PR that closes all three. This relaxes the one-issue-one-PR rule (`PROGRAM.md` §5–§9, AGENTS.md). The operator raised all three to P1 on 2026-11-07, because `command-cli` refuses every write until they land and core #6195 (4.0.0 scope) waits on them.
2. **ADR-0007/0008 and `mission-write` `1.0.1-SNAPSHOT` (PR #215, merged) are accepted, and this mission honours them.** They touch the commit path next to #160 (`statusCommit`). This mission serves the `checkout_append` surface only. `statusCommit` stays null. `coordination_ref_commit` is refused fail-closed until migration step 4. The writer's design must not foreclose the ref-commit path (ADR-0007 D-1 to D-6).
3. **GA scope.** GA also needs the read routes (#169, #158) and the BDD suite (#121). Those are other work and out of scope here. Only a non-rc `service-v*` release goes live at GA; rc pins are fine until then. This mission cuts no tag.
4. **Exclusions.** #165, #166 and #167 are excluded. They depend on the parity corpus this mission creates.

Earlier rulings this spec inherits, from 2026-10-31 (#156): a torn tail is refused, not repaired. The contract keeps its `/api/v1` prefix. A draining service refuses with `SERVICE_DRAINING`.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A legal transition is written durably, byte-exact and once (Priority: P1)

The CLI sends one transition unit for a Mission's work package to the service, for example a claim from `planned` to `claimed`. The rows are the transition first, then its annotations. The unit is legal. The service appends the rows to `<missionDirectory>/status.events.jsonl` under the Mission's status lock and makes them durable. Only then does it answer `201` with a `DurabilityAck`: `appendedLines` is the number of rows, `fsynced` is true, `replayed` is false and `statusCommit` is null.

**Why this priority**: this is the write core. Without it, `command-cli` refuses every write and core's 4.0.0 path cannot bind (#160, #155).

**Independent Test**: start the service against a temporary repository that holds a Mission. Send a legal unit. Compare the event log's new bytes with what core's encoder writes for the same rows: the `status_append` fixtures give the expected bytes. Send the same unit again and check that the answer is a replay and the file is unchanged.

**Acceptance Scenarios**:

1. **Given** a Mission whose WP01 is `planned`, **When** a legal claim unit arrives, **Then** the event log gains exactly the unit's rows, each one encoded as core encodes it (identity fields stripped, session times folded into a duration, keys sorted). The answer is `201`, `appendedLines` = row count, `fsynced` = true, `replayed` = false, `statusCommit` = null.
2. **Given** that unit was already acknowledged, **When** the identical unit arrives again, **Then** the answer is `replayed: true` with `appendedLines: 0`, and the log is byte-for-byte unchanged.
3. **Given** a unit whose `event_id` is already in the log with different content, or a unit only some of whose event ids are in the log, **When** it arrives, **Then** the answer is `409 EVENT_ID_CONFLICT` and nothing is written.
4. **Given** a request that repeats an `event_id` inside one unit or batch, **When** it arrives, **Then** the answer is `409 BOOKKEEPING_DOUBLE_EVENT_ID` and nothing is written.
5. **Given** a CLI Python writer holds the Mission's status lock, **When** a unit arrives, **Then** the service waits for that same lock file with the same OS lock semantics. It writes only after the CLI releases the lock, or it answers `409 STATUS_LOCK_HELD` after the wait budget and writes nothing.
6. **Given** an event log whose last line has no newline (a torn tail), **When** a unit arrives, **Then** the unit is refused, the log is not repaired, and nothing is appended.

---

### User Story 2 - An illegal transition is refused with the code the user sees today (Priority: P1)

The service is authoritative: a CLI pre-check is advisory only (core ADR 2026-10-27-1). The service folds the Mission's event log into the current state of each work package, then checks the unit against the lane state machine, its guards and dependency gating, as core's `wp_state.py`, `transitions.py` and `dependency_verdict.py` do at the pinned core commit. A refusal answers the contract's code and, for a guard, its reason URN. The code is the one the CLI raises today.

**Why this priority**: if the service writes what the CLI would refuse, two writers disagree and the event log loses its meaning (#161).

**Independent Test**: run the parity corpus. Each case is (current state, unit) → expected outcome. The outcome is an acknowledgement or a refusal code plus reason. Run every case against the Java domain and against core's Python implementation at the pin, and check that each outcome matches.

**Acceptance Scenarios**:

1. **Given** WP01 is `planned`, **When** a unit moves it straight to `done` without force, **Then** the answer is `409 TRANSITION_REJECTED` with reason `urn:spec-kitty:write-refusal:illegal_transition`, and nothing is written.
2. **Given** WP02 depends on WP01 and WP01 is neither approved nor done, **When** a unit gates WP02 on its dependencies, **Then** the answer is `409 DEPENDENCIES_NOT_SATISFIED` with reason `…:dependencies_unsatisfied`.
3. **Given** another actor holds the claim on WP01, **When** a unit claims WP01 for a different actor, **Then** the answer is `409 WP_ALREADY_CLAIMED` with reason `…:claim_held_by_other_actor`.
4. **Given** any of the 15 guard reasons of `WriteRefusalReason`, **When** a unit trips that guard, **Then** the refusal carries that reason's URN in `type` and the code the contract binds it to.
5. **Given** a unit for a `missionId` the repository does not have, or a `wpId` the Mission does not have, **When** it arrives, **Then** the answer is `404 MISSION_NOT_FOUND` or `404 WP_NOT_FOUND`.

---

### User Story 3 - A batch is all or nothing, judged unit by unit (Priority: P1)

The CLI sends up to 64 units in one batch. The service checks each unit against the state the earlier units of the batch leave. It then appends all the rows in one write and one sync, or appends nothing.

**Why this priority**: batches are how the CLI moves several work packages at once (finalize, merges). A half-applied batch would corrupt the Mission's state (#160, #161).

**Independent Test**: send a batch whose unit 2 is legal only after unit 1 is applied. It is accepted. Send a batch whose unit 3 is illegal: the refusal names `#/units/3` and the log is unchanged.

**Acceptance Scenarios**:

1. **Given** a legal batch of n units, **When** it arrives, **Then** the answer is `201` with a `BatchDurabilityAck` (one ack per unit, `fsyncs` = 1), and the log gains every row in order from one write.
2. **Given** a batch whose unit i is refused, **When** it arrives, **Then** the refusal names `instance: #/units/<i>`, carries that unit's code and reason, and no row of any unit is written.
3. **Given** a batch whose units name different `missionDirectory`, `writeSurface` or `destinationRef`, **When** it arrives, **Then** the answer is `400 REQUEST_MALFORMED` (unchanged from today).

---

### User Story 4 - A write to an unverified location is refused (Priority: P2)

The request context names the caller's `checkout`, the `missionDirectory` and the `writeSurface`. The service resolves the write location itself from the git common dir it serves, and refuses a request whose context differs. A `coordination_ref_commit` unit is refused: that surface is served only from migration step 4 (ADR-0007).

**Why this priority**: the service must never append to a file the caller merely named (#160, ADR 2026-10-22-1 open question 2).

**Independent Test**: send units whose `missionDirectory` lies outside the repository, does not hold the Mission, or names a lane worktree. Send a `coordination_ref_commit` unit. Each is refused with `422 WRITE_LOCATION_UNVERIFIED`, and no file anywhere is touched.

**Acceptance Scenarios**:

1. **Given** a `checkout_append` unit whose `checkout` is the repository root checkout and whose `missionDirectory` is that Mission's directory in it, **When** it arrives, **Then** the write location is verified and the unit proceeds to the lane checks.
2. **Given** a unit whose `checkout`, `missionDirectory` or `writeSurface` does not match the service's own resolution, **When** it arrives, **Then** the answer is `422 WRITE_LOCATION_UNVERIFIED` and nothing is written.
3. **Given** a well-formed `coordination_ref_commit` unit, **When** it arrives, **Then** it is refused fail-closed (`422 WRITE_LOCATION_UNVERIFIED`, with a detail naming migration step 4). No ref, object or file is written.

---

### User Story 5 - A standalone annotation is written like a unit (Priority: P2)

The CLI records an inner-state annotation outside a transition. The service checks that the Mission and work package exist and that the row has the contract's shape. It appends the row with the same encoder, lock, durability and idempotency as a unit.

**Why this priority**: the annotation operation is the third write operation of the contract, and #160 wires it alongside the transition and batch operations.

**Independent Test**: send an annotation for an existing work package and check the appended bytes and the `DurabilityAck`. Send one for an unknown work package and check for `404 WP_NOT_FOUND`.

**Acceptance Scenarios**:

1. **Given** an existing WP01, **When** a well-formed annotation arrives, **Then** it is appended byte-exact and acknowledged `201` with `appendedLines: 1`.
2. **Given** the same annotation again, **When** it arrives, **Then** it is a replay.

---

### Edge Cases

- **Mission's first write:** an event log that does not exist yet is created under the lock, with owner-default permissions, and synced together with its parent directory.
- **Empty log file:** treated as an empty history, not as a torn tail.
- **Rows that need escaping:** non-ASCII text, astral-plane characters, control characters, floats, nested objects and nulls are encoded exactly as core's encoder does (the `status_append` fixtures cover each one).
- **Session times:** a row with `session_started_at` and `session_ended_at` gets `session_duration_s` exactly as core's sanitizer computes it. When only one of the two is present, the output is whatever core produces in that case (pinned by a fixture).
- **Identity fields in nested objects:** `machine_name`, `hostname`, `workspace_path`, `developer_name` and `developer_email` are stripped at every nesting level, including inside lists.
- **Malformed lines inside the log (not the tail):** the fold follows the read side's classification. A log the fold cannot reduce is refused, and nothing is written.
- **Batch of exactly 64 and 65 units:** 64 is accepted. 65 is `400 REQUEST_MALFORMED` (unchanged).
- **Drain during a write:** a unit that has started its write finishes and is acknowledged. A unit that arrives after drain begins is refused `503 SERVICE_DRAINING` (unchanged).
- **Crash between the sync and the answer:** the client's retry of the same unit is a replay, never a duplicate.
- **Concurrent requests to the service for the same Mission:** they are serialized, and each is judged against the state the previous one left.
- **Windows:** the per-Mission lock uses the channel lock the lock-interop spike proved, not flock.

## Requirements *(mandatory)*

### Functional Requirements

| ID | Requirement | Issue | Status | Delivery | No-op passable? |
|----|-------------|-------|--------|----------|-----------------|
| FR-001 | Each status row is encoded as core encodes it: the sanitizer is applied first, then a sorted-key JSON dump with Python's default separators and ASCII escaping, then one `\n`. The Java encoder and a Python reference encoder in this package both reproduce it. | #160 | Draft | build | no |
| FR-002 | The sanitizer strips `machine_name`, `hostname`, `workspace_path`, `developer_name` and `developer_email` at every nesting level, and replaces `session_started_at`/`session_ended_at` with `session_duration_s`, exactly as core's `sanitize_event_for_log` does at the pinned commit. | #160 | Draft | build | no |
| FR-003 | A new `status_append` conformance fixture family (valid and invalid cases with byte-exact expected output, beside `ledger_append`) ships in the package. The packaged Python conformance suite and the Java encoder's tests both pass every case. | #160 | Draft | build | no |
| FR-004 | An accepted unit is one append of full JSON lines to `<missionDirectory>/status.events.jsonl`, opened for append, followed by a sync to stable storage. Both happen before the `201`. A batch of up to 64 units is one write and one sync. A refused unit or batch writes nothing. | #160 | Draft | build | no |
| FR-005 | Every write takes the per-Mission status lock `<git common dir>/spec-kitty-locks/<mission directory name>.status.lock` with the same OS lock semantics as core's Python writers: flock on POSIX, the channel lock on Windows. A lock still held after the wait budget answers `409 STATUS_LOCK_HELD`. | #160 | Draft | build | no |
| FR-006 | The lock works in the native binary as well as the jar. The foreign-function downcall the POSIX lock uses is registered for the native image, and a native smoke test exercises a locked write. | #160 | Draft | build | no |
| FR-007 | An event log whose last byte is not `\n` (a torn tail) is refused, not repaired. The unit answers a `500` refusal and the file is unchanged. | #160 | Draft | build | no |
| FR-008 | `event_id` is the idempotency key. An identical retry answers `replayed: true` and `appendedLines: 0`. A retry that is partial or differs answers `409 EVENT_ID_CONFLICT`. A duplicate id within one request answers `409 BOOKKEEPING_DOUBLE_EVENT_ID`. | #160 | Draft | build | no |
| FR-009 | The service verifies the request context's `checkout`, `missionDirectory` and `writeSurface` against its own resolution from the git common dir it serves, and answers `422 WRITE_LOCATION_UNVERIFIED` on a mismatch. A `coordination_ref_commit` unit is refused with the same code until migration step 4. | #160 | Draft | build | no |
| FR-010 | Only `service/event-log` may open a status event log for writing. An architecture rule fails the build if any other module writes one. | #160 | Draft | build | no |
| FR-011 | The lane state machine is ported from core's `wp_state.py`, `transitions.py` and `dependency_verdict.py` at the commit pinned in `service/core-pin.json`. The port keeps the same lanes, aliases, edges, terminal lanes, force semantics and guards, and builds on `domain-model`'s `Lane`, `WpState` and the event-log `StatusReducer`. | #161 | Draft | build | no |
| FR-012 | Every guard refusal answers the code the contract binds to its reason, carries the reason's `urn:spec-kitty:write-refusal:*` in `type`, and uses the same code the CLI raises today for the same input. All 15 reasons are reachable. | #161 | Draft | build | no |
| FR-013 | Dependency gating refuses a gated transition whose work package has a dependency that is neither approved nor done (`DEPENDENCIES_NOT_SATISFIED`). The service reads the dependencies from the Mission's own on-disk data, as core does. | #161 | Draft | build | no |
| FR-014 | A batch checks each unit against the state the earlier units leave. A refusal names the unit as `instance: #/units/<i>` and writes nothing for the whole batch. | #161 | Draft | build | no |
| FR-015 | A parity corpus of cases (state, unit) → (ack, or refusal code and reason) is committed and shared with Python. The Java write domain passes every case. A test-only generator re-derives every expected outcome from core's Python implementation at the pinned commit, and CI fails on any drift. | #161 | Draft | build | no |
| FR-016 | The write domain (state machine, guards, gating, batch validation) is framework-free: no Spring, no I/O and no concurrency. An architecture rule enforces this. | #161 | Draft | build | no |
| FR-017 | The transition, batch and annotation operations of `contracts/mission-write` are wired through the authority and the writer. A legal write answers `201` with a `DurabilityAck` or `BatchDurabilityAck` that validates against the contract's schema. The fail-closed refusal of every write is removed. | #155, #160 | Draft | build | no |
| FR-018 | An unknown `missionId` answers `404 MISSION_NOT_FOUND`, and an unknown `wpId` answers `404 WP_NOT_FOUND`, before any lock is taken. | #161, #155 | Draft | build | no |
| FR-019 | The edge order of the contract (guard, token, protocol, size, draining, shape, then the domain codes) and every existing refusal for those checks are unchanged. | #155 | Draft | ratchet | yes — positive control: the existing edge and operations tests stay green unmodified except where they asserted the fail-closed write refusal |
| FR-020 | Concurrent requests for the same Mission are serialized in the service, and each is judged against the state the previous one left. | #160 | Draft | build | no |
| FR-021 | The service's documentation, the services architecture pages, the `mission-write` contract CHANGELOG (for any change to behaviour or provisional markers) and a changelog fragment describe the write core as built. | #160, #161, #155 | Draft | build | no |

### Non-Functional Requirements

| ID | Requirement | Threshold | Status |
|----|-------------|-----------|--------|
| NFR-001 | Latency of one acknowledged unit, measured by a test on a Mission whose event log already holds 10,000 rows (jar, CI runner) | p95 ≤ 250 ms over 50 writes | Draft |
| NFR-002 | Parity corpus coverage | ≥ 1 refusal case for each of the 15 reasons, ≥ 1 accepted case for each legal edge of the state machine, ≥ 5 multi-unit batch cases; ≥ 150 cases in total | Draft |
| NFR-003 | `status_append` fixture coverage | ≥ 12 valid cases (one per sanitizer branch, escaping class and number kind) and ≥ 4 invalid cases | Draft |
| NFR-004 | Code quality of new Java and Python code | cyclomatic complexity ≤ 15 per method or function; `mypy --strict`, `ruff check` and `ruff format --check` clean; JaCoCo class floors met for every new class | Draft |
| NFR-005 | Build integrity | `./gradlew verifyAll` green under strict dependency verification, with no new unverified artifact; every action in a changed workflow pinned by a 40-hex SHA | Draft |
| NFR-006 | Durability under a forced crash | 0 acknowledged units missing, and 0 partial lines appended by a refused or crashed unit, over a crash-injection test of ≥ 100 iterations | Draft |

### Constraints

| ID | Constraint | Status |
|----|------------|--------|
| C-001 | No build-time or runtime dependency on core. Core is reached only through the test-only pin in `service/core-pin.json` (ADR 0002 D4), which this mission moves only if a reviewed change needs it. | Active |
| C-002 | ADR-0007, ADR-0008 and `mission-write` `1.0.1-SNAPSHOT` are honoured. `statusCommit` stays null, and `coordination_ref_commit` is not served. The writer keeps storage behind a port, so the step-4 ref-commit surface can be added without reworking the encoder, the idempotency check or the authority (operator ruling 2). | Active |
| C-003 | Moving validation into the service changes no code a user sees. Every refusal code equals the CLI's code for the same input. | Active |
| C-004 | Out of scope: the read routes (#169, #158), the BDD suite (#121), #165–#167, the status commit and cut-on-failure (migration step 4), moving write-location resolution into the service beyond verification, core's client binding and Python appender deletion (core #6195), and any `service-v*` tag. | Active |
| C-005 | One mission, one branch, one PR that closes #160, #161 and #155 (operator ruling 1). | Active |
| C-006 | A torn tail is refused, never repaired (operator ruling 2026-10-31). | Active |
| C-007 | Changes are recorded in `docs/changelog/unreleased/` fragments. `[Unreleased]` in `CHANGELOG.md` is never hand-edited. | Active |
| C-008 | Any `mission-write` contract change stays inside `1.0.1-SNAPSHOT` and is recorded in its CHANGELOG. A closed response is not changed in a way that breaks a client of `1.0.1-SNAPSHOT`. | Active |
| C-009 | Service modules keep their ArchUnit onion and boundary rules. New rules (writer ownership, framework-free domain) are added, never weakened. | Active |

### Key Entities

- **Transition unit:** the rows of one lane transition (the transition row first, then its annotation rows) and the request context. It is the unit of work: written whole or not at all.
- **Batch:** up to 64 units of one Mission, written as one write and one sync, or not at all.
- **Status event log:** `<missionDirectory>/status.events.jsonl`, the append-only, newline-delimited log of a Mission's status rows.
- **Status row:** one event-log line. Its bytes are fixed by core's encoder (sanitizer, then sorted-key JSON).
- **Work-package state:** the lane, claim, review and evidence state the fold derives from the log. The guards judge a unit against it.
- **Refusal:** a contract code (`WriteRefusalCode`) with, for a guard, a reason URN (`WriteRefusalReason`), and for a batch, the failing unit's `instance`.
- **Parity case:** (state, unit) → (ack, or code and reason). It is shared by the Java domain and core's Python implementation.
- **Durability acknowledgement:** `appendedLines`, `fsynced`, `replayed` and `statusCommit` (null in this mission).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of `status_append` fixture cases pass in both the Python and the Java encoder — [build] · no-op passable: no
- **SC-002**: 100% of parity corpus cases give the same outcome (ack, or the same code and reason) in the service's domain as in core's Python implementation at the pin — [build] · no-op passable: no
- **SC-003**: Every write operation of `contracts/mission-write` acknowledges a legal write with a contract-valid body. 0 operations still refuse every write — [build] · no-op passable: no
- **SC-004**: Across the durability crash test, 0 acknowledged units are lost and 0 refused units leave bytes in the log — [build] · no-op passable: no
- **SC-005**: In the lock-interop test, a service write and a core Python write to the same Mission never interleave, over ≥ 100 contended iterations — [build] · no-op passable: no
- **SC-006**: All four required checks (`acceptance`, `service-gate`, `cross-repo-harness-tests`, `docs-build`) are green on the PR — [ratchet] · no-op passable: yes — positive control: the PR's new tests are part of `service-gate`

## Assumptions

- Core's `wp_state.py`, `transitions.py`, `dependency_verdict.py`, `store.py` and `sanitizer.py` at the pinned commit `512567ee` are the reference behaviour. If the research finds the pin too old for these files, moving the pin is a reviewed change inside this PR, recorded in the plan.
- The dependency data the gate needs (each work package's dependencies) can be read from the Mission directory on disk, where core reads it. The plan confirms the exact source.
- `checkout_append` names the repository root checkout only (ADR-0007 D-7). A lane worktree is never a status surface.
- The torn-tail refusal uses an existing `500` code of the contract. The plan picks the code, and no new code is minted unless the contract changelog records it.
- GraalVM `native-image` is not installed locally, so native verification of the lock downcall runs in CI.

## Dependencies

- `service/event-log` (locks, decoder, reducer), `service/domain-model` (`Lane`, `WpState`) and `service/command-cli` (edge, operations, lifecycle), all on `main`.
- `contracts/mission-write` `1.0.1-SNAPSHOT` (PR #215, merged).
- The core pin `service/core-pin.json` (test-only) for the parity generator and the lock-interop peer.
- Downstream: core #6195 binds the client once a `service-v*` release carries this write core.
