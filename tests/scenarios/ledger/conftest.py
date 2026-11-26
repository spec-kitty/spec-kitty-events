"""Step definitions for the ``ledger/`` area (pytest-bdd, the reference binding).

The steps select ``ledger_append`` write-contract documents
(``spec_kitty_events/conformance/fixtures/ledger_append/``, normative rules in its
``README.md``) and check each append against what its document pins: the exact
``expected_line``, or the ``expected_error`` refusal class with the ledger left as it was. They
never carry ledger data, so a new document is picked up without touching a feature file. The
step texts shared with ``envelope/`` keep their wording; ``expected_error`` plays the part of
the pinned error code. Building and running one append is the test-only
``tests/support/ledger_append.py`` (not package API, C-007). Keep ``README.md``'s
available-steps table in step with this module.
"""

from __future__ import annotations

from importlib.resources import files
from pathlib import Path
from typing import Any

from pytest_bdd import given, parsers, then, when

from spec_kitty_events.conformance.loader import load_ledger_append_cases
from tests.support.class_taxonomy import FIXTURES_ROOT
from tests.support.ledger_append import AppendOutcome, append_case

# The manifest's fixture_type for the write-contract documents: the class the steps name.
LEDGER_APPEND = "ledger_append"


def _selected(fixture_class: str) -> list[dict[str, Any]]:
    assert fixture_class == LEDGER_APPEND, (
        f"the ledger/ steps know the class {LEDGER_APPEND!r}, not {fixture_class!r}"
    )
    selected = load_ledger_append_cases()
    assert selected, f"the fixture class {fixture_class!r} holds no fixture"
    for case in selected:
        # A torn tail is a non-empty ledger whose last byte is not LF (PR-EVENTS-LEDGER-003):
        # checked here, the one path every selection step loads through (#280). The packaged
        # runner repeats the check (conformance/test_ledger_append.py), for consumers.
        if case.get("expected_error") == "LedgerTornTailError":
            existing = case.get("existing_ledger")
            assert existing and not existing.endswith("\n"), (
                f"{case['id']} pins LedgerTornTailError over {existing!r}, not a torn ledger"
            )
    return selected


def _non_empty(selected: list[dict[str, Any]], what: str) -> list[dict[str, Any]]:
    assert selected, f"no {LEDGER_APPEND!r} fixture {what}"
    return selected


@given("the conformance fixtures shipped with the package")
def packaged_fixtures() -> None:
    folder = files("spec_kitty_events.conformance").joinpath("fixtures", LEDGER_APPEND)
    assert folder.is_dir(), "spec_kitty_events.conformance ships no ledger_append fixtures"
    assert Path(str(folder)) == FIXTURES_ROOT / LEDGER_APPEND


@given(
    parsers.parse('every fixture of the class "{fixture_class:w}" that pins acceptance'),
    target_fixture="cases",
)
def every_fixture_pinning_acceptance(fixture_class: str) -> list[dict[str, Any]]:
    return _non_empty(
        [c for c in _selected(fixture_class) if c["expected_result"] == "valid"],
        "pins acceptance",
    )


@given(
    parsers.parse('every fixture of the class "{fixture_class:w}" that pins a refusal'),
    target_fixture="cases",
)
def every_fixture_pinning_refusal(fixture_class: str) -> list[dict[str, Any]]:
    return _non_empty(
        [c for c in _selected(fixture_class) if c["expected_result"] == "invalid"],
        "pins a refusal",
    )


@given(
    parsers.parse(
        'every fixture of the class "{fixture_class:w}" that pins the error code "{code:w}"'
    ),
    target_fixture="cases",
)
def every_fixture_pinning(fixture_class: str, code: str) -> list[dict[str, Any]]:
    return _non_empty(
        [c for c in _selected(fixture_class) if c.get("expected_error") == code],
        f"pins the error code {code!r}",
    )


@given(
    parsers.parse('every fixture of the class "{fixture_class:w}" that sets a field to NaN'),
    target_fixture="cases",
)
def every_fixture_setting_nan(fixture_class: str) -> list[dict[str, Any]]:
    selected = _non_empty(
        [c for c in _selected(fixture_class) if "nan_field" in c], "sets a field to NaN"
    )
    for case in selected:
        assert case["nan_field"] in case["input"], (
            f"{case['id']}: nan_field {case['nan_field']!r} names no top-level input key"
        )
    return selected


@when("each fixture's input is appended to its ledger", target_fixture="outcomes")
def appended_to_ledger(cases: list[dict[str, Any]], tmp_path: Path) -> list[AppendOutcome]:
    return [append_case(case, tmp_path) for case in cases]


@then("every fixture is accepted")
def every_fixture_accepted(outcomes: list[AppendOutcome]) -> None:
    for outcome in outcomes:
        case_id = outcome.case["id"]
        assert outcome.case["expected_result"] == "valid", f"{case_id} does not pin acceptance"
        assert outcome.refusal is None, f"{case_id} was refused with {outcome.refusal}"


@then("every fixture is refused with the error code it pins")
def every_fixture_refused(outcomes: list[AppendOutcome]) -> None:
    for outcome in outcomes:
        case_id = outcome.case["id"]
        pinned = outcome.case.get("expected_error")
        assert outcome.case["expected_result"] == "invalid", f"{case_id} does not pin a refusal"
        assert pinned, f"{case_id} pins no error code"
        assert outcome.refusal is not None, f"{case_id} was accepted"
        assert outcome.refusal == pinned, (
            f"{case_id}: refused with {outcome.refusal}, pins {pinned}"
        )


@then("every ledger holds its prior bytes followed by exactly the line its fixture pins")
def every_ledger_framed(outcomes: list[AppendOutcome]) -> None:
    for outcome in outcomes:
        case_id = outcome.case["id"]
        line = outcome.case["expected_line"]
        assert line.endswith("\n") and line.count("\n") == 1, (
            f"{case_id} pins {line!r}, not one LF-terminated line"
        )
        expected = (outcome.prior or b"") + line.encode("ascii")
        assert outcome.after == expected, (
            f"{case_id}: the ledger holds {outcome.after!r}, expected {expected!r}"
        )


@then("every ledger is left byte-identical to its prior state")
def every_ledger_untouched(outcomes: list[AppendOutcome]) -> None:
    for outcome in outcomes:
        case_id = outcome.case["id"]
        if outcome.prior is None:
            assert outcome.after is None, f"{case_id}: a refused append created the ledger"
        else:
            assert outcome.after == outcome.prior, (
                f"{case_id}: a refused append changed the ledger to {outcome.after!r}"
            )
