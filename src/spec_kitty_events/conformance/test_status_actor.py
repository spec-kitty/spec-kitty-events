"""Packaged gate: the diary decoder and the producer contract agree on the structured actor (#263).

``diary.decode_actor`` (what every status read goes through) and the producer contract's
``actorbinding`` schema (what the Python and Java CloudEvents validators read) state the
same rule: exactly ``{role, profile, tool, model}``, or those four plus ``profile_source``
in ``invocation``, ``flag``, ``agent_string``, ``wp_frontmatter`` or null, null exactly
when ``profile`` is null. This module checks that both accept and refuse the same actors:
every structured actor in the packaged producer-row fixtures, plus a fixed table.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Dict, Iterator, List, Tuple

import pytest

from spec_kitty_events import producer_contract as pc
from spec_kitty_events.diary import decode_actor

jsonschema = pytest.importorskip(
    "jsonschema",
    reason="producer conformance needs the conformance extra: pip install spec-kitty-events[conformance]",
)

_ROWS = Path(pc.__file__).resolve().parent / "fixtures" / "rows"
_BINDING_TYPES = ("lane.transitioned.v1", "wp.innerstate.changed.v1")

_P = {"role": "implementer", "profile": "implementer-ivan", "tool": "claude", "model": None}
_N = {**_P, "profile": None}

#: (label, actor, accepted)
_TABLE: Tuple[Tuple[str, Dict[str, Any], bool], ...] = (
    ("four keys", _P, True),
    ("four keys, null profile", _N, True),
    *(
        (f"source {s}", {**_P, "profile_source": s}, True)
        for s in ("invocation", "flag", "agent_string", "wp_frontmatter")
    ),
    ("null source, null profile", {**_N, "profile_source": None}, True),
    ("null source with a profile", {**_P, "profile_source": None}, False),
    ("source without a profile", {**_N, "profile_source": "flag"}, False),
    ("unknown source", {**_P, "profile_source": "guessed"}, False),
    ("non-string source", {**_P, "profile_source": 1}, False),
    ("sixth key", {**_P, "profile_source": "flag", "zz": 1}, False),
    ("model missing", {k: v for k, v in _P.items() if k != "model"}, False),
)


def _binding_schema(name: str) -> Dict[str, Any]:
    binding: Dict[str, Any] = pc.data_schema(name)["properties"]["actorbinding"]
    return binding


def _schema_accepts(name: str, actor: Dict[str, Any]) -> bool:
    return bool(jsonschema.Draft202012Validator(_binding_schema(name)).is_valid(actor))


def _decoder_accepts(actor: Dict[str, Any]) -> bool:
    try:
        decode_actor(actor)
    except ValueError:
        return False
    return True


def _fixture_actors() -> Iterator[Tuple[str, Dict[str, Any]]]:
    for sub in ("valid", "invalid", "legacy_invalid"):
        for path in sorted((_ROWS / sub).glob("*.json")):
            fixture = json.loads(path.read_text(encoding="utf-8"))
            if fixture["type"].split(".", 2)[-1] not in _BINDING_TYPES:
                continue
            row = fixture["input"]
            data = row.get("data") if isinstance(row.get("data"), dict) else {}
            for actor in (
                data.get("actorbinding"),
                row.get("actor"),
                fixture.get("legacy_row", {}).get("actor"),
            ):
                if isinstance(actor, dict):
                    yield f"{sub}/{path.stem}", actor


_FIXTURE_ACTORS: List[Tuple[str, Dict[str, Any]]] = list(_fixture_actors())


def test_the_fixtures_carry_the_fifth_key() -> None:
    assert any("profile_source" in actor for _, actor in _FIXTURE_ACTORS)


@pytest.mark.parametrize("name", _BINDING_TYPES)
@pytest.mark.parametrize(("label", "actor", "accepted"), _TABLE, ids=[t[0] for t in _TABLE])
def test_decoder_and_schema_give_the_pinned_verdict(
    name: str, label: str, actor: Dict[str, Any], accepted: bool
) -> None:
    assert _decoder_accepts(actor) is accepted, label
    assert _schema_accepts(name, actor) is accepted, label


@pytest.mark.parametrize("name", _BINDING_TYPES)
@pytest.mark.parametrize(
    ("origin", "actor"), _FIXTURE_ACTORS, ids=[origin for origin, _ in _FIXTURE_ACTORS]
)
def test_decoder_and_schema_agree_on_every_fixture_actor(
    name: str, origin: str, actor: Dict[str, Any]
) -> None:
    assert _decoder_accepts(actor) is _schema_accepts(name, actor), origin
