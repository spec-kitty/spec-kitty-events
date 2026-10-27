# Feature Specification: CloudEvents envelope, type registry and legacy-row upcaster

**Feature Branch**: `issue-92-cloudevents-envelope`
**Created**: 2026-10-27
**Status**: Draft
**Input**: spec-kitty-events#92 (Mission Write API step 2 of spec-kitty/spec-kitty#6117), ADR 0001
(`docs/adr/0001-cloudevents-envelope.md`, Accepted 2026-10-27) and the design
`docs/architecture/cloudevents-envelope.md`.

## Intent Summary

Readers of Spec Kitty event logs (the CLI, SaaS ingress, the Mission Status Read API in Java,
the Java write core) need one envelope they can parse from a public spec: CloudEvents 1.0
structured JSON. Logs on disk hold legacy rows (the `3.0.0` transport `Event`, "shape A", and
the status-diary `StatusEvent`, "shape B") that must never be rewritten. This mission ships,
as a **minor release (`11.3.0`)**, the full type registry, the CloudEvents envelope schemas
(read and write profiles), a read-time upcaster that turns legacy rows into CloudEvents, a
legacy fixture corpus with byte-exact golden outputs, a non-normative CloudEvents validator,
and a Java 25 binding that passes the same fixtures as the Python binding. Nothing on a live
path changes: `validate_strict_envelope` keeps requiring `3.0.0`, producers keep emitting it,
and the diary reducer keeps its wall-clock order.

Scope confirmed by the maintainer on 2026-10-27, including the core CLI's lifecycle, decision
and journal envelopes. Decisions recorded before this spec (Decision Moments of this mission; ADR 0001 2026-10-27):
ordering C stays the target and the reducer switch is post-GA; writers switch on the 4.0 GA
critical path once the Read and Write APIs are ready (not in this mission); `3.0.0` is retired
in a later major; every ADR "further choice" is confirmed; release `11.3.0` with the `12.0.0`
cutover separate; two envelope profiles; `dataschema` base `urn:ai.spec-kitty:schema:`; a
Java 25 Maven binding, not published.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Read a legacy diary as CloudEvents (Priority: P1)

A reader (CLI, Read API) opens a `status.events.jsonl` that mixes old diary rows, annotation
rows and rows of other kinds, and asks the shared reader for CloudEvents. Each lane and
annotation row comes back as a CloudEvent; every other row comes back unchanged.

**Why this priority**: the Read API and the write core both depend on reading existing logs
without rewriting them; it is the core of #92's done-when.

**Independent Test**: upcast every row of the status-diary replay corpus and compare with the
committed golden CloudEvents byte for byte.

**Acceptance Scenarios**:

1. **Given** a diary lane row with a string actor, **When** it is upcast with the Mission id
   from context, **Then** the result is the golden CloudEvent of type
   `ai.spec-kitty.lane.transitioned.v1` and validates against the read profile.
2. **Given** an annotation row (`kind: annotation`), **When** it is upcast, **Then** the result
   is of type `ai.spec-kitty.wp.innerstate.changed.v1` with `delta` in `data`.
3. **Given** a row in the diary that no registered form matches (an unknown future kind),
   **When** it is read, **Then** it is returned unchanged and flagged as pass-through.
   Retrospective and Decision Point rows are registered forms and map (FR-005a).
4. **Given** the upcast CloudEvents of a replay fixture, **When** they are decoded back to
   status events and reduced, **Then** the reduced state equals the committed reducer output.

### User Story 2 - Read a 3.0.0 transport envelope as a CloudEvent (Priority: P1)

SaaS ingress, or another transport reader, receives a `3.0.0` `Event` and needs the CloudEvent
form: the aggregate split into `source`/`subject`/`missionid`, the lane-transition payload's
lifted members moved to context attributes, and every other payload moved verbatim.

**Why this priority**: shape A is the envelope every live producer emits today.

**Independent Test**: upcast every shape-A fixture of the corpus (including slug-segment and
unprefixed aggregates) and compare with the goldens.

**Acceptance Scenarios**:

1. **Given** a `WPStatusChanged` envelope with aggregate `mission/<ULID>/wp/WP01`, **When**
   upcast, **Then** `source` is `/spec-kitty/missions/<ULID>`, `subject` is `WP01`, the
   lifted members are attributes, and a `doing` lane becomes `in_progress` in `data`.
2. **Given** an envelope whose aggregate segment is a slug (`mission/replay-mission`), **When**
   upcast, **Then** `source` is `/spec-kitty/projects/<project_uuid>`, `subject` is the whole
   aggregate and there is no `missionid`.
3. **Given** an envelope whose `event_type` is not registered, **When** upcast in strict use,
   **Then** it fails with a named error; in permissive use it is returned unchanged with a
   named warning.

### User Story 2b - Read the core CLI's other legacy rows (Priority: P2)

The Read API also reads Mission lifecycle, Decision Point and run-journal rows the core CLI
writes today (the `lifecycle_envelope`, `decision_envelope` and `journal_envelope` forms the
registry already describes). Each upcasts to its registered CloudEvent.

**Independent Test**: every valid producer fixture's `legacy_row` upcasts to a CloudEvent equal
to its `input` minus the attributes a legacy row cannot carry (the clock pair).

**Acceptance Scenarios**:

1. **Given** a `decision_envelope` row of `DecisionPointOpened`, **When** upcast, **Then** it is
   the `ai.spec-kitty.decision.opened.v1` CloudEvent with `data` = the payload member.
2. **Given** a `lifecycle_envelope` row whose `event_type` is not registered (for example a
   glossary row), **When** read permissively, **Then** it is returned unchanged with a warning.

### User Story 3 - Validate a CloudEvent row (Priority: P2)

A producer (the Java write core, later the CLI) validates a new row before writing it, and a
reader validates an upcast row. The write profile requires the Lamport clock and node id and a
ULID id; the read profile accepts clockless rows and legacy UUID ids.

**Independent Test**: the valid and invalid fixtures of each profile pass and fail as marked.

**Acceptance Scenarios**:

1. **Given** a clockless upcast diary row, **When** validated against the read profile,
   **Then** it is valid; against the write profile, **Then** it is invalid.
2. **Given** a row whose `data` does not match its type's `dataschema`, **When** validated,
   **Then** it fails with a named error naming the type.

### User Story 4 - Same fixtures, Java binding (Priority: P2)

The Java Mission Status Read API and write core use a Java binding that loads the same
schemas, registry and fixtures and produces the same bytes.

**Independent Test**: the Java test suite runs the full fixture corpus (envelope, registry,
upcaster goldens) and passes.

**Acceptance Scenarios**:

1. **Given** the legacy corpus, **When** the Java upcaster runs, **Then** its canonical output
   equals the Python golden bytes for every row.

### Edge Cases

- Legacy UUID ids (36-char hyphenated, 32-char hex) and lower-case ULIDs are normalized with
  the existing rules; two spellings of one id upcast to the same `id`.
- A row whose aggregate segment, payload `mission_id` and caller-context Mission id disagree:
  a named error in strict use; unchanged with a named warning in permissive use. Nothing is
  silently overridden.
- A row already in CloudEvents form is returned unchanged (upcasting is idempotent).
- A diary row with a structured actor: `actor` is the identity projection and the whole object
  goes to `data.actorbinding`.
- A diary row without `mission_id` and no Mission id in context: strict error, permissive
  pass-through with a warning (no `source` is invented).
- A `lamport_clock` above the signed 32-bit range: strict error, permissive pass-through.
- A legacy `feature_slug` member is read as `mission_slug`.
- Rows that are not JSON objects, and pre-3.0 raw rows (shape C): recognised and passed
  through, never mapped.

## Requirements *(mandatory)*

### Functional Requirements

| ID | Requirement | Status | Delivery | No-op passable? |
|---|---|---|---|---|
| FR-001 | The type registry has an entry for every type in `STRICT_EVENT_TYPES` (26) and for the diary lane and annotation rows, each naming its `type`, `dataschema`, data schema file, scope, subject rule and lifted members; the `INTERIM` markers are removed. | Draft | [build] | no — a test fails for each of the 9 types unregistered today |
| FR-002 | Every `dataschema` uses the base `urn:ai.spec-kitty:schema:` followed by the type name (for example `urn:ai.spec-kitty:schema:lane.transitioned.v1`); the existing producer fixtures are migrated to it. | Draft | [build] | no — fixtures carry the placeholder today |
| FR-003 | The package ships two committed CloudEvents envelope schemas: a write profile (today's producer envelope: ULID `id`, `lamportclock` and `nodeid` required) and a read profile (the upcaster's output: `lamportclock`, `nodeid` optional; `id` also accepts the normalized legacy UUID forms; the shape-A extensions `buildid`, `causationid`, `correlationid`, `projectuuid`, `projectslug`, `datatier` declared). | Draft | [build] | no — no read profile exists |
| FR-004 | The upcaster maps a shape-A `3.0.0` `Event` to a CloudEvent following design section 3: constants, renamed extensions, the aggregate split (ULID segment only), lifted members per the registry, `doing` resolved to `in_progress`, and the payload moved verbatim otherwise. | Draft | [build] | no |
| FR-005 | The upcaster maps a shape-B diary lane row and annotation row to a CloudEvent following design section 4, taking the Mission id (and optionally slug) from caller context only when the row lacks it. | Draft | [build] | no |
| FR-005a | The upcaster also maps the core CLI's other legacy row forms the registry describes (`lifecycle_envelope`, `decision_envelope`, `journal_envelope`, and the `flat` forms of other registered types), driven by each entry's legacy form, discriminator and lifted members, so every producer fixture's `legacy_row` upcasts to its CloudEvent. | Draft | [build] | no — the producer fixtures pair each legacy row with its CloudEvent |
| FR-006 | Rows that are not mapped (shape C raw rows, shape D non-lane rows, rows already in CloudEvents form, non-objects) are returned unchanged with an outcome naming why. | Draft | [build] | no — an identity "upcaster" would fail the outcome assertions |
| FR-007 | The upcaster has a strict mode, where an unmappable or conflicting legacy row raises a named error with a stable code, and a permissive mode, where the same row is returned unchanged with a named warning carrying the same code. | Draft | [build] | no |
| FR-008 | The upcaster is deterministic and invents nothing: no minted id or time and no defaulted member beyond the published constants; the input row is never mutated, and the original row (and, when read from bytes, the original line) is kept with the result so a writer can re-emit it unchanged. | Draft | [build] | no — property test over generated rows |
| FR-009 | A canonical serialization of CloudEvents (sorted keys, compact separators, UTF-8, no ASCII escaping) is defined; for every row of the legacy corpus, the serialized upcast result equals the committed golden bytes, and upcasting the parsed golden again yields the same bytes. | Draft | [build] | no |
| FR-010 | Upcast lane CloudEvents decode back to the `StatusEvent` the reducer decodes from the legacy row, so reducing the upcast form of each status-diary replay fixture gives the committed reducer output. | Draft | [build] | no — needs the decode path |
| FR-011 | A CloudEvents validator, beside and independent of `validate_strict_envelope`, checks a row against the read or write profile and its `data` against the registered data schema, failing with named errors. It is non-normative: no existing entry point calls it. | Draft | [build] | no |
| FR-012 | The legacy fixture corpus (every shape-A envelope fixture under `conformance/fixtures/events/valid`, every row of `conformance/fixtures/status_diary/replay/*.jsonl`, the `legacy_row` of every valid producer fixture in all four legacy forms, and new edge-case rows) is packaged with its expected outcome and golden output, and the packaged conformance gate runs it. | Draft | [build] | no |
| FR-013 | A Java binding (Maven module, Java 25) loads the same registry, envelope and data schemas and the same fixtures, implements the validator and the upcaster, and passes the whole corpus, including byte equality with the goldens. | Draft | [build] | no |
| FR-014 | CI builds and tests the Java binding on every PR. | Draft | [build] | no |
| FR-015 | `validate_strict_envelope`, the producer emission paths and the diary reducer's order and folding are unchanged; existing tests pass without edits to their expectations. | Draft | [ratchet] | yes — positive control: FR-009 and FR-010 goldens exercise the same rows |
| FR-016 | `COMPATIBILITY.md` (an `11.3.0` minor section and the version declaration), `CHANGELOG.md`, the design doc's row-level upcaster contract (detection, outcomes, error codes) and the producer contract README record the release and its classification, including the release and lock refresh that core needs. | Draft | [build] | no |

### Non-Functional Requirements

| ID | Requirement | Threshold | Status |
|---|---|---|---|
| NFR-001 | Determinism | Upcasting the same input twice, and in Python and Java, yields identical bytes for 100% of corpus rows and of at least 200 generated rows per property test. | Draft |
| NFR-002 | Type safety | `mypy --strict` reports 0 errors on the new modules. | Draft |
| NFR-003 | Test coverage | Line coverage of the new Python modules is at least 95%. | Draft |
| NFR-004 | Throughput | Upcasting 10,000 diary rows takes under 2 seconds in Python on CI hardware (asserted by a test). | Draft |
| NFR-005 | Runtime dependencies | The Python upcaster adds no new runtime dependency; schema validation uses the existing optional `jsonschema` extra. | Draft |

### Constraints

| ID | Constraint | Status |
|---|---|---|
| C-001 | No log rewrite: legacy rows on disk and their fixtures are never changed or retired. | Active |
| C-002 | Schemas, the registry and the fixtures are the single source of truth; neither binding hand-codes a mapping the registry states. | Active |
| C-003 | No live-path change before the writer switch (charter directive 1): no producer emits `4.0.0`, strict ingress is unchanged. | Active |
| C-004 | SemVer: minor release `11.3.0` within core's existing `<12` range; the `12.0.0` cutover (strict accepts `4.0.0`) is out of scope. | Active |
| C-005 | Mapping a shape-A row found inside a diary stays pass-through (design section 1). | Active |
| C-006 | The Java binding is not published to a package registry in this mission. | Active |

### Key Entities

- **Legacy row**: a line of a log in shape A (`3.0.0` `Event`), B (diary lane or annotation
  row), C (pre-3.0 raw) or D (other diary rows).
- **CloudEvent row**: CloudEvents 1.0 structured JSON with Spec Kitty extensions.
- **Registry entry**: type name, `dataschema`, data schema, scope, subject rule, lifted members,
  legacy forms (shape-A `event_type`, diary kind).
- **Envelope profile**: read or write; the attribute rules a row must meet.
- **Upcast result**: the outcome (mapped, pass-through, error), the row (CloudEvent or the
  original), the original line, and any named warnings.
- **Upcast context**: caller-supplied Mission id and slug (from the Mission's `meta.json`).

## Success Criteria *(mandatory)*

- **SC-001**: 100% of the legacy rows in the corpus upcast to a CloudEvent that validates
  against the read profile and its data schema, or to their recorded pass-through/error
  outcome — [build] · no-op passable: no
- **SC-002**: 100% of the golden outputs are byte-identical across two runs and across the
  two bindings — [build] · no-op passable: no
- **SC-003**: All 8 status-diary replay fixtures reduce to the same state from the upcast rows
  as from the legacy rows — [build] · no-op passable: no
- **SC-004**: 0 changed expectations in the existing test suite and conformance gate —
  [ratchet] · no-op passable: yes (positive control SC-001)

## Assumptions

- The reverse-DNS prefix `ai.spec-kitty` and the type names in today's registry are final
  (confirmed 2026-10-27); the 9 new types follow `<noun>.<verb>.v1`.
- Core consumes this package with a `<12` bound, so `11.3.0` needs only a lock refresh in core
  (cf. spec-kitty#6602); the core-side refresh is core's work.
- The core repository could not be read in this session; constraints from core ADRs are taken
  from this repository's ADR and design text and from the maintainer's decisions.
- Shape-A payload models are the source of the existing data schemas for the 17 already
  registered strict types, so shape-A payloads of those types are valid `data`.

## Out of Scope

- The Phase 4b cutover (`12.0.0`), the reducer sort-key switch (post-GA, #6609), the core
  merge-driver change, writer switches, the Zeitgeist codec, and Kafka/Spring bindings.
