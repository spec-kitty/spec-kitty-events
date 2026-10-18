"""Envelope clock: ``lamport_clock`` is optional (issue #120)."""

from __future__ import annotations

import uuid
from datetime import datetime, timezone
from typing import Optional

import pytest

from pydantic import ValidationError as PydanticValidationError

from spec_kitty_events.conflict import is_concurrent, total_order_key
from spec_kitty_events.lifecycle import (
    MISSION_CANCELLED,
    MISSION_STARTED,
    MissionCancelledPayload,
    MissionStartedPayload,
    MissionStatus,
    reduce_lifecycle_events,
)
from spec_kitty_events.merge import state_machine_merge
from spec_kitty_events.models import Event, ValidationError
from spec_kitty_events.ordering import canonical_order_key, is_clocked, node_order_key
from spec_kitty_events.status import (
    WP_STATUS_CHANGED,
    ExecutionMode,
    Lane,
    StatusTransitionPayload,
    reduce_status_events,
    status_event_sort_key,
)
from spec_kitty_events.storage import InMemoryEventStore
from spec_kitty_events.strict import STRICT_ENVELOPE_KEYS, validate_strict_envelope
from spec_kitty_events.work_observation import WORK_OBSERVATION
from spec_kitty_events.work_replay import work_item_from_event

_PROJECT = uuid.UUID("12345678-1234-5678-1234-567812345678")


def make_event(
    event_id: str,
    clock: Optional[int],
    second: int = 0,
    node_id: str = "node-a",
    aggregate_id: str = "agg-1",
) -> Event:
    kwargs = dict(
        event_id=event_id,
        event_type="Test",
        aggregate_id=aggregate_id,
        payload={},
        timestamp=datetime(2026, 1, 1, 0, 0, second, tzinfo=timezone.utc),
        build_id="build-1",
        node_id=node_id,
        project_uuid=_PROJECT,
        correlation_id="01ARZ3NDEKTSV4RRFFQ69G5FAC",
    )
    if clock is not None:
        kwargs["lamport_clock"] = clock
    return Event(**kwargs)  # type: ignore[arg-type]


def test_unclocked_envelope_is_accepted() -> None:
    event = make_event("01ARZ3NDEKTSV4RRFFQ69G5FA1", None)
    assert event.lamport_clock is None
    assert "lamport=None" in repr(event)


def test_clock_zero_is_clocked_and_negative_still_invalid() -> None:
    assert is_clocked(make_event("01ARZ3NDEKTSV4RRFFQ69G5FA1", 0))
    assert not is_clocked(make_event("01ARZ3NDEKTSV4RRFFQ69G5FA2", None))
    with pytest.raises(PydanticValidationError):
        make_event("01ARZ3NDEKTSV4RRFFQ69G5FA3", -1)


def test_canonical_order_key_shape() -> None:
    clocked = make_event("01ARZ3NDEKTSV4RRFFQ69G5FA1", 4, second=5)
    unclocked = make_event("01ARZ3NDEKTSV4RRFFQ69G5FA2", None, second=5)
    assert canonical_order_key(clocked) == (1, 4, clocked.timestamp.isoformat(), clocked.event_id)
    assert canonical_order_key(unclocked) == (
        0,
        0,
        unclocked.timestamp.isoformat(),
        unclocked.event_id,
    )


def test_mixed_stream_sorts_without_type_error_and_is_permutation_stable() -> None:
    events = [
        make_event("01ARZ3NDEKTSV4RRFFQ69G5FA5", None, second=1),
        make_event("01ARZ3NDEKTSV4RRFFQ69G5FA4", 2, second=9),
        make_event("01ARZ3NDEKTSV4RRFFQ69G5FA3", None, second=0),
        make_event("01ARZ3NDEKTSV4RRFFQ69G5FA2", 0, second=3),
    ]
    expected = [
        "01ARZ3NDEKTSV4RRFFQ69G5FA3",
        "01ARZ3NDEKTSV4RRFFQ69G5FA5",
        "01ARZ3NDEKTSV4RRFFQ69G5FA2",
        "01ARZ3NDEKTSV4RRFFQ69G5FA4",
    ]
    for perm in (events, events[::-1], events[2:] + events[:2]):
        assert [e.event_id for e in sorted(perm, key=canonical_order_key)] == expected
        assert [e.event_id for e in sorted(perm, key=status_event_sort_key)] == expected


def test_node_order_key_and_storage_adapters_handle_unclocked() -> None:
    a = make_event("01ARZ3NDEKTSV4RRFFQ69G5FA1", None, node_id="a")
    b = make_event("01ARZ3NDEKTSV4RRFFQ69G5FA2", 1, node_id="z")
    c = make_event("01ARZ3NDEKTSV4RRFFQ69G5FA3", 1, node_id="b")
    assert node_order_key(b) == (1, 1, "z", b.event_id)
    assert node_order_key(a) == (0, 0, "a", a.event_id)
    store = InMemoryEventStore()
    for e in (a, b, c):
        store.save_event(e)
    expected = [a.event_id, c.event_id, b.event_id]
    assert [e.event_id for e in store.load_events("agg-1")] == expected
    assert [e.event_id for e in store.load_all_events()] == expected


def test_total_order_key_handles_unclocked() -> None:
    a = make_event("01ARZ3NDEKTSV4RRFFQ69G5FA1", None, node_id="a")
    b = make_event("01ARZ3NDEKTSV4RRFFQ69G5FA2", 7, node_id="z")
    assert sorted([a, b], key=total_order_key) == [a, b]


def test_unclocked_is_never_concurrent() -> None:
    a = make_event("01ARZ3NDEKTSV4RRFFQ69G5FA1", None)
    b = make_event("01ARZ3NDEKTSV4RRFFQ69G5FA2", None)
    c = make_event("01ARZ3NDEKTSV4RRFFQ69G5FA3", 1)
    d = make_event("01ARZ3NDEKTSV4RRFFQ69G5FA4", 1)
    assert not is_concurrent(a, b)
    assert not is_concurrent(a, c)
    assert is_concurrent(c, d)


def test_merge_refuses_unclocked_input() -> None:
    a = make_event("01ARZ3NDEKTSV4RRFFQ69G5FA1", None)
    b = make_event("01ARZ3NDEKTSV4RRFFQ69G5FA2", 1)
    with pytest.raises(ValidationError, match="unclocked"):
        state_machine_merge([b, a], {"x": 1})
    with pytest.raises(ValidationError, match="unclocked"):
        state_machine_merge([a], {"x": 1})


def _status_event(
    event_id: str, from_lane: Optional[Lane], to_lane: Lane, clock: Optional[int], second: int
) -> Event:
    payload = StatusTransitionPayload(
        mission_slug="m",
        wp_id="WP01",
        from_lane=from_lane,
        to_lane=to_lane,
        actor="a",
        execution_mode=ExecutionMode.WORKTREE,
    ).model_dump()
    return make_event(event_id, clock, second=second).model_copy(
        update={"event_type": WP_STATUS_CHANGED, "payload": payload}
    )


def test_status_unclocked_events_group_as_singletons() -> None:
    """Two unclocked transitions are sequential, not one concurrent group."""
    events = [
        _status_event("01ARZ3NDEKTSV4RRFFQ69G5FA1", None, Lane.PLANNED, None, 1),
        _status_event("01ARZ3NDEKTSV4RRFFQ69G5FA2", Lane.PLANNED, Lane.CLAIMED, None, 2),
    ]
    result = reduce_status_events(events)
    assert not result.anomalies
    assert result.wp_states["WP01"].current_lane == Lane.CLAIMED


def test_status_mixed_stream_reduces_without_type_error() -> None:
    events = [
        _status_event("01ARZ3NDEKTSV4RRFFQ69G5FA2", Lane.PLANNED, Lane.CLAIMED, 1, 2),
        _status_event("01ARZ3NDEKTSV4RRFFQ69G5FA1", None, Lane.PLANNED, None, 1),
    ]
    result = reduce_status_events(events)
    assert result.wp_states["WP01"].current_lane == Lane.CLAIMED


def _lifecycle_event(
    event_type: str, payload: dict, clock: Optional[int], second: int, n: int
) -> Event:  # type: ignore[type-arg]
    return make_event(f"01ARZ3NDEKTSV4RRFFQ69G5FB{n}", clock, second=second).model_copy(
        update={"event_type": event_type, "payload": payload, "aggregate_id": "mission/M001"}
    )


def test_lifecycle_unclocked_events_are_singleton_groups() -> None:
    """Cancel-last reordering only applies inside a concurrent (clocked) group."""
    cancelled = _lifecycle_event(
        MISSION_CANCELLED,
        MissionCancelledPayload(mission_id="M001", reason="r", actor="a").model_dump(),
        None,
        1,
        1,
    )
    started = _lifecycle_event(
        MISSION_STARTED,
        MissionStartedPayload(
            mission_id="M001", mission_type="software-dev", initial_phase="specify", actor="a"
        ).model_dump(),
        None,
        2,
        2,
    )
    result = reduce_lifecycle_events([started, cancelled])
    assert result.event_count == 2
    # Singleton groups apply in timestamp order: cancel first, then start.
    # Were they one group, cancel-last would apply start first and report no anomaly.
    assert len(result.anomalies) == 1


def test_lifecycle_mixed_stream_reduces() -> None:
    started = _lifecycle_event(
        MISSION_STARTED,
        MissionStartedPayload(
            mission_id="M001", mission_type="software-dev", initial_phase="specify", actor="a"
        ).model_dump(),
        None,
        1,
        1,
    )
    cancelled = _lifecycle_event(
        MISSION_CANCELLED,
        MissionCancelledPayload(mission_id="M001", reason="r", actor="a").model_dump(),
        1,
        2,
        2,
    )
    result = reduce_lifecycle_events([cancelled, started])
    assert result.mission_status == MissionStatus.CANCELLED
    assert not result.anomalies


def test_work_replay_refuses_unclocked_envelope() -> None:
    event = make_event("01ARZ3NDEKTSV4RRFFQ69G5FA1", None).model_copy(
        update={
            "event_type": WORK_OBSERVATION,
            "payload": {"producer": {"producer_id": "p", "instance_id": "i", "sequence": 0}},
        }
    )
    with pytest.raises(ValueError, match=r"work replay requires lamport_clock \(LW-10\)"):
        work_item_from_event(event)


def test_strict_profile_still_requires_lamport_clock() -> None:
    assert "lamport_clock" in STRICT_ENVELOPE_KEYS
    base = make_event("01ARZ3NDEKTSV4RRFFQ69G5FA1", 1).model_dump(mode="json")

    def shape_details(record: dict) -> list:  # type: ignore[type-arg]
        return [e.details for e in validate_strict_envelope(record)]

    assert {"missing": ["lamport_clock"]} not in shape_details(base)
    absent = {k: v for k, v in base.items() if k != "lamport_clock"}
    assert {"missing": ["lamport_clock"]} in shape_details(absent)
    assert {"null": ["lamport_clock"]} in shape_details({**base, "lamport_clock": None})


def _cloudevents_row(event: Event) -> dict[str, object]:
    row: dict[str, object] = {
        "id": event.event_id,
        "time": event.timestamp.isoformat(),
        "source": event.node_id,
    }
    if event.lamport_clock is not None:
        row["lamportclock"] = event.lamport_clock
    return row


def test_canonical_key_agrees_with_adr_0001_total_order() -> None:
    """The envelope order matches ADR 0001's total order: clockless rows sort first."""
    from spec_kitty_events.total_order import order_key

    events = [
        make_event("01ARZ3NDEKTSV4RRFFQ69G5FA1", 5, second=0),
        make_event("01ARZ3NDEKTSV4RRFFQ69G5FA2", None, second=9),
        make_event("01ARZ3NDEKTSV4RRFFQ69G5FA3", 0, second=4),
        make_event("01ARZ3NDEKTSV4RRFFQ69G5FA4", None, second=1),
    ]
    adr = [e.event_id for e in sorted(events, key=lambda e: order_key(_cloudevents_row(e)))]
    assert [e.event_id for e in sorted(events, key=canonical_order_key)] == adr


def test_mixed_utc_offsets_order_by_instant_like_adr_0001() -> None:
    """A +05:00 timestamp four hours 'later' on the clock face is earlier in time."""
    from datetime import timedelta

    from spec_kitty_events.total_order import order_key

    east = make_event("01ARZ3NDEKTSV4RRFFQ69G5FA1", None).model_copy(
        update={"timestamp": datetime(2026, 1, 1, 12, 0, tzinfo=timezone(timedelta(hours=5)))}
    )
    utc = make_event("01ARZ3NDEKTSV4RRFFQ69G5FA2", None).model_copy(
        update={"timestamp": datetime(2026, 1, 1, 8, 0, tzinfo=timezone.utc)}
    )
    adr = [e.event_id for e in sorted([utc, east], key=lambda e: order_key(_cloudevents_row(e)))]
    assert [e.event_id for e in sorted([utc, east], key=canonical_order_key)] == adr
