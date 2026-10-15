# Feature Specification: Review-ref-gated rewinds

**Feature Branch**: `issue-48-review-ref-gated-rewinds`
**Created**: 2026-10-15
**Status**: Draft
**Input**: spec-kitty/spec-kitty-events#48. Operator decision (2026-10-15): option (b), document the two backward rewinds to `in_progress` as a named, force-exempt family. No behaviour change. Plan-time grounding found that `for_review -> in_progress` is under the same validator guard, so the family is three edges, not two.

## Intent Summary

- **Primary actor:** a consumer of the shared status contract. This is a developer or agent maintaining the spec-kitty CLI or the SaaS who reads the transition rules to decide which backward review moves are legal and what they must carry.
- **Trigger:** the consumer is implementing or auditing a reviewer action that sends a work package back. The two cases are a reviewer bouncing a WP from `in_review` to `in_progress` for minor fixes, and reopening an `approved` WP to `in_progress`.
- **Desired outcome:** the contract says plainly that backward review moves come in two guard families:
  - **Review-rejection family**, the four `* -> planned` rewinds. These require `force` and a non-empty `reason`; `review_ref` is optional when forced.
  - **Review-ref-gated rewinds**, `for_review -> in_progress`, `in_review -> in_progress` and `approved -> in_progress`. These are legal without `force` and require a `review_ref` whether forced or not.

  Packaged conformance fixtures pin both families, so a consumer cannot "generalise" one rule onto the other without a red test.
- **Invariant:** the transition validator's accept/reject outcome for every `(from_lane, to_lane, force, review_ref)` combination is unchanged by this mission.
- **Boundary:** this mission does not touch causal ordering metadata (`parent_id`/`lamport_clock`). That is spec-kitty-events#72, a separate follow-up mission.

Discovery was minimized at the operator's explicit instruction ("semi-auto: drive without stopping except for genuine operator decisions"). The one genuine decision, force-required versus force-exempt, was resolved by the operator (Decision Moment `01M4ZJJFKTDBRFBK4B8M5WAYWA`).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Consumer learns which guard a backward move needs (Priority: P1)

A CLI maintainer reads the status contract to wire up "reviewer sends WP back for minor fixes". The contract tells them that `in_review -> in_progress` needs a review reference but not force. They emit it unforced with a `review_ref`, and the contract accepts it.

**Why this priority**: this is the gap #48 reports. The family is defined only by code, not by the contract a consumer reads.

**Independent Test**: read the contract documentation for the three edges, then validate the documented examples against the validator.

**Acceptance Scenarios**:

1. **Given** the published contract documentation, **When** a consumer looks up `in_review -> in_progress` or `approved -> in_progress`, **Then** it names the review-ref-gated rewind family, says force is not required, and says a `review_ref` is required.
2. **Given** an unforced `approved -> in_progress` status change carrying a `review_ref`, **When** it is validated, **Then** it is valid.

### User Story 2 - Conformance fixtures pin the family (Priority: P1)

A downstream repo runs the packaged conformance suite. Fixtures for the three edges fail it if a consumer or a future contract change makes them force-required, or drops the `review_ref` requirement.

**Why this priority**: documentation alone drifts. The fixtures are the enforceable contract other repos consume.

**Independent Test**: run the packaged conformance gate. The new valid and invalid fixtures for the three edges are collected and pass.

**Acceptance Scenarios**:

1. **Given** an unforced rewind with a `review_ref`, **Then** the fixture is classed valid.
2. **Given** an unforced rewind without a `review_ref`, **Then** the fixture is classed invalid.
3. **Given** a forced rewind without a `review_ref`, **Then** the fixture is classed invalid (force does not waive the review reference).

### User Story 3 - Diary consumers understand how the reducer treats the edges (Priority: P2)

A maintainer reading the status-diary reducer documentation learns how each edge is treated:
- `in_review -> in_progress` is treated as a rejection rollback, with rollback precedence.
- `approved -> in_progress` is treated as an approved departure, subject to `from_lane` continuity unless forced.
- Forcing `approved -> in_progress` therefore lets a stale reopen override a finished (`done`) WP.

That last point is why the contract keeps these edges force-exempt.

**Why this priority**: it explains the operator's decision and prevents a future "just force them" change.

**Independent Test**: read the diary documentation and confirm it states this mapping and the forced-reopen hazard.

**Acceptance Scenarios**:

1. **Given** the diary reducer documentation, **When** a maintainer looks up these edges, **Then** it states each edge's reducer treatment and the forced-reopen hazard.

### Edge Cases

- A forced `in_review -> in_progress` with a `review_ref` stays valid. Force is allowed but not required.
- The four `* -> planned` review-rejection edges keep their force-required behaviour unchanged.
- Other backward moves outside both families keep their current validity.

## Requirements *(mandatory)*

### Functional Requirements

| ID | Requirement | Status | Delivery | No-op passable? |
|---|---|---|---|---|
| FR-001 | The status contract documentation names a "review-ref-gated rewind" family containing exactly `for_review -> in_progress`, `in_review -> in_progress` and `approved -> in_progress`. It states that they are legal without force and require a `review_ref` whether forced or not, and it presents this family next to the force-required review-rejection family. | Proposed | [build] | no |
| FR-002 | The packaged conformance fixtures include, for each of the three edges, a valid case (unforced, with `review_ref`), an invalid case (unforced, no `review_ref`) and an invalid case (forced, no `review_ref`). The packaged conformance gate collects and passes them all. | Proposed | [build] | no |
| FR-003 | The validator's accept/reject outcome for every `(from_lane, to_lane, force, review_ref)` combination is identical before and after this mission. | Proposed | [ratchet] | yes (positive control: FR-002's fixtures, which exercise the same validator on the same edges) |
| FR-004 | The diary reducer documentation states how it treats each edge (rollback versus approved departure) and the hazard that forcing `approved -> in_progress` lets a stale reopen override a `done` WP. | Proposed | [build] | no |
| FR-005 | The release notes record the clarification as a patch-level, documentation-and-fixture change with no behaviour change, citing spec-kitty-events#48. | Proposed | [build] | no |

### Non-Functional Requirements

| ID | Requirement | Threshold | Status |
|---|---|---|---|
| NFR-001 | No consumer-visible behaviour change | 0 changes in validator outcomes across the existing in-repo suite and the packaged conformance gate | Proposed |
| NFR-002 | Release level | Patch (no public API addition or removal) | Proposed |

### Constraints

| ID | Constraint | Status |
|---|---|---|
| C-001 | No new public API symbol. The family may be named as a private module constant only if that keeps it consistent with the existing private review-rejection family helper. | Proposed |
| C-002 | Out of scope: causal metadata on diary rows (spec-kitty-events#72, follow-up mission). | Proposed |
| C-003 | The envelope schema version stays `3.0.0`. | Proposed |

### Key Entities

- **Review-rejection family**: backward rewinds to `planned`. These require `force` and `reason`; `review_ref` is optional when forced.
- **Review-ref-gated rewind family**: backward rewinds to `in_progress`. These require `review_ref` and not `force`.

## Success Criteria *(mandatory)*

- **SC-001**: A consumer can find the family's guard rules in the contract documentation in one lookup, with no need to read validator code. — [build] · no-op passable: no
- **SC-002**: 9 new conformance cases (3 edges × 3 cases) pass in the packaged conformance gate. — [build] · no-op passable: no
- **SC-003**: 0 changed outcomes in existing validator tests and conformance fixtures. — [ratchet] · no-op passable: yes (positive control: SC-002)

## Assumptions

- The CLI emits these edges unforced with a `review_ref` today, so option (b) matches current producer behaviour.
- The existing invalid fixture `wp_status_changed_unforced_in_review_to_planned` is the pattern the new fixtures follow.
