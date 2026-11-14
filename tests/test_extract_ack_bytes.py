"""Core-free unit tests for the ack-bytes extraction tool (encoder mode)."""

from __future__ import annotations

import importlib.util
import json
import sys
from pathlib import Path
from types import ModuleType

import pytest


def _load_module() -> ModuleType:
    path = Path(__file__).resolve().parents[1] / "scripts" / "conformance" / "extract_ack_bytes.py"
    spec = importlib.util.spec_from_file_location("extract_ack_bytes", path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def _case(units: list[dict[str, object]], expected: list[dict[str, object]]) -> dict[str, object]:
    return {
        "id": "example",
        "request": {"units": units},
        "expected": {"result": "ack", "units": expected},
    }


def test_encoder_mode_reproduces_the_committed_resource_byte_for_byte() -> None:
    module = _load_module()
    extracted = module.extract(module.CASES_DIR, module.encoder_serializer())

    assert module.render(extracted) == module.OUTPUT.read_text(encoding="utf-8")
    assert module.drift(module.OUTPUT, extracted) == []


def test_case_count_equals_the_corpus_ack_count() -> None:
    module = _load_module()
    documents = [json.loads(p.read_text("utf-8")) for p in module.CASES_DIR.glob("*.json")]
    ack_ids = {d["id"] for d in documents if d["expected"]["result"] == "ack"}

    committed = json.loads(module.OUTPUT.read_text(encoding="utf-8"))

    assert len(ack_ids) == 98
    assert set(committed) == ack_ids
    assert all(t == "" or t.endswith("\n") for texts in committed.values() for t in texts)


def test_replayed_unit_is_empty_and_others_concatenate_their_rows_in_order() -> None:
    module = _load_module()
    case = _case(
        [{"rows": [{"a": 1}, {"b": 2}]}, {"rows": [{"c": 3}]}, {"rows": [{"d": 4}]}],
        [{"replayed": False}, {"replayed": True}, {"replayed": False}],
    )

    texts = module.unit_texts(case, lambda row: json.dumps(row, sort_keys=True))

    assert texts == ['{"a": 1}\n{"b": 2}\n', "", '{"d": 4}\n']


def test_mismatched_unit_counts_are_refused() -> None:
    module = _load_module()
    case = _case([{"rows": [{"a": 1}]}], [{"replayed": False}, {"replayed": False}])

    with pytest.raises(ValueError, match="unit counts differ"):
        module.unit_texts(case, lambda row: "x")


def test_check_reports_changed_missing_and_extra_cases(tmp_path: Path) -> None:
    module = _load_module()
    output = tmp_path / "ack-bytes.json"
    output.write_text(
        module.render({"same": ["a\n"], "changed": ["b\n"], "extra": ["e\n"]}), "utf-8"
    )

    drifted = module.drift(output, {"same": ["a\n"], "changed": ["B\n"], "missing": ["m\n"]})

    assert drifted == ["changed", "extra", "missing"]
    assert module.drift(tmp_path / "absent.json", {"x": [""]}) == ["x"]
