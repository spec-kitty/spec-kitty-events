# Data model

## Registry entry (additions)

| Field | Type | Meaning |
|---|---|---|
| `dataschema` | URN | `urn:ai.spec-kitty:schema:<name>` |
| `legacy` | object or null | CLI legacy form (unchanged); `null` for transport-only types |
| `transport` | object or null | shape A mapping: `event_type`, `lifted` (`member` → `attribute`, optional `projection`, `binding_member`), `lane_aliases` |

Top level: `dataschema_base` becomes `urn:ai.spec-kitty:schema:`; `envelope_schemas` =
`{"write": "envelope.schema.json", "read": "envelope.read.schema.json"}` (the old
`envelope_schema` key stays, naming the write profile); `legacy_shapes.transport_envelope`
documents shape A.

## Envelope profiles

| Attribute | Write | Read |
|---|---|---|
| `id` | upper ULID | upper ULID or lower hyphenated UUID |
| `lamportclock`, `nodeid` | required (nodeid 12 hex) | optional (nodeid non-empty string) |
| `buildid`, `causationid`, `correlationid`, `projectuuid`, `projectslug`, `datatier` | not allowed | optional |
| everything else | as today | as write |

## UpcastResult

`outcome` (`mapped` | `passthrough`), `row`, `form` (detected form or `none`), `original`
(the input value), `original_line` (bytes, when read from a line), `issues` (tuple of
`UpcastIssue(code, message)`).

## UpcastContext

`missionid`, `missionslug`, `projectuuid`: optional strings.

## Corpus case (`fixtures/upcast/<group>/<case>.json`)

`{"case", "notes", "context", "mode", "line" | "row", "expected": {"outcome", "form",
"codes", "golden"}}` where `golden` is the canonical CloudEvent string for `mapped` cases.
