# `replay/` promises

Each row maps a `@promise:` id to the guarantee its scenario asserts, the requirement that states
it, and the binding that runs it. The streams and their pinned outputs stay the single source of
truth.

JVM binding: none

| Promise | Guarantee | Requirement | Binding |
|---------|-----------|-------------|---------|
| `PR-EVENTS-REPLAY-001` | Every lifecycle replay stream folds to the reducer output its fixture pins, a duplicated event id and an ignored unknown event type included. | `lifecycle.reduce_lifecycle_events`; lifecycle replay stream and golden (11.1.0); #119 | python |
| `PR-EVENTS-REPLAY-002` | Every decision point replay stream folds to the reducer output its fixture pins, anomalies, a deferred decision reopened by a resolve, and unfoldable terminal combinations included. | `decisionpoint.reduce_decision_point_events`; reopen fold and `unfoldable_terminal_combination` (11.0.0); #120 | python |
| `PR-EVENTS-REPLAY-003` | The order a stream's rows arrive in does not change its fold: reversed or shuffled, it folds to the same pinned output. | `ordering.canonical_order_key` (11.0.0), #120; lifecycle golden permutation invariance, #119 | python |
