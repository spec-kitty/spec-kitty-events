---
work_package_id: WP05
title: Docs and release 11.3.0
dependencies:
- WP03
- WP04
requirement_refs:
- FR-016
- C-004
planning_base_branch: issue-92-cloudevents-envelope
merge_target_branch: issue-92-cloudevents-envelope
branch_strategy: Planning artifacts for this mission were generated on issue-92-cloudevents-envelope. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into issue-92-cloudevents-envelope unless the human explicitly redirects the landing branch.
subtasks:
- T024
- T025
- T026
history:
- timestamp: '2026-10-27T18:00:00Z'
  lane: planned
  agent: system
  action: Prompt generated via /spec-kitty.tasks
agent_profile: curator-carla
authoritative_surface: docs/
create_intent: []
execution_mode: code_change
owned_files:
- docs/architecture/cloudevents-envelope.md
- docs/architecture/producer-row-schemas.md
- docs/architecture/README.md
- src/spec_kitty_events/producer_contract/README.md
- CHANGELOG.md
- COMPATIBILITY.md
- pyproject.toml
- src/spec_kitty_events/__init__.py
- README.md
role: implementer
tags: []
tracker_refs: []
---
# Work Package Prompt: WP05 – Docs and release 11.3.0

## ⚡ Do This First: Load Agent Profile

Load your assigned profile before reading anything else: `/spk-charter-profile-load curator-carla`
(or `spec-kitty agent profile show curator-carla`), and adopt its identity, scope and boundaries.

## Branch Strategy

Planning base and merge target: `issue-92-cloudevents-envelope` (single_branch topology). The
execution workspace comes from `lanes.json`. Implement with
`spec-kitty agent action implement WP05 --agent claude --mission cloudevents-envelope-registry-upcaster-01M5Z6VM`.

## Context

Spec: `../spec.md`; plan: `../plan.md`; research: `../research.md`; contract:
`../contracts/upcaster-contract.md`; ADR: `docs/adr/0001-cloudevents-envelope.md`; design:
`docs/architecture/cloudevents-envelope.md` sections 3–9.

## Objective

Record the release: `11.3.0` minor, the row-level upcaster contract in the design doc, and the
consumer impact.

## Subtasks

### T024 Design doc
Design section 9: replace "specified in the first implementation mission" with the contract
(detection order, outcomes, codes) and point at the corpus. Section 8: URN base. Section 7:
registry now complete. `producer-row-schemas.md`: INTERIM lifted, URN, profiles.

### T025 Version
`pyproject.toml` and `__version__` → `11.3.0`; `uv lock` refresh if the project version is in
the lock; COMPATIBILITY `## 11.3.0` (minor: additive types/schemas/API; non-normative
validator; `dataschema` base change on the non-normative producer contract; write-profile
pattern change) and the `Current package version` line; CHANGELOG `[11.3.0]`.

### T026 READMEs and consumer note
Producer contract README (profiles, transport, upcast corpus, Java). Core: lock refresh within
`<12` (cf. spec-kitty#6602); no code change needed until it adopts the reader.

## Definition of Done
DocKitty frontmatter valid; links resolve; lint clean.

## Reviewer Guidance

Check the change against the spec rows mapped to this WP; reject any mapping that invents a
value, any schema authored twice, and any change to `validate_strict_envelope`, producer
emission or the diary reducer.
