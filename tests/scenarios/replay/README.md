# `replay/` scenarios

How a replay stream folds: every stream folds to the reducer output its fixture pins, and the
order its rows arrive in does not change that. Driven by the replay folders
`spec_kitty_events/conformance/fixtures/lifecycle/replay/` and `.../decisionpoint/replay/`, where
each `<stem>.jsonl` stream is paired with the `<stem>_output.json` it pins. Bound in Python only:
no JVM reducer binding exists yet. Promises: [`PROMISES.md`](PROMISES.md).

| Behaviour | Covers |
|-----------|--------|
| `lifecycle-replay/` | Every `lifecycle/replay` stream folds, through `reduce_lifecycle_events`, to its pinned output |
| `decision-point-replay/` | Every `decisionpoint/replay` stream folds, through `reduce_decision_point_events`, to its pinned output: full lifecycles, anomalies, V1 interview outcomes, reopen and unfoldable folds |
| `order-independent-replay/` | Every stream of both folders folds to the same pinned output with its rows reversed or shuffled (fixed seeds) |

The pairing is the test-only `tests/support/replay.py`: a stream without its output, or an output
without its stream, is an error, never a skip. A fold is compared as `model_dump(mode="json")`
with the pinned JSON.

## Available steps

The reducers a step can name are `"lifecycle"` and `"decision point"`.

| Group | Step |
|-------|------|
| Arrange | `Given the conformance fixtures shipped with the package` |
| Arrange | `Given every replay stream in the fixture folder "<folder>"` |
| Act | `When each stream is folded by the "<reducer>" reducer` |
| Act | `When each stream is folded by the "<reducer>" reducer with its rows shuffled` |
| Assert | `Then every fold is the state its fixture pins` |

"Every replay stream in the fixture folder" fails when the folder holds no stream, or a stream
holds no event, so a scenario never passes on an empty selection.
