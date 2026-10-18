# Research: Mission-lifecycle event reconciliation

Phase 0 output. Decisions are grounded in brownfield investigation
(`scratchpad/mission-B-grounding.md`) and the verified shapes read from the package and the
core producer. Mirror-not-depend: shapes are read from core, never imported.

## Decision 1 — MissionReopened / FollowUpRecorded need schema+fixtures only
- **Decision**: Generate committed JSON Schemas from the existing `lifecycle.py` models and add valid/invalid fixtures. Do NOT touch the models.
- **Rationale**: The Python models already match core's emit (`spec-kitty/.../status/lifecycle_events.py` `emit_mission_reopened` / `emit_follow_up_recorded`); `conformance/validators.py` marks them "model-layer only" with no JSON schema and no fixtures. The only gap is the schema+fixture layer.
- **Alternatives rejected**: Re-authoring the models (not needed — aligned); hand-authoring schemas (would risk drift — generate from the model instead).

## Decision 2 — ReviewerSelfApproval added to the lifecycle layer, mirroring the producer-contract row
- **Decision**: Add a `ReviewerSelfApproval` payload model to `project_lifecycle` with type constant + registry/dispatch membership, mirroring the field set already proven in `producer_contract` (`fixtures/rows/valid/wp.reviewer.selfapproved.v1.json`) and core's emit (`lifecycle_events.py`).
- **Field set (verified from the producer-contract valid fixture)**: `mission_slug`, `wp_id`, `implementing_actor`, `intended_reviewer`, `failure_reason`, `fallback_approved` (bool), `timestamp`.
- **Rationale**: core emits it as a lifecycle event but the lifecycle layer does not model it; only `producer_contract` does. Mirroring the already-committed producer row keeps one shape.
- **Alternatives rejected**: Importing/reusing the producer_contract internals across layers (keep the lifecycle model self-contained, `frozen`, `extra="forbid"` like its peers); inventing a new field set (must mirror the producer).

## Decision 3 — Fidelity + additivity proven by existing gates
- **Decision**: Prove schema≡model with `tests/integration/test_schema_drift.py`, behaviour with the packaged conformance gate, and additivity with a diff vs the pre-mission commit.
- **Rationale**: These gates already exist; the reconciliation plugs into them rather than adding new machinery.

## Out of scope / deferred (recorded, not actioned)
- `LOCAL_ONLY_EVENT_TYPES` population → Mission A (#118). This mission does not touch that registry.
- `SignificanceEvaluated` / `DecisionTimeoutExpired` mirroring → deferred: not emitted in open-core and entangled with a RACI actor-shape question that risks a breaking change to `RuntimeActorIdentity`. Operator decision required before any work.

## Supply-chain note
No dependencies added, upgraded, or removed. `DIRECTIVE_051` / `supply-chain-install-safety`: not applicable to this change (no install surface touched).
