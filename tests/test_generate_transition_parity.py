"""Unit tests for the core-independent parts of the parity generator."""

from __future__ import annotations

import importlib.util
import json
import sys
from datetime import datetime
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
    transition_error = type("TransitionError", (Exception,), {})
    refusal = module.map_exception_to_refusal(
        transition_error("Illegal transition planned -> done"), [source]
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
    transition_error = type("TransitionError", (Exception,), {})
    with pytest.raises(module.GenerationError, match="No known refusal reason"):
        module.map_exception_to_refusal(transition_error("new core behavior"), [])


def test_exception_mapping_rejects_non_core_exceptions() -> None:
    module = _generator()
    with pytest.raises(module.GenerationError, match="Unsupported core exception type"):
        module.map_exception_to_refusal(ValueError("Illegal transition"), [])


def test_core_arguments_must_be_derivable_from_rows() -> None:
    module = _generator()
    with pytest.raises(module.GenerationError, match="implementation_evidence_present"):
        module.tr("for_review", implementation_evidence_present=False)
    with pytest.raises(module.GenerationError, match="operator_force_note"):
        module.start_impl(operator_force_note="operator")
    with pytest.raises(module.GenerationError, match="review_lane_exit"):
        module.start_impl(review_lane_exit=True)


def test_core_python_comes_from_core_checkout(tmp_path: Path) -> None:
    module = _generator()
    (tmp_path / ".python-version").write_text("3.11.15\n", encoding="utf-8")
    assert module.core_python(tmp_path) == (3, 11)


def test_mismatching_core_interpreter_exits_two(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    module = _generator()
    (tmp_path / ".python-version").write_text("3.11.15\n", encoding="utf-8")
    monkeypatch.setenv("SPEC_KITTY_CORE_CHECKOUT", str(tmp_path))
    monkeypatch.setattr(module.sys, "version_info", (3, 12, 0))
    with pytest.raises(SystemExit) as raised:
        module.main([])
    assert raised.value.code == 2


def test_clock_ahead_approval_is_later_than_departure() -> None:
    case_path = (
        Path(__file__).parents[1]
        / "src/spec_kitty_events/conformance/status_transition_parity/cases"
        / "batch-clock-ahead-approved-departure.json"
    )
    case = json.loads(case_path.read_text(encoding="utf-8"))
    approval = next(row for row in case["log"] if row["to_lane"] == "approved")
    requests = [row for unit in case["request"]["units"] for row in unit["rows"]]
    departure = next(row for row in requests if row["from_lane"] == "approved")
    assert datetime.fromisoformat(approval["at"]) > datetime.fromisoformat(departure["at"])


def test_refused_planned_start_uses_two_unit_r13_shape() -> None:
    case_path = (
        Path(__file__).parents[1]
        / "src/spec_kitty_events/conformance/status_transition_parity/cases"
        / "dependency-start-planned-two-units-refused.json"
    )
    case = json.loads(case_path.read_text(encoding="utf-8"))
    units = case["request"]["units"]
    assert case["request"]["kind"] == "batch"
    assert case["expected"]["unit"] == 0
    assert [unit["operation"] for unit in units] == ["start_implementation"] * 2
    assert {unit["wpId"] for unit in units} == {"WP01"}
    assert [(unit["rows"][0]["from_lane"], unit["rows"][0]["to_lane"]) for unit in units] == [
        ("planned", "claimed"),
        ("claimed", "in_progress"),
    ]


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
