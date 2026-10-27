"""The read-time upcaster: one legacy row in, one CloudEvent (or the row unchanged) out.

Registry-driven: which members become which attributes, the projections, the payload
member and the lane aliases all come from ``producer_contract/registry.json``. The only
mapping stated here is the fixed attribute set of the 3.0.0 transport envelope (design
section 3). Deterministic and inventive of nothing: no minted id or time, no defaulted
member; the input row is never mutated.
"""

from __future__ import annotations

import copy
import json
import re
from dataclasses import dataclass, field
from functools import lru_cache
from typing import Any, Dict, List, NoReturn, Optional, Set, Tuple, Union

from spec_kitty_events import producer_contract as pc
from spec_kitty_events.models import normalize_event_id

from ._detect import Detection, detect
from ._types import IssueCode, UpcastContext, UpcastError, UpcastIssue, UpcastResult

__all__ = ["upcast", "upcast_line"]

# re.ASCII: case-folding is ASCII-only, as in every other binding (no Kelvin sign for K).
_ULID_RE = re.compile(r"[0-9A-HJKMNP-TV-Z]{26}", re.IGNORECASE | re.ASCII)
# A UUID as the 3.0.0 envelope writes it: hyphenated or 32 hex digits, either case. Stated
# explicitly (not uuid.UUID, which also takes braces and urn: forms) so every binding agrees.
_UUID_RE = re.compile(
    r"[0-9a-f]{8}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{12}",
    re.IGNORECASE | re.ASCII,
)
_MISSION_AGGREGATE_RE = re.compile(r"^mission/([^/]+)(?:/wp/([^/]+))?$")
_CLOCK_MAX = 2**31 - 1
# The deepest nesting a line may have (the root container is depth 1). Part of the contract,
# so every binding refuses the same lines; the Java binding sets Jackson's maxNestingDepth.
MAX_DEPTH = 256

# The 3.0.0 transport envelope (models.Event): its members, and those that must be set.
_TRANSPORT_MEMBERS = frozenset(
    {
        "event_id",
        "event_type",
        "aggregate_id",
        "payload",
        "timestamp",
        "build_id",
        "node_id",
        "lamport_clock",
        "causation_id",
        "project_uuid",
        "project_slug",
        "correlation_id",
        "schema_version",
        "data_tier",
    }
)
_TRANSPORT_REQUIRED = (
    "event_id",
    "event_type",
    "aggregate_id",
    "timestamp",
    "build_id",
    "node_id",
    "project_uuid",
    "correlation_id",
    "schema_version",
)
_TRANSPORT_SCHEMA_VERSION = "3.0.0"

# CLI envelope members whose content the CloudEvent type already states: the aggregate
# kind (``aggregate_type``), the legacy payload schema version and the journal row
# version. Published here so the drop is a mapping, not a silent loss.
_IMPLIED_BY_TYPE = frozenset({"aggregate_type", "schema_version", "row_version"})


class _Failure(Exception):
    def __init__(self, issue: UpcastIssue) -> None:
        super().__init__(issue.message)
        self.issue = issue


def _fail(code: str, message: str) -> NoReturn:
    raise _Failure(UpcastIssue(code, message))


@lru_cache(maxsize=1)
def _header() -> Dict[str, Any]:
    registry = pc.load_registry()
    return {
        "specversion": registry["specversion"],
        "datacontenttype": registry["datacontenttype"],
        "schemaversion": registry["schemaversion"],
        "legacy_shapes": registry["legacy_shapes"],
    }


@lru_cache(maxsize=1)
def _time_re() -> "re.Pattern[str]":
    # The same RFC 3339 pattern the envelope schemas state: one rule, one place.
    return re.compile(pc.envelope_schema("read")["properties"]["time"]["pattern"])


# ----------------------------------------------------------------- member checks
def _string(value: Any, member: str) -> str:
    if not isinstance(value, str) or not value:
        _fail(IssueCode.INVALID_MEMBER, f"{member} must be a non-empty string")
    return value


def _event_id(value: Any, member: str) -> str:
    if isinstance(value, str) and not value.isascii():
        # normalize_event_id folds case with Unicode rules; ids are ASCII in every binding
        _fail(IssueCode.INVALID_ID, f"{member} must be ASCII")
    try:
        return normalize_event_id(value)
    except ValueError as exc:
        _fail(IssueCode.INVALID_ID, f"{member}: {exc}")


def _mission_id(value: Any, member: str) -> str:
    if not isinstance(value, str) or not _ULID_RE.fullmatch(value):
        _fail(IssueCode.INVALID_ID, f"{member} must be a ULID")
    return value.upper()


def _time(value: Any, member: str) -> str:
    if not isinstance(value, str) or not _time_re().match(value):
        _fail(IssueCode.INVALID_TIME, f"{member} must be an RFC 3339 timestamp")
    return value


def _project_uuid(value: Any, member: str) -> str:
    text = _string(value, member)
    if not (len(text) in (32, 36) and _UUID_RE.fullmatch(text)):
        _fail(IssueCode.INVALID_MEMBER, f"{member} must be a UUID")
    return text


def _object(value: Any, member: str) -> Dict[str, Any]:
    if not isinstance(value, dict):
        _fail(IssueCode.INVALID_MEMBER, f"{member} must be an object")
    return copy.deepcopy(value)


def _require(row: Dict[str, Any], members: Tuple[str, ...]) -> None:
    missing = [m for m in members if row.get(m) is None]
    if missing:
        _fail(IssueCode.MISSING_MEMBER, "missing: " + ", ".join(missing))


def _no_strays(row: Dict[str, Any], allowed: Set[str] | frozenset[str]) -> None:
    strays = sorted(set(row) - set(allowed))
    if strays:
        _fail(IssueCode.UNEXPECTED_MEMBER, "not mapped by this form: " + ", ".join(strays))


# ----------------------------------------------------------------- the mapping state
@dataclass
class _Draft:
    """What a row has yielded so far; :func:`_finish` turns it into a CloudEvent."""

    entry: Dict[str, Any]
    context: UpcastContext
    attributes: Dict[str, Any] = field(default_factory=dict)
    data: Dict[str, Any] = field(default_factory=dict)
    mission_ids: List[Tuple[str, str]] = field(default_factory=list)
    slugs: List[Tuple[str, str]] = field(default_factory=list)
    subject: Optional[str] = None
    aggregate: Optional[str] = None
    project: Optional[str] = None
    # values that account for an aggregate without becoming attributes
    aggregate_slugs: List[str] = field(default_factory=list)

    def lift(self, item: Dict[str, Any], value: Any) -> None:
        """Apply one registry ``lifted`` item to a legacy member's value."""
        attribute, member = item["attribute"], item["member"]
        if attribute == "type":
            return  # the discriminator: the entry's type replaces it
        if attribute == "id":
            self.attributes["id"] = _event_id(value, member)
        elif attribute == "time":
            self.attributes["time"] = _time(value, member)
        elif attribute == "missionid":
            self.mission_ids.append((member, _mission_id(value, member)))
        elif attribute == "missionslug":
            self.slugs.append((member, _string(value, member)))
        elif attribute == "subject":
            self.subject = _string(value, member)
        elif "projection" in item:
            self._project_actor(item, value)
        else:
            self.attributes[attribute] = _string(value, member)

    def _project_actor(self, item: Dict[str, Any], value: Any) -> None:
        member = item["member"]
        if isinstance(value, dict):
            self.data[item["binding_member"]] = copy.deepcopy(value)
            if item["projection"] == "retrospective_actor_id":
                identity = value.get("id")
            else:  # actor_identity: diary.actor_identity_str
                identity = value.get("tool") or value.get("role")
        elif isinstance(value, str):
            identity = value
        else:
            _fail(IssueCode.INVALID_MEMBER, f"{member} must be a string or an object")
        if identity is not None and not isinstance(identity, str):
            _fail(IssueCode.INVALID_MEMBER, f"{member} identity must be a string")
        if identity:
            self.attributes[item["attribute"]] = identity


def _one(candidates: List[Tuple[str, str]], code: str, what: str) -> Optional[str]:
    values = {value for _, value in candidates}
    if len(values) > 1:
        detail = ", ".join(f"{source}={value!r}" for source, value in candidates)
        _fail(code, f"{what} disagrees: {detail}")
    return next(iter(values), None)


def _accounted(aggregate: str, mission_id: str, slugs: List[str], subject: Optional[str]) -> bool:
    heads = {mission_id, f"mission/{mission_id}"}
    heads |= set(slugs) | {f"mission/{slug}" for slug in slugs}
    options = set(heads)
    if subject is not None:
        options.add(subject)
        options |= {f"{head}/wp/{subject}" for head in heads if head.startswith("mission/")}
    return aggregate in options


def _finish(draft: _Draft) -> Dict[str, Any]:
    entry, context = draft.entry, draft.context
    if context.missionid is not None:
        draft.mission_ids.append(("context", _mission_id(context.missionid, "context.missionid")))
    if context.missionslug is not None:
        draft.slugs.append(("context", _string(context.missionslug, "context.missionslug")))
    mission_id = _one(draft.mission_ids, IssueCode.MISSION_ID_CONFLICT, "mission id")
    slug = _one(draft.slugs, IssueCode.MISSION_SLUG_CONFLICT, "mission slug")
    project = draft.project
    if project is None and context.projectuuid is not None:
        project = _project_uuid(context.projectuuid, "context.projectuuid")
    subject, aggregate = draft.subject, draft.aggregate

    if entry["scope"] == "project":
        if project is None:
            _fail(IssueCode.MISSION_ID_MISSING, "a project-scoped type needs a project uuid")
        if aggregate is not None and aggregate not in (project, f"project/{project}"):
            _fail(IssueCode.AGGREGATE_UNMAPPABLE, f"aggregate {aggregate!r} is not the project")
        source, subject, mission_id, slug = f"/spec-kitty/projects/{project}", None, None, None
    elif mission_id is not None:
        slugs = ([slug] if slug else []) + draft.aggregate_slugs
        if aggregate is not None and not _accounted(aggregate, mission_id, slugs, subject):
            _fail(IssueCode.AGGREGATE_UNMAPPABLE, f"aggregate {aggregate!r} is not this mission")
        if entry["subject"] == "none" and subject is not None:
            _fail(
                IssueCode.AGGREGATE_UNMAPPABLE, "a work-package aggregate on a mission-level type"
            )
        if entry["subject"] == "wp_id" and subject is None:
            _fail(IssueCode.MISSING_MEMBER, "a work-package type needs its work package")
        source = f"/spec-kitty/missions/{mission_id}"
    else:  # the project-scoped fallback: no mission id is known anywhere
        if project is None:
            _fail(
                IssueCode.MISSION_ID_MISSING, "no mission id, and no project uuid to fall back to"
            )
        if aggregate is not None:
            if (
                subject is not None
                and subject != aggregate
                and not aggregate.endswith(f"/wp/{subject}")
            ):
                _fail(
                    IssueCode.SUBJECT_CONFLICT,
                    f"aggregate {aggregate!r} does not end in {subject!r}",
                )
            subject = aggregate
        elif subject is None:
            _fail(IssueCode.MISSION_ID_MISSING, "no mission id, and no aggregate to use as subject")
        source = f"/spec-kitty/projects/{project}"

    header = _header()
    event: Dict[str, Any] = {
        "specversion": header["specversion"],
        "id": draft.attributes.pop("id"),
        "source": source,
        "type": entry["type"],
        "time": draft.attributes.pop("time"),
        "datacontenttype": header["datacontenttype"],
        "dataschema": entry["dataschema"],
        "schemaversion": header["schemaversion"],
    }
    if subject is not None:
        event["subject"] = subject
    if mission_id is not None:
        event["missionid"] = mission_id
    if slug is not None:
        event["missionslug"] = slug
    event.update(draft.attributes)
    event["data"] = draft.data
    return event


# ----------------------------------------------------------------- the forms
def _map_transport(
    entry: Dict[str, Any], row: Dict[str, Any], context: UpcastContext
) -> Dict[str, Any]:
    if row.get("schema_version") != _TRANSPORT_SCHEMA_VERSION:
        _fail(
            IssueCode.UNSUPPORTED_SCHEMA_VERSION,
            f"only the {_TRANSPORT_SCHEMA_VERSION} transport envelope maps; got {row.get('schema_version')!r}",
        )
    _no_strays(row, _TRANSPORT_MEMBERS)
    _require(row, _TRANSPORT_REQUIRED)
    draft = _Draft(entry, context)
    draft.attributes["id"] = _event_id(row["event_id"], "event_id")
    draft.attributes["time"] = _time(row["timestamp"], "timestamp")
    draft.attributes["buildid"] = _string(row["build_id"], "build_id")
    draft.attributes["nodeid"] = _string(row["node_id"], "node_id")
    clock = row.get("lamport_clock")
    if clock is not None:
        if not isinstance(clock, int) or isinstance(clock, bool):
            _fail(IssueCode.INVALID_MEMBER, "lamport_clock must be an integer")
        if not 0 <= clock <= _CLOCK_MAX:
            _fail(IssueCode.CLOCK_OUT_OF_RANGE, f"lamport_clock {clock} is outside 0..{_CLOCK_MAX}")
        draft.attributes["lamportclock"] = clock
    if row.get("causation_id") is not None:
        draft.attributes["causationid"] = _event_id(row["causation_id"], "causation_id")
    draft.attributes["correlationid"] = _event_id(row["correlation_id"], "correlation_id")
    draft.project = _project_uuid(row["project_uuid"], "project_uuid")
    draft.attributes["projectuuid"] = draft.project
    if row.get("project_slug") is not None:
        draft.attributes["projectslug"] = _string(row["project_slug"], "project_slug")
    tier = row.get("data_tier")
    if tier is not None:
        if not isinstance(tier, int) or isinstance(tier, bool) or not 0 <= tier <= 4:
            _fail(IssueCode.INVALID_MEMBER, "data_tier must be an integer 0..4")
        draft.attributes["datatier"] = tier

    payload = _object(row.get("payload"), "payload")
    transport = entry["transport"]
    for item in transport["lifted"]:
        value = payload.pop(item["member"], None)
        if value is not None:
            draft.lift(item, value)
    for member, aliases in transport["lane_aliases"].items():
        if isinstance(payload.get(member), str) and payload[member] in aliases:
            payload[member] = aliases[payload[member]]
    draft.data.update(payload)
    if isinstance(payload.get("mission_slug"), str):
        draft.aggregate_slugs.append(payload["mission_slug"])

    aggregate = _string(row["aggregate_id"], "aggregate_id")
    match = _MISSION_AGGREGATE_RE.fullmatch(aggregate)
    if match and _ULID_RE.fullmatch(match.group(1)):
        draft.mission_ids.append(("aggregate_id", match.group(1).upper()))
        wp = match.group(2)
        if wp is not None:
            if entry["subject"] != "wp_id":
                _fail(
                    IssueCode.AGGREGATE_UNMAPPABLE,
                    "a work-package aggregate on a mission-level type",
                )
            if draft.subject is not None and draft.subject != wp:
                _fail(
                    IssueCode.SUBJECT_CONFLICT,
                    f"aggregate wp {wp!r} vs payload wp_id {draft.subject!r}",
                )
            draft.subject = wp
    else:
        draft.aggregate = aggregate
    return _finish(draft)


def _map_envelope(
    form: str, entry: Dict[str, Any], row: Dict[str, Any], context: UpcastContext
) -> Dict[str, Any]:
    shape = _header()["legacy_shapes"][form]
    payload_member = shape["payload_member"]
    _no_strays(row, set(shape["members"]) | {shape["discriminator_member"], payload_member})
    _require(row, tuple(shape["required"]))
    draft = _Draft(entry, context)
    lifted = {item["member"] for item in entry["lifted"]}
    for item in entry["lifted"]:
        draft.lift(item, row.get(item["member"]))
    draft.data = _object(row[payload_member], payload_member)
    for member, value in row.items():
        if (
            member in lifted
            or member in _IMPLIED_BY_TYPE
            or member == payload_member
            or value is None
        ):
            continue
        if member == "aggregate_id":
            draft.aggregate = _string(value, member)
            if _ULID_RE.fullmatch(draft.aggregate):
                draft.mission_ids.append((member, draft.aggregate.upper()))
                draft.aggregate = None
        elif member == "project_uuid":
            draft.project = _project_uuid(value, member)
            draft.attributes["projectuuid"] = draft.project
        elif member == "project_slug":
            draft.attributes["projectslug"] = _string(value, member)
        else:  # pragma: no cover - every shape member is handled above
            _fail(IssueCode.UNEXPECTED_MEMBER, f"no mapping for {member}")
    return _finish(draft)


def _map_flat(entry: Dict[str, Any], row: Dict[str, Any], context: UpcastContext) -> Dict[str, Any]:
    members = dict(row)
    for key in entry["legacy"]["constants"]:
        members.pop(key)
    if "feature_slug" in members and entry["name"] == "lane.transitioned.v1":
        # the published legacy name of mission_slug (StatusEvent.from_dict reads it)
        legacy_slug = members.pop("feature_slug")
        current = members.get("mission_slug") or None  # StatusEvent.from_dict reads it with `or`
        # both are strings before they are compared (JSON 1, 1.0 and true are not slugs)
        _string(legacy_slug, "feature_slug")
        if current is not None:
            _string(current, "mission_slug")
        if current is not None and current != legacy_slug:
            _fail(IssueCode.MISSION_SLUG_CONFLICT, "feature_slug and mission_slug disagree")
        members["mission_slug"] = legacy_slug if current is None else current
    draft = _Draft(entry, context)
    for item in entry["lifted"]:
        value = members.pop(item["member"], None)
        if value is None:
            if item["required"]:
                _fail(IssueCode.MISSING_MEMBER, f"missing: {item['member']}")
            continue
        draft.lift(item, value)
    draft.data.update(copy.deepcopy(members))
    return _finish(draft)


def _map(detection: Detection, row: Dict[str, Any], context: UpcastContext) -> Dict[str, Any]:
    entry = detection.entry
    assert entry is not None
    if detection.form == "transport_envelope":
        return _map_transport(entry, row, context)
    if detection.form == "flat":
        return _map_flat(entry, row, context)
    return _map_envelope(detection.form, entry, row, context)


# ----------------------------------------------------------------- entry points
def upcast(
    row: Any,
    context: Optional[UpcastContext] = None,
    *,
    strict: bool = False,
    original_line: Optional[bytes] = None,
) -> UpcastResult:
    """Read one parsed row as a CloudEvent.

    Permissive (the default; local reads and merges): a row that does not map comes back
    unchanged as a ``passthrough`` with the named issue. Strict (ingress, and validating a
    row before it is written): the same row raises :class:`UpcastError`. A row that is
    already a CloudEvent is a pass-through in both modes.

    Strict mode refuses a row that does not map; it does **not** validate the mapped
    event's ``data`` payload against its schema. Flat-shape rows (lane, annotation,
    retrospective) fold any unexpected top-level member into ``data``, so ingress that
    must reject such members has to also call :func:`validate_cloudevent` on the result.
    """
    ctx = context if context is not None else UpcastContext()
    detection = detect(row)
    issue: Optional[UpcastIssue] = None
    if detection.code is not None:
        issue = UpcastIssue(detection.code, _DETECTION_MESSAGES[detection.code])
    else:
        try:
            event = _map(detection, row, ctx)
        except _Failure as failure:
            issue = failure.issue
        else:
            return UpcastResult("mapped", event, detection.form, row, original_line)
    if strict and issue.code != IssueCode.ALREADY_CLOUDEVENT:
        raise UpcastError(issue)
    return UpcastResult("passthrough", row, detection.form, row, original_line, (issue,))


def upcast_line(
    line: Union[str, bytes],
    context: Optional[UpcastContext] = None,
    *,
    strict: bool = False,
) -> UpcastResult:
    """Read one log line (bytes as written, or text) as a CloudEvent; see :func:`upcast`.

    The line's bytes are kept on the result (``original_line``) so a writer that keeps a
    legacy row writes it back unchanged.
    """
    raw = line.encode("utf-8") if isinstance(line, str) else bytes(line)
    try:
        # NaN and the infinities are not JSON (RFC 8259), whatever json.loads tolerates
        row = json.loads(raw.decode("utf-8"), parse_constant=_refuse_constant)
        if _depth(row) > MAX_DEPTH:
            raise ValueError(f"nested deeper than {MAX_DEPTH}")
    except (UnicodeDecodeError, ValueError, RecursionError):
        issue = UpcastIssue(IssueCode.NOT_JSON, "the line is not a JSON value")
        if strict:
            raise UpcastError(issue) from None
        return UpcastResult("passthrough", None, "none", None, raw, (issue,))
    return upcast(row, context, strict=strict, original_line=raw)


def _depth(value: Any) -> int:
    """The container nesting depth of a JSON value, without recursion (root container = 1)."""
    deepest, stack = 0, [(value, 1)]
    while stack:
        node, level = stack.pop()
        children = (
            node.values() if isinstance(node, dict) else node if isinstance(node, list) else None
        )
        if children is None:
            continue
        deepest = max(deepest, level)
        stack.extend((child, level + 1) for child in children)
    return deepest


def _refuse_constant(name: str) -> NoReturn:
    raise ValueError(f"{name} is not JSON")


_DETECTION_MESSAGES: Dict[str, str] = {
    IssueCode.NOT_AN_OBJECT: "the row is not a JSON object",
    IssueCode.ALREADY_CLOUDEVENT: "the row is already a CloudEvent",
    IssueCode.UNRECOGNISED_ROW: "no legacy form matches the row",
    IssueCode.UNREGISTERED_TYPE: "the row's event type has no registry entry for its form",
}
