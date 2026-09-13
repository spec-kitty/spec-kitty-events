# Contract: Durable Work Observation

**Status**: durable · **Introduced**: 10.1.0 · **Owner module**:
`spec_kitty_events.work_observation` (vocabulary, identity, matrix),
`spec_kitty_events.work_replay` (stream semantics) · **Issue**:
spec-kitty/spec-kitty-events#55 (planning#2268 "Zeitgeist Live Work")

> This document governs the durable live-work contract — the versioned,
> typed work observation shared by the CLI, SaaS and Zeitgeist. It rides
> the standard `Event` envelope; it does not extend it.

## 1. Envelope

Every durable work observation is a standard `Event` envelope with
`event_type="WorkObservation"`, `aggregate_id="mission/<mission_id>"`,
`schema_version="3.0.0"`, and the payload validated by
`WorkObservationPayload`. The envelope keeps exactly the `Event`
semantics it already has:

- `event_id` / `correlation_id` / `causation_id` — the existing causal
  semantics. A **retry attempt** is a new `event_id` whose envelope
  `causation_id` (and optionally payload `reply_to`) names the failed
  attempt; it is never a re-take of the same ID.
- `timestamp` — producer occurrence time (R-T-01), presentation only.
- `node_id` — the producer instance; `lamport_clock` — the journal clock
  at emission. Together with the payload's per-producer `sequence` these
  own ordering (§5).

**No server-owned field ever appears on the wire.** The authenticated
principal/team binding, the admitted-repository generation, `received_at`,
and the committed cursor are derived server-side from the credential at
ingestion (saas#1814). `SERVER_OWNED_FIELDS` fails closed on all of them,
and the strict profile's forbidden-key walk unions `FORBIDDEN_WORK_KEYS`
for `event_type="WorkObservation"`.

## 2. Identity

Five distinct identities, never collapsed (LW-01):

| Identity | Model | Stable across |
|---|---|---|
| Principal | `ActorIdentity` (kind: human/agent/service/factory) | display-name changes |
| Agent profile | `AgentProfileRef` (harness/model/profile) | — |
| Session | `SessionIdentity` | reconnect (`reconnect_epoch`) and credential rotation (`credential_epoch`); delegation via `delegated_from` |
| Factory attempt | `FactoryAttemptRef` (attempt/job) | sessions coming and going |
| Producer | `ProducerIdentity` (producer/instance/monotonic sequence) | — |

`display_name`, `display_label`, and `display_slug` are mutable
presentation, never identity. Mission identity (`MissionIdentity`) is the
canonical opaque `mission_id` — it exists from the first specify activity,
before any Git push, and survives label changes, worktree moves, PRs and
merges (LW-02). Repository identity (`RepositoryIdentity`) is
`provider` + the provider-canonical `repository_id`; a provider rename
changes `display_slug` only. Cross-repo programmes (`ProgrammeLink`)
relate distinct missions without merging their identities.

## 3. Vocabulary

25 closed kinds across six families — `WORK_KIND` in
`spec_kitty_events.work_observation`, one payload ID each
(`work.<kind>.v1`), one durable support-matrix row each. Per-kind
required/forbidden fields are governed by the in-module field matrix and
machine-tested; an enum registration with no constructible shape is not
capture coverage.

- **lifecycle (6)** — mission review and retrospective
  captured/failed/skipped. Failure and skip require honest inline text;
  they are first-class observations, never laundered into silence (LW-03).
- **session (5)** — started/ended, delegation started/ended (a delegation
  always names its counterpart), binding changed (LW-01/LW-02).
- **action (3)** — tool invoked, file edited (metadata only: path and
  byte deltas — there is no `contents` field, and `contents` is a
  forbidden key), test executed. Every action carries an explicit outcome:
  success/failure/skipped (LW-04).
- **narrative (9)** — intent, progress, question, answer (requires
  `reply_to`), decision, handoff (requires `recipient`), blocker raised,
  blocker resolved (requires `ref`), next (LW-05/LW-09).
- **message (1)** — durable peer message (text + recipient).
- **coverage (1)** — a recorded capture gap: area + reason, never a silent
  drop (LW-08/LW-10).

Long content is never inlined: inline text is bounded at 2000 characters,
and anything longer is an `ArtifactReference` (id, SHA-256 content hash,
byte length, media type, completeness — `complete=false` marks a partial
capture) resolved against authenticated artifact storage (LW-11).
`SourceProvenance` records how each observation was captured
(harness_hook / cli_wrapper / emitter / manual / factory), the named
capability, and the honest limitation when capture is partial.

## 4. Duplicate, conflict, and gap semantics

`classify_work_stream` (in `spec_kitty_events.work_replay`) is the
reference implementation every consumer mirrors — machine-tested against
golden fixtures (`work_observation/replay/*.jsonl`):

- **Exact duplicate** — same `event_id` *and* the same
  `canonical_work_hash` (SHA-256 over the canonical JSON serialization,
  sorted keys, tight separators): an idempotent retry, accepted once.
- **Same ID, different payload** — typed `ID_PAYLOAD_CONFLICT` rejection;
  the first-accepted observation stands, never a silent overwrite (LW-10).
- **Late / out-of-order** — a sequence at or below the per-instance
  high-water mark: accepted (arrival order is transport noise) and flagged
  so projections and replay resynchronize honestly.
- **Gap** — a sequence jump past unseen sequences: recorded as a
  `SequenceGap`. The first observed sequence is an anchor, not a gap; the
  server's committed cursor owns pre-stream coverage.

## 5. Replay order

`replay_order_key` = `(lamport_clock, node_id, sequence, event_id)`. **No
client timestamp establishes authoritative total order** (planning#2268):
a producer on a skewed clock must not be able to reorder durable history,
and the `Event` contract already reserves ordering for the Lamport clock
and `node_id`.

## 6. Versioning, negotiation, and extension

- The work-contract lineage is `"1"`, embedded in every payload ID
  (`work.<kind>.v1`). It is independent of the package version.
- `negotiate_work_contract(producer_version, consumer_versions)`:
  compatible when the consumer supports the producer's exact version or a
  later version in the same major (per
  [versioning-and-compatibility.md](./versioning-and-compatibility.md));
  incompatibility returns a typed `UNSUPPORTED_CONTRACT_VERSION` rejection.
  Producers capability-gate emission on this. Version/package pins follow
  actual compatibility — never an equal-major-number requirement across
  packages.
- Safe optional extensions: unknown un-namespaced keys are rejected
  (`extra="forbid"` everywhere); optional data rides `extensions` with
  `x-`-prefixed keys only, scalar values only.
- Membership changes to `FORBIDDEN_WORK_KEYS` are a contract change
  (bump `FORBIDDEN_WORK_KEYS_VERSION`).

## 7. Producer-owner map

Every advertised kind maps to its actual producer owner and consumer test
target (the issue's "an enum alone cannot satisfy capture coverage"):

| Surface | Owner | Consumer tests |
|---|---|---|
| Durable delivery, journal recovery, publisher health | spec-kitty#4266 (CLI) | saas#1814 ingestion |
| Harness tool/file/test/delegation capture | spec-kitty#4268 (CLI) | saas#1816 projections |
| Durable ingestion, receipts, journal | saas#1814 (SaaS) | this package's conformance fixtures |
| Relay fan-out, recoverable cursors | zeitgeist#304 | saas#1817 SSE catch-up |
| People/Missions/Repositories projections | saas#1816 | saas#1820–1822 views |
| Conversations, history, temporal replay | saas#1815, saas#1818 | saas#1823, saas#1824 |
| Factory job/attempt/agent binding | planning#2270 | e2e#452 |

Known capture limitations at introduction (recorded, not hidden): the
current CLI bridge (`specify_cli/status/zeitgeist_bridge.py`) is
best-effort/lossy and is *not* a producer of this contract; producer
enablement lands in spec-kitty#4266/#4268 after the consumers above can
validate `WorkObservation`. The volatile presence heartbeat
(`harness_observation`) stays volatile — it is not folded into this
contract.

## 8. TS/OpenAPI inputs

The committed JSON schemas under `src/spec_kitty_events/schemas/`
(`work_observation_payload.schema.json` plus the identity/action/
provenance sub-model schemas) are generated from the same pydantic models
that validate the Python fixtures, and are the canonical inputs for
browser-side TypeScript/OpenAPI code generation. Regenerate with
`uv run python -m spec_kitty_events.schemas.generate`; the `--check` drift
gate and `tests/test_support_matrix.py` keep them byte-identical to the
models.
