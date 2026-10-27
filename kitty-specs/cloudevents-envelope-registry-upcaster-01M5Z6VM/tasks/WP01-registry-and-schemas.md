---
work_package_id: WP01
title: Registry and schemas complete
dependencies: []
requirement_refs:
- FR-001
- FR-002
- FR-003
- C-002
planning_base_branch: issue-92-cloudevents-envelope
merge_target_branch: issue-92-cloudevents-envelope
branch_strategy: Planning artifacts for this mission were generated on issue-92-cloudevents-envelope. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into issue-92-cloudevents-envelope unless the human explicitly redirects the landing branch.
subtasks:
- T001
- T002
- T003
- T004
- T005
- T006
history:
- timestamp: '2026-10-27T18:00:00Z'
  lane: planned
  agent: system
  action: Prompt generated via /spec-kitty.tasks
agent_profile: python-pedro
authoritative_surface: src/spec_kitty_events/producer_contract/
create_intent:
- src/spec_kitty_events/producer_contract/envelope.read.schema.json
execution_mode: code_change
owned_files:
- src/spec_kitty_events/producer_contract/registry.json
- src/spec_kitty_events/producer_contract/envelope.schema.json
- src/spec_kitty_events/producer_contract/envelope.read.schema.json
- src/spec_kitty_events/producer_contract/data/**
- src/spec_kitty_events/producer_contract/composed/**
- src/spec_kitty_events/producer_contract/fixtures/rows/**
- src/spec_kitty_events/producer_contract/__init__.py
- tests/test_producer_contract.py
- src/spec_kitty_events/conformance/test_producer_contract.py
role: implementer
tags: []
tracker_refs: []
---
# Work Package Prompt: WP01 – Registry and schemas complete

## ⚡ Do This First: Load Agent Profile

Load your assigned profile before reading anything else: `/spk-charter-profile-load python-pedro`
(or `spec-kitty agent profile show python-pedro`), and adopt its identity, scope and boundaries.

## Branch Strategy

Planning base and merge target: `issue-92-cloudevents-envelope` (single_branch topology). The
execution workspace comes from `lanes.json`. Implement with
`spec-kitty agent action implement WP01 --agent claude --mission cloudevents-envelope-registry-upcaster-01M5Z6VM`.

## Context

Spec: `../spec.md`; plan: `../plan.md`; research: `../research.md`; contract:
`../contracts/upcaster-contract.md`; ADR: `docs/adr/0001-cloudevents-envelope.md`; design:
`docs/architecture/cloudevents-envelope.md` sections 3–9.

## Objective

Complete the one schema set (spec C-002): registry entries for all 26 `STRICT_EVENT_TYPES` and
the diary rows, `urn:ai.spec-kitty:schema:` as the `dataschema` base, and read/write envelope
profiles. Red first: write the failing tests in T006 before T001–T005.

## Subtasks

### T001 URN dataschema base
- Registry `dataschema_base` → `urn:ai.spec-kitty:schema:`; every entry's `dataschema` and every
  data schema's `$id` → `urn:ai.spec-kitty:schema:<name>`; composed `$id`s follow.
- Write profile (`envelope.schema.json`): `dataschema` pattern `^urn:ai\.spec-kitty:schema:[a-z0-9.]+(?![\s\S])`, drop `format: uri`? keep `format: uri` (a URN is a URI); `$id` `urn:ai.spec-kitty:schema:envelope.write.v1`; title "write profile".
- Update every fixture under `fixtures/rows/**` mechanically (string replace of the old base).

### T002 Read profile
- New `envelope.read.schema.json`, same closed shape, differences per data-model.md: id ULID or
  lower hyphenated UUID; `lamportclock`, `nodeid` optional (nodeid `minLength: 1`); extra optional
  `buildid`, `causationid`, `correlationid` (id patterns), `projectuuid`, `projectslug`
  (strings), `datatier` (integer 0–4).
- Registry: `envelope_schemas: {"write": ..., "read": ...}`; keep `envelope_schema` (write).
- `row_schema(type, profile="write")`; `envelope_schema(profile="write")`; composed read rows
  under `composed/read/<name>.row.schema.json`, drift-tested like the others.

### T003 Transport mappings
- Each entry gains `transport`: `null` or `{"event_type": X, "lifted": [...], "lane_aliases": {...}}`.
- 17 existing entries whose discriminator is a strict type: `transport.event_type` = it, `lifted: []`.
- `lane.transitioned.v1`: `event_type: WPStatusChanged`, lifted `mission_slug→missionslug`,
  `mission_id→missionid`, `wp_id→subject`, `actor→actor` (projection `actor_identity`,
  `binding_member: actorbinding`), `execution_mode→executionmode`; `lane_aliases: {"doing": "in_progress"}`
  applying to `from_lane`, `to_lane`.
- `legacy_shapes.transport_envelope` describes shape A (documentation entry, `form: transport`).

### T004 Eight transport-only entries
Per research R-8. `legacy: null`. Data schema = the payload model's generated schema with
`$ref` inlined, no `$defs`, `additionalProperties: false` as the model forbids extras. Pin each to
its model in tests (model validates the fixture data; schema accepts model-dumped data). Check
`ReviewRollback`/observation payloads for `wp_id` to choose `subject`.

### T005 Lane summary and regeneration
`summary` (string or null) optional in `lane.transitioned.v1` data (shape A only). Regenerate
`composed/rows`, `composed/legacy` (skip entries with `legacy: null`), `composed/read`. Add a valid
producer fixture for each new type (CloudEvent `input` + shape-A `transport_row`), and
invalid unknown-member fixtures.

### T006 Tests (red first)
- every strict type has exactly one entry with that `transport.event_type`;
- no `INTERIM` in registry description; every `dataschema` starts with the URN base and equals
  its schema `$id`;
- read profile accepts a clockless row and a UUID id, write profile rejects both;
- composed drift for the three directories; new types pinned to models.

## Definition of Done
`uv run pytest tests/test_producer_contract.py` and the packaged gate pass; `ruff`; mypy strict on
`producer_contract/__init__.py`.

## Reviewer Guidance

Check the change against the spec rows mapped to this WP; reject any mapping that invents a
value, any schema authored twice, and any change to `validate_strict_envelope`, producer
emission or the diary reducer.
