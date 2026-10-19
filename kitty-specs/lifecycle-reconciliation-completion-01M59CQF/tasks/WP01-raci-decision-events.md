---
work_package_id: WP01
title: RACI decision events - models, schemas, fixtures
dependencies: []
requirement_refs:
- FR-001
- FR-002
- FR-003
- FR-004
- FR-005
- FR-006
- FR-007
- NFR-002
- NFR-003
- C-002
- C-004
- C-005
- SC-001
planning_base_branch: issue-119-lifecycle-reconciliation-completion
merge_target_branch: issue-119-lifecycle-reconciliation-completion
branch_strategy: Planning artifacts were generated on issue-119-lifecycle-reconciliation-completion; completed changes must merge back into issue-119-lifecycle-reconciliation-completion. Execution worktrees are allocated per computed lane from lanes.json.
subtasks:
- T001
- T002
- T003
- T004
- T005
- T006
- T007
phase: Phase 1 - Foundational contracts
agent: ''
history:
- '2026-10-19: created by /spec-kitty.tasks'
agent_profile: python-pedro
authoritative_surface: src/spec_kitty_events/decision_significance.py
create_intent:
- src/spec_kitty_events/decision_significance.py
- src/spec_kitty_events/schemas/raci_role_binding.schema.json
- src/spec_kitty_events/schemas/resolved_raci_snapshot.schema.json
- src/spec_kitty_events/schemas/significance_evaluated_payload.schema.json
- src/spec_kitty_events/schemas/decision_timeout_expired_payload.schema.json
- tests/unit/test_decision_significance.py
- tests/test_decision_significance_producer_row.py
execution_mode: code_change
owned_files:
- src/spec_kitty_events/decision_significance.py
- src/spec_kitty_events/__init__.py
- src/spec_kitty_events/conformance/validators.py
- src/spec_kitty_events/conformance/loader.py
- src/spec_kitty_events/conformance/fixtures/manifest.json
- src/spec_kitty_events/conformance/fixtures/decision_significance/**
- src/spec_kitty_events/schemas/generate.py
- src/spec_kitty_events/schemas/raci_role_binding.schema.json
- src/spec_kitty_events/schemas/resolved_raci_snapshot.schema.json
- src/spec_kitty_events/schemas/significance_evaluated_payload.schema.json
- src/spec_kitty_events/schemas/decision_timeout_expired_payload.schema.json
- tests/unit/test_decision_significance.py
- tests/test_decision_significance_producer_row.py
role: implementer
---

# WP01 - RACI decision events: models, schemas, fixtures

## ⚡ Do This First: Load Agent Profile

Before reading anything else, load the assigned profile with the `/spk-charter-profile-load` skill (profile `python-pedro`). Then load governance: `spec-kitty charter context --action implement`. Read `kitty-specs/lifecycle-reconciliation-completion-01M59CQF/{spec.md,plan.md,research.md,data-model.md,contracts/decision-significance-events.md}` and `research/mission-brief.md`.

## Objective

Mirror core's `SignificanceEvaluatedPayload` and `TimeoutExpiredPayload` into the package as `SignificanceEvaluatedPayload` / `DecisionTimeoutExpiredPayload`, with a NEW `RaciRoleBinding` (actor_id required, min length 1, operator ruling R2) and a `ResolvedRaciSnapshot`. `RuntimeActorIdentity` is not touched (R1). Requirements: FR-001..FR-007, NFR-001..NFR-004, C-001..C-005, C-007.

## Branch Strategy

Planning base and merge target: `issue-119-lifecycle-reconciliation-completion`. The workspace is the lane worktree computed by `finalize-tasks` (`lanes.json`). Start with:

```bash
spec-kitty agent action implement WP01 --agent <name> --mission lifecycle-reconciliation-completion-01M59CQF
```

## STOP conditions (escalate, do not decide)

- Any existing test assertion, count pin or parametrize list would need to change. Known pins: `tests/unit/test_strict.py:129` (26), `tests/test_support_matrix.py:124,342` (55), `tests/test_producer_contract.py:88,148` (27), `tests/unit/test_fixtures.py:112,474,485`.
- Any existing model, schema, fixture or producer-row schema would need to change.
- Mirroring a core rule faithfully would refuse a payload shape core emits, other than a null/empty `actor_id` (R2).

## Subtasks

### T001 - Create `src/spec_kitty_events/decision_significance.py`

1. Module docstring: mirrors `spec-kitty` `src/runtime/next/_internal_runtime/significance.py:485-522` and `schema.py:79-120` by shape; it never imports core (C-002). Cite R1/R2.
2. Constants: `SIGNIFICANCE_EVALUATED: str = "SignificanceEvaluated"`, `DECISION_TIMEOUT_EXPIRED: str = "DecisionTimeoutExpired"`, `DECISION_SIGNIFICANCE_EVENT_TYPES: FrozenSet[str]`.
3. Models, every one `ConfigDict(frozen=True, extra="forbid")`, Python 3.10-compatible typing (follow `mission_next.py` style, `Optional`, `Tuple`):
   - `RaciRoleBinding`: `actor_type: Literal["human","llm","service"]`; `actor_id: str = Field(..., min_length=1)`.
   - `ResolvedRaciSnapshot`: fields per data-model.md. Read core `schema.py:109-140` and mirror its `model_validator` rules exactly (provenance: what `source` requires of `inferred_rule` / `override_reason`; any accountable-is-human rule only if `ResolvedRACIBinding` itself enforces it; `RACIAssignment`'s rule is NOT on the resolved binding unless the source shows it). Document each mirrored rule with the core line it comes from.
   - `SignificanceEvaluatedPayload` and `DecisionTimeoutExpiredPayload`: per data-model.md. `significance_score: Dict[str, Any]`; `hard_trigger_classes: Tuple[str, ...] = ()`; `timeout_configured_seconds: int = Field(..., gt=0)`; `escalation_targets: Tuple[RaciRoleBinding, ...] = ()`; `raci_snapshot: ResolvedRaciSnapshot` (plan D6).
4. Whitespace-only `actor_id`: follow the package's existing rule for required identifiers (`min_length=1` only, as `RuntimeActorIdentity.actor_id`). Do not invent a stricter rule.
5. `__all__` listing the public names.

### T002 - Validator dispatch and exports

- `conformance/validators.py`: add both types to `_EVENT_TYPE_TO_MODEL` and to the schema-name map (the one with `"MissionReopened": "mission_reopened_payload"`). Additions only.
- `__init__.py`: export the constants, the frozenset and the four models (follow how `mission_next` names are exported). Do not touch `__version__` (WP03 owns the bump).
- Do NOT add the types to `MISSION_NEXT_EVENT_TYPES`, `STRICT_EVENT_TYPES` or `support_matrix.json` (C-005).

### T003 - Schemas

- `schemas/generate.py`: append `("raci_role_binding", RaciRoleBinding)`, `("resolved_raci_snapshot", ResolvedRaciSnapshot)`, `("significance_evaluated_payload", ...)`, `("decision_timeout_expired_payload", ...)` to `PYDANTIC_MODELS` under a comment naming this mission.
- Regenerate with the module's own entry point (read `generate.py` for the exact command); commit only the four new files. `git diff --stat` on `src/spec_kitty_events/schemas/` must show no modified existing schema (NFR-004). Never hand-edit (C-004).

### T004 - Fixture categories and decision fixtures

- `conformance/loader.py::_VALID_CATEGORIES`: add `"decision_significance"` and `"lifecycle"` (the latter is used by WP02), each with a trailing comment `# 11.1.0 (#119)`.
- Fixtures under `conformance/fixtures/decision_significance/{valid,invalid}/`, each a raw payload JSON as the other categories use (copy the file shape from `mission_next/valid/*.json`). Minimum set:
  - valid: `significance_evaluated.json` (core-shaped, actor `{"actor_type":"service","actor_id":"runtime"}`, band `medium`, one hard trigger class); `significance_evaluated_low_band.json`; `decision_timeout_expired.json` (band `high`, timeout 900, two escalation targets, a full snapshot with consulted/informed).
  - invalid: `significance_evaluated_actor_id_null.json`, `..._actor_id_empty.json`, `..._actor_id_missing.json`, `..._bad_band.json`, `..._extra_key.json`; `decision_timeout_expired_low_band.json`, `..._timeout_zero.json`, `..._escalation_target_actor_id_null.json`, `..._snapshot_actor_id_empty.json`, plus one per provenance rule mirrored in T001.
- `manifest.json`: one entry per fixture (`id`, `path`, `expected_result`, `event_type`, `notes`, `min_version: "11.1.0"`), appended at the end of `fixtures`. Additions only.

### T005 - `tests/unit/test_decision_significance.py` [P]

Round-trip, frozen, extra-forbid, each Literal bound, `gt=0`, R2 cases (null/empty/missing) on every place a `RaciRoleBinding` appears (actor, escalation targets, snapshot roles), each mirrored provenance rule (one passing and one failing case each), `validate_event` accept/refuse via the conformance validator, and a ratchet that `RuntimeActorIdentity.model_json_schema()` equals its committed schema file.

### T006 - `tests/test_decision_significance_producer_row.py` [P]

Load `producer_contract/data/run.significance.evaluated.v1.schema.json` and the generated `significance_evaluated_payload` schema; assert identical property-name sets, equal enums for `effective_band` and `actor.actor_type`, equal `minLength` on the three identifiers, and equal required sets except that the package's `actor` object additionally requires `actor_id`. Pin the single exception explicitly (a test that fails if the row ever drops `null` or the model ever allows it), with a docstring citing R2. Also validate every producer-row valid fixture whose `actor_id` is a non-empty string through `validate_event(..., "SignificanceEvaluated")` and assert acceptance; assert rows with `actor_id: null` are refused (documented R2 divergence). Do not edit `tests/test_producer_contract.py`.

### T007 - Gates

```bash
uv run --frozen pytest tests/unit/test_decision_significance.py tests/test_decision_significance_producer_row.py tests/integration/test_schema_drift.py tests/test_producer_contract.py tests/unit/test_fixtures.py tests/unit/test_strict.py tests/test_support_matrix.py -q
uv run --frozen pytest --pyargs spec_kitty_events.conformance -q
make test-fast
uv run --frozen mypy --strict src && uv run --frozen ruff check . && uv run --frozen ruff format --check .
```

Record commands and counts in the WP handoff.

## Definition of Done

- FR-001..FR-007 met; all gates above green; conformance count = 727 + new fixtures.
- `git diff` against the base shows only additions, except the registration lists in `validators.py`, `generate.py`, `loader.py`, `__init__.py`, `manifest.json`.
- No import of `specify_cli`, `runtime`, `spec_kitty_saas`, `spec_kitty_tracker`.

## Reviewer guidance

Diff every field against core `significance.py:485-522` and `schema.py:79-140`. Confirm the only narrowing is `actor_id`. Confirm no count-pinned set changed and the producer-row schema is untouched.
