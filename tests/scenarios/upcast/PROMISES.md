# `upcast/` promises

Each row maps a `@promise:` id to the guarantee its scenario asserts, the requirement that states
it, and the binding that runs it. The corpus stays the single source of truth.

JVM binding: full

| Promise | Guarantee | Requirement | Binding |
|---------|-----------|-------------|---------|
| `PR-EVENTS-UPCAST-001` | Every CLI producer fixture's legacy row maps to its golden CloudEvent, byte for byte, with the form and read-profile validity its case pins. | `docs/architecture/cloudevents-envelope.md` §9; corpus group `cli`; #92, #199 | python, jvm |
| `PR-EVENTS-UPCAST-002` | Every conformance 3.0.0 envelope pinned as mapped maps to its golden CloudEvent, byte for byte, with its pinned form and validity. | §9; corpus group `conformance`; #92, #199 | python, jvm |
| `PR-EVENTS-UPCAST-003` | Every status-diary line pinned as mapped, read with the Mission id from context, maps to its golden CloudEvent, byte for byte. | §9; corpus group `diary`; #92, #199 | python, jvm |
| `PR-EVENTS-UPCAST-004` | Every conformance envelope pinned as a pass-through keeps its row and bytes and carries exactly the codes it pins. | §9; corpus group `conformance`; #92, #199 | python, jvm |
| `PR-EVENTS-UPCAST-005` | Every status-diary line pinned as a pass-through keeps its row and bytes and carries exactly the codes it pins. | §9; corpus group `diary`; #92, #199 | python, jvm |
| `PR-EVENTS-UPCAST-006` | Upcasting a golden again is a pass-through (`ALREADY_CLOUDEVENT`) with the same bytes: upcasting is idempotent. | §9 (idempotence); groups `cli`, `conformance`, `diary`, `edge`, `transport`; #92, #199, #287 | python, jvm |
| `PR-EVENTS-UPCAST-007` | Strict use raises the first code a case pins, unless it is `ALREADY_CLOUDEVENT`, and upcasts every other case. | §9 (strict use); groups `cli`, `conformance`, `diary`, `edge` (the only group pinning `ALREADY_CLOUDEVENT`), `transport`; #92, #199, #287 | python, jvm |
| `PR-EVENTS-UPCAST-008` | Every edge case pinned as mapped (for example structured actors, id spellings, boundary clocks, nesting at the limit, floats and non-ASCII text) maps to its golden CloudEvent, byte for byte, with its pinned form and read-profile validity, valid or not. | §9; corpus group `edge`; #287 | python, jvm |
| `PR-EVENTS-UPCAST-009` | Every transport-group case, one 3.0.0 transport envelope per producer type that has a transport form (design §3), maps back to its golden CloudEvent, byte for byte, with its pinned form and validity. | §9; corpus group `transport`; #287 | python, jvm |
| `PR-EVENTS-UPCAST-010` | Every edge case pinned as a pass-through (for example a line that is not JSON or not an object, a missing, unexpected or invalid member, a conflict with the caller context, an unregistered type, or a row that is already a CloudEvent) keeps its row and bytes and carries exactly the codes it pins. | §9; corpus group `edge`; #287 | python, jvm |
