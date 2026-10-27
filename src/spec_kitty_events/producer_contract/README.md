# Producer contract

The language-neutral contract a Spec Kitty event producer writes against
(spec-kitty/spec-kitty#6185, spec-kitty-events#92). Non-normative until the ADR 0001
cutover release. Everything a non-Python consumer needs is a file in this directory;
nothing has to be re-derived from Python code.

| Path | What it is |
|---|---|
| `registry.json` | One entry per emitted type, plus `legacy_shapes`, plus `deferred`. |
| `envelope.schema.json` | The write profile: attribute rules for a CloudEvents row a producer writes. |
| `envelope.read.schema.json` | The read profile: rows a reader meets, including upcast legacy rows (clock optional, legacy UUID ids, the 3.0.0 extensions). |
| `data/<name>.schema.json` | The `data` schema of one type. Closed, self-contained. |
| `composed/rows/<name>.row.schema.json` | The whole CloudEvents line of one type: envelope, pinned `type` and `dataschema`, `data`, and the scope, `subject` and `missionid` rules. Use these; there is nothing to compose. |
| `composed/read/<name>.row.schema.json` | The same, in the read profile. Validate upcast rows against these. |
| `composed/legacy/<name>.legacy.schema.json` | The persisted legacy line of one type (data schema plus lifted members, or the legacy envelope around it). |
| `fixtures/rows/{valid,invalid,legacy_invalid}/` | Conformance lines. Each invalid fixture names the JSON path and the schema keyword it must fail on. Every type has a valid fixture, an unknown-member fixture and (where it has required members) a missing-member fixture, in both forms. |
| `fixtures/total_order/` | The thirteen total-order cases. |
| `fixtures/upcast/cases/<group>/<case>.json` | The upcaster corpus: one legacy log line, its context, and the expected outcome, issue codes and golden CloudEvent. Every binding runs it. |
| `total-order.md` | The ordering contract (a copy of `contracts/total-order.md`). |

The composed schemas are generated from the other files by
`spec_kitty_events.producer_contract.row_schema` and `legacy_row_form_schema`; a test
fails if a committed file differs from what those functions return.

## Registry keys

Top level: `type_prefix`, `dataschema_base`, `schemaversion`, `specversion`,
`datacontenttype`, `envelope_schema`, `legacy_shapes`, `types`, `deferred`. Read `type`,
`dataschema` and `schemaversion` from the entry; do not rebuild them from the base,
because the prefix and base are interim and may change before the cutover.

Entry: `type`, `name`, `dataschema` (equal to the schema's `$id`), `schema`, `scope`
(`mission` or `project`), `subject` (`wp_id`: required, the work package id; `none`:
absent on a mission source, and the aggregate on the project-scoped fallback source),
`required_attributes` (attributes required beyond the envelope's), `legacy`, `lifted`.

`legacy.shape` names an entry of `legacy_shapes`. Form `flat`: the legacy row is one
object holding the data members, the lifted members (`lifted[].legacy_schema`,
`lifted[].required`) and `legacy.constants`; a lifted member with `binding_member` replaces
that data member (the structured actor). Form `payload_envelope`: the legacy row is the
shape's `members` plus a `payload` member that is the data; the discriminator member must
equal `legacy.discriminator_value`.

## Attribute sources and projections

`lifted[].attribute` is the attribute a legacy member becomes. Attributes no legacy member
becomes come from the producer's context: `source` is `/spec-kitty/missions/<missionid>`
(or `/spec-kitty/projects/<project_uuid>` for a project-scoped type or when no mission id
is known), `missionid` and `missionslug` from the row, else the Mission's `meta.json`,
`lamportclock` and `nodeid` from the producer. When no mission id is known anywhere the
row takes the project-scoped source, carries no `missionid`, and its `subject` is the
aggregate.

Projection `actor_identity` (lane and annotation rows): a string actor is copied; a
structured actor becomes its `tool`, else its `role`; an empty result omits the attribute.
Projection `retrospective_actor_id`: the actor's `id`. In both, `data.actorbinding` is
present only for an object actor and equals the row's actor unchanged.

Rules JSON Schema cannot state, which the producer enforces: `missionid` equals the id in
`source`; `subject` equals the work package id in `data` where it has one.

## Validation notes

- `format` is an annotation in JSON Schema 2020-12 and many validators ignore it. Every
  `date-time` here also carries an RFC 3339 `pattern`, so a validator that ignores `format`
  still rejects a non-timestamp. The pattern checks the shape, not calendar validity.
- Patterns end with `(?![\s\S])` instead of `$`, because `$` accepts a trailing newline in
  Python and Java.
- `type: integer` accepts `5.0` under 2020-12; the ordering contract does not. A producer
  writes `lamportclock` as an integer literal.
- Adding a context attribute to the envelope is a schema release: the envelope is closed.
