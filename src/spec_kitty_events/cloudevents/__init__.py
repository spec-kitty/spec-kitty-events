"""CloudEvents 1.0 rows: the read-time upcaster for legacy log rows, and canonical bytes.

ADR 0001 makes CloudEvents 1.0 structured JSON the envelope of Spec Kitty event-log
rows. Rows already on disk keep their legacy form (the 3.0.0 transport envelope, the
status-diary lane and annotation rows, and the core CLI's lifecycle, decision, journal
and retrospective rows); they are read through :func:`upcast` and never rewritten.

The mapping is driven by the type registry in :mod:`spec_kitty_events.producer_contract`
and pinned by the corpus under ``producer_contract/fixtures/upcast/``, which every
language binding runs. Status: non-normative until the ADR 0001 cutover release;
nothing here changes what ``validate_strict_envelope`` accepts.
"""

from __future__ import annotations

from ._canonical import canonical_bytes, canonical_text
from ._decode import DIARY_TYPES, diary_row
from ._types import IssueCode, UpcastContext, UpcastError, UpcastIssue, UpcastResult
from ._upcast import upcast, upcast_line
from ._validate import ValidationIssue, validate_cloudevent

__all__ = [
    "DIARY_TYPES",
    "IssueCode",
    "UpcastContext",
    "UpcastError",
    "UpcastIssue",
    "UpcastResult",
    "ValidationIssue",
    "canonical_bytes",
    "canonical_text",
    "diary_row",
    "upcast",
    "upcast_line",
    "validate_cloudevent",
]
