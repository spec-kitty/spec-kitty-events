"""``diary.decode_actor``: the 4-key structured actor and the optional fifth key ``profile_source`` (#263).

The rule is core's ``specify_cli.status.models.decode_actor``: exactly
``{role, profile, tool, model}`` or those four plus ``profile_source``, every value
``str | None``, ``profile_source`` one of ``invocation``, ``flag``, ``agent_string``,
``wp_frontmatter`` or null, and null exactly when ``profile`` is null. Every other dict
is refused (fail closed); every non-dict value is coerced to ``str``.
"""

from __future__ import annotations

from typing import Any, Dict, Optional

import pytest

from spec_kitty_events.diary import (
    DiaryError,
    StatusEvent,
    decode_actor,
    parse_diary,
    reduce,
)

_SOURCES = ("invocation", "flag", "agent_string", "wp_frontmatter")


def _four(profile: Optional[str] = "implementer-ivan") -> Dict[str, Any]:
    return {"role": "implementer", "profile": profile, "tool": "claude", "model": None}


def _five(source: Optional[str], profile: Optional[str] = "implementer-ivan") -> Dict[str, Any]:
    return {**_four(profile), "profile_source": source}


def _row(actor: Any) -> Dict[str, Any]:
    return {
        "actor": actor,
        "at": "2026-11-13T10:00:00+00:00",
        "event_id": "01JAAAA0000000000000000263",
        "evidence": None,
        "execution_mode": "worktree",
        "force": False,
        "from_lane": "planned",
        "mission_slug": "263-profile-source",
        "reason": None,
        "review_ref": None,
        "to_lane": "claimed",
        "wp_id": "WP01",
    }


@pytest.mark.parametrize("profile", ["implementer-ivan", None])
def test_the_four_key_actor_is_unchanged(profile: Optional[str]) -> None:
    actor = _four(profile)
    decoded = decode_actor(actor)
    assert decoded == actor
    assert isinstance(decoded, dict)
    assert list(decoded) == ["role", "profile", "tool", "model"]
    assert decoded is not actor  # a copy, never the caller's dict


@pytest.mark.parametrize("source", _SOURCES)
def test_every_known_source_is_accepted_with_a_profile(source: str) -> None:
    actor = _five(source)
    decoded = decode_actor(actor)
    assert decoded == actor
    assert isinstance(decoded, dict)
    assert list(decoded) == ["role", "profile", "tool", "model", "profile_source"]


def test_a_null_source_is_accepted_with_a_null_profile() -> None:
    actor = _five(None, profile=None)
    assert decode_actor(actor) == actor


def test_the_key_order_of_the_input_does_not_matter() -> None:
    actor = {"profile_source": "flag", "model": "m", "tool": "t", "profile": "p", "role": "r"}
    decoded = decode_actor(actor)
    assert isinstance(decoded, dict)
    assert list(decoded) == ["role", "profile", "tool", "model", "profile_source"]


@pytest.mark.parametrize("source", [*_SOURCES])
def test_a_source_without_a_profile_is_refused(source: str) -> None:
    with pytest.raises(ValueError, match="null exactly when profile is null"):
        decode_actor(_five(source, profile=None))


def test_a_null_source_with_a_profile_is_refused() -> None:
    with pytest.raises(ValueError, match="null exactly when profile is null"):
        decode_actor(_five(None))


@pytest.mark.parametrize(
    "source", ["guessed", "", "FLAG", "wp-frontmatter", " flag", "agent string"]
)
def test_an_unknown_source_is_refused(source: str) -> None:
    with pytest.raises(ValueError, match="profile_source must be one of"):
        decode_actor(_five(source))


@pytest.mark.parametrize("value", [1, True, ["flag"], {"v": "flag"}])
def test_a_non_string_source_is_refused(value: Any) -> None:
    with pytest.raises(ValueError, match="'profile_source' was"):
        decode_actor(_five(value))


@pytest.mark.parametrize(
    "actor",
    [
        {**_five("flag"), "zz": 1},  # unknown sixth key
        {**_four(), "zz": "x"},  # unknown fifth key
        {**_four(), "profile_sources": "flag"},  # near-miss name
        {"role": "r", "profile": "p", "tool": "t", "profile_source": "flag"},  # model missing
        {"role": "r", "profile": "p", "tool": "t"},
        {"profile": "p"},
        {},
    ],
)
def test_any_other_key_set_still_fails_closed(actor: Dict[str, Any]) -> None:
    with pytest.raises(ValueError, match="structured actor must contain exactly"):
        decode_actor(actor)


def test_a_non_string_core_field_is_still_refused_with_a_source() -> None:
    with pytest.raises(ValueError, match="'model' was int"):
        decode_actor({**_five("flag"), "model": 5})


def test_a_non_dict_is_still_coerced_to_str() -> None:
    assert decode_actor("claude") == "claude"
    assert decode_actor(7) == "7"


def test_a_five_key_row_round_trips_through_status_event() -> None:
    row = _row(_five("wp_frontmatter"))
    event = StatusEvent.from_dict(row)
    assert event.actor == _five("wp_frontmatter")
    assert StatusEvent.from_dict(event.to_dict()).actor == _five("wp_frontmatter")


def test_a_five_key_row_reduces_to_its_tool() -> None:
    state = reduce([_row(_five("invocation"))])
    assert state.work_packages["WP01"]["actor"] == "claude"
    assert parse_diary([_row(_five("agent_string"))]).transitions[0].actor == _five("agent_string")


@pytest.mark.parametrize(
    "actor",
    [_five("guessed"), _five("flag", profile=None), _five(None), {**_five("flag"), "zz": 1}],
)
def test_a_refused_actor_fails_the_reduction_loud(actor: Dict[str, Any]) -> None:
    with pytest.raises(DiaryError, match="Invalid lane-transition row"):
        reduce([_row(actor)])
