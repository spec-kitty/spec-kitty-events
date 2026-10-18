"""Tests for ``spec_kitty_events.persistence`` (ledger-write contract seam, #118)."""

from __future__ import annotations

import json
import os
import statistics
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict

import pytest
from hypothesis import HealthCheck, given, settings
from hypothesis import strategies as st
from pydantic import BaseModel

from spec_kitty_events.glossary import GlossaryScopeActivatedPayload
from spec_kitty_events.models import Event, SpecKittyEventsError
from spec_kitty_events.persistence import (
    LedgerAppendError,
    LedgerTornTailError,
    LedgerValidationError,
    append_event,
    build_record,
    serialize_record,
)

MISSION_REOPENED: Dict[str, Any] = {
    "event_type": "MissionReopened",
    "mission_id": "01KTESTMISSIONID0000000000",
    "mission_slug": "mission-001",
    "reason": "reviewer requested rework after merge",
    "reopened_by": "claude",
    "reopened_at": "2026-01-01T00:00:00+00:00",
    "cleared_merge": {"merged_pr": 42, "merged_at": "2026-01-01T00:00:00+00:00"},
}

# Shaped like core's flat glossary dict (spec-kitty src/glossary/events.py),
# 11.0.0 shape: run_id present, no scope_type (static capture, core 0746bec3).
CORE_GLOSSARY_DICT: Dict[str, Any] = {
    "event_type": "GlossaryScopeActivated",
    "event_id": "83f49bb3-70e7-58e9-8d57-a6686f81284c",
    "timestamp": "2026-10-18T12:00:00+00:00",
    "mission_id": "01M57TCP-mission",
    "run_id": "run-0001",
    "scope_id": "team_domain",
    "glossary_version_id": "v3",
}


def _envelope(**overrides: Any) -> Event:
    fields: Dict[str, Any] = {
        "event_id": "01JMXXXXXXXXXXXXXXXXXXXXXX",
        "event_type": "MissionReopened",
        "aggregate_id": "mission-001",
        "payload": {k: v for k, v in MISSION_REOPENED.items() if k != "event_type"},
        "timestamp": datetime(2026, 1, 15, 10, 0, tzinfo=timezone.utc),
        "build_id": "build-test",
        "node_id": "test-node",
        "lamport_clock": 1,
        "project_uuid": uuid.UUID("a1b2c3d4-e5f6-7890-abcd-ef1234567890"),
        "correlation_id": "01JMYYYYYYYYYYYYYYYYYYYYYY",
    }
    fields.update(overrides)
    return Event(**fields)


def _core_fallback_line(record: Dict[str, Any]) -> bytes:
    # Byte rule of core's local fallback append (spec-kitty src/glossary/events.py).
    return (json.dumps(record, sort_keys=True, default=str) + "\n").encode()


def test_core_import_site_resolves_and_appends(tmp_path: Path) -> None:
    """Pins #118: core's exact import line resolves and one line is written."""
    from spec_kitty_events.persistence import append_event as imported

    ledger = tmp_path / "x.events.jsonl"
    imported(dict(MISSION_REOPENED), ledger)
    assert ledger.read_bytes() == _core_fallback_line(MISSION_REOPENED)


def test_flat_core_glossary_dict_matches_core_fallback_bytes(tmp_path: Path) -> None:
    ledger = tmp_path / "nested" / "dir" / "g.events.jsonl"
    append_event(dict(CORE_GLOSSARY_DICT), ledger)
    assert ledger.read_bytes() == _core_fallback_line(CORE_GLOSSARY_DICT)


def test_event_envelope_is_dumped_in_json_mode(tmp_path: Path) -> None:
    ledger = tmp_path / "e.jsonl"
    event = _envelope()
    append_event(event, ledger)
    record = json.loads(ledger.read_bytes())
    assert record == event.model_dump(mode="json")
    assert record["timestamp"] == "2026-01-15T10:00:00Z"


def test_envelope_shaped_dict_validates_inner_payload() -> None:
    record = _envelope().model_dump(mode="json")
    record["payload"] = {"mission_id": "x"}
    with pytest.raises(LedgerValidationError, match="MissionReopened"):
        build_record(record)


def test_registered_payload_model_gets_event_type_attached() -> None:
    model = GlossaryScopeActivatedPayload(
        mission_id="m", run_id="r", scope_id="team_domain", glossary_version_id="v1"
    )
    record = build_record(model)
    assert record == {"event_type": "GlossaryScopeActivated", **model.model_dump(mode="json")}


def test_unregistered_model_is_refused() -> None:
    class Stray(BaseModel):
        x: int = 1

    with pytest.raises(LedgerValidationError, match="not a registered"):
        build_record(Stray())


@pytest.mark.parametrize(
    "bad",
    [
        {"mission_id": "x"},
        {"event_type": 7},
        {"event_type": "NoSuchEvent"},
        {"event_type": "Event"},
        {**MISSION_REOPENED, "reason": None},
        {**MISSION_REOPENED, "event_id": 5},
        {**MISSION_REOPENED, "cleared_merge": {"merged_pr": float("nan")}},
        {**MISSION_REOPENED, "extra": object()},
        {**MISSION_REOPENED, "nested": {1: "non-str key"}},
        {**MISSION_REOPENED, "items": [float("inf")]},
    ],
)
def test_invalid_inputs_raise_and_write_nothing(tmp_path: Path, bad: Dict[str, Any]) -> None:
    ledger = tmp_path / "x.jsonl"
    ledger.write_bytes(b'{"prior": 1}\n')
    with pytest.raises(LedgerValidationError):
        append_event(bad, ledger)
    assert ledger.read_bytes() == b'{"prior": 1}\n'


def test_unsupported_input_kind_is_refused(tmp_path: Path) -> None:
    ledger = tmp_path / "x.jsonl"
    with pytest.raises(LedgerValidationError, match="unsupported"):
        append_event(["not", "an", "event"], ledger)  # type: ignore[arg-type]  # deliberate wrong kind
    assert not ledger.exists()


def test_error_hierarchy() -> None:
    assert issubclass(LedgerValidationError, LedgerAppendError)
    assert issubclass(LedgerValidationError, ValueError)
    assert issubclass(LedgerTornTailError, LedgerAppendError)
    assert issubclass(LedgerAppendError, SpecKittyEventsError)


def test_torn_tail_is_refused(tmp_path: Path) -> None:
    ledger = tmp_path / "x.jsonl"
    ledger.write_bytes(b'{"partial": ')
    with pytest.raises(LedgerTornTailError):
        append_event(dict(MISSION_REOPENED), ledger)
    assert ledger.read_bytes() == b'{"partial": '


def test_empty_file_and_consecutive_appends_keep_call_order(tmp_path: Path) -> None:
    ledger = tmp_path / "x.jsonl"
    ledger.write_bytes(b"")
    second = {**MISSION_REOPENED, "reason": "second"}
    append_event(dict(MISSION_REOPENED), ledger)
    append_event(second, ledger)
    assert ledger.read_bytes() == _core_fallback_line(MISSION_REOPENED) + _core_fallback_line(
        second
    )


def test_directory_target_raises_without_side_effects(tmp_path: Path) -> None:
    with pytest.raises(OSError):
        append_event(dict(MISSION_REOPENED), tmp_path)


def test_short_write_raises(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(os, "write", lambda fd, data: len(data) - 1)
    with pytest.raises(LedgerAppendError, match="short write"):
        append_event(dict(MISSION_REOPENED), tmp_path / "x.jsonl")


def test_serialize_record_pins_ascii_escapes_and_float_repr() -> None:
    line = serialize_record({"b": "café", "a": 1e-05, "c": 0.95})
    assert line == b'{"a": 1e-05, "b": "caf\\u00e9", "c": 0.95}\n'


def test_serialize_record_refuses_unserialisable() -> None:
    with pytest.raises(LedgerValidationError, match="not serialisable"):
        serialize_record({"x": float("nan")})


def test_reviewer_self_approval_keeps_its_declared_timestamp_field() -> None:
    from spec_kitty_events.persistence import _flat_payload

    payload = _flat_payload(
        {"event_type": "ReviewerSelfApproval", "timestamp": "t"}, "ReviewerSelfApproval"
    )
    assert payload == {"timestamp": "t"}


_bad_values = st.one_of(
    st.integers(),
    st.none(),
    st.floats(allow_nan=True, allow_infinity=True).filter(
        lambda f: f != f or f in (float("inf"), float("-inf"))
    ),
    st.lists(st.integers(), max_size=2),
)


@settings(max_examples=200, suppress_health_check=[HealthCheck.function_scoped_fixture])
@given(
    field=st.sampled_from(sorted(k for k in MISSION_REOPENED if k != "event_type")),
    value=_bad_values,
    unknown_type=st.text(min_size=1, max_size=12).filter(lambda t: t not in {"MissionReopened"}),
    use_unknown=st.booleans(),
)
def test_property_no_partial_records(
    tmp_path: Path, field: str, value: Any, unknown_type: str, use_unknown: bool
) -> None:
    ledger = tmp_path / "p.jsonl"
    ledger.write_bytes(b'{"prior": 1}\n')
    record = {**MISSION_REOPENED, field: value}
    if use_unknown:
        record["event_type"] = unknown_type + "\u0000unknown"
    try:
        append_event(record, ledger)
    except LedgerAppendError:
        assert ledger.read_bytes() == b'{"prior": 1}\n'
    else:
        # Only a record the contract genuinely accepts may be written: exactly
        # one new line, which round-trips through build_record unchanged.
        prior, *rest = ledger.read_bytes().split(b"\n")
        assert prior == b'{"prior": 1}' and len(rest) == 2 and rest[1] == b""
        assert build_record(json.loads(rest[0])) == json.loads(rest[0])


def test_benchmark_append_median_reported(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    """NFR-002: reported, not gated (target < 5 ms median on local disk)."""
    ledger = tmp_path / "bench.jsonl"
    samples = []
    for _ in range(50):
        start = time.perf_counter()
        append_event(dict(CORE_GLOSSARY_DICT), ledger)
        samples.append(time.perf_counter() - start)
    with capsys.disabled():
        print(f"\nappend_event median: {statistics.median(samples) * 1000:.3f} ms")
