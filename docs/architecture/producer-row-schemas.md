---
title: Producer row schemas and the total order
description: Design for the per-row-kind data schemas, the producer registry, the conformance fixtures and the total-order comparator (spec-kitty#6185). First slice of #92.
doc_status: draft
updated: 2026-10-14
type: Architecture
kind: Explanation
---

# Producer row schemas and the total order

**Status:** Proposed. This note supports
[ADR-0001](../adr/0001-cloudevents-envelope.md), which is Accepted (2026-10-15), and
[the envelope design](./cloudevents-envelope.md). The ADR decided the `ai.spec-kitty`
prefix, ordering option C and ULID-only new ids on maintainer approval. The `source`
tie-break and the interim data-model choices come from the spec-kitty operator for
spec-kitty/spec-kitty#6185 and still await maintainer confirmation. Nothing here changes what any existing release accepts.

**Reader:** the maintainer reviewing the PR for spec-kitty-events#92 step 2, and the
author of the Java producer in spec-kitty#6185, which consumes the released artifact.

## 1. What this PR carries, and what it leaves to #92

#6185 needs a released package that holds the one schema set its producer writes against.
#92 says the same set must be shared, not duplicated. This PR is the part of #92's scope
that #6185 needs first. It carries:

| Piece | Where |
|---|---|
| One `data` JSON Schema per emitted row kind (28 types, all `v1`) | `src/spec_kitty_events/producer_contract/data/<name>.schema.json` |
| The registry: type, scope, `subject` rule, lifted members, legacy shape, schema file, `dataschema` URI | `src/spec_kitty_events/producer_contract/registry.json` |
| The minimal envelope subset (attribute rules for rows the producer writes) | `src/spec_kitty_events/producer_contract/envelope.schema.json` |
| Python loaders, and the two composed schemas (full CloudEvent row; legacy-row form) | `src/spec_kitty_events/producer_contract/__init__.py` |
| Producer conformance fixtures: valid CloudEvents with their legacy rows, invalid CloudEvents, invalid legacy rows | `src/spec_kitty_events/producer_contract/fixtures/rows/{valid,invalid,legacy_invalid}/` |
| The total-order comparator: contract, Python implementation, thirteen fixtures (the nine worked examples and four edge cases) | `contracts/total-order.md`, `src/spec_kitty_events/total_order.py`, `src/spec_kitty_events/producer_contract/fixtures/total_order/` |
| Packaged conformance tests (run by `pytest --pyargs spec_kitty_events.conformance`), in-repo tests, `COMPATIBILITY.md` and `CHANGELOG.md` entries | `src/spec_kitty_events/conformance/test_total_order.py`, `.../test_producer_contract.py`, `tests/`, repo root |

It does not carry, and #92 keeps: the envelope validator and upcaster for legacy rows, the
legacy fixture corpus, the full `STRICT_EVENT_TYPES` registry, the Java binding, the
reducer sort-key switch and the major release. `spec_kitty_events.Lane` already has the
ten members the producer's lane schema needs (`genesis` through `canceled`); the PR adds
a test that pins the two together and changes no enum.

The envelope subset is the "conditional" item in #6185's scope: #92's envelope schema has
not landed, the producer must validate against attribute rules, so a strict subset of the
design (section 2 of the envelope design) ships here, marked non-normative until #92
absorbs it.

## 2. Decisions this PR records

All are inputs from #6185, not choices made here. Items marked INTERIM are the ADR's
"further choices", adopted by the operator and awaiting maintainer confirmation.

- Type string: `ai.spec-kitty.<name>.v<N>`. The prefix is decided by ADR 0001 question 4
  (maintainer, 2026-10-15). Registry key `type_prefix` holds it once; changing it later is breaking.
- `dataschema` base: the design's placeholder `https://schemas.example.invalid/spec-kitty/`
  is kept, in one registry field (`dataschema_base`), because the maintainer has to name a
  domain the project controls (not part of the ADR 0001 prefix decision). The URI is an identifier, not a
  fetch target. INTERIM.
- `schemaversion`: `4.0.0`, the CloudEvents-form value of the design. Non-normative until
  the cutover release; `validate_strict_envelope` still requires `3.0.0`.
- `data` members are snake_case and moved verbatim from the persisted row; members lifted
  to context attributes leave `data` (the registry's `lifted` list). `actor` and
  `executionmode` are context attributes. A structured actor keeps its object in
  `data.actorbinding`. INTERIM.
- New-type versioning (`.vN`): this PR adds `v1` only. INTERIM.
- Ids: a producer writes a 26-character upper-case ULID only. The comparator normalises
  legacy UUID spellings with `normalize_event_id`, so readers keep accepting them.
- Ordering option C, with the total order of section 4.

## 3. Schema and registry shape

One schema file per type, JSON Schema 2020-12, `additionalProperties: false`, no `$ref`
and no `$defs` (a consumer in any language loads one file and nothing else; a test pins
that). A `data` member that the persisted row can hold as `null` allows `null`. The schema
is the contract for non-Python producers; for the 19 types that already have a typed
payload model in this package, a test pins the schema's member set to that model's, so the
two cannot drift apart silently.

A registry entry:

```json
{
  "type": "ai.spec-kitty.mission.created.v1",
  "name": "mission.created.v1",
  "dataschema": "https://schemas.example.invalid/spec-kitty/mission.created.v1",
  "schema": "data/mission.created.v1.schema.json",
  "scope": "mission",
  "subject": "none",
  "legacy": { "shape": "lifecycle_envelope", "discriminator_value": "MissionCreated" },
  "lifted": [ { "member": "event_id", "attribute": "id" }, { "member": "timestamp", "attribute": "time" }, { "member": "event_type", "attribute": "type" } ]
}
```

- `scope`: `mission` or `project`. `subject`: `wp_id` (a work package id is required) or
  `none` (absent on a Mission-scoped row; on the project-scoped fallback source of
  OD-MISSIONID it is the aggregate; a `project`-scope type never carries one).
- `legacy.shape` names one of the `legacy_shapes` entries — `flat`, `lifecycle_envelope`,
  `decision_envelope` or `journal_envelope` — and each entry declares a `form` that drives
  composition. `form: flat` (lane, annotation, retrospective rows) keeps the data members
  and the lifted members in one object; `form: payload_envelope` (lifecycle, decision and
  run-journal rows) puts `data` under the shape's payload member and lifts the other row
  members. `lifted` names each legacy member and the attribute it becomes.
- The legacy-row form of a type is generated, never authored: `legacy_row_form_schema()`
  takes the data schema and the registry's lifted list and returns the schema a persisted
  line of that type must satisfy. This is the single source FR-032 asks for.

## 4. The total order

Rows are CloudEvents attribute dictionaries (the upcast form). The tie-break bytes of a
group are ASCII-escaped, sorted-key, compact JSON text. For a row `r`:

```
key(r) = ( h(r), c(r), t(r), i(r), s(r) )
  h = 1 if the row has a lamportclock member else 0     c = lamportclock, else 0
  t = time as a UTC instant (exact, no rounding)        i = normalised id (code points)
  s = source ("" if absent or null), by code points
```

Sort ascending, lexicographically. Clockless rows come first, then clocked rows by clock,
then time, then id, then source. The order is a strict total order on the rows that
survive dedupe because dedupe keys on `(s, i)`, so no two survivors share a key.

Before sorting, rows that cannot be keyed are set aside as error rows, never dropped and
never sorted: `time_unparseable` (absent, empty, not RFC 3339), `clock_out_of_range` (the
member is present and is not an integer in 0..2^31-1; `null`, strings, booleans and
numbers written with a decimal point or exponent all count), `id_missing`,
`id_not_normalizable`, and `source_invalid` (a present, non-null source that is not a
string; the one class this note adds, because the design is silent). Dedupe then runs on
the valid rows: group by `(s, i)`, keep the row whose compact sorted-key UTF-8 serialisation
has the smallest bytes, count every other row of the group as collapsed, and report the
group as a conflict when its rows differ in any member other than `id`. The result
satisfies `len(ordered) + len(errors) + collapsed == rows read`.

**Recommendation on the `source` tie-break (the point #6185 asks this PR to confirm):**
keep `s` last in the key, compared by code point, with an absent source as `""`. Reason:
CloudEvents makes `(source, id)` the identity of an event, so two events may share an id
across sources, and a total order must break that tie. Placing `s` last leaves the
operator's ruled order (Lamport, time, id) exactly as ruled and uses `s` only when
everything else is equal. `nodeid` is not needed in the key: `(nodeid, lamportclock)` is
unique per producer and equal clocks on different nodes are still separated by `time` and
`id`. One consequence to state to the maintainer: a legacy row with no source and the same
event re-written later as a sourced CloudEvent are two keys, so they do not collapse. That
is #92's upcaster to resolve (the upcaster gives legacy rows their source).

## 5. Release classification

Additive: new modules, data files and fixtures; no existing accept/reject boundary moves.
Minor bump, `10.4.6` to `10.5.0`. `COMPATIBILITY.md` gets a `10.5.0` section; `CHANGELOG.md`
gets `[10.5.0]` under Added. The schemas are published ahead of the cutover and are
non-normative until the major release of #92; emission of these types stays gated by the
consumer, as the versioning contract already says for new event types.

## 5a. Notes on the envelope subset and the schemas

- The producer subset of the envelope requires `lamportclock` and `nodeid` (the design says
  "optional in the schema, because diary writers stamp none today"). The producer stamps
  both on every row it writes; #92 reconciles this when it absorbs the subset. The total
  order still defines clockless rows, which come from legacy and upcast rows.
- Eight of the 28 data schemas (lane, annotation, reviewer self-approval, significance,
  denied, the three retrospective rows) have no typed model in this package to compare
  with. Their member and required lists are pinned by hand-written lists in the tests.
- The lane schema carries the members of the diary row only. The design's shape-A `summary`
  member is left to #92, which can add it to `lane.transitioned.v1` before the cutover.
- A run-journal legacy line validates only after the write-time stamp (`event_id`,
  `row_version`) that #6185 adds to the existing writer.
- `contracts/total-order.md` is also shipped in the package, next to the schemas.

## 6. Open maintainer confirmations (carried to the PR body)

1. ADR 0001 is Accepted (2026-10-15). The events charter amendment that lets a JSON-schema
   contract carry a non-Python binding beside the Python one is #6117 decision 11
   (spec-kitty-events#106, unblocking #92). The charter says "Python library with Pydantic
   event models, committed schemas generated by Pydantic". These schemas are authored as
   JSON and are the contract for non-Python producers, the second reading in the ADR's
   "Charter" section.
2. The `source` tie-break and the INTERIM list of section 2 come from the spec-kitty
   operator, not from this repository's maintainer. The prefix, ordering option C and
   ULID-only ids are decided by ADR 0001.
3. The `dataschema` base URI is a placeholder until a controlled domain is named.
4. `source_invalid` and the treatment of `null` source as absent are this PR's reading of
   a gap in the design.
