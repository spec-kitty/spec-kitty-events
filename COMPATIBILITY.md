# Compatibility Guide

**Current package version**: `11.1.0`

The on-wire envelope schema version is `3.0.0` and has been unchanged since
the cutover. The package version and the envelope schema version move
independently — see
[`contracts/versioning-and-compatibility.md`](contracts/versioning-and-compatibility.md).

`spec-kitty-events` is a fail-closed contract package. Released sections
below are ordered newest-first by the release that introduced them. An
unreleased `## Known gap (not yet closed)` section documents a gap no
release has closed yet; it carries no version number and precedes every
released section, regardless of when the gap it describes will close.

> The single declaration above is the only place this document states the
> package version; `tests/test_compatibility_doc.py` pins it to
> `spec_kitty_events.__version__`. Do not restate it in prose.

This document is the public compatibility policy for consumers of:

- `spec-kitty-events`
- `spec-kitty-saas`
- `spec-kitty`


## `11.1.0` — decision-significance events and lifecycle conformance breadth (minor)

`11.1.0` is additive. It adds two event types and fixtures; no existing accept boundary
moves, following the `6.1.0` new-event-type precedent. The envelope `schema_version` stays
`3.0.0`. `STRICT_EVENT_TYPES`, `MISSION_NEXT_EVENT_TYPES` and the support matrix are unchanged.

### What changed

- New event types `SignificanceEvaluated` and `DecisionTimeoutExpired`, with the models
  `RaciRoleBinding` and `ResolvedRaciSnapshot` (module `decision_significance`).
- New fixture categories `decision_significance` and `lifecycle` (valid, invalid, replay),
  with `min_version` `11.1.0` on the new decision fixtures.
- Test and fixture coverage: lifecycle invalid fixtures, a lifecycle replay golden, and
  `WPStatusChanged` lane and `summary` breadth (#62).
- The lifecycle replay golden pins existing reducer behaviour: an unknown event type is counted in `event_count` and changes no state.

### Adopting 11.1.0

- Widen the pin to `spec-kitty-events>=11.1.0,<12`. Nothing else is needed if you do not use the new types.
- Import the new types from `spec_kitty_events.decision_significance`.
- Every `RaciRoleBinding` needs a non-empty `actor_id`: the top-level `actor` and the nested bindings in `raci_snapshot` and `escalation_targets`. Null, absent or empty is refused (operator ruling; narrower than core's `RACIRoleBinding`, which allows None, e.g. inferred RACI defaults).
- `raci_snapshot` is typed as `ResolvedRaciSnapshot`, so a dict that is not a resolved RACI binding is refused (core types it `dict[str, Any]`).
- Consumers of the producer row `run.significance.evaluated.v1` see no change.
- The new types are not in `STRICT_EVENT_TYPES`, so `validate_strict_envelope` still rejects them as unknown (same as `ReviewerSelfApproval`).

### Deliberate difference from the producer row and core (R2)

The package requires a non-empty `actor_id` on `RaciRoleBinding`. Core's `RACIRoleBinding`
and the `run.significance.evaluated.v1` producer row both allow a null or absent `actor_id`.
A payload that core emits with a null `actor_id` is refused by this package. The producer row
is unchanged. A core follow-up must make core always set `actor_id` before adopting these models.

## `11.0.0` — decision reopen, core-shaped glossary contracts, optional envelope clock (breaking)

`11.0.0` is a SemVer major. It reconciles the package with what core actually writes
into its local ledgers (spec-kitty/spec-kitty-events#120). The envelope `schema_version`
is unchanged (`3.0.0`); the wire envelope did not move, only what the package accepts and
how it folds. Fixtures added or rewritten for this release carry `min_version` `11.0.0`.

### What changed

Breaking:

- **Glossary payloads adopt core's emitted shapes.** All seven existing glossary payload
  models were renamed, retyped or reshaped. Old field names are not accepted beside the
  new ones (no aliases). See the field map below.
- **`SemanticConflictEntry` is removed** and `semantic_conflict_entry.schema.json` is
  deleted. `GlossaryFinding` (with `GlossaryConflictType`, `SenseRef`) replaces it.
  `ReducedGlossaryState` is re-keyed: term candidates by `term`, senses by
  `(scope, term_surface)`, clarifications correlated by `conflict_id`; the old
  conflict list is replaced by `semantic_checks` and `generation_blocks`.
- **`LOCAL_ONLY_EVENT_TYPES` is removed** from the package root and `__all__`, as 10.8.0
  announced. `from spec_kitty_events import LOCAL_ONLY_EVENT_TYPES` now raises `ImportError`.
- **`Event.lamport_clock` is optional** (`Optional[int]`, default `None`, `>= 0` when
  present), and `schemas/event.schema.json` no longer lists it as required. Code that
  reads `event.lamport_clock` as an `int` must handle `None`.
- **The DecisionPoint reducer allows the reopen deferred -> resolved** (an `invalid_transition`
  before). A reduced state now always carries `reopened_from_deferred` (a bool).

Additive:

- `StepCheckpointed` is registered (payload `StepCheckpointedPayload`, committed schema,
  conformance maps, member of `GLOSSARY_EVENT_TYPES`). The package now has one more event type.
- `spec_kitty_events.ordering.canonical_order_key` and `node_order_key`: one deterministic
  key `(1 if clocked else 0, clock or 0, timestamp, event_id)` for mixed clocked and
  unclocked streams. Every in-package ordering site uses it.
- `reduce_decision_point_records(records, *, strict=True)`, `DECISION_POINT_TERMINAL_REOPEN`
  (`{(deferred, resolved)}`) and `DecisionPointFoldError`: a read-side fold over the ledger
  records `append_event` writes.
- New anomaly kind `unfoldable_terminal_combination`.
- New packaged fixtures: core-shaped glossary records, `StepCheckpointed`, DecisionPoint reopen
  streams, `ordering/`, unclocked envelopes and `ledger_append` cases for full
  DecisionPoint envelopes and glossary records.
- Packaging fix: conformance fixtures that earlier wheels omitted (`ordering/`,
  `timestamp_semantics/`, `dossier/replay/canonical_output_snapshot.json`,
  `zeitgeist_attrs/README.md`) are now shipped, and `tests/test_wheel_contents.py` guards it.

### Why major

The glossary renames and type changes flip the accept/reject boundary for every glossary
producer and reader. The `lamport_clock` change widens the envelope contract. The reopen
changes what a previously-`invalid_transition` stream reduces to. The removal of
`LOCAL_ONLY_EVENT_TYPES` breaks every importer. No committed replay behaviour changed:
existing reducer goldens differ only by the added `reopened_from_deferred: false` key.

### Unaffected

Consumers that never touch glossary events, `lamport_clock` as a required int, the
reopen path or `LOCAL_ONLY_EVENT_TYPES`; the envelope wire format (`schema_version`
`3.0.0`); the strict profile, which still requires `lamport_clock`; every non-glossary
payload contract; and `GlossaryStrictnessSet`, which is unchanged.

### Unclocked envelopes

An envelope with `lamport_clock = None` is valid for local ledgers. It orders before all
clocked envelopes, by `(timestamp, event_id)`, matching ADR 0001's total order
(`total_order.order_key`, `contracts/total-order.md`), where every clockless row sorts
first. It is never concurrent with another envelope
and always forms a singleton group. It is **not** valid for the durable journal or
TeamSpace ingress: `validate_strict_envelope` still requires `lamport_clock`, and the
clock-required surfaces (`work_replay`, `WorkObservation`, `HarnessObservation`) refuse an
unclocked envelope with a clear error. `merge` raises on unclocked input. The exported
`status_event_sort_key` now returns the 4-tuple
`(1 if clocked else 0, lamport_clock or 0, utc_timestamp_iso, event_id)`, where the time part is the instant in UTC, instead of a 3-tuple.
`project_uuid` stays required.

### Decision reopen

One Resolved(deferred) plus one Resolved(resolved) for a decision fold to RESOLVED in
either file order, using the resolved event's answer and time. The reduced state sets
`reopened_from_deferred = True` and emits no anomaly. An Overridden is legal only after
the effective resolve. Any other multi-Resolved set (two deferred, deferred plus
canceled, three Resolved) reports `unfoldable_terminal_combination` for each extra, and
the first Resolved by the canonical key stands. An Overridden before the effective resolve
reports `invalid_transition`.
`reduce_decision_point_records` raises `DecisionPointFoldError` on the latter under
`strict=True`.

### Migration

**Producer (including core).**
- Write the full `Event` envelope for DecisionPoint events. `lamport_clock` may be omitted
  for local ledgers; keep it for the journal and TeamSpace.
- Build glossary records in the new shapes (field map below).
- Import glossary names from the flat `spec_kitty_events.glossary` module (there is no
  `glossary.events` submodule), using the `*Payload` names.
- Drop any `LOCAL_ONLY_EVENT_TYPES` import. Raise the pin to `>=11,<12`.

**Reader / projector.**
- Treat `lamport_clock` as optional; order with `canonical_order_key`. Update any code that
  unpacks `status_event_sort_key`, which now returns 4 elements.
- Do not pass unclocked envelopes to `merge` or `work_replay`.
- Read `ReducedGlossaryState` through its new keys:
  - `term_candidates[term]`
  - `term_senses[(scope, term_surface)]`
  - `checkpoints[step_id]`
- Read `ClarificationRecord.conflict_id`; its `semantic_check_event_id` may be `None`.
- Fold ledger records with `reduce_decision_point_records`. Accept `reopened_from_deferred`
  and the new anomaly kind.

**Binding author.** Implement the ordering key and the unclocked rules, then run the new
`ordering`, `decisionpoint` reopen, `glossary` and `ledger_append` fixtures.

### Glossary field map (old 10.8.0 -> new 11.0.0)

| Model | Old -> new |
|---|---|
| `GlossaryScopeActivatedPayload` | remove `scope_type`; add `run_id` |
| `TermCandidateObservedPayload` | `term_surface` -> `term`; `step_id` -> `source_step`; `actor` -> `actor_id`; remove `scope_id`, `step_metadata`; add `extraction_method`, `context`, `run_id` |
| `SemanticCheckEvaluatedPayload` | `conflicts` -> `findings` (`GlossaryFinding`); `severity` -> `overall_severity`; recommended action `pass` -> `proceed`; remove `scope_id`, `step_metadata`; add `run_id`, `blocked` |
| `GenerationBlockedBySemanticConflictPayload` | remove `conflict_event_ids`, `blocking_strictness`, `step_metadata`; add `conflicts` (`GlossaryFinding`), `strictness_mode`, `effective_strictness`, `run_id` |
| `GlossaryClarificationRequestedPayload` | remove `scope_id`, `actor`; add `run_id`, `conflict_id`; `semantic_check_event_id` is optional but never empty |
| `GlossaryClarificationResolvedPayload` | remove `mission_id`, `clarification_event_id`, `selected_meaning`; add `conflict_id`, `term_surface`, `selected_sense` (`SenseRef`), `resolution_mode`, `provenance`; `actor` is now an object (`actor_id`, `actor_type`, `display_name`) |
| `GlossarySenseUpdatedPayload` | remove `mission_id`, `scope_id`, `before_sense`, `after_sense`, `reason`; add `scope`, `new_sense`, `update_type`, `provenance`; `actor` is now an object |
| `StepCheckpointedPayload` | new: `mission_id`, `run_id`, `step_id`, `strictness`, `scope_refs`, `input_hash`, `cursor`, `retry_token` |
| `GlossaryStrictnessSetPayload` | unchanged |

The envelope `schema_version` is unchanged (`3.0.0`).

## `10.8.0` — local-ledger write contract; `LOCAL_ONLY_EVENT_TYPES` deprecated

`10.8.0` is a SemVer minor, additive. A new module, `spec_kitty_events.persistence`,
exports `append_event(event, path)`, the reference write path for a local JSONL ledger,
along with `build_record`, `serialize_record` and the `LedgerAppendError` /
`LedgerValidationError` / `LedgerTornTailError` refusals.
- It accepts a canonical `Event`, a flat dict carrying `event_type`, or a registered
  payload model.
- It validates fail-closed (`strict=True`) and refuses unknown types.
- It appends `json.dumps(record, sort_keys=True, ensure_ascii=True, allow_nan=False) + "\n"`
  with one `O_APPEND` write. For JSON-native records these bytes are identical to
  core's local fallback append.
- The new `ledger_append` conformance fixtures (min_version `10.8.0`) pin those bytes,
  so every binding is held to the same lines.

`LOCAL_ONLY_EVENT_TYPES` was **deprecated** in 10.8.0 and is removed in `11.0.0`. It stayed
exported, empty and silent (no runtime warning) through 10.x. `MissionReopened`,
`FollowUpRecorded` and `GlossarySenseUpdated` are canonical. The envelope
`schema_version` is unchanged. No existing export, schema or fixture changed, and
consumers that do not import `persistence` are unaffected.

**Core adoption:** core's guard also imports `spec_kitty_events.glossary.events`, which this
package does not provide, so core's `EVENTS_AVAILABLE` stays `False`. Core keeps its local
fallback until it drops or rewrites that import. The fallback's line bytes already equal
`append_event`'s for JSON-native records, and core's existing call shape `(event, path)` is
unchanged. This follow-up belongs to core.
(ledger-write-contract-seam; spec-kitty/spec-kitty-events#118)

## `10.7.0` — mission-lifecycle event schema + fixture reconciliation

`10.7.0` is a SemVer minor, additive. Three mission-lifecycle events core emits now
have committed JSON Schemas and conformance fixtures: `MissionReopened` and
`FollowUpRecorded` (previously model-layer-only — no schema, no fixtures) are now
schema-backed, and a new `ReviewerSelfApproval` payload is modelled in the lifecycle
layer (`project_lifecycle`), mirroring the committed `producer_contract` row. No existing
event type, model, schema, or fixture changed; the package adds nothing it imports from
`spec-kitty`/`-saas`/`-tracker`/`-runtime`. Consumers on `10.6.0` are unaffected; the new
contracts gate from `10.7.0`. (mission-lifecycle-reconciliation; spec-kitty/spec-kitty-events#119)

## `10.6.0` — subtask status vocabulary with terminal `skipped`

`10.6.0` is a SemVer minor, additive. `WPInnerStateDelta.subtasks` is now typed
`Mapping[str, SubtaskStatus]`, where `SubtaskStatus` is the active `Lane` values plus
`skipped` (the vocabulary the `wp.innerstate.changed.v1` schema already declared).
Readers that previously raised on a `skipped` subtask now parse and re-serialize it.
`not_applicable` is an input alias normalized to `skipped` at parse and never emitted.
`Status` now aliases `SubtaskStatus` (it aliased `Lane`); every previous value still
parses. `is_terminal_subtask()` treats `done` and `skipped` as terminal. The on-wire
envelope version stays `3.0.0`; no schema file changed.

## `10.5.3` — packaged total-order contract copy re-synced

`10.5.3` (spec-kitty-events#110) is a packaging patch. The total-order contract
shipped inside the wheel (`spec_kitty_events/producer_contract/total-order.md`) is a
byte-for-byte copy of the canonical `contracts/total-order.md`; a documentation fold
(#108) updated the canonical file's ADR-0001 back-reference to "Accepted" but not the
packaged copy, so the two drifted. This release re-syncs the copy. No validator,
schema, fixture or behaviour change; the envelope schema stays `3.0.0`.

## `10.5.2` — review-ref-gated rewinds documented in the status contract

`10.5.2` (spec-kitty-events#48) is a documentation and contract-clarification patch.
The `-> in_progress` rewinds from `for_review`, `in_review` and `approved` form a
second family, separate from the review-rejection family (`* -> planned`). They are
force-exempt: `force` is neither required nor needed, and consumers MUST NOT require
it. They are gated on a non-empty `review_ref`, which is required whether or not the
event is forced. A missing or blank `review_ref` yields a violation containing
`requires review_ref`. Forcing a rewind skips the status diary's `from_lane`
continuity check, so a stale forced `approved -> in_progress` could reopen a WP that
is already `done`; keeping the edges unforced keeps that check in force.

No validator change and no reducer behaviour change; the envelope schema stays
`3.0.0`. Nine conformance fixtures pin the family. The `10.5.1`, `10.4.x` and `#69`
rulings, `force`, and the Lamport `reduce_status_events` path are unchanged.

## `10.5.1` — status-diary reducer: a real departure from `approved` outranks a clock-ahead approval

`10.5.1` (spec-kitty-events#101, for spec-kitty/spec-kitty#4941) is a patch-level
behaviour change to `spec_kitty_events.diary.reduce` / `reduce_parsed`. The
snapshot shape and the envelope schema `3.0.0` are unchanged; only lane and
verdict values on certain clock-skewed logs differ. When a WP's real
`approved -> …` departure (a reviewer's `approved -> in_progress` rejection, or
an `approved -> done`) carried an earlier `at` than the `in_review -> approved`
approval it left — a reviewer whose clock ran ahead, or a teammate's approval row
arriving through `git pull` and the status-log union merge driver, which also
orders by `at` — the reducer sorted the departure before its own approval. At
that position the WP was still `in_review`, so arm-(ii) `from_lane` continuity
dropped the departure as causally stale and the stale-looking approval won: a
rejection recorded after an approval was silently lost and the WP stayed
`approved`. Now a non-seed departure from `approved` has its **sort** `at` lifted
to the `at` of the approval it followed (`_order_transitions` /
`_approved_departure_sort_at`), pairing each WP's departures to its approvals in
`(at, event_id)` order, so the departure sorts after its approval and arm (ii)
applies it. The stored `at` is never changed.

This generalises the `10.4.6` (spec-kitty-events#87) equal-`at` tie-break to the
later-`at` case; the equal-`at` behaviour and the un-skewed case are unchanged
(the lift is a no-op whenever no approval is later than its departure). The
`10.4.5` (spec-kitty#6061), `10.4.4` (spec-kitty#6058), `10.4.3`
bare-sibling (spec-kitty-events#81) and `#69` `from_lane`-continuity rulings,
real-against-real arbitration, `force`, seedless logs and the Lamport
`reduce_status_events` path are all unchanged. Consumers whose logs contain a
departure from `approved` recorded under clock skew may need to re-materialize
snapshots to pick up the corrected lanes.

## `10.5.0` — producer row schemas, the producer registry and the total order

`10.5.0` (spec-kitty-events#92, step 2 of the Mission Write API; spec-kitty/spec-kitty#6185
needs it) is a **minor**, additive release. No existing contract changes: every envelope,
payload and fixture that was accepted before is accepted now, and every one that was
rejected is rejected. The on-wire envelope schema `3.0.0` is unchanged, and
`validate_strict_envelope` still requires it. Nothing here is a reducer change.

What is new:

- **`spec_kitty_events.producer_contract`**: a registry of the 27 row kinds the Spec Kitty
  producer emits (`ai.spec-kitty.<name>.v1`), one self-contained JSON Schema (2020-12) per
  kind for the CloudEvents `data` member, a minimal envelope schema for rows the producer
  writes, and loaders. `row_schema()` composes the schema of a whole CloudEvents line of one
  type; `legacy_row_form_schema()` composes the schema of the persisted legacy line from the
  same data schema and the registry's lifted members, so no schema is authored twice.
- **Producer conformance fixtures** under `producer_contract/fixtures/rows/`: valid
  CloudEvents lines with their legacy rows, invalid lines that each pin the path and keyword
  they must fail on, and invalid legacy lines.
- **`spec_kitty_events.total_order`** and `contracts/total-order.md`: the total order over
  rows in CloudEvents form (Lamport clock, then time, then id, then source; clockless rows
  first), a reference implementation, and thirteen fixtures (the nine worked examples of
  #6185 and four edge cases).
- Packaged tests, so `pytest --pyargs spec_kitty_events.conformance` runs both fixture sets.

What this release does not do, and #92 keeps: the envelope validator and the upcaster for
legacy rows, the legacy fixture corpus, the full type registry, a Java binding, the reducer
sort-key switch, and the `3.0.0` to `4.0.0` envelope cutover with its major release.

**Status: non-normative.** The schemas, the registry and the order are published ahead of
the cutover release of ADR 0001 (Accepted 2026-10-15; the cutover is not yet released). No consumer or producer gates on them:
`schemaversion` `4.0.0` is the CloudEvents form and the strict profile does not accept it.
Per the versioning contract a new event type is additive, and a minor version does not
make an unknown type safe for every consumer: a producer must keep emission of these types
gated until each intended consumer can validate it.

**Interim choices, awaiting maintainer confirmation.** They come from the spec-kitty
operator for #6185: the type prefix `ai.spec-kitty`; ordering option C with `source` as the
final tie-break; ULID-only ids for new events; snake_case `data` members; `actor` and
`executionmode` as extension attributes; `.vN` new-type versioning; the type names and
lifted members; and a placeholder `dataschema` base URI
(`https://schemas.example.invalid/spec-kitty/`) kept in one registry field until a domain
the project controls is named. The prefix and the base are part of every type string and
`dataschema`, so changing either after emission is breaking.

**Between this release and the cutover** the schemas, the registry and the order are
non-normative and may change in a minor release, announced in `CHANGELOG.md`; only the
cutover release fixes them. Consumers should pin the exact version they validated against.

**Roles.** Producers: nothing changes until you choose to emit; the schemas are the
contract to write against. Consumers: nothing changes; the `3.0.0` and diary readers and
the reducer are untouched. Local-CLI compatibility is not weakened.

## `10.4.6` — status-diary reducer: an equal-`at` `approved -> done` settles its approval

`10.4.6` (spec-kitty-events#87) is a patch-level behaviour change to
`spec_kitty_events.diary.reduce` / `reduce_parsed`. The snapshot shape and the
envelope schema `3.0.0` are unchanged; only lane and verdict values on certain
migrated logs differ. When a WP's real `in_review -> approved` and its
`approved -> done` shared the same `at` and the `done` sorted before the
approval by `event_id`, the reducer dropped the `done` (arm-(ii) continuity saw
it leave `approved` while the WP was still `in_review`) and did not count it as
building upon the approval, so the approval was judged bare and a later
rollback-shaped `migration:` seed reopened the WP. Now a real `approved -> …`
sorts after its same-`at`, same-WP approval (`_order_transitions`), so arm (ii)
continuity applies the `done` and the WP's final setter is the `done` event
rather than a bare approval; the later rollback-shaped seed then has no
bare-sibling approval to contest — the WP stays `done`/`approved`. The sort
tie-break is the whole fix; `_is_bare_sibling` is unchanged (it still returns
`False` for a non-approval setter like the `done`).

Ordering changes only when a same-WP, same-`at` real approval exists; the
`10.4.5` (spec-kitty#6061), `10.4.4` (spec-kitty#6058) and `10.4.3`
bare-sibling (spec-kitty-events#81) rulings, real-against-real arbitration,
`force`, seedless logs and the Lamport `reduce_status_events` path are
unchanged. Consumers with non-terminal migrated missions whose approval/done
pairs share a timestamp may need to re-materialize snapshots to pick up the
corrected lanes.

## `10.4.5` — status-diary reducer: a rollback-shaped seed never takes a lane against the real lifecycle

`10.4.5` (spec-kitty/spec-kitty#6061) is a patch-level behaviour change to
`spec_kitty_events.diary.reduce` / `reduce_parsed`. The snapshot shape and the
envelope schema `3.0.0` are unchanged; only lane, actor and verdict values on
migrated logs differ. A rollback-shaped `migration:` seed (a restatement of a
rejection, e.g. `in_review -> in_progress` with a `changes_requested` verdict)
used to take the lane through the rollback exemption whatever the WP's real
lifecycle, reopening a `done` WP or overriding a later compatibility repair.
Now:

- **R1, arriving.** A rollback seed applies only when its `from_lane` continues
  the lane in force. Exceptions: its bare sibling approval (a non-rollback
  `-> approved` from the same lane that no real event left `approved` after),
  an earlier rollback seed in force, and `force`. A rollback seed that loses is
  a continuity loser, so its payload still folds (`10.4.4`), also at an equal
  `at`.
- **R2, setter.** An applied rollback seed yields the lane to a real event, as a
  forward seed does, except to its bare sibling approval. The WP is no longer
  stranded at the seed's lane by a later real claim or `done`.
- **R3.** A seed never uses the forward-seed-setter exemption.
- Equal `at` follows from R1 and R2: the outcome no longer depends on which
  `event_id` sorts first. Real-against-real arbitration, `force` and logs with
  no seeds are unchanged, as are the `10.4.3` bare-sibling ruling and the
  `10.4.4` seed-owns-its-verdict ruling.

Verdict change in the losing-seed pair: a rejection seed that loses, then an
approval seed that loses (a synthetic case: the 079 setup followed by a later
approval seed; no mission in the spec-kitty corpus has it, and the real 079
keeps `changes_requested`), now records `approved` (the later
cycle's verdict folds); `10.4.4` recorded `changes_requested` because the
rejection seed had taken the lane and owned the verdict.

Consumers with non-terminal migrated missions may need to re-materialize their
snapshots to pick up the corrected lanes. New golden replay
`status-diary-replay-seed-lane-authority`.

## `10.4.4` — status-diary reducer: a reconstruction seed that loses the lane contest still folds its unwritten payload

`10.4.4` (spec-kitty/spec-kitty#6058) is a patch-level behaviour change to
`spec_kitty_events.diary.reduce` / `reduce_parsed`. The snapshot shape is
unchanged; only slot values on migrated logs differ. A `migration:`
reconstruction seed whose `from_lane` does not continue the lane in force used
to be dropped whole, losing the claim attribution (`agent`, `shell_pid`,
`shell_pid_created_at`) and the backfilled `review_result` the migration was
minted to preserve. Now:

- A seed dropped by the `from_lane` continuity rule folds each claim slot that
  no real event has written, using the same acceptance as an applied
  `planned -> claimed` (`shell_pid` and `shell_pid_created_at` when not `None`,
  `agent` when non-empty), and folds its `review_result` when no real event has
  written one (a real verdict, or the explicit `None` of a verdict-less
  `in_review` exit, counts as written).
- A real event's value always stands for its slot, whichever side of the seed it
  sorts. A real write of `shell_pid` voids a seed-supplied
  `shell_pid_created_at`, so a live pid is never paired with a legacy baseline.
- A seed that loses a same-timestamp contest to a rollback folds nothing, and a
  losing seed never changes `lane`, `actor`, `last_event_id`,
  `last_transition_at` or `force_count`.
- Real events in a log with no seeds are unchanged from `10.4.2`. A seed that is
  applied behaves as in `10.4.2` too, with one exception (I3): a real `shell_pid`
  write voids a seed-supplied `shell_pid_created_at`, whether the seed was applied
  or folded, so a later real claim carrying a pid but no baseline no longer keeps
  the seed's baseline.
- Two seeds sharing an `event_id` with different content resolve by content (the
  canonical-minimum row) rather than by file order. Real events keep
  first-occurrence deduplication.

Out of scope at `10.4.4`: a rollback-shaped seed such as
`in_review -> in_progress` still applied through the rollback exemption. Closed
in `10.4.5` (spec-kitty/spec-kitty#6061).

## `10.4.3` — status-diary reducer: a backfilled rejection is never overwritten by a bare concurrent approval

`10.4.3` (spec-kitty/spec-kitty#4941) closes the remaining `approval after
rejection` sibling of the `10.4.0` (#69) / `10.4.1` (#5967) reducer fixes
(`spec_kitty_events.diary.reduce` / `reduce_parsed`). `10.4.1` added a seed
exemption so a reconstruction seed (`migration:` actor) as current setter does
not reject a WP's later real lifecycle. That exemption was unconditional: it
also lifted arm (ii)'s `from_lane`-continuity drop when the seed was itself a
committed **rejection**. The hazard: a backfilled rejection cycle leaves the WP
in `in_progress` (the rejection branched off `in_review`), then a bare
`in_review -> approved` approval carrying **no rework** between it and the
rejection branches off that same `in_review` state. It is causally concurrent
with the rejection, not a rework following it, yet the seed exemption let it
win — the WP resolved to `approved` with verdict `approved` when it should have
stayed in the reclaimed lane with verdict `changes_requested`. The fix narrows
the seed exemption: it lifts the drop only for a seed that is a forward
restatement, never for a seed that is a rollback/rejection. A seed restating a
rejection keeps the rejection's authority (arm (ii) still drops the concurrent
approval, regardless of its wall-clock `at`); only a non-rejection seed (e.g.
`planned -> claimed`) still yields to the real lifecycle. The `10.4.1` #5967
cases are unaffected — their seeds are not rollbacks and their real setters (a
cancel, a rework claim) are not seeds. Like its predecessors this corrects a
**defect** in the `reduce()`/`reduce_parsed()` consumer role — a rejected WP
silently resurfacing as `approved` was never a contracted outcome — so it is a
bug fix, not a promised-behavior change. The envelope contract is untouched (no
wire shape, required field, or forbidden-key change), so the on-wire schema
version stays `3.0.0`; the patch bump conservatively flags the corrected
reducer output for consumers that pin conformance goldens. Exemptions are
otherwise unchanged: a rollback event is authoritative and always applies, and
`force=True` is the deliberate break-glass override.

## `10.4.1` — status-diary reducer: `from_lane` continuity now holds for every current setter, not only a rollback

`10.4.1` (spec-kitty/spec-kitty#5967) completes the `10.4.0` (#69)
`status.events.jsonl` reducer fix (`spec_kitty_events.diary.reduce` /
`reduce_parsed`). `10.4.0` dropped a causally-stale forward transition (whose
`from_lane` did not match the lane in force) **only while the current lane had
been set by a review rollback**. Two further split-brain shapes escaped that
narrow gate because the current setter was itself a forward transition: a
lead's pushed `in_progress -> canceled` overwritten by a teammate's stale
`in_progress -> for_review` (current setter = the cancel), and a reviewer's
pushed `in_review -> planned` rejection the implementer had already reclaimed
(`planned -> claimed -> in_progress`) overwritten by a second reviewer's stale
`in_review -> approved` (current setter = the rework claim). In both the stale
event's `from_lane` does not match the lane in force, so arm (ii) of
`_should_apply_event` now enforces `from_lane` continuity for **all**
non-rollback setters rather than special-casing a rollback setter. This is a
strict superset of the `10.4.0` rule: every verdict it produced is preserved,
and the delta is exactly the newly-dropped stale non-rollback-setter cases.
The change also shifts the tie-break for any two divergent transitions that
fork from a common base lane toward causal continuity (the first-sorted fork
wins, a concurrent later-`at` fork off the same base is dropped), consistent
with the module's Lamport/causal direction and still fully deterministic under
the total `(at, event_id)` sort. Like `10.4.0` this corrects a **defect** in
the `reduce()`/`reduce_parsed()` consumer role — a pushed cancel or rejection
silently reverting was never a contracted outcome — so it is a bug fix, not a
promised-behavior change. The envelope contract is untouched (no wire shape,
required field, or forbidden-key change), so the on-wire schema version stays
`3.0.0`; the patch bump conservatively flags the corrected reducer output for
consumers that pin conformance goldens. Exemptions are unchanged: a rollback
event is authoritative and always applies, and `force=True` is the deliberate
break-glass override.

## `10.4.0` — status-diary reducer: a causally-concurrent rejection is never overwritten by a later approval

`10.4.0` (spec-kitty/spec-kitty-events#69) fixes the `status.events.jsonl`
reducer's (`spec_kitty_events.diary.reduce`) rollback-precedence
arbitration. Before this release, a reviewer's `in_review -> planned`
rejection could be silently overwritten by a stale `in_review -> approved`
approval that carried a later wall-clock `at` -- the arbitration only
compared timestamps that were EXACTLY equal, so any later-`at` forward
transition won unconditionally regardless of which branch it causally
followed. Two defects, both closed: (1) `_is_rollback_event` recognized only
`-> in_progress` as a rollback target, missing the canonical
`move-task --to planned` rejection shape; (2) the precedence check never
fired for a later (non-equal) timestamp. The fix adds a from-lane-continuity
rule: a forward transition never overwrites an already-applied rollback
unless its `from_lane` matches the lane the rollback left the WP in --
i.e. it must have causally followed the rollback, not branched off the same
prior `in_review` state. A genuine rework chain after a rejection (e.g.
`planned -> claimed -> ... -> approved`, each step's `from_lane` matching the
prior `to_lane`) is unaffected and still reaches `approved`. This corrects a
**defect** in the `reduce()`/`reduce_parsed()` consumer role: a rejected WP
silently reverting to `approved` was never an intended, contracted outcome a
consumer could rely on, so restoring the correct precedence is a bug fix, not
a change to a promised behavior. The envelope contract is untouched (no wire
shape, required field, or forbidden-key change), so the on-wire schema version
stays `3.0.0` per
[`contracts/versioning-and-compatibility.md`](contracts/versioning-and-compatibility.md);
the package minor bump conservatively flags the corrected reducer output for
consumers that pin conformance goldens. Every downstream consumer of the
reducer (the CLI's `status.reducer`, the SaaS repo dossier) inherits the
corrected precedence automatically; the new packaged conformance fixture
pair `status-diary-replay-concurrent-reject-beats-approve[-output]` pins the
corrected outcome for consumers that verify against the conformance suite.

## `10.3.0` — bounded cross-mission coordination messages (new volatile family)

`10.3.0` (spec-kitty/spec-kitty-events#54) adds one volatile event type,
`CoordinationMessage`, to the `zeitgeist_attrs` vocabulary: the bounded
cross-mission message contract the planning#2163 spike demonstrated, over
the existing relay transport. Additive for every existing consumer: no
existing event type, payload field, or frame shape changes, and consumers
that do not know the new type treat it exactly as they treat any
`UnknownVolatileEventTypeError` today. Consumers that pin the volatile
vocabulary by name (SaaS attr-key validation, Slack rendering) must adopt
this version (or later) before any producer sends the type — the same
rollout order rule as `10.2.0`'s `summary` attr. `contract_version` rides
as an explicit attr and both codec directions reject an unknown version
with a named error, so a future revision of this payload shape can never
be silently misinterpreted. `sender_agent_id` is an untrusted
logical-agent label; the authenticated principal stays server-derived and
`FORBIDDEN_COORDINATION_KEYS` fails closed on any payload that claims it.
Payload contract only — no runtime send tool exists or is advertised
(spec-kitty#4269 is the future producer). `10.3.0` rather than an
in-place `10.2.0` amendment because `scripts/check_version_not_amended.py`
spends a version once it has been declared on main, adopted or not.

## `10.2.0` — dedicated inline `summary` attr for `WPStatusChanged`

`10.2.0` (spec-kitty/spec-kitty#4327, events#60) gives a WP transition's
moment a bounded, human-readable gist: `StatusTransitionPayload` gains an
optional `summary` field — a producer-supplied one-line gist of the
transition — and `WPStatusChanged` joins `SUMMARY_SOURCE_EVENT_TYPES`, so
its zeitgeist projection carries the bounded derived `summary` attr (the
same pattern the `*Completed` and Ops invocation kinds already use).
Additive in both directions: old producers omit the field and old frames
still decode (the attr key is optional on decode, like every derived
summary; the payload field is optional, `extra="forbid"` unchanged). The
raw `summary` field is `UNBROADCAST_FIELDS`-local alongside `reason`, so
no frame shape that was accepted or rejected before changes behavior.
Producers (the CLI) MUST validate the gist at creation (one printable
line, at most 240 UTF-8 bytes) and keep `review_ref` a pointer; the codec
still bounds the derived attr independently of whatever the producer
validated. SaaS consumers that reject unknown attr keys must adopt this
version (or later) before the CLI sends the field — see
spec-kitty/spec-kitty-saas#1863. `10.2.0` rather than an in-place `10.1.0`
amendment because `scripts/check_version_not_amended.py` spends a version
once it has been declared on main, adopted or not.

## `10.1.0` — durable live-work contracts land before producer enablement

`10.1.0` adds the `WorkObservation` event type (25 durable kinds,
`contracts/durable-work-observation.md`) — an additive change: every
existing event contract keeps its accept/reject boundary. Tool/test
actions carry a typed lifecycle `state` (started/running/result/cancelled;
outcome and counts only at `result`), file actions carry a typed
`operation` (read/edit/create/delete/rename, with rename
`destination_path` and a repository-relative path grammar accepting spaces
and Unicode), mission binding is conditional on semantic kind (required
for the six lifecycle kinds only), and an `agent_profile` combines with a
`factory_attempt` — all within the still-unreleased 10.1.0 contract, so no
adopted version is amended. Producers MUST
capability-gate `WorkObservation` emission until each intended consumer
(SaaS durable ingestion, Zeitgeist relay fan-out, SaaS projections) can
validate it; the `10.1.0` `min_consumer_package` on the 25 new
support-matrix rows is that floor. Consumers that pin
`declared_dependency_contracts` by digest must re-pin against the new
`support_matrix.json` deliberately, not silently.

## `10.0.6` — contract-version table synchronization is pinned

`10.0.6` keeps the contract-version event registration and known-version
mapping in sync, and clarifies that both projection directions reject an
unknown Ops Invocation contract version.

## `10.0.5` — contributor metadata and release hygiene

`10.0.5` contains no compatibility-boundary changes. It retires the "known gap"
framing for the encode-side control-character rejection now that
EXPERIMENTAL-spec-kitty-events#104 has shipped, recording that behavior once as a
dated `8.2.1` entry, and carries the repository formatting the version-amendment
guard requires. Consumers need no action.

## `10.0.4` — timezone guard cleanup (non-breaking)

This patch release changes no contract, wire encoding, or wire decoding. The
timestamp shape check already requires a UTC offset, so the previously
separate timezone-awareness branch in `from_zeitgeist_attrs` was unreachable;
it is removed without changing any accepted or rejected timestamp.

## `10.0.3` — MissionCreated conformance floor correction (non-breaking)

This patch release changes packaged documentation and conformance metadata
only; it changes no Python runtime behavior, event payloads, wire encoding, or
wire decoding. The `mission_created_mission_id_present` fixture now declares
its true capability floor, `8.2.0`, because that release is the first released
codec whose derived `summary` attribute reproduces the fixture's complete
`expected_attrs` shape. The compatibility narrative above now distinguishes
that floor from `mission_id` itself, which `MissionCreated` has decoded since
`8.0.0`.

## `10.0.0` — mixed ISO-8601 `occurred_at` spellings rejected (breaking)

`from_zeitgeist_attrs` now rejects an `occurred_at` value that combines
ISO-8601's basic date with its extended time, or its extended date with its
basic time (for example, `20260825T09:00:00Z` or `2026-08-25T090000Z`).
ISO-8601 requires one spelling across the date and time. The check is a
positive calendar-date shape match, so it also rejects reduced-precision
mixed spellings, arbitrary single-character separators that Python 3.11+
`fromisoformat` accepts, and week-date mixes.

The accepted spellings are:

- extended: `YYYY-MM-DD[T ]HH:MM[:SS]`
- basic: `YYYYMMDD[T ]HH[MM[SS]]`

Both spellings may carry a decimal fraction and `Z`, `±HH:MM[:SS]`, or
`±HHMM[SS]`.
Decode reshapes only its private parsing candidate, so accepted wire bytes —
including a valid basic timestamp — remain unchanged in the returned attrs.
Python 3.11+ previously accepted the malformed examples above while Python
3.10 rejected them; Python 3.10 also rejected valid basic timestamps. This
release makes both outcomes consistent across supported Python versions.

This is a consumer-visible narrowing and widening of the attrs decode
boundary, so it is a major package bump. Producers that emit
`datetime.isoformat()` or otherwise keep one spelling across the date and
time are unaffected. Producers carrying mixed spellings must emit one of the
forms above consistently.
Seconds-precision UTC offsets — including those rendered by a historical
`zoneinfo` timestamp's `datetime.isoformat()` — remain accepted in both the
colon-separated and basic spellings.
No envelope schema, event type, payload model, or attrs key changes in this
release.

## `9.1.6` — no contract change (typing + public packaging)

`9.1.6` is the first release published to public PyPI from the converged
`Priivacy-ai/spec-kitty-events` line. It contains no behavior or contract
change relative to `9.1.5`: strict-typing fixes in `zeitgeist_attrs`
summary helpers (same attribute reads via `getattr`) and the packaged
conformance test, plus packaging metadata (project URLs, README license
section).

## `9.1.5` — decoded detail refs follow canonical event IDs

`9.1.5` fixes a follow-up to the `9.1.2` decode guard. A wire frame whose
`event_id` and derived `detail_ref` use the same noncanonical spelling was
accepted, but the decoded `VolatileMoment` kept the canonicalized
`event_id` beside the raw `detail_ref`. Decode now rewrites that derived
`detail_ref` to the canonical `event_id` spelling as well, keeping the
decoded attrs self-consistent. Canonical frames are unaffected.

## `9.1.4` — missing Ops Invocation `detail_ref` rejection is pinned

`9.1.4` adds a packaged conformance fixture for an inbound
`OpsInvocationStarted` frame that omits the required derived `detail_ref`
attr. Decode already rejected that frame, so no runtime behavior changes;
the fixture prevents a future refactor from silently dropping the
requirement.

## `9.1.3` — encode rejects unknown Ops Invocation contract versions

`9.1.3` tightens `to_zeitgeist_attrs` for every contract-versioned event
type. Before encoding, it checks the payload's `contract_version` against
the same `KNOWN_CONTRACT_VERSIONS_BY_EVENT_TYPE` table used by
`from_zeitgeist_attrs`. A producer that mis-stamps version `1` payloads with
an unknown version now fails locally with `UnknownContractVersionError`
rather than emitting a frame this same package version discards on decode.
Known-version frames are unaffected.

## `9.1.2` — derived `detail_ref` attrs must resolve to their own moment

`9.1.2` tightens `from_zeitgeist_attrs` for the Ops Invocation event types.
Their `detail_ref` attr is mechanically derived as
`"<event_type>:<event_id>"`, so decode now rejects a wire frame whose
`detail_ref` points at another event or uses an unrelated value. Frames
produced by this package are unaffected; only malformed inbound frames that
previously decoded are rejected.

## `9.1.1` — timestamp parsing normalized across supported Python versions

`9.1.1` is a parser bug fix, not a new event family or payload field. The
strict envelope validator, retrospective timestamp validation, and packaged
timestamp conformance helper now normalize supported ISO-8601 spellings
before calling `datetime.fromisoformat`, so Python 3.10 accepts the same
fractional-second precision, basic format, reduced precision, and numeric
offset spellings that Python 3.11+ already accepted. No producer or consumer
migration is required.

## `9.0.2` — mixed-case doubled `Z` UTC designators are rejected

`9.0.2` tightens timestamp validation at three normalization sites:
`strict.validate_strict_envelope`, the retrospective payload validators, and
the packaged conformance timestamp helper. A malformed value ending in `zZ`
is now rejected instead of being normalized to a lowercase-`z` form that some
supported Python interpreters accept. Producers already sending one valid UTC
designator are unaffected.

## `9.0.1` — `zeitgeist_ref_for` rejects control characters in the derived ref

`9.0.1` tightens the producer-side reject boundary for volatile moments.
`zeitgeist_ref_for` now applies the same `str.isprintable()` check as attrs
values to its derived `ref`, so a control or formatting character in a ref
source such as `mission_slug`, `mission_id`, `run_id`, or
`decision_point_id` raises `ZeitgeistAttrsControlCharacterError` instead of
reaching the relay. Producers with printable refs are unaffected; producers
that previously relied on non-printable ref values must clean or reject those
values before calling the codec.

## `9.0.0` — `mission_id` widened onto `WPStatusChanged`/`MissionCreated`/`MissionClosed` for cross-family join (breaking)

`9.0.0` is a **breaking accept/decode-boundary change**, not additive,
despite the Pydantic-model diff itself being a new optional field.
`PhaseEnteredPayload` has always used `mission_id` (required) as its
`REF_FIELD_BY_EVENT_TYPE` frame ref, while the other three mission-scoped
volatile families — `WPStatusChangedPayload` (`StatusTransitionPayload`),
`MissionCreatedPayload`, and `MissionClosedPayload` — use `mission_slug` as
their ref. Until this release, `StatusTransitionPayload` and
`MissionClosedPayload` did not declare `mission_id` at all, so
`from_zeitgeist_attrs`'s closed, schema-derived key vocabulary
(`_schema_keys_for_model`) never admitted it for either family: an attrs
frame carrying `mission_id` for a `WPStatusChanged` or `MissionClosed`
moment raised `ZeitgeistAttrsError` on decode. A consumer therefore had no
shared key to join one of their moments against a `PhaseEntered` moment for
the same mission aggregate — and, symmetrically, no way to receive one
without it being rejected.

This release adds an optional `mission_id` field (default `None`) to
`StatusTransitionPayload` and `MissionClosedPayload`; when a producer
populates it, `mission_id` rides alongside `mission_slug` in the encoded
`to_zeitgeist_attrs` projection for all three families. Per
[`EXPERIMENTAL-spec-kitty-planning`#1012](https://github.com/spec-kitty/EXPERIMENTAL-spec-kitty-planning/issues/1012),
this is Option 2: `REF_FIELD_BY_EVENT_TYPE` itself is unchanged —
`PhaseEntered`'s ref stays `mission_id` and the other three keep
`mission_slug` as their ref — only the attrs widen.

**Why this is major, not minor.** Per
[`contracts/versioning-and-compatibility.md`](contracts/versioning-and-compatibility.md),
"any change to an existing event contract that makes a previously-rejected
envelope accepted" is major. A producer that starts populating `mission_id`
on `WPStatusChanged`/`MissionClosed` moves those two families' encoded
attrs from a shape every `<9.0.0` decode rejected to one every `>=9.0.0`
decode accepts — the accept/reject boundary moved, which is exactly the
"new capability that widens an existing family's contract" case the
`6.1.0` new-event-type precedent does *not* cover (a whole new event type
is additive because no existing contract's boundary moves; this is the
opposite: an existing family's own boundary moves). `MissionCreatedPayload`
is excluded from this classification — it already declared `mission_id`,
and `mission_id` has been in `MissionCreated`'s schema-derived key set
since `8.0.0`, so `MissionCreated`'s decode boundary already admitted the
key from that release onward. The broadcast key vocabulary
itself changed at `8.1.0` (the prose fields `friendly_name`/`purpose_tldr`/
`purpose_context` were dropped) and `8.2.0` (the derived `summary` attr was
added, via `_schema_keys_for_model`, which did not exist before `8.2.0`).
This bump changes nothing about `MissionCreated`'s compatibility story; the
only new artifact for that family here is the
`mission_created_mission_id_present` conformance fixture
(`min_version: 8.2.0` — the fixture pins the derived `summary` attr, which
no released codec reproduces before `8.2.0`, even though `mission_id`
itself was decodable since `8.0.0`), which exercises a capability that
already shipped rather than a new one.

**Producers that omit `mission_id` are unaffected**: the field stays
optional and is omitted from attrs when absent, so a producer that never
populates it is wire-compatible with every `<9.0.0` consumer, exactly as
before. The breaking surface is scoped entirely to producers that *do*
populate the field.

**Required migration.**

- Consumers (`spec-kitty-saas`, `spec-kitty`, any other reader of
  `WPStatusChanged`/`MissionClosed` zeitgeist attrs) must bump their
  `spec-kitty-events` pin to `>=9.0.0` before a producer in their path
  starts populating `mission_id` on these two families.
- Producers MUST capability-gate the rollout exactly like the `6.0.0`
  `genesis`-lane precedent (see
  [`contracts/lane-vocabulary.md`](contracts/lane-vocabulary.md)'s
  "Versioning" section): do not populate `mission_id` on
  `WPStatusChanged`/`MissionClosed` for a given broadcast until every
  consumer that will read it is known to be on `>=9.0.0`. Populating it
  while any live consumer is still pinned `<9.0.0` causes that consumer's
  `from_zeitgeist_attrs` call to raise on decode, silently dropping the
  moment.
- `EXPERIMENTAL-spec-kitty-events#69` closes with this artifact set;
  `EXPERIMENTAL-spec-kitty-events#197`/`#198` track the two MINOR
  documentation/conformance-coverage follow-ups the squad also filed
  against the originating PR.

## `8.2.1` — forbidden attrs rejected in every dot segment (breaking boundary shipped without a major bump)

PR
[`EXPERIMENTAL-spec-kitty-events#139`](https://github.com/spec-kitty/EXPERIMENTAL-spec-kitty-events/pull/139)
widened `_forbidden_key_hits` on both `to_zeitgeist_attrs` encode and
`from_zeitgeist_attrs` decode. The old predicate rejected an exact forbidden
key and a forbidden trailing segment, but missed the same name in a prefix or
middle segment. Consequently `token.sub`, `url.href`, `team.name`, and
`a.token.b` previously passed; starting at implementation commit `d18a67a`
(merge commit `1e21a29`) each is rejected because at least one dot-separated
segment belongs to `FORBIDDEN_ATTR_KEYS`.

This is a **breaking reject-boundary widening**, not an additive validation
improvement. `contracts/versioning-and-compatibility.md` explicitly classifies
"adding to the forbidden-key set, or widening where it is enforced" as major:
a producer or consumer can move from accepting an identical attrs frame to
raising the existing forbidden-key error solely by updating this package.
The behavior is intentional and security-motivated; forbidden names include
`token`, `authorization`, `bearer`, `password`, `url`, and team/deployment
identifiers that must not leak into broadcast attrs.

**Historical version boundary.** The package declared `8.2.1` at `1e21a29`.
That version number had already been used at earlier trees, so `8.2.1` alone
cannot identify whether this rule is present: exact git pins before `1e21a29`
lack it, while pins at or after that merge contain it. The later `9.0.0`
release records a separate `mission_id` accept-boundary widening; it does not
retroactively make PR #139 a correctly-versioned 9.0.0 change. This section
documents the shipped boundary rather than rewriting package history.

**Required migration.** Before moving a consumer or producer pin across
`1e21a29`, inspect every emitted and stored zeitgeist attrs key. Rename or
remove a key when any dot-separated segment matches `FORBIDDEN_ATTR_KEYS`;
do not bypass or weaken the guard. Consumers already pinned to current
`9.0.0` have this rejection behavior.

## `8.2.1` — `to_zeitgeist_attrs` rejects control characters on encode, matching decode

`from_zeitgeist_attrs` rejects an attrs value carrying a non-printable
character on decode (`str.isprintable()`, EXPERIMENTAL-spec-kitty-events#25,
then widened by #63). `to_zeitgeist_attrs` now runs the same check on
encode (EXPERIMENTAL-spec-kitty-events#64, closed by PR #104): a producer
whose `actor`/`review_ref`/id field carries a control character now fails
closed at encode time with `ZeitgeistAttrsControlCharacterError`, instead
of successfully broadcasting a value that a consumer's decode would later
reject, silently dropping the moment. Both directions share one predicate
and raise the same typed error.

## `8.0.0` — Sync, legacy-envelope, and cutover surfaces deleted

`8.0.0` deletes three modules whose only consumer was the offline
CLI→SaaS sync/replay story that the programme retired:

- `spec_kitty_events.sync` (Sync lifecycle contracts + reducer) — had no
  production consumers.
- `spec_kitty_events.legacy` (`legacy_envelope_v1` normalizer).
- `spec_kitty_events.cutover` (`CUTOVER_ARTIFACT` and helpers).

Consumer-visible consequence beyond the missing imports: `validate_event()`
no longer applies the envelope-level cutover gate. An envelope with a
missing or non-canonical `schema_version`, or one carrying forbidden legacy
keys/names at envelope level, can now pass `validate_event` when its payload
shape validates. Fail-closed envelope validation still exists as the opt-in
strict profile — `validate_strict_envelope()` enforces
`schema_version == "3.0.0"`, the recursive forbidden-key walk
(`forbidden_keys.find_forbidden_keys`), the full envelope key set, and the
forbidden legacy aggregate-name prefixes (`strict.FORBIDDEN_LEGACY_AGGREGATE_NAMES`,
re-homed from the deleted artifact per issue #10).
Producers and ingress paths that relied on `validate_event` for the gate
must switch to the strict profile.

That switch is a single call only for the event types
`spec_kitty_events.strict.STRICT_EVENT_TYPES` admits (the fixed
lifecycle/WP/project/harness allowlist) — `validate_strict_envelope()`
rejects every other event type with `UNKNOWN_EVENT_TYPE` regardless of
whether the envelope is otherwise well-formed. Callers whose event type is
outside that allowlist must reproduce the removed gate's checks directly
instead: `forbidden_keys.validate_no_forbidden_keys(record,
forbidden=FORBIDDEN_LEGACY_KEYS) is None` for the recursive legacy-key walk
(callers needing every error rather than just the first can use
`list(forbidden_keys.find_forbidden_keys(record,
forbidden=FORBIDDEN_LEGACY_KEYS))` instead), an
explicit `record.get("schema_version") == "3.0.0"` check for the envelope
signal, `(not isinstance(aggregate_id := record.get("aggregate_id"), str)) or
aggregate_id.split("/", 1)[0] not in strict.FORBIDDEN_LEGACY_AGGREGATE_NAMES`
for the forbidden legacy aggregate-name prefix, and
`record.get("event_type") not in {"FeatureCreated",
"FeatureClosed"}` for the forbidden legacy event names (see `## Forbidden
Legacy Surfaces` below for that list's source). The `isinstance` guard on
the aggregate-name check matters: a wire record can carry `aggregate_id:
null` or another non-string, and `strict.py`'s own gate
(`isinstance(aggregate_id, str)`) treats that as not-forbidden rather than
raising, so a recipe that instead does `record.get("aggregate_id",
"").split(...)` raises `AttributeError` on exactly that input instead of
reproducing the gate. Unlike the other three checks, no package constant
survives for the legacy event names — they were deleted outright in
`8.0.0` and not re-homed — so this document is the only place a caller
outside the strict profile can find them.

Migration: pin `>=8.0.0`; delete or re-home any import of the three
modules. There are no aliases. See `CHANGELOG.md` (`### Breaking`) for the
full removed-name list.

## `7.0.0` — Strict journal profile + HarnessObservation

`7.0.0` publishes `spec_kitty_events.strict.validate_strict_envelope`: an
opt-in, deterministic, structured validator over the existing envelope and
lifecycle/WP contracts (`Event` itself is unchanged and stays lenient — see
`### Breaking` below for what *is* newly rejected by the strict profile).
It also publishes `spec_kitty_events.harness_observation`: a new, volatile
`HarnessObservation` event family with exactly six payload IDs
(`harness.presence.v1`, `harness.lane_signal.v1`, `harness.focus_started.v1`,
`harness.focus_heartbeat.v1`, `harness.focus_paused.v1`,
`harness.focus_ended.v1`). Observations are never reduced into mission/WP
state and carry no time/TTL/user/team field.

The machine-readable **support matrix** —
`spec_kitty_events.strict.SUPPORT_MATRIX` / the published
`support_matrix.json` package-data copy, generated by `schemas/generate.py`
and drift-checked alongside every other schema — is the authority for
"which payload IDs / event types this package supports": 14 journal rows,
16 volatile rows (the Ephemeral Team Status vocabulary — `WPStatusChanged`,
`MissionCreated`, `MissionClosed`, `PhaseEntered`, the six `mission_next`
mission-run types — plus 6 observation rows, one per `ObservationKind`,
each with its payload ID). See the `[8.0.0]` CHANGELOG section for the
E2 durability promotion and the `zeitgeist_attrs` codecs.
`spec_kitty_events.strict.support_matrix_digest()` is what downstream
candidates pin in `declared_dependency_contracts` (see
`tests/test_support_matrix.py` for the pinned row/payload-ID counts; the
row contents are not restated here per the anti-restatement rule below).

Skew story (6.x ↔ 7.0):

- a 6.x consumer calling `validate_event(payload, "HarnessObservation")`
  raises `ValueError("Unknown event type: ...")` — fail closed, unchanged
  API shape;
- a 6.x consumer given a 7.0 `MissionStarted` payload that carries the new
  optional `mission_slug` field still accepts it (the 6.x model is lenient)
  — benign forward-compat;
- a 7.0 consumer rejects a `MissionStarted` payload carrying an unknown
  extra field with `PAYLOAD_SCHEMA_FAIL` (new in `7.0.0`; the four-field
  shape with no `mission_slug` remains valid, since the field is optional).

Local-appender canonicalization debt (out of scope for this release):
every envelope written today by
`spec-kitty/src/specify_cli/status/lifecycle_events.py`'s `_build_envelope`
(`.kittify/canonical-events.jsonl`, `status.events.jsonl`) fails the strict
profile on multiple counts (`schema_version="5.0.0"`, an extra
`aggregate_type` key, several missing envelope keys) and its
`ReviewerSelfApproval` rows additionally have no events model. This
package does not rewrite those rows; local-CLI permissive reading of them
is unweakened. Canonicalizing the appender is out of scope for this
release (tracked for the journal-writer work that consumes this contract).

## Canonical On-Wire Policy

Note: the **envelope schema on the wire** is at version `3.0.0`, which is not
the package version declared at the top of this document. The two are
intentionally distinct (see the bump-rationale section below).

- Signal field: `schema_version`
- Signal location: event envelope
- Required value: `3.0.0`

Since `8.0.0` removed the cutover artifact, this gate is enforced by the
strict profile (`validate_strict_envelope`) and by the recursive
forbidden-key walker (`spec_kitty_events.forbidden_keys`), not by
`validate_event`. Live ingestion paths fail closed when any of the
following are true:

- the envelope is missing `schema_version`
- `schema_version` does not equal `3.0.0`
- the envelope or nested payload contains forbidden legacy keys (recursive walk)
- `aggregate_id` uses a forbidden legacy aggregate name (`feature`,
  `feature_catalog`) — rejected with `FORBIDDEN_AGGREGATE_NAME`

## Forbidden Legacy Surfaces

Legacy mission-domain surfaces rejected on live paths (as of `8.0.0`, by
the strict profile / forbidden-key walker rather than `validate_event`):

- keys: `feature_slug`, `feature_number`, `mission_key`, `legacy_aggregate_id`
  — rejected at any depth by `forbidden_keys.find_forbidden_keys`.
- event names: `FeatureCreated`, `FeatureClosed` — not members of
  `STRICT_EVENT_TYPES`, so the strict profile rejects them.
- aggregate name prefixes: `feature`, `feature_catalog` — the dedicated
  prefix check shipped with the cutover artifact and left `validate_event`
  with it in `8.0.0`; it was deliberately **re-homed onto the strict
  profile** (`strict.FORBIDDEN_LEGACY_AGGREGATE_NAMES`, error code
  `FORBIDDEN_AGGREGATE_NAME`) rather than dropped, so a live path that
  validates strictly still fails closed on `aggregate_id="feature/WP01"`
  (issue #10). `validate_event` stays lenient; offline rewrite jobs should
  still convert such ids.

## Canonical Mission And Build Taxonomy

Public mission-domain payloads use:

- `mission_slug`: canonical mission instance identifier
- `mission_number`: canonical numeric mission identifier
- `mission_type`: canonical workflow/template identifier

Event envelopes distinguish build and node identity explicitly:

- `build_id`: emitting build identifier
- `node_id`: emitting node identifier within that build

## Rollout Policy

There are no runtime compatibility bridges in live ingestion.

- New producers must emit canonical envelopes (`schema_version="3.0.0"`) from day one.
- Consumers must reject legacy mission-domain envelopes on live paths.
- Historical `2.x` or pre-cutover data may only be read by offline migration or rewrite jobs.
- Offline rewrite workflows must convert historical data into canonical `schema_version="3.0.0"` form before re-ingestion.

## Cross-Repo Release Gates

`spec-kitty-events` should only be treated as released when all of the following are true:

1. `spec-kitty-events` package metadata matches the version declared at the top of this document.
2. Committed JSON Schemas are regenerated and drift-free.
3. Conformance fixtures and replay goldens pass with artifact-driven validation.
4. `spec-kitty-saas` is updated to emit canonical envelopes (`schema_version="3.0.0"`) only.
5. `spec-kitty` is updated to consume canonical mission/build terminology and fail-closed validation outcomes.

## Consumer Guidance

If you operate a producer:

- emit `schema_version="3.0.0"` (see the note above)
- emit `build_id`
- emit canonical mission-domain fields only

If you operate a consumer:

- validate the envelope signal before processing payload content
- reject forbidden legacy mission-domain fields and names
- treat pre-cutover data as migration input, not live traffic

## Quick Reference

Accepted live envelope:

```json
{
  "event_type": "WPStatusChanged",
  "aggregate_id": "mission/WP01",
  "schema_version": "3.0.0",
  "build_id": "build-2026-04-05",
  "node_id": "runner-01",
  "payload": {
    "mission_slug": "mission-001",
    "wp_id": "WP01",
    "from_lane": "planned",
    "to_lane": "claimed",
    "actor": "ci-bot",
    "execution_mode": "worktree"
  }
}
```

Rejected live envelope examples:

- envelope without `schema_version`
- envelope with `schema_version="2.9.0"`
- payload containing `feature_slug`
- envelope with `event_type="FeatureCreated"`
- envelope with `aggregate_id="feature/123"`

## Versioning

The on-wire envelope schema is `3.0.0`; the package version is declared at the
top of this document. Together they publish the canonical mission/build contract
and the recursive forbidden-key gate.

The full policy — what requires a major bump, what artifacts a bump must update,
and why the two version numbers are distinct — lives in
[`contracts/versioning-and-compatibility.md`](contracts/versioning-and-compatibility.md).

- `2.x` additive compatibility language no longer applies.
- Future breaking contract changes require a new major **package** release; whether they also bump the envelope schema version depends on whether they change the wire format. Neither the `5.0.0` nor the `6.0.0` package release bumped the wire-format envelope version — both changed contract behaviour with the existing `3.0.0` envelope shape.

### Post-mission lifecycle events (6.1.0)

The `6.1.0` release is **additive and wire-compatible**. It adds the
`MissionReopened` and `FollowUpRecorded` contracts, the
`MissionStatus.REOPENED` enum member (actionable, not terminal), and their
handling in `reduce_lifecycle_events`. The envelope shape is unchanged, but
consumers that validate or switch on exact event types must be upgraded before
they receive either new type. Producers must capability-gate emission until
each intended consumer recognizes it. Consumers that never receive the new
types are unaffected.

Producers emitting post-mission lifecycle facts need `>=6.1.0`.

### Genesis lane (6.0.0)

The `6.0.0` package release adds the `genesis` canonical lane (a non-display,
pre-finalize origin state; see `CHANGELOG.md`). Like `5.0.0`, this is a major
**package** bump that intentionally does **not** bump the wire-format envelope
version — it widens the set of accepted `Lane` values (`from_lane="genesis"` on
`WPStatusChanged`) while keeping the existing `3.0.0` envelope shape. Consumers
on `<6.0.0` will fail-closed (reject) a `from_lane="genesis"` payload; producers
must therefore gate genesis fan-out on the installed package's lane capability
until every consumer is on `>=6.0.0`.

Display consumers must not derive board columns, summary chips, progress rows,
or lane filters from every `Lane` member. Use `DISPLAY_LANES` for ordered
display/summary surfaces and `NON_DISPLAY_LANES` for explicit exclusions;
`Lane.GENESIS` is canonical for validation/replay but is not displayable.

## Local-CLI compatibility vs TeamSpace ingress validity (5.0.0)

The `5.0.0` major release sharpens a distinction that has always been implicit in
`spec-kitty-events`: there are two distinct validity domains, and a row that is
acceptable in one is not necessarily acceptable in the other. This section is the
authoritative explanation. Consumers and producers must read it before assuming
that "valid" is a single global property.

### The two validity domains

- **Local-CLI compatibility.** The `spec-kitty` CLI continues to read historical
  `status.events.jsonl` rows on local disk for users' own bookkeeping —
  reconstructing a mission's history, rendering local dashboards, replaying
  status, computing diff summaries, etc. The CLI's local reader is deliberately
  permissive: it tolerates pre-cutover envelope shapes (including legacy keys
  such as `feature_slug`, `mission_key`, raw rows missing `schema_version`, and
  pre-canonical lane vocabularies) so that users do not lose access to their own
  historical data after this bump. Local compatibility is **not** weakened by
  the `5.0.0` release.

- **TeamSpace ingress validity.** Only canonical envelopes pass TeamSpace
  ingress. The ingress path runs the full fail-closed contract gate from
  `4.0.0` plus the additions landed in this mission (canonical lane vocabulary
  including `in_review`, reconciled `MissionCreated`/`WPStatusChanged`/
  `MissionClosed` payloads, and the recursive forbidden-key validator that
  rejects legacy keys at any depth, including inside array elements). A row
  that the local CLI happily reads off disk will be rejected at TeamSpace
  ingress unless it has been canonicalized first.

### Concrete examples

A historical row that is **valid for the local CLI** but **invalid for
TeamSpace ingress** (legacy `feature_slug` key, missing `schema_version`):

```json
{"event_type":"FeatureCreated","aggregate_id":"feature/123","payload":{"feature_slug":"my-feature","feature_number":7}}
```

A canonical envelope that is **valid for both** the local CLI and TeamSpace
ingress (canonical mission-domain fields, canonical lane vocabulary including
`in_review`, `schema_version="3.0.0"`, no forbidden legacy keys at any depth):

```json
{"event_type":"WPStatusChanged","aggregate_id":"mission/WP01","schema_version":"3.0.0","build_id":"build-2026-05-01","node_id":"runner-01","payload":{"mission_slug":"mission-001","wp_id":"WP01","from_lane":"claimed","to_lane":"in_review","actor":"implementer-ivan","execution_mode":"worktree"}}
```

> **Package version vs envelope schema version (read this carefully).**
> The **package version** is the value in `pyproject.toml` and
> `__version__`, declared once at the top of this document. The
> **envelope schema version on the wire** is **`3.0.0`** — the default
> for `Event.schema_version` (and the value the strict profile requires).
> They are not the same number and they do not move together. The major
> package bump from `4.x` to `5.0.0` reflected contract behaviour changes
> (`in_review` canonical, payload reconciliation, recursive forbidden-key
> validator), NOT a wire-format envelope-version bump; `6.0.0`, `7.0.0`,
> and `8.0.0` likewise. Producers must continue to emit
> `schema_version="3.0.0"` on the envelope regardless of the package
> major; the strict profile rejects anything else.

### The documented bridge

The bridge between these two domains is the **CLI canonicalizer** that ships in
`spec-kitty` Tranche B. The canonicalizer reads historical `status.events.jsonl`
rows and produces canonical envelopes (`schema_version="3.0.0"`, accepted by
the strict profile) suitable for ingress. Producers that need to
forward historical data into TeamSpace MUST run it through the canonicalizer
first; ingress will not accept raw historical rows. Consumers that read locally
MUST NOT assume their local-disk rows have already been canonicalized.

### Cross-references

- [`contracts/lane-vocabulary.md`](contracts/lane-vocabulary.md) — the canonical lane vocabulary and the major-bump rule that governs changing it.
- [`contracts/versioning-and-compatibility.md`](contracts/versioning-and-compatibility.md) — what requires a major bump and which artifacts a bump must update.
- [`kitty-specs/teamspace-event-contract-foundation-01KQHDE4/contracts/payload-reconciliation.md`](kitty-specs/teamspace-event-contract-foundation-01KQHDE4/contracts/payload-reconciliation.md) — the reconciliation log for `MissionCreated`, `WPStatusChanged`, and `MissionClosed` payloads.

### Bump rationale (per R-03)

The `5.0.0` package bump is a genuine major bump under semantic versioning.
The envelope schema version on the wire stays at `3.0.0`; only the *package*
moves to `5.0.0`. Per
research item R-03 (schema version bump semantic), each of the following is a
behavior change for at least one role and therefore each independently
justifies a major:

1. **Lane vocabulary widens.** `in_review` is now a canonical lane (FR-001,
   FR-002). Consumers that previously rejected `in_review` as unknown now
   accept it; consumers that switch on exact lane-set membership are
   behaviorally affected.
2. **Payload reconciliation.** `MissionCreatedPayload`,
   `WPStatusChangedPayload`, and `MissionClosedPayload` are now the single
   source of truth (FR-003, FR-004). CLI emission and library models have been
   reconciled; pre-bump producers of disagreeing shapes are now invalid.
3. **Recursive forbidden-key validator.** Legacy keys (`feature_slug`,
   `feature_number`, `mission_key`, plus the audit-derived expansion) are now
   rejected at any depth, including inside array elements (FR-005). Envelopes
   that previously slipped through with a deeply nested legacy key are now
   rejected.

These three changes compound: any one of them is a contract change for at
least one role, and together they require a major bump rather than a minor or
patch.

## Decision Moment V1 (4.0.0)

### Scope

- **Breaking for DecisionPoint.** The `DecisionPoint*` event family (excluding `DecisionPointOverridden`) now carries `origin_surface` and supports discriminated-union payloads. `DecisionPointResolved` (interview variant) requires `terminal_outcome`.
- **Compatible for DecisionInput.** `DecisionInputRequested` and `DecisionInputAnswered` payloads are unchanged. 3.x consumers continue to validate.

### Producer migration

| Producer                | 3.x action                         | 4.0.0 action                                                   |
|-------------------------|------------------------------------|----------------------------------------------------------------|
| ADR DecisionPoint       | Emit 3.x payload                   | Add `origin_surface: "adr"` to every payload                    |
| Interview DecisionPoint | (n/a — didn't exist)               | Use `origin_surface: "planning_interview"` + V1 fields          |
| DecisionInput* events   | Emit as-is                         | No change                                                       |

### Consumer migration

| Consumer                              | 3.x action                                       | 4.0.0 action                                                                 |
|---------------------------------------|--------------------------------------------------|------------------------------------------------------------------------------|
| DecisionPoint replay / reducer        | Reduce 3.x ADR payloads                          | Reduce ADR + V1 interview events via the single reducer (discriminated by `origin_surface`) |
| DecisionInput* consumers              | Consume as-is                                    | No change                                                                    |
| Slack orchestrator                    | (n/a)                                            | Subscribe to `DecisionPointWidened`; post closure message on `DecisionPointResolved` |
| Teamspace projection                  | (n/a)                                            | Project V1 fields from `DecisionPointResolved` interview variant             |

### Terminal outcome / write-back rules

- `DecisionInputAnswered` is emitted ONLY when `DecisionPointResolved.terminal_outcome == "resolved"` AND `final_answer` is populated. Deferred and canceled outcomes do NOT emit a `DecisionInputAnswered` (no answer exists).
- `DecisionPointResolved.closed_locally_while_widened=true` is legal only when a prior `DecisionPointWidened` exists for the same `decision_point_id`. Reducers raise an anomaly (`kind="invalid_transition"`) and project the field as `false` if the precondition is not met.

### No grace period

4.x validators fail closed on missing `terminal_outcome` or missing `origin_surface`. There is no temporary permissive path. Downstream consumers must migrate deliberately against this contract boundary.
