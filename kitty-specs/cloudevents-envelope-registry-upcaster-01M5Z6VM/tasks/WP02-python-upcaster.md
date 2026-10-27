---
work_package_id: WP02
title: Python upcaster
dependencies:
- WP01
requirement_refs:
- FR-004
- FR-005
- FR-005a
- FR-006
- FR-007
- FR-008
- NFR-001
- NFR-002
- NFR-004
- NFR-005
- C-001
- C-005
planning_base_branch: issue-92-cloudevents-envelope
merge_target_branch: issue-92-cloudevents-envelope
branch_strategy: Planning artifacts for this mission were generated on issue-92-cloudevents-envelope. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into issue-92-cloudevents-envelope unless the human explicitly redirects the landing branch.
subtasks:
- T007
- T008
- T009
- T010
- T011
- T012
history:
- timestamp: '2026-10-27T18:00:00Z'
  lane: planned
  agent: system
  action: Prompt generated via /spec-kitty.tasks
agent_profile: python-pedro
authoritative_surface: src/spec_kitty_events/cloudevents/
create_intent:
- src/spec_kitty_events/cloudevents/__init__.py
- src/spec_kitty_events/cloudevents/_canonical.py
- src/spec_kitty_events/cloudevents/_detect.py
- src/spec_kitty_events/cloudevents/_upcast.py
- src/spec_kitty_events/cloudevents/_types.py
- tests/test_cloudevents_canonical.py
- tests/test_cloudevents_upcast.py
- tests/test_cloudevents_properties.py
execution_mode: code_change
owned_files:
- src/spec_kitty_events/cloudevents/__init__.py
- src/spec_kitty_events/cloudevents/_canonical.py
- src/spec_kitty_events/cloudevents/_detect.py
- src/spec_kitty_events/cloudevents/_upcast.py
- src/spec_kitty_events/cloudevents/_types.py
- tests/test_cloudevents_canonical.py
- tests/test_cloudevents_upcast.py
- tests/test_cloudevents_properties.py
role: implementer
tags: []
tracker_refs: []
---
# Work Package Prompt: WP02 – Python upcaster

## ⚡ Do This First: Load Agent Profile

Load your assigned profile before reading anything else: `/spk-charter-profile-load python-pedro`
(or `spec-kitty agent profile show python-pedro`), and adopt its identity, scope and boundaries.

## Branch Strategy

Planning base and merge target: `issue-92-cloudevents-envelope` (single_branch topology). The
execution workspace comes from `lanes.json`. Implement with
`spec-kitty agent action implement WP02 --agent claude --mission cloudevents-envelope-registry-upcaster-01M5Z6VM`.

## Context

Spec: `../spec.md`; plan: `../plan.md`; research: `../research.md`; contract:
`../contracts/upcaster-contract.md`; ADR: `docs/adr/0001-cloudevents-envelope.md`; design:
`docs/architecture/cloudevents-envelope.md` sections 3–9.

## Objective

Implement `contracts/upcaster-contract.md` in `spec_kitty_events.cloudevents`, driven by the
registry (no mapping table in code beyond the shape-A attribute renames of design section 3).
Write each test before its implementation.

## Subtasks

### T007 Canonical serialization
`canonical_bytes(value) -> bytes`: `json.dumps(sort_keys=True, separators=(",", ":"),
ensure_ascii=False, allow_nan=False).encode("utf-8")`. Reject non-JSON values (TypeError).
Tests: key order, unicode, floats (`1e-05`, `1.5`, `1e+16`), ints, nested, NaN refused.

### T008 Types and detection
`_types.py`: `UpcastContext` (frozen dataclass: `missionid`, `missionslug`, `projectuuid`),
`UpcastIssue(code, message)`, `UpcastError(ValueError)` with `.code`, `UpcastResult`
(`outcome`, `row`, `form`, `original`, `original_line`, `issues`), `IssueCode` string constants.
`_detect.py`: detection order of the contract.

### T009 Shape A
- Accept only the 14 `Event` keys; any other key → `UNEXPECTED_MEMBER`. `schema_version` must be
  `3.0.0` (else `UNSUPPORTED_SCHEMA_VERSION`).
- Ids via `models.normalize_event_id` (check the exact helper); `INVALID_ID` on failure.
- `time` must match the RFC 3339 pattern used by the envelope schemas (`INVALID_TIME`).
- Aggregate split per design section 3 (ULID segment only; `/wp/<WP>` suffix → subject).
- Lifts from `entry["transport"]["lifted"]` (actor projection + binding), lane aliases.
- Mission id candidates: aggregate segment, lifted `mission_id`, context → all equal or
  `MISSION_ID_CONFLICT`; wp segment vs lifted `wp_id` → `SUBJECT_CONFLICT`.
- Extensions: `buildid`, `nodeid`, `lamportclock` (0..2^31-1 else `CLOCK_OUT_OF_RANGE`; null
  omitted), `causationid`, `correlationid` (normalized; null omitted), `projectuuid`,
  `projectslug` (null omitted), `datatier` (when present and not null).

### T010 Registry-driven flat and envelope forms
Entry lookup by (form, discriminator) for envelopes and by flat constants / lane / annotation.
`data` = payload (envelopes) or remaining members minus lifted and constants (flat), with
`binding_member` holding a structured actor. Projections `actor_identity`
(str → itself; dict → `tool` else `role`) and `retrospective_actor_id` (dict `id`); an empty
projection omits `actor`. Lifted attributes `id` (normalized), `time`, `subject`, `type`
(discriminator → ignored, the entry's type is used), others copied. `source`/`missionid`/
`missionslug` from row or context, project fallback with `subject` = `aggregate_id` when the
row carries one. `feature_slug` read as `mission_slug` (lane rows only, published alias).

### T011 Policy and lines
`upcast(row, context=None, *, strict=False)`, `upcast_line(line, context=None, *, strict=False)`.
Permissive: any issue → pass-through result with issues; strict: raise `UpcastError` (except
`ALREADY_CLOUDEVENT`). The input object is deep-copied before reading nested values into output.

### T012 Property tests
Hypothesis: generated diary lane rows and shape-A rows → same bytes twice; input unchanged
(deep-compare with a copy); upcast(upcast(x).row) is pass-through with equal bytes. Throughput:
10,000 diary rows < 2 s (NFR-004), asserted.

## Definition of Done
Tests pass; mypy strict clean; coverage of the package ≥ 95%.

## Reviewer Guidance

Check the change against the spec rows mapped to this WP; reject any mapping that invents a
value, any schema authored twice, and any change to `validate_strict_envelope`, producer
emission or the diary reducer.
