# Data model: command-cli write core

## Request side

### WriteRequest (application)

- `missionId`: a ULID, from the URL.
- `kind`: `UNIT`, `BATCH` or `ANNOTATION`.
- `units`: a list of `UnitRequest`, 1–64 of them. An annotation request is one unit with a single annotation row.
- `context`: a `RequestContext` (`actor`, `checkout`, `missionDirectory`, `writeSurface`, `destinationRef`). One context is shared by the whole batch, as the contract requires.

### UnitRequest

- `wpId`: matches `^WP\d+$`.
- `operation`: `TRANSITION` (the default), `START_IMPLEMENTATION` or `START_REVIEW`.
- `rows`: a list of `Row`. Row 0 is a transition; rows 1..n are annotations (FR-027).

### Row

- `eventId`: a ULID.
- `value`: the parsed JSON value. It is an ordered map holding `String`, `BigInteger`, `Double`, `Boolean`, `null`, `List` and `Map`, the value Python's `json.loads` would produce.

**Invariants**

- Row-kind rules hold (FR-027).
- No lane alias appears (FR-023).
- No non-finite number appears.
- The session-time pair can be subtracted.
- No `event_id` repeats within the request.

## Writer side (event-log)

### Encoded row

`bytes = encode(sanitize(value)) + "\n"`. `encode` is a Python `json.dumps(sort_keys=True)` (FR-001), and `sanitize` is core's sanitizer (FR-002).

### MissionLockPath

`<gcd>/spec-kitty-locks/<missionDirectory basename>.status.lock`

### StatusLogSession

Valid only while the Mission lock is held.

- `currentBytes()`: the whole log, or an empty array when the file does not exist.
- `appendAndSync(bytes)`: one `O_APPEND` write, then a sync, plus a sync of the parent directory when the file was created. On any failure it truncates to the size before the write and throws `PersistenceFailed`.

**Invariant:** append is called at most once per session, and only after the torn-tail check passed.

## Authority side (command-cli domain.authority)

### MissionFacts

- `dependencies(wpId)`: a `DependencyDeclaration`, one of:
  - `Declared(List<WpId>)`;
  - `None`;
  - `Unresolvable(message)`, for an ambiguous match, an unreadable file or a malformed value.
- `subtaskRoster(wpId)`: the list of authored subtask ids. An empty list means complete.

### WpWriteState (the fold result per WP)

- `lane`: a `Lane`.
- `actor`: the current holder, a string or a structured value projected to its tool.
- `role`: from the annotation-folded `role` slot.
- `reasonSource`: the provenance of the last cancellation reason.
- `hasTransitions`: true when any transition exists, which is the implementation evidence.
- `subtaskState`: the event-sourced subtask completion.
- `implementerOfRecord`, plus `review` and `agent`, as core folds them.

### Fold

`fold(rows in log order) → Map<WpId, WpWriteState>`. It follows core's `read_events` and reducer at the pin:

- blank lines are skipped;
- rows with a non-lane `event_type` are skipped;
- invalid JSON or an unknown `kind` makes the log unreducible;
- `event_id` dedupe is first-wins;
- ordering follows core's ordering;
- annotation deltas fold into role, agent, subtasks and review.

### GuardContext

`actor`, `workspaceContext`, `subtasksComplete`, `implementationEvidencePresent`, `reason`, `reviewRef`, `evidence`, `force`, `reviewResult` and `dependencyReady`. Some are derived (FR-022):

| Input | Derivation |
|---|---|
| `workspaceContext` | `<execution_mode>:<root>` when the row carries none (core's single-door default) |
| `subtasksComplete` | from the roster and the subtask state, unforced only |
| `implementationEvidencePresent` | `hasTransitions` |
| `dependencyReady` | the dependency verdict, or null when the edge is not gated |

### Refusal

- `code`: `WriteRefusalCode`.
- `reason`: a `WriteRefusalReason` URN, or null.
- `message`: core's text, or a fixed text (FR-025).
- `unitIndex`: set for a batch only.

### Judgement

Either `Accepted`, with the per-unit `{eventIds, appendedLines, replayed}`, or `Refused(Refusal)`.

## Lane state machine (ported)

The machine has 11 `Lane` values and 29 edges. `done` and `canceled` are terminal. A force with actor and reason bypasses the structural edges and every guard, except a target of `genesis`. Guard precedence is in research.md §1.3, and the edge table in §1.2.

## Response side

- `DurabilityAck`: `{eventIds, appendedLines, fsynced: true, statusCommit: null, replayed}`.
- `BatchDurabilityAck`: `{units: [DurabilityAck…], fsyncs: 0|1}`, with 0 only when every unit is replayed.
- `WriteRefusal`: the problem+json body `{type?, title, status, code, detail, instance?}`.
