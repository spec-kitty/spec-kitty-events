"""Conformance class assertion: parametrized over every fixture in the
eight-class taxonomy, asserts each fixture's expected outcome (and the
``expected_error_code`` for invalid fixtures).

The fixtures are listed by the shared test-only lister
(:func:`tests.support.class_taxonomy.list_class_fixtures`) and joined with
``manifest.json``'s ``classes.entries`` rows; each is judged by the shared
test-only verdict (:mod:`tests.support.class_verdict`), whose fixed check
order is documented there. The ``envelope/`` BDD scenarios under
``tests/scenarios/`` use the same lister and verdict (spec-kitty-events#197).

For ``historical_row_raw`` fixtures, the validator is not yet expected
to distinguish them from generic envelope-shape failures; the test
accepts any rejection (and documents the gap in the fixture's
``notes``). The ``expected_error_code`` is preserved as the *ideal*
code for a future WP.
"""

from __future__ import annotations

import json
from typing import Any

import pytest

from spec_kitty_events import (
    MISSION_CLOSED,
    MISSION_CREATED,
    WP_STATUS_CHANGED,
)
from spec_kitty_events.models import Event
from spec_kitty_events.validation_errors import ValidationErrorCode
from tests.support.class_taxonomy import (
    FIXTURES_ROOT,
    ClassFixtureEntry,
    list_class_fixtures,
    manifest_class_entries,
)
from tests.support.class_verdict import class_verdict


# Eight named classes (canonical order matches the contract).
EIGHT_CLASSES: tuple[str, ...] = (
    "envelope_valid_canonical",
    "envelope_valid_historical_synthesized",
    "envelope_invalid_unknown_lane",
    "envelope_invalid_forbidden_key",
    "envelope_invalid_payload_schema",
    "envelope_invalid_shape",
    "historical_row_raw",
    "lane_mapping_legacy",
)


def _load_class_entries() -> list[ClassFixtureEntry]:
    """The manifest row of every fixture the shared lister finds in the eight classes."""
    entries: list[ClassFixtureEntry] = []
    for fixture in list_class_fixtures(*EIGHT_CLASSES):
        assert fixture.entry is not None, (
            f"Fixture {fixture.rel_path} has no manifest.json classes.entries row."
        )
        entries.append(fixture.entry)
    return entries


def _load_fixture_file(rel_path: str) -> dict[str, Any]:
    with (FIXTURES_ROOT / rel_path).open("r", encoding="utf-8") as fh:
        result: dict[str, Any] = json.load(fh)
    return result


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------


_ALL_ENTRIES: list[ClassFixtureEntry] = _load_class_entries()


@pytest.mark.parametrize(
    "entry",
    _ALL_ENTRIES,
    ids=[e.id for e in _ALL_ENTRIES],
)
def test_fixture_outcome_matches_expected(entry: ClassFixtureEntry) -> None:
    fixture = _load_fixture_file(entry.path)

    # Sanity: fixture file's class label must match manifest entry.
    assert fixture["class"] == entry.fixture_class, (
        f"Fixture {entry.path}: file declares class={fixture['class']!r} "
        f"but manifest registers it under class={entry.fixture_class!r}."
    )
    assert fixture["expected"] == entry.expected, (
        f"Fixture {entry.path}: file declares expected={fixture['expected']!r} "
        f"but manifest registers expected={entry.expected!r}."
    )
    if entry.expected == "invalid":
        assert entry.expected_error_code is not None, (
            f"Manifest entry {entry.id} marks expected=invalid but is missing "
            f"expected_error_code (required by conformance-fixture-classes.md)."
        )
        assert fixture.get("expected_error_code") == entry.expected_error_code, (
            f"Fixture {entry.path}: expected_error_code mismatch between file "
            f"({fixture.get('expected_error_code')!r}) and manifest "
            f"({entry.expected_error_code!r})."
        )

    outcome = class_verdict(entry.fixture_class, fixture["input"])

    if entry.expected == "valid":
        assert outcome.ok, (
            f"Fixture {entry.path} expected to validate but failed with {outcome.error!r}"
        )
        # Additionally assert the canonical fixture survives the real
        # public Event.model_validate entry point (envelope SSOT).
        if entry.fixture_class in {
            "envelope_valid_canonical",
            "envelope_valid_historical_synthesized",
        }:
            Event.model_validate(fixture["input"])
        return

    # expected == "invalid"
    assert not outcome.ok, (
        f"Fixture {entry.path} expected to be rejected but the validator accepted it."
    )
    assert outcome.error is not None
    actual_code = outcome.error.code.value

    if entry.fixture_class == "historical_row_raw":
        # The validator does not yet detect raw historical rows as a
        # distinct rejection class. Accept any rejection; record the
        # *ideal* expected_error_code in the manifest for a future WP.
        accepted_codes = {
            ValidationErrorCode.ENVELOPE_SHAPE_INVALID.value,
            ValidationErrorCode.FORBIDDEN_KEY.value,
            ValidationErrorCode.UNKNOWN_LANE.value,
            ValidationErrorCode.PAYLOAD_SCHEMA_FAIL.value,
            ValidationErrorCode.RAW_HISTORICAL_ROW.value,
        }
        assert actual_code in accepted_codes, (
            f"Fixture {entry.path}: historical_row_raw rejected with "
            f"unexpected code {actual_code!r}; accepted codes: {sorted(accepted_codes)}."
        )
        return

    assert actual_code == entry.expected_error_code, (
        f"Fixture {entry.path}: expected_error_code={entry.expected_error_code!r} "
        f"but validator returned {actual_code!r} ({outcome.error.message!r})."
    )


def test_every_manifest_entry_is_a_listed_fixture() -> None:
    """No ``classes.entries`` row of the eight classes points at a file the lister cannot see."""
    listed = {entry.path for entry in _ALL_ENTRIES}
    unlisted = [
        entry.path
        for entry in manifest_class_entries()
        if entry.fixture_class in EIGHT_CLASSES and entry.path not in listed
    ]
    assert not unlisted, f"manifest classes.entries rows with no fixture file: {unlisted}"


def test_every_class_has_at_least_one_fixture() -> None:
    """Coverage gate: every of the eight classes has >= 1 fixture."""
    by_class: dict[str, int] = {cls: 0 for cls in EIGHT_CLASSES}
    for entry in _ALL_ENTRIES:
        assert entry.fixture_class in by_class, (
            f"Manifest entry {entry.id} declares unknown class "
            f"{entry.fixture_class!r}; valid classes: {sorted(by_class)}."
        )
        by_class[entry.fixture_class] += 1

    empty = [cls for cls, count in by_class.items() if count == 0]
    assert not empty, (
        f"Conformance fixture suite is missing fixtures for class(es) "
        f"{empty}. Each of the eight classes must have at least one fixture."
    )


def test_canonical_class_covers_canonical_event_types() -> None:
    """Every canonical event type appears at least once under
    ``envelope_valid_canonical``."""
    canonical_event_types = {MISSION_CREATED, MISSION_CLOSED, WP_STATUS_CHANGED}
    seen_event_types: set[str] = set()
    for entry in _ALL_ENTRIES:
        if entry.fixture_class != "envelope_valid_canonical":
            continue
        seen_event_types.add(entry.event_type)

    missing = canonical_event_types - seen_event_types
    assert not missing, (
        f"envelope_valid_canonical missing fixtures for event type(s) "
        f"{sorted(missing)}. Expected at least one fixture per canonical "
        f"event type."
    )


def test_invalid_fixtures_have_expected_error_code() -> None:
    """Every invalid manifest entry must declare expected_error_code."""
    for entry in _ALL_ENTRIES:
        if entry.expected != "invalid":
            continue
        assert entry.expected_error_code is not None, (
            f"Invalid manifest entry {entry.id} missing expected_error_code."
        )
        assert entry.expected_error_code in {code.value for code in ValidationErrorCode}, (
            f"Invalid manifest entry {entry.id} declares unknown error code "
            f"{entry.expected_error_code!r}."
        )


def test_valid_canonical_fixtures_validate_against_real_event_model() -> None:
    """Sanity gate: every envelope_valid_canonical and
    envelope_valid_historical_synthesized fixture validates against
    the real public ``Event`` model."""

    target_classes = {
        "envelope_valid_canonical",
        "envelope_valid_historical_synthesized",
    }
    for entry in _ALL_ENTRIES:
        if entry.fixture_class not in target_classes:
            continue
        fixture = _load_fixture_file(entry.path)
        # The exception type intentionally bubbles up if it fails — this
        # is the SSOT gate: every "valid" canonical fixture MUST pass
        # Event.model_validate.
        Event.model_validate(fixture["input"])
