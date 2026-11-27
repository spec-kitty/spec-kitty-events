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


# The document keys of fixtures/ledger_append/README.md, plus the manifest's id and
# expected_result the loader adds: a misspelt key (say ``existing_ledgr``) would
# otherwise drop silently and the case would run over no ledger at all (#279).
_DOCUMENT_KEYS = {
    "input_kind",
    "input",
    "model",
    "existing_ledger",
    "nan_field",
    "expected_line",
    "expected_error",
    "id",
    "expected_result",
}


@pytest.mark.parametrize("case", _CASES, ids=[c["id"] for c in _CASES])
def test_ledger_append_document_keys(case: Dict[str, Any]) -> None:
    unknown = set(case) - _DOCUMENT_KEYS
    assert not unknown, f"{case['id']}: keys outside the document contract: {sorted(unknown)}"
    pinned = "expected_line" if case["expected_result"] == "valid" else "expected_error"
    assert pinned in case, f"{case['id']}: pins no {pinned}"


def test_ledger_append_torn_tail_documents_are_torn() -> None:
    """Every document pinning ``LedgerTornTailError`` carries a torn ledger (#280).

    The BDD selection step in ``tests/scenarios/ledger/conftest.py`` repeats this check; the
    packaged runner cannot import ``tests/``."""
    torn = [c for c in _CASES if c.get("expected_error") == "LedgerTornTailError"]
    assert torn, "no ledger_append document pins LedgerTornTailError"
    for case in torn:
        existing = case.get("existing_ledger")
        assert existing and not existing.endswith("\n"), (
            f"{case['id']} pins LedgerTornTailError over {existing!r}, not a torn ledger"
        )


def test_ledger_append_validation_refusal_over_a_well_formed_ledger() -> None:
    """A validation refusal is pinned over a non-empty, LF-terminated ledger (#279), so
    "a refused append leaves the ledger byte-identical" is proven over existing bytes,
    not only over an absent file."""
    assert any(
        c.get("expected_error") == "LedgerValidationError"
        and c.get("existing_ledger")
        and c["existing_ledger"].endswith("\n")
        for c in _CASES
    ), "no LedgerValidationError document carries a non-empty, well-formed existing_ledger"


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
