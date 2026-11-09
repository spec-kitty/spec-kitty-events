"""Core-free unit tests for the status_append fixture generator."""

from __future__ import annotations

import importlib.util
import json
import sys
from pathlib import Path
from types import ModuleType

import pytest


def _load_module() -> ModuleType:
    path = (
        Path(__file__).resolve().parents[1]
        / "scripts"
        / "conformance"
        / "generate_status_append.py"
    )
    spec = importlib.util.spec_from_file_location("generate_status_append", path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def test_document_writer_uses_stable_deterministic_shape(tmp_path: Path) -> None:
    module = _load_module()
    case = module.Case("example", '{"b": 2}', ("example",), "Example case.")

    module.write_documents(tmp_path, (case,), lambda row: json.dumps(row, sort_keys=True))

    assert (tmp_path / "valid" / "example.json").read_text(encoding="utf-8") == (
        "{\n"
        '  "input_json": "{\\"b\\": 2}",\n'
        '  "expected_line": "{\\"b\\": 2}\\n",\n'
        '  "covers": [\n'
        '    "example"\n'
        "  ]\n"
        "}\n"
    )


def test_invalid_documents_record_actual_core_behaviour() -> None:
    module = _load_module()
    nan_case = module.Case("nan", '{"n": NaN}', ("non_finite_number",), "NaN.", "NonFiniteNumber")
    mixed_case = module.Case(
        "mixed",
        '{"session_started_at": "2026-01-01", "session_ended_at": "2026-01-02T00:00:00Z"}',
        ("session_pair_mixed_awareness",),
        "Mixed.",
        "SessionTimesNotSubtractable",
    )

    def serialize(row: object) -> str:
        if row == json.loads(mixed_case.input_json):
            raise TypeError("can't subtract offset-naive and offset-aware datetimes")
        return '{"n": NaN}'

    assert module.build_document(nan_case, serialize) == {
        "input_json": nan_case.input_json,
        "expected_error": "NonFiniteNumber",
        "core_behaviour": "writes NaN",
        "covers": ["non_finite_number"],
    }
    assert module.build_document(mixed_case, serialize) == {
        "input_json": mixed_case.input_json,
        "expected_error": "SessionTimesNotSubtractable",
        "core_behaviour": "raises TypeError",
        "covers": ["session_pair_mixed_awareness"],
    }


def test_check_diff_reports_changed_missing_and_extra_cases(tmp_path: Path) -> None:
    module = _load_module()
    case = module.Case("example", '{"b": 2}', ("example",), "Example case.")
    documents = module.expected_documents(tmp_path, (case,), lambda row: '{"b": 2}')
    module.write_documents(tmp_path, (case,), lambda row: '{"b": 2}')

    assert module.diff_documents(tmp_path, documents) == []

    path = tmp_path / "valid" / "example.json"
    path.write_text('{"changed": true}\n', encoding="utf-8")
    assert module.diff_documents(tmp_path, documents) == ["example"]

    path.unlink()
    (tmp_path / "invalid").mkdir(exist_ok=True)
    (tmp_path / "invalid" / "extra.json").write_text("{}\n", encoding="utf-8")
    assert module.diff_documents(tmp_path, documents) == ["example", "extra"]


def test_write_removes_a_case_no_longer_in_the_table(tmp_path: Path) -> None:
    module = _load_module()
    (tmp_path / "valid").mkdir(parents=True)
    (tmp_path / "valid" / "stale.json").write_text("{}\n", encoding="utf-8")

    module.write_documents(tmp_path, (), lambda row: "{}")

    assert not (tmp_path / "valid" / "stale.json").exists()


def test_main_requires_core_checkout(monkeypatch: pytest.MonkeyPatch, capsys: object) -> None:
    module = _load_module()
    monkeypatch.delenv("SPEC_KITTY_CORE_CHECKOUT", raising=False)

    assert module.main([]) == 2
    captured = capsys.readouterr()  # type: ignore[attr-defined]
    assert captured.out == ""
    assert captured.err == "SPEC_KITTY_CORE_CHECKOUT must name the pinned core checkout\n"
