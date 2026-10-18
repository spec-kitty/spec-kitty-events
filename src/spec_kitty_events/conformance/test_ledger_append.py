"""Packaged runner for the ``ledger_append`` write-contract fixtures (#118).

Each document pins one append: the input, and either the exact ASCII line
:func:`~spec_kitty_events.persistence.append_event` must write after any
``existing_ledger`` bytes, or the refusal it must raise while leaving the file
byte-identical. A second binding (for example a Java write-side sidecar) is
held to the same documents.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any, Dict, List

import pytest
from pydantic import BaseModel

import spec_kitty_events as ske
from spec_kitty_events import persistence
from spec_kitty_events.conformance.loader import load_ledger_append_cases
from spec_kitty_events.models import Event


class _Unregistered(BaseModel):
    """Stand-in for a model class name the package does not export."""


_CASES: List[Dict[str, Any]] = load_ledger_append_cases()


def _build_input(case: Dict[str, Any]) -> object:
    fields: Dict[str, Any] = dict(case["input"])
    if "nan_field" in case:
        fields[case["nan_field"]] = float("nan")
    kind = case["input_kind"]
    if kind == "event":
        return Event(**fields)
    if kind == "model":
        model_cls = getattr(ske, case["model"], None)
        if model_cls is None:
            return _Unregistered()
        return model_cls(**fields)
    return fields


def test_ledger_append_category_is_populated() -> None:
    assert {c["expected_result"] for c in _CASES} == {"valid", "invalid"}


@pytest.mark.parametrize("case", _CASES, ids=[c["id"] for c in _CASES])
def test_ledger_append_fixture(case: Dict[str, Any], tmp_path: Path) -> None:
    ledger = tmp_path / "ledger.events.jsonl"
    prior = case.get("existing_ledger")
    if prior is not None:
        ledger.write_bytes(prior.encode("utf-8"))
    event = _build_input(case)
    if case["expected_result"] == "valid":
        persistence.append_event(event, ledger)  # type: ignore[arg-type]  # fixture-built input
        assert ledger.read_bytes() == ((prior or "") + case["expected_line"]).encode("ascii")
        return
    error = getattr(persistence, case["expected_error"])
    with pytest.raises(error):
        persistence.append_event(event, ledger)  # type: ignore[arg-type]  # fixture-built input
    if prior is None:
        assert not ledger.exists()
    else:
        assert ledger.read_bytes() == prior.encode("utf-8")
