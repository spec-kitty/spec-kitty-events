# `status/` promises

Each row maps a `@promise:` id to the guarantee its scenario asserts, the requirement that states
it, and the binding that runs it. The fixtures and the corpus stay the single source of truth.

JVM binding: none

The services (`service/event-log`, `service/command-cli`) implement status append and the
transition fold. Their copies of the append and transition promises (`APPEND-003`,
`TRANSITION-007`) are `@wip` until the `jvm` step source of D3 (#283) lands; the follow-up tags
them `@jvm` and binds them through `verifyAll` and `service-gate`. No `status/` promise is
`@manual`.

| Promise | Guarantee | Requirement | Binding |
|---------|-----------|-------------|---------|
| `PR-EVENTS-STATUS-APPEND-001` | Every valid `status_append` row is appended as exactly the bytes its fixture pins (`expected_line`). | `spec_kitty_events.status_log.encode_status_row`; `conformance/fixtures/status_append/README.md`; #160, #270 | python |
| `PR-EVENTS-STATUS-APPEND-002` | Every invalid `status_append` row is refused with the refusal class its fixture pins (`expected_error`), and nothing is written. | `status_log.NonFiniteNumber`, `status_log.SessionTimesNotSubtractable`; #160, #270 | python |
| `PR-EVENTS-STATUS-APPEND-003` | The services append every `status_append` row as its fixture pins: the bytes, or the refusal. | `service/event-log` status encoder, held to the same documents; #270 | @wip |
| `PR-EVENTS-STATUS-REPLAY-001` | Every `status_diary/replay` stream reduces to its recorded `-output` golden. | `spec_kitty_events.diary.reduce`; #270 | python |
| `PR-EVENTS-STATUS-REPLAY-002` | A replay stream reduced in reverse or shuffled order gives the same recorded output. | `diary.reduce` is order-independent; #270 | python |
| `PR-EVENTS-STATUS-TRANSITION-001` | Every status-transition parity case conforms to `case.schema.json`. | `conformance/status_transition_parity/`; #270 | python |
| `PR-EVENTS-STATUS-TRANSITION-002` | Every refusal case pins a code from the closed write-refusal codes and a reason URN from the closed set, or no reason. | `parity_corpus.WRITE_REFUSAL_CODES`, `REASON_URNS`; #270 | python |
| `PR-EVENTS-STATUS-TRANSITION-003` | Every refusal reason is reached by a case, or `unreachable.json` records why no request can reach it. | `unreachable.json`; #223, #270 | python |
| `PR-EVENTS-STATUS-TRANSITION-004` | `validate_transition`, unforced and with every guard met, admits exactly the lane edges the corpus accepts from an unforced request. | `status.validate_transition` (11.5.0 core edge graph); #237, #270 | python |
| `PR-EVENTS-STATUS-TRANSITION-005` | Every other lane pair (an illegal twin) is refused unforced with an edge violation, and force lifts the edge check for every pair. | `status.validate_transition`; #237, #270 | python |
| `PR-EVENTS-STATUS-TRANSITION-006` | Every one-request case the corpus refuses as `illegal_transition` asks for a lane pair the edge graph refuses (or a lane outside the vocabulary). | `status.validate_transition`, `Lane`; #270 | python |
| `PR-EVENTS-STATUS-TRANSITION-007` | The services apply every case's request to its prior log and give the acknowledgement or refusal it pins. | `service/command-cli` and `service/event-log` transition fold and ack bytes; #237, #270 | @wip |
| `PR-EVENTS-STATUS-ACTOR-001` | Every four-key structured status actor a fixture accepts decodes to itself, and the `actorbinding` schema accepts it too. | `diary.decode_actor`; producer contract `actorbinding`; #263, #270 | python |
| `PR-EVENTS-STATUS-ACTOR-002` | Every five-key structured status actor (`profile_source`) a fixture accepts decodes to itself, and the schema accepts it too. | `diary.decode_actor`; #263, #270 | python |
| `PR-EVENTS-STATUS-ACTOR-003` | A `profile_source` that is null while `profile` is set, or set while `profile` is null, is refused by the decoder and the schema. | `diary.decode_actor`; fixtures with `violation: profile_source_nullness`; #263, #270 | python |
| `PR-EVENTS-STATUS-ACTOR-004` | A `profile_source` outside `invocation`, `flag`, `agent_string`, `wp_frontmatter` is refused by the decoder and the schema. | `diary.decode_actor`; fixtures with `violation: enum_violation`; #263, #270 | python |
