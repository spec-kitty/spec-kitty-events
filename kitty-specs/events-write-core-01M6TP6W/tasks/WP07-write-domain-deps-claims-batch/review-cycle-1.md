---
affected_files: []
cycle_number: 1
mission_slug: events-write-core-01M6TP6W
reproduction_command:
reviewed_at: '2026-11-08T03:27:44Z'
reviewer_agent: claude
wp_id: WP07
---

# WP07 review (Opus): REJECT, cycle 1

1. [MAJOR] UnitJudge.java:36 passes the row's force into the transition guards for START_IMPLEMENTATION, while ClaimRules.java:42 reads it as review_lane_exit. Core never forces planned->claimed or claimed->in_progress on a start. Fix: the force that reaches the guards for start_implementation is `force && currentLane in {for_review, in_review, approved}`. Add a core case: start_impl(force=True) from planned with [WP99].
2. [MAJOR] StateProjections.java:21-26 (latest implementer) scans time-sorted, de-duplicated events. Core's _read_events_for_implementer scans the log in append order with no sort. Fix: project over the raw transitions in log order. Add the clock-skew case (skew-resume-a) to the corpus.
3. [MINOR] A start_implementation row with reason but no force: refuse it as core does (start_rejected), or pin it as a protocol case.
4. [MINOR] Messages differ from core: ClaimRules.java:83 says 'uninitialized' where core says 'genesis'; :73 says 'unknown' where core says 'none on record'; a structured actor must show as tool:model:profile (actor_compact_identity).
5. [MINOR] FullParityCorpusTest duplicates ParityCorpusTest; give each of the 11 later-layer cases its own reason.
