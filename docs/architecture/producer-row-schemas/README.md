---
title: Producer row schemas and the total order
description: The per-row-kind data schemas (36 types), the producer registry, the conformance fixtures and the total-order comparator — accepted, and shipped since 11.3.0.
doc_status: active
updated: 2026-11-03T00:00:00Z
type: Architecture
kind: Hub
okf_version: "0.2"
related:
  - architecture/cloudevents-envelope
  - architecture/ordering-and-reduction
  - adr/0001-cloudevents-envelope
---

# Producer row schemas and the total order

**Status:** Accepted and shipped. What this note specified has been released since
`11.3.0` (see the blockquote below); the schemas it describes are the shipped contract,
each with a generated schema page in the list this page stands on. The note records
[ADR-0001](/adr/0001-cloudevents-envelope/) (Accepted 2026-10-15) and
[the envelope design](/architecture/cloudevents-envelope/): the `ai.spec-kitty` prefix,
ordering option C, ULID-only new ids, the `source` tie-break and the interim data-model
choices from spec-kitty/spec-kitty#6185.

**Reader:** anyone consuming or contributing to the producer row contract, and the
author of the Java producer in spec-kitty#6185, which consumes the released artifact.

> **Status after #92 (release `11.3.0`).** The INTERIM choices below are confirmed (ADR 0001,
> 2026-10-27); the `dataschema` base is `urn:ai.spec-kitty:schema:`; the envelope subset is the
> write profile beside a read profile; the registry covers every strict type; the upcaster,
> its corpus and a Java binding have shipped (`docs/architecture/cloudevents-envelope.md`,
> section 9). This document keeps the record of the `10.5.0` slice.

## 1. What the slice carries

#6185 needed a released package that holds the one schema set its producer writes
against; #92 says the same set must be shared, not duplicated. The slice carries:

| Piece | Where |
|---|---|
| One `data` JSON Schema per emitted row kind (36 types, all `v1`) | `src/spec_kitty_events/producer_contract/data/<name>.schema.json` |
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
absorbs it. Its generated page is the [write profile](/architecture/producer-row-schemas/producer-envelope-write/),
beside the [read profile](/architecture/producer-row-schemas/producer-envelope-read/).

## 2. Decisions the slice records

All are inputs from #6185. Items marked INTERIM are the ADR's "further
choices", adopted by the operator and confirmed on 2026-10-27.

- Type string: `ai.spec-kitty.<name>.v<N>`. The prefix is decided by ADR 0001 question 4
  (maintainer, 2026-10-15). Registry key `type_prefix` holds it once; changing it later is breaking.
- `dataschema` base: the design's placeholder `https://schemas.example.invalid/spec-kitty/`
  is kept, in one registry field (`dataschema_base`). The URI is an identifier, not a
  fetch target.
- `schemaversion`: `4.0.0`, the CloudEvents-form value of the design. Non-normative until
  the cutover release; `validate_strict_envelope` still requires `3.0.0`.
- `data` members are snake_case and moved verbatim from the persisted row; members lifted
  to context attributes leave `data` (the registry's `lifted` list). `actor` and
  `executionmode` are context attributes. A structured actor keeps its object in
  `data.actorbinding`.
  INTERIM at the time, confirmed with the rest.
- New-type versioning (`.vN`): this slice adds `v1` only.
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
never sorted: `time_unparseable` (absent, empty or not RFC 3339), `clock_out_of_range` (the
member is present and is not an integer in 0..2^31-1; `null`, strings, booleans and
numbers written with a decimal point or exponent all count), `id_missing`,
`id_not_normalizable`, and `source_invalid` (a present, non-null source that is not a
string; the one class this note adds, because the design is silent). Dedupe then runs on
the valid rows: group by `(s, i)`, keep the row whose compact sorted-key UTF-8 serialisation
has the smallest bytes, count every other row of the group as collapsed, and report the
group as a conflict when its rows differ in any member other than `id`. The result
satisfies `len(ordered) + len(errors) + collapsed == rows read`.

## 5. Release classification (historical record of the `10.5.0` slice)

Additive: new modules, data files and fixtures; no existing accept/reject boundary moves.
Minor bump, `10.4.6` to `10.5.0`. `COMPATIBILITY.md` and `CHANGELOG.md` each carry the
slice's entries. The schemas were published ahead of the cutover and remain
non-normative until the major release of #92; emission of these types stays gated by the
consumer, as the versioning contract already says for new event types.

## 5a. Notes on the envelope subset and the schemas

- The producer subset of the envelope requires `lamportclock` and `nodeid` (the design says
  "optional in the schema, because diary writers stamp none today"). The producer stamps
  both on every row it writes; this is reconciled when #92 absorbs the subset. The total
  order still defines clockless rows, which come from legacy and upcast rows.
- Eight of the 36 data schemas (lane, annotation, reviewer self-approval, significance,
  denied, the three retrospective rows) have no typed model in this package to compare
  with. Their member and required lists are pinned by hand-written lists in the tests.
- The lane schema carries the members of the diary row only. The design's shape-A `summary`
  member is left to #92, which can add it to `lane.transitioned.v1` before the cutover.
- A run-journal legacy line validates only after the write-time stamp (`event_id`,
  `row_version`) that #6185 added to the existing writer.
- `contracts/total-order.md` is also shipped in the package, next to the schemas.

<!-- BEGIN GENERATED: schemas -->
## Schemas

- [Schema: decision.opened v1 (producer row)](/architecture/producer-row-schemas/decision.opened.v1/) — A planning-interview decision point was opened.
- [Schema: decision.resolved v1 (producer row)](/architecture/producer-row-schemas/decision.resolved.v1/) — A planning-interview decision point reached a terminal outcome (resolved, deferred or canceled).
- [Schema: harness.observed v1 (producer row)](/architecture/producer-row-schemas/harness.observed.v1/) — A coding-harness session reported an observation.
- [Schema: lane.transitioned v1 (producer row)](/architecture/producer-row-schemas/lane.transitioned.v1/) — Lane transition of a work package.
- [Schema: mission.cancelled v1 (producer row)](/architecture/producer-row-schemas/mission.cancelled.v1/) — A Mission was cancelled.
- [Schema: mission.closed v1 (producer row)](/architecture/producer-row-schemas/mission.closed.v1/) — A Mission was closed.
- [Schema: mission.completed v1 (producer row)](/architecture/producer-row-schemas/mission.completed.v1/) — A Mission was completed.
- [Schema: mission.created v1 (producer row)](/architecture/producer-row-schemas/mission.created.v1/) — A Mission was created.
- [Schema: mission.followup.recorded v1 (producer row)](/architecture/producer-row-schemas/mission.followup.recorded.v1/) — A follow-up commit or pull request was recorded against a Mission.
- [Schema: mission.phase.entered v1 (producer row)](/architecture/producer-row-schemas/mission.phase.entered.v1/) — A Mission entered a phase.
- [Schema: mission.plan.completed v1 (producer row)](/architecture/producer-row-schemas/mission.plan.completed.v1/) — The plan phase of a Mission completed.
- [Schema: mission.plan.started v1 (producer row)](/architecture/producer-row-schemas/mission.plan.started.v1/) — The plan phase of a Mission started.
- [Schema: mission.reopened v1 (producer row)](/architecture/producer-row-schemas/mission.reopened.v1/) — A merged or closed Mission was returned to an actionable state.
- [Schema: mission.specify.completed v1 (producer row)](/architecture/producer-row-schemas/mission.specify.completed.v1/) — The specify phase of a Mission completed.
- [Schema: mission.specify.started v1 (producer row)](/architecture/producer-row-schemas/mission.specify.started.v1/) — The specify phase of a Mission started.
- [Schema: mission.started v1 (producer row)](/architecture/producer-row-schemas/mission.started.v1/) — A Mission was started.
- [Schema: mission.tasks.completed v1 (producer row)](/architecture/producer-row-schemas/mission.tasks.completed.v1/) — The tasks phase of a Mission completed.
- [Schema: mission.tasks.started v1 (producer row)](/architecture/producer-row-schemas/mission.tasks.started.v1/) — The tasks phase of a Mission started.
- [Schema: producer envelope (read profile)](/architecture/producer-row-schemas/producer-envelope-read/) — Attribute rules for a CloudEvents row as a reader may meet it, including the upcast form of a legacy row (ADR 0001, read profile): the id may be a normalized legacy UUID
- [Schema: producer envelope (write profile)](/architecture/producer-row-schemas/producer-envelope-write/) — Attribute rules for a CloudEvents 1.0 structured-JSON row a Spec Kitty producer writes (ADR 0001, write profile): upper-case ULID id, lamportclock and nodeid required.
- [Schema: project.initialized v1 (producer row)](/architecture/producer-row-schemas/project.initialized.v1/) — A project was initialized.
- [Schema: retrospective.captured v1 (producer row)](/architecture/producer-row-schemas/retrospective.captured.v1/) — A retrospective was generated and a record is on disk.
- [Schema: retrospective.capturefailed v1 (producer row)](/architecture/producer-row-schemas/retrospective.capturefailed.v1/) — Retrospective generation failed under a warn failure policy.
- [Schema: retrospective.skipped v1 (producer row)](/architecture/producer-row-schemas/retrospective.skipped.v1/) — The strict retrospective gate was bypassed.
- [Schema: run.completed v1 (producer row)](/architecture/producer-row-schemas/run.completed.v1/) — A mission run completed.
- [Schema: run.decision.answered v1 (producer row)](/architecture/producer-row-schemas/run.decision.answered.v1/) — A requested decision was answered.
- [Schema: run.decision.denied v1 (producer row)](/architecture/producer-row-schemas/run.decision.denied.v1/) — An actor was denied authority over a decision.
- [Schema: run.decision.requested v1 (producer row)](/architecture/producer-row-schemas/run.decision.requested.v1/) — A run requested decision input.
- [Schema: run.decision.timeoutexpired v1 (producer row)](/architecture/producer-row-schemas/run.decision.timeoutexpired.v1/) — A decision's timeout expired without an answer and was escalated.
- [Schema: run.significance.evaluated v1 (producer row)](/architecture/producer-row-schemas/run.significance.evaluated.v1/) — The significance of a decision was scored and routed.
- [Schema: run.started v1 (producer row)](/architecture/producer-row-schemas/run.started.v1/) — A mission run started.
- [Schema: run.step.autocompleted v1 (producer row)](/architecture/producer-row-schemas/run.step.autocompleted.v1/) — The runtime auto-completed a step.
- [Schema: run.step.issued v1 (producer row)](/architecture/producer-row-schemas/run.step.issued.v1/) — The runtime issued a step.
- [Schema: work.observed v1 (producer row)](/architecture/producer-row-schemas/work.observed.v1/) — A unit of agent work was observed.
- [Schema: wp.created v1 (producer row)](/architecture/producer-row-schemas/wp.created.v1/) — A work package was created.
- [Schema: wp.innerstate.changed v1 (producer row)](/architecture/producer-row-schemas/wp.innerstate.changed.v1/) — Off-axis runtime-state annotation of a work package.
- [Schema: wp.review.rolledback v1 (producer row)](/architecture/producer-row-schemas/wp.review.rolledback.v1/) — A review rolled a Mission back to an earlier phase.
- [Schema: wp.reviewer.selfapproved v1 (producer row)](/architecture/producer-row-schemas/wp.reviewer.selfapproved.v1/) — A work package was approved by its implementing actor after the intended reviewer failed.
<!-- END GENERATED: schemas -->
