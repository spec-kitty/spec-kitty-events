"""The fold-differential files must equal what the package reducer derives (#237)."""

from __future__ import annotations

import importlib.util
import json
from pathlib import Path
from types import ModuleType

import pytest

_SCRIPT = Path(__file__).resolve().parents[1] / "scripts/conformance/generate_fold_differential.py"


def _load() -> ModuleType:
    spec = importlib.util.spec_from_file_location("generate_fold_differential", _SCRIPT)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


_GENERATOR = _load()


def test_files_match_the_reducer() -> None:
    assert _GENERATOR.drift() == []


def test_there_are_cases() -> None:
    assert len(_GENERATOR.build()) >= 9


@pytest.mark.parametrize("name", sorted(_GENERATOR.build()))
def test_case_lane_is_the_reducers(name: str) -> None:
    document = json.loads((_GENERATOR.OUTPUT_DIR / name).read_text(encoding="utf-8"))
    assert document["expected"] == _GENERATOR._expected(document["log"])
