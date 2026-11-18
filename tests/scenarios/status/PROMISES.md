# `status/` promises

Each row maps a `@promise:` id to the guarantee its scenario asserts, the requirement that states
it, and the binding that runs it. The fixtures and the corpus stay the single source of truth.

JVM binding: partial

The services (`service/event-log`, `service/command-cli`) implement status append and the
transition fold, and Cucumber-JVM binds two promises against them through `verifyAll` and
`service-gate` (the `:event-log` and `:command-cli` runners of
[`../jvm-runners.json`](../jvm-runners.json)). `APPEND-003` is bound in both languages.
`TRANSITION-007` is JVM-only (operator ruling on #289): only the services apply a request to a
prior log, and there is no Python write fold. By the operator's ruling it covers the
**domain-layer** parity cases only, through command-cli's authority. The 16 later-layer cases
(replay classification, log decoding, Mission discovery and the R16 write location, listed with
their reasons in `service/command-cli/src/test/java/org/speckitty/commandcli/domain/authority/batch/FullParityCorpusTest.java`)
need the HTTP service; `service/command-cli/src/test/java/org/speckitty/commandcli/adapter/in/http/operations/ProtocolCorpusTest.java`
runs them over real HTTP, also in `service-gate`. No `status/` promise is `@manual`.

| Promise | Guarantee | Requirement | Binding |
|---------|-----------|-------------|---------|
| `PR-EVENTS-STATUS-APPEND-001` | Every valid `status_append` row is appended as exactly the bytes its fixture pins (`expected_line`). | `spec_kitty_events.status_log.encode_status_row`; `conformance/fixtures/status_append/README.md`; #160, #270 | python |
| `PR-EVENTS-STATUS-APPEND-002` | Every invalid `status_append` row is refused with the refusal class its fixture pins (`expected_error`), and nothing is written. | `status_log.NonFiniteNumber`, `status_log.SessionTimesNotSubtractable`; #160, #270 | python |
| `PR-EVENTS-STATUS-APPEND-003` | Every binding appends every `status_append` row as its fixture pins: the bytes, or the refusal. Python's `encode_status_row` and the service's `StatusRowEncoder` alike. | `status_log.encode_status_row`; `service/event-log` `StatusRowEncoder`; #160, #270 | python, jvm |
| `PR-EVENTS-STATUS-REPLAY-001` | Every `status_diary/replay` stream reduces to its recorded `-output` golden. | `spec_kitty_events.diary.reduce`; #270 | python |
| `PR-EVENTS-STATUS-REPLAY-002` | A replay stream reduced in reverse or shuffled order gives the same recorded output. | `diary.reduce` is order-independent; #270 | python |
| `PR-EVENTS-STATUS-TRANSITION-001` | Every status-transition parity case conforms to `case.schema.json`. | `conformance/status_transition_parity/`; #270 | python |
| `PR-EVENTS-STATUS-TRANSITION-002` | Every refusal case pins a code from the closed write-refusal codes and a reason URN from the closed set, or no reason. | `parity_corpus.WRITE_REFUSAL_CODES`, `REASON_URNS`; #270 | python |
| `PR-EVENTS-STATUS-TRANSITION-003` | Every refusal reason is reached by a case, or `unreachable.json` records why no request can reach it. | `unreachable.json`; #223, #270 | python |
| `PR-EVENTS-STATUS-TRANSITION-004` | `validate_transition`'s unforced edge check admits exactly the lane edges the corpus accepts from an unforced request. Of those, the full validation refuses only the review-rejection family (`* -> planned`), which the package requires to be forced although core acknowledges it unforced. | `status.validate_transition` (11.5.0 core edge graph); the review-rejection guard (`docs/events/work-and-status.md`); #237, #270 | python |
| `PR-EVENTS-STATUS-TRANSITION-005` | Every other lane pair (an illegal twin) is refused unforced with an edge violation, and force lifts the edge check for every pair. | `status.validate_transition`; #237, #270 | python |
| `PR-EVENTS-STATUS-TRANSITION-006` | Every one-request case the corpus refuses as `illegal_transition` asks for a lane pair the edge graph refuses (or a lane outside the vocabulary). | `status.validate_transition`, `Lane`; #270 | python |
| `PR-EVENTS-STATUS-TRANSITION-007` | command-cli's authority applies every domain-layer case's request to its prior log and gives the acknowledgement, or the refusal code, reason, message (core-oracle cases) and unit, it pins. | `service/command-cli` write fold and guards (`Authority`); #237, #270 | jvm |
| `PR-EVENTS-STATUS-ACTOR-001` | Every four-key structured status actor a fixture accepts decodes to itself, and the `actorbinding` schema accepts it too. | `diary.decode_actor`; producer contract `actorbinding`; #263, #270 | python |
| `PR-EVENTS-STATUS-ACTOR-002` | Every five-key structured status actor (`profile_source`) a fixture accepts decodes to itself, and the schema accepts it too. | `diary.decode_actor`; #263, #270 | python |
| `PR-EVENTS-STATUS-ACTOR-003` | A `profile_source` that is null while `profile` is set, or set while `profile` is null, is refused by the decoder and the schema. | `diary.decode_actor`; fixtures with `violation: profile_source_nullness`; #263, #270 | python |
| `PR-EVENTS-STATUS-ACTOR-004` | A `profile_source` outside `invocation`, `flag`, `agent_string`, `wp_frontmatter` is refused by the decoder and the schema. | `diary.decode_actor`; fixtures with `violation: enum_violation`; #263, #270 | python |
