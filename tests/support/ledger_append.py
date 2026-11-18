"""Builds and runs one ``ledger_append`` write-contract document (test-only, #198).

The documents are listed by the package's ``load_ledger_append_cases`` and their keys are the
normative contract in ``conformance/fixtures/ledger_append/README.md``. This module turns one
document into the input it describes, appends it to a ledger that holds exactly the document's
``existing_ledger`` bytes (or no file), and reports what the append did, without asserting
anything: the BDD steps under ``tests/scenarios/ledger/`` judge the outcome against what the
document pins. It mirrors the packaged runner ``conformance/test_ledger_append.py``, which stays
the gate other repos consume. Not package API (C-007).
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any

from pydantic import BaseModel

import spec_kitty_events as ske
from spec_kitty_events import persistence
from spec_kitty_events.models import Event


class _Unregistered(BaseModel):
    """Stands in for the model name a document uses for "not a registered payload model"."""


def build_input(case: dict[str, Any]) -> object:
    """The append input a document describes, by its ``input_kind``.

    ``nan_field`` names a top-level ``input`` key whose value becomes an IEEE-754 NaN (JSON
    cannot hold one).
    """
    fields: dict[str, Any] = dict(case["input"])
    if "nan_field" in case:
        fields[case["nan_field"]] = float("nan")
    kind = case["input_kind"]
    if kind == "event":
        return Event(**fields)
    if kind == "model":
        model_cls = getattr(ske, case["model"], None)
        return _Unregistered() if model_cls is None else model_cls(**fields)
    if kind == "dict":
        return fields
    raise ValueError(f"{case['id']}: unknown input_kind {kind!r}")


@dataclass(frozen=True)
class AppendOutcome:
    """What one append did: the bytes before and after, and the refusal, if any."""

    case: dict[str, Any]
    prior: bytes | None  # None: the ledger file did not exist before the append
    after: bytes | None  # None: the ledger file does not exist after the append
    refusal: str | None  # the class name of the LedgerAppendError raised, if any


def append_case(case: dict[str, Any], workdir: Path) -> AppendOutcome:
    """Append the document's input to ``workdir/<id>.events.jsonl`` seeded as it pins."""
    ledger = workdir / f"{case['id']}.events.jsonl"
    existing = case.get("existing_ledger")
    prior = None if existing is None else existing.encode("utf-8")
    if prior is not None:
        ledger.write_bytes(prior)
    refusal = None
    try:
        persistence.append_event(build_input(case), ledger)  # type: ignore[arg-type]
    except persistence.LedgerAppendError as error:
        refusal = type(error).__name__
    after = ledger.read_bytes() if ledger.exists() else None
    return AppendOutcome(case, prior, after, refusal)
