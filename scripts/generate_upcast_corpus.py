#!/usr/bin/env python3
"""Generate (or check) the upcaster corpus under ``producer_contract/fixtures/upcast/``.

The corpus is the one fixture set every binding of the upcaster runs (spec-kitty-events
#92): each case is one legacy log line, the caller context, and the expected outcome,
form, issue codes and golden CloudEvent (canonical text). Inputs come from the
committed fixtures (the 3.0.0 conformance envelopes, every status-diary replay line,
every producer fixture's legacy row) plus the edge cases stated below. Expected values
are what the Python upcaster returns; a change to them is reviewed as a diff of this
corpus, never regenerated blind.

Usage:
    uv run python scripts/generate_upcast_corpus.py           # write
    uv run python scripts/generate_upcast_corpus.py --check   # fail on drift
"""

from __future__ import annotations

import argparse
import copy
import json
import shutil
import sys
from pathlib import Path
from typing import Any, Dict, Iterator, List, Optional, Tuple

from spec_kitty_events import producer_contract as pc
from spec_kitty_events.cloudevents import (
    UpcastContext,
    canonical_text,
    upcast_line,
    validate_cloudevent,
)

ROOT = Path(pc.__file__).resolve().parent
CASES = ROOT / "fixtures" / "upcast" / "cases"
CONFORMANCE = ROOT.parent / "conformance" / "fixtures"
MSN = "01J0000000000000000000MSN1"
PRJ = "5c0f6f0e-3b1a-4d8e-9a55-0d7e3f2b9c11"
# The caller context of each status-diary replay file: the Mission's meta.json would
# supply its id. The replay files carry none, so the corpus assigns one per file.
_DIARY_MISSION = "01J0000000000000000000RP{:02d}"
_TRANSPORT_MARKERS = {"event_type", "aggregate_id", "payload", "node_id", "build_id"}


def _json_line(row: Any) -> str:
    return json.dumps(row, ensure_ascii=False)


def _case(
    group: str,
    name: str,
    line: str,
    context: Optional[Dict[str, str]],
    origin: str,
    notes: str,
) -> Dict[str, Any]:
    ctx = context or {}
    result = upcast_line(line, UpcastContext(**ctx))
    golden = canonical_text(result.row) if result.mapped else None
    valid = None
    if result.mapped:
        valid = validate_cloudevent(result.row, profile="read") == []
    return {
        "case": name,
        "group": group,
        "origin": origin,
        "notes": notes,
        "context": ctx,
        "line": line,
        "expected": {
            "outcome": result.outcome,
            "form": result.form,
            "codes": list(result.codes),
            "golden": golden,
            "valid": valid,
        },
    }


# ------------------------------------------------------------------ sources
def _producer_fixtures() -> List[Dict[str, Any]]:
    return [
        json.loads(p.read_text(encoding="utf-8"))
        for p in sorted((ROOT / "fixtures" / "rows" / "valid").glob("*.json"))
    ]


def _context_of(event: Dict[str, Any]) -> Dict[str, str]:
    ctx: Dict[str, str] = {}
    if "missionid" in event:
        ctx["missionid"] = event["missionid"]
    if "missionslug" in event:
        ctx["missionslug"] = event["missionslug"]
    if event["source"].startswith("/spec-kitty/projects/"):
        ctx["projectuuid"] = event["source"].rsplit("/", 1)[1]
    return ctx


def _transport_from(event: Dict[str, Any], entry: Dict[str, Any]) -> Dict[str, Any]:
    """The 3.0.0 envelope a producer fixture's CloudEvent was, by design section 3."""
    payload = copy.deepcopy(event["data"])
    binding = payload.pop("actorbinding", None)
    for item in entry["transport"]["lifted"]:
        attribute = item["attribute"]
        value = event.get(attribute)
        if item.get("binding_member") and binding is not None:
            value = binding
        if value is not None:
            payload[item["member"]] = value
    if entry["scope"] == "project":
        aggregate = PRJ
    elif "subject" in event and entry["subject"] == "wp_id":
        aggregate = f"mission/{event['missionid']}/wp/{event['subject']}"
    else:
        aggregate = f"mission/{event['missionid']}"
    return {
        "event_id": event["id"],
        "event_type": entry["transport"]["event_type"],
        "aggregate_id": aggregate,
        "payload": payload,
        "timestamp": event["time"],
        "build_id": "build-1",
        "node_id": event["nodeid"],
        "lamport_clock": event["lamportclock"],
        "causation_id": None,
        "project_uuid": PRJ,
        "project_slug": "demo",
        "correlation_id": "01J0000000000000000000C001",
        "schema_version": "3.0.0",
        "data_tier": 0,
    }


def _transport_cases() -> Iterator[Dict[str, Any]]:
    seen = set()
    for fixture in _producer_fixtures():
        entry = pc.get_entry(fixture["type"])
        if entry["transport"] is None or entry["name"] in seen:
            continue
        row = fixture.get("transport_row")
        if row is None:
            if "missionid" not in fixture["input"] and entry["scope"] != "project":
                continue  # the unresolved-mission variant: covered by the cli group
            row = _transport_from(fixture["input"], entry)
        seen.add(entry["name"])
        yield _case(
            "transport",
            entry["name"],
            _json_line(row),
            None,
            "producer_contract/fixtures/rows/valid",
            f"{entry['transport']['event_type']} as a 3.0.0 transport envelope.",
        )


def _conformance_envelopes() -> Iterator[Tuple[str, Any]]:
    for path in sorted(CONFORMANCE.rglob("*")):
        rel = path.relative_to(CONFORMANCE).as_posix()
        if "invalid" in rel or path.suffix not in (".json", ".jsonl"):
            continue
        text = path.read_text(encoding="utf-8")
        values: List[Any] = []
        if path.suffix == ".jsonl":
            values = [json.loads(line) for line in text.splitlines() if line.strip()]
        else:
            try:
                loaded = json.loads(text)
            except ValueError:
                continue
            values = loaded if isinstance(loaded, list) else [loaded]
        for value in values:
            candidates = [value] + (list(value.values()) if isinstance(value, dict) else [])
            for candidate in candidates:
                if isinstance(candidate, dict) and _TRANSPORT_MARKERS <= candidate.keys():
                    yield rel, candidate


def _conformance_cases() -> Iterator[Dict[str, Any]]:
    seen = set()
    for index, (rel, row) in enumerate(_conformance_envelopes()):
        line = _json_line(row)
        if line in seen:
            continue
        seen.add(line)
        name = f"{Path(rel).stem}.{row.get('event_type', 'row')}.{index:03d}"
        yield _case(
            "conformance",
            name,
            line,
            None,
            f"conformance/fixtures/{rel}",
            "A 3.0.0 envelope from the conformance fixtures.",
        )


def _diary_cases() -> Iterator[Dict[str, Any]]:
    replay = CONFORMANCE / "status_diary" / "replay"
    for number, path in enumerate(sorted(replay.glob("*.jsonl")), start=1):
        context = {"missionid": _DIARY_MISSION.format(number)}
        for lineno, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
            if not line.strip():
                continue
            yield _case(
                "diary",
                f"{path.stem}.{lineno:03d}",
                line,
                context,
                f"conformance/fixtures/status_diary/replay/{path.name}:{lineno}",
                "A status.events.jsonl line, read with the Mission id from context.",
            )


def _cli_cases() -> Iterator[Dict[str, Any]]:
    for path in sorted((ROOT / "fixtures" / "rows" / "valid").glob("*.json")):
        fixture = json.loads(path.read_text(encoding="utf-8"))
        if "legacy_row" not in fixture:
            continue
        yield _case(
            "cli",
            path.stem,
            _json_line(fixture["legacy_row"]),
            _context_of(fixture["input"]),
            f"producer_contract/fixtures/rows/valid/{path.name}",
            "The persisted legacy line of a producer fixture.",
        )


def _lane(**overrides: Any) -> Dict[str, Any]:
    row: Dict[str, Any] = {
        "event_id": "01J0000000000000000000E001",
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


def _envelope(**overrides: Any) -> Dict[str, Any]:
    row: Dict[str, Any] = {
        "event_id": "01J0000000000000000000E101",
        "event_type": "WPStatusChanged",
        "aggregate_id": f"mission/{MSN}/wp/WP01",
        "payload": {
            "mission_slug": "041-demo",
            "mission_id": MSN,
            "wp_id": "WP01",
            "from_lane": "claimed",
            "to_lane": "in_progress",
            "actor": "claude",
            "force": False,
            "reason": None,
            "execution_mode": "worktree",
            "review_ref": None,
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


def _payload(**overrides: Any) -> Dict[str, Any]:
    payload: Dict[str, Any] = copy.deepcopy(_envelope()["payload"])
    payload.update(overrides)
    return payload


_WORK = json.loads(
    (CONFORMANCE / "work_observation" / "replay" / "late_and_gap_stream.jsonl")
    .read_text(encoding="utf-8")
    .splitlines()[0]
)


def _nested(levels: int) -> Dict[str, Any]:
    """An object nested `levels` deep: {"a": {"a": ... {} }}."""
    node: Dict[str, Any] = {}
    for _ in range(levels - 1):
        node = {"a": node}
    return node


CTX = {"missionid": MSN, "missionslug": "041-demo"}
EDGE: List[Tuple[str, Any, Optional[Dict[str, str]], str]] = [
    (
        "id-uuid-hyphenated-upper",
        _json_line(_lane(event_id="0F8FAD5B-D9CB-469F-A165-70867728950E")),
        CTX,
        "A legacy UUID id is lower-cased.",
    ),
    (
        "id-uuid-bare-hex",
        _json_line(_lane(event_id="0f8fad5bd9cb469fa16570867728950e")),
        CTX,
        "A 32-hex UUID is hyphenated: the same id as the case above.",
    ),
    (
        "id-ulid-lower-case",
        _json_line(_lane(event_id="01j0000000000000000000e001")),
        CTX,
        "A lower-case ULID is upper-cased.",
    ),
    (
        "id-not-an-id",
        _json_line(_lane(event_id="evt-1")),
        CTX,
        "An id that is neither ULID nor UUID.",
    ),
    (
        "time-not-rfc3339",
        _json_line(_lane(at="2026-01-01 00:00:00")),
        CTX,
        "A time without the T separator and offset.",
    ),
    (
        "mission-id-conflict",
        _json_line(_lane(mission_id="01J0000000000000000000MSN2")),
        CTX,
        "The row and the context name different Missions.",
    ),
    (
        "mission-slug-conflict",
        _json_line(_lane()),
        {"missionid": MSN, "missionslug": "other"},
        "The row and the context name different slugs.",
    ),
    (
        "mission-id-missing",
        _json_line(_lane()),
        None,
        "No Mission id and no project to fall back to.",
    ),
    (
        "mission-id-from-row-only",
        _json_line(_lane(mission_id=MSN)),
        None,
        "The row carries its Mission id; no context needed.",
    ),
    (
        "project-fallback-from-context",
        _json_line(_lane()),
        {"projectuuid": PRJ},
        "No Mission id: the project-scoped fallback source.",
    ),
    (
        "feature-slug",
        _json_line({("feature_slug" if k == "mission_slug" else k): v for k, v in _lane().items()}),
        CTX,
        "The published legacy name of mission_slug.",
    ),
    (
        "structured-actor",
        _json_line(
            _lane(
                actor={
                    "role": "reviewer",
                    "profile": "reviewer-renata",
                    "tool": "claude",
                    "model": "m",
                }
            )
        ),
        CTX,
        "actor is the identity projection; the object goes to data.actorbinding.",
    ),
    (
        "structured-actor-no-identity",
        _json_line(_lane(actor={"profile": "p"})),
        CTX,
        "An actor object without tool or role: no actor attribute, the binding kept whole; the binding schema needs all four members, so the result is not valid.",
    ),
    (
        "lane-missing-execution-mode",
        _json_line({k: v for k, v in _lane().items() if k != "execution_mode"}),
        CTX,
        "A required lifted member is absent.",
    ),
    (
        "annotation-structured-actor",
        _json_line(
            {
                "event_id": "01J0000000000000000000A001",
                "at": "2026-01-01T00:00:02+00:00",
                "wp_id": "WP01",
                "actor": {
                    "role": "implementer",
                    "profile": "implementer-ivan",
                    "tool": "codex",
                    "model": "m",
                },
                "kind": "annotation",
                "delta": {"subtasks": {"T001": "done"}},
            }
        ),
        CTX,
        "An annotation row with a structured actor.",
    ),
    (
        "transport-doing-alias",
        _json_line(_envelope(payload=_payload(to_lane="doing"))),
        None,
        "The doing alias resolves to in_progress (a published mapping).",
    ),
    (
        "transport-summary",
        _json_line(_envelope(payload=_payload(summary="Started on the parser"))),
        None,
        "summary rides in data (shape A only).",
    ),
    (
        "transport-no-from-lane",
        _json_line(_envelope(payload={k: v for k, v in _payload().items() if k != "from_lane"})),
        None,
        "No from_lane: mapped without a default, so the result is not valid (nothing is invented).",
    ),
    (
        "transport-slug-aggregate",
        _json_line(
            _envelope(
                aggregate_id="mission/replay-mission/wp/WP01", payload=_payload(mission_id=None)
            )
        ),
        None,
        "A slug segment is never a mission id: project source, the aggregate is the subject.",
    ),
    (
        "transport-unprefixed-aggregate",
        _json_line(_envelope(aggregate_id="WP01", payload=_payload(mission_id=None))),
        None,
        "An unprefixed aggregate.",
    ),
    (
        "transport-other-prefix",
        _json_line(
            _envelope(
                event_type="MissionStarted",
                aggregate_id="connector/conn-github-001",
                payload={
                    "mission_id": "m-1",
                    "mission_type": "software-dev",
                    "initial_phase": "specify",
                    "actor": "u",
                },
            )
        ),
        None,
        "Any other prefix: project source, the aggregate is the subject.",
    ),
    (
        "transport-mission-id-conflict",
        _json_line(_envelope(payload=_payload(mission_id="01J0000000000000000000MSN2"))),
        None,
        "The aggregate and the payload name different Missions.",
    ),
    (
        "transport-subject-conflict",
        _json_line(_envelope(aggregate_id=f"mission/{MSN}/wp/WP02")),
        None,
        "The aggregate and the payload name different work packages.",
    ),
    (
        "transport-wp-aggregate-mission-type",
        _json_line(
            _envelope(
                event_type="MissionStarted",
                payload={
                    "mission_id": MSN,
                    "mission_type": "software-dev",
                    "initial_phase": "specify",
                    "actor": "u",
                },
            )
        ),
        None,
        "A work-package aggregate on a mission-level type.",
    ),
    (
        "transport-schema-2.9.0",
        _json_line(_envelope(schema_version="2.9.0")),
        None,
        "A pre-3.0 envelope: recognised, not mapped.",
    ),
    (
        "transport-unregistered",
        _json_line(_envelope(event_type="Sparkle")),
        None,
        "An event type the registry does not know.",
    ),
    (
        "transport-unknown-member",
        _json_line(_envelope(team_slug="core")),
        None,
        "A member Event does not define: not dropped silently.",
    ),
    (
        "transport-clock-overflow",
        _json_line(_envelope(lamport_clock=2**31)),
        None,
        "lamport_clock above the CloudEvents Integer range.",
    ),
    (
        "transport-data-tier-out-of-range",
        _json_line(_envelope(data_tier=5)),
        None,
        "data_tier outside 0..4.",
    ),
    (
        "transport-subnormal-floats",
        _json_line(
            dict(
                _WORK,
                payload=dict(
                    _WORK["payload"],
                    extensions={"x-min": 5e-324, "x-two": 1e-323, "x-max": 1.7976931348623157e308},
                ),
            )
        ),
        None,
        "Subnormal and extreme floats print in Python repr's shortest form in every binding.",
    ),
    (
        "id-non-ascii-kelvin-sign",
        _json_line(_lane(event_id="01J0000000000000000000K101")),
        CTX,
        "Ids are ASCII: a KELVIN SIGN does not case-fold to K.",
    ),
    (
        "transport-non-ascii-aggregate-segment",
        _json_line(
            _envelope(
                aggregate_id="mission/01J0000000000000000000MſN1/wp/WP01",
                payload=_payload(mission_id=None),
            )
        ),
        None,
        "A LATIN SMALL LETTER LONG S is not an s: the segment is no ULID, so the project fallback applies.",
    ),
    (
        "feature-slug-not-a-string",
        _json_line(
            {
                ("feature_slug" if k == "mission_slug" else k): (1.0 if k == "mission_slug" else v)
                for k, v in _lane(mission_slug=1).items()
            }
        ),
        CTX,
        "Slugs are strings before they are compared (1 and 1.0 are not slugs).",
    ),
    (
        "transport-data-pattern-trailing-newline",
        _json_line(
            _envelope(
                event_type="HarnessObservation",
                aggregate_id="session/sess-1",
                payload={"kind": "presence", "harness": "claude\n", "session_id": "sess-1"},
            )
        ),
        None,
        "Mapped, but not valid: data patterns end in (?![\\s\\S]), so a trailing newline fails in every binding.",
    ),
    (
        "nesting-at-the-limit",
        _json_line(_lane(policy_metadata=_nested(254))),
        CTX,
        "255 levels in (the row, policy_metadata and 253 below it); the CloudEvent adds data: 256, the limit.",
    ),
    (
        "nesting-beyond-the-limit",
        _json_line(_lane(policy_metadata=_nested(256))),
        CTX,
        "257 levels: deeper than the shared limit of 256, refused as NOT_JSON in every binding.",
    ),
    (
        "not-json-nan",
        '{"event_id": "01J0000000000000000000E001", "x": NaN}',
        CTX,
        "NaN is not JSON (RFC 8259), whatever a parser tolerates.",
    ),
    (
        "transport-project-uuid-urn-form",
        _json_line(_envelope(project_uuid=f"urn:uuid:{PRJ}")),
        None,
        "A project uuid is hyphenated or 32 hex digits; the urn and brace forms are not.",
    ),
    (
        "transport-project-uuid-bare-hex",
        _json_line(_envelope(project_uuid=PRJ.replace("-", "").upper())),
        None,
        "A 32-hex project uuid is copied as written.",
    ),
    (
        "transport-clock-max",
        _json_line(_envelope(lamport_clock=2**31 - 1)),
        None,
        "The largest clock that fits.",
    ),
    (
        "transport-clockless",
        _json_line(
            _envelope(
                lamport_clock=None, project_slug=None, causation_id="01J0000000000000000000E100"
            )
        ),
        None,
        "A clockless envelope (11.0.0); nulls omitted, causation id kept.",
    ),
    (
        "transport-floats-and-unicode",
        _json_line(
            dict(
                _WORK,
                payload=dict(
                    _WORK["payload"],
                    text='Groß\t→ 漢字   "q" \\ \u0001',
                    extensions={
                        "x-score": 0.1,
                        "x-tiny": 1e-05,
                        "x-big": 1e16,
                        "x-neg": -0.0,
                        "x-int": 12345678901234567890,
                        "x-exp": 1.5e300,
                    },
                ),
            )
        ),
        None,
        "Canonical bytes: floats, a large integer, escapes and non-ASCII text.",
    ),
    ("not-json", "{broken", CTX, "A line that is not JSON."),
    ("not-an-object-array", "[1, 2]", CTX, "A JSON array."),
    ("not-an-object-string", '"text"', CTX, "A JSON string."),
    ("not-an-object-null", "null", CTX, "JSON null."),
    (
        "unrecognised-event-name",
        _json_line(
            {"event_id": "x", "at": "2026-01-01T00:00:00Z", "event_name": "Retro", "payload": {}}
        ),
        CTX,
        "A diary row of an unknown kind (shape D): kept as written.",
    ),
    (
        "lifecycle-unregistered",
        _json_line(
            {
                "event_id": "01J0000000000000000000G001",
                "event_type": "GlossaryTermAdded",
                "aggregate_id": MSN,
                "aggregate_type": "Mission",
                "schema_version": "5.0.0",
                "timestamp": "2026-01-01T00:00:00+00:00",
                "project_uuid": PRJ,
                "project_slug": "demo",
                "payload": {},
            }
        ),
        CTX,
        "A deferred CLI row kind (glossary): no registry entry.",
    ),
    (
        "lifecycle-unexpected-member",
        _json_line(
            {
                "event_id": "01J0000000000000000000G002",
                "event_type": "MissionReopened",
                "aggregate_id": MSN,
                "aggregate_type": "Mission",
                "schema_version": "5.0.0",
                "timestamp": "2026-01-01T00:00:00+00:00",
                "project_uuid": PRJ,
                "project_slug": "demo",
                "payload": {},
                "origin": "x",
            }
        ),
        CTX,
        "A CLI envelope member its form does not define.",
    ),
]


def _edge_cases() -> Iterator[Dict[str, Any]]:
    for name, line, context, notes in EDGE:
        yield _case("edge", name, line, context, "scripts/generate_upcast_corpus.py", notes)


def _already_cloudevent() -> Dict[str, Any]:
    event = json.loads(
        next(c for c in _edge_cases() if c["case"] == "structured-actor")["expected"]["golden"]
    )
    return _case(
        "edge",
        "already-cloudevent",
        _json_line(event),
        CTX,
        "scripts/generate_upcast_corpus.py",
        "A CloudEvent row passes through unchanged.",
    )


def build() -> Dict[str, str]:
    cases = [
        *_transport_cases(),
        *_conformance_cases(),
        *_diary_cases(),
        *_cli_cases(),
        *_edge_cases(),
        _already_cloudevent(),
    ]
    out: Dict[str, str] = {}
    for case in cases:
        rel = f"{case['group']}/{case['case']}.json"
        assert rel not in out, rel
        out[rel] = json.dumps(case, indent=2, ensure_ascii=False) + "\n"
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="fail if the committed corpus differs")
    args = parser.parse_args()
    wanted = build()
    if args.check:
        on_disk = {
            p.relative_to(CASES).as_posix(): p.read_text(encoding="utf-8")
            for p in CASES.rglob("*.json")
        }
        drift = sorted(set(wanted) ^ set(on_disk)) + sorted(
            k for k in wanted if k in on_disk and wanted[k] != on_disk[k]
        )
        if drift:
            print("upcast corpus drift:", *drift[:20], sep="\n  ")
            return 1
        print(f"upcast corpus in sync ({len(wanted)} cases)")
        return 0
    if CASES.exists():
        shutil.rmtree(CASES)
    for rel, text in wanted.items():
        path = CASES / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
    print(f"wrote {len(wanted)} cases to {CASES}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
