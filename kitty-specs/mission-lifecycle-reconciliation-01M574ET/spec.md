# Feature Specification: Mission-lifecycle event reconciliation

**Feature Branch**: `issue-119-mission-lifecycle-reconciliation`
**Created**: 2026-10-18
**Status**: Draft
**Input**: Scope item B (tracker #119) of the spec-kitty-events 4.x rework epic #117 — mission-lifecycle event onboarding / reconciliation against core's current usage.

## Overview

`spec-kitty-events` is the fixture-backed contract authority for the Spec Kitty ecosystem.
Brownfield grounding against core (`spec-kitty`, pinned `spec-kitty-events>=10.5.1,<11`)
found the mission-lifecycle contracts (`lifecycle`, `project_lifecycle`, `mission_audit`,
`mission_next`) are already largely aligned with what core emits and consumes. The real
gaps are narrow and additive: two lifecycle events are modelled in Python but have no
committed JSON schema or conformance fixtures, and one event core emits as a lifecycle event
is modelled only in the `producer_contract` subsystem, not in the lifecycle layer. This
mission closes those gaps by **mirroring core's current shapes into the package** — never
importing core — additively and fail-closed, so every mission-lifecycle event core produces
has a committed schema and at least one valid and one invalid fixture.

## Domain Language

| Canonical term | Meaning |
|---|---|
| Mirror-not-depend | The package reproduces the shape core emits as its own committed contract; it never imports `spec-kitty`/`-saas`/`-tracker`/`-runtime`. |
| Schema+fixture gap | A payload has a Python model but no committed JSON Schema and/or no conformance fixture, so consumers cannot validate it against the packaged corpus. |
| Fail-closed | An unknown event type or an invalid payload is refused by `validate_event`; reducers skip unknown types without error. |

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Every emitted lifecycle event has a committed contract (Priority: P1)

A downstream consumer (the core ledger read-side, a SaaS projector, a future Java binding)
receives a `MissionReopened`, `FollowUpRecorded`, or `ReviewerSelfApproval` event and
validates it against the packaged JSON Schemas and fixtures — with no gap where the event is
"model-layer only".

**Why this priority**: The package's whole purpose is to be the one validatable contract; an
event core emits but the package cannot validate against committed artifacts is a hole in the
contract authority.

**Independent Test**: For each of the three events, confirm a committed JSON Schema exists and
`validate_event` accepts a valid payload and refuses an invalid one, using committed fixtures.

**Acceptance Scenarios**:

1. **Given** a valid `MissionReopened` payload matching core's emit shape, **When** `validate_event` is called, **Then** it is accepted and a committed valid fixture exists for it.
2. **Given** a valid `FollowUpRecorded` payload, **When** validated, **Then** it is accepted and a committed valid fixture exists.
3. **Given** a valid `ReviewerSelfApproval` payload matching core's emit, **When** validated, **Then** it is accepted and a committed valid fixture exists.

### User Story 2 - Reconciliation is faithful to core's current shape (Priority: P1)

A contract maintainer confirms the added schemas and the `ReviewerSelfApproval` model mirror
exactly how core currently builds and emits these events — same field names, optionality, and
types — so the package and core agree without the package importing core.

**Why this priority**: A reconciliation that guesses the shape would create a second, drifting
contract; the point is fidelity to the producer.

**Independent Test**: Diff each added schema/model against core's emit sites and the existing
`producer_contract` row schema; confirm field-level equivalence.

**Acceptance Scenarios**:

1. **Given** the `ReviewerSelfApproval` shape core emits, **When** the package model is compared, **Then** fields, optionality, and types match and the package imports nothing from core.
2. **Given** the `MissionReopened`/`FollowUpRecorded` models already shipped, **When** the new schemas are generated, **Then** they are generated from those canonical models, not hand-authored divergently.

### User Story 3 - Fail-closed and additive guarantees hold (Priority: P2)

The reconciliation adds contracts without changing any existing event, and the new events are
fail-closed: invalid payloads are refused, and reducers ignore types they do not recognise.

**Why this priority**: Additivity and fail-closed are binding charter guarantees; a
reconciliation must not become a breaking change or a silent-accept.

**Independent Test**: Run the conformance gate and a diff proving no existing schema/fixture/
model changed; run the invalid fixtures through `validate_event`.

**Acceptance Scenarios**:

1. **Given** an invalid payload for any of the three events, **When** validated, **Then** it is refused.
2. **Given** the full change, **When** compared to the prior commit, **Then** no existing event type, payload model, schema, or fixture is modified (only additions), and the package's import set is unchanged.

### Edge Cases

- A new schema disagrees with its canonical Python model → the committed schema-generation drift check fails (NFR-001).
- `ReviewerSelfApproval` optional fields differ between core's emit and the `producer_contract` row schema → surface the discrepancy and mirror core's *emit* shape (the producer), recording the reconciliation in `research.md`.
- A reviewer tries to fold `LOCAL_ONLY_EVENT_TYPES` population or `SignificanceEvaluated`/`DecisionTimeoutExpired` into this mission → out of scope (see Constraints C-003/C-004).

## Requirements *(mandatory)*

### Functional Requirements

| ID | Requirement | Status | Delivery | No-op passable? |
|---|---|---|---|---|
| FR-001 | A committed JSON Schema for `MissionReopened` MUST be added, generated from its canonical Pydantic model and matching core's emit shape. | Draft | [build] | no |
| FR-002 | A committed JSON Schema for `FollowUpRecorded` MUST be added, generated from its canonical model and matching core's emit shape. | Draft | [build] | no |
| FR-003 | At least one valid and one invalid conformance fixture MUST be added for `MissionReopened`. | Draft | [build] | no |
| FR-004 | At least one valid and one invalid conformance fixture MUST be added for `FollowUpRecorded`. | Draft | [build] | no |
| FR-005 | A `ReviewerSelfApproval` payload model MUST be added to the `project_lifecycle` layer (type constant + registry membership), mirroring core's emit shape and the existing `producer_contract` row schema. | Draft | [build] | no |
| FR-006 | A committed JSON Schema plus at least one valid and one invalid conformance fixture MUST be added for `ReviewerSelfApproval`. | Draft | [build] | no |
| FR-007 | All three events MUST be registered so `validate_event` accepts a valid payload and refuses an invalid one (fail-closed), and reducers MUST continue to skip unknown types without error. | Draft | [build] | no |
| FR-008 | The change MUST be strictly additive: no existing event type, payload model, schema, or fixture is modified, and the package continues to import nothing from `spec-kitty`, `-saas`, `-tracker`, `-runtime`. | Draft | [ratchet] | no |
| FR-009 | The packaged conformance gate (`uv run pytest --pyargs spec_kitty_events.conformance`) MUST pass with the new schemas and fixtures. | Draft | [build] | no |

### Non-Functional Requirements

| ID | Requirement | Status | Delivery | No-op passable? |
|---|---|---|---|---|
| NFR-001 | Added JSON Schemas MUST be generated from the canonical Pydantic models so the committed schema-generation drift check passes (schema ≡ model); no hand-authored divergence. | Draft | [build] | no |

### Constraints

| ID | Constraint | Status | Delivery | No-op passable? |
|---|---|---|---|---|
| C-001 | Mirror-not-depend: the shapes MUST mirror core's current emit; the package MUST NOT import `spec-kitty`/`-saas`/`-tracker`/`-runtime`. | Draft | [ratchet] | no |
| C-002 | The addition MUST be released under SemVer as a minor (additive) change. | Draft | [ratchet] | no |
| C-003 | Populating `LOCAL_ONLY_EVENT_TYPES` is OUT of scope — it is Mission A's (#118) headline deliverable; this mission MUST NOT modify that registry. | Draft | [folded] | no |
| C-004 | Mirroring `SignificanceEvaluated`/`DecisionTimeoutExpired` is OUT of scope — deferred pending an operator decision on the RACI actor-shape question that risks a breaking change to `RuntimeActorIdentity`. | Draft | [folded] | no |

### Key Entities

- **`MissionReopened` payload**: existing Python model (`lifecycle.py`); this mission adds its schema + fixtures.
- **`FollowUpRecorded` payload**: existing Python model (`lifecycle.py`); this mission adds its schema + fixtures.
- **`ReviewerSelfApproval` payload**: emitted by core as a lifecycle event, modelled only in `producer_contract` today; this mission adds a `project_lifecycle` model + schema + fixtures mirroring core's emit.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of the mission-lifecycle events core currently emits (including the three reconciled here) have a committed JSON Schema and at least one valid and one invalid fixture. — [build] · no-op passable: no
- **SC-002**: The packaged conformance gate is green with the additions. — [build] · no-op passable: no
- **SC-003**: The committed schema-generation drift check is green (every added schema equals its canonical model). — [build] · no-op passable: no
- **SC-004**: A diff against the pre-mission commit shows additions only — zero existing event types, models, schemas, or fixtures modified, and an unchanged package import set. — [ratchet] · no-op passable: no

## Assumptions

- The brownfield grounding (`scratchpad/mission-B-grounding.md`) is the authoritative scope input: `MissionReopened`/`FollowUpRecorded` need schema+fixtures only (models aligned), `ReviewerSelfApproval` needs a lifecycle-layer model mirroring core; all else in the four modules is already aligned (verified negative results).
- The exact `ReviewerSelfApproval` field set is read from core's emit site and the existing `producer_contract` row schema during plan/implement; this spec fixes the behaviour and additivity, not the field list.

## Dependencies

- Parent epic: #117 (context). Sibling: Mission A (#118) owns `LOCAL_ONLY_EVENT_TYPES` (C-003 boundary). Reconciliation is mirror-only against core (`spec-kitty`), not a runtime dependency.
