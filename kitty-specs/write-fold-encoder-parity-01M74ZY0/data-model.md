# Data Model: Write-fold and reference-encoder parity (M2)

## TransitionId (Java, write fold)
- `Identified(raw, spelling)`: the row's `event_id` as decoded JSON (`raw`, compared with Python
  equality through `EventIds`) and its spelling for id-keyed maps.
- `Absent`: the row has no `event_id` or a JSON `null`. Built only by its own factory.
- Invariants: `Absent` never equals anything (not even another `Absent`) for setter lookup or
  replay; a setter lookup by `Absent` returns nothing. A string id spelled `"None"` is
  `Identified`, never `Absent`.

## Fold state slot `last_event_id`
- Holds the setter's `TransitionId` (was its spelling). Annotation rows still reset it to "no
  setter".

## Ack-bytes expectation (test resource)
`service/command-cli/src/test/resources/conformance/ack-bytes.json`:
`{ "<case id>": ["<unit 0 appended text>", ...] }` — one string per expected unit, the exact text
appended (LF-terminated lines, ASCII), `""` for a replayed unit. Keyed and ordered by case id.

## Fold differential case (test resource)
`service/command-cli/src/test/resources/fold-differential/<id>.json`:
`{ "id", "notes", "log": [row...], "expected": { "<wp_id>": "<lane>" } }`; `expected` is the
package reducer's result.

## Encoder refusals (Python)
`StatusRowRefused(ValueError)` ← `NonFiniteNumber`, `SessionTimesNotSubtractable`,
`IntegerTooLarge` (new), `NonStringKey` (new).

## Edge graph
`frozenset[tuple[Lane | None, Lane]]` equal to core's unforced edges plus the package's
`(None, planned)` initial edge.
