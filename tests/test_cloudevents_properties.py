"""Properties of the upcaster over generated rows: deterministic, pure, idempotent."""

from __future__ import annotations

import copy
import time
from typing import Any, Dict

from hypothesis import given, settings
from hypothesis import strategies as st

from spec_kitty_events.cloudevents import UpcastContext, canonical_bytes, upcast
from spec_kitty_events.status import Lane

_CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
_ulids = st.text(alphabet=_CROCKFORD, min_size=26, max_size=26)
_lanes = st.sampled_from([lane.value for lane in Lane] + ["doing"])
_names = st.text(alphabet="abcdefghijklmnopqrstuvwxyz0123456789-", min_size=1, max_size=12)
_stamps = st.builds(
    "2026-{:02d}-{:02d}T{:02d}:{:02d}:{:02d}{}".format,
    st.integers(1, 12),
    st.integers(1, 28),
    st.integers(0, 23),
    st.integers(0, 59),
    st.integers(0, 59),
    st.sampled_from(["Z", "+00:00", ".123456+02:00"]),
)
_actors = st.one_of(
    _names,
    st.fixed_dictionaries({"role": _names}, optional={"tool": _names, "profile": _names}),
)
_json_leaf = st.one_of(st.none(), st.booleans(), st.integers(-(2**40), 2**40), _names)
_extra = st.dictionaries(_names, _json_leaf, max_size=3)


@st.composite
def _diary_rows(draw: Any) -> Dict[str, Any]:
    row: Dict[str, Any] = {
        "event_id": draw(_ulids),
        "mission_slug": draw(_names),
        "wp_id": "WP" + draw(st.text(alphabet="0123456789", min_size=2, max_size=2)),
        "from_lane": draw(st.one_of(st.none(), _lanes)),
        "to_lane": draw(_lanes),
        "at": draw(_stamps),
        "actor": draw(_actors),
        "force": draw(st.booleans()),
        "execution_mode": draw(st.sampled_from(["worktree", "main"])),
        "reason": draw(st.one_of(st.none(), _names)),
        "review_ref": None,
        "evidence": None,
    }
    if draw(st.booleans()):
        row["policy_metadata"] = draw(_extra)
    return row


@st.composite
def _transport_rows(draw: Any) -> Dict[str, Any]:
    mission = draw(_ulids)
    wp = "WP" + draw(st.text(alphabet="0123456789", min_size=2, max_size=2))
    return {
        "event_id": draw(_ulids),
        "event_type": "WPStatusChanged",
        "aggregate_id": draw(st.sampled_from([f"mission/{mission}/wp/{wp}", wp])),
        "payload": {
            "mission_slug": draw(_names),
            "mission_id": draw(st.sampled_from([mission, None])),
            "wp_id": wp,
            "from_lane": draw(st.one_of(st.none(), _lanes)),
            "to_lane": draw(_lanes),
            "actor": draw(_actors),
            "force": False,
            "execution_mode": "worktree",
        },
        "timestamp": draw(_stamps),
        "build_id": draw(_names),
        "node_id": draw(_names),
        "lamport_clock": draw(st.one_of(st.none(), st.integers(0, 2**31 - 1))),
        "causation_id": None,
        "project_uuid": "5c0f6f0e-3b1a-4d8e-9a55-0d7e3f2b9c11",
        "project_slug": draw(st.one_of(st.none(), _names)),
        "correlation_id": draw(_ulids),
        "schema_version": "3.0.0",
        "data_tier": draw(st.integers(0, 4)),
    }


_rows = st.one_of(_diary_rows(), _transport_rows())
_contexts = st.builds(UpcastContext, missionid=st.one_of(st.none(), _ulids))


@settings(max_examples=250, deadline=None)
@given(row=_rows, context=_contexts)
def test_same_row_same_bytes_and_the_input_is_untouched(
    row: Dict[str, Any], context: UpcastContext
) -> None:
    before = copy.deepcopy(row)
    first, second = upcast(row, context), upcast(copy.deepcopy(row), context)
    assert row == before
    assert first.outcome == second.outcome and first.codes == second.codes
    assert canonical_bytes(first.row) == canonical_bytes(second.row)


@settings(max_examples=250, deadline=None)
@given(row=_rows, context=_contexts)
def test_upcasting_the_result_again_changes_nothing(
    row: Dict[str, Any], context: UpcastContext
) -> None:
    result = upcast(row, context)
    again = upcast(result.row, context)
    assert again.outcome == "passthrough"
    assert canonical_bytes(again.row) == canonical_bytes(result.row)


@settings(max_examples=250, deadline=None)
@given(row=_diary_rows(), mission=_ulids)
def test_a_diary_row_with_a_mission_in_context_always_maps(
    row: Dict[str, Any], mission: str
) -> None:
    result = upcast(row, UpcastContext(missionid=mission))
    assert result.mapped, result.issues
    assert result.row["data"]["to_lane"] == row["to_lane"]  # diary rows carry no alias mapping


def test_ten_thousand_diary_rows_upcast_within_the_bound() -> None:
    lanes = [lane.value for lane in Lane]
    rows = [
        {
            "event_id": f"01J00000000000000000{i:06d}",
            "mission_slug": "041-demo",
            "wp_id": f"WP{i % 40:02d}",
            "from_lane": lanes[i % len(lanes)],
            "to_lane": lanes[(i + 1) % len(lanes)],
            "at": "2026-01-01T00:00:00+00:00",
            "actor": "claude" if i % 2 else {"role": "implementer", "tool": "claude"},
            "force": False,
            "execution_mode": "worktree",
            "reason": None,
            "review_ref": None,
            "evidence": None,
        }
        for i in range(10_000)
    ]
    context = UpcastContext(missionid="01J0000000000000000000MSN1")
    start = time.perf_counter()
    for row in rows:
        upcast(row, context)
    elapsed = time.perf_counter() - start
    assert elapsed < 2.0, f"10,000 rows took {elapsed:.2f}s (NFR-004: < 2 s)"
