"""validate_transition admits exactly core's unforced edge graph (D4, #231 4.6)."""

from __future__ import annotations

from typing import FrozenSet, Optional, Tuple

import pytest

from spec_kitty_events.status import (
    DoneEvidence,
    ExecutionMode,
    Lane,
    RepoEvidence,
    ReviewVerdict,
    StatusTransitionPayload,
    validate_transition,
)

# Copied from spec-kitty `src/specify_cli/status/wp_state.py`, `allowed_targets()` per
# state, at pin 512567eee71e31bb39f580c4f7acf8b865e9d1b2. Core's write service is the
# write authority; done and canceled are terminal, uninitialized has no edges.
CORE_EDGES: FrozenSet[Tuple[Lane, Lane]] = frozenset(
    {
        (Lane.GENESIS, Lane.PLANNED),
        (Lane.GENESIS, Lane.CANCELED),
        (Lane.PLANNED, Lane.CLAIMED),
        (Lane.PLANNED, Lane.BLOCKED),
        (Lane.PLANNED, Lane.CANCELED),
        (Lane.CLAIMED, Lane.IN_PROGRESS),
        (Lane.CLAIMED, Lane.BLOCKED),
        (Lane.CLAIMED, Lane.CANCELED),
        (Lane.IN_PROGRESS, Lane.FOR_REVIEW),
        (Lane.IN_PROGRESS, Lane.APPROVED),
        (Lane.IN_PROGRESS, Lane.PLANNED),
        (Lane.IN_PROGRESS, Lane.BLOCKED),
        (Lane.IN_PROGRESS, Lane.CANCELED),
        (Lane.FOR_REVIEW, Lane.IN_REVIEW),
        (Lane.FOR_REVIEW, Lane.BLOCKED),
        (Lane.FOR_REVIEW, Lane.CANCELED),
        (Lane.IN_REVIEW, Lane.APPROVED),
        (Lane.IN_REVIEW, Lane.DONE),
        (Lane.IN_REVIEW, Lane.IN_PROGRESS),
        (Lane.IN_REVIEW, Lane.PLANNED),
        (Lane.IN_REVIEW, Lane.BLOCKED),
        (Lane.IN_REVIEW, Lane.CANCELED),
        (Lane.APPROVED, Lane.DONE),
        (Lane.APPROVED, Lane.IN_PROGRESS),
        (Lane.APPROVED, Lane.PLANNED),
        (Lane.APPROVED, Lane.BLOCKED),
        (Lane.APPROVED, Lane.CANCELED),
        (Lane.BLOCKED, Lane.IN_PROGRESS),
        (Lane.BLOCKED, Lane.CANCELED),
    }
)
PACKAGE_EDGES: FrozenSet[Tuple[Optional[Lane], Lane]] = CORE_EDGES | {(None, Lane.PLANNED)}

REMOVED_EDGES = [
    (Lane.FOR_REVIEW, Lane.APPROVED),
    (Lane.FOR_REVIEW, Lane.DONE),
    (Lane.FOR_REVIEW, Lane.IN_PROGRESS),
    (Lane.FOR_REVIEW, Lane.PLANNED),
    (Lane.IN_REVIEW, Lane.FOR_REVIEW),
    (Lane.GENESIS, Lane.BLOCKED),
    (Lane.BLOCKED, Lane.BLOCKED),
]

_PAIRS = [(f, t) for f in [None, *Lane] for t in Lane]


def _evidence() -> DoneEvidence:
    return DoneEvidence(
        repos=[RepoEvidence(repo="r", branch="main", commit="abc123")],
        verification=[],
        review=ReviewVerdict(reviewer="alice", verdict="approved"),
    )


def _payload(
    from_lane: Optional[Lane], to_lane: Lane, *, force: bool = False
) -> StatusTransitionPayload:
    """A payload that satisfies every guard, so only the edge check can object."""
    return StatusTransitionPayload(
        mission_slug="m",
        wp_id="WP01",
        from_lane=from_lane,
        to_lane=to_lane,
        actor="a",
        force=force,
        reason="because",
        execution_mode=ExecutionMode.WORKTREE,
        review_ref="feedback://WP01",
        evidence=_evidence(),
    )


def _edge_violations(payload: StatusTransitionPayload) -> list[str]:
    return [v for v in validate_transition(payload).violations if "is not allowed" in v]


@pytest.mark.parametrize(("from_lane", "to_lane"), _PAIRS)
def test_unforced_edge_iff_core_edge(from_lane: Optional[Lane], to_lane: Lane) -> None:
    has_edge_violation = bool(_edge_violations(_payload(from_lane, to_lane)))
    assert has_edge_violation is ((from_lane, to_lane) not in PACKAGE_EDGES)


@pytest.mark.parametrize(("from_lane", "to_lane"), REMOVED_EDGES)
def test_removed_edge_message_and_forced_bypass(from_lane: Lane, to_lane: Lane) -> None:
    assert _edge_violations(_payload(from_lane, to_lane)) == [
        f"Transition {from_lane} -> {to_lane} is not allowed"
    ]
    assert _edge_violations(_payload(from_lane, to_lane, force=True)) == []


@pytest.mark.parametrize(("from_lane", "to_lane"), _PAIRS)
def test_force_never_reports_an_edge_violation(from_lane: Optional[Lane], to_lane: Lane) -> None:
    assert _edge_violations(_payload(from_lane, to_lane, force=True)) == []


def test_forced_for_review_to_in_progress_is_valid_with_review_ref_and_reason() -> None:
    assert validate_transition(_payload(Lane.FOR_REVIEW, Lane.IN_PROGRESS, force=True)).valid


def test_terminal_exit_still_requires_force() -> None:
    result = validate_transition(_payload(Lane.DONE, Lane.PLANNED))
    assert "done is terminal; requires force=True to exit" in result.violations
