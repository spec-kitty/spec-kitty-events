# `upcast/` scenarios

How a legacy log row is read into a CloudEvent: mapped to the exact golden its case pins, passed
through unchanged with the codes that say why, read again without a byte changing, and refused in
strict use with the first code it pins. Driven by the upcast corpus,
`spec_kitty_events/producer_contract/fixtures/upcast/cases/`, groups `cli`, `conformance` and
`diary` (the contract is section 9 of `docs/architecture/cloudevents-envelope.md`). Bound in
Python and in Cucumber-JVM, against the Java `Upcaster`, on the same feature files. Promises:
[`PROMISES.md`](PROMISES.md).

| Behaviour | Covers |
|-----------|--------|
| `mapped-rows/` | Every case pinned as `mapped` maps to its golden bytes, with its pinned form and read-profile validity |
| `passthrough-rows/` | Every case pinned as `passthrough` keeps its row and its bytes, with the codes it pins |
| `golden-idempotence/` | Reading a golden again is a pass-through (`ALREADY_CLOUDEVENT`) with the same bytes |
| `strict-use/` | Strict use raises the first pinned code, and upcasts every case that pins none (or `ALREADY_CLOUDEVENT`) |

The Python steps live in `conftest.py` and list the corpus through the test-only
`tests/support/upcast_corpus.py`. The JVM steps live in
`java/src/test/java/ai/speckitty/events/cloudevents/UpcastSteps.java` and read the same corpus
through `-Dupcast.cases`; `./gradlew :java-binding:scenarios` runs every `@jvm` scenario under
`tests/scenarios/`, as part of `verifyAll`.

## Available steps

Both bindings define every step below, with the same text.

| Group | Step |
|-------|------|
| Arrange | `Given the upcast corpus shipped with the package` |
| Arrange | `Given every case of the group "<group>"` |
| Arrange | `Given every case of the group "<group>" pinned as "<outcome>"` |
| Act | `When each case's line is upcast with its caller context` |
| Act | `When each case's golden is upcast again with its caller context` |
| Act | `When each case's line is upcast in strict use` |
| Assert | `Then every result has the outcome, form and codes its case pins` |
| Assert | `Then every mapped row's canonical bytes equal its golden` |
| Assert | `Then every golden's read-profile validity is the one its case pins` |
| Assert | `Then every passed-through line is left as it was` |
| Assert | `Then every result is a pass-through of its golden with the same bytes` |
| Assert | `Then strict use refuses every case with the first code it pins and upcasts every other case` |

"Every case of the group" fails when the selection is empty, and `<outcome>` is `mapped` or
`passthrough`, so a scenario never passes on an empty selection.
