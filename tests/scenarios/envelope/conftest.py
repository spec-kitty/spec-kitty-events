"""Step definitions for the ``envelope/`` area (pytest-bdd, the reference binding).

The steps name a fixture class of ``spec_kitty_events/conformance/fixtures/class_taxonomy/``
and check each fixture's verdict against what that fixture pins (``expected``,
``expected_error_code`` or ``expected_error_codes``). They never carry envelope data, so a new
fixture in a covered class is picked up without touching a feature file. The listing and the
eight-class verdict are the test-only helpers in ``tests/support/`` (not package API, C-007).
Keep ``README.md``'s available-steps table in step with this module.
"""

from __future__ import annotations

from dataclasses import dataclass
from importlib.resources import files
from pathlib import Path
from typing import Any

from pytest_bdd import given, parsers, then, when

from spec_kitty_events.strict import validate_strict_envelope
from tests.support.class_taxonomy import (
    CLASS_TAXONOMY_DIR,
    FIXTURES_ROOT,
    ClassFixture,
    list_class_fixtures,
)
from tests.support.class_verdict import class_verdict


@dataclass(frozen=True)
class Verdict:
    fixture: ClassFixture
    pinned: dict[str, Any]
    accepted: bool
    codes: tuple[str, ...]

    @property
    def pinned_codes(self) -> tuple[str, ...]:
        if "expected_error_codes" in self.pinned:
            return tuple(self.pinned["expected_error_codes"])
        code = self.pinned.get("expected_error_code")
        return (code,) if code is not None else ()


def _selected(fixture_class: str) -> list[ClassFixture]:
    selected = list_class_fixtures(fixture_class)
    assert selected, f"the fixture class {fixture_class!r} holds no fixture"
    return selected


@given("the conformance fixtures shipped with the package")
def packaged_fixtures() -> None:
    # The lister reads FIXTURES_ROOT, which is resolved through the installed package.
    taxonomy = files("spec_kitty_events.conformance").joinpath("fixtures", "class_taxonomy")
    assert taxonomy.is_dir(), "spec_kitty_events.conformance ships no class_taxonomy fixtures"
    assert Path(str(taxonomy)) == FIXTURES_ROOT / CLASS_TAXONOMY_DIR


@given(
    parsers.parse('every fixture of the class "{fixture_class:w}"'),
    target_fixture="fixtures",
)
def every_fixture_of(fixture_class: str) -> list[ClassFixture]:
    return _selected(fixture_class)


@given(
    parsers.parse(
        'every fixture of the class "{fixture_class:w}" that pins the error code "{code:w}"'
    ),
    target_fixture="fixtures",
)
def every_fixture_pinning(fixture_class: str, code: str) -> list[ClassFixture]:
    selected = []
    for fixture in _selected(fixture_class):
        pinned = fixture.load()
        if code in pinned.get("expected_error_codes", [pinned.get("expected_error_code")]):
            selected.append(fixture)
    assert selected, f"no fixture of the class {fixture_class!r} pins the error code {code!r}"
    return selected


def _judge(fixtures: list[ClassFixture], judge: Any) -> list[Verdict]:
    verdicts = []
    for fixture in fixtures:
        pinned = fixture.load()
        accepted, codes = judge(fixture, pinned["input"])
        verdicts.append(Verdict(fixture, pinned, accepted, tuple(codes)))
    return verdicts


def _eight_class(fixture: ClassFixture, input_obj: Any) -> tuple[bool, list[str]]:
    outcome = class_verdict(fixture.fixture_class, input_obj)
    return outcome.ok, [] if outcome.error is None else [outcome.error.code.value]


def _strict(_fixture: ClassFixture, input_obj: Any) -> tuple[bool, list[str]]:
    codes = [error.code.value for error in validate_strict_envelope(input_obj)]
    return not codes, codes


@when("each fixture's input is judged as an envelope", target_fixture="verdicts")
def judged_as_envelope(fixtures: list[ClassFixture]) -> list[Verdict]:
    return _judge(fixtures, _eight_class)


@when(
    "each fixture's input is judged against the strict journal profile",
    target_fixture="verdicts",
)
def judged_against_strict_profile(fixtures: list[ClassFixture]) -> list[Verdict]:
    return _judge(fixtures, _strict)


@when("each fixture's input is resolved as a legacy lane mapping", target_fixture="verdicts")
def resolved_as_lane_mapping(fixtures: list[ClassFixture]) -> list[Verdict]:
    for fixture in fixtures:
        assert fixture.fixture_class == "lane_mapping_legacy", fixture.rel_path
    return _judge(fixtures, _eight_class)


@then("every fixture is accepted")
def every_fixture_accepted(verdicts: list[Verdict]) -> None:
    for verdict in verdicts:
        assert verdict.pinned["expected"] == "valid", (
            f"{verdict.fixture.rel_path} does not pin acceptance"
        )
        assert verdict.accepted, (
            f"{verdict.fixture.rel_path} was refused with {list(verdict.codes)}"
        )


@then("every fixture is refused with the error code it pins")
def every_fixture_refused(verdicts: list[Verdict]) -> None:
    for verdict in verdicts:
        assert verdict.pinned["expected"] == "invalid", (
            f"{verdict.fixture.rel_path} does not pin a refusal"
        )
        assert verdict.pinned_codes, f"{verdict.fixture.rel_path} pins no error code"
        assert not verdict.accepted, f"{verdict.fixture.rel_path} was accepted"
        assert verdict.codes == verdict.pinned_codes, (
            f"{verdict.fixture.rel_path}: refused with {list(verdict.codes)}, "
            f"pins {list(verdict.pinned_codes)}"
        )


@then("every verdict is the one its fixture pins")
def every_verdict_as_pinned(verdicts: list[Verdict]) -> None:
    for verdict in verdicts:
        if verdict.pinned["expected"] == "valid":
            every_fixture_accepted([verdict])
        else:
            every_fixture_refused([verdict])
