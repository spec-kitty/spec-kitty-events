---
work_package_id: WP03
title: Corpus, goldens, validator and reduced state
dependencies:
- WP02
requirement_refs:
- FR-009
- FR-010
- FR-011
- FR-012
- FR-015
- NFR-003
- C-003
- SC-001
- SC-003
- SC-004
planning_base_branch: issue-92-cloudevents-envelope
merge_target_branch: issue-92-cloudevents-envelope
branch_strategy: Planning artifacts for this mission were generated on issue-92-cloudevents-envelope. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into issue-92-cloudevents-envelope unless the human explicitly redirects the landing branch.
subtasks:
- T013
- T014
- T015
- T016
- T017
- T018
history:
- timestamp: '2026-10-27T18:00:00Z'
  lane: planned
  agent: system
  action: Prompt generated via /spec-kitty.tasks
agent_profile: python-pedro
authoritative_surface: src/spec_kitty_events/producer_contract/fixtures/upcast/
create_intent:
- src/spec_kitty_events/cloudevents/_decode.py
- src/spec_kitty_events/cloudevents/_validate.py
- scripts/generate_upcast_corpus.py
- src/spec_kitty_events/conformance/test_upcast_corpus.py
- tests/test_cloudevents_decode_validate.py
execution_mode: code_change
owned_files:
- src/spec_kitty_events/cloudevents/_decode.py
- src/spec_kitty_events/cloudevents/_validate.py
- src/spec_kitty_events/producer_contract/fixtures/upcast/**
- scripts/generate_upcast_corpus.py
- src/spec_kitty_events/conformance/test_upcast_corpus.py
- tests/test_cloudevents_decode_validate.py
role: implementer
tags: []
tracker_refs: []
---
# Work Package Prompt: WP03 – Corpus, goldens, validator and reduced state

## ⚡ Do This First: Load Agent Profile

Load your assigned profile before reading anything else: `/spk-charter-profile-load python-pedro`
(or `spec-kitty agent profile show python-pedro`), and adopt its identity, scope and boundaries.

## Branch Strategy

Planning base and merge target: `issue-92-cloudevents-envelope` (single_branch topology). The
execution workspace comes from `lanes.json`. Implement with
`spec-kitty agent action implement WP03 --agent claude --mission cloudevents-envelope-registry-upcaster-01M5Z6VM`.

## Context

Spec: `../spec.md`; plan: `../plan.md`; research: `../research.md`; contract:
`../contracts/upcaster-contract.md`; ADR: `docs/adr/0001-cloudevents-envelope.md`; design:
`docs/architecture/cloudevents-envelope.md` sections 3–9.

## Objective

Pin the upcaster with a packaged corpus of legacy rows and golden CloudEvents that both
bindings run, plus the validator and the equal-reduced-state proof.

## Subtasks

### T013 Lane decode
`status_event_row(cloudevent) -> dict`: inverse of the lane mapping (subject→`wp_id`,
missionslug/missionid, actor (binding if present), executionmode, `time`→`at`, `id`→`event_id`,
data members). Only for `lane.transitioned.v1`.

### T014 Validator
`validate_cloudevent(row, *, profile="read") -> list[ValidationIssue]` using the composed row
schema of the row's type (`UNREGISTERED_TYPE` otherwise). Imports `jsonschema` lazily; raises a
clear ImportError naming the `conformance` extra.

### T015 Generator
`scripts/generate_upcast_corpus.py [--check]`: builds cases from (a) one shape-A `3.0.0` row per
strict type, built from each type's producer fixture data; (b) every line of
`conformance/fixtures/status_diary/replay/*.jsonl` with the Mission id from the replay file; (c)
every valid producer fixture's `legacy_row`; (d) hand-written edge cases in
`fixtures/upcast/edge_cases.source.json` (UUID ids, conflicts, slug aggregates, unprefixed
aggregates, `2.9.0`, non-objects, already-CloudEvent, clock overflow, structured actor,
`feature_slug`, missing mission id, unknown member). Expected values come from running the
upcaster and are reviewed by hand once; `--check` fails on drift.

### T016 Corpus
`fixtures/upcast/cases/<group>/<case>.json` per data-model.md, plus `fixtures/upcast/README.md`.

### T017 Packaged gate
`conformance/test_upcast_corpus.py`: for every case: outcome, form and codes match; mapped →
canonical bytes equal golden; golden validates against the read profile; upcasting the parsed
golden gives pass-through with equal bytes; strict mode raises for every case with codes.

### T018 Reduced state
For each replay fixture: decode the upcast lane rows (pass-through rows kept as is) and
reduce with the diary reducer; compare with `<name>_output.json`.

## Definition of Done
`uv run pytest --pyargs spec_kitty_events.conformance` and the new tests pass; generator
`--check` clean.

## Reviewer Guidance

Check the change against the spec rows mapped to this WP; reject any mapping that invents a
value, any schema authored twice, and any change to `validate_strict_envelope`, producer
emission or the diary reducer.
