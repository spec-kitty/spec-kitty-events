"""Conformance tests for the ``lifecycle`` fixture category (#119).

Every valid fixture is accepted and every invalid one refused by
``validate_event``; the 18 lifecycle event types that had no invalid
fixture are each pinned (FR-008); and every ``status.Lane`` member appears
in some valid WPStatusChanged fixture (SC-004).
"""

from __future__ import annotations

import pytest

from spec_kitty_events.conformance import validate_event
from spec_kitty_events.conformance.loader import FixtureCase, load_fixtures
from spec_kitty_events.status import LANE_ALIASES, Lane

_CASES = load_fixtures("lifecycle")
_VALID = [c for c in _CASES if c.expected_valid]
_INVALID = [c for c in _CASES if not c.expected_valid]

_FR008_TYPES = frozenset(
    {
        "MissionCreated", "MissionStarted", "MissionClosed", "MissionCompleted",
        "MissionCancelled", "MissionOriginBound", "PhaseEntered", "ReviewRollback",
        "SpecifyStarted", "SpecifyCompleted", "PlanStarted", "PlanCompleted",
        "TasksStarted", "TasksCompleted", "WPAssigned", "HistoryAdded",
        "ErrorLogged", "DependencyResolved",
    }
)  # fmt: skip


@pytest.mark.parametrize("case", _VALID, ids=lambda c: c.id)
def test_valid_fixture_is_accepted(case: FixtureCase) -> None:
    assert validate_event(case.payload, case.event_type).valid


@pytest.mark.parametrize("case", _INVALID, ids=lambda c: c.id)
def test_invalid_fixture_is_refused(case: FixtureCase) -> None:
    result = validate_event(case.payload, case.event_type)
    assert not result.valid
    assert result.model_violations


@pytest.mark.parametrize("case", _INVALID, ids=lambda c: c.id)
def test_invalid_fixture_names_the_intended_violation(case: FixtureCase) -> None:
    result = validate_event(case.payload, case.event_type)
    fields = {v.field for v in result.model_violations}
    if "extra-key" in case.id:
        assert "unexpected_extra_key" in fields
    else:
        missing = case.id.rsplit("-missing-", 1)[1].removesuffix("-invalid").replace("-", "_")
        assert missing in fields
        assert missing not in case.payload


def test_every_fr008_type_has_a_missing_field_and_an_extra_key_fixture() -> None:
    by_type: dict[str, set[str]] = {}
    for case in _INVALID:
        kind = "extra" if "extra-key" in case.id else "missing"
        by_type.setdefault(case.event_type, set()).add(kind)
    assert set(by_type) == _FR008_TYPES
    assert all(kinds == {"extra", "missing"} for kinds in by_type.values())


def test_every_lane_appears_in_a_valid_wp_status_changed_fixture() -> None:
    seen: set[str] = set()
    for category in ("events", "edge_cases", "zeitgeist_attrs", "lifecycle"):
        for case in load_fixtures(category):
            if case.expected_valid and case.event_type == "WPStatusChanged":
                payload = case.payload.get("payload", case.payload)
                for key in ("from_lane", "to_lane"):
                    value = payload.get(key)
                    if value:
                        seen.add(
                            LANE_ALIASES[value].value
                            if value in LANE_ALIASES
                            else Lane(value).value
                        )
    assert seen == {lane.value for lane in Lane}
