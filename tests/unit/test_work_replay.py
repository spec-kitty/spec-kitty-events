"""Replay/duplicate semantics for durable work streams (spec-kitty-events#55).

Machine-tests the four stream rules every consumer must agree on — exact
duplicate, same-ID/different-payload conflict, late/out-of-order arrival,
and recorded gaps — plus the LW-10 rule that no client timestamp ever
establishes authoritative total order.
"""

from __future__ import annotations

import pytest
from spec_kitty_events.models import Event
from spec_kitty_events.work_observation import (
    WorkRejectionReason,
    canonical_work_hash,
)
from spec_kitty_events.work_replay import (
    WorkStreamItem,
    classify_work_stream,
    replay_order_key,
    work_item_from_event,
)


def E(n: str) -> str:
    """26-char pinned ULID-style event id."""
    return f"01J0000000000000000000{n}"


H = "a" * 64
PROJECT = "12345678-1234-5678-1234-567812345678"


def item(
    event_id,
    payload_hash=H,
    producer="p1",
    instance="i1",
    sequence=0,
    lamport=0,
    node="n1",
    timestamp="2026-01-01T00:00:00Z",
) -> WorkStreamItem:
    return WorkStreamItem(
        event_id=event_id,
        payload_hash=payload_hash,
        producer_id=producer,
        instance_id=instance,
        sequence=sequence,
        lamport_clock=lamport,
        node_id=node,
        timestamp=timestamp,
    )


def envelope(event_id, payload, lamport, node="n1", causation=None) -> dict:
    return {
        "event_id": event_id,
        "event_type": "WorkObservation",
        "aggregate_id": "mission/m-1",
        "payload": payload,
        "timestamp": "2026-01-01T00:00:00Z",
        "build_id": "build-1",
        "node_id": node,
        "lamport_clock": lamport,
        "causation_id": causation,
        "project_uuid": PROJECT,
        "project_slug": None,
        "correlation_id": E("C0RR"),
        "schema_version": "3.0.0",
        "data_tier": 0,
    }


WORK_PAYLOAD = {
    "kind": "narrative.progress_reported",
    "producer": {"producer_id": "p1", "instance_id": "i1", "sequence": 0},
    "session": {"session_id": "s1"},
    "actor": {"principal_kind": "human", "principal_id": "acct-1"},
    "mission": {"mission_id": "m-1", "display_label": "Fix auth"},
    "repository": {
        "provider": "github",
        "repository_id": "99001",
        "display_slug": "spec-kitty/spec-kitty-saas",
    },
    "provenance": {"source": "cli_wrapper", "capability": "work-observation"},
    "text": "Working on the auth fix.",
}


# ── the four stream rules ────────────────────────────────────────────────────


def test_exact_duplicate_is_idempotent() -> None:
    report = classify_work_stream(
        [
            item(E("AA01")),
            item(E("AA01")),
        ]
    )
    assert len(report.accepted) == 1
    assert [d.event_id for d in report.duplicates] == [E("AA01")]
    assert not report.conflicts
    assert report.clean is False


def test_same_id_different_payload_is_a_typed_conflict() -> None:
    report = classify_work_stream(
        [
            item(E("AA01"), payload_hash=H),
            item(E("AA01"), payload_hash="b" * 64),
        ]
    )
    assert len(report.accepted) == 1
    assert not report.duplicates
    assert len(report.conflicts) == 1
    conflict = report.conflicts[0]
    assert conflict.reason is WorkRejectionReason.ID_PAYLOAD_CONFLICT
    assert conflict.event_id == E("AA01")


def test_first_observation_stands_in_a_conflict() -> None:
    """The first-accepted bytes win; the conflicting re-take is rejected,
    never a silent overwrite (LW-10: no lost ACKed work)."""
    first = item(E("AA01"), payload_hash=H, sequence=0)
    second = item(E("AA01"), payload_hash="b" * 64, sequence=1)
    report = classify_work_stream([first, second])
    assert report.accepted == (first,)
    assert report.accepted[0].payload_hash == H


def test_late_out_of_order_arrival_is_accepted_and_flagged() -> None:
    report = classify_work_stream(
        [
            item(E("AA01"), sequence=2, lamport=2),
            item(E("AA02"), sequence=0, lamport=3),  # late: below the high-water mark
            item(E("AA03"), sequence=2, lamport=4),  # late: equal to the high-water mark
        ]
    )
    assert len(report.accepted) == 3
    assert {i.event_id for i in report.late} == {E("AA02"), E("AA03")}


def test_sequence_gap_is_recorded_as_data() -> None:
    report = classify_work_stream(
        [
            item(E("AA01"), sequence=0, lamport=0),
            item(E("AA02"), sequence=3, lamport=1),
        ]
    )
    assert len(report.gaps) == 1
    gap = report.gaps[0]
    assert (gap.producer_id, gap.instance_id, gap.expected, gap.observed) == ("p1", "i1", 1, 3)


def test_first_sequence_is_an_anchor_not_a_gap() -> None:
    """A stream recovering mid-flight may start above zero: the server's
    committed cursor owns pre-stream coverage, not this classification."""
    report = classify_work_stream([item(E("AA01"), sequence=5)])
    assert report.gaps == ()
    assert report.clean


def test_streams_are_independent_per_producer_instance() -> None:
    report = classify_work_stream(
        [
            item(E("AA01"), producer="p1", instance="i1", sequence=0),
            item(E("AA02"), producer="p1", instance="i2", sequence=0),
            item(E("AA03"), producer="p2", instance="i1", sequence=0),
        ]
    )
    assert report.clean
    assert len(report.accepted) == 3


# ── replay order (LW-10: no client timestamp owns order) ─────────────────────


def test_replay_order_is_lamport_node_sequence_event() -> None:
    assert replay_order_key(item(E("AA01"), sequence=1, lamport=0)) < replay_order_key(
        item(E("AA02"), sequence=0, lamport=1)
    )


def test_client_timestamps_never_reorder_replay() -> None:
    """Two streams identical except for client timestamps classify to the
    same accepted order — a skewed producer clock cannot reorder durable
    history (planning#2268: no client timestamp establishes authoritative
    total order)."""
    early = [
        item(E("AA01"), sequence=0, lamport=0, timestamp="2026-01-01T00:00:00Z"),
        item(E("AA02"), sequence=1, lamport=1, timestamp="2026-01-01T00:00:01Z"),
    ]
    skewed = [
        item(E("AA01"), sequence=0, lamport=0, timestamp="2031-01-01T00:00:00Z"),
        item(E("AA02"), sequence=1, lamport=1, timestamp="2020-01-01T00:00:00Z"),
    ]
    a = classify_work_stream(early)
    b = classify_work_stream(skewed)
    assert (
        [i.event_id for i in a.accepted]
        == [i.event_id for i in b.accepted]
        == [E("AA01"), E("AA02")]
    )


def test_accepted_is_reported_in_replay_order_not_arrival_order() -> None:
    report = classify_work_stream(
        [
            item(E("AA03"), sequence=2, lamport=2),
            item(E("AA01"), sequence=0, lamport=0),
            item(E("AA02"), sequence=1, lamport=1),
        ]
    )
    assert [i.event_id for i in report.accepted] == [E("AA01"), E("AA02"), E("AA03")]


# ── envelope integration ─────────────────────────────────────────────────────


def test_work_item_from_event_round_trips() -> None:
    payload = dict(WORK_PAYLOAD)
    event = Event.model_validate(envelope(E("AA01"), payload, lamport=4, node="n1"))
    built = work_item_from_event(event)
    assert built.event_id == E("AA01")
    assert built.payload_hash == canonical_work_hash(payload)
    assert (built.producer_id, built.instance_id, built.sequence) == ("p1", "i1", 0)
    assert built.lamport_clock == 4


def test_work_item_from_event_rejects_non_work_envelopes() -> None:
    record = envelope(E("AA01"), WORK_PAYLOAD, lamport=0)
    record["event_type"] = "MissionStarted"
    event = Event.model_validate(record)
    with pytest.raises(ValueError, match="WorkObservation"):
        work_item_from_event(event)


def test_work_item_from_event_requires_producer_identity() -> None:
    payload = {k: v for k, v in WORK_PAYLOAD.items() if k != "producer"}
    event = Event.model_validate(envelope(E("AA01"), payload, lamport=0))
    with pytest.raises(ValueError, match="producer"):
        work_item_from_event(event)


def test_full_envelope_classifies_end_to_end() -> None:
    """A full Event stream — duplicate, conflict, late, gap — classifies
    identically to the item-level rules (machine-tested across the
    envelope path, not just the dataclass path)."""
    dup = dict(WORK_PAYLOAD)
    conflict = dict(WORK_PAYLOAD)
    conflict["text"] = "Tampered."
    events = [
        envelope(E("AA01"), WORK_PAYLOAD, lamport=0),
        envelope(E("AA01"), dup, lamport=0),  # exact duplicate
        envelope(E("AA01"), conflict, lamport=0),  # id conflict
        envelope(
            E("AA02"),
            {**WORK_PAYLOAD, "producer": {"producer_id": "p1", "instance_id": "i1", "sequence": 0}},
            lamport=1,
            node="n2",
        ),  # late (same sequence, new id)
    ]
    items = [work_item_from_event(Event.model_validate(e)) for e in events]
    report = classify_work_stream(items)
    assert len(report.accepted) == 2
    assert [d.event_id for d in report.duplicates] == [E("AA01")]
    assert [c.event_id for c in report.conflicts] == [E("AA01")]
    assert [i.event_id for i in report.late] == [E("AA02")]


def test_stream_item_is_frozen() -> None:
    from dataclasses import FrozenInstanceError

    stream_item = item(E("AA01"))
    with pytest.raises(FrozenInstanceError):
        stream_item.event_id = E("AA02")  # type: ignore[misc]
