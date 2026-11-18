# `ledger/` scenarios

How a record is appended to a local JSONL ledger: an accepted append adds exactly the line its
fixture pins, a refused append leaves the ledger byte-identical, a torn tail is refused, and NaN
is refused. Driven by `spec_kitty_events/conformance/fixtures/ledger_append/`, whose `README.md`
holds the normative rules (document keys, input kinds, byte rule, refusals); the reference write
path is `spec_kitty_events.persistence.append_event`. Bound in Python only: no JVM write-side
binding exists yet. Promises: [`PROMISES.md`](PROMISES.md).

| Behaviour | Covers |
|-----------|--------|
| `accepted-appends/` | Every document that pins acceptance adds exactly its `expected_line` after its `existing_ledger` bytes |
| `refused-appends/` | Every document that pins a refusal raises its `expected_error` class and leaves the ledger byte-identical, or still absent |
| `torn-tail/` | An append to a ledger whose last byte is not LF is refused with `LedgerTornTailError` |
| `nan-values/` | An append whose input carries NaN is refused, and nothing is written |

Each append runs against its own ledger file, seeded with the document's `existing_ledger`, through
the test-only `tests/support/ledger_append.py`. The packaged runner
`spec_kitty_events.conformance.test_ledger_append` stays the gate other repos consume.

## Available steps

The class the steps name is the manifest `fixture_type` `ledger_append`. Step texts shared with
`envelope/` keep their wording: here "the error code" a fixture pins is its `expected_error`
refusal class.

| Group | Step |
|-------|------|
| Arrange | `Given the conformance fixtures shipped with the package` |
| Arrange | `Given every fixture of the class "<fixture_class>" that pins acceptance` |
| Arrange | `Given every fixture of the class "<fixture_class>" that pins a refusal` |
| Arrange | `Given every fixture of the class "<fixture_class>" that pins the error code "<code>"` |
| Arrange | `Given every fixture of the class "<fixture_class>" that sets a field to NaN` |
| Act | `When each fixture's input is appended to its ledger` |
| Assert | `Then every fixture is accepted` |
| Assert | `Then every fixture is refused with the error code it pins` |
| Assert | `Then every ledger holds its prior bytes followed by exactly the line its fixture pins` |
| Assert | `Then every ledger is left byte-identical to its prior state` |

Every "every fixture of the class" step fails when its selection is empty, so a scenario never
passes on an empty selection.
