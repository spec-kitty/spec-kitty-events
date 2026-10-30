---
title: 'ADR: adopt CloudEvents 1.0 as the event envelope'
description: 'Accepted: CloudEvents 1.0 structured JSON is the Spec Kitty event-log envelope; the 3.0.0 envelope and status-diary row are legacy inputs to a read-time upcaster.'
doc_status: active
updated: 2026-10-27T00:00:00Z
type: ADR
kind: ADR
authors:
  - MOES-Media
  - human:stijn
---

# ADR: adopt CloudEvents 1.0 as the event envelope

**Status:** Accepted

**Date:** 2026-10-27 (proposed 2026-10-13; accepted in part 2026-10-15)

**Deciders:** Maintainer. Direction: maintainer approval, 2026-10-13. Accepted, with the
open questions 1, 2 and 4 decided, on maintainer approval, 2026-10-15. Open questions 3a and 3b
(timing) and every further choice decided on maintainer approval, 2026-10-27, before the first
implementation mission ([#92](https://github.com/spec-kitty/spec-kitty-events/issues/92)) started.
No question in this ADR is open.

**Reader:** a maintainer or consumer author deciding whether the Spec Kitty event log,
the merge driver and the Mission Status Read API move to a CloudEvents envelope, and when.

**Technical Story:** [spec-kitty/spec-kitty#6117](https://github.com/spec-kitty/spec-kitty/issues/6117)
(Mission Write API). Related here:
[spec-kitty/spec-kitty-events#16](https://github.com/spec-kitty/spec-kitty-events/issues/16),
[#38](https://github.com/spec-kitty/spec-kitty-events/issues/38),
[#7](https://github.com/spec-kitty/spec-kitty-events/issues/7),
[#72](https://github.com/spec-kitty/spec-kitty-events/issues/72).

This ADR starts the `docs/adr/` folder of this repository. Design detail is in
[`docs/architecture/cloudevents-envelope.md`](../architecture/cloudevents-envelope.md). Nothing here has
shipped: no code, schema, fixture or version number changes with this document.

---

## Context and Problem Statement

Two row shapes exist today, and both are wire contracts of this package.

1. **The transport envelope `Event`** (`src/spec_kitty_events/models.py`, `schema_version`
   `3.0.0`). Fourteen fields: `event_id`, `event_type`, `aggregate_id`, `payload`,
   `timestamp`, `build_id`, `node_id`, `lamport_clock`, `causation_id`, `project_uuid`,
   `project_slug`, `correlation_id`, `schema_version`, `data_tier`. The strict profile
   (`strict.py`) requires all fourteen. Nothing in it names a spec or a binding: it is a
   bespoke Pydantic model with a bespoke JSON Schema generated from it.
2. **The status-diary row `StatusEvent`** (`diary.py`), one line per lane transition in
   `status.events.jsonl`: `event_id`, `mission_slug`, `wp_id`, `from_lane`, `to_lane`,
   `at`, `actor`, `force`, `execution_mode`, `reason`, `review_ref`, `evidence`,
   `review_result`, `policy_metadata`, `mission_id`. This is not an `Event`: it has no
   `event_type`, no causal fields and no schema version, and the reducer orders it by
   `(at, event_id)`.

What the log writers do not have is a single, externally specified envelope. The
consequences:

- Issue #16 made this package the canonical owner of event envelopes, but the envelope is
  only defined as Python. The 125 committed JSON Schemas under `src/spec_kitty_events/schemas` are generated from Pydantic
  models, so any non-Python reader has to treat the Python as the spec.
- Issue #38 had to define a legacy compatibility contract (`RAW_HISTORICAL_ROW`, named
  legacy shapes) because rows on disk predate the envelope. The diary shape is not
  covered by it at all.
- Issue #7 asks for this package to be the machine-readable contract authority that
  consumers verify against. A bespoke envelope makes every new consumer re-implement it.
- Issue #72 observes that the diary row carries no causal clock although the transport
  envelope does, so the two shapes cannot be ordered the same way.

The maintainer's reasons for adopting CloudEvents (direction approved 2026-10-13):

- It opens up Kafka and cloud streaming, because CloudEvents has a Kafka protocol binding.
- Spring Cloud Stream support becomes usable directly at the API level of the Mission
  Status Read API.
- It ties in with the UI.

## Decision Drivers

- One envelope that an unrelated tool can parse from a public spec, in any language.
- No log rewrite: archived Missions on `main`, git history and other people's clones keep
  their rows as written.
- Fail closed (charter) at ingress and in writes: an unmappable legacy row is an error,
  never a silent default. Local reads of a user's own history stay permissive, as the
  versioning contract requires, and surface named warnings instead of defaults.
- The package stays the single authority for envelope, schemas, fixtures and compatibility.
- Cheap migration: existing fixtures and, where their shape is unchanged, payload models
  carry over. The lane-transition `data` is a new model (design section 3); legacy payload
  models keep validating legacy rows.

## Considered Options

1. **Keep the bespoke envelope** (`Event` 3.0.0 plus the diary row) and document it.
   - Pros: no migration, no major bump, no charter question.
   - Cons: the two shapes stay divergent (#72); no standard transport binding, so Kafka
     or Spring support means writing and maintaining our own mapping; every non-Python
     consumer re-implements a Python-defined contract.
2. **An AsyncAPI-described custom envelope.** Keep our own attribute set but publish it as
   an AsyncAPI document.
   - Pros: machine-readable description, tooling for docs and code generation.
   - Cons: AsyncAPI describes channels and messages, it does not define an envelope or a
     protocol binding with header conventions. We would still own and version the
     envelope, and gain no ready-made Kafka or Spring behaviour. It also does not
     remove the second-shape problem. (It remains usable later, to describe channels
     that carry CloudEvents; the two are not exclusive.)
3. **CloudEvents only at the transport edge.** Keep the custom on-disk row; wrap or map
   to CloudEvents when publishing to a broker.
   - Pros: no change to log writers or the local format; no upcaster for disk rows.
   - Cons: two envelopes forever, plus a mapping that every edge must implement
     identically; the write core and the read API would each hold both shapes; the
     diary-versus-transport divergence stays. This is the cheapest short-term option and
     the most expensive long-term one.
4. **CloudEvents 1.0 as the envelope for log rows (chosen).** On disk, in the log and at
   the edge, one shape. Legacy shapes are read through an upcaster.
   - Pros: one shape everywhere; external spec; Kafka protocol binding and a Java SDK
     exist; the same row is the log line and the broker message.
   - Cons: a major envelope version; attribute-name rules force renames
     (`lamport_clock` becomes `lamportclock`); CloudEvents has no ordering semantics we
     can rely on, so ordering stays ours; a permanent upcaster to maintain.

## Decision

**Chosen option:** 4. CloudEvents 1.0, structured JSON mode
(`application/cloudevents+json`), one event per line, is the envelope for Spec Kitty
event-log rows. The envelope definition (attribute mapping, type registry, schemas,
upcaster contract, fixtures) is owned by this package.

Which shape CloudEvents replaces, stated for both:

- **The 3.0.0 transport envelope `Event`:** becomes a **legacy input**. Its fields map to
  CloudEvents attributes and extension attributes. New producers do not emit it.
- **The status-diary row `StatusEvent`:** becomes a **legacy input**. Its fields map to
  CloudEvents attributes, extension attributes and the `data` member. New rows in
  `status.events.jsonl` are CloudEvents.
- Both are read through one upcaster. Every field of both shapes has a mapping in the
  design doc; a field with no mapping is a defect in the design, not a field to drop.
  Rows that are not lane events (Decision Point, retrospective, future protocol rows) are
  not mapped: the upcaster passes them through unchanged, as the reducer ignores them
  today (design section 1).

Confirmed by the maintainer on 2026-10-15: CloudEvents 1.0 structured JSON, from now on.

Spec Kitty fields ride as CloudEvents extension attributes. CloudEvents requires
extension names to be lowercase letters and digits and says they SHOULD NOT exceed 20
characters, so renames are part of the change.

## Consequences

### Versioning (per `contracts/versioning-and-compatibility.md`)

The two axes move independently and both move here.

- **On-wire envelope schema version:** `3.0.0` to `4.0.0` in the cutover release (design
  section 14, Phase 4b), meaning a release of this package, not of the CLI. The upcaster's
  output carries `4.0.0` from the first library release, but it is for reading only. Until
  the cutover release `validate_strict_envelope` keeps requiring `3.0.0` and producers
  keep emitting it. The Phase 3 validator and the `4.0.0` schemas, published earlier, are
  non-normative until the cutover release: no consumer or producer gates on them, and the
  strict profile only accepts `4.0.0` there. That is why the earlier phases are additive
  (minor) and the cutover release is the major. In the cutover release the package accepts `4.0.0` beside `3.0.0`
  (a named legacy path), so no live producer is rejected; the accept/reject boundary
  widens. Whether and when `3.0.0` is later rejected (a separate retirement step, after
  every producer emits CloudEvents) is question 3b, decided A (design section 14,
  "Cutover").
- **Package version:** a **major** bump in the cutover release (it accepts a previously
  rejected envelope). The versioning contract makes any change that
  rejects a previously accepted envelope, or accepts a previously rejected one, a major
  change. The package is at an `11.x` line and consumers pin `<12`, so the next major (`12.0.0`)
  also gates adoption. The cutover release is not gated on question 3a or 3b;
  whether a later retirement step rejects `3.0.0` is 3b.
- The required artifacts of a major bump all apply: `CHANGELOG.md` `### Breaking`,
  `COMPATIBILITY.md` section and the single `Current package version` declaration,
  `pyproject.toml` and `__version__`, regenerated schemas, and conformance fixtures for
  the new accept/reject boundary.

### Consumers

- **spec-kitty (CLI):** writes and reads `status.events.jsonl`. It gains the shared
  reader (upcaster) for reading and must emit CloudEvents once its project schema is
  bumped. Its orchestrator-api envelopes are a separate contract and are not changed.
- **spec-kitty-saas:** ingests canonical envelopes under the strict profile today. It
  must accept the new envelope and keep rejecting raw historical rows at ingress. The
  central CLI-to-SaaS contract named by the charter must be updated in the same change.
- **Mission Status Read API:** reads the diary through the shared reader. It needs a
  CloudEvents parser and the upcaster contract in Java; generated or conformance-tested
  from the committed schemas and fixtures.
- **In-package Zeitgeist codec** (`src/spec_kitty_events/zeitgeist_attrs.py`): encodes
  `(payload, Event)` pairs and dispatches `WP_STATUS_CHANGED` to `StatusTransitionPayload`.
  Lifting members out of `data` means a CloudEvent's `data` no longer validates against
  that model. It stays on shape A, or gains a CloudEvent input that projects the lifted
  members back; which one is decided in Phase 4 (design section 14). It is not silently
  left behind.
- **spec-kitty-runtime and other readers of this package:** same upcaster, same major.

### Streaming

Kafka and Spring become possible, not delivered. The CloudEvents Kafka protocol binding
defines binary mode (`ce_` header prefix) and structured mode; the Java SDK has
`cloudevents-kafka` and `cloudevents-spring` modules. Details and what is unverified are
in the design doc. Spring Cloud Stream's own CloudEvents handling is not verified there.

### Charter (item for the maintainer)

The charter says the package ships as "a Python library with committed schemas and
fixtures" and names "Python 3.10+ with Pydantic event models". Two readings:

- **CloudEvents envelope, Pydantic models still the source, schemas still generated:**
  no amendment is strictly required. The charter does require "deliberate compatibility
  review" of envelope changes, which this ADR and its PR are.
- **Schemas as the source of truth, with a Java binding generated from or tested against
  them** (the direction in spec-kitty#6117): this contradicts the current wording and
  needs a charter amendment through pull request review by maintainers. **Flagged as a
  maintainer item; this ADR does not amend the charter.**

The charter's central CLI-SaaS contract rule also applies (see Consumers).

## Compatibility

- **Read-time upcaster, in the shared reader library.** It reads a legacy row (3.0.0
  `Event`, diary `StatusEvent`, or a named pre-3.0 shape) and returns a CloudEvent, or the
  row unchanged. It is deterministic and invents nothing. In strict use (ingress, and
  validating new rows before they are written) an unmappable row fails closed with a named
  error. Local reads and merges of a user's own history never become narrower than
  today's reducer and driver, as the versioning contract promises: what they accept or
  ignore today passes through unchanged, so the merge driver never rejects a row it
  accepts today. It drops no row and writes no default. The principles are design section 9; the
  row-level contract is specified and tested in the first implementation mission.
- **No log rewrite.** Legacy rows stay in files indefinitely and are covered by fixture
  tests for as long as they can exist.
- **New rows only as CloudEvents.** A project schema bump stops older CLIs from appending
  legacy rows to an upgraded project.
- **Log merge driver (proposal for the core repository to confirm).** The driver lives in
  the core repository and today keys on `event_id` alone, raises on a same-id,
  different-content pair and re-serializes rows (spec-kitty main 3847251e0,
  `src/specify_cli/status/event_log_merge.py:73-77`, `:165-174`, `:193`). The proposal: it
  reads rows through the upcaster and compares them by `(source, id)`, so a file with old
  and new rows from two branches merges; rows it cannot map are carried through by their
  raw id as today, not rejected (its mode is core's decision, never narrower than today);
  and it writes the original bytes of the retained row, so legacy rows are not rewritten
  (design sections 9 and 14). Fork detection belongs in the same proposal: it reads
  top-level `from_lane`, `to_lane` and `wp_id` and skips rows lacking them as
  non-transitions (`src/specify_cli/status/event_log_merge.py:137-142`, called from
  `src/specify_cli/consolidation/drivers.py:246-250`; the #5967 backstop), so on CloudEvent
  rows it would be blind. Two failure modes: an unchanged driver rejects CloudEvent rows
  (no `event_id`) and the merge conflicts (`src/specify_cli/consolidation/drivers.py:245-257`);
  a partially adopted driver, accepting `id` with fork detection still on top-level fields,
  lets a concurrent cancel and advance merge cleanly. The sort key reads the upcast form too. **Rollout dependency, owned by core:** this
  driver change, covering both, ships before any phase lets writers emit CloudEvents into a log. The key
  depends on questions 2 and 4 (both decided: ULID ids, the `ai.spec-kitty` prefix); nothing
  here is decided for core.
- **Silent aliases.** The charter forbids "silent compatibility aliases ... without an
  explicit cutover decision". The upcaster is not one: it is named, its mapping is
  published row by row, in strict use it fails closed with a named error, and it is pinned by
  fixtures. This ADR records the direction; the explicit cutover decision
  for the live-path change is the writer switch that question 3a (decided 2026-10-27) schedules, and no
  live path changes before then. A reader that accepted legacy keys without declaring them
  would be the thing the directive bans.

### Costs

A permanent upcaster; a second language binding to keep in step (if the Java binding
goes ahead); an ordering rule that CloudEvents does not give us; renamed attributes
that every consumer must learn.

## Decided questions (1, 2 and 4 on 2026-10-15; 3a, 3b and the further choices on 2026-10-27)

### 1. Ordering (decided: C)

The code orders the diary by wall-clock `(at, event_id)` (the reducer as shipped, #4941).
Core ADR `2026-02-09-3` (event-log merge semantics, Accepted) orders by Lamport clock,
then `at`, then `event_id`. The `Event` docstring says `timestamp` is not used for
ordering and Lamport plus `node_id` own it. Issue #72 proposes threading a causal clock
into the diary. Today's diary writers stamp no clock.

| Option | Trade-off |
|---|---|
| A. Keep `(time, id)`; carry no clock | No writer change; reducer sort key unchanged; keeps the known brittleness #72 describes; contradicts the ADR. |
| B. Carry `lamportclock` and `nodeid` as extensions; order Lamport, `time`, `id` | Matches the ADR; needs the write core to stamp a clock and a rule for legacy rows with none; changes the reducer sort key. |
| C. B, with `(time, id)` as the fallback for rows without a clock | B plus no breakage of existing logs; also changes the reducer sort key (design section 5). Only valid with a defined total order for logs mixing clocked and clockless rows (a naive mixed comparator is not transitive). |

**Decided (maintainer, 2026-10-15): C.** A Lamport clock, with `(time, id)` as the tiebreak
and as the fallback for rows without a clock. It is conditional on the total order for logs
mixing clocked and clockless rows being defined first (see `contracts/total-order.md`). Phase 2 owns the reducer
sort-key change (design section 14).

**Scheduling note (maintainer, 2026-10-27).** C is the target order and the shipped total order
(`contracts/total-order.md`) already implements it. The status-diary reducer keeps its wall-clock
`(at, event_id)` order for the Spec Kitty 4.0.0 line; the Phase 2 reducer sort-key switch to
Lamport-first is post-GA work (core spec-kitty#6609, per the operator's reading of the core ADR
`2026-02-09-3` amendment, which this repository does not mirror). #92 does not change the reducer. CloudEvents' own `sequence` extension is not a fit and its
documentation says such extensions "have no official standing", so ordering is a Spec
Kitty extension in any case. Options B and C also bound `lamportclock` to the CloudEvents
Integer range (signed 32-bit), or make it a string (design section 5).

### 2. Event id format (decided: A)

The validator accepts 26-char ULID, 36-char UUID and 32-char hex UUID today.

| Option | Trade-off |
|---|---|
| A. ULID only for new events; readers keep accepting UUID in legacy rows | One format going forward, sortable ids; legacy UUID rows stay readable. |
| B. ULID and UUID both valid for new events | No producer change; two formats forever; `id` uniqueness reasoning stays harder. |

**Decided (maintainer, 2026-10-15): A.** ULID is the only event id format for new events. The upcaster normalizes ids with the existing rules, so a merge
driver, if core adopts the proposal, compares UUID spelling variants as one id.

### 3. Timing

Five version numbers exist and must not be conflated, the same five as design section 8:
the CloudEvents spec version (`1.0`), the on-wire envelope schema version (`3.0.0`), the
payload schema version (the `type` suffix), the package version (now `11.x`), and the
Spec Kitty release line (`4.x` in the core). "Envelope major" means the on-wire schema going `3.0.0` to `4.0.0`; the package
major that carries it is a different number.

Question 3 carries two separate questions. The 4b package release (the package starts
accepting `4.0.0`) is not gated on either.

#### 3a. When writers switch

The product-line mapping of the writer phase (design section 14, Phase 5): when the CLI
starts emitting CloudEvents.

| Option | Trade-off |
|---|---|
| A. With the Spec Kitty 4.0 GA | Earliest; couples the GA to an unproven write path. |
| B. Later in 4.x, with the first write-core command switch | Spec Kitty 4.0 GA is not gated on it; two-step release possible. |
| C. With Spec Kitty 5.0 | Slowest; Read API consumes the old envelope meanwhile. |

**Decided (maintainer, 2026-10-27): writers switch when the Mission Read API and Write API are
ready, and that is on the Spec Kitty 4.0 GA critical path.** This is option A gated on readiness
rather than on a date: the GA waits for the write path, not the other way round. The library goes
first (models, schemas, registry, upcaster, fixtures, the Java binding; #92, a minor release), then
the 4b package major, then the core merge-driver change, then the writers. No live path changes
before the writer switch, which is the explicit cutover decision the charter directive asks for.

#### 3b. Whether and when `3.0.0` is retired

The design's Cutover options (section 14): A, retire `3.0.0` in a later package release
once every producer has shipped Phase 5 (a second major); B, keep accepting both
indefinitely or for a stated window. Whether SaaS ingress keeps accepting the old shape
for a window or indefinitely, or a later retirement step rejects it, is this question.
**Decided (maintainer, 2026-10-27): A.** `3.0.0` is retired in a later package major, once every
producer emits CloudEvents. That retirement is its own release and its own cutover decision; until
then the package accepts both.

### 4. Reverse-DNS type prefix (decided: `ai.spec-kitty`)

`dev.spec-kitty` was a placeholder. CloudEvents says `type` SHOULD be prefixed with a
reverse-DNS name. The prefix is part of every type string and every `dataschema`, so
changing it later is a breaking change.

| Option | Trade-off |
|---|---|
| A. `dev.spec-kitty` | Short; assumes the project controls that domain. |
| B. A prefix on a domain the project demonstrably controls | Honest reverse-DNS; needs the maintainer to name the domain. |

**Decided (maintainer, 2026-10-15): the prefix is `ai.spec-kitty`.** The maintainer left the
prefix to the implementers; it is neither A nor B above. It is the prefix of the type registry
in the released 10.5.x package and of the spec-kitty producer, so a type reads
`ai.spec-kitty.<name>.v<N>`. Changing it later is a breaking change.

## Further choices (confirmed 2026-10-27)

The maintainer confirmed every choice below on 2026-10-27; the `INTERIM` markers of the 10.5.0
registry are lifted by #92. Two of them differ from the illustrative example in spec-kitty#6117.

- **New-type versioning replaces a clause of the versioning contract.** The design
  (section 7) proposes `.vN` new types in place of the contract rule that renaming a typed
  payload field is major, with a contract-text amendment at cutover.
- **Dedupe key `(source, id)`** for any log merge (design section 6).
- **Type name `lane.transitioned`** and the set of lifted members (design sections 3
  and 7).

- **Data member naming:** `data` keeps the existing snake_case member names (`from_lane`,
  `to_lane`) so existing payload models carry over; #6117's example used `fromlane`/`tolane`.
- **Which fields become extensions:** `actor` and `executionmode` are context attributes
  (they survive Kafka header mapping and filter without parsing `data`) rather than `data`
  members.

Recorded with them on 2026-10-27, for the first implementation mission (#92):

- **Release:** #92 is a minor release (`11.3.0`): the full registry, the upcaster, the legacy
  corpus, the CloudEvents validator (non-normative) and the Java binding. The Phase 4b cutover
  (`12.0.0`, strict accepts `4.0.0`) follows as its own change.
- **Two envelope profiles:** the read profile (what the upcaster returns) has `lamportclock` and
  `nodeid` optional, because legacy rows carry none and the upcaster invents nothing; the write
  profile (new rows) keeps requiring them.
- **`dataschema` base:** `urn:ai.spec-kitty:schema:`, an identifier with no hosting assumption.
  Bindings resolve it to the packaged schema files. Changing it later is breaking.
- **Java binding:** a Gradle module built for Java 25 (Temurin 25, as core's acceptance JVM),
  passing the same fixtures as the Python binding. Not published to a registry by #92.
