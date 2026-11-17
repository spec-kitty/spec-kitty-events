"""The shared test-only class-taxonomy helpers (spec-kitty-events#197).

Pins the envelope verdict's check order and the fixture-class lister's behaviour. Both helpers
live in ``tests/support/`` and are shared by the conformance class test, the determinism audit,
the strict-journal class test and the ``envelope/`` scenarios.
"""

from __future__ import annotations

import copy
from typing import Any

import pytest

from spec_kitty_events.forbidden_keys import FORBIDDEN_LEGACY_KEYS
from tests.support.class_taxonomy import (
    FIXTURES_ROOT,
    list_class_fixtures,
    manifest_class_entries,
)
from tests.support.class_verdict import envelope_verdict


def _canonical_input() -> dict[str, Any]:
    fixture = list_class_fixtures("envelope_valid_canonical")[0]
    return copy.deepcopy(fixture.load()["input"])


def _code(envelope: Any) -> str | None:
    outcome = envelope_verdict(envelope)
    return None if outcome.error is None else outcome.error.code.value


FORBIDDEN_KEY = sorted(FORBIDDEN_LEGACY_KEYS)[0]


def test_the_canonical_input_is_accepted() -> None:
    assert _code(_canonical_input()) is None


def test_a_forbidden_key_alone_is_refused_as_forbidden_key() -> None:
    envelope = _canonical_input()
    envelope[FORBIDDEN_KEY] = "legacy"
    assert _code(envelope) == "FORBIDDEN_KEY"


def test_a_missing_required_field_with_a_forbidden_key_is_a_shape_failure() -> None:
    """Check order: required fields (2) are judged before forbidden keys (3)."""
    envelope = _canonical_input()
    del envelope["event_type"]
    envelope[FORBIDDEN_KEY] = "legacy"
    assert _code(envelope) == "ENVELOPE_SHAPE_INVALID"


def test_a_non_object_wrapper_is_judged_before_anything_else() -> None:
    assert _code([{FORBIDDEN_KEY: "legacy"}]) == "ENVELOPE_SHAPE_INVALID"


def test_a_forbidden_key_is_judged_before_the_payload_shape() -> None:
    envelope = _canonical_input()
    envelope["payload"] = [{FORBIDDEN_KEY: "legacy"}]
    assert _code(envelope) == "FORBIDDEN_KEY"


@pytest.mark.parametrize("fixture_class", ["envelope_valid_canonical", "lane_mapping_legacy"])
def test_the_lister_walks_a_class_recursively_in_path_order(fixture_class: str) -> None:
    fixtures = list_class_fixtures(fixture_class)
    on_disk = sorted((FIXTURES_ROOT / "class_taxonomy" / fixture_class).rglob("*.json"))
    assert [f.path for f in fixtures] == on_disk
    assert [f.rel_path for f in fixtures] == sorted(f.rel_path for f in fixtures)
    assert {f.fixture_class for f in fixtures} == {fixture_class}


def test_the_lister_joins_each_fixture_with_its_manifest_row() -> None:
    by_path = {entry.path: entry for entry in manifest_class_entries()}
    fixtures = list_class_fixtures("envelope_invalid_shape", "envelope_strict_journal")
    for fixture in fixtures:
        assert fixture.entry is by_path.get(fixture.rel_path)
    assert any(f.entry is not None for f in fixtures)
    assert any(f.entry is None for f in fixtures)  # strict journal has no classes.entries row


def test_an_unknown_class_lists_nothing() -> None:
    assert list_class_fixtures("no_such_class") == []
