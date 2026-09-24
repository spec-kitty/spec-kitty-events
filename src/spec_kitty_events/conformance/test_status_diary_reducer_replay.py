"""Packaged reducer-golden runner for the ``status_diary`` replay fixtures.

``test_pyargs_entrypoint.py`` deliberately excludes ``replay_stream`` /
``reducer_output`` fixture_type entries from its generic event gate (they are
not raw envelope payloads to validate -- see that module's
``_event_fixture_entries`` docstring). Before this module existed, that
exclusion meant ``pytest --pyargs spec_kitty_events.conformance`` collected
NO reducer goldens at all: the packaged golden-replay coverage lived only in
``tests/test_status_diary_reducer.py``, which ``pyproject.toml``'s
``testpaths = ["tests"]`` never ships in the wheel (the same
spec-kitty-events#145 gap ``test_zeitgeist_attrs_codec.py`` closed for the
``zeitgeist_attrs`` category).

This module drives :func:`~spec_kitty_events.diary.reduce` through the
packaged golden pairs so a downstream consumer running the pyargs entrypoint
inherits the same reducer-precedence guarantees the in-repo suite pins --
including the #69 causally-concurrent-rejection-beats-approval fixture pair.
"""

from __future__ import annotations

import random

import pytest

from spec_kitty_events.conformance.loader import load_reducer_output, load_replay_stream
from spec_kitty_events.diary import reduce

# Every packaged status-diary replay_stream id, paired by convention with a
# like-named "<id>-output" reducer_output id. Mirrors
# ``tests/test_status_diary_reducer.py::_GOLDEN_REPLAYS``.
_GOLDEN_REPLAYS = (
    "status-diary-replay-fresh-mission",
    "status-diary-replay-every-lane",
    "status-diary-replay-out-of-order-duplicates",
    "status-diary-replay-unknown-kinds",
    "status-diary-replay-concurrent-reject-beats-approve",
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
    rows = load_replay_stream(fixture_id)
    baseline = reduce(list(rows)).to_dict()
    shuffled = list(rows)
    random.Random(41).shuffle(shuffled)
    assert reduce(shuffled).to_dict() == baseline
