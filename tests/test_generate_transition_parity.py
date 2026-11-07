"""Unit tests for the core-independent parts of the parity generator."""

from __future__ import annotations

import importlib.util
import json
import sys
from pathlib import Path
from types import ModuleType

import pytest


def _generator() -> ModuleType:
    path = Path(__file__).parents[1] / "scripts/conformance/generate_transition_parity.py"
    spec = importlib.util.spec_from_file_location("generate_transition_parity", path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def test_exception_mapping_uses_core_source_texts() -> None:
    module = _generator()
    source = module.ReasonSource(
        "illegal_transition",
        "TRANSITION_REJECTED",
        "src/specify_cli/status/wp_state.py",
        ("Illegal transition",),
    )
    refusal = module.map_exception_to_refusal(
        Exception("Illegal transition planned -> done"), [source]
    )
    assert refusal == {
        "code": "TRANSITION_REJECTED",
        "reason": "urn:spec-kitty:write-refusal:illegal_transition",
        "message": "Illegal transition planned -> done",
    }


def test_exception_mapping_uses_lifecycle_exception_classes() -> None:
    module = _generator()
    claim = type("WorkPackageClaimConflict", (Exception,), {})
    start = type("WorkPackageStartRejected", (Exception,), {})
    sources = [
        module.ReasonSource("claim_held_by_other_actor", "WP_ALREADY_CLAIMED", "lifecycle.py", ()),
        module.ReasonSource("start_rejected", "TRANSITION_REJECTED", "lifecycle.py", ()),
    ]
    assert module.map_exception_to_refusal(claim("held"), sources)["code"] == "WP_ALREADY_CLAIMED"
    assert module.map_exception_to_refusal(start("wrong"), sources)["reason"].endswith(
        "start_rejected"
    )


def test_exception_mapping_fails_loudly_for_unknown_text() -> None:
    module = _generator()
    with pytest.raises(module.GenerationError, match="No known refusal reason"):
        module.map_exception_to_refusal(Exception("new core behavior"), [])


def test_check_diff_reports_content_missing_and_stale_core_cases(tmp_path: Path) -> None:
    module = _generator()
    case_dir = tmp_path / "cases"
    case_dir.mkdir()
    (case_dir / "same.json").write_text(json.dumps({"oracle": "core"}) + "\n", encoding="utf-8")
    (case_dir / "changed.json").write_text(
        json.dumps({"oracle": "core", "old": True}) + "\n", encoding="utf-8"
    )
    (case_dir / "stale.json").write_text(
        json.dumps({"id": "stale", "oracle": "core"}) + "\n", encoding="utf-8"
    )
    (case_dir / "protocol.json").write_text(
        json.dumps({"id": "protocol", "oracle": "protocol"}) + "\n", encoding="utf-8"
    )
    rendered = {
        "same": (case_dir / "same.json").read_text(encoding="utf-8"),
        "changed": "new\n",
        "missing": "missing\n",
    }
    assert module.diff_case_files(rendered, case_dir) == ["changed", "missing", "stale"]


def test_write_never_rewrites_or_removes_protocol_cases(tmp_path: Path) -> None:
    module = _generator()
    case_dir = tmp_path / "cases"
    case_dir.mkdir()
    protocol = case_dir / "protocol.json"
    protocol_text = json.dumps({"oracle": "protocol", "hand_written": True}) + "\n"
    protocol.write_text(protocol_text, encoding="utf-8")
    stale_core = case_dir / "stale-core.json"
    stale_core.write_text(json.dumps({"oracle": "core"}) + "\n", encoding="utf-8")
    module.write_case_files({"fresh": "generated\n"}, case_dir)
    assert protocol.read_text(encoding="utf-8") == protocol_text
    assert not stale_core.exists()
    assert (case_dir / "fresh.json").read_text(encoding="utf-8") == "generated\n"
