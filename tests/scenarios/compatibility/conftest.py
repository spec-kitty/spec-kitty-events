"""Step definitions for the ``compatibility/`` area (pytest-bdd only).

The steps name a directory of ``spec_kitty_events/conformance/fixtures/cutover_boundary/`` and
check each fixture against what pins it: its ``manifest.json`` row (``event_type``,
``expected_result``) and, for the strict side, the fixture's own ``expected_error_codes``. They
never carry envelope data. The listing is the test-only ``tests/support/cutover_boundary.py``
(not package API, C-007). Keep ``README.md``'s available-steps table in step with this module.
"""

from __future__ import annotations

from dataclasses import dataclass
from importlib.resources import files
from pathlib import Path
from typing import Any

from pytest_bdd import given, parsers, then, when

from spec_kitty_events.conformance import validate_event
from spec_kitty_events.strict import validate_strict_envelope
from tests.support.class_taxonomy import FIXTURES_ROOT
from tests.support.cutover_boundary import (
    CUTOVER_BOUNDARY_DIR,
    BoundaryFixture,
    list_boundary_fixtures,
)


@dataclass(frozen=True)
class Judgement:
    fixture: BoundaryFixture
    pinned: dict[str, Any]
    accepted: bool
    codes: tuple[str, ...]


@given("the cutover-boundary fixtures shipped with the package")
def packaged_boundary_fixtures() -> None:
    boundary = files("spec_kitty_events.conformance").joinpath("fixtures", CUTOVER_BOUNDARY_DIR)
    assert boundary.is_dir(), "spec_kitty_events.conformance ships no cutover_boundary fixtures"
    assert Path(str(boundary)) == FIXTURES_ROOT / CUTOVER_BOUNDARY_DIR


@given(
    parsers.parse('every cutover-boundary fixture under "{directory:w}"'),
    target_fixture="fixtures",
)
def every_boundary_fixture(directory: str) -> list[BoundaryFixture]:
    selected = list_boundary_fixtures(directory)
    assert selected, f"cutover_boundary/{directory}/ holds no fixture"
    return selected


@when(
    "each fixture's envelope is validated as the event type its manifest row names",
    target_fixture="judgements",
)
def validated_as_event_type(fixtures: list[BoundaryFixture]) -> list[Judgement]:
    judgements = []
    for fixture in fixtures:
        envelope = fixture.load()
        result = validate_event(envelope, fixture.event_type)
        violations = [*result.model_violations, *result.schema_violations]
        codes = tuple(str(v) for v in violations)
        judgements.append(Judgement(fixture, envelope, result.valid, codes))
    return judgements


@when(
    "each fixture's input is judged against the strict journal profile",
    target_fixture="judgements",
)
def judged_against_strict_profile(fixtures: list[BoundaryFixture]) -> list[Judgement]:
    judgements = []
    for fixture in fixtures:
        pinned = fixture.load()
        codes = tuple(error.code.value for error in validate_strict_envelope(pinned["input"]))
        judgements.append(Judgement(fixture, pinned, not codes, codes))
    return judgements


@then("every fixture is accepted, as its manifest row pins")
def every_fixture_accepted(judgements: list[Judgement]) -> None:
    for judgement in judgements:
        assert judgement.fixture.expected_result == "valid", (
            f"{judgement.fixture.rel_path}: its manifest row does not pin acceptance"
        )
        assert judgement.accepted, (
            f"{judgement.fixture.rel_path} was refused: {list(judgement.codes)}"
        )


@then("every fixture is refused with exactly the error codes it pins")
def every_fixture_refused(judgements: list[Judgement]) -> None:
    for judgement in judgements:
        rel_path = judgement.fixture.rel_path
        assert judgement.fixture.expected_result == "invalid", (
            f"{rel_path}: its manifest row does not pin a refusal"
        )
        assert judgement.pinned["expected"] == "invalid", f"{rel_path} does not pin a refusal"
        pinned_codes = tuple(judgement.pinned["expected_error_codes"])
        assert pinned_codes, f"{rel_path} pins no error code"
        assert not judgement.accepted, f"{rel_path} was accepted"
        assert judgement.codes == pinned_codes, (
            f"{rel_path}: refused with {list(judgement.codes)}, pins {list(pinned_codes)}"
        )
