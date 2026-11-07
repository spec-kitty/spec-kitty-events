"""Packaged runner for the ``status_append`` write-contract fixtures (#160).

Each document supplies raw JSON row text and either the exact sanitized ASCII
line or the protocol refusal. The Java encoder added by WP05 is held to the
same documents, so both bindings consume identical input values and bytes.
"""

from __future__ import annotations

import json
import re
from collections import Counter
from pathlib import Path
from typing import Any, Dict, List

import pytest

from spec_kitty_events import status_log
from spec_kitty_events.conformance.loader import load_fixtures, load_status_append_cases
from spec_kitty_events.status_log import encode_status_row

_CASES: List[Dict[str, Any]] = load_status_append_cases()
_COVERAGE_CLASSES = frozenset(
    {
        "transition_row_plain",
        "annotation_row_plain",
        "pii_top_level",
        "pii_nested_and_in_lists",
        "session_pair_both_parse",
        "session_pair_negative",
        "session_pair_long_span",
        "session_pair_overwrites_duration",
        "session_pair_one_missing_keeps_duration",
        "session_pair_unparseable",
        "session_pair_zulu_and_basic",
        "session_pair_both_naive",
        "nested_session_keys_untouched",
        "escaping_non_ascii_and_astral",
        "escaping_control_characters",
        "key_order_code_points",
        "numbers_float_repr",
        "numbers_big_integers",
        "nulls_bools_empty_containers",
        "session_value_non_string",
        "non_finite_number",
        "session_pair_mixed_awareness",
    }
)


def test_status_append_category_is_populated() -> None:
    counts = Counter(case["expected_result"] for case in _CASES)
    assert counts["valid"] >= 12
    assert counts["invalid"] >= 4


def test_status_append_is_a_special_fixture_type() -> None:
    assert load_fixtures("status_append") == []


@pytest.mark.parametrize("case", _CASES, ids=[case["id"] for case in _CASES])
def test_status_append_fixture(case: Dict[str, Any]) -> None:
    row = json.loads(case["input_json"])
    if case["expected_result"] == "valid":
        assert encode_status_row(row) == case["expected_line"].encode("ascii")
        return
    error = getattr(status_log, case["expected_error"])
    with pytest.raises(error):
        encode_status_row(row)


def test_status_append_coverage_matches_the_readme_class_list() -> None:
    covered = {coverage for case in _CASES for coverage in case["covers"]}
    readme = (Path(__file__).parent / "fixtures" / "status_append" / "README.md").read_text(
        encoding="utf-8"
    )
    documented = frozenset(re.findall(r"^- `([a-z0-9_]+)`$", readme, flags=re.MULTILINE))

    assert all(case["covers"] for case in _CASES)
    assert covered == _COVERAGE_CLASSES
    assert documented == _COVERAGE_CLASSES
