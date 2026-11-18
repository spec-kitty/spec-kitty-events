"""Listers for the status fixtures and corpus the ``status/`` scenarios describe (test-only).

The ``status/`` BDD area (spec-kitty-events#270) never names a fixture: it selects them through
these listers, so a fixture added to a family is picked up without editing a feature file.
Every lister fails on an empty selection, so a scenario never passes on nothing.

Deliberately not part of the package: the selections are test vocabulary, not public API (C-007).
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path
from typing import Any

from spec_kitty_events import producer_contract
from spec_kitty_events.conformance.loader import load_status_append_cases
from tests.support.class_taxonomy import MANIFEST_PATH

PRODUCER_ROWS: Path = Path(producer_contract.__file__).resolve().parent / "fixtures" / "rows"
STATUS_DIARY_REPLAY_DIR = "status_diary/replay/"
_OUTPUT_SUFFIX = "-output"
# Where a producer-row fixture pins its error when the structured actor is what is wrong.
_ACTOR_ERROR_PATHS = ("/actor", "/data/actorbinding")
# The status event types whose rows carry the structured status actor (#263).
STATUS_ACTOR_TYPES = ("lane.transitioned.v1", "wp.innerstate.changed.v1")


def status_append_cases(expected_result: str | None = None) -> list[dict[str, Any]]:
    """The ``status_append`` documents (``valid``, ``invalid`` or both), as the manifest lists them."""
    cases = [
        case
        for case in load_status_append_cases()
        if expected_result is None or case["expected_result"] == expected_result
    ]
    assert cases, f"no status_append fixture is {expected_result or 'listed'}"
    return cases


@dataclass(frozen=True)
class Replay:
    stream_id: str
    output_id: str


@lru_cache(maxsize=1)
def status_diary_replays() -> tuple[Replay, ...]:
    """Every ``status_diary/replay`` stream in the manifest, paired with its ``-output`` golden.

    A stream without its golden, or a golden without its stream, fails the listing.
    """
    manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
    entries = [e for e in manifest["fixtures"] if e["path"].startswith(STATUS_DIARY_REPLAY_DIR)]
    streams = {e["id"] for e in entries if e["fixture_type"] == "replay_stream"}
    outputs = {e["id"] for e in entries if e["fixture_type"] == "reducer_output"}
    assert streams, "the manifest lists no status_diary replay stream"
    paired = {stream + _OUTPUT_SUFFIX for stream in streams}
    assert paired == outputs, (
        f"unpaired status_diary replay fixtures: streams without output "
        f"{sorted(paired - outputs)}, outputs without stream {sorted(outputs - paired)}"
    )
    return tuple(Replay(s, s + _OUTPUT_SUFFIX) for s in sorted(streams))


@dataclass(frozen=True)
class ActorFixture:
    """One structured actor carried by a producer-row fixture."""

    origin: str  # <subfolder>/<stem>
    base: str  # the event type's base name, for its actorbinding schema
    actor: dict[str, Any]
    expected: str  # what the fixture pins: valid or invalid
    violation: str | None


def _structured_actors(fixture: dict[str, Any]) -> list[dict[str, Any]]:
    row = fixture["input"]
    data = row.get("data") if isinstance(row.get("data"), dict) else {}
    candidates = (
        data.get("actorbinding"),
        row.get("actor"),
        fixture.get("legacy_row", {}).get("actor"),
    )
    return [actor for actor in candidates if isinstance(actor, dict)]


@lru_cache(maxsize=1)
def producer_row_actors() -> tuple[ActorFixture, ...]:
    """Every structured status actor in a valid producer-row fixture of a status event type,
    and every one in an invalid fixture whose pinned error points at that actor."""
    found: list[ActorFixture] = []
    for sub in ("valid", "invalid", "legacy_invalid"):
        for path in sorted((PRODUCER_ROWS / sub).glob("*.json")):
            fixture = json.loads(path.read_text(encoding="utf-8"))
            base = fixture.get("base") or fixture["type"].split(".", 2)[-1]
            if base not in STATUS_ACTOR_TYPES:
                continue
            if fixture["expected"] == "invalid" and not str(
                fixture.get("expected_error_path", "")
            ).startswith(_ACTOR_ERROR_PATHS):
                continue
            for actor in _structured_actors(fixture):
                found.append(
                    ActorFixture(
                        f"{sub}/{path.stem}",
                        base,
                        actor,
                        fixture["expected"],
                        fixture.get("violation"),
                    )
                )
    return tuple(found)


def accepted_actors_with_keys(count: int) -> list[ActorFixture]:
    selected = [a for a in producer_row_actors() if a.expected == "valid" and len(a.actor) == count]
    assert selected, f"no valid producer-row fixture carries a {count}-key structured actor"
    return selected


def refused_actors(violation: str) -> list[ActorFixture]:
    selected = [
        a for a in producer_row_actors() if a.expected == "invalid" and a.violation == violation
    ]
    assert selected, f"no producer-row fixture refuses a structured actor for {violation!r}"
    return selected
