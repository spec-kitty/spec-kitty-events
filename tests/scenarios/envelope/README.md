# `envelope/` scenarios

How an event envelope is judged: accepted when canonical, refused with the code its fixture pins
when invalid, refused when its event type is not registered, and how a legacy lane resolves.
Driven by `spec_kitty_events/conformance/fixtures/class_taxonomy/`. Bound in Python only:
`CloudEventValidator` validates CloudEvents rows, not these legacy `Event` envelopes, so there is
no JVM counterpart to bind. Promises: [`PROMISES.md`](PROMISES.md).

| Behaviour | Covers |
|-----------|--------|
| `accepted-envelopes/` | Every `envelope_valid_*` fixture is accepted |
| `refused-envelopes/` | Every `envelope_invalid_*` fixture is refused with its pinned code: forbidden key, unknown lane, payload schema, shape |
| `unknown-event-type/` | An envelope of an unregistered event type is refused by the strict journal profile (fails closed) |
| `legacy-lane-mapping/` | Every `lane_mapping_legacy` fixture resolves, or is refused, as it pins |

The envelope verdict is the test-only `tests/support/class_verdict.py`, shared with
`tests/test_conformance_classes.py`; its fixed check order is pinned by
`tests/test_class_taxonomy_support.py`.

## Available steps

| Group | Step |
|-------|------|
| Arrange | `Given the conformance fixtures shipped with the package` |
| Arrange | `Given every fixture of the class "<fixture_class>"` |
| Arrange | `Given every fixture of the class "<fixture_class>" that pins the error code "<code>"` |
| Act | `When each fixture's input is judged as an envelope` |
| Act | `When each fixture's input is judged against the strict journal profile` |
| Act | `When each fixture's input is resolved as a legacy lane mapping` |
| Assert | `Then every fixture is accepted` |
| Assert | `Then every fixture is refused with the error code it pins` |
| Assert | `Then every verdict is the one its fixture pins` |

"Every fixture of the class" fails when the class holds no fixture, so a scenario never passes
on an empty selection.
