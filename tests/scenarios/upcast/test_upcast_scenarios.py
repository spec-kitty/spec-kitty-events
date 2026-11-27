"""Binds every ``upcast/`` feature to the pytest-bdd steps in ``conftest.py``.

The guards below keep the binding whole (#287): every corpus group is bound in each behaviour
that has cases for it, and the strict-use exemption for ``ALREADY_CLOUDEVENT`` stays exercised,
so deleting it from either binding's Then step fails a scenario. Each Examples row is its own
scenario, so no step can see the others; the guard reads the feature files instead, through the
scenario contract's own parser (outline rows expanded, as the runners see them).
"""

from __future__ import annotations

import functools
import importlib.util
import re
import sys
from pathlib import Path
from types import ModuleType

from pytest_bdd import scenarios

from spec_kitty_events.cloudevents import IssueCode
from tests.support.upcast_corpus import UPCAST_CASES_ROOT, list_upcast_cases

scenarios(".")

_AREA = Path(__file__).resolve().parent
_CONTRACT = _AREA.parents[2] / "scripts" / "ci" / "scenario_contract.py"
_GROUP = re.compile(
    r'^every case of the group "(?P<group>[^"]+)"(?: pinned as "(?P<outcome>[^"]+)")?$'
)


@functools.cache
def _contract() -> ModuleType:
    spec = importlib.util.spec_from_file_location("_upcast_guard_scenario_contract", _CONTRACT)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module  # dataclasses resolve their module through sys.modules
    spec.loader.exec_module(module)
    return module


def _bound(behaviour: str) -> set[tuple[str, str | None]]:
    """The (group, outcome) selections the gating scenarios of one behaviour make."""
    contract = _contract()
    found, problems = contract.parse_feature(_AREA / behaviour / f"{behaviour}.feature")
    assert not problems, problems
    bound: set[tuple[str, str | None]] = set()
    for scenario in found:
        if not contract.is_gating(scenario):
            continue
        for step in scenario.steps:
            match = _GROUP.match(step.text)
            if match:
                bound.add((match["group"], match["outcome"]))
    return bound


def _groups() -> set[str]:
    return {p.name for p in UPCAST_CASES_ROOT.iterdir() if p.is_dir()}


def _pinned(outcome: str) -> set[str]:
    return {g for g in _groups() if list_upcast_cases(g, outcome)}


def test_every_group_with_mapped_cases_is_bound() -> None:
    bound = {g for g, o in _bound("mapped-rows") if o == "mapped"}
    assert bound == _pinned("mapped"), f"mapped-rows binds {sorted(bound)}"


def test_every_group_with_passthrough_cases_is_bound() -> None:
    bound = {g for g, o in _bound("passthrough-rows") if o == "passthrough"}
    assert bound == _pinned("passthrough"), f"passthrough-rows binds {sorted(bound)}"


def test_every_group_with_goldens_is_read_again() -> None:
    bound = {g for g, o in _bound("golden-idempotence") if o == "mapped"}
    assert bound == _pinned("mapped"), f"golden-idempotence binds {sorted(bound)}"


def test_strict_use_binds_every_group_and_runs_the_exemption() -> None:
    bound = {g for g, o in _bound("strict-use") if o is None}
    assert bound == _groups(), f"strict-use binds {sorted(bound)}"
    exempt = [
        case.rel_path
        for group in sorted(bound)
        for case in list_upcast_cases(group)
        if case.expected["codes"][:1] == [IssueCode.ALREADY_CLOUDEVENT]
    ]
    assert exempt, (
        "no strict-use case pins ALREADY_CLOUDEVENT first: the exemption in the Then step "
        "would be dead code in both bindings"
    )
