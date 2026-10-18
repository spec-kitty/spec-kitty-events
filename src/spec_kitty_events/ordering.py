"""Canonical ordering keys for envelopes whose ``lamport_clock`` is optional.

An envelope without a ``lamport_clock`` is *unclocked*. Unclocked envelopes
sort before every clocked one, ordered among themselves by timestamp and then
``event_id``; the key never compares ``None`` with an ``int``, so mixed
streams are safe to sort.

The leading part matches ADR 0001's total order (``total_order.order_key``,
``contracts/total-order.md``), where every clockless row sorts before every
clocked row, so the envelope order and the CloudEvents-row order agree.
"""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Tuple

from spec_kitty_events.models import Event

__all__ = ["canonical_order_key", "is_clocked", "node_order_key"]


def is_clocked(event: Event) -> bool:
    """Return True when the envelope carries a ``lamport_clock``."""
    return event.lamport_clock is not None


def _utc_iso(timestamp: datetime) -> str:
    """ISO text of the instant in UTC, so string order equals time order.

    An aware timestamp is converted to UTC; a naive one is left as given
    (the strict profile refuses naive timestamps).
    """
    if timestamp.tzinfo is None:
        return timestamp.isoformat()
    return timestamp.astimezone(timezone.utc).isoformat()


def canonical_order_key(event: Event) -> Tuple[int, int, str, str]:
    """Deterministic key: ``(1 if clocked else 0, clock or 0, utc_timestamp_iso, event_id)``."""
    clock = event.lamport_clock
    return (
        1 if clock is not None else 0,
        clock or 0,
        _utc_iso(event.timestamp),
        event.event_id,
    )


def node_order_key(event: Event) -> Tuple[int, int, str, str]:
    """Storage-adapter key: ``(1 if clocked else 0, clock or 0, node_id, event_id)``."""
    clock = event.lamport_clock
    return (1 if clock is not None else 0, clock or 0, event.node_id, event.event_id)
