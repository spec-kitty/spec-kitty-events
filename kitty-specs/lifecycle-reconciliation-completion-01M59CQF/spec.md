# Mission Specification: Lifecycle reconciliation completion and RACI decision events

**Mission**: `lifecycle-reconciliation-completion-01M59CQF`
**Target branch**: `issue-119-lifecycle-reconciliation-completion`
**Created**: 2026-10-19
**Status**: Draft
**Input**: Committed mission brief `research/mission-brief.md` (operator rulings R1/R2, 2026-10-19), confirmed as the Intent Summary by decision `01M59CX1BP8MDR765VS6NHBQVM`.

Closes spec-kitty/spec-kitty-events#119 and #62. Refs #117 (parent epic), #118, #120 (pattern), #114, #92; core spec-kitty/spec-kitty#6185, #6438, #6411, #6117. Prior partial delivery: PR #124 (not redone here).

## Intent Summary

- **Primary actor**: a maintainer of a consumer of this package (the Spec Kitty CLI, the SaaS ingest), who must know which lifecycle event shapes the package guarantees and whether the CLI's emitted shapes are covered.
- **Trigger**: PR #124 shipped part of #119 (three schemas and fixtures) but left the written reconciliation, the invalid-fixture breadth, the reducer replay evidence and the RACI decision events undone; #62 left WPStatusChanged lane breadth untested.
- **Desired outcome**: one minor release (11.1.0) in which (a) every lifecycle event type the CLI emits has valid and invalid conformance evidence, (b) a lifecycle replay stream proves the reducer's fold, (c) WPStatusChanged is evidenced on every lane, (d) the runtime's SignificanceEvaluated and DecisionTimeoutExpired events have package-owned contracts with a RACI actor whose identity is always present, and (e) a written reconciliation records how each of the four lifecycle modules maps to core's current use.
- **Rule that must always hold**: the release is additive. No existing schema, fixture or model changes what it accepts or rejects; the package never imports any consumer; invalid payloads are refused (fail-closed).
- **Operator rulings**: R1 mirror both decision events with a NEW RACI actor model; the existing runtime actor identity stays unchanged. R2 the RACI actor's identifier is required and non-empty (narrower than core's optional identifier); core follows up to always set it.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Consumer validates the runtime's decision events (Priority: P1)

A CLI or SaaS maintainer receives a SignificanceEvaluated or DecisionTimeoutExpired event from the mission runtime and validates it against the package, getting a definite accept or refuse.

**Why this priority**: these are the only runtime-emitted events with no package contract today (deferred by PR #124 pending R1/R2); without them the runtime's decision audit trail is unvalidated.

**Independent Test**: validate the new valid fixtures (accepted) and invalid fixtures (refused, including a missing, null and empty actor identifier) through the package's event validator.

**Acceptance Scenarios**:

1. **Given** a SignificanceEvaluated payload shaped as core emits it with an actor carrying a non-empty identifier, **When** it is validated, **Then** it is accepted.
2. **Given** the same payload with the actor identifier null, empty, or absent, **When** it is validated, **Then** it is refused.
3. **Given** a DecisionTimeoutExpired payload with effective band "low" or a non-positive configured timeout, **When** it is validated, **Then** it is refused.
4. **Given** a DecisionTimeoutExpired payload whose RACI snapshot and escalation targets carry actors with non-empty identifiers, **When** it is validated, **Then** it is accepted.

---

### User Story 2 - Consumer relies on lifecycle refusal and replay evidence (Priority: P1)

A maintainer changing the CLI's lifecycle emission needs proof that the package refuses malformed lifecycle payloads and that a valid lifecycle sequence folds to a known mission state.

**Why this priority**: #119's acceptance criteria require it; today most lifecycle types have only a valid fixture and no replay stream exercises the lifecycle reducer.

**Independent Test**: run the packaged conformance gate; each listed lifecycle type has at least one invalid fixture that is refused, and the replay stream reduces to the committed expected snapshot.

**Acceptance Scenarios**:

1. **Given** an invalid fixture for any of the listed lifecycle types, **When** validated, **Then** it is refused.
2. **Given** the committed lifecycle replay stream, **When** folded through the lifecycle reducer, **Then** the result equals the committed expected snapshot.
3. **Given** the stream with an unknown event type inserted, **When** folded, **Then** the unknown event is skipped and the snapshot is unchanged.

---

### User Story 3 - WPStatusChanged evidenced on every lane (#62) (Priority: P2)

A maintainer needs evidence that a WPStatusChanged event, including one carrying a summary, is handled on rework/rejection transitions, on `done`, and on every lane no fixture covers yet.

**Why this priority**: an evidence gap only (the projection is lane-agnostic, risk rated nil in #62), but it closes an open issue on the same surface.

**Independent Test**: new tests and fixtures for summary-on-rework and summary-on-done pass; a valid fixture exists for each of genesis, in_review, approved, blocked and canceled.

**Acceptance Scenarios**:

1. **Given** a WPStatusChanged with a summary moving from in_review back to in_progress (rework), **When** projected and validated, **Then** the summary rides bounded exactly as on the forward path.
2. **Given** a WPStatusChanged with a summary and `to_lane=done`, **When** projected and validated, **Then** it is accepted with the summary.
3. **Given** a lane-transition fixture for each previously uncovered lane, **When** validated, **Then** each is accepted.

---

### User Story 4 - Maintainer reads one reconciliation record (Priority: P2)

A maintainer reads a single document stating, for each of the four lifecycle modules, which core call sites consume it, which shapes match, and what is left for core follow-ups.

**Why this priority**: it is #119's primary deliverable and the basis for the core follow-up issues.

**Independent Test**: the document exists, names each of the four modules with its core consumers (or "no consumer" for the mission-audit module), and lists each follow-up from the brief.

**Acceptance Scenarios**:

1. **Given** the reconciliation document, **When** a reader looks up any of the four modules, **Then** it states the core consumers, the field-level agreement, and any divergence with its disposition.

---

### User Story 5 - Release consumers see an honest 11.1.0 record (Priority: P3)

A consumer upgrading reads the changelog and compatibility notes for 11.1.0 and learns what was added and that no accept boundary moved; stale core-pin claims are corrected.

**Independent Test**: changelog and compatibility entries for 11.1.0 exist, the package version reads 11.1.0, and no ">=10.5.1,<11" core-pin claim remains in maintained docs.

### Edge Cases

- An actor identifier that is whitespace-only: treated per the same non-empty rule the package uses elsewhere for required identifiers (decided in plan; must be refused if the package's rule refuses it).
- A RACI snapshot whose accountable actor is not human, or whose provenance fields contradict its source (explicit with no override reason, inferred with no rule): mirrored from core's own model rules; refused if core refuses.
- The producer-row schema for SignificanceEvaluated permits a null actor identifier while the new package model refuses it (R2): the producer-row schema is NOT changed (that would move an accept boundary); the divergence is recorded in the reconciliation and compatibility notes.
- A lifecycle replay stream containing a duplicate event (same id): folded once.
- WPStatusChanged on `genesis`: only the legal edges out of genesis (to planned, to canceled) are used for valid fixtures.

## Domain Language

| Term | Meaning | Avoid |
|---|---|---|
| Mission | The canonical unit of work | "feature" |
| Mirror (not depend) | The package reproduces a consumer's shape as its own contract; it never imports the consumer | "share", "re-export from core" |
| RACI role binding | One actor (type + required identifier) bound to a RACI role | conflating with the runtime actor identity |
| Resolved RACI snapshot | The full responsible/accountable/consulted/informed binding with provenance | "raci dict" |
| Accept boundary | The set of inputs a schema, model or fixture accepts vs refuses | — |

## Requirements *(mandatory)*

### Functional Requirements

| ID | Requirement | Status | Delivery | No-op passable? |
|---|---|---|---|---|
| FR-001 | The package MUST provide a RACI role-binding contract with an actor type limited to human, llm or service and a REQUIRED, non-empty actor identifier (R2). | Accepted | [build] | no |
| FR-002 | The package MUST provide a resolved-RACI snapshot contract mirroring core's resolved binding (step, responsible, accountable, consulted, informed, source, inferred rule, override reason) using the FR-001 binding. | Accepted | [build] | no |
| FR-003 | The package MUST provide a SignificanceEvaluated event contract mirroring core's payload (run, decision, step identifiers; significance score; hard-trigger classes; effective band low/medium/high; actor as an FR-001 binding). | Accepted | [build] | no |
| FR-004 | The package MUST provide a DecisionTimeoutExpired event contract mirroring core's payload (identifiers, score, effective band medium/high only, positive configured timeout, escalation targets and actor as FR-001 bindings, RACI snapshot). | Accepted | [build] | no |
| FR-005 | The two new event types MUST be registered with the package's event validator, have generated committed schemas, and have valid and invalid conformance fixtures; invalid fixtures MUST include a null actor identifier and an empty actor identifier. | Accepted | [build] | no |
| FR-006 | The existing runtime actor identity contract MUST remain unchanged (R1). | Accepted | [ratchet] | yes — positive control: its committed schema and existing fixtures are byte-identical to main |
| FR-007 | The new SignificanceEvaluated contract MUST be checked against the producer-row schema already shipped for it; every field MUST agree except the actor identifier, whose narrowing (R2) MUST be recorded. | Accepted | [build] | no |
| FR-008 | Each lifecycle event type lacking one MUST gain at least one invalid conformance fixture that the validator refuses: the Mission* types, PhaseEntered, ReviewRollback, Specify/Plan/Tasks Started/Completed, WPAssigned, HistoryAdded, ErrorLogged, DependencyResolved. | Accepted | [build] | no |
| FR-009 | A valid lifecycle replay stream MUST fold through the lifecycle reducer to a committed expected snapshot, and the conformance gate MUST compare them. | Accepted | [build] | no |
| FR-010 | WPStatusChanged MUST be evidenced with a summary on a rework/rejection transition and on `to_lane=done`, in the unit tests and as conformance fixtures (#62). | Accepted | [build] | no |
| FR-011 | Valid WPStatusChanged fixtures MUST exist for each lane no fixture covers yet: genesis, in_review, approved, blocked, canceled. | Accepted | [build] | no |
| FR-012 | A reconciliation document MUST record, for the lifecycle, project-lifecycle, mission-audit and mission-next modules, the core consumers, field-level agreement and divergences; it MUST state that mission-audit has no core consumer and is unchanged, and list the brief's file-and-link follow-ups. | Accepted | [build] | no |
| FR-013 | The release MUST be versioned 11.1.0 with CHANGELOG and COMPATIBILITY entries stating the additions and that no accept boundary moved. | Accepted | [build] | no |
| FR-014 | Stale claims that core pins ">=10.5.1,<11" MUST be corrected to ">=11.0.0,<12" in maintained docs (historical changelog entries excepted). | Accepted | [build] | no |

### Non-Functional Requirements

| ID | Requirement | Threshold | Status |
|---|---|---|---|
| NFR-001 | Packaged conformance gate stays green and fast | 100% pass (baseline 727 passed, grows only by added cases); runtime under 20 s | Accepted |
| NFR-002 | Static quality | mypy --strict, ruff check, ruff format --check: 0 issues | Accepted |
| NFR-003 | Schema drift | committed schemas equal generated output: 0 drift | Accepted |
| NFR-004 | Additive diff | 0 removed or modified lines in existing schemas, fixtures, models and existing test assertions (version, docs and registration lists excepted) | Accepted |

### Constraints

| ID | Constraint | Status |
|---|---|---|
| C-001 | Additive only: no existing schema, fixture or model changes its accept/reject boundary. | Binding |
| C-002 | No import of spec-kitty, spec-kitty-saas, spec-kitty-tracker or spec-kitty-runtime. | Binding |
| C-003 | Fail-closed: invalid payloads refused; reducers skip unknown types. | Binding |
| C-004 | JSON schemas are generated from the models, never hand-edited. | Binding |
| C-005 | The new decision-event contracts sit beside the mission-next module and do not join any existing event-type set whose membership is a contract (strict envelope set, mission-next reducer set). | Binding |
| C-006 | Out of scope: the two-Lane-enum cleanup (#114), core changes, fixtures for consumer-less payload modules and decision_moment, populating the support matrix with new rows. | Binding |
| C-007 | Escalate instead of deciding: a new field on an existing model, a changed accept boundary, an edit to an existing test expectation, or a core-emit vs producer-row disagreement. | Binding |
| C-008 | One adversarial-squad review per PR; conformance gate run on every PR. | Binding |

### Key Entities

- **RACI role binding**: actor type (human/llm/service) + required non-empty actor identifier.
- **Resolved RACI snapshot**: step, responsible, accountable (human), consulted[], informed[], source (inferred/explicit), inferred rule, override reason.
- **SignificanceEvaluated**: run, decision, step, score, hard-trigger classes, band, actor.
- **DecisionTimeoutExpired**: run, decision, step, score, band (medium/high), configured timeout (>0), escalation targets[], RACI snapshot, actor.
- **Lifecycle replay stream**: ordered valid lifecycle events + expected reduced mission state.

## Success Criteria *(mandatory)*

- **SC-001**: 100% of the 2 new decision event types accept their valid fixtures and refuse all their invalid fixtures, including null and empty actor identifiers. — [build] · no-op passable: no
- **SC-002**: 100% of the lifecycle types listed in FR-008 have at least one refused invalid fixture. — [build] · no-op passable: no
- **SC-003**: the lifecycle replay stream reproduces its expected snapshot exactly (1 of 1). — [build] · no-op passable: no
- **SC-004**: WPStatusChanged has valid evidence on all 10 lanes, and summary evidence on rework and done. — [build] · no-op passable: no
- **SC-005**: 0 existing fixtures change verdict and 0 existing schemas change bytes. — [ratchet] · no-op passable: yes — positive control: the new fixtures in SC-001..SC-004 must exist
- **SC-006**: the reconciliation covers 4 of 4 modules and all 6 follow-ups from the brief. — [build] · no-op passable: no

## Assumptions

- The event type names are `SignificanceEvaluated` (as core persists it) and `DecisionTimeoutExpired` (core's emitter names it so; its payload class is `TimeoutExpiredPayload`).
- The significance score and RACI snapshot stay open objects where core serializes them as dictionaries, except that the snapshot's typed shape is offered as its own contract (FR-002); whether the timeout payload's snapshot field is typed or open is a plan decision bounded by C-007.
- Core's actor identifier may be null today; R2 accepts that core events with a null identifier will be refused by the package until the core follow-up lands.
