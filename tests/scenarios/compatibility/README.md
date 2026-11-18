# `compatibility/` scenarios

The 8.0.0 cutover boundary (`COMPATIBILITY.md`, section `8.0.0`): the plain validator
`validate_event` accepts the envelopes the removed cutover gate used to refuse, and the strict
journal profile refuses them, with the codes their fixtures pin. Driven by
`spec_kitty_events/conformance/fixtures/cutover_boundary/` and the fixtures' `manifest.json` rows.
Bound in Python only: the Java binding validates CloudEvents rows, not these legacy envelopes, so
there is no JVM counterpart to bind. Promises: [`PROMISES.md`](PROMISES.md).

| Behaviour | Covers |
|-----------|--------|
| `accepted-by-validate-event/` | Every `accepted_by_validate_event` fixture is accepted by `validate_event`, as its manifest row pins |
| `rejected-by-strict-profile/` | Every `rejected_by_strict_profile` fixture is refused by the strict journal profile with exactly the codes it pins |

The listing, with each fixture's manifest row, is the test-only
`tests/support/cutover_boundary.py`; a fixture without a manifest row fails the listing.

## Available steps

| Group | Step |
|-------|------|
| Arrange | `Given the cutover-boundary fixtures shipped with the package` |
| Arrange | `Given every cutover-boundary fixture under "<directory>"` |
| Act | `When each fixture's envelope is validated as the event type its manifest row names` |
| Act | `When each fixture's input is judged against the strict journal profile` |
| Assert | `Then every fixture is accepted, as its manifest row pins` |
| Assert | `Then every fixture is refused with exactly the error codes it pins` |

"Every cutover-boundary fixture under" fails when the directory holds no fixture, so a scenario
never passes on an empty selection.
