---
affected_files: []
cycle_number: 1
mission_slug: events-write-core-01M6TP6W
reproduction_command:
reviewed_at: '2026-11-07T15:53:31Z'
reviewer_agent: claude
wp_id: WP04
---

# WP04 review (Opus): REJECT, cycle 1

## [MAJOR] findings
1. Three cases depend on core arguments that are not in the case's rows: guard-implementation-evidence-missing (implementation_evidence_present=False), claim-start-blank-force-note and claim-start-force-outside-review (operator_force_note / review_lane_exit). The generator must refuse any core argument that is not derivable from the case (log, tasks, rows, operation). Drop the evidence-hint case and record that half of the guard as unreachable. Either encode the force note in the row in the contract's shape or drop those two cases.
2. batch-clock-ahead-approved-departure never exercises the lift: the approval ends up behind the departure. Set the edited time relative to the log (latest time + 1 day), regenerate, and add a test that the approval is later than the departure.
3. The legacy dependency forms do not separate a correct runner from a wrong one. Use `dependencies: "[]"` (quoted), and add bare and comma variants with an unmet dependency that expect DEPENDENCIES_NOT_SATISFIED.

## [MINOR] findings
- claim-implementer-of-record-after-rework: make the rework row reviewer-a's, and add a third-actor WP_ALREADY_CLAIMED case.
- decision15-batch-second-unit-claim-conflict: use the same-WP R13 shape.
- CORE_PYTHON: read major.minor from the core checkout's .python-version.
- map_exception_to_refusal: require a TransitionError (or the lifecycle classes); anything else is a hard error.
- Add malformed-evidence cases on legal edges (approved->done, in_review->done).
- Add an accepted case for a WP with no tasks file.
- Record a refused start from planned in the two-unit R13 shape.
- unreachable.json: cite transition_pipeline.py:289-304 and note that core's batch path leaves the context empty but is not a wire path.
