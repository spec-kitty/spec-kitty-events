# Feature Specification: Write-fold and reference-encoder parity (M2)

**Feature Branch**: `issue-237-write-fold-encoder-parity`
**Created**: 2026-11-11
**Status**: Draft
**Input**: Issue #237 (Mission M2: write-fold and reference-encoder parity). It closes #237 and the rest of #231: items 4.1–4.6, 4.8–4.11, 4.13, 4.16 and 4.17. Mission M1 (#236, merged as #247 and folded by #248) already landed 4.7, 4.12, 4.14, 4.15 and the `REQUEST_MALFORMED` description comment, so this mission does not redo them. Inputs are the #231 squad findings and comments, the maintainer ruling D4 (2026-11-09), the operator rulings of this mission (below) and the record of mission `events-write-core-01M6TP6W` (research §11, rulings R15–R31).

## Intent Summary

- **Primary actors**: core's CLI (spec-kitty/spec-kitty) and its operators, who from core #6195 on route every status write through the events write service; consumers of the Python package's reference encoder (`status_log`) and of `validate_transition`; maintainers who rely on the parity corpus and the architecture rules to catch drift.
- **Trigger**: core #6195 deletes core's own status writer. From then on, any place where the write service's fold judges a log differently from core's reducer becomes a user-visible lane disagreement, and the reference encoder becomes the only byte-level model consumers can check against.
- **Desired outcome**: the write fold judges a transition with no `event_id` exactly as core does; the Python reference encoder is interpreter-independent and refuses every unencodable input with a typed refusal; `validate_transition` admits only core's edges; and the assurance gaps the squad listed (appended bytes never asserted, an unpinned core interpreter, no process-creation rule, a stale pin description, an untestable clock seam) are closed.
- **Invariant**: every divergence from core is judged by why core behaves so. A deliberate guard is matched; an accident is not copied and is recorded as a ruling with a core issue.
- **Ordering**: the null `event_id` fix (#231 4.16) must be able to land before core #6195; it is the first work package and is independently mergeable.

## Rulings

| ID | Ruling | Decision |
|---|---|---|
| **D4** (maintainer, 2026-11-09) | Align `validate_transition` with core's lane machine and take a minor version bump. Scope here: the unforced edge graph. Force semantics (force bypasses the edge check, as in core) and the guard checks stay as they are. | `DM-01M70TJHAYRAZ7293C10BWXQM4` (recorded in M1) |
| **M2-R1** (operator, 2026-11-11) | A transition without an `event_id` is a deliberate, distinct case, not a null or a `"None"` sentinel. It is modelled through its own constructor or type, and every place that needs an id asks the model, not a string comparison. Its semantics mirror core: core's `_find_event_by_id` returns no setter for a `None` id on purpose, so an id-less setter is no setter. | `DM-01M74ZYQTJR0SBKAC3A3208WSW` |
| **M2-R2** (operator, 2026-11-11) | The expected appended bytes of the ack cases are extracted from core and stored as test resources in this repository. There is no build-time, run-time or test-time hard coupling to core: the tests read only committed resources. The extraction tool may consult core only through the existing CI-only, SHA-pinned test peer (charter: Package Boundary), the same way the other oracles do. | `DM-01M74ZYSR06CFAAN7FAK274HX6` |
| **M2-R3** (operator, 2026-11-11) | Ruling D4's minor bump is taken in this PR: the package version goes from 11.4.0 to 11.5.0, coordinated with the sibling sessions. | `DM-01M74ZYVN3GG64XGV19JKW10X9` |

## User Scenarios & Testing *(mandatory)*

### User Story 1 - The write service and core agree on a log with an id-less transition (Priority: P1)

A Mission's status log holds a transition row whose `event_id` is `null` (only a hand-written or damaged log can hold one; core always writes ULIDs). Core's reducer finds no current-lane setter for such a row. Today the write service treats it as the setter, so on such a log the two disagree on the lane: the service acknowledges a `claimed → in_progress` that core drops, and refuses one core would accept.

**Why this priority**: after core #6195 the service is the only writer, and a fold disagreement becomes a wrong acknowledgement or a wrong refusal. It must land before #6195.

**Independent Test**: fold differential cases where the expectation is the package reducer's verdict (the reducer core itself uses), in both directions.

**Acceptance Scenarios**:

1. **Given** a log whose current-lane setter for a work package has a null `event_id`, **When** a non-forced transition whose `from_lane` does not continue the lane arrives, **Then** the write fold applies it, as core does (no setter, so no continuity check).
2. **Given** the same shape where the old fold would apply an event that core drops (or refuse one core applies), **When** the service folds the log, **Then** its verdict and resulting lane equal the package reducer's.
3. **Given** a log with a non-finite transition `event_id` (`NaN`, `Infinity`, `1e400`), **When** a write is attempted, **Then** the service answers a coded `500` and writes nothing.

---

### User Story 2 - A consumer encodes status rows with the reference encoder on any interpreter (Priority: P2)

A consumer (or the service's own conformance tests) calls `encode_status_row` on a parsed row. The bytes must be the same on Python 3.10, 3.11 and 3.13, every refusal must be a `StatusRowRefused` subclass that names what was wrong, and the input must never be mutated.

**Why this priority**: the encoder is the published byte model of core's writer; once core stops writing, it is the reference.

**Independent Test**: run the encoder tests and the packaged conformance gate under 3.10, 3.11 and 3.13 and compare the bytes.

**Acceptance Scenarios**:

1. **Given** a row whose session timestamps include `+00:00:00.000001`, **When** it is encoded on 3.10, 3.11 and 3.13, **Then** the three outputs are byte-identical and equal core's 3.11 output.
2. **Given** a row with PII inside a tuple, **When** it is sanitised, **Then** the PII key is removed, as core's sanitiser does.
3. **Given** a row with an integer beyond the interpreter's int-to-str digit limit, or a non-string key, **When** it is encoded, **Then** a specific `StatusRowRefused` subclass is raised, never `NonFiniteNumber` and never a bare `TypeError`.

---

### User Story 3 - `validate_transition` admits only core's edges (Priority: P2)

A caller asks `validate_transition` whether an unforced move is permitted. Today it admits edges that core and the write service refuse (`for_review → approved|done|in_progress|planned`, `in_review → for_review`), and the docs present its matrix as the permitted moves.

**Independent Test**: an exhaustive (from, to) matrix test against core's edge graph at the pin.

**Acceptance Scenarios**:

1. **Given** each unforced (from, to) pair over all lanes, **When** it is validated, **Then** the edge part of the verdict equals core's `may_transition_to` at the pinned core SHA.
2. **Given** a forced move, **When** it is validated, **Then** the result is unchanged from today.

---

### User Story 4 - Maintainers trust the assurance net (Priority: P3)

Maintainers rely on the parity corpus, the workflow tests and the architecture rules to catch drift. Today ack cases assert counts but never the bytes written, the domain ack cases never go over HTTP, the core venv's interpreter pin is not asserted, nothing confines process creation in command-cli, and the pin's purpose text is stale. The write budget's deadline and the Mission lock's wait also read two different clocks, so a fake-clock test gets the wrong lock wait.

**Acceptance Scenarios**:

1. **Given** every ack case in the parity corpus (domain and service layers), **When** it runs over HTTP, **Then** the bytes appended to the log equal the committed core-extracted expectation.
2. **Given** a planted class outside `adapter.out.mission` that creates a process, **When** the architecture rule runs on it, **Then** it fails.
3. **Given** a fake clock, **When** a write waits for the Mission lock, **Then** the wait is the deadline minus the fake clock's now.

### Edge Cases

- A string `event_id` spelled `"None"` next to a null one: the id-less model must not make the two equal (today both spell `"None"`).
- An id-less row that is also a seed, a rollback, or the only row for its work package.
- An annotation row with no `event_id` (the annotation fold already resets the setter; its behaviour must not change).
- A numeric `event_id` equal to another by Python equality (`1 == 1.0 == True`): dedupe and setter lookup keep today's behaviour.
- A tuple nested inside a list inside a tuple; an empty tuple.
- A non-string key nested deep inside a value, and a top-level non-string key.
- `validate_transition` from `genesis`, `blocked` and the terminal lanes; `blocked → blocked`.
- An ack case whose unit replays (zero appended bytes) and a batch with several units.

## Domain Language

| Term | Meaning | Avoid |
|---|---|---|
| **setter** | The transition that put a work package in its current lane; the fold's continuity and same-timestamp rules compare a new transition with it. | "last event" |
| **id-less transition** | A transition row whose `event_id` is absent or `null`. A distinct case, never a sentinel string. | "None id" |
| **reference encoder** | The package's `status_log` module: core's sanitiser and serialiser, ported. | "the codec" (that is the Java side) |
| **ack case** | A parity-corpus case whose expected result is an acknowledgement. | — |
| **edge graph** | Which unforced (from, to) lane moves exist, before any guard. | "matrix" alone |

## Requirements *(mandatory)*

### Functional Requirements

| ID | Requirement | Status | Delivery | No-op passable? |
|---|---|---|---|---|
| FR-001 | The write fold MUST model an id-less transition explicitly (M2-R1): a transition is either identified or id-less, created through distinct constructors or factories, and the setter lookup, dedupe and ordering ask that model instead of comparing a spelled string (#231 4.16). | Proposed | [build] | no |
| FR-002 | An id-less current-lane setter MUST count as no setter in the fold's arbitration, exactly as core's `_find_event_by_id` does, so neither the same-timestamp rule nor the continuity rule fires against it. | Proposed | [build] | no |
| FR-003 | Differential fold cases MUST show FR-002 in both directions: one where the old fold acknowledged what core drops, one where it refused what core accepts. Each case's expected lane and verdict MUST be the package reducer's (`spec_kitty_events.diary`, which core uses), committed as test data. | Proposed | [build] | no |
| FR-004 | A test MUST pin the fail-closed handling of a non-finite log transition `event_id` (`NaN`, `Infinity`, `1e400`): a coded `500` and nothing written (#231 4.17). | Proposed | [ratchet] | yes — positive control: the same log with a finite id is acknowledged |
| FR-005 | The Mission lock's wait MUST be computed from the same injected clock as the write budget's deadline, on the write and the repair paths. Production wiring passes the same clock it does today (#231 4.8). | Proposed | [build] | no |
| FR-006 | The reference encoder MUST recurse into tuples when stripping PII, as core's sanitiser does (#231 4.1). | Proposed | [build] | no |
| FR-007 | The reference encoder MUST raise a dedicated `StatusRowRefused` subclass for an integer it cannot encode (the int-to-str digit limit) and another for a non-string key, at any depth. `NonFiniteNumber` MUST be raised only for a non-finite float, and no bare `TypeError` or `ValueError` may escape (#231 4.2). The new subclasses are exported. | Proposed | [build] | no |
| FR-008 | The package docstring's module inventory MUST list `status_log` (#231 4.3). | Proposed | [build] | no |
| FR-009 | Session timestamps MUST be parsed with the CPython 3.11 grammar port on every interpreter, so the encoder's bytes do not depend on the interpreter version, including the `+00:00:00.000001` offset and the fuzzed forms that differ today on 3.11 and 3.13 (#231 comment, item 4.4). | Proposed | [build] | no |
| FR-010 | The encoder tests MUST deep-copy-check the input for no mutation on the session-pair tests and inside the hypothesis property, not only on the PII path (#231 4.5). | Proposed | [build] | no |
| FR-011 | `validate_transition`'s unforced edge graph MUST equal core's lane machine at the pinned core SHA. It drops `for_review → approved`, `for_review → done`, `for_review → in_progress`, `for_review → planned` and `in_review → for_review`, and any other difference the exhaustive matrix test finds (D4, #231 4.6). Forced moves and guard checks are unchanged. | Proposed | [build] | no |
| FR-012 | `docs/events/work-and-status.md` MUST describe `validate_transition`'s edges as core's machine and state that the write service, not this helper, is the write authority. | Proposed | [build] | no |
| FR-013 | The package version MUST go to 11.5.0 in `pyproject.toml` and `__version__` (M2-R3), with a changelog fragment that names the narrowed edges as a consumer-visible change. | Proposed | [build] | no |
| FR-014 | Every ack case of the parity corpus, of every layer, MUST run over HTTP and assert the bytes appended to the log against a committed expectation extracted from core (M2-R2, #231 4.9). The expectation lives in this repository's test resources; the extraction tool is re-runnable and has a drift-check mode, run by the existing CI-only core peer. | Proposed | [build] | no |
| FR-015 | `test_write_core_parity_checks_oracles_with_core_python` MUST assert the `UV_PYTHON="$(cat .python-version)"` prefix on the core `uv sync` and `sync_packs.py` steps (#231 4.10). | Proposed | [build] | no — positive control: the assertion fails on a copy of the workflow without the prefix |
| FR-016 | A command-cli architecture rule MUST confine process creation (`ProcessBuilder`, `Process`, `Runtime.exec`) to `adapter.out.mission`. A planted fixture that creates a process elsewhere MUST fail the rule (#231 4.11). | Proposed | [build] | no |
| FR-017 | `service/core-pin.json`'s `purpose` MUST name every peer the pin drives: lock interop, the nightly corpus oracle and the write-core-parity oracles (including the ack-bytes extraction). The SHA does not change (#231 4.13). | Proposed | [build] | no |
| FR-018 | Every corpus or count the issue quotes MUST be re-derived from the current corpus (the "83" ack cases of #231 4.9 and the "88 of 253" of #237 against today's 258-case corpus), and the mission record states the counts it used. | Proposed | [build] | no |
| FR-019 | The mission record MUST carry an issue-matrix row for #237 and #231 and a changelog fragment per consumer-visible change. | Proposed | [build] | no |

### Non-Functional Requirements

| ID | Requirement | Threshold | Status |
|---|---|---|---|
| NFR-001 | Interpreter independence of the encoder. | 0 byte differences between Python 3.10, 3.11 and 3.13 over the whole status-append fixture set and the `+00:00:00.000001` case. | Proposed |
| NFR-002 | Ack-byte coverage. | 100% of ack cases (every layer) assert appended bytes over HTTP; 0 mismatches. | Proposed |
| NFR-003 | Fold parity on the id-less cases. | 100% of the differential cases agree with the package reducer on lane and verdict. | Proposed |
| NFR-004 | Code quality on new Java code. | Cyclomatic complexity ≤ 15 per method; existing coverage gates of `./gradlew verifyAll` unchanged and passing. | Proposed |
| NFR-005 | Python quality. | `mypy --strict` and `ruff` clean on every changed Python file. | Proposed |

### Constraints

| ID | Constraint | Status |
|---|---|---|
| C-001 | No build-time, run-time or test-time dependency on core (charter: Services, Package Boundary; M2-R2). Core is consulted only through the CI-only, SHA-pinned peer. | Active |
| C-002 | Do not touch `service/src` (the Mission Status API, owned by the sibling session for #170), its README, its conformance goldens, or `PORTED_EVENTS_VERSION`. Shared files (`pyproject.toml`, `__version__`, `service/core-pin.json`) are coordinated through the orchestrator. | Active |
| C-003 | The divergence principle: each difference from core is judged by why core behaves so, and the judgement is recorded. | Active |
| C-004 | One branch (`issue-237-write-fold-encoder-parity`), one PR to `main`. Never merge; never push `main`. Changelog fragments only. | Active |
| C-005 | The id-less fix (FR-001–FR-003) is its own first work package and must be landable before core #6195. | Active |
| C-006 | No change to the encoded bytes of any row the reference encoder accepts today on 3.11, other than rows FR-006 newly sanitises. | Active |

### Key Entities

- **Transition (write fold)**: one transition row; identified or id-less.
- **Ack-bytes expectation**: per ack case and unit, the exact bytes core appends; committed test data.
- **Edge graph**: the set of unforced (from, to) lane moves.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: On every differential id-less case, the write service and core reach the same lane and verdict (0 disagreements). — [build] · no-op passable: no
- **SC-002**: The reference encoder's output is identical on three supported interpreter versions for the whole fixture set (0 differing bytes). — [build] · no-op passable: no
- **SC-003**: Every unencodable input yields a typed refusal (0 untyped errors across the refusal tests). — [build] · no-op passable: no
- **SC-004**: Every acknowledged corpus case proves its written bytes (100% of ack cases). — [build] · no-op passable: no
- **SC-005**: A planted process-creating class fails the architecture check (1 of 1). — [build] · no-op passable: no
- **SC-006**: `validate_transition` and core agree on 100% of unforced lane pairs. — [build] · no-op passable: no

## Assumptions

- The package reducer (`spec_kitty_events.diary`) is the reducer core uses (core's `status/reducer.py` imports it), so its verdicts are core's verdicts.
- Core's edge graph is read from `wp_state.py` at the pinned SHA `512567ee`; later core changes are a pin bump's concern, not this mission's.
- The reference encoder is core-exact for the corpus's request rows (the committed `status_append` oracle proves it), so expectations it produces equal core's; the drift-check mode in the CI-only peer proves this on every run.
- Running core's code locally is not available to this session; anything that needs core at run time is verified by CI's `write-core-parity` job.

## Out of Scope

- Items 4.7, 4.12, 4.14 and 4.15 of #231 and the `REQUEST_MALFORMED` description (landed by M1).
- The read reducer's approved-departure lift (G-4, spec-kitty/spec-kitty#6268) and the Mission Status API (#170).
- Guard semantics of `validate_transition` beyond its edge graph.
