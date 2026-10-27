"""Producer contract: registry integrity, schema shape, model parity, mutation checks.

The fixture-driven checks (valid, invalid, legacy-invalid) live in the packaged
``spec_kitty_events/conformance/test_producer_contract.py``; this module covers what is
about the registry and the schemas themselves.
"""

from __future__ import annotations

import copy
import json
import re
from pathlib import Path
from typing import Any, Dict, Iterator, List, Tuple

import pytest
from jsonschema import Draft202012Validator

from spec_kitty_events import decisionpoint as dp
from spec_kitty_events import harness_observation as ho
from spec_kitty_events import lifecycle as lc
from spec_kitty_events import mission_next as mn
from spec_kitty_events import producer_contract as pc
from spec_kitty_events import project_lifecycle as pl
from spec_kitty_events import work_observation as wo
from spec_kitty_events.strict import STRICT_EVENT_TYPES
from spec_kitty_events.status import Lane

_ROOT = Path(pc.__file__).resolve().parent
_ROWS = _ROOT / "fixtures" / "rows"
_CE_NAME = re.compile(r"^[a-z0-9]{1,20}$")

# The census: data-model section 3 of spec-kitty#6185, stated here independently of the
# registry so a type added or dropped there without a decision fails this test.
EXPECTED_NAMES = {
    "lane.transitioned.v1",
    "wp.innerstate.changed.v1",
    "project.initialized.v1",
    "mission.created.v1",
    "mission.specify.started.v1",
    "mission.plan.started.v1",
    "mission.tasks.started.v1",
    "mission.specify.completed.v1",
    "mission.plan.completed.v1",
    "mission.tasks.completed.v1",
    "wp.created.v1",
    "wp.reviewer.selfapproved.v1",
    "mission.reopened.v1",
    "mission.followup.recorded.v1",
    "decision.opened.v1",
    "decision.resolved.v1",
    "run.started.v1",
    "run.step.issued.v1",
    "run.step.autocompleted.v1",
    "run.decision.requested.v1",
    "run.decision.answered.v1",
    "run.completed.v1",
    "run.significance.evaluated.v1",
    "run.decision.timeoutexpired.v1",
    "run.decision.denied.v1",
    "retrospective.captured.v1",
    "retrospective.capturefailed.v1",
    "retrospective.skipped.v1",
}
# spec-kitty-events#92: the strict types without a CLI legacy form, read from the 3.0.0
# transport envelope only (registry ``legacy: null``).
TRANSPORT_ONLY = {
    "mission.started.v1",
    "mission.completed.v1",
    "mission.cancelled.v1",
    "mission.closed.v1",
    "mission.phase.entered.v1",
    "wp.review.rolledback.v1",
    "harness.observed.v1",
    "work.observed.v1",
}
EXPECTED_NAMES = EXPECTED_NAMES | TRANSPORT_ONLY
WITH_LEGACY = EXPECTED_NAMES - TRANSPORT_ONLY
URN_BASE = "urn:ai.spec-kitty:schema:"


def _validator(schema: Dict[str, Any]) -> Draft202012Validator:
    Draft202012Validator.check_schema(schema)
    return Draft202012Validator(schema)


def _walk(node: Any) -> Iterator[Tuple[str, Any]]:
    if isinstance(node, dict):
        for key, value in node.items():
            yield key, value
            yield from _walk(value)
    elif isinstance(node, list):
        for item in node:
            yield from _walk(item)


def _valid_fixtures() -> List[Dict[str, Any]]:
    return [
        json.loads(p.read_text(encoding="utf-8")) for p in sorted((_ROWS / "valid").glob("*.json"))
    ]


# ------------------------------------------------------------------------- registry
def test_registry_holds_exactly_the_producer_types() -> None:
    registry = pc.load_registry()
    assert {t["name"] for t in registry["types"]} == EXPECTED_NAMES
    assert len(registry["types"]) == len(EXPECTED_NAMES) == 36
    assert pc.registered_types() == tuple(t["type"] for t in registry["types"])


def test_registry_header() -> None:
    registry = pc.load_registry()
    assert registry["type_prefix"] == "ai.spec-kitty"
    assert registry["specversion"] == "1.0"
    assert registry["schemaversion"] == "4.0.0"
    assert registry["datacontenttype"] == "application/json"
    assert registry["dataschema_base"] == URN_BASE
    assert "INTERIM" not in registry["description"]
    assert registry["envelope_schemas"] == {
        "write": "envelope.schema.json",
        "read": "envelope.read.schema.json",
    }
    assert registry["envelope_schema"] == registry["envelope_schemas"]["write"]


@pytest.mark.parametrize("name", sorted(EXPECTED_NAMES))
def test_entry_is_well_formed(name: str) -> None:
    registry = pc.load_registry()
    entry = pc.get_entry(name)
    assert entry["type"] == f"{registry['type_prefix']}.{name}"
    assert re.fullmatch(r"[a-z0-9]+(\.[a-z0-9]+)*\.v[0-9]+", name)
    assert entry["dataschema"] == registry["dataschema_base"] + name
    assert entry["scope"] in {"mission", "project"}
    assert entry["subject"] in {"none", "wp_id"}
    if entry["scope"] == "project":
        assert entry["subject"] == "none"
    assert (_ROOT / entry["schema"]).is_file()
    assert (entry["legacy"] is None) == (name in TRANSPORT_ONLY)
    if entry["legacy"] is not None:
        assert entry["legacy"]["shape"] in registry["legacy_shapes"]
    transport = entry["transport"]
    if transport is not None:
        assert set(transport) == {"event_type", "lifted", "lane_aliases"}
        for lifted in transport["lifted"]:
            assert _CE_NAME.fullmatch(lifted["attribute"]), lifted
    attributes = [lifted["attribute"] for lifted in entry["lifted"]]
    assert len(attributes) == len(set(attributes)), "an attribute is lifted twice"
    members = [lifted["member"] for lifted in entry["lifted"]]
    assert len(members) == len(set(members)), "a member is lifted twice"
    for attribute in attributes + entry["required_attributes"]:
        assert _CE_NAME.fullmatch(attribute), attribute
        assert attribute != "data"
    for lifted in entry["lifted"]:
        if registry["legacy_shapes"][entry["legacy"]["shape"]]["form"] == "flat":
            assert {"required", "legacy_schema"} <= set(lifted)


def test_every_strict_type_has_exactly_one_transport_entry() -> None:
    by_event_type: Dict[str, List[str]] = {}
    for entry in pc.load_registry()["types"]:
        if entry["transport"] is not None:
            by_event_type.setdefault(entry["transport"]["event_type"], []).append(entry["name"])
    assert set(by_event_type) == set(STRICT_EVENT_TYPES)
    assert all(len(names) == 1 for names in by_event_type.values()), by_event_type
    assert by_event_type["WPStatusChanged"] == ["lane.transitioned.v1"]
    for name in TRANSPORT_ONLY:
        assert pc.get_entry(name)["transport"] is not None, name


def test_lane_transport_lifts_the_repeated_members_and_resolves_the_alias() -> None:
    transport = pc.get_entry("lane.transitioned.v1")["transport"]
    assert [(item["member"], item["attribute"]) for item in transport["lifted"]] == [
        ("mission_slug", "missionslug"),
        ("mission_id", "missionid"),
        ("wp_id", "subject"),
        ("actor", "actor"),
        ("execution_mode", "executionmode"),
    ]
    actor = transport["lifted"][3]
    assert actor["projection"] == "actor_identity"
    assert actor["binding_member"] == "actorbinding"
    assert transport["lane_aliases"] == {
        "from_lane": {"doing": "in_progress"},
        "to_lane": {"doing": "in_progress"},
    }
    for entry in pc.load_registry()["types"]:
        if entry["transport"] is not None and entry["name"] != "lane.transitioned.v1":
            assert entry["transport"]["lifted"] == [] and entry["transport"]["lane_aliases"] == {}


def test_every_dataschema_is_a_urn_and_equals_the_schema_id() -> None:
    for name in EXPECTED_NAMES:
        entry = pc.get_entry(name)
        assert entry["dataschema"] == URN_BASE + name
        assert pc.data_schema(name)["$id"] == entry["dataschema"]
    assert "example.invalid" not in json.dumps(pc.load_registry())


def test_lookup_by_name_and_by_type_and_unknown() -> None:
    assert pc.get_entry("lane.transitioned.v1") == pc.get_entry(
        "ai.spec-kitty.lane.transitioned.v1"
    )
    with pytest.raises(pc.RegistryError):
        pc.get_entry("lane.transitioned.v2")
    with pytest.raises(pc.RegistryError):
        pc.get_entry("dev.spec-kitty.lane.transitioned.v1")
    with pytest.raises(pc.RegistryError):
        pc.row_schema("nope")
    with pytest.raises(pc.RegistryError):
        pc.legacy_row_form_schema("nope")
    with pytest.raises(pc.RegistryError):
        pc.data_schema("nope")


def test_loaders_return_copies() -> None:
    pc.load_registry()["types"].clear()
    pc.get_entry("lane.transitioned.v1")["lifted"].clear()
    pc.data_schema("lane.transitioned.v1")["properties"].clear()
    pc.envelope_schema()["properties"].clear()
    assert len(pc.registered_types()) == 36
    assert pc.get_entry("lane.transitioned.v1")["lifted"]
    assert pc.data_schema("lane.transitioned.v1")["properties"]
    assert pc.envelope_schema()["properties"]


def test_every_schema_file_is_registered() -> None:
    on_disk = {p.name for p in (_ROOT / "data").glob("*.schema.json")}
    registered = {Path(pc.get_entry(n)["schema"]).name for n in EXPECTED_NAMES}
    assert on_disk == registered


def test_deferred_rows_are_named() -> None:
    deferred = {item["legacy"] for item in pc.load_registry()["deferred"]}
    assert {"DecisionPointWidened", "DecisionPointDeferred", "DecisionPointCanceled"} <= deferred
    assert any("glossary" in item for item in deferred)


# ------------------------------------------------------------------------- schemas
@pytest.mark.parametrize("name", sorted(EXPECTED_NAMES))
def test_data_schema_is_self_contained_and_closed(name: str) -> None:
    schema = pc.data_schema(name)
    _validator(schema)
    assert schema["$schema"] == "https://json-schema.org/draft/2020-12/schema"
    assert schema["$id"] == pc.get_entry(name)["dataschema"]
    assert schema["type"] == "object"
    assert schema["additionalProperties"] is False
    keys = {key for key, _ in _walk(schema)}
    assert "$ref" not in keys and "$defs" not in keys and "definitions" not in keys
    assert "default" not in keys, "a default would let a consumer invent a value"
    for member in schema.get("required", []):
        assert member in schema["properties"]


@pytest.mark.parametrize("name", sorted(EXPECTED_NAMES))
def test_composed_schemas_are_valid_schemas(name: str) -> None:
    _validator(pc.row_schema(name))
    _validator(pc.row_schema(name, profile="read"))
    if name in WITH_LEGACY:
        _validator(pc.legacy_row_form_schema(name))
    else:
        with pytest.raises(pc.RegistryError):
            pc.legacy_row_form_schema(name)


def test_envelope_schema_rules() -> None:
    schema = pc.envelope_schema()
    _validator(schema)
    props = schema["properties"]
    assert all(_CE_NAME.fullmatch(name) for name in props)
    assert props["lamportclock"]["maximum"] == 2**31 - 1 and props["lamportclock"]["minimum"] == 0
    assert schema["additionalProperties"] is False
    for required in (
        "specversion",
        "id",
        "source",
        "type",
        "time",
        "lamportclock",
        "nodeid",
        "data",
    ):
        assert required in schema["required"]


_CLOCKLESS_UUID_ROW: Dict[str, Any] = {
    "specversion": "1.0",
    "id": "0f8fad5b-d9cb-469f-a165-70867728950e",
    "source": "/spec-kitty/missions/01J0000000000000000000MSN1",
    "type": "ai.spec-kitty.lane.transitioned.v1",
    "time": "2026-01-01T00:00:00+00:00",
    "datacontenttype": "application/json",
    "dataschema": URN_BASE + "lane.transitioned.v1",
    "schemaversion": "4.0.0",
    "subject": "WP01",
    "missionid": "01J0000000000000000000MSN1",
    "missionslug": "041-demo",
    "actor": "claude",
    "executionmode": "worktree",
    "data": {"from_lane": "planned", "to_lane": "claimed"},
}


def test_read_profile_accepts_what_a_legacy_row_can_carry_and_write_does_not() -> None:
    read = _validator(pc.row_schema("lane.transitioned.v1", profile="read"))
    write = _validator(pc.row_schema("lane.transitioned.v1"))
    assert read.is_valid(_CLOCKLESS_UUID_ROW)
    assert not write.is_valid(_CLOCKLESS_UUID_ROW)
    transport = dict(
        _CLOCKLESS_UUID_ROW,
        id="01J0000000000000000000F001",
        buildid="b-1",
        nodeid="node-a",
        lamportclock=3,
        causationid="01J0000000000000000000F000",
        correlationid="01J0000000000000000000C000",
        projectuuid="5c0f6f0e-3b1a-4d8e-9a55-0d7e3f2b9c11",
        projectslug="demo",
        datatier=2,
    )
    assert read.is_valid(transport)
    assert not write.is_valid(transport)
    assert not read.is_valid(dict(transport, datatier=5))
    assert not read.is_valid(dict(transport, id="0F8FAD5B-D9CB-469F-A165-70867728950E"))
    assert not read.is_valid(dict(transport, dataschema="https://x/lane.transitioned.v1"))
    with pytest.raises(ValueError):
        pc.row_schema("lane.transitioned.v1", profile="other")


def test_read_and_write_profiles_differ_only_where_the_data_model_says() -> None:
    read, write = pc.envelope_schema(profile="read"), pc.envelope_schema()
    assert set(read["properties"]) - set(write["properties"]) == {
        "buildid",
        "causationid",
        "correlationid",
        "projectuuid",
        "projectslug",
        "datatier",
    }
    assert set(write["required"]) - set(read["required"]) == {"lamportclock", "nodeid"}
    assert set(read["required"]) <= set(write["required"])
    for attribute in set(write["properties"]) - {"id", "nodeid"}:
        assert read["properties"][attribute] == write["properties"][attribute], attribute


def test_lane_schema_matches_the_canonical_enum() -> None:
    lanes = [lane.value for lane in Lane]
    assert len(lanes) == 10
    data = pc.data_schema("lane.transitioned.v1")["properties"]
    assert data["to_lane"]["enum"] == lanes
    assert data["from_lane"]["anyOf"][0]["enum"] == lanes
    subtask_values = pc.data_schema("wp.innerstate.changed.v1")["properties"]["delta"][
        "properties"
    ]["subtasks"]["additionalProperties"]["enum"]
    assert subtask_values == lanes + ["skipped"]  # SubtaskValue = Lane | SubtaskStatus.skipped
    assert "uninitialized" not in lanes and "doing" not in lanes


def test_lane_schema_refuses_the_alias_and_the_read_state() -> None:
    validator = _validator(pc.data_schema("lane.transitioned.v1"))
    assert validator.is_valid({"from_lane": None, "to_lane": "planned"})
    assert not validator.is_valid({"from_lane": "planned", "to_lane": "doing"})
    assert not validator.is_valid({"from_lane": "uninitialized", "to_lane": "planned"})
    assert not validator.is_valid({"from_lane": "planned"})


# Types that already have a typed payload model in this package. The schema must name the
# same members as the model, so the two cannot drift apart without a test failing.
_MODELS: Dict[str, Any] = {
    "project.initialized.v1": pl.ProjectInitializedPayload,
    "mission.created.v1": lc.MissionCreatedPayload,
    "mission.specify.started.v1": pl.SpecifyStartedPayload,
    "mission.plan.started.v1": pl.PlanStartedPayload,
    "mission.tasks.started.v1": pl.TasksStartedPayload,
    "mission.specify.completed.v1": pl.SpecifyCompletedPayload,
    "mission.plan.completed.v1": pl.PlanCompletedPayload,
    "mission.tasks.completed.v1": pl.TasksCompletedPayload,
    "wp.created.v1": pl.WPCreatedPayload,
    "mission.reopened.v1": lc.MissionReopenedPayload,
    "mission.followup.recorded.v1": lc.FollowUpRecordedPayload,
    "decision.opened.v1": dp.DecisionPointOpenedInterviewPayload,
    "decision.resolved.v1": dp.DecisionPointResolvedInterviewPayload,
    "run.started.v1": mn.MissionRunStartedPayload,
    "run.step.issued.v1": mn.NextStepIssuedPayload,
    "run.step.autocompleted.v1": mn.NextStepAutoCompletedPayload,
    "run.decision.requested.v1": mn.DecisionInputRequestedPayload,
    "run.decision.answered.v1": mn.DecisionInputAnsweredPayload,
    "run.completed.v1": mn.MissionRunCompletedPayload,
    "mission.started.v1": lc.MissionStartedPayload,
    "mission.completed.v1": lc.MissionCompletedPayload,
    "mission.cancelled.v1": lc.MissionCancelledPayload,
    "mission.closed.v1": lc.MissionClosedPayload,
    "mission.phase.entered.v1": lc.PhaseEnteredPayload,
    "wp.review.rolledback.v1": lc.ReviewRollbackPayload,
    "harness.observed.v1": ho.HarnessObservationPayload,
    "work.observed.v1": wo.WorkObservationPayload,
}
_LOCAL_ONLY = {
    "mission.specify.started.v1": {"artifact_path"},
    "mission.plan.started.v1": {"artifact_path"},
    "mission.tasks.started.v1": {"artifact_path"},
}


@pytest.mark.parametrize("name", sorted(_MODELS))
def test_schema_names_the_same_members_as_its_typed_model(name: str) -> None:
    model = _MODELS[name]
    schema = pc.data_schema(name)
    assert set(schema["properties"]) == set(model.model_fields) | _LOCAL_ONLY.get(name, set())
    model_required = {n for n, f in model.model_fields.items() if f.is_required()}
    assert set(schema["required"]) == model_required


@pytest.mark.parametrize("name", sorted(_MODELS))
def test_what_the_model_writes_validates(name: str) -> None:
    fixture = next(f for f in _valid_fixtures() if f["type"].endswith("." + name))
    model = _MODELS[name]
    payload = {
        k: v for k, v in fixture["input"]["data"].items() if k not in _LOCAL_ONLY.get(name, set())
    }
    dumped = json.loads(model.model_validate(payload).model_dump_json())
    assert _validator(pc.data_schema(name)).is_valid(dumped), name


# ------------------------------------------------------------------------- fixtures
def test_valid_fixtures_cover_every_type_and_are_unique() -> None:
    fixtures = _valid_fixtures()
    assert {f["type"] for f in fixtures} == set(pc.registered_types())
    ids = [f["input"]["id"] for f in fixtures]
    assert len(ids) == len(set(ids))
    clocks = [f["input"]["lamportclock"] for f in fixtures]
    assert len(clocks) == len(set(clocks))


def _independent_projection(entry: Dict[str, Any], legacy: Dict[str, Any]) -> Dict[str, Any]:
    """The data a legacy row yields: written separately from the fixture generator."""
    legacy_shape = pc.load_registry()["legacy_shapes"][entry["legacy"]["shape"]]
    if legacy_shape["form"] == "payload_envelope":
        return copy.deepcopy(legacy["payload"])
    lifted_members = {item["member"] for item in entry["lifted"]}
    constants = set(entry["legacy"]["constants"])
    data = {k: copy.deepcopy(v) for k, v in legacy.items() if k not in lifted_members | constants}
    for item in entry["lifted"]:
        binding = item.get("binding_member")
        if binding and isinstance(legacy.get(item["member"]), dict):
            data[binding] = copy.deepcopy(legacy[item["member"]])
    return data


@pytest.mark.parametrize(
    "fixture",
    [f for f in _valid_fixtures() if "legacy_row" in f],
    ids=lambda f: f["input"]["type"] + ":" + f["input"]["id"][-4:],
)
def test_legacy_row_projects_to_the_fixtures_data_and_attributes(fixture: Dict[str, Any]) -> None:
    entry = pc.get_entry(fixture["type"])
    row, legacy = fixture["input"], fixture["legacy_row"]
    assert row["data"] == _independent_projection(entry, legacy)
    assert row["id"] == legacy["event_id"]
    for item in entry["lifted"]:
        attribute, member = item["attribute"], item["member"]
        if attribute == "actor" or member not in legacy or legacy[member] is None:
            continue
        if attribute == "type":
            continue
        value = legacy[member]
        assert row.get(attribute) == value, f"{member} -> {attribute}"
    shape = pc.load_registry()["legacy_shapes"][entry["legacy"]["shape"]]
    if shape["form"] == "flat":
        # the actor attribute is the identity projection of the row's actor
        actor = legacy["actor"]
        expected = (
            actor
            if isinstance(actor, str)
            else (actor.get("id") or actor.get("tool") or actor.get("role"))
        )
        assert row["actor"] == expected


# ------------------------------------------------------------------------- mutation
def _mutations(row: Dict[str, Any], schema: Dict[str, Any]) -> Iterator[Tuple[str, Dict[str, Any]]]:
    """Single defects that the row schema must reject."""
    data_schema = schema["properties"]["data"]
    extra = copy.deepcopy(row)
    extra["data"]["zz_unknown"] = 1
    yield "unknown data member", extra
    for member in data_schema.get("required", []):
        missing = copy.deepcopy(row)
        missing["data"].pop(member, None)
        yield f"data member {member} missing", missing
    for attribute in schema["required"]:
        gone = copy.deepcopy(row)
        gone.pop(attribute, None)
        yield f"attribute {attribute} missing", gone
    stray = copy.deepcopy(row)
    stray["zz"] = 1
    yield "unknown attribute", stray


@pytest.mark.parametrize(
    "fixture", _valid_fixtures(), ids=lambda f: f["input"]["type"] + ":" + f["input"]["id"][-4:]
)
def test_every_single_defect_is_rejected(fixture: Dict[str, Any]) -> None:
    schema = pc.row_schema(fixture["type"])
    validator = _validator(schema)
    assert validator.is_valid(fixture["input"])
    seen = 0
    for label, mutated in _mutations(fixture["input"], schema):
        seen += 1
        assert not validator.is_valid(mutated), label
    assert seen >= 3  # a floor: the loop above is never vacuous


def test_the_registry_driven_loop_visits_every_type() -> None:
    assert {f["type"] for f in _valid_fixtures()} == set(pc.registered_types())


def test_an_empty_schema_set_would_be_noticed() -> None:
    assert len(pc.registered_types()) > 0 and len(list((_ROOT / "data").glob("*.json"))) > 0


# -------------------------------------------------------------- composed row schemas
def _fixture(name: str) -> Dict[str, Any]:
    return json.loads((_ROWS / "valid" / f"{name}.json").read_text(encoding="utf-8"))


def test_row_schema_subject_and_missionid_rules() -> None:
    lane = _validator(pc.row_schema("lane.transitioned.v1"))
    row = _fixture("lane.transitioned.v1")["input"]
    assert lane.is_valid(row)
    without_subject = {k: v for k, v in row.items() if k != "subject"}
    assert not lane.is_valid(without_subject)

    created = _validator(pc.row_schema("mission.created.v1"))
    mission_level = _fixture("mission.created.v1")["input"]
    assert created.is_valid(mission_level)
    assert not created.is_valid(dict(mission_level, subject="WP01"))
    assert not created.is_valid({k: v for k, v in mission_level.items() if k != "missionid"})
    fallback = _fixture("mission.created.v1.unresolved_mission")["input"]
    assert created.is_valid(fallback)
    assert not created.is_valid(dict(fallback, missionid=mission_level["missionid"]))

    project = _validator(pc.row_schema("project.initialized.v1"))
    init = _fixture("project.initialized.v1")["input"]
    assert project.is_valid(init)
    assert not project.is_valid(dict(init, subject="x"))
    assert not project.is_valid(dict(init, missionid=mission_level["missionid"]))


def test_row_schema_pins_type_and_dataschema() -> None:
    schema = pc.row_schema("wp.created.v1")
    entry = pc.get_entry("wp.created.v1")
    assert schema["properties"]["type"] == {"type": "string", "const": entry["type"]}
    assert schema["properties"]["dataschema"] == {"type": "string", "const": entry["dataschema"]}
    other = _fixture("wp.reviewer.selfapproved.v1")["input"]
    assert not _validator(schema).is_valid(other)


# ----------------------------------------------------------- legacy-row-form generation
def test_legacy_form_of_a_flat_row_puts_the_lifted_members_back() -> None:
    schema = pc.legacy_row_form_schema("lane.transitioned.v1")
    props = schema["properties"]
    assert "actorbinding" not in props and "actorbinding" not in schema["required"]
    for member in (
        "event_id",
        "at",
        "mission_slug",
        "mission_id",
        "wp_id",
        "actor",
        "execution_mode",
    ):
        assert member in props
    assert "mission_id" not in schema["required"] and "wp_id" in schema["required"]
    assert schema["additionalProperties"] is False


def test_legacy_form_constants_and_discriminators() -> None:
    annotation = pc.legacy_row_form_schema("wp.innerstate.changed.v1")
    assert annotation["properties"]["kind"] == {"const": "annotation"}
    retro = pc.legacy_row_form_schema("retrospective.skipped.v1")
    assert retro["properties"]["type"] == {"const": "RetrospectiveSkipped"}
    life = pc.legacy_row_form_schema("mission.created.v1")
    assert life["properties"]["event_type"] == {"type": "string", "const": "MissionCreated"}
    assert life["properties"]["payload"] == {
        k: v
        for k, v in pc.data_schema("mission.created.v1").items()
        if k not in ("$schema", "$id", "title")
    }
    journal = pc.legacy_row_form_schema("run.completed.v1")
    assert (
        journal["properties"]["row_version"]["const"] == 1 and "row_version" in journal["required"]
    )


def test_every_payload_envelope_type_distinguishes_its_event_type() -> None:
    names = [n for n in WITH_LEGACY if pc.get_entry(n)["legacy"]["shape"] != "flat"]
    discriminators = {pc.get_entry(n)["legacy"]["discriminator_value"] for n in names}
    assert len(discriminators) == len(names)


# ------------------------------------------------------------------- the CloudEvents rules
@pytest.mark.parametrize(
    "stamp, ok",
    [
        ("2026-01-01T00:00:00+00:00", True),
        ("2026-01-01T00:00:00Z", True),
        ("2026-01-01T00:00:00.123456+02:00", True),
        ("2026-01-01", False),
        ("2026-01-01T00:00:00", False),
        ("20260101T000000Z", False),
    ],
)
def test_envelope_time_pattern(stamp: str, ok: bool) -> None:
    row = copy.deepcopy(_fixture("lane.transitioned.v1")["input"])
    row["time"] = stamp
    assert _validator(pc.row_schema("lane.transitioned.v1")).is_valid(row) is ok


@pytest.mark.parametrize(
    "clock, ok",
    [
        (0, True),
        (2**31 - 1, True),
        (2**31, False),
        (-1, False),
        (1.5, False),
        ("1", False),
        (None, False),
    ],
)
def test_envelope_clock_bounds(clock: Any, ok: bool) -> None:
    row = copy.deepcopy(_fixture("lane.transitioned.v1")["input"])
    row["lamportclock"] = clock
    assert _validator(pc.row_schema("lane.transitioned.v1")).is_valid(row) is ok


@pytest.mark.parametrize(
    "ident, ok",
    [
        ("01J0000000000000000000F001", True),
        ("01j0000000000000000000f001", False),
        ("0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b", False),
        ("01J0000000000000000000FI01", False),  # I is not Crockford base32
        ("", False),
    ],
)
def test_envelope_id_is_an_upper_case_ulid(ident: str, ok: bool) -> None:
    row = copy.deepcopy(_fixture("lane.transitioned.v1")["input"])
    row["id"] = ident
    assert _validator(pc.row_schema("lane.transitioned.v1")).is_valid(row) is ok


def test_load_does_not_need_jsonschema() -> None:
    """Loading is stdlib only; the module must not import jsonschema."""
    source = Path(pc.__file__).read_text(encoding="utf-8")
    assert "jsonschema" not in re.sub(r'""".*?"""', "", source, flags=re.S).replace("# ", "")


# --------------------------------------------------- guards added after the squad round
_COMMON_RETRO = ["schema_version", "lamport", "wp_id", "force", "execution_mode", "actorbinding"]
# Hand-listed from the real writers, for the types that have no typed model to compare with.
EXPECTED_REQUIRED: Dict[str, List[str]] = {
    "lane.transitioned.v1": ["from_lane", "to_lane"],
    "wp.innerstate.changed.v1": ["delta"],
    "wp.reviewer.selfapproved.v1": [
        "mission_slug",
        "wp_id",
        "implementing_actor",
        "intended_reviewer",
        "failure_reason",
        "fallback_approved",
        "timestamp",
    ],
    "run.significance.evaluated.v1": [
        "run_id",
        "decision_id",
        "step_id",
        "significance_score",
        "effective_band",
        "actor",
    ],
    "run.decision.denied.v1": [
        "run_id",
        "decision_id",
        "actor_type",
        "actor_id",
        "authority_role",
        "rationale_linkage",
        "reason",
    ],
    "retrospective.captured.v1": _COMMON_RETRO
    + [
        "findings_status",
        "record_path",
        "generator_version",
        "policy_source",
        "provenance_kind",
        "proposal_count",
        "evidence_ref_count",
    ],
    "retrospective.capturefailed.v1": _COMMON_RETRO
    + [
        "failure_category",
        "failure_message",
        "remediation_hint",
        "policy_source",
        "attempted_provenance_kind",
        "missing_artifacts",
    ],
    "retrospective.skipped.v1": _COMMON_RETRO
    + [
        "skip_reason",
        "skip_reason_source",
        "policy_source",
        "bypassed_provenance_kind",
        "would_have_attempted",
    ],
}


@pytest.mark.parametrize("name", sorted(EXPECTED_REQUIRED))
def test_required_members_of_the_types_without_a_model(name: str) -> None:
    assert sorted(pc.data_schema(name)["required"]) == sorted(EXPECTED_REQUIRED[name])


_LIFE = ["id", "time", "type"]
EXPECTED_LIFTED: Dict[str, List[str]] = {
    "lane.transitioned.v1": [
        "id",
        "time",
        "missionslug",
        "missionid",
        "subject",
        "actor",
        "executionmode",
    ],
    "wp.innerstate.changed.v1": ["id", "subject", "time", "actor"],
    "retrospective.captured.v1": ["id", "time", "actor", "missionid", "missionslug"],
    "retrospective.capturefailed.v1": ["id", "time", "actor", "missionid", "missionslug"],
    "retrospective.skipped.v1": ["id", "time", "actor", "missionid", "missionslug"],
    "wp.created.v1": _LIFE + ["subject"],
    "wp.reviewer.selfapproved.v1": _LIFE + ["subject"],
    **{name: [] for name in TRANSPORT_ONLY},
}


@pytest.mark.parametrize("name", sorted(EXPECTED_NAMES))
def test_lifted_attributes_match_the_type_table(name: str) -> None:
    got = sorted(item["attribute"] for item in pc.get_entry(name)["lifted"])
    assert got == sorted(EXPECTED_LIFTED.get(name, _LIFE)), name


def _open_objects(node: Any, path: str = "") -> Iterator[str]:
    if isinstance(node, dict):
        if "properties" in node and node.get("additionalProperties") is not False:
            yield path
        for key, value in node.items():
            yield from _open_objects(value, f"{path}/{key}")
    elif isinstance(node, list):
        for index, value in enumerate(node):
            yield from _open_objects(value, f"{path}/{index}")


def test_every_nested_object_is_closed_except_the_one_taken_from_a_model_that_is_open() -> None:
    open_ones = {
        (name, path) for name in EXPECTED_NAMES for path in _open_objects(pc.data_schema(name))
    }
    # ParticipantIdentity is an open model in decisionpoint.py; the schema mirrors it.
    assert open_ones == {("decision.resolved.v1", "/properties/actual_participants/items")}


def test_nested_stray_members_are_rejected() -> None:
    lane = _validator(pc.data_schema("lane.transitioned.v1"))
    review = {"reviewer": "r", "verdict": "approved", "reference": "x"}
    assert lane.is_valid(
        {"from_lane": "approved", "to_lane": "done", "evidence": {"review": review}}
    )
    assert not lane.is_valid(
        {"from_lane": "approved", "to_lane": "done", "evidence": {"review": dict(review, zz=1)}}
    )
    assert not lane.is_valid(
        {"from_lane": "approved", "to_lane": "done", "evidence": {"review": review, "zz": 1}}
    )
    assert not lane.is_valid(
        {
            "from_lane": "a",
            "to_lane": "done",
            "actorbinding": {"role": None, "profile": None, "tool": None, "model": None, "zz": 1},
        }
    )


def test_skipped_subtask_is_accepted() -> None:
    schema = pc.data_schema("wp.innerstate.changed.v1")
    assert _validator(schema).is_valid({"delta": {"subtasks": {"T001": "skipped", "T002": "done"}}})


def _all_nodes(node: Any) -> Iterator[Dict[str, Any]]:
    if isinstance(node, dict):
        yield node
        for value in node.values():
            yield from _all_nodes(value)
    elif isinstance(node, list):
        for value in node:
            yield from _all_nodes(value)


def test_every_date_time_has_a_pattern_that_rejects_non_timestamps() -> None:
    sources: List[Any] = [pc.data_schema(n) for n in EXPECTED_NAMES]
    sources += [pc.load_registry(), pc.envelope_schema()]
    seen = 0
    for source in sources:
        for node in _all_nodes(source):
            if node.get("format") == "date-time":
                seen += 1
                assert "pattern" in node, node
                assert re.search(node["pattern"], "2026-01-01T00:00:00+00:00")
                assert re.search(node["pattern"], "2026-01-01T00:00:00.123456Z")
                for bad in (
                    "yesterday",
                    "2026-01-01",
                    "2026-01-01T00:00:00",
                    "2026-01-01T00:00:00Z\n",
                ):
                    assert not re.search(node["pattern"], bad), bad
    assert seen > 20
    created = pc.data_schema("wp.created.v1")
    bad_row = {
        "mission_slug": "m",
        "wp_id": "WP01",
        "wp_title": "t",
        "actor": "a",
        "created_at": "yesterday",
    }
    assert not _validator(created).is_valid(bad_row)


@pytest.mark.parametrize("attribute", ["id", "nodeid", "missionid", "source", "time"])
def test_a_trailing_newline_is_not_accepted(attribute: str) -> None:
    row = copy.deepcopy(_fixture("lane.transitioned.v1")["input"])
    assert _validator(pc.row_schema("lane.transitioned.v1")).is_valid(row)
    row[attribute] = row[attribute] + "\n"
    assert not _validator(pc.row_schema("lane.transitioned.v1")).is_valid(row)


def test_time_fraction_is_bounded_to_nine_digits() -> None:
    row = copy.deepcopy(_fixture("lane.transitioned.v1")["input"])
    validator = _validator(pc.row_schema("lane.transitioned.v1"))
    row["time"] = "2026-01-01T00:00:00.123456789Z"
    assert validator.is_valid(row)
    row["time"] = "2026-01-01T00:00:00.1234567890Z"
    assert not validator.is_valid(row)


def test_mission_scoped_types_on_a_project_source_need_the_fallback_shape() -> None:
    created = _validator(pc.row_schema("mission.created.v1"))
    fallback = _fixture("mission.created.v1.unresolved_mission")["input"]
    assert created.is_valid(fallback)
    assert not created.is_valid({k: v for k, v in fallback.items() if k != "subject"})
    wp = _validator(pc.row_schema("wp.created.v1"))
    row = copy.deepcopy(_fixture("wp.created.v1")["input"])
    row.pop("missionid")
    row["source"] = "/spec-kitty/projects/P1"
    assert wp.is_valid(row)
    row.pop("subject")
    assert not wp.is_valid(row)


def test_composed_schemas_on_disk_equal_what_the_functions_return() -> None:
    def on_disk(*parts: str) -> Any:
        return json.loads((_ROOT / "composed").joinpath(*parts).read_text(encoding="utf-8"))

    for name in EXPECTED_NAMES:
        assert on_disk("rows", f"{name}.row.schema.json") == pc.row_schema(name), name
        assert on_disk("read", f"{name}.row.schema.json") == pc.row_schema(name, profile="read"), (
            name
        )
    for name in WITH_LEGACY:
        assert on_disk("legacy", f"{name}.legacy.schema.json") == pc.legacy_row_form_schema(name), (
            name
        )
    assert len(list((_ROOT / "composed").rglob("*.json"))) == 2 * 36 + 28


def test_the_packaged_total_order_contract_is_a_copy_of_the_contract() -> None:
    repo = Path(__file__).resolve().parent.parent
    assert (_ROOT / "total-order.md").read_bytes() == (
        repo / "contracts" / "total-order.md"
    ).read_bytes()


def test_every_type_has_invalid_fixtures_in_both_forms() -> None:
    invalid = {
        json.loads(p.read_text(encoding="utf-8"))["type"]
        for p in (_ROWS / "invalid").glob("*.json")
    }
    legacy = {
        json.loads(p.read_text(encoding="utf-8"))["type"]
        for p in (_ROWS / "legacy_invalid").glob("*.json")
    }
    assert invalid == set(pc.registered_types())
    assert legacy == {pc.get_entry(name)["type"] for name in WITH_LEGACY}
