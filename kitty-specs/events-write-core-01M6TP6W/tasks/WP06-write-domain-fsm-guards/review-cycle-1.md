---
affected_files: []
cycle_number: 1
mission_slug: events-write-core-01M6TP6W
reproduction_command:
reviewed_at: '2026-11-07T21:52:21Z'
reviewer_agent: claude
wp_id: WP06
---

# WP06 review (Opus): REJECT, cycle 1

1. [MAJOR] The done-evidence check diverges from core (PipelineGuards.java:48-51; PipelineGuardsTest.java:65 locks in the wrong behaviour).
   - Core's `_build_done_evidence` (transition_pipeline.py:361-363) runs whenever evidence is not None, whatever the target lane. reviewer and verdict are refused only when falsy (Python truthiness); reference is refused when falsy or blank after strip.
   - 5 of 20 core probes diverge (planned->claimed with {"review":{}}; in_progress->planned with malformed evidence; in_progress->approved with review:"x"; in_review->done and approved->done with a whitespace-only reviewer or verdict).
   - Fix the check, fix the test, and add these cases to the WP04 corpus through the generator.
2. [MAJOR] WriteFold does not skip core's other non-lane rows (WriteFold.java:47).
   - Core's `is_non_lane_event` (store.py:573-621) also skips rows whose `event_name` starts with `retrospective.`, and rows whose `type` is RetrospectiveCaptured, RetrospectiveCaptureFailed or RetrospectiveSkipped.
   - Any mission with a retrospective is otherwise unreducible: a 500 on every write.
   - Port the predicate (as RowClassifier.java:68-69 does on the read side), with a test per row kind and corpus fold cases.
3. [MINOR] ParityCorpusTest.java:28 also skips `units().size()==1` and quietly drops 3 cases. Name them in a constant and assert the count.
4. [MINOR] The fold accepts rows core rejects: a missing at, actor, force, execution_mode or wp_id, or a structured actor with an unexpected key set. WpWriteState.java:23 `Map.copyOf(review)` throws on a null value. Require the keys, and use an unmodifiable map that allows nulls.
