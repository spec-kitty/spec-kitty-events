---
work_package_id: WP07
title: 'Write domain, part 2: dependencies, claims and batches'
dependencies:
- WP06
requirement_refs:
- FR-012
- FR-013
- FR-014
- FR-022
- FR-024
- NFR-004
- SC-002
- C-003
- C-004
planning_base_branch: kitty/mission-events-write-core-01M6TP6W
merge_target_branch: main
branch_strategy: Planning artifacts for this mission were generated on kitty/mission-events-write-core-01M6TP6W. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into main unless the human explicitly redirects the landing branch.
subtasks:
- T039
- T040
- T041
- T042
- T043
history:
- timestamp: '2026-11-07T10:30:00Z'
  lane: planned
  agent: system
  action: Prompt generated via /spec-kitty.tasks
agent_profile: java-jenny
authoritative_surface: service/command-cli/src/main/java/org/speckitty/commandcli/domain/authority/batch/
create_intent:
- service/command-cli/src/main/java/org/speckitty/commandcli/domain/authority/dependency/
- service/command-cli/src/main/java/org/speckitty/commandcli/domain/authority/claim/
- service/command-cli/src/main/java/org/speckitty/commandcli/domain/authority/batch/
- service/command-cli/src/test/java/org/speckitty/commandcli/domain/authority/dependency/
- service/command-cli/src/test/java/org/speckitty/commandcli/domain/authority/claim/
- service/command-cli/src/test/java/org/speckitty/commandcli/domain/authority/batch/
execution_mode: code_change
owned_files:
- service/command-cli/src/main/java/org/speckitty/commandcli/domain/authority/dependency/**
- service/command-cli/src/main/java/org/speckitty/commandcli/domain/authority/claim/**
- service/command-cli/src/main/java/org/speckitty/commandcli/domain/authority/batch/**
- service/command-cli/src/test/java/org/speckitty/commandcli/domain/authority/dependency/**
- service/command-cli/src/test/java/org/speckitty/commandcli/domain/authority/claim/**
- service/command-cli/src/test/java/org/speckitty/commandcli/domain/authority/batch/**
role: implementer
tags: []
tracker_refs: []
---
# Work Package Prompt: WP07 - Write domain, part 2: dependencies, claims and batches

## ⚡ Do This First: Load Agent Profile

Load your assigned profile before reading anything else: `/spk-charter-profile-load java-jenny`
(or `spec-kitty agent profile show java-jenny`). Adopt its identity, scope and boundaries.

## Objective

Complete the lane authority WP06 started, in three pure sub-packages of
`org.speckitty.commandcli.domain.authority`:

1. **`dependency`**: `DependencyVerdict implements DependencyGate` (FR-013, FR-022's last input).
2. **`claim`**: `ClaimRules implements ClaimGate`: the lifecycle doors core runs for
   `start_implementation` and `start_review` (FR-024): implementation-claim actor compatibility,
   generic placeholders yielding, the implementer of record resuming, the role-aware review
   re-claim, `start_rejected`, and the lifecycle-only force rules.
3. **`batch`**: `BatchJudge`, which judges each unit of a request against the fold of the log plus
   the earlier units of the same request (FR-014), across work packages, and `Authority`, the one
   assembly WP08 calls.

Then the **whole corpus** is green through `Authority` (SC-002), and every one of the 15 reasons is
either reached by a case or recorded as unreachable over the wire with its cause (FR-012).

## Context

- Spec: FR-012, FR-013, FR-014, FR-022, FR-024, C-003, C-004 (the `move-task` agent-mismatch
  guard and the for-review commit gate stay client-side: do **not** port them), SC-002; US2
  scenarios 2 and 3, US3 scenarios 1 and 2.
- Research: §1.4 paragraph 3 (lifecycle-only force rules), §1.5 (claim rules, actor compatibility,
  generic placeholders, implementer of record, role-aware review re-claim, zero-event idempotent
  starts), §1.6 (dependency semantics and declarations: frontmatter `dependencies`, the
  `^<wpId>(?:[-_.]|\.md$)` match over `tasks/*.md` excluding `readme.md`, zero files = no
  dependencies, several = unresolvable, accepted forms `[]`, `WP01, WP02`, bare `WP01`, malformed
  → unsatisfied), §2 (`claim_held_by_other_actor` → `WP_ALREADY_CLAIMED`;
  `dependencies_unsatisfied` → `DEPENDENCIES_NOT_SATISFIED`), §3.2 (batches may span work packages;
  do not port core's older same-WP batch restriction), §10 risks 1 and 2, §11.2, §11.12.
- Core at the pin (read, never import): `src/specify_cli/status/dependency_verdict.py`,
  `src/specify_cli/core/dependency_graph.py:35-177`, `src/specify_cli/status_lanes.py:37-96`,
  `src/specify_cli/status/emit.py:323-458,591-607`, `src/specify_cli/status/wp_metadata.py:187-205`,
  `src/specify_cli/status/work_package_lifecycle.py` (all of it),
  `src/specify_cli/status/review_claim_predicate.py:52-87`,
  `src/specify_cli/coordination/status_service.py:260-301`.
- WP06's seams (read-only for you): `domain/authority/DependencyGate.java`, `ClaimGate.java`,
  `UnitJudge.java`, `UnitInput.java`, `Operation.java`, `WriteFold.java` (the decoded-rows
  overload), `WpWriteState.java`, `MissionFacts.java` (`DependencyDeclaration`), `Refusal.java`,
  `DetailPolicy.java`, `Judgement.java`; test helpers `CorpusCases.java`,
  `CorpusMissionFacts.java`, and `ParityCorpusTest.java` (its `WP07_AREAS` names your areas:
  `dependency`, `claim`, `review_claim`, `start_rejected`, `batch`).
- JaCoCo: `org.speckitty.commandcli.domain.*` classes need 90 % lines and 80 % branches each
  (`service/command-cli/build.gradle.kts:189-217`).

## Branch Strategy

Planning happens on `kitty/mission-events-write-core-01M6TP6W` in an owned checkout
(`single_branch` topology). The mission lands as one PR to `main` from `issue-160-write-core`;
the planning base and merge target recorded here are `main`.
`spec-kitty agent action implement WP07 --agent <name> --mission events-write-core-01M6TP6W`

## Ground rules

- ATDD, red first: write the full-corpus runner (T043) first and run it red; it names every
  failing case.
- Java 25. Build and test with
  `jh=$(scripts/ensure_jdk25.sh); JAVA_HOME=$jh ./gradlew --no-daemon -Dorg.gradle.java.installations.paths=$jh :command-cli:check`.
- Cyclomatic complexity ≤ 15 per method (NFR-004): one method per lane in the start rules.
- Pure, like WP06: WP06's `AuthorityPurityTest` covers your sub-packages too (`domain.authority..`).
- No new dependency. No dependency on core (C-001).
- Do not edit WP06's files. Plug in through the constructors of `UnitJudge` and the gate
  interfaces. If a seam is genuinely missing, add the smallest change, declare it as an out-of-map
  edit in the PR and keep WP06's tests green.
- Do not run `make test-full`.
- Record the test commands you ran and their pass counts in your final commit message.

## Subtasks

### T039 `DependencyVerdict`

**Files**: `domain/authority/dependency/DependencyVerdict.java`, tests.

**Steps**:
1. Tests first, from research §1.6: only `planned → claimed` and `claimed → in_progress` are gated
   (every other edge returns `null`, no verdict); `None` (no declaration) → `true`; `Declared`
   deps each satisfied when their folded lane is `approved` or `done`, or `canceled` with
   `reasonSource == operator`; a dependency missing from the fold defaults to `planned`
   (unsatisfied); `for_review`, `in_review`, `blocked`, an invalid lane → unsatisfied;
   `Unresolvable` → `false`.
2. The refusal itself comes from WP06's machine (`dependencyReady == false` →
   `dependencies_unsatisfied` with core's text, `DEPENDENCIES_NOT_SATISFIED`); force with actor
   and reason bypasses it there. Your class only computes the tri-state.
3. The declaration reading (file match, YAML forms) is WP08's adapter; `MissionFacts` hands you a
   `DependencyDeclaration`. Write a test that pins the accepted forms through
   `CorpusMissionFacts` so WP08 can copy the expectations.

### T040 `ClaimRules`: implementation start

**Files**: `domain/authority/claim/ClaimRules.java`, `ActorCompatibility.java`, tests.

**Steps**:
1. `ClaimRules.check(Operation, UnitInput, FoldView)` returns `Optional.empty()` for
   `TRANSITION` (core's emit pipeline has no claim rules; applying them to `move-task` would change
   its codes, research §11.2).
2. `START_IMPLEMENTATION`, by the work package's lane in the fold (`work_package_lifecycle.py:323-453`):
   - `genesis` → `start_rejected` (not finalized, core's text);
   - `planned` → allowed (the unit is `planned → claimed`; the following `claimed → in_progress`
     arrives as the next unit of the same batch, WP04's README);
   - `claimed` → the holder and the requester must be compatible, else `WP_ALREADY_CLAIMED` +
     `claim_held_by_other_actor` with core's text (`WP <id> is already claimed for implementation by
     '<holder>'`: it echoes the holder, so its detail is a fixed text via `DetailPolicy`);
   - `in_progress` → a different, incompatible actor conflicts, except the implementer of record
     resuming after a reviewer's rework (`_admits_implementer_of_record`, `:183-207`);
   - any other lane → `start_rejected` (`WP <id> is in '<lane>', cannot start implementation`),
     unless the explicit review-lane exit applies (T041).
3. `ActorCompatibility` (`:32-57,117-158`): project structured and compact actors to the bare tool;
   compatible when either side is missing or the tools match; the generic placeholders
   `implement-command`, `unknown`, `user` yield to a real claimant. Role-blind by design.
4. The holder comes from the fold (`WpWriteState`: actor, and role from annotation deltas, never by
   splitting a compact actor; `status_service.py:260-301`).

### T041 `ClaimRules`: review start, `start_rejected`, lifecycle force rules

**Steps**:
1. `START_REVIEW` (`work_package_lifecycle.py:490-533`, `review_claim_predicate.py:52-87`):
   `for_review → in_review` needs only an actor (WP06's machine refuses a blank one); already
   `in_review` conflicts only when the holder's role is exactly `reviewer` after `strip()`, the
   holder's actor is non-blank and the requester differs (requester role ignored) →
   `WP_ALREADY_CLAIMED`; other lanes → `start_rejected` (`... cannot start review`).
2. Lifecycle-only force rules (research §1.4 paragraph 3, `work_package_lifecycle.py:210-263`):
   an operator force note is allowed only for `for_review`, `in_review` or `approved`; otherwise
   `start_rejected` (`FORCE_NOTE_REQUIRED` or `--force only applies to ...`); a force exit from
   `in_review` without the note is a review claim conflict, from `approved` a `start_rejected`.
   Port the messages verbatim; route echoing ones through `DetailPolicy`.
3. Zero-event outcomes (same-actor idempotent starts) never reach the service (spec assumption:
   the client sends no unit). A unit that nonetheless arrives for such a state is judged by what
   its row says (WP06's stale rule and machine). WP04's README records the wire shape the corpus
   chose for a review re-claim; follow it, and if it is undecided, raise it in the PR rather than
   inventing one.

### T042 `BatchJudge` and `Authority`

**Files**: `domain/authority/batch/BatchJudge.java`, `Authority.java`, `AuthorityInput.java`,
tests.

**Steps**:
1. `Authority.standard()` composes `UnitJudge(new DependencyVerdict(), new ClaimRules())` and a
   `BatchJudge`. `Authority.judge(List<LogLine> log, List<UnitInput> newUnits, boolean batch,
   MissionFacts facts)` → `Judgement`. WP08 passes only the **new** units (replays are classified
   before the authority, FR-026 step 9) in request order, with their original request indexes.
2. `BatchJudge`: for unit *i*, fold `log` plus the rows of the accepted units `< i` (re-fold, as
   core re-reduces in-lock per call; use WP06's decoded-rows overload), judge unit *i*; the first
   refusal stops the request and carries `unitIndex` = the unit's index **in the request** (WP08
   renders `instance: #/units/<i>`) when `batch` is true, `null` for a single unit.
3. Units may name different work packages of one Mission (research §3.2); a unit's annotation rows
   join the fold for the units after it (FR-024: "the earlier units of a batch").
4. Tests first: a batch whose unit 2 is legal only after unit 1; one refused at unit 3; one spanning
   WP01 and WP02 where WP02's dependency is satisfied by unit 1 moving WP01 to `approved`; a
   two-unit `start_implementation` from `planned`; a 64-unit batch (no quadratic blow-up beyond
   64 re-folds; time it).

### T043 Full corpus green and the reason ledger

**Files**: `domain/authority/batch/FullParityCorpusTest.java`, `ReasonLedgerTest.java`.

**Steps**:
1. `FullParityCorpusTest`: every `layer: domain` case (all areas, single units and batches) through
   `Authority.standard()`, reusing WP06's `CorpusCases` and `CorpusMissionFacts`; compare ack, or
   code, reason, message and unit index. 100 % pass; one dynamic test per case id. WP06's
   `ParityCorpusTest` keeps running its gate-free subset unchanged.
2. `ReasonLedgerTest` (FR-012): for each of the 15 `RefusalReason` values, either ≥ 1 passing
   domain case refuses with it, or WP04's `unreachable.json` lists it with a non-empty cause; never
   both. If you find a reason unreachable that WP04 did not record (or the reverse), the file is
   WP04's: make the change as a declared out-of-map edit with the cause, and say so in the PR.
3. Record the per-area pass counts and the ledger (reason → case ids or cause) in the commit
   message.

## Test strategy

```bash
jh=$(scripts/ensure_jdk25.sh); JAVA_HOME=$jh ./gradlew --no-daemon -Dorg.gradle.java.installations.paths=$jh :command-cli:test --tests '*domain.authority*' --tests '*AuthorityPurityTest'
jh=$(scripts/ensure_jdk25.sh); JAVA_HOME=$jh ./gradlew --no-daemon -Dorg.gradle.java.installations.paths=$jh :command-cli:check
uv run pytest --pyargs spec_kitty_events.conformance -k parity -q
```

## Definition of Done

- [ ] `DependencyVerdict`, `ClaimRules` (both starts and the force rules), `BatchJudge` and
      `Authority` exist in the three sub-packages, pure, each class at the JaCoCo floors,
      complexity ≤ 15.
- [ ] `FullParityCorpusTest` passes 100 % of the domain cases; `ParityCorpusTest` stays green.
- [ ] `ReasonLedgerTest` accounts for all 15 reasons.
- [ ] No client-side check of C-004 was ported.
- [ ] `:command-cli:check` green; the final commit message lists the commands, the counts and the
      ledger.

## Risks

- **Actor and role from one reduction**: splitting `tool:role` strings instead of reading the
  folded `role` slot gives wrong review conflicts (research §1.5).
- **Batch re-fold ordering**: appending a later unit's rows without re-sorting diverges from core
  when `at` values are not monotonic; re-fold.
- **Over-porting**: claim rules on `TRANSITION` change `move-task`'s codes (C-003).

## Reviewer guidance

- Read `ActorCompatibility` against `work_package_lifecycle.py:32-57,117-158` at the pin.
- Check the batch test that spans two work packages and the one refused at unit 3 (instance index).
- Check the ledger output: every reason has a case id or a cause.

## Orchestrator ruling (2026-11-07): lifecycle operations and the review re-claim

This ruling is binding (research.md §11, decision 15). For `start_implementation` and `start_review`, core's lifecycle logic runs against the current lane in the fold before the stale-`from_lane` check. It gives one of three outcomes:

- an incompatible holder → `WP_ALREADY_CLAIMED` / `claim_held_by_other_actor`;
- a lane the start does not accept → `TRANSITION_REJECTED` / `start_rejected`;
- a compatible holder already in the target lane, which is core's zero-event no-op → stale `from_lane` (`TRANSITION_REJECTED`, no URN, `oracle: protocol`).

A start from `planned` is a batch of two units, both `start_implementation`. A review re-claim is a `start_review` unit with row `for_review → in_review`. A different actor holding `in_review` with role `reviewer` → `WP_ALREADY_CLAIMED`. The corpus pins each of these.
