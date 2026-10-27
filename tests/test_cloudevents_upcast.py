"""The legacy-row upcaster: each form, each issue code, strict and permissive use.

Expectations here are written by hand from design sections 3 and 4 and the upcaster
contract, independently of the golden corpus (which is generated, then reviewed).
"""

from __future__ import annotations

import copy
import json
from typing import Any, Dict

import pytest

from spec_kitty_events.cloudevents import (
    IssueCode,
    UpcastContext,
    UpcastError,
    upcast,
    upcast_line,
)

MSN = "01J0000000000000000000MSN1"
PRJ = "5c0f6f0e-3b1a-4d8e-9a55-0d7e3f2b9c11"
URN = "urn:ai.spec-kitty:schema:"
CTX = UpcastContext(missionid=MSN, missionslug="041-demo")


def _constants(name: str) -> Dict[str, Any]:
    return {
        "specversion": "1.0",
        "type": "ai.spec-kitty." + name,
        "datacontenttype": "application/json",
        "dataschema": URN + name,
        "schemaversion": "4.0.0",
    }


def _lane_row(**overrides: Any) -> Dict[str, Any]:
    row: Dict[str, Any] = {
        "event_id": "01J0000000000000000000F001",
        "mission_slug": "041-demo",
        "wp_id": "WP01",
        "from_lane": "planned",
        "to_lane": "claimed",
        "at": "2026-01-01T00:00:00+00:00",
        "actor": "claude",
        "force": False,
        "execution_mode": "worktree",
        "reason": None,
        "review_ref": None,
        "evidence": None,
    }
    row.update(overrides)
    return row


def _transport_row(**overrides: Any) -> Dict[str, Any]:
    row: Dict[str, Any] = {
        "event_id": "01J0000000000000000000E001",
        "event_type": "WPStatusChanged",
        "aggregate_id": f"mission/{MSN}/wp/WP01",
        "payload": {
            "mission_slug": "041-demo",
            "mission_id": MSN,
            "wp_id": "WP01",
            "from_lane": "claimed",
            "to_lane": "doing",
            "actor": "claude",
            "force": False,
            "reason": None,
            "execution_mode": "worktree",
            "review_ref": None,
            "summary": "started",
            "evidence": None,
        },
        "timestamp": "2026-01-01T00:00:01Z",
        "build_id": "build-1",
        "node_id": "node-a",
        "lamport_clock": 7,
        "causation_id": None,
        "project_uuid": PRJ,
        "project_slug": "demo",
        "correlation_id": "01J0000000000000000000C001",
        "schema_version": "3.0.0",
        "data_tier": 0,
    }
    row.update(overrides)
    return row


# ------------------------------------------------------------------ shape B: diary
def test_diary_lane_row_maps_per_design_section_4() -> None:
    result = upcast(_lane_row(), CTX)
    assert result.mapped and result.form == "flat" and result.issues == ()
    assert result.row == {
        **_constants("lane.transitioned.v1"),
        "id": "01J0000000000000000000F001",
        "source": f"/spec-kitty/missions/{MSN}",
        "time": "2026-01-01T00:00:00+00:00",
        "subject": "WP01",
        "missionid": MSN,
        "missionslug": "041-demo",
        "actor": "claude",
        "executionmode": "worktree",
        "data": {
            "from_lane": "planned",
            "to_lane": "claimed",
            "force": False,
            "reason": None,
            "review_ref": None,
            "evidence": None,
        },
    }


def test_structured_actor_projects_and_keeps_the_binding_whole() -> None:
    actor = {"role": "reviewer", "profile": "p", "tool": "claude", "model": "m"}
    result = upcast(_lane_row(actor=actor), CTX)
    assert result.row["actor"] == "claude"
    assert result.row["data"]["actorbinding"] == actor
    role_only = upcast(_lane_row(actor={"role": "reviewer"}), CTX)
    assert role_only.row["actor"] == "reviewer"
    empty = upcast(_lane_row(actor={"profile": "p"}), CTX)
    assert "actor" not in empty.row and empty.row["data"]["actorbinding"] == {"profile": "p"}


def test_row_mission_id_is_used_and_must_agree_with_context() -> None:
    row = _lane_row(mission_id=MSN.lower())
    assert upcast(row).row["missionid"] == MSN
    assert upcast(row, CTX).row["missionid"] == MSN
    other = UpcastContext(missionid="01J0000000000000000000MSN2")
    assert upcast(row, other).codes == (IssueCode.MISSION_ID_CONFLICT,)
    with pytest.raises(UpcastError) as raised:
        upcast(row, other, strict=True)
    assert raised.value.code == IssueCode.MISSION_ID_CONFLICT


def test_mission_slug_must_agree_with_context() -> None:
    result = upcast(_lane_row(), UpcastContext(missionid=MSN, missionslug="other"))
    assert result.codes == (IssueCode.MISSION_SLUG_CONFLICT,)


def test_feature_slug_is_the_published_legacy_name_of_mission_slug() -> None:
    row = _lane_row()
    row["feature_slug"] = row.pop("mission_slug")
    assert upcast(row, CTX).row["missionslug"] == "041-demo"
    both = _lane_row(feature_slug="041-demo")
    assert upcast(both, CTX).row == upcast(_lane_row(), CTX).row
    clash = _lane_row(feature_slug="else")
    assert upcast(clash, CTX).codes == (IssueCode.MISSION_SLUG_CONFLICT,)


def test_without_any_mission_id_a_project_uuid_gives_the_fallback_source() -> None:
    missing = upcast(_lane_row())
    assert missing.codes == (IssueCode.MISSION_ID_MISSING,)
    fallback = upcast(_lane_row(), UpcastContext(projectuuid=PRJ))
    assert fallback.row["source"] == f"/spec-kitty/projects/{PRJ}"
    assert fallback.row["subject"] == "WP01" and "missionid" not in fallback.row


def test_a_missing_required_lifted_member_is_named() -> None:
    row = _lane_row()
    del row["execution_mode"]
    assert upcast(row, CTX).codes == (IssueCode.MISSING_MEMBER,)


def test_annotation_row_maps_to_the_annotation_type() -> None:
    row = {
        "event_id": "01J0000000000000000000A001",
        "at": "2026-01-01T00:00:02+00:00",
        "wp_id": "WP01",
        "actor": "claude",
        "kind": "annotation",
        "delta": {"subtasks": {"T001": "done"}},
    }
    result = upcast(row, CTX)
    assert result.row == {
        **_constants("wp.innerstate.changed.v1"),
        "id": "01J0000000000000000000A001",
        "source": f"/spec-kitty/missions/{MSN}",
        "time": "2026-01-01T00:00:02+00:00",
        "subject": "WP01",
        "missionid": MSN,
        "missionslug": "041-demo",
        "actor": "claude",
        "data": {"delta": {"subtasks": {"T001": "done"}}},
    }


# ------------------------------------------------------------------ shape A: transport
def test_transport_lane_envelope_maps_per_design_section_3() -> None:
    result = upcast(_transport_row())
    assert result.mapped and result.form == "transport_envelope"
    assert result.row == {
        **_constants("lane.transitioned.v1"),
        "id": "01J0000000000000000000E001",
        "source": f"/spec-kitty/missions/{MSN}",
        "time": "2026-01-01T00:00:01Z",
        "subject": "WP01",
        "missionid": MSN,
        "missionslug": "041-demo",
        "actor": "claude",
        "executionmode": "worktree",
        "buildid": "build-1",
        "nodeid": "node-a",
        "lamportclock": 7,
        "correlationid": "01J0000000000000000000C001",
        "projectuuid": PRJ,
        "projectslug": "demo",
        "datatier": 0,
        "data": {
            "from_lane": "claimed",
            "to_lane": "in_progress",
            "force": False,
            "reason": None,
            "review_ref": None,
            "summary": "started",
            "evidence": None,
        },
    }


def test_transport_ids_are_normalized_and_nulls_omitted() -> None:
    row = _transport_row(
        event_id="0F8FAD5BD9CB469FA16570867728950E",
        causation_id="01j0000000000000000000e000",
        project_slug=None,
        lamport_clock=None,
    )
    del row["data_tier"]
    result = upcast(row)
    assert result.row["id"] == "0f8fad5b-d9cb-469f-a165-70867728950e"
    assert result.row["causationid"] == "01J0000000000000000000E000"
    for absent in ("projectslug", "lamportclock", "datatier"):
        assert absent not in result.row


def test_slug_and_unprefixed_aggregates_take_the_project_source() -> None:
    payload = {"actor": "u", "initial_phase": "specify", "mission_id": "M-R1", "mission_type": "sd"}
    slug = upcast(
        _transport_row(event_type="MissionStarted", aggregate_id="mission/M-R1", payload=payload)
    )
    assert slug.row["source"] == f"/spec-kitty/projects/{PRJ}"
    assert slug.row["subject"] == "mission/M-R1" and "missionid" not in slug.row
    assert slug.row["data"] == payload  # a slug is never presented as a mission id
    session = upcast(
        _transport_row(
            event_type="HarnessObservation",
            aggregate_id="session/sess-1",
            payload={"kind": "presence", "harness": "claude", "session_id": "sess-1"},
        )
    )
    assert session.row["subject"] == "session/sess-1"
    lane = _transport_row(aggregate_id="WP01")
    del lane["payload"]["mission_id"]
    unprefixed = upcast(lane)
    assert unprefixed.row["subject"] == "WP01" and unprefixed.row["source"].startswith(
        "/spec-kitty/projects/"
    )


def test_aggregate_and_payload_disagreements_are_named() -> None:
    other = _transport_row()
    other["payload"]["mission_id"] = "01J0000000000000000000MSN2"
    assert upcast(other).codes == (IssueCode.MISSION_ID_CONFLICT,)
    wp = _transport_row(aggregate_id=f"mission/{MSN}/wp/WP02")
    assert upcast(wp).codes == (IssueCode.SUBJECT_CONFLICT,)
    mission_level = _transport_row(
        event_type="MissionStarted",
        aggregate_id=f"mission/{MSN}/wp/WP01",
        payload={"actor": "u", "initial_phase": "specify", "mission_id": MSN, "mission_type": "sd"},
    )
    assert upcast(mission_level).codes == (IssueCode.AGGREGATE_UNMAPPABLE,)
    foreign = _transport_row(aggregate_id="mission/some-other/wp/WP01")
    assert upcast(foreign).codes == (IssueCode.AGGREGATE_UNMAPPABLE,)


@pytest.mark.parametrize(
    "overrides, code",
    [
        ({"schema_version": "2.9.0"}, IssueCode.UNSUPPORTED_SCHEMA_VERSION),
        ({"event_type": "Sparkle"}, IssueCode.UNREGISTERED_TYPE),
        ({"team_slug": "t"}, IssueCode.UNEXPECTED_MEMBER),
        ({"correlation_id": None}, IssueCode.MISSING_MEMBER),
        ({"lamport_clock": 2**31}, IssueCode.CLOCK_OUT_OF_RANGE),
        ({"lamport_clock": -1}, IssueCode.CLOCK_OUT_OF_RANGE),
        ({"lamport_clock": True}, IssueCode.INVALID_MEMBER),
        ({"data_tier": 5}, IssueCode.INVALID_MEMBER),
        ({"event_id": "not-an-id"}, IssueCode.INVALID_ID),
        ({"timestamp": "yesterday"}, IssueCode.INVALID_TIME),
        ({"project_uuid": "nope"}, IssueCode.INVALID_MEMBER),
        ({"payload": []}, IssueCode.INVALID_MEMBER),
    ],
)
def test_transport_issue_codes(overrides: Dict[str, Any], code: str) -> None:
    row = _transport_row(**overrides)
    result = upcast(row)
    assert result.outcome == "passthrough" and result.codes == (code,)
    assert result.row is row
    with pytest.raises(UpcastError) as raised:
        upcast(row, strict=True)
    assert raised.value.code == code


def test_a_project_scoped_type_needs_its_project_as_aggregate() -> None:
    payload = {"project_slug": "demo"}
    ok = _transport_row(event_type="ProjectInitialized", aggregate_id=PRJ, payload=payload)
    assert upcast(ok, CTX).row["source"] == f"/spec-kitty/projects/{PRJ}"
    assert "missionid" not in upcast(ok, CTX).row
    bad = _transport_row(
        event_type="ProjectInitialized", aggregate_id="project/else", payload=payload
    )
    assert upcast(bad).codes == (IssueCode.AGGREGATE_UNMAPPABLE,)


# ------------------------------------------------------------------ CLI envelopes
def test_lifecycle_envelope_uses_the_ulid_aggregate_and_keeps_the_project() -> None:
    row = {
        "event_id": "01J0000000000000000000F007",
        "event_type": "MissionReopened",
        "aggregate_id": MSN,
        "aggregate_type": "Mission",
        "schema_version": "5.0.0",
        "timestamp": "2026-01-01T00:00:00+00:00",
        "project_uuid": PRJ,
        "project_slug": None,
        "payload": {"anything": 1},
    }
    result = upcast(row)
    assert result.form == "lifecycle_envelope"
    assert result.row["missionid"] == MSN and result.row["projectuuid"] == PRJ
    assert "projectslug" not in result.row and result.row["data"] == {"anything": 1}
    assert upcast(dict(row, zz=1)).codes == (IssueCode.UNEXPECTED_MEMBER,)
    assert upcast(dict(row, event_type="GlossaryTermAdded")).codes == (IssueCode.UNREGISTERED_TYPE,)


def test_decision_and_journal_envelopes_need_the_mission_from_context() -> None:
    decision = {
        "event_id": "01J0000000000000000000F019",
        "at": "2026-01-01T00:00:00+00:00",
        "event_type": "DecisionPointOpened",
        "payload": {"x": 1},
    }
    assert upcast(decision).codes == (IssueCode.MISSION_ID_MISSING,)
    assert upcast(decision, CTX).row["type"] == "ai.spec-kitty.decision.opened.v1"
    journal = {
        "event_id": "01J0000000000000000000F021",
        "row_version": 1,
        "event_type": "MissionRunStarted",
        "timestamp": "2026-01-01T00:00:00+00:00",
        "payload": {"y": 2},
    }
    result = upcast(journal, CTX)
    assert result.form == "journal_envelope" and "row_version" not in json.dumps(result.row)


# ------------------------------------------------------------------ pass-through
@pytest.mark.parametrize(
    "row, code",
    [
        ([1, 2], IssueCode.NOT_AN_OBJECT),
        ("text", IssueCode.NOT_AN_OBJECT),
        (None, IssueCode.NOT_AN_OBJECT),
        ({"event_id": "x", "event_name": "Retro", "payload": {}}, IssueCode.UNRECOGNISED_ROW),
        (
            {"event_id": "x", "event_type": "Kind", "mission_slug": "m", "payload": {}},
            IssueCode.UNRECOGNISED_ROW,
        ),
    ],
)
def test_unmapped_rows_pass_through_permissively_and_fail_strictly(row: Any, code: str) -> None:
    result = upcast(row, CTX)
    assert result.outcome == "passthrough" and result.codes == (code,) and result.row is row
    with pytest.raises(UpcastError):
        upcast(row, CTX, strict=True)


def test_a_cloudevent_passes_through_in_both_modes() -> None:
    event = upcast(_lane_row(), CTX).row
    for strict in (False, True):
        again = upcast(event, CTX, strict=strict)
        assert again.outcome == "passthrough" and again.form == "cloudevent"
        assert again.codes == (IssueCode.ALREADY_CLOUDEVENT,) and again.row is event


def test_lines_keep_their_bytes_and_bad_json_is_named() -> None:
    raw = json.dumps(_lane_row(), separators=(", ", ": ")).encode("utf-8") + b"\n"
    result = upcast_line(raw, CTX)
    assert result.mapped and result.original_line == raw
    assert upcast_line(raw.decode("utf-8"), CTX).original_line == raw
    broken = upcast_line(b"{not json\n", CTX)
    assert broken.codes == (IssueCode.NOT_JSON,) and broken.original_line == b"{not json\n"
    assert upcast_line(b"\xff\xfe", CTX).codes == (IssueCode.NOT_JSON,)
    with pytest.raises(UpcastError):
        upcast_line(b"", CTX, strict=True)


def test_the_input_is_never_mutated() -> None:
    for row in (_lane_row(actor={"tool": "t"}), _transport_row()):
        before = copy.deepcopy(row)
        result = upcast(row, CTX if "wp_id" in row else None)
        assert row == before
        result.row["data"]["mutated"] = True
        assert row == before


def test_every_issue_code_is_named_once() -> None:
    assert len(IssueCode.ALL) == len(set(IssueCode.ALL))
