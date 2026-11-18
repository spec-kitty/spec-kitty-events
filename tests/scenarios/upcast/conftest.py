"""Step definitions for the ``upcast/`` area (pytest-bdd, the reference binding).

The steps name a group of the upcast corpus (``producer_contract/fixtures/upcast/cases/``) and
check each case's result against what that case pins (``expected``). They never carry a line or
a golden, so a new case in a covered group is picked up without touching a feature file. The
Java binding binds the same step texts in
``java/src/test/java/ai/speckitty/events/cloudevents/UpcastSteps.java``; keep the two, and
``README.md``'s available-steps table, in step.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from importlib.resources import files
from pathlib import Path

from pytest_bdd import given, parsers, then, when

from spec_kitty_events.cloudevents import (
    IssueCode,
    UpcastContext,
    UpcastError,
    UpcastResult,
    canonical_text,
    upcast_line,
    validate_cloudevent,
)
from tests.support.upcast_corpus import UPCAST_CASES_ROOT, UpcastCase, list_upcast_cases

OUTCOMES = ("mapped", "passthrough")


@dataclass(frozen=True)
class Upcast:
    case: UpcastCase
    result: UpcastResult


@dataclass(frozen=True)
class StrictUse:
    case: UpcastCase
    raised: str | None  # the code strict use raised, or None when it upcast the row


def _context(case: UpcastCase) -> UpcastContext:
    return UpcastContext(**case.context)


@given("the upcast corpus shipped with the package")
def packaged_corpus() -> None:
    cases = files("spec_kitty_events.producer_contract").joinpath("fixtures", "upcast", "cases")
    assert cases.is_dir(), "spec_kitty_events.producer_contract ships no upcast corpus"
    assert Path(str(cases)) == UPCAST_CASES_ROOT


@given(parsers.parse('every case of the group "{group:w}"'), target_fixture="cases")
def every_case_of(group: str) -> list[UpcastCase]:
    selected = list_upcast_cases(group)
    assert selected, f"the corpus group {group!r} holds no case"
    return selected


@given(
    parsers.parse('every case of the group "{group:w}" pinned as "{outcome:w}"'),
    target_fixture="cases",
)
def every_case_pinned_as(group: str, outcome: str) -> list[UpcastCase]:
    assert outcome in OUTCOMES, f"{outcome!r} is not an upcast outcome: {OUTCOMES}"
    selected = list_upcast_cases(group, outcome)
    assert selected, f"no case of the corpus group {group!r} is pinned as {outcome!r}"
    return selected


@when("each case's line is upcast with its caller context", target_fixture="upcasts")
def upcast_each_line(cases: list[UpcastCase]) -> list[Upcast]:
    return [Upcast(case, upcast_line(case.line, _context(case))) for case in cases]


@when("each case's golden is upcast again with its caller context", target_fixture="upcasts")
def upcast_each_golden(cases: list[UpcastCase]) -> list[Upcast]:
    upcasts = []
    for case in cases:
        golden = case.expected["golden"]
        assert isinstance(golden, str), f"{case.rel_path} pins no golden"
        upcasts.append(Upcast(case, upcast_line(golden, _context(case))))
    return upcasts


@when("each case's line is upcast in strict use", target_fixture="strict_uses")
def upcast_each_line_strictly(cases: list[UpcastCase]) -> list[StrictUse]:
    uses = []
    for case in cases:
        try:
            upcast_line(case.line, _context(case), strict=True)
        except UpcastError as error:
            uses.append(StrictUse(case, error.code))
        else:
            uses.append(StrictUse(case, None))
    return uses


@then("every result has the outcome, form and codes its case pins")
def outcome_form_and_codes(upcasts: list[Upcast]) -> None:
    for upcast in upcasts:
        expected, result = upcast.case.expected, upcast.result
        pinned = (expected["outcome"], expected["form"], expected["codes"])
        actual = (result.outcome, result.form, list(result.codes))
        assert actual == pinned, f"{upcast.case.rel_path}: got {actual}, pins {pinned}"
        assert result.original_line == upcast.case.line.encode("utf-8"), (
            f"{upcast.case.rel_path}: the original line bytes changed"
        )


@then("every mapped row's canonical bytes equal its golden")
def canonical_bytes_equal_golden(upcasts: list[Upcast]) -> None:
    for upcast in upcasts:
        assert upcast.result.mapped, f"{upcast.case.rel_path} was not mapped"
        assert canonical_text(upcast.result.row) == upcast.case.expected["golden"], (
            f"{upcast.case.rel_path}: the mapped row's canonical bytes differ from the golden"
        )


@then("every golden's read-profile validity is the one its case pins")
def golden_validity_as_pinned(upcasts: list[Upcast]) -> None:
    for upcast in upcasts:
        expected = upcast.case.expected
        valid = validate_cloudevent(json.loads(expected["golden"]), profile="read") == []
        assert valid is expected["valid"], (
            f"{upcast.case.rel_path}: read-profile valid is {valid}, pins {expected['valid']}"
        )


@then("every passed-through line is left as it was")
def passed_through_unchanged(upcasts: list[Upcast]) -> None:
    for upcast in upcasts:
        case, result = upcast.case, upcast.result
        assert not result.mapped, f"{case.rel_path} was mapped"
        assert case.expected["golden"] is None and case.expected["valid"] is None, (
            f"{case.rel_path} pins a golden for a pass-through"
        )
        if result.row is not None:
            assert canonical_text(result.row) == canonical_text(json.loads(case.line)), (
                f"{case.rel_path}: the passed-through row was rewritten"
            )


@then("every result is a pass-through of its golden with the same bytes")
def golden_passes_through(upcasts: list[Upcast]) -> None:
    for upcast in upcasts:
        result = upcast.result
        assert result.outcome == "passthrough", f"{upcast.case.rel_path}: {result.outcome}"
        assert list(result.codes) == [IssueCode.ALREADY_CLOUDEVENT], (
            f"{upcast.case.rel_path}: {list(result.codes)}"
        )
        assert canonical_text(result.row) == upcast.case.expected["golden"], (
            f"{upcast.case.rel_path}: reading the golden again changed its bytes"
        )


@then("strict use refuses every case with the first code it pins and upcasts every other case")
def strict_use_as_pinned(strict_uses: list[StrictUse]) -> None:
    for use in strict_uses:
        codes = use.case.expected["codes"]
        refuses = bool(codes) and codes[0] != IssueCode.ALREADY_CLOUDEVENT
        expected = codes[0] if refuses else None
        assert use.raised == expected, (
            f"{use.case.rel_path}: strict use raised {use.raised}, expected {expected}"
        )
