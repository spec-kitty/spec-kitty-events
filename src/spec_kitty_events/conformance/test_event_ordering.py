"""Packaged runner for the ``event_ordering`` fixtures (#120, 11.0.0).

Each document lists envelopes (clocked and unclocked) and the ``event_id`` order
:func:`spec_kitty_events.ordering.canonical_order_key` must produce. The result
must be the same for every arrangement of the input, and a mixed stream must
never raise ``TypeError`` (``None`` is never compared with an ``int``).
"""

from __future__ import annotations

import itertools
from typing import Any, Dict, List

import pytest

from spec_kitty_events.conformance.loader import load_event_ordering_cases
from spec_kitty_events.models import Event
from spec_kitty_events.ordering import canonical_order_key, is_clocked

_CASES: List[Dict[str, Any]] = load_event_ordering_cases()


def test_event_ordering_category_is_populated() -> None:
    assert _CASES, "event_ordering fixtures vanished from the manifest"
    assert any(not is_clocked(Event(**e)) for c in _CASES for e in c["events"])
    assert any(is_clocked(Event(**e)) for c in _CASES for e in c["events"])


@pytest.mark.parametrize("case", _CASES, ids=[c["id"] for c in _CASES])
def test_event_ordering_fixture(case: Dict[str, Any]) -> None:
    events = [Event(**raw) for raw in case["events"]]
    assert sorted(case["expected_order"]) == sorted(e.event_id for e in events)
    for arrangement in itertools.permutations(events):
        ordered = sorted(arrangement, key=canonical_order_key)
        assert [e.event_id for e in ordered] == case["expected_order"]
