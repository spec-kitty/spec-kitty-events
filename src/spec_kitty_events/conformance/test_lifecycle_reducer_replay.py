"""Packaged reducer-golden runner for the ``lifecycle`` replay fixture (#119).

``test_pyargs_entrypoint.py`` excludes ``replay_stream`` / ``reducer_output``
manifest entries from its generic event gate, so a reducer golden is only
collected by ``pytest --pyargs spec_kitty_events.conformance`` when a module
like this one drives it (cf. ``test_status_diary_reducer_replay.py``).

This module folds the committed mission lifecycle stream through
:func:`~spec_kitty_events.lifecycle.reduce_lifecycle_events` and compares the
result with the packaged golden. It also pins two reducer guarantees on the
same stream: permutation invariance, and that an event of an unknown type is
ignored (it is counted in ``event_count`` as a processed event but changes
no other field).
"""

from __future__ import annotations

import random
from typing import Any, Dict, List

from spec_kitty_events.conformance.loader import load_reducer_output, load_replay_stream
from spec_kitty_events.lifecycle import reduce_lifecycle_events
from spec_kitty_events.models import Event

_STREAM_ID = "lifecycle-replay-mission-lifecycle"
_UNKNOWN_TYPE = "ExperimentalUnknownEvent"


def _fold(rows: List[Dict[str, Any]]) -> Dict[str, Any]:
    return reduce_lifecycle_events([Event(**row) for row in rows]).model_dump(mode="json")


def test_golden_replay() -> None:
    """The reducer reproduces the pinned golden for the lifecycle stream."""
    rows = load_replay_stream(_STREAM_ID)
    assert _fold(rows) == load_reducer_output(f"{_STREAM_ID}-output")


def test_stream_carries_the_duplicate_and_the_unknown_type() -> None:
    """Guard the stream's reason to exist: one duplicated id, one unknown type."""
    rows = load_replay_stream(_STREAM_ID)
    ids = [row["event_id"] for row in rows]
    assert len(ids) - len(set(ids)) == 1
    assert sum(row["event_type"] == _UNKNOWN_TYPE for row in rows) == 1


def test_golden_replay_is_permutation_invariant() -> None:
    """Any permutation of the same rows reduces to the same state."""
    rows = load_replay_stream(_STREAM_ID)
    baseline = _fold(list(rows))
    for seed in (41, 42, 43):
        shuffled = list(rows)
        random.Random(seed).shuffle(shuffled)
        assert _fold(shuffled) == baseline


def test_unknown_event_type_is_ignored_by_the_fold() -> None:
    """Removing the unknown-type row leaves every field unchanged except the
    processed-event count, which drops by exactly that one event."""
    rows = load_replay_stream(_STREAM_ID)
    with_unknown = _fold(rows)
    without = _fold([row for row in rows if row["event_type"] != _UNKNOWN_TYPE])
    assert without["event_count"] == with_unknown["event_count"] - 1
    with_unknown.pop("event_count")
    without.pop("event_count")
    assert without == with_unknown
