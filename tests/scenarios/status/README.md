# `status/` scenarios

How a status row is written and read: appended as the bytes its fixture pins or refused, a
status diary replayed to its recorded state, a transition acknowledged or refused as the parity
corpus pins (including `validate_transition`'s edge graph), and the structured status actor.
Driven by `conformance/fixtures/status_append/`, the manifest's `status_diary/replay` entries,
`conformance/status_transition_parity/` and the status producer-row fixtures. Promises:
[`PROMISES.md`](PROMISES.md).

| Behaviour | Covers |
|-----------|--------|
| `append/` | Every valid `status_append` fixture's bytes, every invalid one's refusal |
| `replay/` | Every status-diary replay stream reduces to its recorded output, in any order |
| `transition/` | The parity corpus's schema, refusal vocabularies and reachability; the edge graph's legal edges and illegal twins |
| `actor/` | The 4-key and 5-key structured status actor, and its invalid combinations |

The fixture listers are the test-only `tests/support/status_fixtures.py`. The services'
promises (`APPEND-003`, `TRANSITION-007`) are `@wip` until the `jvm` step source (#283) lands.

## Available steps

| Group | Step |
|-------|------|
| Arrange | `Given every status-append fixture` |
| Arrange | `Given every status-append fixture pinned as "<result>"` |
| Arrange | `Given every status-diary replay stream with its recorded output` |
| Arrange | `Given every status-transition parity case` |
| Arrange | `Given every status-transition parity case the corpus pins as a refusal` |
| Arrange | `Given every parity case refused as an illegal transition from one unforced request` |
| Arrange | `Given the lane edges the parity corpus accepts from an unforced request` |
| Arrange | `Given every structured status actor with <count> keys that its fixture accepts` |
| Arrange | `Given every structured status actor its fixture refuses for "<violation>"` |
| Act | `When each fixture's row is appended` |
| Act | `When each stream is reduced in its recorded order` |
| Act | `When each stream is reduced in reverse and in a shuffled order` |
| Act | `When every pair of lanes is validated as a transition carrying every guarded field` |
| Act | `When each case's requested lane pair is validated unforced` |
| Act | `When each actor is decoded as a status actor` |
| Assert | `Then every line written is the exact bytes its fixture pins` |
| Assert | `Then every append is refused with the refusal its fixture pins` |
| Assert | `Then every append writes the bytes or raises the refusal its fixture pins` |
| Assert | `Then every stream reduces to its recorded output` |
| Assert | `Then every case conforms to the corpus case schema` |
| Assert | `Then every refusal pins a write-refusal code and a reason from the closed vocabularies` |
| Assert | `Then every refusal reason is reached by a case or has its cause recorded as unreachable` |
| Assert | `Then exactly the edges the corpus accepts pass the unforced edge check` |
| Assert | `Then of those edges only the review-rejection family is refused unforced, by its force guard` |
| Assert | `Then every other pair is refused unforced with an edge violation` |
| Assert | `Then forcing any pair lifts the edge check` |
| Assert | `Then validate_transition refuses every requested pair` |
| Assert | `Then every actor decodes to itself, every key kept` |
| Assert | `Then every actor is refused` |
| Assert | `Then the producer contract's actor schema gives the same verdict` |

Every selection fails when it is empty, so a scenario never passes on nothing.

Not bound in Python (only the services apply a request to a prior log; `TRANSITION-007`, `@wip`):
`When each case's request is applied to its prior log`,
`Then every outcome is the acknowledgement or refusal its case pins`.
