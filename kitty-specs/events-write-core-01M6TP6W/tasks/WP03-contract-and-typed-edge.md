---
work_package_id: WP03
title: Contract operation field and the typed write edge
dependencies: []
requirement_refs:
- FR-012
- FR-019
- FR-023
- FR-024
- FR-025
- FR-027
- FR-028
- NFR-004
- C-002
- C-008
planning_base_branch: kitty/mission-events-write-core-01M6TP6W
merge_target_branch: main
branch_strategy: Planning artifacts for this mission were generated on kitty/mission-events-write-core-01M6TP6W. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into main unless the human explicitly redirects the landing branch.
subtasks:
- T014
- T015
- T016
- T017
- T018
- T019
history:
- timestamp: '2026-11-07T10:30:00Z'
  lane: planned
  agent: system
  action: Prompt generated via /spec-kitty.tasks
agent_profile: java-jenny
authoritative_surface: service/command-cli/src/main/java/org/speckitty/commandcli/adapter/in/http/operations/WriteBodies.java
create_intent:
- contracts/mission-write/schemas/WriteOperation.yaml
- contracts/mission-write/examples/TransitionUnit.start-implementation.yaml
- service/command-cli/src/main/java/org/speckitty/commandcli/application/write/WriteRequest.java
- service/command-cli/src/main/java/org/speckitty/commandcli/domain/service/RefusalReason.java
- service/command-cli/src/test/java/org/speckitty/commandcli/adapter/in/http/operations/WriteBodiesTest.java
- service/command-cli/src/test/java/org/speckitty/commandcli/domain/service/RefusalVocabularyTest.java
- service/domain-model/src/main/java/org/speckitty/missionstatus/domain/model/PyIsoDateTime.java
- service/domain-model/src/test/java/org/speckitty/missionstatus/domain/model/PyIsoDateTimeTest.java
- service/domain-model/src/test/resources/pyisodatetime/vectors.json
- scripts/conformance/generate_fromisoformat_vectors.py
execution_mode: code_change
owned_files:
- contracts/mission-write/schemas/WriteOperation.yaml
- contracts/mission-write/schemas/TransitionUnit.yaml
- contracts/mission-write/schemas/WriteRefusalCode.yaml
- contracts/mission-write/schemas/WriteRefusalReason.yaml
- contracts/mission-write/schemas/_index.yaml
- contracts/mission-write/examples/TransitionUnit.start-implementation.yaml
- contracts/mission-write/CHANGELOG.md
- contracts/tools/enum_pins.json
- service/command-cli/src/main/java/org/speckitty/commandcli/domain/service/RefusalCode.java
- service/command-cli/src/main/java/org/speckitty/commandcli/domain/service/RefusalReason.java
- service/command-cli/src/main/java/org/speckitty/commandcli/adapter/in/http/edge/RefusalBody.java
- service/command-cli/src/main/java/org/speckitty/commandcli/adapter/in/http/operations/WriteBodies.java
- service/command-cli/src/main/java/org/speckitty/commandcli/application/write/WriteRequest.java
- service/command-cli/src/test/java/org/speckitty/commandcli/adapter/in/http/edge/RefusalBodyTest.java
- service/command-cli/src/test/java/org/speckitty/commandcli/adapter/in/http/operations/WriteBodiesTest.java
- service/command-cli/src/test/java/org/speckitty/commandcli/domain/service/DomainValuesTest.java
- service/command-cli/src/test/java/org/speckitty/commandcli/domain/service/RefusalVocabularyTest.java
- service/domain-model/src/main/java/org/speckitty/missionstatus/domain/model/PyIsoDateTime.java
- service/domain-model/src/test/java/org/speckitty/missionstatus/domain/model/PyIsoDateTimeTest.java
- service/domain-model/src/test/resources/pyisodatetime/**
- service/domain-model/build.gradle.kts
- scripts/conformance/generate_fromisoformat_vectors.py
role: implementer
tags: []
tracker_refs: []
---
# Work Package Prompt: WP03 - Contract `operation` field and the typed write edge

## ⚡ Do This First: Load Agent Profile

Load your assigned profile before reading anything else: `/spk-charter-profile-load java-jenny`
(or `spec-kitty agent profile show java-jenny`). Adopt its identity, scope and boundaries.

## Objective

Three things, all on the request side of the write path:

1. **The contract delta** of `mission-write` `1.0.1-SNAPSHOT`
   ([contracts/mission-write-1.0.1-snapshot-delta.md](../contracts/mission-write-1.0.1-snapshot-delta.md)):
   a new `WriteOperation` enum, an optional `TransitionUnit.operation`, one example, the enum pin,
   the widened descriptions and their CHANGELOG entries. Additive or descriptive only (C-008).
2. **The refusal vocabulary in code**: `RefusalCode` gains every domain code the write path
   answers, with the status the contract fixes and a title; a `RefusalReason` enum carries the 15
   URNs and the code each binds to; `RefusalBody` gains an optional `type`.
3. **A typed edge**: `WriteBodies` returns a typed `WriteRequest` (units, operation, rows as the
   value Python's `json.loads` would produce, context) instead of discarding what it parsed, and
   refuses, as `400 REQUEST_MALFORMED`, a row of the wrong kind for its position (FR-027), a lane
   alias or non-canonical lane spelling (FR-023), a non-finite number and a session-time pair core's
   sanitizer cannot subtract (FR-028). A port of Python's `datetime.fromisoformat` lands in
   domain-model so the edge and the encoder (WP05) share one parser.

The existing edge and operations tests stay green (FR-019). The controller keeps calling the
fail-closed `WriteGate` until WP08; nothing is written yet.

## Context

- Spec: FR-019, FR-023 (alias → 400), FR-024 (the field; the claim rules themselves are WP07),
  FR-025 (fixed detail texts, no echo), FR-026 step 1 (shape includes row kinds), FR-027, FR-028
  (input refusals), FR-012 (codes and URNs in code), C-002, C-008.
- Plan: "Request flow (FR-026)" step 1; "Coordination points" (`RefusalCode` and `RefusalBody` are
  this WP's, WP06 to WP08 consume them).
- Data model: `WriteRequest`, `UnitRequest`, `Row` and their invariants.
- Research: §1.1 (the only alias is `doing → in_progress`; core normalizes `strip().lower()`),
  §1.7 (annotation rows: `kind: annotation`, `wp_id` `^WP\d+$`, non-empty delta), §2 (the 15 URNs
  and their codes; missing `wp_id`/`to_lane`/`actor` is a `TypeError` in core → protocol
  `REQUEST_MALFORMED`), §3.2 (Repo A's `WriteBodies` rules), §4.1 (the mixed naive/aware
  `TypeError`), §8.1 (the `WriteBodies`, `RefusalCode`, `RefusalBody` seams), §11.2, §11.9.
- Existing seams:
  - `service/command-cli/src/main/java/org/speckitty/commandcli/adapter/in/http/operations/WriteBodies.java:22-214`:
    the hand-written shape check (`onlyFields`, `row`, `context`, the batch shared-location rule
    at `:75-83`, `MAX_BATCH_UNITS` at `:31`, `WP_ID` at `:28`).
  - `.../operations/WriteController.java:52-63`: the controller calls
    `bodies.missionId(...).or(shape)` and then `gate.persist()`. Do **not** change it (WP08 owns
    it); keep the three `Optional<Malformed>` methods as delegates of the new typed parse.
  - `.../domain/service/RefusalCode.java:7-30` (seven values with status and title);
    `.../adapter/in/http/edge/RefusalBody.java:21-63` (ordered map, no reflection, alphabetical
    member order `code, detail, instance, status, title`).
  - `.../test/.../domain/service/DomainValuesTest.java:97` (each code carries its contract
    status) and `.../operations/OperationsTest.java:205` (codes used are contract codes); both
    iterate `RefusalCode.values()`.
  - `service/domain-model/src/main/java/org/speckitty/missionstatus/domain/model/PyText.java`
    (the precedent: Python `str` behaviours ported once); `Lane.java:12-63` (11 wire values,
    `fromWire` refuses unknowns, no alias).
  - `contracts/mission-write/schemas/TransitionUnit.yaml:10-36`, `WriteRefusalCode.yaml`,
    `WriteRefusalReason.yaml`, `schemas/_index.yaml`, `contracts/tools/enum_pins.json:24-27`,
    `contracts/mission-write/CHANGELOG.md` (`## 1.0.1-SNAPSHOT` with `Added`/`Changed`/`Removed`).

## Branch Strategy

Planning happens on `kitty/mission-events-write-core-01M6TP6W` in an owned checkout
(`single_branch` topology). The mission lands as one PR to `main` from `issue-160-write-core`;
the planning base and merge target recorded here are `main`.
`spec-kitty agent action implement WP03 --agent <name> --mission events-write-core-01M6TP6W`

## Ground rules

- ATDD, red first: write each subtask's acceptance tests before the code and see them fail for
  the right reason.
- Java 25. Build and test with
  `jh=$(scripts/ensure_jdk25.sh); JAVA_HOME=$jh ./gradlew --no-daemon -Dorg.gradle.java.installations.paths=$jh :command-cli:check`
  and the same prefix for `:domain-model:check` and `:contracts:check`.
- Cyclomatic complexity ≤ 15 per method (NFR-004): `WriteBodies` is already near the limit in
  places; extract a `RowRules` helper (package-private, same package) rather than growing methods.
- No new dependency (NFR-005). No dependency on core (C-001); core paths are cited for parity only.
- The edge order (guard, token, protocol, size, draining, shape) does not move (FR-019).
- No refusal detail echoes a value, a path or a token (FR-025): every detail is a fixed text.
- Declared out-of-map edits (the files belong to WP08; record each in the PR):
  1. `service/command-cli/build.gradle.kts`, **the shared `dependencies { }` block**: add
     `implementation(project(":domain-model"))` and nothing else. WP08 later edits the same block
     (event-log, SnakeYAML, test fixtures); this is the one declared shared edit between WP03 and
     WP08 (ruling R11).
  2. `service/command-cli/src/test/java/org/speckitty/commandcli/adapter/in/http/operations/OperationsTest.java`:
     the base `row` of `malformedWrites()` (`:118`) becomes a **valid transition row** (`event_id`,
     `wp_id`, `from_lane`, `to_lane`, `actor`, and the other fields of
     `examples/TransitionUnit.claim.yaml`), so every malformed case still fails for its own reason;
     the minimal-row bodies of `aRowWhoseMissionIdIsNullCountsAsNamingNoMission` (`:177`) and
     `aCoordinationRefCommitIsWellFormedAndReachesTheGate` (`:188`) gain the same fields so they
     still reach the gate. This is FR-019's allowed exception (tests that asserted the old
     shape-only contract); say so in the PR.
  3. `service/command-cli/src/test/java/org/speckitty/commandcli/nativesmoke/NativeSmokeTest.java`:
     only the write body's bare row (`:51-60`) becomes a valid transition row, so the native legs
     stay green between WP03 and WP08 (it still expects the fail-closed 500 until WP08 rewrites it).
- Every example under `contracts/mission-write/examples/` that carries rows must pass the new row
  rules (T019); a test iterates them all (ruling R11).
- Native test floor: `DomainValuesTest` is `@Tag("native")` and counts toward the command-cli native
  floor (≥ 120, `.github/workflows/service.yml:432`); do not move tests out of native-tagged classes.
- Python (`scripts/conformance/generate_fromisoformat_vectors.py`) is linted by
  `uv run ruff check . && uv run ruff format --check .` and complexity-gated by
  `uv run ruff check --select C901 --config 'lint.mccabe.max-complexity = 15' scripts/conformance/generate_fromisoformat_vectors.py`
  (ruling R6). Java complexity ≤ 15 is enforced by WP08's command-cli JaCoCo METHOD/COMPLEXITY rule
  and by the domain-model `jacocoTestCoverageVerification` you run.
- Do not run `make test-full`.
- Record the test commands you ran and their pass counts in your final commit message.

## Subtasks

### T014 Contract: `WriteOperation`, `TransitionUnit.operation`, example, enum pin

**Files**: `contracts/mission-write/schemas/WriteOperation.yaml` (new),
`schemas/TransitionUnit.yaml`, `schemas/_index.yaml`,
`examples/TransitionUnit.start-implementation.yaml` (new), `contracts/tools/enum_pins.json`.

**Steps**:
1. `WriteOperation.yaml`: `type: string`, `enum` (or one `const` per value, following the house
   style of `WriteSurface.yaml`) `transition`, `start_implementation`, `start_review`, each with
   a description from the delta doc (`transition`: core's emit pipeline as `move-task`,
   `mark-status` and `status emit` use it; the starts: core's
   `work_package_lifecycle.start_implementation_status` / `start_review_status`, which carry the
   claim rules), and an `x-derived` annotation (not `x-source`: the enum is minted by this
   contract, derived from core's door names, so no single core symbol is its source; ruling R11).
2. `TransitionUnit.yaml`: add optional `operation: {$ref: WriteOperation.yaml}` with a
   description ("absent means `transition`"). It stays `additionalProperties: false`; `required`
   unchanged. `TransitionBatch` inherits it through its unit items.
3. `_index.yaml`: list `WriteOperation.yaml`.
4. Example `TransitionUnit.start-implementation.yaml`: a claimed WP resumed by
   `start_implementation`: one transition row (`claimed → in_progress`, as core's lifecycle
   writes it), `operation: start_implementation`, a `checkout_append` context. Link it from the
   schema's `examples`. A unit holds **one** transition row (FR-027): core's start from `planned`
   emits two transitions, which travel as a batch of two units, each with
   `operation: start_implementation` (WP04 encodes that mapping in the corpus; say so in the
   operation's description).
5. `enum_pins.json` → `"mission-write"`: add `"WriteOperation": ["transition",
   "start_implementation", "start_review"]`.

### T015 Contract: description widenings and CHANGELOG

**Files**: `schemas/WriteRefusalCode.yaml`, `schemas/WriteRefusalReason.yaml`,
`contracts/mission-write/CHANGELOG.md`.

**Steps** (descriptions only; no value added or removed):
1. `STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED`: also a torn tail (refused, not repaired), a
   log the write fold cannot reduce, and a failed write or sync after which the append was cut
   back.
2. `WRITE_LOCATION_UNVERIFIED`: also a `coordination_ref_commit` unit before migration step 4,
   ahead of any Mission lookup; on `checkout_append`, `checkout` not `.`, `destinationRef` not
   null, or `missionDirectory` not the directory of the Mission named by `missionId`.
3. `TRANSITION_REJECTED`: may carry no reason URN when the unit's `from_lane` is not the work
   package's current lane (a stale unit).
4. `REQUEST_MALFORMED`: also a row of the wrong kind for its position, a lane alias or
   non-canonical lane spelling, a non-finite number, and a session-time pair that cannot be
   subtracted.
5. `SERVICE_BUSY`: the 10 s budget shared by the in-process turn and the Mission lock.
6. `DEPENDENCIES_NOT_SATISFIED` and `WriteRefusalReason.dependencies_unsatisfied`: satisfied
   when `approved`, `done`, or `canceled` with operator provenance. `unknown_lane`: the lane
   vocabulary is the persisted `Lane` set (11 values, not "the nine").
7. CHANGELOG, under `## 1.0.1-SNAPSHOT`: `### Added` gets `WriteOperation`,
   `TransitionUnit.operation`, the example, the enum pin; `### Changed` gets each widened
   description, one bullet each, citing #160/#161/#155. Say the field is additive and a client
   that omits it is unaffected. `info.version` stays `1.0.1-SNAPSHOT`; no tag.
8. Run the contract tools and fix what they flag:
   `uv run python contracts/tools/bundle.py --root contracts --out "$(mktemp -d)" --bundle-only`,
   `uv run python contracts/tools/enum_pin_check.py`, `uv run python contracts/tools/example_check.py`,
   `uv run python contracts/tools/provisional_check.py`, `uv run python contracts/tools/breaking_check.py`,
   `uv run python contracts/tools/leak_scan.py` (read each tool's `--help` for its arguments),
   then `uv run pytest tests -k "contract or mission_write" -q` and the `:contracts:check` task.

### T016 `RefusalCode`, `RefusalReason`, `RefusalBody.type`

**Files**: `domain/service/RefusalCode.java`, `domain/service/RefusalReason.java` (new),
`adapter/in/http/edge/RefusalBody.java`; tests `DomainValuesTest.java`, `RefusalBodyTest.java`,
`RefusalVocabularyTest.java` (new).

**Steps**:
1. Tests first: every `RefusalCode` is a `WriteRefusalCode` value with the status
   `WriteRefusal.yaml` binds it. The **contract-reading** check goes in the new, **untagged**
   `RefusalVocabularyTest` (it reads files under `commandCli.contractRoot`, which the native test
   binary cannot; `DomainValuesTest` is `@Tag("native")`, so it keeps only in-memory checks and
   loses none of its tests); every `RefusalReason` URN is a `WriteRefusalReason` value, all 15 present, and each
   binds the code research §2 gives (`dependencies_unsatisfied → DEPENDENCIES_NOT_SATISFIED`,
   `claim_held_by_other_actor → WP_ALREADY_CLAIMED`, the other 13 → `TRANSITION_REJECTED`).
2. `RefusalCode` adds: `TRANSITION_REJECTED` 409, `WP_ALREADY_CLAIMED` 409,
   `DEPENDENCIES_NOT_SATISFIED` 409, `WP_NOT_FOUND` 404, `MISSION_NOT_FOUND` 404,
   `STATUS_LOCK_HELD` 409, `BOOKKEEPING_DOUBLE_EVENT_ID` 409, `EVENT_ID_CONFLICT` 409,
   `WRITE_LOCATION_UNVERIFIED` 422, `SERVICE_BUSY` 503. Titles in the existing sentence-case
   style (`"Transition rejected"`). Update the class Javadoc ("the codes this service answers").
3. `RefusalReason` (domain, pure): the 15 URNs (`urn:spec-kitty:write-refusal:<suffix>`) with
   `urn()` and `code()`.
4. `RefusalBody`: add a nullable `type` component and `withType(String urn)`; `members()` adds
   `"type"` only when set, keeping the alphabetical order (`code, detail, instance, status,
   title, type`). Keep the ordered-map, no-reflection serialisation (`RefusalBody.java:17-19`:
   the native image cannot read record components). `of(...)` factories unchanged.
5. `RefusalBodyTest`: bytes with and without `type`; a body with `type` validates against
   `WriteRefusal.yaml` (reuse the schema helper the operations tests use,
   `ContractSchemas.java`, read-only).

### T017 `PyIsoDateTime` in domain-model

**Purpose**: one Java port of `datetime.fromisoformat` **as core's pinned interpreter runs it**
(Python 3.12; confirm against core's `.python-version` at `service/core-pin.json`), used by the
edge (T019) and by WP05's sanitizer.

**Files**: `service/domain-model/src/main/java/org/speckitty/missionstatus/domain/model/PyIsoDateTime.java`
(new), its test, `service/domain-model/src/test/resources/pyisodatetime/vectors.json` (new),
`scripts/conformance/generate_fromisoformat_vectors.py` (new, stdlib only).

**Steps**:
1. Vectors first: the generator script (refuses to run unless `sys.version_info[:2]` is the
   pinned version; run it with `uv run --python 3.12 python scripts/conformance/generate_fromisoformat_vectors.py`)
   writes, for ~60 inputs, `{"text", "parses", "aware", "utc_epoch_micros" | "naive_micros"}`.
   Cover: extended and basic forms, `T` and space and other single-character separators, `Z`,
   `+05:30`, `-00:00`, offsets with seconds and microseconds, fractions of 1 to 9 digits (and the
   `,` decimal mark), hour-only and hour-minute times, date-only, ISO week dates
   (`2026-W01-1`), invalid months/days/hours (`24:00`, `23:60`), trailing garbage, empty
   string, non-ASCII digits. **At least one vector per rejection branch** of the port (every place
   `_pydatetime` raises `ValueError`: bad separator, bad date length, bad week, bad hour, minute,
   second, fraction, offset, trailing text), each named in the vector's `branch` field so the Java
   test can assert every branch is exercised (ruling R11). Add a `--check` mode that diffs.
   Fixtures in WP01 use only the shared 3.10-compatible subset; the full grammar lives here
   (ruling R12).
2. Port CPython 3.12's pure-Python reference (`Lib/_pydatetime.py`:
   `_find_isoformat_datetime_separator`, `_parse_isoformat_date`, `_parse_isoformat_time`,
   `_parse_hh_mm_ss_ff`, `_parse_isoformat_timezone`) rather than writing a regex; it is the
   behaviour CPython's tests hold the C implementation to.
3. API: `static Optional<PyIsoDateTime> parse(String text)`; `boolean aware()`;
   `static long truncatedSecondsBetween(PyIsoDateTime start, PyIsoDateTime end)` =
   `int((end - start).total_seconds())` (truncation toward zero, microsecond resolution;
   throws `IllegalArgumentException` when exactly one side is aware, the Python `TypeError`).
4. Respect `DomainModelBoundaryTest` (java.time is allowed; no java.util.concurrent, no I/O).
   Complexity ≤ 15 per method: one method per grammar piece.
5. Build (`service/domain-model/build.gradle.kts`, **you own it**, ruling R11): add
   `testImplementation(libs.jackson.databind.bootmanaged)` as event-log's build does, so the test
   reads `vectors.json`; nothing in `main` changes. The module's `jacocoTestCoverageVerification`
   and `jacocoScopeCheck` (the root convention's tasks) must pass with the new class.

**Validation**: [ ] the test runs every vector and asserts every `branch` value occurs; [ ]
`:domain-model:check`, `:domain-model:jacocoTestCoverageVerification` and `jacocoScopeCheck` green.

### T018 Typed `WriteRequest`

**Files**: `application/write/WriteRequest.java` (new);
`adapter/in/http/operations/WriteBodies.java`; `WriteBodiesTest.java` (new).

**Steps**:
1. `WriteRequest` (application layer: no Jackson, no Spring; `ArchitectureRulesTest:67-77`):
   `record WriteRequest(String missionId, Kind kind, List<UnitRequest> units, Context context)`
   with nested `enum Kind {UNIT, BATCH, ANNOTATION}`, `record UnitRequest(String wpId, Operation
   operation, List<Row> rows)`, `enum Operation {TRANSITION, START_IMPLEMENTATION, START_REVIEW}`
   (with its wire value), `record Row(String eventId, Object value)` where `value` is an
   insertion-ordered `Map<String,Object>` tree of `String`, `BigInteger`, `Double`, `Boolean`,
   `null`, `List`, `Map` (the `PythonJson` value shape, research §4.3), and `record Context(String
   actor, String checkout, String missionDirectory, String writeSurface, String destinationRef)`.
   An annotation request is one unit with one annotation row. Defensive copies, unmodifiable.
2. `WriteBodies`: add `Parsed parseUnit(String missionId, byte[] body)`,
   `parseBatch(...)`, `parseAnnotation(...)` returning either a `WriteRequest` or a `Malformed`
   (a small sealed result type). The three existing `Optional<Malformed>` methods become
   one-line delegates, so `WriteController` and `OperationsTest` keep compiling (WP08 switches
   the controller and may delete the delegates).
3. `operation`: absent → `TRANSITION`; present → one of the three wire values, else
   `REQUEST_MALFORMED` ("The unit's operation is not a WriteOperation value."). Accept the new
   member in `UNIT_FIELDS` (it is optional). An annotation has no `operation`.
4. Value conversion from Jackson's tree: objects → `LinkedHashMap` in document order; integers of
   any size → `BigInteger`; floating numbers → `Double` (exactly what `json.loads` produces:
   `1` and `1.0` differ, `-0.0` stays negative); strings as decoded (a lone surrogate stays a
   lone surrogate). Duplicate keys are already refused (`STRICT_DUPLICATE_DETECTION`, `:44`).
5. Tests first: a parsed claim example yields the expected units, rows and context; `1`, `1.0`,
   `-0.0`, `2**70` and an astral string survive into the value tree with the right Java types.

### T019 Row rules at the edge

**Files**: `WriteBodies.java` (plus a package-private `RowRules` helper if needed);
`WriteBodiesTest.java`.

**Rules** (each a fixed detail text; a batch names `#/units/<i>`):
1. **Kinds (FR-027)**: a unit's row 0 is a transition row: it has **no** `kind` member, it has
   `from_lane` and `to_lane` as strings and an `actor` that is a non-empty string or an object
   (a missing one is core's `TypeError`, research §2). Rows 1..n of a unit, and the standalone
   annotation's row, have `kind: "annotation"`, a `wp_id` matching `^WP\d+$` (it already must
   equal the request's `wpId`) and a `delta` object with at least one member whose value is
   neither `null` nor `""` (core refuses an all-empty delta, research §1.7). Anything else →
   `REQUEST_MALFORMED`, so no transition can bypass the authority.
2. **Lanes (FR-023)**: a lane value that is exactly one of the 11 `Lane` wire values passes. A
   value whose Python `strip().lower()` (use `PyText.strip` and `toLowerCase(Locale.ROOT)`) is a
   `Lane` wire value or the alias `doing`, but which is not itself canonical, is
   `REQUEST_MALFORMED` (core would normalize it before building the row; rows are persisted as
   sent). Any other string passes to the authority, which answers `409 TRANSITION_REJECTED` with
   `unknown_lane` (WP06), as core does. Document this split in the Javadoc.
3. **Non-finite (FR-028)**: any `Double` that is NaN or infinite anywhere in a row (Jackson reads
   `1e400` as infinity; a `NaN` token is already not JSON) → `REQUEST_MALFORMED`.
4. **Session pair (FR-028)**: when a row's top-level `session_started_at` and `session_ended_at`
   are both strings that `PyIsoDateTime` parses and exactly one is aware →
   `REQUEST_MALFORMED`. Any other combination passes (core then drops or computes, WP05). Keep
   the rule identical to WP01's `SessionTimesNotSubtractable` cases; the `status_append` invalid
   fixtures pin it.
5. Tests first, one per rule and branch, including: `kind: null` on row 0; an annotation with
   `delta: {}` and with `delta: {"note": ""}`; `from_lane: "doing"`, `"Planned"`, `" planned"`,
   `"in-review"` (passes: unknown, not an alias); `1e400` nested in a list; a naive/aware pair
   and an aware/aware pair; a batch where unit 3 breaks a rule (`instance: #/units/3`).
6. A test iterates every file under `contracts/mission-write/examples/` that carries `rows` or
   `row` and asserts the new rules accept it (ruling R11).
7. Run the existing edge and operations tests unmodified, except the declared `OperationsTest`
   rows (ground rules): `./gradlew :command-cli:test --tests '*edge*' --tests '*OperationsTest'`.

## Test strategy

```bash
jh=$(scripts/ensure_jdk25.sh); JAVA_HOME=$jh ./gradlew --no-daemon -Dorg.gradle.java.installations.paths=$jh :domain-model:check :domain-model:jacocoTestCoverageVerification
jh=$(scripts/ensure_jdk25.sh); JAVA_HOME=$jh ./gradlew --no-daemon -Dorg.gradle.java.installations.paths=$jh jacocoScopeCheck
jh=$(scripts/ensure_jdk25.sh); JAVA_HOME=$jh ./gradlew --no-daemon -Dorg.gradle.java.installations.paths=$jh :command-cli:check
jh=$(scripts/ensure_jdk25.sh); JAVA_HOME=$jh ./gradlew --no-daemon -Dorg.gradle.java.installations.paths=$jh :contracts:check
uv run pytest tests -k "contract or mission_write" -q
uv run python scripts/conformance/generate_fromisoformat_vectors.py --check   # under Python 3.12
uv run ruff check . && uv run ruff format --check .
uv run ruff check --select C901 --config 'lint.mccabe.max-complexity = 15' scripts/conformance/generate_fromisoformat_vectors.py
```

Blast radius: `:event-log:check` and `:mission-status-api:test` compile against domain-model;
run them once.

## Definition of Done

- [ ] The contract delta is in, the contract tools and `:contracts:check` are green, the CHANGELOG
      records every addition and widening.
- [ ] `RefusalCode` has every code the write path answers with its status; `RefusalReason` has the
      15 URNs with their codes; `RefusalBody` carries `type` when set.
- [ ] `PyIsoDateTime` passes every committed vector, with a vector per rejection branch; the vectors
      regenerate unchanged under 3.12; domain-model's JaCoCo verification and scope check pass.
- [ ] Every contract example with rows passes the new rules; the contract-reading vocabulary check
      is in the untagged `RefusalVocabularyTest`; the native floor (≥ 120) is untouched.
- [ ] `WriteBodies` returns a typed `WriteRequest` and enforces the four row rules with fixed
      details; the existing edge and operations tests are green.
- [ ] The three declared out-of-map edits (command-cli build dependency block, `OperationsTest`
      rows, `NativeSmokeTest` row) are recorded in the PR.
- [ ] The final commit message lists the commands and counts.

## Risks

- **Over-refusing at the edge**: unknown lane strings must reach the authority (C-003: core
  answers `unknown_lane`); only spellings core would normalize are 400.
- **Contract tools**: a new schema not listed in `_index.yaml`, or an example not linked, fails
  the bundle or example check.
- **Native serialisation**: adding `type` through bean introspection would break the native
  image (`RefusalBody.java:17-19`).

## Reviewer guidance

- Read the CHANGELOG against the delta doc line by line; nothing beyond it.
- Check that every new detail text is fixed and echoes nothing.
- Check the value conversion keeps `1` versus `1.0` and `-0.0`.
- Check `PyIsoDateTime` is a port of `_pydatetime`, with vectors from the pinned interpreter, not a
  hand regex.
