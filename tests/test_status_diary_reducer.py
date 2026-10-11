"""Reducer unit tests for the status diary (:mod:`spec_kitty_events.diary`).

Ports the behavioural core of the CLI's ``tests/status/test_reducer.py``
(issue #41 moved the reducer here verbatim): empty stream, ordering,
deduplication, force_count, rollback-aware concurrency, runtime-slot
carry-forward, the ``planned -> claimed`` policy_metadata claim triple,
the ``review_result`` slot rules, annotation folding, the traversal-guarded
mission slug, the row-partition contract, plus the packaged golden replays.
"""

from __future__ import annotations

import json
from typing import Any

import pytest

import spec_kitty_events.status as status_module
from spec_kitty_events.conformance.loader import (
    load_reducer_output,
    load_replay_stream,
)
from spec_kitty_events.diary import (
    ANNOTATION_KIND,
    NON_DISPLAY_LANES,
    DiaryError,
    EventStream,
    InnerStateChanged,
    Lane,
    State,
    StatusEvent,
    parse_diary,
    reduce,
    reduce_parsed,
)

# ── Helpers ────────────────────────────────────────────────────────────────────


def _ulid(n: int) -> str:
    return "01JZZZZ" + "0" * 15 + f"{n:04d}"


def _row(
    event_id: str,
    wp_id: str = "WP01",
    from_lane: str | None = "planned",
    to_lane: str = "claimed",
    at: str = "2026-02-08T12:00:00+00:00",
    actor: Any = "claude-opus",
    *,
    mission_slug: str = "034-feature-name",
    force: bool = False,
    execution_mode: str = "worktree",
    reason: str | None = None,
    review_ref: str | None = None,
    evidence: dict[str, Any] | None = None,
    review_result: dict[str, Any] | None = None,
    policy_metadata: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Build one raw diary row with sensible defaults."""
    row: dict[str, Any] = {
        "event_id": event_id,
        "mission_slug": mission_slug,
        "wp_id": wp_id,
        "from_lane": from_lane,
        "to_lane": to_lane,
        "at": at,
        "actor": actor,
        "force": force,
        "execution_mode": execution_mode,
        "reason": reason,
        "review_ref": review_ref,
        "evidence": evidence,
    }
    if review_result is not None:
        row["review_result"] = review_result
    if policy_metadata is not None:
        row["policy_metadata"] = policy_metadata
    return row


def _annotation(
    event_id: str,
    wp_id: str = "WP01",
    at: str = "2026-02-08T12:00:00+00:00",
    actor: Any = "claude",
    delta: dict[str, Any] | None = None,
) -> dict[str, Any]:
    return {
        "event_id": event_id,
        "kind": ANNOTATION_KIND,
        "wp_id": wp_id,
        "at": at,
        "actor": actor,
        "delta": delta or {},
    }


_DONE_EVIDENCE = {
    "review": {
        "reviewer": "reviewer-1",
        "verdict": "approved",
        "reference": "feedback://034/WP01/approval",
    },
    "repos": [{"repo": "demo", "branch": "kitty/m-01", "commit": "abc1234567890"}],
    "verification": [{"command": "make test-fast", "result": "pass", "summary": "green"}],
}

_REVIEW_RESULT = {
    "reviewer": "reviewer-1",
    "verdict": "approved",
    "reference": "feedback://034/WP01/approved",
}


# ── Empty stream ───────────────────────────────────────────────────────────────


class TestReduceEmpty:
    def test_reduce_empty_events(self) -> None:
        state = reduce([])

        assert state.mission_slug == ""
        assert state.event_count == 0
        assert state.last_event_id is None
        assert state.work_packages == {}
        # NON_DISPLAY_LANES (genesis, uninitialized) are excluded from the summary
        assert state.summary == {lane.value: 0 for lane in Lane if lane not in NON_DISPLAY_LANES}

    def test_reduce_generator_input(self) -> None:
        rows = iter([_row(_ulid(1))])
        state = reduce(rows)
        assert state.event_count == 1


# ── Basic folds ────────────────────────────────────────────────────────────────


class TestReduceSingleEvent:
    def test_reduce_single_event(self) -> None:
        state = reduce([_row(_ulid(1), actor="claude-opus")])

        assert state.mission_slug == "034-feature-name"
        assert state.event_count == 1
        assert state.last_event_id == _ulid(1)
        wp = state.work_packages["WP01"]
        assert wp["lane"] == "claimed"
        assert wp["actor"] == "claude-opus"
        assert wp["last_transition_at"] == "2026-02-08T12:00:00+00:00"
        assert wp["last_event_id"] == _ulid(1)
        assert wp["force_count"] == 0
        assert state.summary["claimed"] == 1


class TestReduceOrderedEvents:
    def test_reduce_ordered_events(self) -> None:
        events = [
            _row(_ulid(1), to_lane="claimed", at="2026-02-08T12:00:00+00:00"),
            _row(
                _ulid(2),
                from_lane="claimed",
                to_lane="in_progress",
                at="2026-02-08T13:00:00+00:00",
            ),
            _row(
                _ulid(3),
                from_lane="in_progress",
                to_lane="for_review",
                at="2026-02-08T14:00:00+00:00",
            ),
        ]
        state = reduce(events)

        assert state.event_count == 3
        assert state.work_packages["WP01"]["lane"] == "for_review"
        assert state.summary["for_review"] == 1


class TestReduceOutOfOrder:
    def test_reduce_out_of_order_events(self) -> None:
        """Events are sorted by (at, event_id), so order in list doesn't matter."""
        events = [
            _row(
                _ulid(3),
                from_lane="in_progress",
                to_lane="for_review",
                at="2026-02-08T14:00:00+00:00",
            ),
            _row(_ulid(1), to_lane="claimed", at="2026-02-08T12:00:00+00:00"),
            _row(
                _ulid(2),
                from_lane="claimed",
                to_lane="in_progress",
                at="2026-02-08T13:00:00+00:00",
            ),
        ]
        state = reduce(events)

        assert state.work_packages["WP01"]["lane"] == "for_review"
        assert state.last_event_id == _ulid(3)


class TestReduceSameTimestampTiebreak:
    def test_distinct_same_at_events_are_order_independent(self) -> None:
        """Two distinct events for one WP sharing `at` still fold deterministically.

        The sort key is ``(at, event_id)``, not ``at`` alone: without the
        ``event_id`` tiebreak, a stable sort would apply same-``at`` events in
        *list* order, so the fold would depend on the order rows happen to
        appear in ``status.events.jsonl`` rather than on the events themselves.
        """
        claim = _row(_ulid(1), from_lane="planned", to_lane="claimed")
        start = _row(_ulid(2), from_lane="claimed", to_lane="in_progress")
        assert claim["at"] == start["at"]  # concurrent: the tiebreak is what matters

        forward = reduce([claim, start])
        reversed_order = reduce([start, claim])

        assert forward.to_dict() == reversed_order.to_dict()
        assert forward.work_packages["WP01"]["lane"] == "in_progress"
        assert forward.work_packages["WP01"]["last_event_id"] == _ulid(2)


class TestReduceDeduplication:
    def test_reduce_deduplication(self) -> None:
        """Duplicate event_ids are deduplicated; first occurrence kept."""
        event = _row(_ulid(1), actor="claude-opus")
        # Same event_id but different actor (simulating corruption)
        duplicate = _row(_ulid(1), actor="other-agent")
        state = reduce([event, duplicate])

        assert state.event_count == 1
        assert state.work_packages["WP01"]["actor"] == "claude-opus"


class TestReduceMultipleWPs:
    def test_reduce_multiple_wps(self) -> None:
        events = [
            _row(_ulid(1), wp_id="WP01", to_lane="claimed"),
            _row(_ulid(2), wp_id="WP02", to_lane="claimed"),
            _row(_ulid(3), wp_id="WP01", from_lane="claimed", to_lane="in_progress"),
        ]
        state = reduce(events)

        assert set(state.work_packages) == {"WP01", "WP02"}
        assert state.work_packages["WP01"]["lane"] == "in_progress"
        assert state.work_packages["WP02"]["lane"] == "claimed"
        assert state.summary["claimed"] == 1
        assert state.summary["in_progress"] == 1


class TestReduceForceCount:
    def test_force_count_increments_and_carries(self) -> None:
        events = [
            _row(_ulid(1), to_lane="claimed", force=False),
            _row(
                _ulid(2),
                from_lane="claimed",
                to_lane="planned",
                force=True,
                reason="rewind",
            ),
            _row(_ulid(3), to_lane="claimed", force=True, reason="reclaim"),
        ]
        state = reduce(events)
        wp = state.work_packages["WP01"]
        assert wp["force_count"] == 2
        assert wp["lane"] == "claimed"


# ── Rollback-aware concurrency ─────────────────────────────────────────────────


class TestReduceConcurrentRollbackPrecedence:
    def test_in_review_to_in_progress_rollback_beats_concurrent_approval(self) -> None:
        """Current reviewer rollback transition wins over same-timestamp approval."""
        at = "2026-02-08T15:00:00+00:00"
        events = [
            _row(
                _ulid(1),
                from_lane="in_review",
                to_lane="in_progress",
                at=at,
                actor="reviewer-a",
            ),
            _row(
                _ulid(2),
                from_lane="in_review",
                to_lane="approved",
                at=at,
                actor="reviewer-b",
                review_ref="review://WP01/approved",
            ),
        ]

        state = reduce(events)

        assert state.work_packages["WP01"]["lane"] == "in_progress"
        assert state.work_packages["WP01"]["last_event_id"] == _ulid(1)
        assert state.summary["in_progress"] == 1
        assert state.summary["approved"] == 0

    def test_legacy_for_review_rollback_still_beats_concurrent_forward(self) -> None:
        """Legacy reviewer rollback shape keeps rollback precedence."""
        at = "2026-02-08T15:00:00+00:00"
        events = [
            _row(
                _ulid(1),
                from_lane="for_review",
                to_lane="in_progress",
                at=at,
                actor="reviewer-a",
                review_ref="review://WP01/changes-requested",
            ),
            _row(
                _ulid(2),
                from_lane="for_review",
                to_lane="approved",
                at=at,
                actor="reviewer-b",
                review_ref="review://WP01/approved",
            ),
        ]

        state = reduce(events)

        assert state.work_packages["WP01"]["lane"] == "in_progress"
        assert state.work_packages["WP01"]["last_event_id"] == _ulid(1)

    def test_planned_rejection_beats_later_stale_approval(self) -> None:
        """#69: a committed ``in_review -> planned`` rejection is never
        overwritten by a causally-concurrent approval that carries a LATER
        wall-clock ``at``. The approval's ``from_lane`` (``in_review``) does
        not match the lane the rejection left the WP in (``planned``), so it
        never causally followed the rejection -- it must be dropped
        regardless of timestamp ordering (defect B: the old arbitration only
        checked EQUAL timestamps)."""
        events = [
            _row(_ulid(1), from_lane="planned", to_lane="claimed", at="2026-02-08T12:00:00+00:00"),
            _row(
                _ulid(2),
                from_lane="claimed",
                to_lane="in_progress",
                at="2026-02-08T12:05:00+00:00",
            ),
            _row(
                _ulid(3),
                from_lane="in_progress",
                to_lane="for_review",
                at="2026-02-08T12:10:00+00:00",
            ),
            _row(
                _ulid(4),
                from_lane="for_review",
                to_lane="in_review",
                at="2026-02-08T12:15:00+00:00",
            ),
            _row(
                _ulid(5),
                from_lane="in_review",
                to_lane="planned",
                at="2026-02-08T12:20:00+00:00",
                actor="reviewer-a",
                review_ref="review://WP01/changes-requested",
            ),
            _row(
                _ulid(6),
                from_lane="in_review",
                to_lane="approved",
                at="2026-02-08T12:25:00+00:00",
                actor="reviewer-b",
                review_result=_REVIEW_RESULT,
            ),
        ]

        state = reduce(events)

        assert state.work_packages["WP01"]["lane"] == "planned"
        assert state.work_packages["WP01"]["last_event_id"] == _ulid(5)
        assert state.summary["approved"] == 0
        assert state.summary["planned"] == 1

    def test_in_progress_rejection_beats_later_stale_approval(self) -> None:
        """Same as above but the rejection rolls back to ``in_progress``
        (the legacy #1475 shape already recognized by ``_is_rollback_event``)
        -- isolates defect B alone: this shape still lost pre-fix because the
        equal-``at`` gate never fired for a later-timestamp approval."""
        events = [
            _row(_ulid(1), from_lane="planned", to_lane="claimed", at="2026-02-08T12:00:00+00:00"),
            _row(
                _ulid(2),
                from_lane="claimed",
                to_lane="in_progress",
                at="2026-02-08T12:05:00+00:00",
            ),
            _row(
                _ulid(3),
                from_lane="in_progress",
                to_lane="for_review",
                at="2026-02-08T12:10:00+00:00",
            ),
            _row(
                _ulid(4),
                from_lane="for_review",
                to_lane="in_review",
                at="2026-02-08T12:15:00+00:00",
            ),
            _row(
                _ulid(5),
                from_lane="in_review",
                to_lane="in_progress",
                at="2026-02-08T12:20:00+00:00",
                actor="reviewer-a",
                review_ref="review://WP01/changes-requested",
            ),
            _row(
                _ulid(6),
                from_lane="in_review",
                to_lane="approved",
                at="2026-02-08T12:25:00+00:00",
                actor="reviewer-b",
                review_result=_REVIEW_RESULT,
            ),
        ]

        state = reduce(events)

        assert state.work_packages["WP01"]["lane"] == "in_progress"
        assert state.work_packages["WP01"]["last_event_id"] == _ulid(5)
        assert state.summary["approved"] == 0

    def test_continuous_rework_after_rejection_is_not_suppressed(self) -> None:
        """Over-suppression guard: a rejection followed by a CONTINUOUS
        rework chain (each event's ``from_lane`` matches the prior event's
        ``to_lane``) must reach ``approved`` -- the from_lane-continuity rule
        must not block genuine forward progress that causally follows the
        rollback. Must stay green both before and after the fix."""
        events = [
            _row(
                _ulid(1),
                from_lane="in_review",
                to_lane="planned",
                at="2026-02-08T12:00:00+00:00",
                actor="reviewer-a",
                review_ref="review://WP01/changes-requested",
            ),
            _row(_ulid(2), from_lane="planned", to_lane="claimed", at="2026-02-08T12:05:00+00:00"),
            _row(
                _ulid(3),
                from_lane="claimed",
                to_lane="in_progress",
                at="2026-02-08T12:10:00+00:00",
            ),
            _row(
                _ulid(4),
                from_lane="in_progress",
                to_lane="for_review",
                at="2026-02-08T12:15:00+00:00",
            ),
            _row(
                _ulid(5),
                from_lane="for_review",
                to_lane="in_review",
                at="2026-02-08T12:20:00+00:00",
            ),
            _row(
                _ulid(6),
                from_lane="in_review",
                to_lane="approved",
                at="2026-02-08T12:25:00+00:00",
                actor="reviewer-b",
                review_result=_REVIEW_RESULT,
            ),
        ]

        state = reduce(events)

        assert state.work_packages["WP01"]["lane"] == "approved"

    def test_forced_recovery_after_rejection_applies(self) -> None:
        """Break-glass guard: a ``force=True`` transition emitted after a
        rollback applies even when its ``from_lane`` does not match the lane
        the rollback set. ``force`` is the deliberate, reason-carrying
        override, so the from_lane-continuity precedence drop (#69) must not
        swallow it. The #69 hazard is an *unforced* approval, so exempting
        force does not reopen it (see ``test_planned_rejection_beats_later_stale_approval``)."""
        events = [
            _row(
                _ulid(1),
                from_lane="in_review",
                to_lane="planned",
                at="2026-02-08T12:20:00+00:00",
                actor="reviewer-a",
                review_ref="review://WP01/changes-requested",
            ),
            _row(
                _ulid(2),
                from_lane="in_review",
                to_lane="blocked",
                at="2026-02-08T12:25:00+00:00",
                actor="operator",
                force=True,
                reason="break-glass: escalate blocked dependency",
            ),
        ]

        state = reduce(events)

        assert state.work_packages["WP01"]["lane"] == "blocked"
        assert state.work_packages["WP01"]["last_event_id"] == _ulid(2)

    def test_pushed_cancel_beats_later_stale_submit(self) -> None:
        """#5967 (cancel shape): the lead's pushed ``in_progress -> canceled``
        is never overwritten by a teammate's stale ``in_progress ->
        for_review`` that carries a LATER wall-clock ``at`` (made in a clone
        that had not pulled the cancel). The current setter is a cancel, not a
        rollback, so the rollback-setter-only form of the #69 guard missed it;
        the stale submit's ``from_lane`` (``in_progress``) does not match the
        lane in force (``canceled``), so from_lane-continuity drops it."""
        events = [
            _row(_ulid(1), from_lane="planned", to_lane="claimed", at="2026-02-08T12:00:00+00:00"),
            _row(
                _ulid(2), from_lane="claimed", to_lane="in_progress", at="2026-02-08T12:05:00+00:00"
            ),
            _row(
                _ulid(3),
                from_lane="in_progress",
                to_lane="canceled",
                at="2026-02-08T12:10:00+00:00",
                actor="lead",
                force=True,
                reason="descoped: out of scope",
            ),
            _row(
                _ulid(4),
                from_lane="in_progress",
                to_lane="for_review",
                at="2026-02-08T12:15:00+00:00",
                actor="implementer",
            ),
        ]

        state = reduce(events)

        assert state.work_packages["WP01"]["lane"] == "canceled"
        assert state.work_packages["WP01"]["last_event_id"] == _ulid(3)

    def test_rejection_then_rework_claim_beats_later_stale_approval(self) -> None:
        """#5967 (rework shape): after a reviewer's pushed ``in_review ->
        planned`` rejection the implementer reclaims the WP (``planned ->
        claimed -> in_progress``); a SECOND reviewer, in a clone that had not
        pulled, approves the stale ``in_review -> approved`` with a LATER ``at``.
        Here the current setter is the rework claim (``claimed -> in_progress``),
        NOT the rollback, so the rollback-setter-only form of the #69 guard let
        the stale approval through. Its ``from_lane`` (``in_review``) does not
        match the lane in force (``in_progress``), so it is dropped and the WP
        stays in the reclaimed rework."""
        events = [
            _row(_ulid(1), from_lane="planned", to_lane="claimed", at="2026-02-08T12:00:00+00:00"),
            _row(
                _ulid(2), from_lane="claimed", to_lane="in_progress", at="2026-02-08T12:05:00+00:00"
            ),
            _row(
                _ulid(3),
                from_lane="in_progress",
                to_lane="for_review",
                at="2026-02-08T12:10:00+00:00",
            ),
            _row(
                _ulid(4),
                from_lane="for_review",
                to_lane="in_review",
                at="2026-02-08T12:15:00+00:00",
            ),
            _row(
                _ulid(5),
                from_lane="in_review",
                to_lane="planned",
                at="2026-02-08T12:20:00+00:00",
                actor="reviewer-a",
                review_ref="review://WP01/changes-requested",
            ),
            _row(_ulid(6), from_lane="planned", to_lane="claimed", at="2026-02-08T12:25:00+00:00"),
            _row(
                _ulid(7), from_lane="claimed", to_lane="in_progress", at="2026-02-08T12:30:00+00:00"
            ),
            _row(
                _ulid(8),
                from_lane="in_review",
                to_lane="approved",
                at="2026-02-08T12:35:00+00:00",
                actor="reviewer-b",
                review_result=_REVIEW_RESULT,
            ),
        ]

        state = reduce(events)

        assert state.work_packages["WP01"]["lane"] == "in_progress"
        assert state.work_packages["WP01"]["last_event_id"] == _ulid(7)
        assert state.summary["approved"] == 0

    def test_in_review_to_claimed_rejection_beats_later_stale_approval(self) -> None:
        """#69 precedence holds for the ``in_review -> claimed`` rollback shape
        too (the third pre-review target in ``_REJECTION_ROLLBACK_TARGETS``): a
        committed rejection to ``claimed`` is never overwritten by a later
        stale ``in_review -> approved`` approval whose ``from_lane`` does not
        match ``claimed``."""
        events = [
            _row(_ulid(1), from_lane="planned", to_lane="claimed", at="2026-02-08T12:00:00+00:00"),
            _row(
                _ulid(2),
                from_lane="claimed",
                to_lane="in_progress",
                at="2026-02-08T12:05:00+00:00",
            ),
            _row(
                _ulid(3),
                from_lane="in_progress",
                to_lane="for_review",
                at="2026-02-08T12:10:00+00:00",
            ),
            _row(
                _ulid(4),
                from_lane="for_review",
                to_lane="in_review",
                at="2026-02-08T12:15:00+00:00",
            ),
            _row(
                _ulid(5),
                from_lane="in_review",
                to_lane="claimed",
                at="2026-02-08T12:20:00+00:00",
                actor="reviewer-a",
                review_ref="review://WP01/changes-requested",
            ),
            _row(
                _ulid(6),
                from_lane="in_review",
                to_lane="approved",
                at="2026-02-08T12:25:00+00:00",
                actor="reviewer-b",
                review_result=_REVIEW_RESULT,
            ),
        ]

        state = reduce(events)

        assert state.work_packages["WP01"]["lane"] == "claimed"
        assert state.work_packages["WP01"]["last_event_id"] == _ulid(5)
        assert state.summary["approved"] == 0

    def test_migration_seed_does_not_reject_the_real_lifecycle(self) -> None:
        """#5967 follow-up: a reconstruction SEED is not a decision, so it must
        not reject the WP's later recorded lifecycle.

        A corpus backfill (actor ``migration:backfill_runtime_state``) seeds
        ``planned -> claimed`` at the genesis instant, restating what the legacy
        frontmatter said. The real event log then records the true lifecycle
        ``planned -> blocked -> in_progress -> for_review -> in_review ->
        approved`` with later ``at`` values. Sorted by ``at`` the seed applies
        first and becomes the current setter; the real ``planned -> blocked``
        then carries ``from_lane=planned`` != the seeded ``claimed``. Under the
        rollback-generalized from_lane-continuity drop this stranded the WP at
        ``claimed``. Because the current setter is a reconstruction seed, the
        drop is now suppressed and the real lifecycle wins (ends ``approved``)."""
        events = [
            _row(
                _ulid(2),
                from_lane="planned",
                to_lane="claimed",
                at="2026-02-08T12:00:00+00:00",
                actor="migration:backfill_runtime_state",
            ),
            _row(
                _ulid(3),
                from_lane="planned",
                to_lane="blocked",
                at="2026-02-08T18:00:00+00:00",
                actor="implement-command",
            ),
            _row(
                _ulid(4),
                from_lane="blocked",
                to_lane="in_progress",
                at="2026-02-08T18:05:00+00:00",
                actor="user",
            ),
            _row(
                _ulid(5),
                from_lane="in_progress",
                to_lane="for_review",
                at="2026-02-08T18:10:00+00:00",
                actor="user",
            ),
            _row(
                _ulid(6),
                from_lane="for_review",
                to_lane="in_review",
                at="2026-02-08T18:15:00+00:00",
                actor="user",
            ),
            _row(
                _ulid(7),
                from_lane="in_review",
                to_lane="approved",
                at="2026-02-08T18:20:00+00:00",
                actor="user",
                review_result=_REVIEW_RESULT,
            ),
        ]

        state = reduce(events)

        assert state.work_packages["WP01"]["lane"] == "approved"
        assert state.work_packages["WP01"]["last_event_id"] == _ulid(7)
        assert state.summary["approved"] == 1

    def test_migration_seed_still_holds_when_no_real_transition_supersedes_it(self) -> None:
        """The seed exemption only *yields* to a real lifecycle; with none, the
        seed's own reconstructed state stands.

        A WP whose only runtime transition is the backfill seed
        (``planned -> in_progress``) must stay ``in_progress`` -- the exemption
        must not drop the seed itself (a naive "ignore migration seeds" rule
        would regress such a WP back to ``planned``)."""
        events = [
            _row(
                _ulid(1),
                from_lane="genesis",
                to_lane="planned",
                at="2026-02-08T12:00:00+00:00",
                actor="finalize-tasks",
            ),
            _row(
                _ulid(2),
                from_lane="planned",
                to_lane="in_progress",
                at="2026-02-08T12:00:00.000500+00:00",
                actor="migration:backfill_runtime_state",
            ),
        ]

        state = reduce(events)

        assert state.work_packages["WP01"]["lane"] == "in_progress"
        assert state.work_packages["WP01"]["last_event_id"] == _ulid(2)


class TestSummaryCounts:
    def test_summary_counts_match_wp_states(self) -> None:
        events = [
            _row(_ulid(1), wp_id="WP01", to_lane="in_progress"),
            _row(_ulid(2), wp_id="WP02", to_lane="in_progress"),
            _row(_ulid(3), wp_id="WP03", from_lane="planned", to_lane="for_review"),
            _row(
                _ulid(4),
                wp_id="WP04",
                from_lane="for_review",
                to_lane="done",
                evidence=_DONE_EVIDENCE,
            ),
        ]
        state = reduce(events)

        lane_counts: dict[str, int] = {
            lane.value: 0 for lane in Lane if lane not in NON_DISPLAY_LANES
        }
        for wp_state in state.work_packages.values():
            lane_counts[wp_state["lane"]] += 1

        assert state.summary == lane_counts


# ── Runtime slots ──────────────────────────────────────────────────────────────


class TestRuntimeSlotCarryForward:
    def test_transition_preserves_runtime_slots(self) -> None:
        """A later transition never erases slots written by earlier ones."""
        events = [
            _row(
                _ulid(1),
                to_lane="claimed",
                policy_metadata={
                    "shell_pid": 7,
                    "shell_pid_created_at": "t1",
                    "agent": "claude",
                },
            ),
            _annotation(
                _ulid(2),
                delta={
                    "note": "working",
                    "subtasks": {"T1": "done"},
                    "tracker_refs": ["TCK-1"],
                },
            ),
            _row(_ulid(3), from_lane="claimed", to_lane="in_progress"),
        ]
        state = reduce(events)
        wp = state.work_packages["WP01"]
        assert wp["shell_pid"] == 7
        assert wp["agent"] == "claude"
        assert wp["notes"] == ["working"]
        assert wp["subtasks"] == {"T1": "done"}
        assert wp["tracker_refs"] == ["TCK-1"]
        assert wp["lane"] == "in_progress"

    def test_claim_triple_from_policy_metadata(self) -> None:
        events = [
            _row(
                _ulid(1),
                to_lane="claimed",
                policy_metadata={
                    "shell_pid": 99,
                    "shell_pid_created_at": "2026-02-08T12:00:01+00:00",
                    "agent": "codex",
                },
            )
        ]
        state = reduce(events)
        wp = state.work_packages["WP01"]
        assert wp["shell_pid"] == 99
        assert wp["shell_pid_created_at"] == "2026-02-08T12:00:01+00:00"
        assert wp["agent"] == "codex"

    def test_empty_agent_sidecar_is_a_no_op(self) -> None:
        """An empty agent sidecar never blanks an existing slot."""
        events = [
            _row(_ulid(1), to_lane="claimed", policy_metadata={"agent": "claude"}),
            _row(
                _ulid(2),
                from_lane="claimed",
                to_lane="planned",
                force=True,
                reason="rewind",
            ),
            _row(_ulid(3), to_lane="claimed", policy_metadata={"agent": ""}),
        ]
        state = reduce(events)
        assert state.work_packages["WP01"]["agent"] == "claude"


# ── review_result slot rules ───────────────────────────────────────────────────


class TestReviewResultSlot:
    def test_outbound_from_in_review_writes_explicit_none(self) -> None:
        """A forced exit from in_review with no verdict lands an explicit None."""
        events = [
            _row(
                _ulid(1),
                from_lane="planned",
                to_lane="in_review",
                force=True,
                reason="skip ahead",
            ),
            _row(
                _ulid(2),
                from_lane="in_review",
                to_lane="planned",
                force=True,
                reason="forced rewind",
            ),
        ]
        state = reduce(events)
        assert state.work_packages["WP01"]["review_result"] is None

    def test_populated_verdict_overrides_regardless_of_from_lane(self) -> None:
        """A single-hop transition carrying a verdict records it."""
        events = [
            _row(
                _ulid(1),
                from_lane="in_progress",
                to_lane="approved",
                evidence=_DONE_EVIDENCE,
                review_result=_REVIEW_RESULT,
            ),
        ]
        state = reduce(events)
        assert state.work_packages["WP01"]["review_result"] == _REVIEW_RESULT

    def test_slot_is_sticky_when_absent(self) -> None:
        """An unrelated later transition carries the verdict forward verbatim."""
        events = [
            _row(
                _ulid(1),
                from_lane="in_progress",
                to_lane="approved",
                evidence=_DONE_EVIDENCE,
                review_result=_REVIEW_RESULT,
            ),
            _row(
                _ulid(2),
                from_lane="approved",
                to_lane="done",
                force=True,
                reason="close",
            ),
        ]
        state = reduce(events)
        assert state.work_packages["WP01"]["review_result"] == _REVIEW_RESULT


# ── Annotation folding ─────────────────────────────────────────────────────────


class TestAnnotationFolding:
    def test_annotation_only_stream_materializes_uninitialized_wp(self) -> None:
        events = [_annotation(_ulid(1), delta={"note": "runtime only"})]
        state = reduce(events)

        wp = state.work_packages["WP01"]
        assert wp["lane"] == str(Lane.UNINITIALIZED)
        # Non-display lanes never reach the board summary.
        assert sum(state.summary.values()) == 0
        # Annotations do not move the snapshot markers.
        assert state.last_event_id is None
        assert state.materialized_at == ""

    def test_replace_slots_fold_latest_wins(self) -> None:
        events = [
            _annotation(_ulid(1), delta={"role": "implementer", "model": "m1"}),
            _annotation(_ulid(2), delta={"role": "reviewer"}),
        ]
        state = reduce(events)
        wp = state.work_packages["WP01"]
        assert wp["role"] == "reviewer"
        assert wp["model"] == "m1"

    def test_blank_scalar_delta_is_a_no_op(self) -> None:
        events = [
            _annotation(_ulid(1), delta={"assignee": "avery"}),
            _annotation(_ulid(2), delta={"assignee": ""}),
        ]
        state = reduce(events)
        assert state.work_packages["WP01"]["assignee"] == "avery"

    def test_notes_append_and_subtasks_merge(self) -> None:
        events = [
            _annotation(_ulid(1), delta={"note": "one", "subtasks": {"T1": "done"}}),
            _annotation(_ulid(2), delta={"note": "two", "subtasks": {"T2": "done"}}),
        ]
        state = reduce(events)
        wp = state.work_packages["WP01"]
        assert wp["notes"] == ["one", "two"]
        assert wp["subtasks"] == {"T1": "done", "T2": "done"}

    def test_tracker_refs_union_vs_replace(self) -> None:
        events = [
            _annotation(_ulid(1), delta={"tracker_refs": ["A", "B"]}),
            _annotation(_ulid(2), delta={"tracker_refs": ["B", "C"]}),
            _annotation(_ulid(3), delta={"tracker_refs_replace": ["X", "X", "Y"]}),
        ]
        state = reduce(events)
        assert state.work_packages["WP01"]["tracker_refs"] == ["X", "Y"]

    def test_review_release_sentinel_clears_override(self) -> None:
        """The all-empty override is a deliberate clear, not a persisted {}."""
        events = [
            _annotation(
                _ulid(1),
                delta={
                    "review": {
                        "at": "2026-02-08T12:00:00+00:00",
                        "actor": "arbiter",
                        "wp_id": "WP01",
                        "reason": "override",
                    }
                },
            ),
            _annotation(
                _ulid(2),
                delta={"review": {"at": "", "actor": "", "wp_id": "", "reason": ""}},
            ),
        ]
        state = reduce(events)
        assert state.work_packages["WP01"]["review"] is None

    def test_partial_review_override_is_persisted(self) -> None:
        events = [
            _annotation(
                _ulid(1),
                delta={
                    "review": {
                        "at": "2026-02-08T12:00:00+00:00",
                        "actor": "arbiter",
                        "wp_id": "WP01",
                        "reason": "",
                    }
                },
            ),
        ]
        state = reduce(events)
        override = state.work_packages["WP01"]["review"]
        assert override == {
            "at": "2026-02-08T12:00:00+00:00",
            "actor": "arbiter",
            "wp_id": "WP01",
            "reason": "",
        }

    def test_claim_release_clears_only_the_claim_triple(self) -> None:
        events = [
            _row(
                _ulid(1),
                to_lane="claimed",
                policy_metadata={"shell_pid": 5, "agent": "claude"},
            ),
            _annotation(_ulid(2), delta={"assignee": "avery", "release_runtime_claim": True}),
        ]
        state = reduce(events)
        wp = state.work_packages["WP01"]
        assert wp["agent"] is None
        assert wp["shell_pid"] is None
        assert wp["shell_pid_created_at"] is None
        # Not part of the claim triple.
        assert wp["assignee"] == "avery"

    def test_annotations_never_move_force_count(self) -> None:
        events = [
            _row(_ulid(1), to_lane="claimed", force=True, reason="claim"),
            _annotation(_ulid(2), delta={"note": "x"}),
        ]
        state = reduce(events)
        assert state.work_packages["WP01"]["force_count"] == 1


class TestAnnotationSameTimestampTiebreak:
    def test_distinct_same_at_annotations_are_order_independent(self) -> None:
        """Two distinct annotations for one WP sharing `at` still fold deterministically.

        The annotation-pass sort key is ``(at, event_id)``, not ``at`` alone:
        without the ``event_id`` tiebreak, a stable sort would apply same-``at``
        annotations in *list* order, so the fold would depend on the order rows
        happen to appear in ``status.events.jsonl`` rather than on the events
        themselves (#2684 determinism).
        """
        first = _annotation(_ulid(1), delta={"role": "implementer"})
        second = _annotation(_ulid(2), delta={"role": "reviewer"})
        assert first["at"] == second["at"]  # concurrent: the tiebreak is what matters

        forward = reduce([first, second])
        reversed_order = reduce([second, first])

        assert forward.to_dict() == reversed_order.to_dict()
        assert forward.work_packages["WP01"]["role"] == "reviewer"

    def test_later_at_annotation_wins_even_with_earlier_event_id(self) -> None:
        """The annotation-pass sort key's `at` component is pinned, not just `event_id`.

        The key is ``(at, event_id)``: `at` is the primary component. Pick an
        `event_id` order that *disagrees* with the `at` order -- the earlier
        ULID carries the later timestamp -- so that dropping `at` from the key
        (sorting by `event_id` alone) would flip the fold winner. Sorting by
        `at` correctly, the later-timestamp annotation applies last and wins,
        regardless of which order the rows are given in (#166).
        """
        earlier_ulid_later_at = _annotation(
            _ulid(1), at="2026-02-08T13:00:00+00:00", delta={"role": "LATER-at"}
        )
        later_ulid_earlier_at = _annotation(
            _ulid(2),
            at="2026-02-08T12:00:00+00:00",
            delta={"role": "earlier-at-but-later-ulid"},
        )

        forward = reduce([earlier_ulid_later_at, later_ulid_earlier_at])
        reversed_order = reduce([later_ulid_earlier_at, earlier_ulid_later_at])

        assert forward.to_dict() == reversed_order.to_dict()
        assert forward.work_packages["WP01"]["role"] == "LATER-at"


# ── Mission slug safety ────────────────────────────────────────────────────────


class TestMissionSlugSafety:
    def test_traversal_slug_is_downgraded_to_empty(self) -> None:
        events = [_row(_ulid(1), mission_slug="../../../../tmp/evil")]
        state = reduce(events)
        assert state.mission_slug == ""
        # ...and the serialized payload stays canonical.
        assert state.to_dict()["mission_slug"] == ""

    def test_structured_actor_projects_to_string_identity(self) -> None:
        events = [
            _row(
                _ulid(1),
                actor={
                    "role": "implementer",
                    "profile": "p",
                    "tool": "claude",
                    "model": "opus",
                },
            )
        ]
        state = reduce(events)
        assert state.work_packages["WP01"]["actor"] == "claude"


# ── Row partition contract ─────────────────────────────────────────────────────


class TestRowPartition:
    def test_unknown_kind_fails_loud(self) -> None:
        row = _row(_ulid(1))
        row["kind"] = "mystery"
        with pytest.raises(DiaryError, match="Unknown event kind"):
            reduce([row])

    def test_unknown_event_type_rows_are_ignored(self) -> None:
        """Non-status writers sharing the file are ignored, not fatal (#41)."""
        noise = [
            {
                "event_type": "DecisionPointOpened",
                "event_id": "dp-1",
                "payload": {"question": "ship?"},
            },
            {"event_type": "SomeFutureProtocolEventV99", "event_id": "fut-1"},
            {
                "event_name": "retrospective.requested",
                "at": "2026-02-08T12:00:00+00:00",
                "event_id": "retro-1",
                "payload": {},
            },
        ]
        state = reduce([*noise, _row(_ulid(1))])
        assert state.event_count == 1

    @pytest.mark.parametrize(
        "event_type",
        ["RetrospectiveCaptured", "RetrospectiveCaptureFailed", "RetrospectiveSkipped"],
    )
    def test_type_envelope_retrospective_lifecycle_rows_are_ignored(self, event_type: str) -> None:
        """The CLI's top-level `type` retrospective lifecycle rows are non-lane (#41/#42)."""
        lifecycle_row = {
            "type": event_type,
            "event_id": "01KS049J4V9CSWBKJHTY2FB014",
            "mission_slug": "demo",
            "wp_id": None,
            "at": "2026-05-01T10:25:00+00:00",
        }
        state = reduce([lifecycle_row, _row(_ulid(1))])
        assert state.event_count == 1

    def test_malformed_lane_row_fails_loud(self) -> None:
        with pytest.raises(DiaryError, match="Invalid lane-transition row"):
            reduce([{"wp_id": "WP01"}])  # missing every required key

    def test_non_object_row_fails_loud(self) -> None:
        with pytest.raises(DiaryError, match="expected JSON object"):
            reduce(["not a dict"])  # type: ignore[list-item]

    def test_parse_diary_partitions_by_kind(self) -> None:
        rows = [
            _row(_ulid(1)),
            _annotation(_ulid(2), delta={"note": "n"}),
            {"event_type": "DecisionPointResolved", "event_id": "dp-2"},
        ]
        stream = parse_diary(rows)
        assert isinstance(stream, EventStream)
        assert [e.event_id for e in stream.transitions] == [_ulid(1)]
        assert [a.event_id for a in stream.annotations] == [_ulid(2)]

    def test_annotation_requires_valid_ulid(self) -> None:
        row = _annotation("not-a-ulid")
        with pytest.raises(DiaryError, match="not a valid ULID"):
            parse_diary([row])


# ── Typed fold parity + serialization ──────────────────────────────────────────


class TestTypedFoldParity:
    def test_reduce_parsed_matches_reduce(self) -> None:
        rows = [_row(_ulid(1)), _annotation(_ulid(2), delta={"note": "n"})]
        via_dicts = reduce(rows)
        stream = parse_diary(rows)
        via_typed = reduce_parsed(stream.transitions, stream.annotations)
        assert via_dicts.to_dict() == via_typed.to_dict()

    def test_to_dict_round_trip(self) -> None:
        state = reduce(
            [
                _row(_ulid(1), mission_slug="041-reducer-unification"),
            ]
        )
        restored = State.from_dict(state.to_dict())
        assert restored.to_dict() == state.to_dict()

    def test_to_dict_is_byte_stable(self) -> None:
        rows = [_row(_ulid(1)), _annotation(_ulid(2), delta={"note": "n"})]
        a = reduce(list(rows))
        b = reduce(list(reversed(rows)))
        doc_a = json.dumps(a.to_dict(), sort_keys=True, indent=2, ensure_ascii=False) + "\n"
        doc_b = json.dumps(b.to_dict(), sort_keys=True, indent=2, ensure_ascii=False) + "\n"
        assert doc_a == doc_b


# ── Facade re-export ───────────────────────────────────────────────────────────


def test_status_facade_exposes_the_shared_entry_point() -> None:
    """``spec_kitty_events.status.reduce`` IS the diary reducer (issue #41 API)."""
    assert status_module.reduce is reduce
    assert status_module.State is State
    assert status_module.parse_diary is parse_diary
    assert status_module.DiaryError is DiaryError


# ── Packaged golden replays ────────────────────────────────────────────────────


_GOLDEN_REPLAYS = (
    "status-diary-replay-fresh-mission",
    "status-diary-replay-every-lane",
    "status-diary-replay-out-of-order-duplicates",
    "status-diary-replay-unknown-kinds",
    "status-diary-replay-seed-payload-fold",
)


@pytest.mark.parametrize("fixture_id", _GOLDEN_REPLAYS)
def test_golden_replay(fixture_id: str) -> None:
    """reduce() reproduces the pinned golden output for each packaged stream."""
    rows = load_replay_stream(fixture_id)
    expected = load_reducer_output(f"{fixture_id}-output")
    assert reduce(rows).to_dict() == expected


@pytest.mark.parametrize("fixture_id", _GOLDEN_REPLAYS)
def test_golden_replay_is_permutation_invariant(fixture_id: str) -> None:
    """Any permutation of the same rows reduces to the same state."""
    import random

    rows = load_replay_stream(fixture_id)
    baseline = reduce(list(rows)).to_dict()
    shuffled = list(rows)
    random.Random(41).shuffle(shuffled)
    assert reduce(shuffled).to_dict() == baseline


def test_every_golden_stream_has_a_registered_pair() -> None:
    """Each replay id has its reducer_output counterpart in the manifest."""
    for fixture_id in _GOLDEN_REPLAYS:
        rows = load_replay_stream(fixture_id)
        output = load_reducer_output(f"{fixture_id}-output")
        assert rows and output


def test_status_event_round_trips_through_the_row_shape() -> None:
    """StatusEvent.to_dict rows re-parse losslessly (wire-format stability)."""
    event = StatusEvent.from_dict(_row(_ulid(9), to_lane="done", evidence=_DONE_EVIDENCE))
    reparsed = StatusEvent.from_dict(event.to_dict())
    assert reparsed == event


def test_inner_state_changed_round_trips_through_the_row_shape() -> None:
    record = InnerStateChanged.from_dict(_annotation(_ulid(8), delta={"note": "n"}))
    reparsed = InnerStateChanged.from_dict(record.to_dict())
    assert reparsed == record


# ── Reconstruction-seed payload fold (spec-kitty#6058, contract F1-F8) ─────────
#
# A ``migration:`` reconstruction seed that loses the lane contest on from_lane
# continuity (arm ii of ``_should_apply_event``) still carries payload the
# migration was minted to preserve: the claim slots and the backfilled verdict.
# It never becomes the setter, and a real event always beats it, slot by slot.

_SEED = "migration:backfill_runtime_state"
_VERDICT_SEED = "migration:verdict_provenance_backfill"
_CLAIM_SLOTS = ("agent", "shell_pid", "shell_pid_created_at")
_SETTER_SLOTS = ("lane", "actor", "last_event_id", "last_transition_at", "force_count")
_ABSENT = "<absent>"


def _late_seed_id(n: int) -> str:
    """A seed ULID sorting AFTER every ``_ulid`` id at the same ``at``."""
    return "01JZZZZZ" + "0" * 14 + f"{n:04d}"


def _early_seed_id(n: int) -> str:
    """A seed ULID sorting BEFORE every ``_ulid`` id at the same ``at``."""
    return "01JAAAAA" + "0" * 14 + f"{n:04d}"


def _t(sec: int) -> str:
    return f"2026-02-08T12:00:{sec:02d}+00:00"


def _verdict(verdict: str, reviewer: str = "reviewer-1") -> dict[str, Any]:
    return {
        "reviewer": reviewer,
        "verdict": verdict,
        "reference": f"feedback://{reviewer}/{verdict}",
    }


def _wp(rows: list[dict[str, Any]], wp_id: str = "WP01") -> dict[str, Any]:
    return reduce(rows).work_packages[wp_id]


def _slots(wp: dict[str, Any]) -> dict[str, Any]:
    """The setter, claim and verdict slots of one WP (absent slots are explicit)."""
    view = {k: wp.get(k, _ABSENT) for k in (*_SETTER_SLOTS, *_CLAIM_SLOTS)}
    result = wp.get("review_result", _ABSENT)
    view["review_result"] = result["verdict"] if isinstance(result, dict) else result
    return view


_TRIPLE = {"agent": "legacy-agent", "shell_pid": 4242, "shell_pid_created_at": "1700000000.5"}


def _claim(
    eid: str, at: str, actor: str = "claude", meta: dict[str, Any] | None = None
) -> dict[str, Any]:
    return _row(eid, at=at, actor=actor, policy_metadata=meta)


def _seed_claim(eid: str, at: str, meta: dict[str, Any] | None = None) -> dict[str, Any]:
    return _row(eid, at=at, actor=_SEED, policy_metadata=meta if meta is not None else _TRIPLE)


def _to_in_progress(eid: str, at: str, actor: str = "claude") -> dict[str, Any]:
    return _row(eid, from_lane="claimed", to_lane="in_progress", at=at, actor=actor)


def _cycle_to_rejection() -> list[dict[str, Any]]:
    """claim(claude, pid 100) -> in_progress -> for_review -> in_review -> rejected."""
    return [
        _claim(_ulid(1), _t(1), meta={"agent": "claude", "shell_pid": 100}),
        _to_in_progress(_ulid(2), _t(2)),
        _row(_ulid(3), from_lane="in_progress", to_lane="for_review", at=_t(3)),
        _row(_ulid(4), from_lane="for_review", to_lane="in_review", at=_t(4), actor="rev"),
        _row(
            _ulid(5),
            from_lane="in_review",
            to_lane="planned",
            at=_t(5),
            actor="rev",
            review_result=_verdict("changes_requested"),
        ),
    ]


class TestSeedPayloadFold:
    """A continuity-losing seed folds the payload no real event has written."""

    # -- US1: the seed's claim slots -------------------------------------------

    def test_losing_claim_seed_fills_the_triple_and_leaves_the_setter(self) -> None:
        """sc.1: real claim without a sidecar + a same-``at`` seed sorting after it."""
        wp = _wp([_claim(_ulid(1), _t(1)), _seed_claim(_late_seed_id(2), _t(1))])
        assert wp["lane"] == "claimed"
        assert wp["last_event_id"] == _ulid(1)
        assert wp["actor"] == "claude"
        assert {k: wp[k] for k in _CLAIM_SLOTS} == _TRIPLE

    def test_seed_triple_survives_the_next_real_transition(self) -> None:
        """sc.2: the same, followed by a real ``claimed -> in_progress``."""
        wp = _wp(
            [
                _claim(_ulid(1), _t(1)),
                _seed_claim(_late_seed_id(2), _t(1)),
                _to_in_progress(_ulid(3), _t(2)),
            ]
        )
        assert wp["lane"] == "in_progress"
        assert wp["last_event_id"] == _ulid(3)
        assert {k: wp[k] for k in _CLAIM_SLOTS} == _TRIPLE

    def test_seed_position_does_not_change_the_slots(self) -> None:
        """NFR-004: the seed sorting before the real claim gives the same slots."""
        real = _claim(_ulid(1), _t(1))
        after = _slots(_wp([real, _seed_claim(_late_seed_id(2), _t(1))]))
        before = _slots(_wp([real, _seed_claim(_early_seed_id(2), _t(1))]))
        assert after == before
        assert after["shell_pid"] == 4242

    def test_complement_seeds_only_the_slots_the_real_claim_left_missing(self) -> None:
        """CLI backfill shape: real claim with ``agent`` only + a pid/baseline seed."""
        wp = _wp(
            [
                _claim(_ulid(1), _t(1), meta={"agent": "claude"}),
                _seed_claim(_late_seed_id(2), _t(1)),
            ]
        )
        assert wp["agent"] == "claude"
        assert wp["shell_pid"] == 4242
        assert wp["shell_pid_created_at"] == "1700000000.5"

    def test_seed_attribution_survives_a_later_cancel(self) -> None:
        """B6: 10.4.0 parity -- a canceled WP keeps the seed's attribution."""
        wp = _wp(
            [
                _claim(_ulid(1), _t(1)),
                _seed_claim(_late_seed_id(2), _t(1)),
                _row(_ulid(3), from_lane="claimed", to_lane="canceled", at=_t(2), actor="lead"),
            ]
        )
        assert wp["lane"] == "canceled"
        assert wp["agent"] == "legacy-agent"

    @pytest.mark.parametrize(("earlier", "later"), [("seed-a", "seed-b"), ("seed-b", "seed-a")])
    def test_seed_versus_seed_the_later_by_at_and_id_wins(self, earlier: str, later: str) -> None:
        """FR-003: two losing seeds with different ids resolve by (at, event_id)."""
        rows = [
            _claim(_ulid(1), _t(1)),
            _seed_claim(_late_seed_id(2), _t(1), {"agent": earlier, "shell_pid": 11}),
            _seed_claim(_late_seed_id(3), _t(1), {"agent": later, "shell_pid": 22}),
        ]
        wp = _wp(rows)
        assert (wp["agent"], wp["shell_pid"]) == (later, 22)

    # -- US1: the seed's verdict -----------------------------------------------

    def test_losing_verdict_seed_after_a_merge_keeps_the_verdict(self) -> None:
        """sc.3: ``approved -> done`` with no real verdict, then a verdict seed."""
        evidence = _DONE_EVIDENCE
        wp = _wp(
            [
                _claim(_ulid(1), _t(1)),
                _to_in_progress(_ulid(2), _t(2)),
                _row(
                    _ulid(3),
                    from_lane="in_progress",
                    to_lane="approved",
                    at=_t(3),
                    actor="lead",
                    force=True,
                    reason="legacy",
                    evidence=evidence,
                ),
                _row(
                    _ulid(4),
                    from_lane="approved",
                    to_lane="done",
                    at=_t(4),
                    actor="merge",
                    evidence=evidence,
                ),
                _row(
                    _late_seed_id(5),
                    from_lane="in_review",
                    to_lane="approved",
                    at=_t(5),
                    actor=_VERDICT_SEED,
                    review_result=_verdict("approved", "old-reviewer"),
                ),
            ]
        )
        assert wp["lane"] == "done"
        assert wp["last_event_id"] == _ulid(4)
        assert wp["actor"] == "merge"
        assert wp["review_result"]["verdict"] == "approved"

    def test_losing_verdict_seed_never_overwrites_a_real_rejection(self) -> None:
        """B3: a real ``changes_requested`` stands against a later approved seed."""
        wp = _wp(
            _cycle_to_rejection()
            + [
                _row(
                    _late_seed_id(9),
                    from_lane="in_review",
                    to_lane="approved",
                    at=_t(7),
                    actor=_VERDICT_SEED,
                    review_result=_verdict("approved", "old-reviewer"),
                )
            ]
        )
        assert wp["lane"] == "planned"
        assert wp["review_result"]["verdict"] == "changes_requested"

    def test_rejected_verdict_seed_does_not_become_approval_proof(self) -> None:
        """B3b: the stale seed verdict cannot ride a later force-approve."""
        rows = _cycle_to_rejection() + [
            _row(
                _late_seed_id(9),
                from_lane="in_review",
                to_lane="approved",
                at=_t(7),
                actor=_VERDICT_SEED,
                review_result=_verdict("approved", "old-reviewer"),
            ),
            _claim(_ulid(10), _t(8), actor="codex"),
            _to_in_progress(_ulid(11), _t(9), actor="codex"),
            _row(
                _ulid(12),
                from_lane="in_progress",
                to_lane="approved",
                at=_t(10),
                actor="lead",
                force=True,
                reason="ship",
                evidence=_DONE_EVIDENCE,
            ),
        ]
        wp = _wp(rows)
        assert wp["lane"] == "approved"
        assert wp["review_result"]["reviewer"] != "old-reviewer"

    def test_losing_verdict_seed_does_not_refill_a_verdictless_real_exit(self) -> None:
        """B4: a real verdict-less ``in_review`` exit leaves an explicit None."""
        rows = _cycle_to_rejection()[:4] + [
            _row(
                _ulid(5),
                from_lane="in_review",
                to_lane="in_progress",
                at=_t(5),
                actor="lead",
                force=True,
                reason="takeback",
            ),
            _row(
                _late_seed_id(9),
                from_lane="in_review",
                to_lane="approved",
                at=_t(6),
                actor=_VERDICT_SEED,
                review_result=_verdict("approved", "old-reviewer"),
            ),
        ]
        wp = _wp(rows)
        assert wp["lane"] == "in_progress"
        assert wp["review_result"] is None

    def test_losing_seed_without_a_verdict_does_not_clear_one(self) -> None:
        """sc.7 (ratchet): a verdict-less losing seed leaves the verdict in place."""
        wp = _wp(
            _cycle_to_rejection()
            + [
                _row(
                    _late_seed_id(9),
                    from_lane="claimed",
                    to_lane="in_progress",
                    at=_t(7),
                    actor=_SEED,
                )
            ]
        )
        assert wp["lane"] == "planned"
        assert wp["review_result"]["verdict"] == "changes_requested"

    # -- FR-003: a real event always beats a seed, slot by slot -----------------

    @pytest.mark.parametrize(
        "seed_id", [_late_seed_id(7), _early_seed_id(7)], ids=["late", "early"]
    )
    def test_real_reclaim_beats_a_conflicting_seed_in_either_order(self, seed_id: str) -> None:
        """B1: the live claimant's values stand whichever side the seed sorts."""
        rows = _cycle_to_rejection() + [
            _claim(_ulid(6), _t(6), actor="codex", meta={"agent": "codex", "shell_pid": 222}),
            _seed_claim(seed_id, _t(6), {"agent": "claude", "shell_pid": 100}),
            _to_in_progress(_ulid(8), _t(8), actor="codex"),
        ]
        wp = _wp(rows)
        assert wp["lane"] == "in_progress"
        assert (wp["agent"], wp["shell_pid"]) == ("codex", 222)

    @pytest.mark.parametrize(
        "seed_id", [_late_seed_id(2), _early_seed_id(2)], ids=["late", "early"]
    )
    def test_real_pid_voids_a_seed_baseline(self, seed_id: str) -> None:
        """B2 / I3: a real pid is never paired with the seed's process-start time."""
        rows = [
            _claim(_ulid(1), _t(1)),
            _seed_claim(seed_id, _t(1)),
            _to_in_progress(_ulid(3), _t(2)),
            _row(_ulid(4), from_lane="in_progress", to_lane="for_review", at=_t(3)),
            _row(_ulid(5), from_lane="for_review", to_lane="in_review", at=_t(4), actor="rev"),
            _row(_ulid(6), from_lane="in_review", to_lane="planned", at=_t(5), actor="rev"),
            _claim(_ulid(7), _t(6), actor="codex", meta={"agent": "codex", "shell_pid": 222}),
        ]
        wp = _wp(rows)
        assert wp["shell_pid"] == 222
        assert wp.get("shell_pid_created_at") != "1700000000.5"

    def test_real_agent_only_claim_keeps_the_seed_pid_and_baseline(self) -> None:
        """A real claim writing only ``agent`` leaves the seed's pid and baseline."""
        rows = [
            _claim(_ulid(1), _t(1)),
            _seed_claim(_late_seed_id(2), _t(1)),
            _to_in_progress(_ulid(3), _t(2)),
            _row(_ulid(4), from_lane="in_progress", to_lane="for_review", at=_t(3)),
            _row(_ulid(5), from_lane="for_review", to_lane="in_review", at=_t(4), actor="rev"),
            _row(_ulid(6), from_lane="in_review", to_lane="planned", at=_t(5), actor="rev"),
            _claim(_ulid(7), _t(6), actor="codex", meta={"agent": "codex"}),
        ]
        wp = _wp(rows)
        assert wp["agent"] == "codex"
        assert (wp["shell_pid"], wp["shell_pid_created_at"]) == (4242, "1700000000.5")

    @pytest.mark.parametrize(
        "seed_id", [_late_seed_id(2), _early_seed_id(2)], ids=["late", "early"]
    )
    def test_real_pid_without_baseline_is_not_paired_with_a_seed_baseline(
        self, seed_id: str
    ) -> None:
        """I3, order-independent: a seed after a real pid never supplies the baseline."""
        real = _claim(_ulid(1), _t(1), meta={"agent": "claude", "shell_pid": 100})
        wp = _wp([real, _seed_claim(seed_id, _t(1))])
        assert (wp["agent"], wp["shell_pid"]) == ("claude", 100)
        assert "shell_pid_created_at" not in wp

    def test_real_pid_and_baseline_both_stand(self) -> None:
        """I3 only voids the SEED's baseline: a real pid with a real baseline keeps it."""
        meta = {"shell_pid": 7, "shell_pid_created_at": "1800000000.0"}
        wp = _wp([_claim(_ulid(1), _t(1), meta=meta), _seed_claim(_late_seed_id(2), _t(1))])
        assert (wp["shell_pid"], wp["shell_pid_created_at"]) == (7, "1800000000.0")

    # -- FR-003 / F4: arm (i) losers fold nothing -------------------------------

    def test_seed_losing_to_a_same_at_rollback_contributes_nothing(self) -> None:
        """B7 / sc.6: arm (i) -- the rollback keeps the WP unclaimed."""
        rows = _cycle_to_rejection() + [
            _seed_claim(_late_seed_id(9), _t(5), {"agent": "ghost", "shell_pid": 999})
        ]
        without = _wp(_cycle_to_rejection())
        wp = _wp(rows)
        assert wp["lane"] == "planned"
        assert _slots(wp) == _slots(without)
        assert wp["agent"] == "claude"

    def test_arm_i_loser_folds_nothing_even_when_no_real_claim_wrote_a_slot(self) -> None:
        """B7b / F4: with no real sidecar, only the arm-(i) guard keeps the seed out.

        The real claim carries no ``policy_metadata``, so per-slot provenance
        cannot mask a wrongly folded loser: the ghost triple must not appear.
        """
        base = _cycle_to_rejection()
        base[0] = _claim(_ulid(1), _t(1))  # no sidecar
        ghost = {"agent": "ghost", "shell_pid": 999, "shell_pid_created_at": "1.0"}
        wp = _wp(base + [_seed_claim(_late_seed_id(9), _t(5), ghost)])
        without = _wp(base)
        assert wp["lane"] == "planned"
        assert _slots(wp) == _slots(without)
        for slot in _CLAIM_SLOTS:
            assert slot not in wp

    # -- FR-002 / F6 / F7: nothing else moves ----------------------------------

    def test_stale_real_claim_after_a_cancel_contributes_nothing(self) -> None:
        """US2 sc.1 (ratchet): a dropped REAL event never folds a sidecar or verdict."""
        rows = [
            _claim(_ulid(1), _t(1), meta={"agent": "claude", "shell_pid": 100}),
            _to_in_progress(_ulid(2), _t(2)),
            _row(_ulid(3), from_lane="in_progress", to_lane="canceled", at=_t(3), actor="lead"),
            _claim(_ulid(4), _t(4), actor="ghost", meta={"agent": "ghost", "shell_pid": 999}),
            _row(
                _ulid(5),
                from_lane="in_review",
                to_lane="approved",
                at=_t(5),
                actor="ghost",
                review_result=_verdict("approved"),
            ),
        ]
        wp = _wp(rows)
        assert wp["lane"] == "canceled"
        assert (wp["agent"], wp["shell_pid"]) == ("claude", 100)
        assert wp.get("review_result", None) is None

    def test_applied_seed_behaves_as_in_10_4_2(self) -> None:
        """F6 (ratchet): a seed that wins the lane is the setter and writes its payload."""
        wp = _wp([_seed_claim(_ulid(1), _t(1))])
        assert wp["lane"] == "claimed"
        assert wp["actor"] == _SEED
        assert wp["last_event_id"] == _ulid(1)
        assert {k: wp[k] for k in _CLAIM_SLOTS} == _TRIPLE

    @pytest.mark.parametrize(
        ("meta", "expected"),
        [
            ({"shell_pid": 0}, {"shell_pid": 0}),
            ({"shell_pid": -1}, {"shell_pid": -1}),
            ({"shell_pid_created_at": 0}, {"shell_pid_created_at": 0}),
            ({"agent": ""}, {}),
        ],
        ids=["pid0", "pid-1", "baseline0", "agent-empty"],
    )
    def test_applied_claim_acceptance_is_pinned(
        self, meta: dict[str, Any], expected: dict[str, Any]
    ) -> None:
        """F6 / I5: a real planned -> claimed lands pid 0 / -1 / baseline 0 as written.

        Hand-picked values (not the B10 differential), so tightening the shared
        helper to ``pid <= 0`` is caught: an empty ``agent`` is the one no-op.
        """
        wp = _wp([_claim(_ulid(1), _t(1), meta=meta)])
        assert {k: wp[k] for k in _CLAIM_SLOTS if k in wp} == expected

    def test_real_baseline_survives_a_real_reclaim_without_one(self) -> None:
        """F7 / FR-002 (ratchet): the I3 void touches only SEED baselines.

        A seedless log reduces as in 10.4.2: a real start time carries forward
        through a real re-claim that writes a pid but no start time.
        """
        rows = [
            _claim(
                _ulid(1),
                _t(1),
                actor="a",
                meta={"agent": "a", "shell_pid": 100, "shell_pid_created_at": "1700.0"},
            ),
            _row(_ulid(2), from_lane="claimed", to_lane="planned", at=_t(2)),
            _claim(_ulid(3), _t(3), actor="b", meta={"agent": "b", "shell_pid": 222}),
        ]
        wp = _wp(rows)
        assert (wp["agent"], wp["shell_pid"]) == ("b", 222)
        assert wp["shell_pid_created_at"] == "1700.0"

    def test_empty_losing_seed_is_a_no_op(self) -> None:
        """A losing seed with no sidecar and no verdict changes nothing."""
        real = [_claim(_ulid(1), _t(1), meta={"agent": "claude"})]
        seed = _row(_late_seed_id(2), at=_t(1), actor=_SEED)
        assert reduce(real + [seed]).work_packages == reduce(real).work_packages

    def test_losing_seed_never_touches_the_setter_slots(self) -> None:
        """I1: lane, actor, last_event_id, last_transition_at, force_count are kept."""
        real = [_claim(_ulid(1), _t(1))]
        wp = _wp(real + [_seed_claim(_late_seed_id(2), _t(1))])
        ref = _wp(real)
        assert {k: wp[k] for k in _SETTER_SLOTS} == {k: ref[k] for k in _SETTER_SLOTS}

    # -- I5: acceptance equals the applied claim path's -------------------------

    @pytest.mark.parametrize(
        "meta",
        [
            {"shell_pid": 0},
            {"shell_pid": -1},
            {"shell_pid": "x"},
            {"shell_pid": None},
            {"shell_pid": True},
            {"agent": ""},
            {"agent": None},
            {"agent": "  "},
            {"shell_pid_created_at": 0},
            {"shell_pid_created_at": "not-a-time"},
        ],
        ids=[
            "pid0",
            "pid-1",
            "pid-str",
            "pid-none",
            "pid-bool",
            "agent-empty",
            "agent-none",
            "agent-blank",
            "baseline0",
            "baseline-str",
        ],
    )
    def test_losing_seed_acceptance_equals_the_applied_path(self, meta: dict[str, Any]) -> None:
        """B10: the slots a losing seed folds equal those of the same seed applied."""
        applied = _wp([_seed_claim(_ulid(1), _t(1), meta)])
        losing = _wp([_claim(_ulid(2), _t(1)), _seed_claim(_late_seed_id(3), _t(1), meta)])
        assert {k: losing.get(k, _ABSENT) for k in _CLAIM_SLOTS} == {
            k: applied.get(k, _ABSENT) for k in _CLAIM_SLOTS
        }

    @pytest.mark.parametrize(
        "meta",
        [{"shell_pid": 0}, {"shell_pid": "x"}, {"agent": ""}],
        ids=["pid0", "pid-str", "agent-empty"],
    )
    def test_real_sidecar_stands_against_an_odd_losing_seed(self, meta: dict[str, Any]) -> None:
        """B10: where a real sidecar precedes the seed, the real value stands."""
        real = _claim(_ulid(1), _t(1), meta={"agent": "claude", "shell_pid": 100})
        wp = _wp([real, _seed_claim(_late_seed_id(2), _t(1), meta)])
        assert (wp["agent"], wp["shell_pid"]) == ("claude", 100)

    # -- NFR-004: determinism ---------------------------------------------------

    def test_duplicate_seed_ids_resolve_by_content_not_file_order(self) -> None:
        """B8 / I6: two seeds sharing an event id give one result in both file orders."""
        base = [_claim(_ulid(1), _t(1)), _to_in_progress(_ulid(3), _t(2))]
        s1 = _seed_claim(_late_seed_id(2), _t(1), {"agent": "claude", "shell_pid": 100})
        s2 = _seed_claim(_late_seed_id(2), _t(1), {"agent": "codex", "shell_pid": 222})
        one = reduce(base + [s1, s2]).work_packages
        two = reduce(base + [s2, s1]).work_packages
        assert one == two
        # The canonical (sorted-JSON) minimum is the claude row.
        assert one["WP01"]["agent"] == "claude"

    def test_duplicate_real_ids_still_keep_the_first(self) -> None:
        """Real-event dedup is unchanged: the first occurrence wins."""
        first = _claim(_ulid(1), _t(1), meta={"agent": "first"})
        second = _claim(_ulid(1), _t(1), meta={"agent": "second"})
        assert _wp([first, second])["agent"] == "first"
        assert _wp([second, first])["agent"] == "second"

    def test_shuffled_order_gives_an_identical_snapshot(self) -> None:
        """B9: 200 shuffles of a mixed real + seed log reduce identically."""
        import random

        rows = _cycle_to_rejection() + [
            _claim(_ulid(6), _t(6), actor="codex", meta={"agent": "codex"}),
            _seed_claim(_late_seed_id(7), _t(6), {"agent": "claude", "shell_pid": 100}),
            _row(
                _late_seed_id(8),
                from_lane="in_review",
                to_lane="approved",
                at=_t(4),
                actor=_VERDICT_SEED,
                review_result=_verdict("approved"),
            ),
        ]
        ref = reduce(rows).to_dict()
        rng = random.Random(6058)
        for _ in range(200):
            shuffled = rows[:]
            rng.shuffle(shuffled)
            assert reduce(shuffled).to_dict() == ref

    # B5 (a rollback-shaped verdict seed, ``in_review -> in_progress`` with a
    # ``changes_requested`` verdict, still applies and sets the lane through the
    # rollback exemption) is out of scope here: see spec-kitty/spec-kitty#6061.
