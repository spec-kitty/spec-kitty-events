---
work_package_id: WP06
title: 'Write domain, part 1: fold, lane machine and guards'
dependencies:
- WP03
- WP04
requirement_refs:
- FR-011
- FR-012
- FR-016
- FR-022
- FR-023
- FR-025
- NFR-004
- SC-002
- C-003
- C-009
- C-010
planning_base_branch: kitty/mission-events-write-core-01M6TP6W
merge_target_branch: main
branch_strategy: Planning artifacts for this mission were generated on kitty/mission-events-write-core-01M6TP6W. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into main unless the human explicitly redirects the landing branch.
subtasks:
- T032
- T033
- T034
- T035
- T036
- T037
- T038
history:
- timestamp: '2026-11-07T10:30:00Z'
  lane: planned
  agent: system
  action: Prompt generated via /spec-kitty.tasks
agent_profile: java-jenny
authoritative_surface: service/command-cli/src/main/java/org/speckitty/commandcli/domain/authority/
create_intent:
- service/command-cli/src/main/java/org/speckitty/commandcli/domain/authority/
- service/command-cli/src/test/java/org/speckitty/commandcli/domain/authority/
- service/command-cli/src/test/java/org/speckitty/commandcli/architecture/AuthorityPurityTest.java
- service/command-cli/src/test/java/org/speckitty/commandcli/architecture/fixture/authority/
execution_mode: code_change
owned_files:
- service/command-cli/src/main/java/org/speckitty/commandcli/domain/authority/*.java
- service/command-cli/src/test/java/org/speckitty/commandcli/domain/authority/*.java
- service/command-cli/src/test/java/org/speckitty/commandcli/architecture/AuthorityPurityTest.java
- service/command-cli/src/test/java/org/speckitty/commandcli/architecture/fixture/authority/**
role: implementer
tags: []
tracker_refs: []
---
# Work Package Prompt: WP06 - Write domain, part 1: fold, lane machine and guards

## ⚡ Do This First: Load Agent Profile

Load your assigned profile before reading anything else: `/spk-charter-profile-load java-jenny`
(or `spec-kitty agent profile show java-jenny`). Adopt its identity, scope and boundaries.

## Objective

Port core's lane authority into a **pure** package, `org.speckitty.commandcli.domain.authority`
(FR-011, FR-016): no Spring, no Jackson, no file or network I/O, no concurrency, no clock. Part 1
covers everything except dependency gating, the lifecycle claim rules and batch sequencing
(WP07):

1. **`WriteFold`**: a write-side fold of the Mission's log lines that ports core's `read_events` and
   reducer at the pin, annotation deltas included, into one `WpWriteState` per work package. It
   does **not** use event-log's `StatusReducer` (C-010; research §11.8).
2. **`LaneMachine`**: 11 lanes, the 29 structural edges, terminal `done` and `canceled`, and force
   semantics (force with actor and reason bypasses edges and guards, to any target but `genesis`).
3. **`Guards`** with exact precedence and core's messages, and **`GuardContext`** with the inputs
   the pipeline derives when the row carries none (FR-022, except dependency readiness).
4. The pipeline guards that run before the machine (done evidence, the unnoted review-lane force
   exit), the stale-`from_lane` protocol rule (FR-023) and the unknown-lane refusal.
5. **`UnitJudge`**, `Refusal`, `Judgement`, and two seams WP07 fills: `DependencyGate` and
   `ClaimGate`.
6. An ArchUnit rule that keeps the package pure, with planted violations (FR-016).
7. **`ParityCorpusTest`**: the Java runner over WP04's corpus for the areas this WP owns; the
   areas WP07 owns are skipped by an explicit, named list (red first: run it before the code).

## Context

- Spec: FR-011, FR-012 (code plus reason URN; message), FR-016, FR-022 (non-dependency inputs),
  FR-023 (stale `from_lane` → `409 TRANSITION_REJECTED`, no URN; canonical lanes), FR-025 (detail
  is core's message unless it would echo a request value), C-003, C-009, C-010; US2 scenarios 1
  and 4; edge case "Malformed lines inside the log".
- Plan: "Placement decisions" (authority takes decoded rows and `MissionFacts`, returns a
  `Judgement`; does not use `StatusReducer`), "Complexity Tracking" (separate write fold),
  "Coordination points" (the corpus is your acceptance oracle).
- Data model: `MissionFacts`, `WpWriteState`, `Fold`, `GuardContext` and its derivation table,
  `Refusal`, `Judgement`, "Lane state machine (ported)".
- Research (read all of §1 before coding): §1.1 (vocabulary; `WPState` is the only authority;
  `validate_transition` normalizes and reports `Unknown lane: <original>`), §1.2 (the edge table),
  §1.3 (guards, exact texts, precedence; `current_actor` not read by the machine; structured actor
  projected to `tool`, fallback `role`), §1.4 (force), §1.7 (annotations do not traverse the
  machine), §2 (reason → code; guards outside the 15 URNs), §8.2 (what event-log's model drops;
  the 10.5.1 ordering gap), §10 risks 1, 3 and 10, §11.5, §11.8.
- Core at the pin (`512567ee`, read, never import): `src/specify_cli/status/wp_state.py`,
  `transitions.py`, `models.py` (lanes, `StatusEvent.to_dict`, annotation delta slots `:694-905`),
  `transition_pipeline.py:133-177,222-242,289-388`, `emit.py:461-548`, `store.py` (`read_events`),
  `reducer.py` (and the `spec_kitty_events.diary.reduce_parsed` version core's `uv.lock` pins, if
  core's reducer delegates to it). The fold follows **core at the pin**, not this repo's newer
  `diary`.
- Existing seams:
  - `service/domain-model/.../Lane.java:12-63` (11 wire values, no alias), `PyText.java`
    (`isBlank`, `strip`: Python `str` semantics), `CodePoints.java` (code-point order).
  - WP03's `domain/service/RefusalCode.java` and `RefusalReason.java` (codes, statuses, the 15
    URNs with their bound codes).
  - `service/command-cli/src/test/java/org/speckitty/commandcli/architecture/ArchitectureRulesTest.java:55-65`
    (the existing domain rule forbids Spring, Jackson and file APIs; yours is stricter and scoped to
    `domain.authority`).
  - `service/command-cli/build.gradle.kts:189-217`: JaCoCo per-class floors for
    `org.speckitty.commandcli.domain.*`: 90 % lines **and** 80 % branches per class. Every class you
    add must meet both.
  - WP04's corpus: `src/spec_kitty_events/conformance/status_transition_parity/`
    (`README.md` for the fields, layers and areas; `cases/*.json`).

## Branch Strategy

Planning happens on `kitty/mission-events-write-core-01M6TP6W` in an owned checkout
(`single_branch` topology). The mission lands as one PR to `main` from `issue-160-write-core`;
the planning base and merge target recorded here are `main`.
`spec-kitty agent action implement WP06 --agent <name> --mission events-write-core-01M6TP6W`

## Ground rules

- ATDD, red first: write `ParityCorpusTest` (T038) and the purity rule (T037) first and run them
  red; then the unit tests of each subtask before its code.
- Java 25. Build and test with
  `jh=$(scripts/ensure_jdk25.sh); JAVA_HOME=$jh ./gradlew --no-daemon -Dorg.gradle.java.installations.paths=$jh :command-cli:check`.
- Cyclomatic complexity ≤ 15 per method (NFR-004). One class per lane state is fine (core does
  it); a table plus small guard methods is fine too. No method holds the whole machine.
- No new dependency. No dependency on core (C-001). Not on event-log either: the authority depends
  on `domain-model`, `commandcli.domain.service` and the JDK only.
- Port behaviour, including what looks odd (force to `uninitialized`, research §1.4). Do not
  "correct" core.
- Your main files are top-level files of `domain/authority/` (`*.java`); WP07 adds the
  sub-packages `domain/authority/dependency`, `claim` and `batch`. Do not create those.
- Do not run `make test-full`.
- Record the test commands you ran and their pass counts in your final commit message.

## Subtasks

### T032 `WriteFold` and `WpWriteState`

**Files**: `LogLine.java` (sealed: `Blank`, `Value(Map<String,Object>)`, `Unparseable`),
`WriteFold.java`, `WpWriteState.java`, `UnreducibleLog.java` (exception or result), tests.

**Steps**:
1. Input: the log as `List<LogLine>` (WP08 decodes the bytes; a trailing `\r` is stripped there
   before decoding, research §11.10). Rules of core's `read_events` at the pin: `Blank` skipped;
   a row with a non-lane `event_type` skipped; `Unparseable` or an unknown `kind` → the log is
   unreducible (WP08 answers `500 STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED`). Confirm each rule
   against `store.py` at the pin and write one test per rule.
2. Reduction, as core's reducer at the pin: `event_id` dedupe first-wins (and core's seed
   exceptions, if the pin has them), core's ordering (`(at, event_id)` by code point unless the
   pin differs; check the approved-departure lift of research §8.2), the lane fold, and the
   cancellation provenance projection (`reason_source` explicit wins; prefixes `Force move to `
   and `move-task: ` and blank mean synthetic; any other non-blank reason means operator; research
   §1.6).
3. `WpWriteState` per WP: `lane` (`Lane`; `planned`? or `uninitialized` when no transition: use
   what core's reducer yields for a WP with no events and pin it with a corpus case), the current
   `actor` (raw value) and its tool projection, `role` (from annotation deltas, never by splitting
   a compact actor: research §1.5), `agent`, `reasonSource`, `hasTransitions`, the event-sourced
   subtask state, `implementerOfRecord`, the folded `review` slot. Fold annotation deltas exactly
   as core does (research §1.7: `subtasks`, `note`, `review`, `agent`, `role`, release and claim
   metadata; scalar `""` is absent).
4. API: `static FoldView fold(List<LogLine> lines)` and an overload that takes already-decoded
   rows (`List<Map<String,Object>>`) so WP07's batch judge can re-fold log plus earlier accepted
   units without re-decoding. Performance: one pass plus one sort; WP08 measures 10,000 rows
   (NFR-001).
5. **`FoldView`** (`FoldView.java`, ruling R4): the read-only result of a fold, the one type the gates
   and judges receive. `WpWriteState of(String wpId)` (a work package with no row in the log gets
   the state core's reducer gives it, pinned by a corpus case: never `null`),
   `Map<String, WpWriteState> all()` (unmodifiable), and `boolean known(String wpId)`. It holds no
   reference to the input rows and cannot be mutated.

### T033 `LaneMachine`

**Files**: `LaneMachine.java` (+ per-lane rules if you split), tests.

**Steps**:
1. Tests first: the 29 legal edges of research §1.2 (exactly 29: assert the count), every other
   pair illegal unforced, `done` and `canceled` with no targets, `uninitialized` with no structural
   transitions.
2. `check(from, to, GuardContext)` in core's order (`wp_state.py:164-209`): any target `genesis` →
   `illegal_transition`, even forced; forced → `force_requires_actor_and_reason` unless actor and
   reason are both non-blank (`PyText.isBlank`), else allowed to **any** other target from any
   source, including `uninitialized` and the same lane; unforced non-edge → `illegal_transition`;
   unforced edge → its guard (T034).
3. Messages: core's exact texts (`Force transitions require actor and reason`, the illegal-edge
   text `transitions.py`/`wp_state.py` produce). Keep them in one place.

### T034 `Guards` and `GuardContext`

**Files**: `Guards.java`, `GuardContext.java`, `Actors.java` (structured actor → tool, fallback
`role`), tests.

**Steps**:
1. Tests first: one test per row of research §1.3's table, asserting reason **and exact message**,
   plus precedence tests (actor before dependencies; workspace before dependencies; subtasks before
   evidence; `in_review`: completeness, then approval for `approved`/`done`, then consistency).
2. `GuardContext` fields (data model): actor, workspaceContext, subtasksComplete (tri-state),
   implementationEvidencePresent (tri-state), reason, reviewRef, evidence, force, reviewResult,
   dependencyReady (tri-state; always `null` in this WP: `DependencyGate` fills it in WP07; only an
   explicit `false` refuses).
3. Derivation from the row and the fold (FR-022; `transition_pipeline.py:133-177,289-304`):
   - `workspaceContext`: the row carries none, so core's single-door default
     `<execution_mode>:<repo root>` applies; the domain only needs its blankness, so derive
     `<execution_mode>:<root>` with a fixed root token. Note that this makes
     `workspace_context_required` unreachable over the wire unless WP04 found otherwise; tell WP07
     (FR-012 ledger).
   - `subtasksComplete`: unforced `in_progress → for_review` only: the authored roster from
     `MissionFacts.subtaskRoster(wpId)` (empty means complete) against the folded subtask state;
     read `emit.py:492-548` for what "complete" means and what the roster is.
   - `implementationEvidencePresent`: `hasTransitions` for the WP, inferred even under force.
   - the rest from the row: `actor` (projected), `reason`, `review_ref`, `evidence`, `force`,
     `review_result`.
4. `MissionFacts` (interface, data model): `DependencyDeclaration dependencies(String wpId)`
   (sealed: `Declared(List<String>)`, `None`, `Unresolvable(String message)`) and
   `List<String> subtaskRoster(String wpId)`. WP08 implements it from disk; tests use an in-memory
   one.

### T035 Pipeline guards, stale `from_lane`, unknown lane

**Files**: `PipelineGuards.java`, tests.

**Steps**:
1. Unknown lane: a `from_lane` or `to_lane` that is not a `Lane` wire value →
   `TRANSITION_REJECTED` + `unknown_lane`, core's text `Unknown lane: <original>` (WP03 already
   refused the spellings core would normalize, so what reaches you is unknown even to core).
2. Stale `from_lane` (protocol, FR-023): the row's `from_lane` differs from the fold's lane for the
   WP → `TRANSITION_REJECTED`, reason `null`, a fixed message (e.g. `The unit's from_lane is not the
   work package's current lane.`). Corpus cases `oracle: protocol`, area `stale`.
3. Unnoted review-lane force exit (`transition_pipeline.py:222-242`): forced, out of `in_review` or
   `approved`, no `review_result`, a non-migration actor, a blank reason → `force_note_required`
   with core's text (`models.py:234-241`; it embeds the WP id: see T036 on details).
4. Done evidence (`emit.py:461-489`): `to_lane == done` with non-null evidence whose `review` is not
   a map, or whose reviewer, verdict or reference is blank → `done_evidence_incomplete` with core's
   text.
5. **Order inside the unit (binding: research §11 decision 15, the ruling block at the end of this
   prompt, ruling R4)**: (1) unknown lane → (2) `ClaimGate`, for the lifecycle operations
   (`START_IMPLEMENTATION`, `START_REVIEW`) only, judged against the work package's **actual lane
   in the fold**, not the row's `from_lane` (WP07 implements it; `ClaimGate.NONE` here) → (3) stale
   `from_lane` → (4) force note → (5) done evidence → (6) `DependencyGate` (fills
   `dependencyReady`) → (7) `LaneMachine.check`. Confirm the force-note/done-evidence order against
   `transition_pipeline.py:355-388` and pin each adjacent pair with a test.
6. **Unit test: a `ClaimGate` refusal beats a stale `from_lane`.** With a stub `ClaimGate` that
   refuses `WP_ALREADY_CLAIMED`, a `START_IMPLEMENTATION` unit whose `from_lane` is also stale is
   answered `WP_ALREADY_CLAIMED`, not the stale refusal; the same unit with `Operation.TRANSITION`
   gets the stale refusal (the gate is not consulted for `TRANSITION`). Also: a stub gate that
   allows, on a unit whose row matches a zero-event no-op (compatible holder already in the target
   lane), yields the stale refusal.

### T036 `UnitJudge`, `Refusal`, `Judgement` and the gate seams

**Files**: `UnitJudge.java`, `UnitInput.java` (`wpId`, `Operation`, rows as
`List<Map<String,Object>>`), `Operation.java` (`TRANSITION`, `START_IMPLEMENTATION`,
`START_REVIEW`), `Refusal.java`, `Judgement.java`, `DependencyGate.java`, `ClaimGate.java`,
`DetailPolicy.java`, tests.

**Steps**:
1. `Refusal(RefusalCode code, RefusalReason reason /*nullable*/, String message, String detail,
   Integer unitIndex /*nullable*/)`: `message` is core's text (the corpus checks it); `detail` is
   what the service shows (FR-025): `message` when core's text names only lane vocabulary, a fixed
   text when it would echo an actor, a reason, a note, a path or any other request value. Keep the
   per-reason decision in `DetailPolicy` with a test per reason; never return a detail containing
   a row value.
2. `DependencyGate`: `Boolean readiness(String wpId, Lane from, Lane to, FoldView state,
   MissionFacts facts)` (null = no verdict); `NO_VERDICT` constant. `ClaimGate`:
   `Optional<Refusal> check(Operation op, UnitInput unit, FoldView state)`; `NONE` constant. WP07
   implements both in its sub-packages; `UnitJudge` takes them by constructor.
3. `UnitJudge.judge(UnitInput unit, Map<String, WpWriteState> state, MissionFacts facts)` →
   `Optional<Refusal>` for the unit's transition row (row 0). Annotation rows (1..n) and a
   standalone annotation do not traverse the machine (research §1.7): they need only that the WP
   exists (WP08 checks it) and a valid shape (WP03 checks it).
4. `Judgement`: `Accepted` or `Refused(Refusal)`; WP07's batch judge produces it for a request.

### T037 Purity rule

**Files**: `service/command-cli/src/test/java/org/speckitty/commandcli/architecture/AuthorityPurityTest.java`
(new), planted fixtures under `.../architecture/fixture/authority/` (new).

**Steps**:
1. Rule (parameterized by package, like `EventLogBoundaryTest`'s `codecRule(String)`): classes in
   `org.speckitty.commandcli.domain.authority..` (WP07's sub-packages included) depend only on
   themselves,
   `org.speckitty.commandcli.domain.service..`, `org.speckitty.missionstatus.domain.model..` and
   `java.lang..`, `java.util..` except `java.util.concurrent..`, `java.math..`, `java.time..` except
   `java.time.Clock` and `java.time.InstantSource`. That allowlist excludes `java.io..`,
   `java.nio..` and `java.net..` entirely (no `java.io` in `domain.authority`, not even
   `UncheckedIOException` or `Serializable`; ruling R4). Import main classes the way
   `ArchitectureRulesTest` does (exclude tests and `aotTest`).
2. Non-vacuity: assert the import contains `domain.authority.UnitJudge` and `WriteFold`.
3. Planted violations, one test each: a fixture that uses `java.io.File` (or any `java.io` type), one
   that uses `java.nio.file.Files`, one that uses
   `java.util.concurrent.locks.ReentrantLock`, one that uses `org.springframework` or
   `tools.jackson`, one that uses `java.time.Clock`; and a conforming fixture that holds.

### T038 `ParityCorpusTest`

**Files**: `ParityCorpusTest.java`, `CorpusCases.java` (reader), `CorpusMissionFacts.java`
(in-memory `MissionFacts` from a case's `tasks` texts), all in the test package
`domain/authority/` (WP07 reuses them read-only).

**Steps**:
1. Locate the corpus without a build change: walk up from `user.dir` to the directory holding
   `pyproject.toml` and `src/spec_kitty_events/conformance/status_transition_parity/cases`;
   fail (not skip) when it is not found or holds fewer than 150 cases.
2. Read each case with the test-classpath Jackson and convert to the value tree (`BigInteger`,
   `Double`, ordered maps). `log` → `LogLine.Value`; `log_text` → split on `\n`, strip one trailing
   `\r`, blank → `Blank`, unparseable → `Unparseable`.
3. `CorpusMissionFacts`: parse each task file's YAML frontmatter with SnakeYAML `SafeConstructor`
   (a test dependency already) into rosters and declarations; mirror what WP08's adapter will do
   (core's rules, research §1.6). Keep it small; WP08 owns the production reader.
4. Run every case with `layer: domain` whose `area` is **not** in
   `static final Set<String> WP07_AREAS = Set.of("dependency", "claim", "review_claim",
   "start_rejected", "batch")` through `UnitJudge(DependencyGate.NO_VERDICT, ClaimGate.NONE)`
   (single-unit requests only; a multi-unit request is a batch, WP07's). Compare: ack, or code,
   reason and message equal (`message` exact for `core` cases).
5. Report: a `@TestFactory` with one dynamic test per case id, so the CI log names each failing
   case. The skipped areas are listed in the test's Javadoc with "owned by WP07".
6. **Floors on the subset you run (ruling R4)**, asserted by the test itself so a thin corpus cannot
   pass vacuously: ≥ 29 accepted `lane_machine` cases (one per legal edge), and ≥ 1 refusal case for
   every reason this WP owns (`unknown_lane`, `illegal_transition`,
   `force_requires_actor_and_reason`, `actor_required`, `implementation_evidence_required`,
   `reason_required`, `review_ref_required`, `reviewer_approval_required`,
   `review_evidence_invalid`, `force_note_required`, `done_evidence_incomplete`, and
   `workspace_context_required` unless WP04's `unreachable.json` lists it), plus ≥ 1 `stale` case.
   WP07 owns `dependencies_unsatisfied`, `claim_held_by_other_actor` and `start_rejected`.
7. `ParityCorpusTest` (and every corpus-reading test) is **not** `@Tag("native")`: it reads files
   from the repository, which the native test binary cannot (ruling R4).

## Test strategy

```bash
jh=$(scripts/ensure_jdk25.sh); JAVA_HOME=$jh ./gradlew --no-daemon -Dorg.gradle.java.installations.paths=$jh :command-cli:test --tests '*domain.authority*' --tests '*AuthorityPurityTest'
jh=$(scripts/ensure_jdk25.sh); JAVA_HOME=$jh ./gradlew --no-daemon -Dorg.gradle.java.installations.paths=$jh :command-cli:check
uv run pytest --pyargs spec_kitty_events.conformance -k parity -q   # the corpus shape, unchanged by you
```

Record in the commit message how many corpus cases ran, passed and were skipped as WP07-owned.

## Definition of Done

- [ ] `domain.authority` holds the fold, the machine, the guards, the pipeline guards, the stale
      rule, `UnitJudge` and the two gate seams; every class meets the JaCoCo 90 % line and 80 %
      branch floors; complexity ≤ 15.
- [ ] `AuthorityPurityTest` holds on the real package and fires on every planted fixture.
- [ ] The unit order is unknown lane → ClaimGate (lifecycle ops, actual lane) → stale → force note →
      done evidence → DependencyGate → machine, with the ClaimGate-beats-stale test; `FoldView` is
      defined and is the only state type the gates see.
- [ ] `ParityCorpusTest` (untagged) asserts its floors (≥ 29 accepted `lane_machine` cases, ≥ 1
      case per WP06 reason) and passes every `domain` case outside `WP07_AREAS` (100 %); the skip list is
      explicit and named.
- [ ] No use of `StatusReducer` or any event-log class; the read service is unchanged (C-010).
- [ ] `:command-cli:check` green; the final commit message lists the commands and counts.

## Risks

- **Precedence**: the commonest port bug (research §10 risk 10). Each precedence pair has a test.
- **Fold drift from core**: the corpus `fold` cases (annotation deltas, duplicates, ordering) are
  the check; if a case fails, read core at the pin again before changing the case.
- **Echoing details**: a detail built from core's message can leak an actor name; `DetailPolicy`
  tests guard it.

## Reviewer guidance

- Count the edges in `LaneMachine`'s test (29) and compare the table with research §1.2.
- Read three guard messages against core at the pin.
- Check `WP07_AREAS` is the only skip mechanism and is used nowhere else.
- Check the purity rule's planted fixtures actually fail the rule (run them).

## Orchestrator ruling (2026-11-07): lifecycle operations and the review re-claim

This ruling is binding (research.md §11, decision 15). For `start_implementation` and `start_review`, core's lifecycle logic runs against the current lane in the fold before the stale-`from_lane` check. It gives one of three outcomes:

- an incompatible holder → `WP_ALREADY_CLAIMED` / `claim_held_by_other_actor`;
- a lane the start does not accept → `TRANSITION_REJECTED` / `start_rejected`;
- a compatible holder already in the target lane, which is core's zero-event no-op → stale `from_lane` (`TRANSITION_REJECTED`, no URN, `oracle: protocol`).

A start from `planned` is a batch of two units, both `start_implementation`. A review re-claim is a `start_review` unit with row `for_review → in_review`. A different actor holding `in_review` with role `reviewer` → `WP_ALREADY_CLAIMED`. The corpus pins each of these.
