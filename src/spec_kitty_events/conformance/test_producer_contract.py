"""Packaged conformance runner for the producer row fixtures.

Every fixture under ``producer_contract/fixtures/rows/`` is run against the schema the
registry composes for its type:

* ``valid/``: a CloudEvents line (``input``) validates against :func:`row_schema`, and
  its persisted legacy line (``legacy_row``) validates against the generated
  :func:`legacy_row_form_schema`.
* ``invalid/``: a CloudEvents line must be rejected, with an error at the pinned
  ``expected_error_path`` raised by the pinned ``expected_keyword`` (so a fixture cannot
  pass by failing for the wrong reason).
* ``legacy_invalid/``: the same for the legacy form.

Needs the optional ``jsonschema`` dependency (the ``conformance`` extra).
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Dict, List, Set, Tuple

import pytest

from spec_kitty_events import producer_contract as pc

jsonschema = pytest.importorskip(
    "jsonschema",
    reason="producer conformance needs the conformance extra: pip install spec-kitty-events[conformance]",
)

_ROWS_DIR = Path(pc.__file__).resolve().parent / "fixtures" / "rows"


def _paths(sub: str) -> List[Path]:
    return sorted((_ROWS_DIR / sub).glob("*.json"))


def _load(path: Path) -> Dict[str, Any]:
    loaded: Dict[str, Any] = json.loads(path.read_text(encoding="utf-8"))
    return loaded


def _errors(schema: Dict[str, Any], instance: Any) -> List[Any]:
    validator = jsonschema.Draft202012Validator(schema)
    return list(validator.iter_errors(instance))


def _located(errors: List[Any]) -> Set[Tuple[str, str]]:
    return {
        (
            "/" + "/".join(str(part) for part in e.absolute_path) if e.absolute_path else "",
            e.validator,
        )
        for e in errors
    }


def test_every_registered_type_has_a_valid_fixture() -> None:
    covered = {_load(path)["type"] for path in _paths("valid")}
    assert covered == set(pc.registered_types())


@pytest.mark.parametrize("path", _paths("valid"), ids=lambda p: p.stem)
def test_valid_row_and_its_legacy_form(path: Path) -> None:
    fixture = _load(path)
    assert _errors(pc.row_schema(fixture["type"]), fixture["input"]) == []
    # a row a producer writes is also a row a reader accepts
    assert _errors(pc.row_schema(fixture["type"], profile="read"), fixture["input"]) == []
    if pc.get_entry(fixture["type"])["legacy"] is None:
        # read from the 3.0.0 transport envelope only (spec-kitty-events#92)
        assert "legacy_row" not in fixture and "transport_row" in fixture
    else:
        assert _errors(pc.legacy_row_form_schema(fixture["type"]), fixture["legacy_row"]) == []


@pytest.mark.parametrize("path", _paths("invalid"), ids=lambda p: p.stem)
def test_invalid_row_is_rejected_for_the_pinned_reason(path: Path) -> None:
    fixture = _load(path)
    errors = _errors(pc.row_schema(fixture["type"]), fixture["input"])
    assert errors, "an invalid fixture validated"
    assert (fixture["expected_error_path"], fixture["expected_keyword"]) in _located(errors)


@pytest.mark.parametrize("path", _paths("legacy_invalid"), ids=lambda p: p.stem)
def test_invalid_legacy_row_is_rejected_for_the_pinned_reason(path: Path) -> None:
    fixture = _load(path)
    errors = _errors(pc.legacy_row_form_schema(fixture["type"]), fixture["input"])
    assert errors, "an invalid legacy fixture validated"
    assert (fixture["expected_error_path"], fixture["expected_keyword"]) in _located(errors)
