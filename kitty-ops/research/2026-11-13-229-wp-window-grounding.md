# Grounding: `policy_metadata.wp_window` in the Java write service (#229)

Issue [#229](https://github.com/spec-kitty/spec-kitty-events/issues/229) and its comments of
2026-11-08 (`lane_branch_missing`) and 2026-11-10 (the shape as shipped). Read-only grounding,
2026-11-13. `origin/main` at `275e4de`; core (`spec-kitty/spec-kitty`) `main` at `1fb3b55f`, which
carries the contract with core PR #7490 (issue #7403). Operator ruling: the change ships in
`service-v0.1.0-rc.3`, before the rc is cut. SaaS interaction is out of scope.

## The contract

Core `kitty-specs/consolidation-window-provenance-01M6Y84D/contracts/status-event-wp-window.md`
(`schema_version: 1`). The value of `policy_metadata.wp_window` is exactly one of:

```text
{"schema_version": 1, "open_sha": "<40 or 64 lower-case hex>"}
{"schema_version": 1, "gap": {"reason": "capture_failed", "cause": "git_error"|"probe_error", "detail": "<=500 chars"}}
{"schema_version": 1, "gap": {"reason": "lane_branch_missing", "detail": "<=500 chars"}}   # no cause
```

`additionalProperties: false` at both levels; `detail` is optional. The key set is closed: any
new field needs a `schema_version` bump. A writer never writes the reader-only gaps
`not_recorded` or `not_on_lane`.

Writer rules: W-1 stamp every non-migration move of a lane-mapped work package into `claimed` or
`in_progress` (`blocked` writes nothing); W-2 the value comes from the same lane-head reading as
`lane_head`, so `open_sha == lane_head`; W-3 the reading's outcome picks the shape; W-4 never refuse,
delay or retry a move because of the key; W-5 never touch another key; W-6 never backfill; W-7 a
relaying writer preserves the key verbatim. Reader rule R-5: a schema-invalid value reads as
`not_recorded` and is never fatal.

## Where the key is written today

- Core's status pipeline stamps it. `status/transition_pipeline.py::_stamped_policy_metadata` takes
  the one lane-head reading and adds `lane_head` (when it has a SHA) and `wp_window` (via the codec
  `status/wp_window.py`) to the row's `policy_metadata`.
- The row is then handed to the EventBridge port (`status/emit.py::_record_work_package_transition`).
  With the write service bound, `status/write_service_protocol.py::transition_unit_from` sends
  `row.to_dict()` as a `TransitionUnit` row. The CLI builds the row; the service writes it.
- The Java write service (`service/command-cli`) has **no lane-head reading**. Nothing under
  `service/` or `contracts/` mentions `lane_head`. The service validates the row against the lane
  rules, sanitizes it (`StatusRowSanitizer`: a deep strip of five PII key names, none of which is a
  `wp_window` key) and re-encodes it with sorted keys (`StatusRowEncoder`, core's
  `serialize_event_line`). Nothing in the authority reads `wp_window`: `TransitionFold.claimWrites`
  and `StateProjections.agent` read only `shell_pid`, `shell_pid_created_at` and `agent`;
  `StatusRowShape` only refuses a non-object `policy_metadata` on a claim, which a valid row never has.

So the service is already a relaying writer in the sense of W-7, and it already preserves the key
byte for byte. Nothing proves it, and no contract types it.

## Decisions settled here

1. **The service relays; it does not take its own reading.** A service-side stamp could not meet
   W-2: the service has no `lane_head` reading to share, and a second reading would either
   overwrite the CLI's value (W-5) or disagree with `lane_head`. "Writes and preserves" for the
   Java writer is W-7: the bytes it appends carry the CLI's value unchanged.
2. **A malformed value is never refused (W-4).** The service appends it as received (sanitized and
   sorted like any other value); readers read it as `not_recorded` (R-5). The contract types the
   shape a conforming writer sends; it does not make the service enforce it.
3. **Parity comes from the contract file, not from a core-pin bump.** The pinned core
   (`512567ee`) predates #7490, and bumping it is #243's job (blocked). The new cases are
   `oracle: protocol`, hand-written from the contract's examples and rules. Their appended bytes
   come from `extract_ack_bytes.py`; CI's core-mode `--check` proves them against core's
   `serialize_event_line` at the pin, which serializes `policy_metadata` like any other object.
4. **The Python package does not model the key in this change.** The charter's Language Bindings
   rule ("a change to the schemas or fixtures applies to all bindings in the same change") binds
   the package's committed schemas and fixtures. This change leaves every committed JSON Schema
   alone: `producer_contract` `lane.transitioned.v1` keeps `policy_metadata` an open object.
   Typing it there would tighten a fail-closed schema on the SaaS ingestion path (out of scope)
   and, per core's contract, belongs to "its own payload version". The only package-tree change
   is new parity cases in `conformance/status_transition_parity/cases/`. The packaged
   conformance gate validates them against the unchanged `case.schema.json`, and only the service
   consumes them; the Java binding (`java/`) does not read that corpus. So every consumer of the
   changed fixtures is updated in this change, and the rule holds.
5. **Where the type lives.** The closed schemas (`WpWindow`, `WpWindowGap`, `WpWindowGapReason`,
   `WpWindowGapCause`) go in `contracts/_shared/schemas/`: they are vocabulary of the status row,
   not a resource of one module, and `contracts/README.md` ("_shared admission") puts a piece any
   module can refer to there, while module-to-module references have no precedent. mission-write's
   `EventRow.policy_metadata.wp_window` refers to them. mission-status's `StatusTransitionEvent`
   keeps its allow-list (it omits `policy_metadata` by design, and adding a field to a closed
   response is its own contract decision), so the mission-status bundle is unchanged; a later read
   field refers to the same `_shared` schema.

## Follow-up: when the service takes its own reading

[ADR-0007](../../docs/adr/0007-write-service-commits-coordination-status-by-ref.md) D-4 has the
service stamp `policy_metadata.lane_head` from `refs/heads/<lane>` for `coordination_ref_commit`
once write-location resolution moves into the service (migration step 4). That is not built: no
lane-head reading exists in `service/` today. When it is, W-2 binds the service: it must write
`wp_window` from that same reading, on the same moves (W-1), with the same shapes (W-3), and the
relay rule of this change no longer suffices for that surface. To be filed as a follow-up issue
from this PR.

## Similar surfaces

| Surface | What it does with `policy_metadata` | Disposition |
|---|---|---|
| `StatusRowSanitizer` / `StatusRowEncoder` (event-log) | deep PII strip, sorted keys | preserves `wp_window`; pinned by a new encoder test |
| `TransitionFold`, `StateProjections`, `StatusRowShape` (command-cli) | read `agent`, `shell_pid*`; refuse a non-object on a claim | no change; `wp_window` is never read |
| `SeedKey` (event-log) | copies `policy_metadata` whole into the seed key | no change; a relayed value stays part of the row identity |
| Replay judgement (`ReplayClassifier`) | compares sanitized rows as JSON values | no change; core's pipeline builds the value once per event, so a retry carries the same value. A retry with a different value under the same `event_id` is an `event_id` conflict, as for any other field; it is not a refusal because of the key |
| `StatusLogRepairService` | quarantines or completes torn lines; never re-encodes a good row | no change |
| mission-status read API (`StatusTransitionEvent`) | closed allow-list projection that omits `policy_metadata` | unchanged (decision 5); exposing it is its own contract decision |
| `producer_contract` `lane.transitioned.v1` (package) | `policy_metadata` is an open object | no change (decision 4) |

## Tracked issues

| Issue | Relation | Disposition |
|---|---|---|
| #243 core-pin bump (R24, `session_pair` fixtures) | would make core-oracle `wp_window` parity possible | not taken (blocked by #240/#241); parity from the contract file instead |
| #240 core row robustness | non-finite numbers, separators, BOM | no overlap: `wp_window` holds no float and no raw separator; the service stays strict |
| #241 core WP-fact resolution | case-insensitive WP file lookup | no overlap |
| #196 service-line changelog | where a service-only entry lives | no `docs/changelog/service-unreleased/` machinery on `main`; orchestrator ruling: fragment in `docs/changelog/unreleased/`, tagged service line, moves when #196 lands |
| #220 mission-record citations | records of #208 | no overlap |
