---
work_package_id: WP05
title: Java status-row encoder and sanitizer
dependencies:
- WP01
- WP03
requirement_refs:
- FR-001
- FR-002
- FR-003
- FR-028
- NFR-004
- SC-001
planning_base_branch: kitty/mission-events-write-core-01M6TP6W
merge_target_branch: main
branch_strategy: Planning artifacts for this mission were generated on kitty/mission-events-write-core-01M6TP6W. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into main unless the human explicitly redirects the landing branch.
subtasks:
- T027
- T028
- T029
- T030
- T031
history:
- timestamp: '2026-11-07T10:30:00Z'
  lane: planned
  agent: system
  action: Prompt generated via /spec-kitty.tasks
agent_profile: java-jenny
authoritative_surface: service/event-log/src/main/java/org/speckitty/missionstatus/adapter/out/filesystem/codec/StatusRowEncoder.java
create_intent:
- service/event-log/src/main/java/org/speckitty/missionstatus/adapter/out/filesystem/codec/StatusRowEncoder.java
- service/event-log/src/main/java/org/speckitty/missionstatus/adapter/out/filesystem/codec/StatusRowSanitizer.java
- service/event-log/src/main/java/org/speckitty/missionstatus/adapter/out/filesystem/codec/PythonFloatRepr.java
- service/event-log/src/main/java/org/speckitty/missionstatus/adapter/out/filesystem/codec/StatusRowRefusedException.java
- service/event-log/src/main/java/org/speckitty/missionstatus/adapter/out/filesystem/codec/StatusRowDecoder.java
- service/event-log/src/test/java/org/speckitty/missionstatus/adapter/out/filesystem/codec/StatusRowEncoderTest.java
- service/event-log/src/test/java/org/speckitty/missionstatus/adapter/out/filesystem/codec/StatusRowSanitizerTest.java
- service/event-log/src/test/java/org/speckitty/missionstatus/adapter/out/filesystem/codec/PythonFloatReprTest.java
- service/event-log/src/test/java/org/speckitty/missionstatus/adapter/out/filesystem/codec/StatusAppendFixtureTest.java
execution_mode: code_change
owned_files:
- service/event-log/src/main/java/org/speckitty/missionstatus/adapter/out/filesystem/codec/StatusRowEncoder.java
- service/event-log/src/main/java/org/speckitty/missionstatus/adapter/out/filesystem/codec/StatusRowSanitizer.java
- service/event-log/src/main/java/org/speckitty/missionstatus/adapter/out/filesystem/codec/PythonFloatRepr.java
- service/event-log/src/main/java/org/speckitty/missionstatus/adapter/out/filesystem/codec/StatusRowRefusedException.java
- service/event-log/src/main/java/org/speckitty/missionstatus/adapter/out/filesystem/codec/StatusRowDecoder.java
- service/event-log/src/test/java/org/speckitty/missionstatus/adapter/out/filesystem/codec/StatusRowEncoderTest.java
- service/event-log/src/test/java/org/speckitty/missionstatus/adapter/out/filesystem/codec/StatusRowSanitizerTest.java
- service/event-log/src/test/java/org/speckitty/missionstatus/adapter/out/filesystem/codec/PythonFloatReprTest.java
- service/event-log/src/test/java/org/speckitty/missionstatus/adapter/out/filesystem/codec/StatusAppendFixtureTest.java
- service/event-log/build.gradle.kts
role: implementer
tags: []
tracker_refs: []
---
# Work Package Prompt: WP05 - Java status-row encoder and sanitizer

## ⚡ Do This First: Load Agent Profile

Load your assigned profile before reading anything else: `/spk-charter-profile-load java-jenny`
(or `spec-kitty agent profile show java-jenny`). Adopt its identity, scope and boundaries.

## Objective

The Java twin of WP01's `spec_kitty_events.status_log`, in event-log's codec package (pure, no file
I/O): `StatusRowSanitizer` and `StatusRowEncoder` turn a row's value, as `PythonJson` produces it
from the wire (insertion-ordered `Map`, `List`, `String`, `BigInteger`, `Double`, `Boolean`,
`null`), into exactly the bytes core writes:
`json.dumps(sanitize_event_for_log(row), sort_keys=True) + "\n"`. A Java runner holds them to every
`status_append` fixture (FR-001 to FR-003, SC-001). WP08's `EventLogRowCodec` calls them for both
the append and the replay comparison.

## Context

- Spec: FR-001, FR-002, FR-003 (both bindings pass every case), FR-028 (non-finite and mixed
  naive/aware are refusals), NFR-004, SC-001; edge cases "Rows that need escaping", "Session
  times", "Identity fields in nested objects".
- Plan: "Placement decisions" (encoder and sanitizer in the codec package, consuming the
  `PythonJson` value tree).
- Research: §4.1 (sanitizer order and branches), §4.2 (every dump rule: separators, code-point key
  order with astral keys after BMP, the escape set, Python float repr and its thresholds), §4.3
  (`PythonJson` is a decoder only; `CanonicalJson` is a different canonical form: compact
  separators and raw UTF-8; borrow only its `pythonFloat` algorithm), §10 risk 4.
- WP01's fixtures (`src/spec_kitty_events/conformance/fixtures/status_append/`): each document
  has `input_json` (raw JSON text), and `expected_line` or `expected_error` (`NonFiniteNumber`,
  `SessionTimesNotSubtractable`). Read its README first.
- WP03's `PyIsoDateTime` (domain-model): `parse`, `aware()`, `truncatedSecondsBetween`. Use it;
  do not write a second `fromisoformat`.
- Existing seams:
  - `service/event-log/src/main/java/org/speckitty/missionstatus/adapter/out/filesystem/codec/PythonJson.java:14-111`
    (`static Object parse(String)` is package-private: the fixture test lives in the same
    package and calls it; `readFloat` yields infinity for `1e400`, and the parser accepts the
    non-finite tokens).
  - `java/src/main/java/ai/speckitty/events/cloudevents/CanonicalJson.java:117-168`
    (`shortestDigits`, `pythonFloat`): copy the algorithm with attribution (event-log cannot
    depend on the Java binding module); its tests in
    `java/src/test/java/ai/speckitty/events/cloudevents/UpcastCorpusTest.java:97-112` are vectors
    to reuse.
  - `service/domain-model/src/main/java/org/speckitty/missionstatus/domain/model/CodePoints.java`
    (code-point string comparison, used by `AtThenEventId`): reuse it for key order.
  - `service/event-log/src/test/java/org/speckitty/missionstatus/architecture/EventLogBoundaryTest.java:69-74`:
    the codec rule (codec, domain-model, `tools.jackson.core`, `java.nio`, the rule 1b JDK). The
    new classes must hold it unchanged (no `java.io`, no `java.nio.file`, no data binding).
  - `service/event-log/build.gradle.kts:35-43`: the `test` task; you own this file.

## Branch Strategy

Planning happens on `kitty/mission-events-write-core-01M6TP6W` in an owned checkout
(`single_branch` topology). The mission lands as one PR to `main` from `issue-160-write-core`;
the planning base and merge target recorded here are `main`.
`spec-kitty agent action implement WP05 --agent <name> --mission events-write-core-01M6TP6W`

## Ground rules

- ATDD, red first: the fixture runner (T030) is written first and runs red against stub classes.
- Java 25. Build and test with
  `jh=$(scripts/ensure_jdk25.sh); JAVA_HOME=$jh ./gradlew --no-daemon -Dorg.gradle.java.installations.paths=$jh :event-log:check`.
- Cyclomatic complexity ≤ 15 per method (NFR-004): one method per value kind; the float repr in its
  own class.
- No new dependency (NFR-005). No dependency on core (C-001).
- Pure: no clock, no I/O, no concurrency; the codec boundary rule must hold.
- Never mutate the input value tree (the sanitizer copies).
- Do not run `make test-full`.
- Record the test commands you ran and their pass counts in your final commit message.

## Subtasks

### T027 `PythonFloatRepr`

**Files**: `PythonFloatRepr.java` (new, package-private or public static utility),
`PythonFloatReprTest.java` (new).

**Steps**:
1. Tests first, the vectors of research §4.2 plus `UpcastCorpusTest:97-112`: `1.0`, `-0.0`,
   `0.1`, `1e-05`, `0.0001`, `1e+16`, `1000000000000000.0`, `123456789012345678.0` →
   `1.2345678901234568e+17`, `5e-324`, `2.2250738585072014e-308`, `1.7976931348623157e+308`,
   `-1.5`, `100.0`.
2. Port `CanonicalJson.pythonFloat` / `shortestDigits` (shortest round-trip digits, positional for
   decimal exponent `-4 <= exp < 16`, scientific outside with a sign and at least two exponent
   digits, `.0` kept for integral values, `-0.0` kept). Cite the source lines in the Javadoc.
3. Non-finite input throws `StatusRowRefusedException` (kind `NON_FINITE`); never writes `NaN`.

### T028 `StatusRowEncoder`

**Files**: `StatusRowEncoder.java` (new), `StatusRowEncoderTest.java` (new).

**API**: `static String encode(Object value)` (the dump, no newline) and
`static byte[] encodeLine(Map<String, Object> row)` = sanitize, dump, append `\n`, ASCII bytes.

**Rules** (tests first, one per rule):
1. Separators `", "` between members and items, `": "` between key and value; `{}` and `[]` for
   empty containers.
2. Keys sorted by Unicode code point at every depth (`CodePoints.compare`), so a key starting
   with U+1F600 sorts after one starting with U+FFFD even though its first UTF-16 unit is
   smaller.
3. Strings: `\"` and `\\`; `\b`, `\f`, `\n`, `\r`, `\t`; every other char below U+0020 and every
   char above U+007E (DEL U+007F included: Python escapes everything outside space..tilde) as
   lowercase `\u00xx`/`\uxxxx`, one escape per UTF-16 unit, so an astral character becomes its
   surrogate pair (`😀`) and a lone surrogate stays one escape; `/` unescaped.
4. `BigInteger` → decimal digits; `Double` → `PythonFloatRepr`; `Boolean` → `true`/`false`;
   `null` → `null`; `List` in order; `Map` with sorted keys. Any other type →
   `IllegalArgumentException` (the decoder never produces one).
5. Output is pure ASCII; `encodeLine` ends with exactly one `\n`, never `\r\n`.
6. Do not reuse `CanonicalJson`'s separators or its raw-UTF-8 string writer (research §4.3).
7. `StatusRowDecoder` (new, public): `static Object parse(String line)` delegating to the
   package-private `PythonJson.parse`, throwing an unchecked exception on text that is not one JSON
   value. WP08's codec adapter needs a public way to decode a log line into the same value tree
   (for the write fold and the replay comparison); do not widen `PythonJson` itself. Test it with
   a valid line, a non-finite token and garbage.

### T029 `StatusRowSanitizer`

**Files**: `StatusRowSanitizer.java`, `StatusRowRefusedException.java` (new),
`StatusRowSanitizerTest.java` (new).

**Steps**:
1. `static Map<String, Object> sanitize(Map<String, Object> row)`: a deep copy with
   `machine_name`, `hostname`, `workspace_path`, `developer_name`, `developer_email` removed from
   every map at every depth, including maps inside lists and lists inside lists; other keys keep
   their order.
2. Session pair, top level only: always remove `session_started_at` and `session_ended_at`; when
   both are strings that `PyIsoDateTime.parse` accepts, set `session_duration_s` to
   `BigInteger.valueOf(PyIsoDateTime.truncatedSecondsBetween(start, end))` (overwriting any
   existing value; its position in the map does not matter because the encoder sorts); otherwise
   leave an existing `session_duration_s` untouched. A non-string value follows what core does
   (WP01's `session_value_non_string` fixture pins it).
3. Exactly one side aware → `StatusRowRefusedException` with kind `SESSION_TIMES_NOT_SUBTRACTABLE`
   (Python's uncaught `TypeError`, research §4.1). `StatusRowRefusedException` is unchecked,
   carries a `Kind` enum (`NON_FINITE`, `SESSION_TIMES_NOT_SUBTRACTABLE`) and no row content in
   its message.
4. Non-finite numbers anywhere: the encoder refuses them (T027); the sanitizer need not, but a
   single `check(Map)` entry point that runs both refusals without encoding is useful to WP08
   (the edge refuses both earlier, WP03; the codec refusal is the second line of defence).
5. Tests first: every branch of research §4.1 bullet 2 and 3, no mutation of the input, nested
   session keys untouched.

### T030 `StatusAppendFixtureTest`: the Java runner

**Files**: `StatusAppendFixtureTest.java` (new), `service/event-log/build.gradle.kts`.

**Steps**:
1. Build: in the `test` task, add
   `systemProperty("eventLog.statusAppendFixtures", rootProject.file("src/spec_kitty_events/conformance/fixtures/status_append").absolutePath)`
   and declare the directory as a task input (`inputs.dir(...)`, path sensitivity relative) so
   a fixture change reruns the test. Keep the existing `module.mainClasses` and native-access
   settings.
2. The runner (JUnit 5 `@TestFactory` or `@ParameterizedTest` over a `Stream` of the files in
   `valid/` and `invalid/`): read each document with the test-classpath Jackson (data binding is
   allowed in tests), parse `input_json` with `PythonJson.parse` (never with Jackson: the raw text
   must reach the production decoder), then:
   - valid: `StatusRowEncoder.encodeLine(row)` equals `expected_line` as ASCII bytes, compared
     byte for byte with a readable diff on failure;
   - invalid: `StatusRowRefusedException` with the kind mapped from `expected_error`
     (`NonFiniteNumber` → `NON_FINITE`, `SessionTimesNotSubtractable` →
     `SESSION_TIMES_NOT_SUBTRACTABLE`).
3. Non-vacuity: assert at least 12 valid and 4 invalid cases were run (NFR-003), and that the
   directory exists (a missing property is a failure, not a skip).

### T031 Unit tests, complexity and coverage

**Steps**:
1. Hand-written edge cases beyond the fixtures: an empty row; a row whose only member is a PII key;
   a 10-deep nesting; a key that is the empty string; a string made only of escapes; big negative
   integers; a list of lists of maps with PII.
2. A cross-check test: for a few dozen generated rows (a seeded `java.util.Random`, no external
   library), `encode` output re-parsed by `PythonJson.parse` yields an equal value tree (round
   trip), which catches escape bugs.
3. Coverage: every new class meets the module's JaCoCo floors; complexity ≤ 15 everywhere.
4. Boundary: `EventLogBoundaryTest` passes unchanged (the new classes live in the codec package and
   use only allowed packages).

## Test strategy

```bash
jh=$(scripts/ensure_jdk25.sh); JAVA_HOME=$jh ./gradlew --no-daemon -Dorg.gradle.java.installations.paths=$jh :event-log:test --tests '*StatusAppendFixtureTest' --tests '*StatusRow*' --tests '*PythonFloatRepr*'
jh=$(scripts/ensure_jdk25.sh); JAVA_HOME=$jh ./gradlew --no-daemon -Dorg.gradle.java.installations.paths=$jh :event-log:check
uv run pytest --pyargs spec_kitty_events.conformance -k status_append -q   # the Python side of the same fixtures
```

Blast radius: `:mission-status-api:test` (it depends on event-log); run it once.

## Definition of Done

- [ ] `StatusRowSanitizer`, `StatusRowEncoder`, `StatusRowDecoder`, `PythonFloatRepr` and
      `StatusRowRefusedException` exist in the codec package and hold the codec boundary rule.
- [ ] `StatusAppendFixtureTest` passes every `status_append` case (100 %, SC-001) and asserts the
      NFR-003 counts.
- [ ] Unit and round-trip tests green; complexity ≤ 15; JaCoCo floors met.
- [ ] `:event-log:check` green; the final commit message lists the commands and counts.

## Risks

- **UTF-16 versus code points** in key order and in escaping; the astral fixtures catch both.
- **DEL (U+007F)**: easy to miss; Python escapes it.
- **Interpreter grammar**: the session pair depends on `PyIsoDateTime` matching core's Python; a
  fixture failure here may be a WP03 bug, report it rather than patching around it.

## Reviewer guidance

- Run the fixture test and look at its case count.
- Read the string escaper against research §4.2 bullet 3.
- Confirm no `CanonicalJson` separator or UTF-8 writer slipped in; only the float algorithm was
  borrowed, with attribution.
