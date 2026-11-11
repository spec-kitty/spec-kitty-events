# Data Model: Write-fold and reference-encoder parity (M2)

## TransitionId (Java, write fold)
- `Identified(raw, spelling)`: the row's `event_id` as decoded JSON (`raw`, compared with Python
  equality through `EventIds`) and its spelling for id-keyed maps.
- `Absent`: the row has no `event_id` or a JSON `null`. Built only by its own factory.
- Invariants, each mirroring core's Python (`diary.py` keys its dedupe groups and its ordering
  carries by the raw `event_id`, so `None` is one ordinary dict key):
  - **Keys**: `Absent` is a value equal only to itself, usable as a map key. Two id-less rows fall
    into one dedupe group (core keeps the first, or the canonical-minimum seed), exactly like
    `groups.setdefault(None, ...)`.
  - **Setter lookup**: looking up a setter by `Absent` returns nothing (core:
    `_find_event_by_id(None, ...) -> None`), so an id-less setter is no setter.
  - **Ordering**: `Absent` against `Absent` compares equal; against an identified id it is
    unorderable (core raises `TypeError`, the log is unreducible), as `EventIds.compare` already
    does.
  - **Spelling**: a string id spelled `"None"` is `Identified` and is a different key from
    `Absent`. Maps keyed by spelling must not conflate them; the "ids that spell alike" refusal
    must not fire for `"None"` next to `Absent`, since core keeps them apart.

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
