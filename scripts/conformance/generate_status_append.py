#!/usr/bin/env python3
"""Generate status_append fixtures from core's serializer at the pinned SHA.

Only this test-only script imports core. Run it with the pinned checkout's own
locked Python 3.11 interpreter and SPEC_KITTY_CORE_CHECKOUT set to that checkout.
The packaged package and its tests neither import nor depend on core.
"""

from __future__ import annotations

import argparse
import json
import os
import platform
import subprocess
import sys
from collections import Counter
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any

PINNED_PYTHON = (3, 11)
PINNED_CORE_SHA = "512567eee71e31bb39f580c4f7acf8b865e9d1b2"
Serializer = Callable[[Mapping[str, Any]], str]


@dataclass(frozen=True)
class Case:
    """One language-neutral input; expected output always comes from core."""

    id: str
    input_json: str
    covers: tuple[str, ...]
    notes: str
    expected_error: str | None = None


CASES = (
    Case(
        "transition_row_plain",
        '{"event_id":"01M5Z7A1B2C3D4E5F6G7H8J9KM","mission_slug":"write-service-demo-01M5Z6N5","mission_id":"01M5Z6N54A59R5FVKJRAGZNTVK","wp_id":"WP01","from_lane":"planned","to_lane":"claimed","at":"2026-10-27T18:00:00+00:00","actor":"claude","force":false,"execution_mode":"direct_repo","reason":null,"review_ref":null,"evidence":null,"policy_metadata":null}',
        ("transition_row_plain",),
        "A realistic planned-to-claimed transition row preserves every ordinary field.",
    ),
    Case(
        "annotation_row_plain",
        '{"event_id":"01M5Z7A1B2C3D4E5F6G7H8J9KM","kind":"annotation","wp_id":"WP01","at":"2026-10-27T18:00:00+00:00","actor":"claude","delta":{"note":"claimed","subtasks":{"T001":true}}}',
        ("annotation_row_plain",),
        "An annotation row with a nested delta uses the same byte encoder.",
    ),
    Case(
        "pii_top_level",
        '{"event_id":"pii","machine_name":"build-host","hostname":"host","workspace_path":"/secret","developer_name":"Dev","developer_email":"dev@example.test","keep":true}',
        ("pii_top_level",),
        "Every registered PII key is stripped at the top level.",
    ),
    Case(
        "pii_nested_and_in_lists",
        '{"nested":{"hostname":"nested","keep":1},"objects":[{"workspace_path":"/secret","keep":2}],"lists":[[{"developer_email":"deep","keep":3}]],"developer_name":"top"}',
        ("pii_nested_and_in_lists",),
        "PII is stripped from nested objects and objects at any list depth.",
    ),
    Case(
        "session_pair_both_parse",
        '{"session_started_at":"2026-01-01T00:00:00.250000+00:00","session_ended_at":"2026-01-01T00:00:02.750000+00:00"}',
        ("session_pair_both_parse",),
        "An aware session pair becomes a positive duration truncated toward zero.",
    ),
    Case(
        "session_pair_negative",
        '{"session_started_at":"2026-01-01T00:00:02.750000+00:00","session_ended_at":"2026-01-01T00:00:00.250000+00:00"}',
        ("session_pair_negative",),
        "A reversed aware session pair produces a negative duration truncated toward zero.",
    ),
    Case(
        "session_pair_overwrites_duration",
        '{"session_started_at":"2026-01-01T00:00:00+00:00","session_ended_at":"2026-01-01T00:00:10.900000+00:00","session_duration_s":999}',
        ("session_pair_overwrites_duration",),
        "A parseable session pair overwrites an existing duration.",
    ),
    Case(
        "session_pair_one_missing_keeps_duration",
        '{"session_started_at":"2026-01-01T00:00:00+00:00","session_duration_s":17}',
        ("session_pair_one_missing_keeps_duration",),
        "One missing timestamp is removed without replacing the existing duration.",
    ),
    Case(
        "session_pair_unparseable",
        '{"session_started_at":"not-a-date","session_ended_at":"2026-01-01T00:00:01Z","keep":true}',
        ("session_pair_unparseable",),
        "An unparseable timestamp causes both keys to be removed without a duration.",
    ),
    Case(
        "session_pair_zulu_and_basic",
        '{"session_started_at":"20260101T000000Z","session_ended_at":"20260101T000002Z"}',
        ("session_pair_zulu_and_basic",),
        "Basic-format Zulu timestamps parse through the shared interpreter-normalizing subset.",
    ),
    Case(
        "session_pair_both_naive",
        '{"session_started_at":"2026-01-01 00:00:00","session_ended_at":"2026-01-01 00:00:03"}',
        ("session_pair_both_naive",),
        "Two naive timestamps with a space separator are subtractable.",
    ),
    Case(
        "nested_session_keys_untouched",
        '{"nested":{"session_started_at":"2026-01-01T00:00:00+00:00","session_ended_at":"2026-01-01T00:00:10+00:00"}}',
        ("nested_session_keys_untouched",),
        "Session-shaped keys below the top level remain untouched.",
    ),
    Case(
        "escaping_non_ascii_and_astral",
        '{"text":"é—😀"}',
        ("escaping_non_ascii_and_astral",),
        "Non-ASCII BMP characters and astral characters use ASCII escapes and surrogate pairs.",
    ),
    Case(
        "escaping_control_characters",
        '{"text":"\\b\\f\\n\\r\\t\\u0000\\u001f\\u007f\\"\\\\/\\ud800"}',
        ("escaping_control_characters",),
        "Controls, DEL, quotes, backslashes, slashes and lone surrogates pin Python escaping.",
    ),
    Case(
        "key_order_code_points",
        '{"😀":"astral","￿":"bmp","a":{"é":2,"z":1}}',
        ("key_order_code_points",),
        "Objects sort keys by Unicode code point rather than UTF-16 code units.",
    ),
    Case(
        "numbers_float_repr",
        '{"one_float":1.0,"negative_zero":-0.0,"tenth":0.1,"small_scientific":1e-05,"small_decimal":0.0001,"large_scientific":1e+16,"large_decimal":1000000000000000.0,"subnormal":5e-324,"max_float":1.7976931348623157e+308}',
        ("numbers_float_repr",),
        "Finite floats preserve Python's signed-zero and shortest round-trip representations.",
    ),
    Case(
        "numbers_big_integers",
        '{"two_to_64":18446744073709551616,"negative_two_to_70":-1180591620717411303424,"zero":0}',
        ("numbers_big_integers",),
        "Arbitrary-size positive and negative JSON integers remain integers.",
    ),
    Case(
        "nulls_bools_empty_containers",
        '{"null":null,"true":true,"false":false,"empty_object":{},"empty_array":[]}',
        ("nulls_bools_empty_containers",),
        "Nulls, booleans and empty containers use their ordinary JSON spellings.",
    ),
    Case(
        "session_value_non_string",
        '{"session_started_at":42,"session_ended_at":"2026-01-01T00:00:01Z","session_duration_s":9}',
        ("session_value_non_string",),
        "A non-string session value is unparseable and preserves an existing duration.",
    ),
    Case(
        "non_finite_nan",
        '{"n":NaN,"nested":[0]}',
        ("non_finite_number",),
        "The protocol refuses a NaN token that core would write.",
        "NonFiniteNumber",
    ),
    Case(
        "non_finite_infinity",
        '{"nested":[{"n":Infinity}]}',
        ("non_finite_number",),
        "The protocol refuses an Infinity token nested inside objects and arrays.",
        "NonFiniteNumber",
    ),
    Case(
        "non_finite_overflow",
        '{"n":1e400}',
        ("non_finite_number",),
        "The protocol refuses a finite JSON token that json.loads overflows to infinity.",
        "NonFiniteNumber",
    ),
    Case(
        "session_pair_naive_then_aware",
        '{"session_started_at":"2026-01-01T00:00:00","session_ended_at":"2026-01-01T00:00:01Z"}',
        ("session_pair_mixed_awareness",),
        "A naive start and aware end are refused instead of leaking core's TypeError.",
        "SessionTimesNotSubtractable",
    ),
    Case(
        "session_pair_aware_then_naive",
        '{"session_started_at":"2026-01-01T00:00:00Z","session_ended_at":"2026-01-01T00:00:01"}',
        ("session_pair_mixed_awareness",),
        "An aware start and naive end are refused instead of leaking core's TypeError.",
        "SessionTimesNotSubtractable",
    ),
)


def _non_finite_behaviour(line: str) -> str | None:
    for token in ("NaN", "-Infinity", "Infinity"):
        if token in line:
            return f"writes {token}"
    return None


def build_document(case: Case, serialize: Serializer) -> dict[str, Any]:
    """Build one fixture document by executing the supplied core serializer."""
    row = json.loads(case.input_json)
    document: dict[str, Any] = {"input_json": case.input_json}
    try:
        line = serialize(row)
    except TypeError:
        if case.expected_error != "SessionTimesNotSubtractable":
            raise
        document["expected_error"] = case.expected_error
        document["core_behaviour"] = "raises TypeError"
    else:
        if case.expected_error is None:
            document["expected_line"] = line + "\n"
        else:
            behaviour = _non_finite_behaviour(line)
            if case.expected_error != "NonFiniteNumber" or behaviour is None:
                raise RuntimeError(f"unexpected core behaviour for invalid case {case.id}")
            document["expected_error"] = case.expected_error
            document["core_behaviour"] = behaviour
    document["covers"] = list(case.covers)
    return document


def _document_text(document: dict[str, Any]) -> str:
    return json.dumps(document, indent=2, ensure_ascii=False, sort_keys=False) + "\n"


def expected_documents(
    fixture_dir: Path, cases: Sequence[Case], serialize: Serializer
) -> dict[Path, str]:
    """Return every expected path and deterministic document text."""
    documents: dict[Path, str] = {}
    for case in cases:
        category = "invalid" if case.expected_error is not None else "valid"
        documents[fixture_dir / category / f"{case.id}.json"] = _document_text(
            build_document(case, serialize)
        )
    return documents


def _json_files(fixture_dir: Path) -> set[Path]:
    return {
        path
        for category in ("valid", "invalid")
        for path in (fixture_dir / category).glob("*.json")
    }


def write_documents(fixture_dir: Path, cases: Sequence[Case], serialize: Serializer) -> None:
    """Write all case documents and remove cases no longer in the table."""
    documents = expected_documents(fixture_dir, cases, serialize)
    for path, text in documents.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
    for path in _json_files(fixture_dir) - documents.keys():
        path.unlink()


def diff_documents(fixture_dir: Path, documents: Mapping[Path, str]) -> list[str]:
    """Return changed, missing and extra case ids without modifying any file."""
    drift = {
        path.stem
        for path, text in documents.items()
        if not path.is_file() or path.read_text(encoding="utf-8") != text
    }
    drift.update(path.stem for path in _json_files(fixture_dir) - documents.keys())
    return sorted(drift)


def _load_core_serializer(checkout: Path) -> Serializer:
    sys.path.insert(0, str(checkout / "src"))
    from specify_cli.events.sanitizer import sanitize_event_for_log
    from specify_cli.status.store import serialize_event_line

    def serialize(row: Mapping[str, Any]) -> str:
        sanitized = sanitize_event_for_log(dict(row))
        serialized = serialize_event_line(row)
        if serialized != json.dumps(sanitized, sort_keys=True):
            raise RuntimeError("core status serializer and sanitizer disagree")
        return serialized

    return serialize


def _print_summary(cases: Sequence[Case]) -> None:
    valid = sum(case.expected_error is None for case in cases)
    print(
        f"Python {platform.python_version()}; {len(cases)} cases ({valid} valid, {len(cases) - valid} invalid)"
    )
    counts = Counter(coverage for case in cases for coverage in case.covers)
    for coverage in sorted(counts):
        print(f"{coverage}: {counts[coverage]}")


def _checkout_head(checkout: Path) -> str | None:
    """Return the commit the core checkout is at, or None when it is not a git checkout."""
    result = subprocess.run(  # noqa: S603 -- fixed argv, test-only oracle
        ["git", "-C", str(checkout), "rev-parse", "HEAD"],  # noqa: S607
        capture_output=True,
        text=True,
        check=False,
    )
    return result.stdout.strip() if result.returncode == 0 else None


def main(argv: Sequence[str] | None = None) -> int:
    """Generate fixtures or check committed fixtures for oracle drift."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="diff instead of writing")
    args = parser.parse_args(argv)
    checkout_value = os.environ.get("SPEC_KITTY_CORE_CHECKOUT")
    if not checkout_value:
        print("SPEC_KITTY_CORE_CHECKOUT must name the pinned core checkout", file=sys.stderr)
        return 2
    if sys.version_info[:2] != PINNED_PYTHON:
        required = ".".join(str(part) for part in PINNED_PYTHON)
        print(
            f"status_append oracle requires Python {required}, found {platform.python_version()}",
            file=sys.stderr,
        )
        return 2
    head = _checkout_head(Path(checkout_value))
    if head != PINNED_CORE_SHA:
        print(
            f"status_append oracle requires core at {PINNED_CORE_SHA}, found {head or 'no git checkout'}",
            file=sys.stderr,
        )
        return 2

    fixture_dir = (
        Path(__file__).resolve().parents[2]
        / "src"
        / "spec_kitty_events"
        / "conformance"
        / "fixtures"
        / "status_append"
    )
    serialize = _load_core_serializer(Path(checkout_value))
    documents = expected_documents(fixture_dir, CASES, serialize)
    if args.check:
        drift = diff_documents(fixture_dir, documents)
        for case_id in drift:
            print(f"drift: {case_id}")
        if drift:
            return 1
    else:
        write_documents(fixture_dir, CASES, serialize)
    _print_summary(CASES)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
