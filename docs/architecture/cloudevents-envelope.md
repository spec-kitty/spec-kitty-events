---
title: CloudEvents envelope design
description: 'Proposed CloudEvents 1.0 envelope design: field mapping from the 3.0.0 envelope and status-diary row, upcaster principles, and a phased rollout.'
doc_status: draft
updated: 2026-10-13T00:00:00Z
type: Architecture
kind: Explanation
---

# CloudEvents envelope design

**Status:** Proposed design. Nothing here has shipped. This document supports
[ADR-0001](../adr/0001-cloudevents-envelope.md), which is Accepted (2026-10-15).
Where this document recommends, it says "recommendation". The ADR has decided ordering
(option C), the id format (ULID only) and the type prefix (`ai.spec-kitty`); timing (3a and 3b) is still open, and the two embedded
choices of section 4 (snake_case inside `data`; `actor` and `executionmode` as extensions)
are proposals to confirm on the PR.

This is a decision-level document. The row-level upcaster contract is deliberately not
specified here (section 9).

Spec claims below were checked on 2026-10-13 against the CloudEvents specification
repository (`https://github.com/cloudevents/spec`) at the release tag `ce@v1.0.2` (spec
version 1.0). A statement that holds only on the unreleased `main` branch says "on main,
not in v1.0.2". Anything not checked against a primary source is marked **unverified**.

## 1. Scope: which shapes

| # | Shape | Where defined | Role after this change |
|---|---|---|---|
| A | Transport envelope `Event`, `schema_version` `3.0.0` | `src/spec_kitty_events/models.py` | Legacy input. Not written by new producers. |
| B | Status-diary row `StatusEvent`, one line of `status.events.jsonl` (lane rows and annotation rows) | `src/spec_kitty_events/diary.py` | Legacy input. New rows are CloudEvents. |
| C | Pre-3.0 raw rows (named forms under `conformance/fixtures/class_taxonomy/historical_row_raw/`) | conformance fixtures | Named legacy inputs with no mapping: recognised, not mapped. |
| D | Non-lane rows in `status.events.jsonl` (retrospective rows, Decision Point and future protocol rows) | `diary.py` (`_is_non_lane_row`) | Passed through unchanged, never mapped. A type becomes mapped only by a registry entry (section 7). |

CloudEvents replaces A and B as the written form. Shape A's payload for a lane change
(`WPStatusChanged`) already carries the diary fields, so A and B converge on one
CloudEvent type for lane transitions.

Read context decides what is mapped. Shape A is mapped to a lane CloudEvent only on
ingress and transport reads (and in the shared reader's non-diary paths). Inside a
local `status.events.jsonl`, `diary.py` (`_is_non_lane_row`) skips any row with a
top-level `event_type`, which is what a shape A row has; such a row stays pass-through
there, so the diary reducer's treatment of those rows does not change. Mapping a shape A
row found in a diary is permitted only if the first implementation mission proves equal
reduced state.

Out of scope: orchestrator-api envelopes (a separate contract), the `status.json` derived
snapshot, the reducer's folding rules, and any write logic.

## 2. CloudEvents attributes used

Required (spec): `id`, `source`, `specversion`, `type`. Spec wording checked (`ce@v1.0.2`):

`id` "MUST be a non-empty string" and producers "MUST ensure that `source` + `id` is
unique for each distinct event"; `source` "MUST be a non-empty URI-reference" ("An absolute
URI is RECOMMENDED"); `specversion` "MUST use a value of `1.0`"; `type` "SHOULD be prefixed
with a reverse-DNS name" and its versioning format "is producer defined".

Optional ones we always use: `time` ("If present, MUST adhere to the format specified in
RFC 3339"), `datacontenttype` (always `application/json`, set explicitly), `dataschema`
("If present, MUST be a non-empty URI"). `subject` is used when the event is scoped to an
entity inside its source (a work package), and omitted for Mission-level events.

Names. Spec: attribute names "MUST consist of lower-case letters [a-z] or digits [0-9]
from the ASCII character set" and "SHOULD NOT exceed 20 characters"; extension attributes
"MUST follow the same naming convention and use the same type system as standard
attributes", and values must be Boolean, Integer, String, Binary, URI, URI-reference or
Timestamp. Consequences:

- No underscores: every snake_case field that becomes a context attribute is renamed. The
  name check used below is `^[a-z0-9]{1,20}$`.
- No object-valued or array-valued context attribute: structured values go in `data`.
- `Integer` is a signed 32-bit range (-2,147,483,648 to +2,147,483,647), and event formats
  "MUST only use `Integer` values in this range".
- Null (JSON format, `formats/json-format.md`): "Unset attributes MAY be encoded to the
  JSON value of `null`. When decoding attributes and a `null` value is encountered, it MUST
  be treated as the equivalent of unset or omitted." Readers treat a null context attribute
  as absent; writers here omit absent attributes. Null inside `data` is ordinary JSON.

## 3. Attribute mapping: transport envelope `Event` (shape A)

All fourteen fields of `Event` (checked against `Event.model_fields`).

| `Event` field | Target | Type | Note | Name check |
|---|---|---|---|---|
| `event_id` | `id` | String | Normalized with `normalize_event_id` (ULID upper-cased; UUID lower-cased, hyphenated). | ok |
| `event_type` | `type` | String | Looked up in the type registry (section 7). | ok |
| `aggregate_id` | `source` + `subject` + `missionid` | String | See "Aggregate split". | ok |
| `payload` | `data` | Object | Moved verbatim, except members lifted to context attributes (below). | ok |
| `timestamp` | `time` | Timestamp | Copied as written; must be RFC 3339. | ok |
| `build_id` | `buildid` | String | Copied. | 7: ok |
| `node_id` | `nodeid` | String | Copied. | 6: ok |
| `lamport_clock` | `lamportclock` | Integer | Copied; `lamport_clock` is illegal (underscore). Python `int` is unbounded, the spec Integer is 32-bit: see ordering (section 5). | 12: ok |
| `causation_id` | `causationid` | String | Normalized id; omitted when null. | 11: ok |
| `project_uuid` | `projectuuid` | String | UUID text, copied. | 11: ok |
| `project_slug` | `projectslug` | String | Omitted when null. | 11: ok |
| `correlation_id` | `correlationid` | String | Normalized id. | 13: ok |
| `schema_version` | `schemaversion` | String | Output is the constant `4.0.0` (the CloudEvents form); the input `3.0.0` is the shape marker and is not copied. | 13: ok |
| `data_tier` | `datatier` | Integer | Integer 0-4, lifted only when present; no default is written. | 8: ok |

Constant attributes on every mapped row: `specversion` = `1.0`; `datacontenttype` =
`application/json`; `dataschema` from the registry (section 8); `schemaversion` = `4.0.0`.
The upcaster's `4.0.0` output is for reading; producers do not emit it before the cutover
release (section 14).

The Correlation extension (`cloudevents/extensions/correlation.md`) exists on spec main,
not in v1.0.2 (the file is absent at that tag). We reuse the names `correlationid` and
`causationid` but define them, as optional non-empty strings, in our own schema either
way. The `documented-extensions.md` page at v1.0.2 says such attributes "have no official
standing and might be changed, or removed, at any time".

**Aggregate split.** `aggregate_id` for Missions has the form `mission/<mission_id>` or
`mission/<mission_id>/wp/<WPnn>`, where `<mission_id>` is a ULID. The strict profile does
not enforce that: fixtures carry slug-like segments (`mission/replay-mission`,
`mission/M-SJ`, `mission/m-alpha-001`) and unprefixed or other-prefix aggregates (`WP01`,
`mission-software-dev`, `dp/dp-001`, `connector/conn-github-001`, `audit/mission-010/run-001`).
The split applies only to a segment that is a ULID; any other segment falls under the
"any other prefix" rule below, with no `missionid`, so a slug is never presented as a
mission id. Precedence when the aggregate segment and a payload `mission_id` disagree is a
principle to settle in the first implementation mission: the registry-lifted payload value
and the caller context must not be silently overridden, and a disagreement is a named
error in strict use. Fixtures for slug-segment and unprefixed aggregates are part of the
Phase 1 mapping exit criterion.

- `missionid` extension = the `<mission_id>` segment.
- `source` = `/spec-kitty/missions/<mission_id>` (prefix `ai.spec-kitty`, ADR question 4, decided).
- `subject` = the work package id (for example `WP01`), absent for Mission-level events.
- The registry marks which types are work-package scoped, so the split is reversible.
- `session/<id>` aggregates, a non-ULID mission segment, and any other prefix: `source` =
  `/spec-kitty/projects/<project_uuid>`, `subject` = the full `aggregate_id`, no
  `missionid`.

**Lifted payload fields.** Which payload members become context attributes is a property of
the registry entry (section 7). For the lane-transition type (`WPStatusChanged`) the 3.0.0
payload repeats `mission_slug`, `mission_id`, `wp_id`, `actor` and `execution_mode`; these
become `missionslug`, `missionid`, `subject`, `actor` and `executionmode` and leave `data`.
For every other type nothing is lifted. The `doing` lane alias that `StatusTransitionPayload`
accepts is resolved to `in_progress` in `data`; this is the one place a payload is not moved
verbatim, and it is a published mapping, not a silent alias.

**`data` for the annotation type.** `delta` (`WPInnerStateDelta`) and an optional
`actorbinding` (structured actor), as for the lane type; no other member.

**`data` for `lane.transitioned.v1`.** The post-lift `data` cannot validate against
`StatusTransitionPayload` (`extra="forbid"`, requires the lifted members), so it is a new
model and committed schema with its own `dataschema`; the old model keeps validating legacy
payloads. Members: `from_lane` (Lane or null), `to_lane`, optional `force`, `reason`,
`review_ref`, `evidence`, `summary` (A only), `review_result` and `policy_metadata` (B
only), and `actorbinding` (structured actor, A or B). Members present in only one shape
are optional in the union; none is defaulted.

## 4. Attribute mapping: diary row `StatusEvent` (shape B)

All fifteen fields (checked against `StatusEvent`). The diary row has no envelope-level
fields, so several attributes are constants or supplied by context (the Mission's
`meta.json`).

| `StatusEvent` field | Target | Type | Note | Name check |
|---|---|---|---|---|
| `event_id` | `id` | String | Normalized id. | ok |
| (none) | `type` | String | Constant `ai.spec-kitty.lane.transitioned.v1`. | ok |
| (none) | `specversion`, `datacontenttype`, `dataschema`, `schemaversion` | constants | `1.0`; `application/json`; registry URI; `4.0.0`. | ok |
| (none) | `source` | URI-ref | `/spec-kitty/missions/<missionid>`. | ok |
| `mission_slug` (legacy name `feature_slug`, accepted by `StatusEvent.from_dict` today) | `missionslug` | String | Copied. `feature_slug` is a marked legacy name, read as `mission_slug`. | 11: ok |
| `mission_id` (absent in older rows) | `missionid` | String | Copied when present, otherwise supplied by caller context. | 9: ok |
| `wp_id` | `subject` | String | Copied; enough to filter by work package, so not duplicated as an extension. | ok |
| `at` | `time` | Timestamp | Copied as written; must be RFC 3339. | ok |
| `actor` (string) | `actor` | String | Copied; an empty string is omitted. | 5: ok |
| `actor` (structured `{role, profile, tool, model}`) | `actor` + `data.actorbinding` | String + object | `actor` = `actor_identity_str` projection (`tool`, else `role`); the dict is kept whole in `data.actorbinding`. An object cannot be a context attribute. | ok |
| `execution_mode` | `executionmode` | String | Copied. | 13: ok |
| `from_lane`, `to_lane` | `data.from_lane`, `data.to_lane` | Lane | Copied (`from_lane` may be null). | n/a (`data`) |
| `force`, `reason`, `review_ref`, `evidence`, `review_result`, `policy_metadata` | `data.<same name>` | Boolean; String or null; `DoneEvidence`; object | Copied; null kept inside `data`; `review_result` only when present. | n/a |
| annotation row (`kind: annotation`): `event_id`, `at`, `wp_id`, `actor` | `id`, `time`, `subject`, `actor` | as above | Same mapping as the lane row. The row has six fields (these four plus `kind` and `delta`) and carries no mission identity, so `source` and `missionid` (and `missionslug`, if supplied) come only from caller context (the Mission's `meta.json`); `executionmode` is omitted. A structured `actor` (`InnerStateChanged.actor` may be one) follows the same rule as the lane row: `actor` projection plus an optional `data.actorbinding` beside `delta`. | ok |
| annotation row: `delta`, `kind` | `data.delta`, `type` | `WPInnerStateDelta`; String | `delta` copied whole; the `kind` discriminator is replaced by the registered annotation `type` (name fixed in Phase 1). | ok |

Decisions embedded here, for the maintainer to confirm on the PR:

- `data` keeps the existing snake_case field names so the existing payload models and
  schemas carry over and the A-to-CloudEvents mapping stays a move, not a rename. The
  illustrative example in spec-kitty#6117 used `fromlane`/`tolane`; that is a different
  choice. The spec does not apply the name rule to `data` members.
- `actor` and `executionmode` are extensions so they survive Kafka header mapping and can
  be filtered without parsing `data`.

Shape D rows are not mapped: they pass through unchanged, which matches the reducer's
"ignore in place" rule and keeps working for event types that do not exist yet.

## 5. Ordering

CloudEvents has no ordering semantics we can rely on. The documented `sequence` extension
(`extensions/sequence.md`, v1.0.2) is a String that "MUST be a non-empty
lexicographically-orderable string", with semantics set by `sequencetype`; on main the
wording differs (not in v1.0.2). `documented-extensions.md` (v1.0.2) says documented
extensions "have no official standing and might be changed, or removed, at any time".
Lamport clocks are integers and cross-node, which `sequence` (scoped to a `source`) does
not model. So ordering is a Spec Kitty extension. The attributes proposed, as decided in ADR
question 1 (option C):

- `lamportclock` (Integer) and `nodeid` (String): the existing pair, same meaning as in
  `Event`. Optional in the schema, because diary writers stamp none today.
- Reader rule options (ADR): A, wall-clock `(time, id)` as shipped; B, Lamport; C, Lamport
  with a `(time, id)` fallback. Decided: C (ADR, 2026-10-15), valid only if a total order for logs mixing
  clocked and clockless rows is defined (a comparator that uses the clock where present
  and `(time, id)` otherwise is not transitive, so the result would depend on input
  order, against the deterministic principle). Options B and C change the sort key the
  reducer uses (`reduce_parsed` sorts `(at, event_id)` today), so they are reducer changes
  as well as envelope changes, decided with the question; Phase 2 owns that change
  (section 14), which is why Phase 2 says "no reducer change" only for option A.
- A `lamportclock` above the 32-bit Integer range is part of the same decision: bound it,
  or make it a String-typed extension (decimal digits), which changes the attribute type.

## 6. Source and subject conventions

- `source` identifies the producing context, not the host: `/spec-kitty/missions/<missionid>`
  for Mission events, `/spec-kitty/projects/<project_uuid>` for project-scoped events.
  Relative URI-references are allowed by the spec ("non-empty URI-reference"; absolute
  "RECOMMENDED"). Whether to publish an absolute form is tied to the type prefix (ADR question 4, decided).
- `source` + `id` uniqueness: the spec says nothing about equal ids from different sources,
  and a relative `source` weakens cross-repo uniqueness. Within one Mission's log every row
  has the same `source`, so deduping by `id` there equals deduping by `(source, id)`.
  Proposal, to confirm on the PR: dedupe by `(source, id)`; keying on `id` alone
  across sources would drop distinct events unless a global-id invariant is added. Where
  the key lives is for core to decide (section 9).
- `subject` is the entity inside the source: `WP01` for a work package. Shape A's
  `.../wp/WP01` aggregate suffix is normalized to `WP01`; shape B's `wp_id` is already that
  form.

## 7. Type registry and naming

`type` is `<prefix>.<noun>.<verb>.v<N>`, for example `ai.spec-kitty.lane.transitioned.v1`
(the noun/verb name is a proposal to confirm on the PR, like the lifted members of section 3).
`<prefix>` is the reverse-DNS name `ai.spec-kitty` (ADR question 4, decided).

- The registry is a committed table: legacy `event_type` (A) and diary shape (B) to `type`,
  `dataschema` and payload model. It must cover every type in `STRICT_EVENT_TYPES` (26
  today) plus the diary lane and annotation rows, and states which payload members are
  lifted (section 3). Shape D types have no entry by default and are passed through; adding
  an entry later is additive and moves that type from pass-through to mapped.
- `.vN` is the payload major. A payload change that would make a previously valid `data`
  invalid is a new type with `.v(N+1)`; the old type is kept and still validates its
  rows. This is a proposal to confirm on the PR (ADR, "Further choices"); it deliberately replaces the versioning contract's rule that changing or
  renaming a typed payload field is major (there it makes the previous shape invalid) with
  new-type versioning: adding the new `.v(N+1)` type is additive (minor), the contract
  text is amended at cutover (section 14, Phase 4b), and emission of any new `.vN` type is
  capability-gated until each intended consumer can validate it, because a consumer on the
  previous package fails closed on an unknown type. The CloudEvents spec leaves the
  version format to the producer and points to its Primer, which was not read for this
  document (**unverified** what the Primer recommends).
- A registry entry that adds a type is additive (minor, per the versioning contract);
  changing an existing entry's accept/reject boundary is major.

## 8. `dataschema` and schema versioning

`dataschema` is a URI naming the committed JSON Schema for `data`, one per registered
type, for example the placeholder `https://schemas.example.invalid/spec-kitty/lane.transitioned.v1`
(an absolute https URI; the spec only requires a non-empty URI, and a `.vN` suffix changes
the URI on an incompatible schema change). The base is open (it moves with the prefix).
Five version numbers stay separate:

| Axis | Carried by | Moves when |
|---|---|---|
| CloudEvents spec version | `specversion` (`1.0`) | CloudEvents publishes a new spec version we adopt. |
| Spec Kitty envelope schema version | `schemaversion` (`4.0.0` at first) | The attribute set, mapping, ordering or id rules change. Major = accept/reject boundary change. |
| Payload schema version | `type` suffix and `dataschema` | A payload model changes. |
| Package version | `pyproject.toml` | Any release; independent of the above, per the versioning contract. |
| Spec Kitty release line | not carried by an event | The core product's `4.x`/`5.0` line; informs the writer-timing question only (ADR open question 3a). |

The envelope schema (the context attributes) is itself a committed JSON Schema; the strict
profile gains a CloudEvents validator beside, not inside, today's `validate_strict_envelope`
until the cutover release (section 14, "Cutover").

## 9. Upcaster principles

Legacy rows are read through an upcaster in the shared reader library. These principles
bind its design; they are the decisions, not the contract.

- **No log rewrite.** Legacy rows stay in files as written, indefinitely; their fixtures
  are never retired.
- **Deterministic.** Same bytes in, same CloudEvent out. The output derives only from the
  row and a small caller context (the Mission id from `meta.json`). Nothing is invented: no
  minted `id`, no synthesized `time`, no defaulted field beyond the constants listed in
  sections 3 and 4.
- **Fail closed in strict use.** In strict use (ingress, and validating new rows before
  they are written) a row that cannot be mapped fails with a named error. Re-emission of
  rows already on disk, as in a merge, is not strict use.
- **Local read and merge never become narrower than today's reducer and driver.** The
  merge driver never rejects a row it accepts today; an unmappable legacy row is carried
  through byte-for-byte, keyed by its raw id. Anything the reducer decodes or ignores today is passed through permissively and unchanged, with a named
  warning where useful, so the existing decoder still reads it. This is the versioning
  contract's local-CLI compatibility; the cutover wording of `COMPATIBILITY.md` is not
  claimed before fixtures prove it.
- **The reducer's folding is not changed by this design.** A CloudEvent lane row is
  decoded back to the `StatusEvent` the reducer would decode from the legacy row; equal
  reduced state is the claim. Under ordering option A the reducer is unchanged. Options
  B and C change the `reduce_parsed` sort key (section 5); that change is owned by Phase 2,
  and the equal-state claim is then asserted per option there.
- **Merge driver (a core decision).** The log merge driver's fork detection and sort key
  must read the upcast form. Verified in the core repository (spec-kitty main 3847251e0):
  the driver requires a string `event_id` on every row
  (`src/specify_cli/status/event_log_merge.py:73-77`), so a CloudEvent row, which carries
  `id`, is rejected as written; it dedupes by `event_id` alone and raises on a same-id,
  different-content pair (`src/specify_cli/status/event_log_merge.py:165-174`); it sorts by
  `at` or `timestamp`, then `event_id` (`src/specify_cli/status/event_log_merge.py:176-182`);
  and it re-serializes every row with sorted keys
  (`src/specify_cli/status/event_log_merge.py:193`, `:213`). Fork detection
  (`detect_concurrent_fork`, called from `src/specify_cli/consolidation/drivers.py:246-250`)
  reads top-level `from_lane`, `to_lane` and `wp_id`
  (`src/specify_cli/status/event_log_merge.py:137-139`), which a CloudEvent row carries
  under `data` and `subject`; rows lacking them are skipped as non-transitions, so on
  CloudEvent rows the #5967 backstop would be blind. Two failure modes follow. With the
  driver unchanged, `detect_concurrent_fork` runs first and finds nothing, then the merge
  raises on the missing `event_id` and the driver reports a `MergeDriverError`
  (`src/specify_cli/consolidation/drivers.py:245-257`, spec-kitty main 3847251e0), so git
  leaves a conflict: fail-closed, but every log mixing CloudEvent rows conflicts. With a
  partially adopted driver, one that accepts `id` but whose fork detection still reads
  top-level `from_lane`, `to_lane` and `wp_id`, the merge completes and a concurrent cancel
  and advance merge cleanly: the backstop fails open. Whether, and how, the driver adopts
  the upcast form, and in which mode, is for core to decide. Rollout dependency: that core
  change, complete in both respects, ships before any writer emits CloudEvents into a log
  (section 14, Phase 5).

The row-level upcaster contract (detection, outcomes, error codes, fixtures) is specified and tested in the first implementation mission against the real reducer.

## 10. Worked example

A real shape-B row: line 2 of
`src/spec_kitty_events/conformance/fixtures/status_diary/replay/status_diary_fresh_mission.jsonl`
(a work package claim, `planned` to `claimed`):

```json
{"actor": "claude", "at": "2026-08-26T10:05:00+00:00", "event_id": "01JAAAA0000000000000000002", "evidence": null, "execution_mode": "worktree", "force": false, "from_lane": "planned", "mission_slug": "041-reducer-unification", "policy_metadata": {"agent": "claude", "shell_pid": 4242, "shell_pid_created_at": "2026-08-26T10:05:00+00:00"}, "reason": null, "review_ref": null, "to_lane": "claimed", "wp_id": "WP01"}
```

The row has no `mission_id`, so the caller supplies one from `meta.json`; the value
below, `01JAAAA00000000000000MSN01`, is invented for this example. The upcast result,
shown pretty-printed (the log line would be one line):

```json
{
  "specversion": "1.0",
  "id": "01JAAAA0000000000000000002",
  "source": "/spec-kitty/missions/01JAAAA00000000000000MSN01",
  "type": "ai.spec-kitty.lane.transitioned.v1",
  "subject": "WP01",
  "time": "2026-08-26T10:05:00+00:00",
  "datacontenttype": "application/json",
  "dataschema": "https://schemas.example.invalid/spec-kitty/lane.transitioned.v1",
  "schemaversion": "4.0.0",
  "missionid": "01JAAAA00000000000000MSN01",
  "missionslug": "041-reducer-unification",
  "actor": "claude",
  "executionmode": "worktree",
  "data": {
    "from_lane": "planned",
    "to_lane": "claimed",
    "force": false,
    "reason": null,
    "review_ref": null,
    "evidence": null,
    "policy_metadata": {
      "agent": "claude",
      "shell_pid": 4242,
      "shell_pid_created_at": "2026-08-26T10:05:00+00:00"
    }
  }
}
```

Attribute trace:

| Output | From | Rule |
|---|---|---|
| `specversion` | constant | `1.0` |
| `id` | `event_id` | ULID, already canonical upper-case |
| `source` | context `mission_id` | `/spec-kitty/missions/<missionid>` |
| `type`, `dataschema` | registry | lane-transition row |
| `subject` | `wp_id` | copied |
| `time` | `at` | copied as written |
| `schemaversion` | constant | `4.0.0` |
| `missionid` | caller context | row lacks `mission_id` |
| `missionslug` | `mission_slug` | copied |
| `actor`, `executionmode` | `actor`, `execution_mode` | copied (string actor) |
| `data.*` | `from_lane` ... `policy_metadata` | moved verbatim; `review_result` absent in the row, so absent here |

## 11. JSONL framing

What the spec says (JSON format, `cloudevents/formats/json-format.md`, `ce@v1.0.2`, checked):

- Structured mode "MUST use the media type `application/cloudevents+json`".
- A batch "MUST use the media type `application/cloudevents-batch+json`": "several
  CloudEvents are batched into a single JSON document" (a JSON array).
- JSON Lines or newline-delimited JSON is **not mentioned** in that document. One event
  per line is therefore our framing convention, not something the spec defines, and a
  JSONL file is not a "batch" in the spec's sense.

Our rules for `status.events.jsonl`:

- One structured-mode CloudEvent JSON object per line.
- UTF-8, LF line endings, no byte-order mark, no embedded raw newlines.
- Key order and separators: today's rows are written with sorted keys and Python's default
  separators. The new serializer's exact byte rules belong to the event-log library and
  are pinned by golden tests produced with the real serializer (the Windows byte-exact
  line-ending constraint, spec-kitty#4182, applies). This document does not fix them.
- A file may mix CloudEvent, legacy and pass-through lines.

## 12. Kafka and Spring notes

Verified against primary sources on 2026-10-13 (the Kafka binding at `ce@v1.0.2`; the Java
SDK and Spring pages as published that day).

**Kafka protocol binding** (`https://github.com/cloudevents/spec/blob/ce@v1.0.2/cloudevents/bindings/kafka-protocol-binding.md`):

- Binary mode: "CloudEvent attributes are prefixed with `ce_`" in Kafka headers.
- Structured mode: "If present, the Kafka message header property `content-type` MUST be set to the media
  type of an event format", here `application/cloudevents+json`. Our log line is already the value of
  such a message.
- "The Kafka protocol binding does not currently support the batch content mode." This
  sentence is on main, not in v1.0.2.
- Partitioning: implementations "SHOULD provide an opt-in 'Key Mapper'" that maps the
  `partitionkey` attribute to the record key. `partitionkey` is a documented extension
  (`extensions/partitioning.md`, present at v1.0.2; same "no official standing" caveat).
  Whether we define it (for example from `missionid`) is a design item, not decided.

**Java SDK** (`https://cloudevents.github.io/sdk-java/`):

- `cloudevents-kafka` is the "Implementation of Kafka Protocol Binding"; its page
  documents a `CloudEventSerializer` and `CloudEventDeserializer` and a
  `PartitionKeyExtensionInterceptor` that uses the `partitionkey` extension as the key
  (`https://cloudevents.github.io/sdk-java/kafka`).
- `cloudevents-spring` "integrates CloudEvent with Spring's APIs for MVC, WebFlux, and
  messaging" and provides a converter for a Spring Messaging application; its page says
  Spring Messaging is "a core part of the Spring Cloud Function and Spring Cloud Stream
  libraries" (`https://cloudevents.github.io/sdk-java/spring`).

**Spring** (`https://docs.spring.io/spring-integration/reference/cloudevents.html`):
Spring Integration has a CloudEvents module (`spring-integration-cloudevents`). Without an
event format it writes attributes to message headers with the prefix "default is `ce-`"
(binary mode); with an event format such as `application/cloudevents+json` it serializes
the event into the payload (structured mode). The prefix there is `ce-`, while the Kafka
binding says `ce_`.

**Unverified:**

- That Spring Cloud Stream itself ships first-class CloudEvents handling for Kafka
  binders. The Spring Cloud Stream reference page checked does not mention CloudEvents;
  the only link found is the `cloudevents-spring` statement that Spring Messaging
  underlies Spring Cloud Stream. The intent (Spring Cloud Stream usable at the API level)
  therefore depends on the `cloudevents-spring` converter, to be confirmed by a spike.
- Behaviour of these libraries with this package's extension names, and GraalVM native
  image compatibility of the Java SDK.

## 13. Versioning rule and how COMPATIBILITY.md will record it

When the cutover release lands (section 14, "Cutover"), the change is major on both axes
(ADR, Consequences). The `4.0.0` value is published in Phases 1-3 (schemas, upcaster output,
the Phase 3 validator) but those are non-normative: no consumer or producer gates on them,
and the strict profile only accepts `4.0.0` at 4b. That is what keeps Phases 1-3 additive
(minor) and makes 4b the accept/reject boundary change. Per `contracts/versioning-and-compatibility.md` the landing change
updates `CHANGELOG.md` (`### Breaking`), `COMPATIBILITY.md`, `pyproject.toml` and
`__version__`, every `*.schema.json`, and the conformance fixtures.

Planned `COMPATIBILITY.md` content (this document does not edit it):

- A new section above the then-current newest released section, titled with the package
  version being released and the summary "CloudEvents 1.0 envelope; on-wire envelope
  schema `3.0.0` to `4.0.0`", stating the drivers (the ADR), the producer and consumer
  migrations, and that legacy rows are read indefinitely and never rewritten.
- The introductory sentence "The on-wire envelope schema version is `3.0.0` ..." is
  updated in the same change.
- The `**Current package version**: ...` line is a single declaration pinned to
  `spec_kitty_events.__version__` by `tests/test_compatibility_doc.py`; it is changed once,
  by the release change, and the version is not restated in prose elsewhere.

## 14. Phased rollout

No implementation code ships with this ADR. Each phase has an exit criterion at decision
level; later phases are not started until the earlier one's exit holds.

| Phase | Work | Exit criterion |
|---|---|---|
| 0. Decide | ADR confirmed on its PR; open questions 1, 2, 3a, 3b and 4 ruled; the two embedded choices of section 4 confirmed; the proposals of the ADR's "Further choices" list (the `.vN` new-type rule and its versioning-contract replacement, the `(source, id)` dedupe key, the `lane.transitioned` type name, the lifted members) confirmed or changed; charter question answered. | Each open question, embedded choice and listed proposal has a recorded ruling. |
| 1. Schemas and registry | Committed JSON Schemas for the envelope attributes, each registered `data` type, and the registry table. Decide schemas-as-source versus Pydantic-generated (below). | Schema drift check passes; every `STRICT_EVENT_TYPES` entry and the diary rows are registered or listed as excluded with a reason. |
| 2. Upcaster | The upcaster and its fixtures, per the first implementation mission (section 9), with no reducer change under option A (a sort-key change under B or C is owned here). | Legacy fixtures give their expected result; equal reduced state on a round trip of each diary row (option A: unchanged reducer; under B or C: the state the chosen ordering defines, stated per option); conformance gate green. |
| 3. CloudEvents validator | Strict-profile validator for CloudEvents rows beside `validate_strict_envelope` (whose `3.0.0` pin is unchanged). | Validator green; additive (minor) release artifacts complete; `test_compatibility_doc` passes. The validator and the `4.0.0` schemas are non-normative until 4b: no consumer or producer is to gate on them. |
| 4. Consumers | Event-log library and Read API read through the upcaster; ingress stays on the pinned `3.0.0` rule until the 4b package release. The in-package Zeitgeist codec (`zeitgeist_attrs.py`) is settled: it stays on shape A or gains a CloudEvent input. | Downstream consumer tests green against the released package; reducing a diary and its upcast form gives the same snapshot. |
| 4b. Cutover release (the package release) | The package major: it adds the `4.0.0` CloudEvents profile and keeps `3.0.0` acceptance as a named legacy profile (the pin is two-valued: accepting `4.0.0` is the boundary change that makes this a major). saas ingress accepts both; the versioning-contract text "producers must emit `3.0.0`" is rewritten to allow either; the central CLI-SaaS contract is updated in the same change. The 4b release is gated on neither part of open question 3 (3a writer timing, 3b retirement of `3.0.0`). | Major-bump artifacts complete (section 13); both profiles accepted and each pinned by fixtures; contract text rewritten; saas ingress tests green. |
| 5. Writers (CLI releases, only after the 4b package release) | Precondition, owned by core: the merge driver release that accepts CloudEvent rows (an unchanged driver rejects them and conflicts), with fork detection and the sort key reading the upcast form (a partially adopted driver is blind to a concurrent fork), ships first. Then the first command group writes CloudEvents; a project schema bump stops older CLIs appending legacy rows. | Golden byte tests pass; a mixed old/new log merges without rejecting any row the current driver accepts; a log with CloudEvent rows merges with the released driver and does not conflict merely for lacking `event_id`; a concurrent cancel and advance on CloudEvent rows still conflicts; an older CLI refuses the upgraded project. |
| 6. Streaming | Kafka and Spring spike against the verified libraries. | A round trip of a log line through Kafka and a Spring consumer, recorded; unverified items in section 12 resolved or struck. |

**Cutover (the retirement step is ADR open question 3b; options, not decided).** The versioning contract
says producers must emit `3.0.0` and `validate_strict_envelope` requires
`_REQUIRED_SCHEMA_VERSION = "3.0.0"` (`strict.py`). Phases 2 to 4 are additive and change
neither. Two meanings of "release" are kept apart: the cutover release is a release of
this package (Phase 4b); the writer releases are releases of the CLI (Phase 5), which
come only after it. In 4b the package starts accepting `4.0.0` beside `3.0.0`; no live
`3.0.0` producer is rejected and no producer yet emits CloudEvents. What the options
decide is the later, separately announced **retirement step**, after Phase 5:

- A. Retire: once every producer has shipped Phase 5, a later package release stops
  accepting `3.0.0` (saas rejects shape A). That release is a second major by the
  accept/reject rule and needs its own explicit cutover decision.
- B. Keep: ingress accepts both indefinitely, or for a stated window; the named legacy
  `3.0.0` path stays and `3.0.0` is rejected only when a later release says so.

State the accept/reject boundary per option in the release notes (4b: widened; retirement
step: narrowed), so the major-bump rule stays traceable.

Either way the 4b release is declared in `CHANGELOG.md` `### Breaking` and
`COMPATIBILITY.md`, and the charter's "explicit cutover decision" is recorded when open
question 3a (writer timing) is ruled. Question 3a, the product-line mapping of when
writers switch, does not move 4b and is separate from the options above.

**Schemas as source versus Pydantic-generated (charter-level).** Today the schemas are
generated from the Pydantic models (`src/spec_kitty_events/schemas/generate.py`).
spec-kitty#6117 proposes the reverse, with Python and Java bindings generated from or
tested against the schemas. Phase 1 can proceed either way; the second direction
conflicts with the charter's "Python 3.10+ with Pydantic event models" wording and needs
the maintainer-reviewed amendment flagged in the ADR before Phase 1 begins in that form.

## 15. Risks

- **Two shapes remain readable forever.** The upcaster is permanent; mitigated by fixtures
  that never retire.
- **Ordering regression.** Choosing Lamport while writers stamp none leaves legacy rows
  unordered by clock; mitigated by the `(time, id)` fallback (option C, decided) and a
  reducer-equivalence test.
- **Name-rule drift and extension ambiguity.** A future underscore or over-long name
  violates the spec; reused documented-extension names (`correlationid`, `causationid`,
  `partitionkey`) have no official standing. Mitigated by a name-check test and by
  defining them in our own schemas.
- **Prefix baked in.** Every `type` and `dataschema` carries the `ai.spec-kitty` prefix
  (decided, ADR question 4); changing it later is a breaking change.
- **Merge-driver failure modes.** If writers emit CloudEvents before the core driver is
  adopted, an unchanged driver rejects the rows (`event_id` missing) and every mixed merge
  conflicts. If the driver is only partly adopted (accepts `id`, fork detection still reads
  top-level fields), fork detection is blind and a concurrent cancel and advance merge
  cleanly. Mitigated by the Phase 5 precondition and its exit criterion, which cover both.
- **Id collapse.** Identical legacy rows share an `id`; today's merge driver keys on
  `event_id` alone and raises on a same-id, different-content pair
  (`src/specify_cli/status/event_log_merge.py:165-174`, spec-kitty main 3847251e0).
- **Consumer lag.** Consumers on the previous major cannot read the new rows; mitigated by
  the project schema bump stopping older CLIs from appending.
- **Java binding drift.** A second implementation of the upcaster can diverge; mitigated
  by running the same fixture corpus in both.
- **Unverified Spring Cloud Stream behaviour** (section 12).
