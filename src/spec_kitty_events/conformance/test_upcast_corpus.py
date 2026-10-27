"""Packaged conformance runner for the upcaster corpus (spec-kitty-events#92).

Every case under ``producer_contract/fixtures/upcast/cases/`` is one legacy log line
with its caller context and expected result. For each case:

* the outcome, detected form and issue codes match;
* a mapped row's canonical bytes equal the golden, byte for byte;
* the golden is (or, for the cases pinned as not valid, is not) a valid read-profile
  CloudEvent of its type;
* reading the golden again is a pass-through with the same bytes (idempotence);
* strict use raises the first code for every case that carries one, except a row that
  is already a CloudEvent.

The Java binding runs the same files (``java/``) and must produce the same bytes.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Dict, List

import pytest

from spec_kitty_events import producer_contract as pc
from spec_kitty_events.cloudevents import (
    IssueCode,
    UpcastContext,
    UpcastError,
    canonical_text,
    upcast_line,
)

pytest.importorskip(
    "jsonschema",
    reason="the upcast corpus needs the conformance extra: pip install spec-kitty-events[conformance]",
)

from spec_kitty_events.cloudevents import diary_row, validate_cloudevent  # noqa: E402
from spec_kitty_events.conformance.loader import load_reducer_output  # noqa: E402
from spec_kitty_events.diary import reduce  # noqa: E402

_CASES = Path(pc.__file__).resolve().parent / "fixtures" / "upcast" / "cases"


def _cases() -> List[Dict[str, Any]]:
    return [json.loads(p.read_text(encoding="utf-8")) for p in sorted(_CASES.rglob("*.json"))]


def _id(case: Dict[str, Any]) -> str:
    return f"{case['group']}/{case['case']}"


def test_the_corpus_covers_every_group_and_every_registered_transport_type() -> None:
    cases = _cases()
    groups = {case["group"] for case in cases}
    assert groups == {"transport", "conformance", "diary", "cli", "edge"}
    transport = {case["case"] for case in cases if case["group"] == "transport"}
    expected = {
        pc.get_entry(t)["name"] for t in pc.registered_types() if pc.get_entry(t)["transport"]
    }
    assert transport == expected
    codes = {code for case in cases for code in case["expected"]["codes"]}
    assert codes == set(IssueCode.ALL), set(IssueCode.ALL) - codes
    assert len(cases) > 150


@pytest.mark.parametrize("case", _cases(), ids=_id)
def test_case(case: Dict[str, Any]) -> None:
    expected = case["expected"]
    context = UpcastContext(**case["context"])
    result = upcast_line(case["line"], context)
    assert result.outcome == expected["outcome"]
    assert result.form == expected["form"]
    assert list(result.codes) == expected["codes"]
    assert result.original_line == case["line"].encode("utf-8")
    if expected["outcome"] == "mapped":
        golden = expected["golden"]
        assert canonical_text(result.row) == golden
        event = json.loads(golden)
        assert (validate_cloudevent(event, profile="read") == []) is expected["valid"]
        again = upcast_line(golden, context)
        assert again.outcome == "passthrough" and again.codes == (IssueCode.ALREADY_CLOUDEVENT,)
        assert canonical_text(again.row) == golden
    else:
        assert expected["golden"] is None and expected["valid"] is None
        assert result.row is None or canonical_text(result.row) == canonical_text(
            json.loads(case["line"])
        )
    raises = bool(expected["codes"]) and expected["codes"][0] != IssueCode.ALREADY_CLOUDEVENT
    if raises:
        with pytest.raises(UpcastError) as error:
            upcast_line(case["line"], context, strict=True)
        assert error.value.code == expected["codes"][0]
    else:
        upcast_line(case["line"], context, strict=True)


# ------------------------------------------------------------- equal reduced state
def _diary_files() -> List[str]:
    return sorted({case["origin"].split(":")[0] for case in _cases() if case["group"] == "diary"})


@pytest.mark.parametrize("origin", _diary_files(), ids=lambda o: Path(o).stem)
def test_upcast_diary_reduces_to_the_same_state(origin: str) -> None:
    """Design section 9: decoding the upcast rows gives the reducer the same state."""
    cases = sorted(
        (c for c in _cases() if c["origin"].split(":")[0] == origin),
        key=lambda c: int(c["origin"].rsplit(":", 1)[1]),
    )
    legacy = [json.loads(case["line"]) for case in cases]
    upcast_rows = []
    for case in cases:
        result = upcast_line(case["line"], UpcastContext(**case["context"]))
        upcast_rows.append(diary_row(result.row) if result.mapped else result.row)
    # status_diary_<name>.jsonl pairs with the reducer_output id status-diary-replay-<name>-output
    name = Path(origin).stem.removeprefix("status_diary_").replace("_", "-")
    expected = load_reducer_output(f"status-diary-replay-{name}-output")
    assert reduce(legacy).to_dict() == expected
    assert reduce(upcast_rows).to_dict() == expected
