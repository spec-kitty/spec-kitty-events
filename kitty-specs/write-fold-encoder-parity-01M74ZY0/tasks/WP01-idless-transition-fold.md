---
work_package_id: WP01
title: Id-less transitions mirror core (#231 4.16, 4.17)
dependencies: []
requirement_refs:
- FR-001
- FR-002
- FR-003
- FR-004
- NFR-003
- SC-001
- C-005
planning_base_branch: issue-237-write-fold-encoder-parity
merge_target_branch: issue-237-write-fold-encoder-parity
branch_strategy: Single-branch mission. Planning, base and merge target are issue-237-write-fold-encoder-parity; the finished branch goes to main through one PR.
subtasks:
- T001
- T002
- T003
- T004
- T005
- T006
history:
- timestamp: '2026-11-11T11:30:00Z'
  lane: planned
  agent: system
  action: Prompt generated via /spec-kitty.tasks
agent_profile: java-jenny
authoritative_surface: service/command-cli/src/main/java/org/speckitty/commandcli/domain/authority/
create_intent:
- service/command-cli/src/main/java/org/speckitty/commandcli/domain/authority/TransitionId.java
- service/command-cli/src/test/java/org/speckitty/commandcli/domain/authority/FoldDifferentialTest.java
- service/command-cli/src/test/java/org/speckitty/commandcli/adapter/in/http/operations/NonFiniteLogIdTest.java
- service/command-cli/src/test/resources/fold-differential/README.md
- scripts/conformance/generate_fold_differential.py
- tests/test_fold_differential.py
- docs/changelog/unreleased/237-idless-transition-fold.md
execution_mode: code_change
owned_files:
- service/command-cli/src/main/java/org/speckitty/commandcli/domain/authority/WriteFold.java
- service/command-cli/src/main/java/org/speckitty/commandcli/domain/authority/TransitionFold.java
- service/command-cli/src/main/java/org/speckitty/commandcli/domain/authority/EventOrdering.java
- service/command-cli/src/main/java/org/speckitty/commandcli/domain/authority/EventIds.java
- service/command-cli/src/main/java/org/speckitty/commandcli/domain/authority/TransitionId.java
- service/command-cli/src/test/java/org/speckitty/commandcli/domain/authority/FoldDifferentialTest.java
- service/command-cli/src/test/java/org/speckitty/commandcli/domain/authority/WriteFoldTest.java
- service/command-cli/src/test/java/org/speckitty/commandcli/domain/authority/TransitionFoldAdvancedTest.java
- service/command-cli/src/test/java/org/speckitty/commandcli/adapter/in/http/operations/NonFiniteLogIdTest.java
- service/command-cli/src/test/resources/fold-differential/**
- scripts/conformance/generate_fold_differential.py
- tests/test_fold_differential.py
- docs/changelog/unreleased/237-idless-transition-fold.md
role: implementer
tags: []
tracker_refs: []
---
# Work Package Prompt: WP01 — Id-less transitions mirror core (#231 4.16, 4.17)

## ⚡ Do This First: Load Agent Profile

Load your assigned profile before reading anything else: `/spk-charter-profile-load java-jenny`
(or `spec-kitty agent profile show java-jenny`). Adopt its identity, scope and boundaries.

## Objective

A transition row whose `event_id` is absent or JSON `null` must be judged by the write fold exactly
as core's reducer judges it: **it is never the current-lane setter**. Today the Java fold spells the
id with `pyText(null)` → `"None"`, stores that as `last_event_id`, and later finds "the event whose
id spells `None`", i.e. the id-less row itself. So the service applies continuity and same-timestamp
rules against a setter core does not have, and the two disagree on the lane.

Operator ruling **M2-R1**: the id-less case is a deliberate, distinct case. Model it through its own
constructor or type; do **not** fix it with a null check or a sentinel-string comparison.

This WP must be landable on its own (core #6195 depends on it). Keep it self-contained.

## Context (read first)

- Spec: [../spec.md](../spec.md) User Story 1, FR-001–FR-004, NFR-003, C-005.
- Research: [../research.md](../research.md) "Re-verified references" rows 1–2, D-1, D-2.
- Data model: [../data-model.md](../data-model.md) "TransitionId" (the invariants are binding).
- Core semantics (this package's reducer *is* core's reducer; core's `status/reducer.py` imports
  `spec_kitty_events.diary`):
  - `src/spec_kitty_events/diary.py:1454-1458` `_find_event_by_id`: `None` → no setter.
  - `diary.py:1493` `_same_timestamp_verdict` returns `None` (abstains) when there is no setter.
  - `diary.py:1631-1637` arm (ii) only fires when `current_setter is not None`.
  - `diary.py:1886-1903` `_dedup_transitions`: groups by the raw `event_id`, so all `None` ids are
    **one group** (first kept, or the canonical-minimum row when all are seeds).
  - Ordering sorts by `(at, ..., event_id)`; `None` vs a string raises `TypeError` → unreducible.
- Java seams:
  - `WriteFold.java:119-146` `record Transition`: `id()` = `pyText(row.get("event_id"))` (:132),
    `idKey()` = `EventIds.key(...)` (:134).
  - `TransitionFold.java:45` stores `last_event_id`; `:73` `find(String.valueOf(...))`; `:83-86`
    `find` (its `"null"` guard is dead code).
  - `AnnotationFold.java:36` resets `last_event_id` to `null` (no setter) — keep that meaning.
  - `EventOrdering.java:28-39` dedupe groups by `idKey()` and refuses two groups whose `id()`
    spells alike; `:52-121` the carry maps (`effective`, `sequence`, `lifted`) are keyed by `id()`.
  - `EventIds.java` `key`, `sameId`, `compare` (Python equality and ordering of raw ids).
- Existing tests that must stay green: `WriteFoldTest`, `TransitionFoldAdvancedTest`,
  `ParityCorpusTest`, `CorpusAuthorityCases`, the `adapter/in/http/operations` suite.

## Subtasks

### T001 — `TransitionId` model

1. New `domain/authority/TransitionId.java`: a sealed interface with two records/singletons:
   - `Identified(Object raw, String spelling)` — `raw` is the decoded JSON scalar (String,
     BigInteger, Double, Boolean); `spelling` is `WriteFold.pyText(raw)`. Equality and hashing go
     through `EventIds.key(raw)` (Python equality: `1 == 1.0 == True`, `"1" != 1`).
   - `Absent` — a singleton; equal only to itself (core's `None` as a dict key).
   - Factory: `static TransitionId of(Map<String,Object> row)` returns `Absent` when the key is
     missing or its value is `null`, else `Identified`. No other code builds either variant.
   - `boolean identified()` or pattern matching; a `key()` usable in maps; a `compare` that
     delegates to `EventIds.compare` semantics (Absent vs Absent = 0; Absent vs Identified throws
     `UnreducibleLog`, as Python's `TypeError`).
2. `WriteFold.Transition` exposes `TransitionId id()` (rename the old String accessor to
   `spelling()` only if something still needs a spelling, e.g. `last_event_id` projections).
   Grep for every caller of `.id()` and `.idKey()` on `Transition` and move them to the model.
3. Keep cyclomatic complexity ≤ 15 per method (NFR-004).

### T002 — Arbitration through the model

1. `TransitionFold.stateFrom` stores the setter's `TransitionId` (an internal slot, e.g.
   `"__setter"`), not a spelling. If `last_event_id` is visible in the projected state
   (`WriteFold.stateView` → `WpWriteState.slots`), keep its current visible value semantics:
   check who reads it (`grep -rn last_event_id service/command-cli`) and keep any observable output
   byte-identical for identified ids; for an id-less setter the slot is `null`, as core's
   `last_event_id: None`.
2. `arbitrate`: `Optional<Transition> setter = setterOf(current, all)` where an `Absent` setter id
   yields `Optional.empty()` — **this is the ruling**. Then exactly core's structure:
   same-timestamp arm only if a setter exists; continuity arm only if a setter exists.
3. Remove the dead `"null".equals(eventId)` guard.
4. `AnnotationFold` keeps resetting the setter to "none" (no behaviour change).

### T003 — Id-keyed maps and the dedupe spelling check

1. `EventOrdering.dedup`: group by `TransitionId` (same grouping as today's `idKey`, so all
   `Absent` rows are one group — matches core). The "ids that spell alike are not equal" refusal
   must compare spellings **only among identified ids**: an `Identified("None")` next to `Absent`
   is two distinct keys in core and must not be refused.
2. `EventOrdering` carry maps (`effective`, `sequence`, `lifted`) and `afterApproval`: key them by
   `TransitionId` instead of the spelling, so `Absent` and `Identified("None")` never share an entry.
3. `compare`/`compareOrdered`: tie-break through the model; behaviour for identified ids unchanged.
4. Run `WriteFoldTest`, `TransitionFoldAdvancedTest`, `ParityCorpusTest` after this step; they must
   be unchanged (all corpus ids are ULID strings).

### T004 — Differential cases from the package reducer [P]

1. `scripts/conformance/generate_fold_differential.py`: reads hand-written inputs (log rows) and
   writes `service/command-cli/src/test/resources/fold-differential/<id>.json` with
   `{"id", "notes", "log": [...], "expected": {"<wp_id>": "<lane>", ...}}`, where `expected` is
   `spec_kitty_events.diary.reduce(log).work_packages[wp]["lane"]` (check the exact accessor; the
   reducer returns a `State`). `--check` re-derives and exits non-zero on drift. Deterministic
   output (sorted keys, 2-space indent, trailing LF).
2. At least these cases (FR-003 "both directions"):
   - `idless-setter-stale-forward-applies`: `planned→claimed` with `event_id: null`, then a
     non-forced `for_review→in_review` with a ULID at a later `at`. Core: `in_review` (no setter →
     no continuity). Old Java: `claimed` (continuity drop). Verified in this session:
     `reduce(...)` gives `in_review`.
   - `idless-setter-claimed-to-in-progress`: a log where core **drops** an event that the old Java
     fold applied, or applies one the old fold dropped, around `claimed → in_progress` (the #231
     comment's wording). Construct it, run the generator, and record in `notes` which side changed.
     If the only reachable divergence is in one direction, say so in `notes` and in the WP's
     activity log, with the reasoning.
   - `idless-same-timestamp-rollback`: same `at`, id-less forward setter vs a rollback event.
   - `idless-two-rows-dedupe`: two id-less rows for one WP — first kept (core groups `None`).
   - `idless-and-string-none`: an id-less row and a row whose id is the string `"None"` for
     different WPs at different `at` — must fold, not be refused.
   - `identified-control`: the same log as case 1 with a ULID on the first row → `claimed`
     (positive control: the fix does not disable continuity for identified setters).
3. `tests/test_fold_differential.py`: runs the generator in `--check` mode logic in-process (import
   it with `-I`-safe means or call its function), so CI's Python suite holds the files to the
   reducer. Use the package's public reducer only; no core import.
4. `fold-differential/README.md`: one paragraph on the provenance (package reducer = core's reducer)
   and how to regenerate.

### T005 — Java differential test

`FoldDifferentialTest` (JUnit `@TestFactory`): for every JSON file in
`src/test/resources/fold-differential/`, fold `log` with `WriteFold.fold(rows)` (decode rows the way
the log codec does, so numbers stay `BigInteger`/`Double`) and assert each expected WP's lane. A
missing directory or zero files must fail the test (no vacuous pass).

### T006 — Non-finite log id + fragment

1. `NonFiniteLogIdTest` (HTTP, using `WriteRig`): seed the log with a prior transition whose
   `event_id` is `NaN`, `Infinity` and `1e400` (three cases; write the raw bytes, these are not
   valid JSON numbers for every parser — mirror how `EventIds`/the codec decode them, see
   research.md decision 22 of `events-write-core-01M6TP6W`), post a valid unit, and assert: status
   `500`, a coded refusal body (the code the service uses for an unreducible/unreadable log; read
   `WriteService`/`EventIds.java:26` to name it), and the log bytes unchanged. Positive control:
   the same log with a finite id is acknowledged (`201`).
2. Fragment `docs/changelog/unreleased/237-idless-transition-fold.md`, section `Fixed`, e.g.:
   "- The write service no longer treats a transition with no `event_id` as a work package's
   current-lane setter. Core's reducer finds no setter for such a row, so on a hand-written log the
   two could disagree on the lane (#237; #231 item 4.16). A non-finite log `event_id` stays a coded
   500 with nothing written, now pinned by a test (#231 item 4.17)."

## Out-of-map edits

None expected. If `WpWriteState` or `StateProjections` must change to keep the `last_event_id`
projection, record a one-line rationale in the activity log.

## Definition of Done

- `./gradlew :command-cli:test` green (record the count), including the new tests.
- `uv run pytest tests/test_fold_differential.py -q` green; `uv run python scripts/conformance/generate_fold_differential.py --check` exits 0.
- `ruff check . && ruff format --check .` clean; `mypy --strict` clean on the new script if it is
  under the mypy scope.
- No `"None"`-spelling comparison remains in the fold; `grep -n '"None"\|"null"' domain/authority/*.java` shows only `pyText`.
- Fragment passes `uv run python scripts/changelog.py check`.

## Risks and reviewer guidance

- Reviewer: check the four invariants of data-model.md one by one against the code; check that
  `Absent` cannot be built except by the factory; check the positive-control case.
- Ordering regressions: compare `ParityCorpusTest` and `TransitionFoldAdvancedTest` counts before
  and after.

## Do not

- Do not touch `service/src` (the read service) or `service/README.md`.
- Do not change the parity corpus or any packaged schema.
- Implementation command: `spec-kitty agent action implement WP01 --agent <name> --mission write-fold-encoder-parity-01M74ZY0`
