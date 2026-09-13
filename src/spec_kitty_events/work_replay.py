"""Durable work stream semantics: duplicates, conflicts, gaps, replay order.

spec-kitty-events#55 (planning#2268 LW-08/LW-10): the durable work journal
consumers (saas#1814 ingestion, saas#1816 projections, saas#1818 history)
must decide, machine-testably and identically across consumers:

* **exact duplicate** — same ``event_id`` *and* the same canonical payload
  hash (:func:`spec_kitty_events.work_observation.canonical_work_hash`):
  an idempotent retry. Accepted once, the copy recorded, never applied
  twice.
* **same ID, different payload** — a conflict. The first observation
  stands; the second is rejected with a typed
  ``WorkRejectionReason.ID_PAYLOAD_CONFLICT`` rejection, never a silent
  overwrite (LW-10: faults never produce lost ACKed work).
* **late / out-of-order** — a producer sequence at or below the highest
  sequence already seen for that ``(producer_id, instance_id)`` (and not an
  exact duplicate). Accepted — durable history is append-ordered, arrival
  order is transport noise — but flagged so projections and replay can
  resynchronize honestly (LW-10: local/recorded/projected distinguished).
* **gap** — a producer sequence that jumps past unseen sequences. The gap
  is *recorded as data* (LW-08: replay gaps are explicit, never papered
  over).

Replay order (:func:`replay_order_key`) is ``(lamport_clock, node_id,
sequence, event_id)`` — the Event envelope's causal semantics plus the
producer's monotonic sequence. **No client timestamp ever establishes
authoritative total order** (planning#2268): ``timestamp`` is
producer-occurrence presentation, exactly as the ``Event`` docstring
already requires.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Dict, Mapping, Optional, Sequence, Tuple

from spec_kitty_events.models import Event
from spec_kitty_events.work_observation import (
    WORK_OBSERVATION,
    TypedRejection,
    WorkRejectionReason,
    canonical_work_hash,
)

__all__ = [
    "WorkStreamItem",
    "WorkStreamReport",
    "SequenceGap",
    "work_item_from_event",
    "replay_order_key",
    "classify_work_stream",
]


@dataclass(frozen=True)
class WorkStreamItem:
    """One durable work observation as a stream classifier sees it.

    ``payload_hash`` is the canonical payload hash — the *only* payload
    fact the duplicate/conflict decision needs. ``timestamp`` is carried
    for reporting only; it is deliberately absent from
    :func:`replay_order_key`.
    """

    event_id: str
    payload_hash: str
    producer_id: str
    instance_id: str
    sequence: int
    lamport_clock: int
    node_id: str
    timestamp: Optional[str] = None


def work_item_from_event(event: Event) -> WorkStreamItem:
    """Build a :class:`WorkStreamItem` from a ``WorkObservation`` envelope.

    Raises:
        ValueError: If the event is not a WorkObservation envelope or its
            payload does not carry the producer identity fields.
    """
    if event.event_type != WORK_OBSERVATION:
        raise ValueError(f"expected event_type={WORK_OBSERVATION!r}, got {event.event_type!r}")
    producer = event.payload.get("producer")
    if not isinstance(producer, Mapping):
        raise ValueError("payload.producer is missing or not an object")
    for key in ("producer_id", "instance_id", "sequence"):
        if key not in producer:
            raise ValueError(f"payload.producer.{key} is missing")
    return WorkStreamItem(
        event_id=event.event_id,
        payload_hash=canonical_work_hash(dict(event.payload)),
        producer_id=str(producer["producer_id"]),
        instance_id=str(producer["instance_id"]),
        sequence=int(producer["sequence"]),
        lamport_clock=event.lamport_clock,
        node_id=event.node_id,
        timestamp=event.timestamp.isoformat(),
    )


def replay_order_key(item: WorkStreamItem) -> Tuple[Any, ...]:
    """The deterministic total-order key for durable work replay.

    ``(lamport_clock, node_id, sequence, event_id)``. The client
    ``timestamp`` is intentionally NOT part of the key — producers on
    skewed clocks must not be able to reorder durable history, and the
    ``Event`` contract already reserves ordering for the Lamport clock and
    ``node_id``. ``sequence`` disambiguates same-tick emissions from one
    producer instance; ``event_id`` is the final deterministic tie-break.
    """
    return (item.lamport_clock, item.node_id, item.sequence, item.event_id)


@dataclass(frozen=True)
class SequenceGap:
    """A recorded gap in one producer instance's monotonic sequence."""

    producer_id: str
    instance_id: str
    expected: int
    observed: int


@dataclass(frozen=True)
class WorkStreamReport:
    """The full classification of one durable work stream.

    ``accepted`` is in :func:`replay_order_key` order. ``duplicates`` are
    the exact-duplicate copies (accepted once, listed here so consumers can
    ACK the retry). ``conflicts`` are the same-ID/different-payload
    rejections. ``late`` are accepted-but-out-of-order items.
    ``gaps`` are the recorded sequence gaps.
    """

    accepted: Tuple[WorkStreamItem, ...]
    duplicates: Tuple[WorkStreamItem, ...]
    conflicts: Tuple[TypedRejection, ...]
    late: Tuple[WorkStreamItem, ...]
    gaps: Tuple[SequenceGap, ...]

    @property
    def clean(self) -> bool:
        """True when the stream had no duplicate, conflict, late arrival,
        or gap — i.e. nothing a replay UI would need to surface."""
        return not (self.duplicates or self.conflicts or self.late or self.gaps)


def classify_work_stream(items: Sequence[WorkStreamItem]) -> WorkStreamReport:
    """Classify a durable work stream per the module's four rules.

    Arrival order is the transport's; the report's ``accepted`` is in
    replay order regardless. Per ``(producer_id, instance_id)`` the first
    observed sequence is the anchor (a stream recovering mid-flight may
    legitimately start above zero — that is the *server's* pre-stream gap
    to account for with its committed cursor, not this stream's).
    """
    accepted: list[WorkStreamItem] = []
    duplicates: list[WorkStreamItem] = []
    conflicts: list[TypedRejection] = []
    late: list[WorkStreamItem] = []
    gaps: list[SequenceGap] = []

    seen_hashes: Dict[str, str] = {}
    # (producer_id, instance_id) -> highest sequence accepted so far
    high_water: Dict[Tuple[str, str], int] = {}

    for item in items:
        prior_hash = seen_hashes.get(item.event_id)
        if prior_hash is not None:
            if prior_hash == item.payload_hash:
                duplicates.append(item)
            else:
                conflicts.append(
                    TypedRejection(
                        reason=WorkRejectionReason.ID_PAYLOAD_CONFLICT,
                        detail=(
                            f"event_id {item.event_id} already accepted with a "
                            f"different canonical payload hash "
                            f"({prior_hash[:12]}… != {item.payload_hash[:12]}…); "
                            f"the first observation stands"
                        ),
                        event_id=item.event_id,
                    )
                )
            continue
        seen_hashes[item.event_id] = item.payload_hash

        key = (item.producer_id, item.instance_id)
        if key in high_water:
            highest = high_water[key]
            if item.sequence <= highest:
                late.append(item)
            elif item.sequence > highest + 1:
                gaps.append(
                    SequenceGap(
                        producer_id=item.producer_id,
                        instance_id=item.instance_id,
                        expected=highest + 1,
                        observed=item.sequence,
                    )
                )
            high_water[key] = max(highest, item.sequence)
        else:
            high_water[key] = item.sequence
        accepted.append(item)

    accepted.sort(key=replay_order_key)
    return WorkStreamReport(
        accepted=tuple(accepted),
        duplicates=tuple(duplicates),
        conflicts=tuple(conflicts),
        late=tuple(late),
        gaps=tuple(gaps),
    )
