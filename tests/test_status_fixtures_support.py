"""The ``status/actor`` selections cover every status-actor fixture (#270).

``actor.feature`` selects accepted actors by key count and refused ones by their fixture's
``violation``. This reads those values from the feature itself and checks that together they
select every structured status actor ``producer_row_actors()`` finds, so a fixture with a new
violation or key count fails here instead of being covered by nothing.
"""

from __future__ import annotations

import re
from pathlib import Path

from tests.support.status_fixtures import (
    accepted_actors_with_keys,
    producer_row_actors,
    refused_actors,
)

ACTOR_FEATURE = Path(__file__).parent / "scenarios" / "status" / "actor" / "actor.feature"


def test_actor_feature_selections_cover_every_status_actor_fixture() -> None:
    text = ACTOR_FEATURE.read_text(encoding="utf-8")
    counts = {int(n) for n in re.findall(r"actor with (\d+) keys that its fixture accepts", text)}
    violations = set(re.findall(r'actor its fixture refuses for "(\w+)"', text))
    assert counts and violations
    selected = [a for n in sorted(counts) for a in accepted_actors_with_keys(n)]
    selected += [a for v in sorted(violations) for a in refused_actors(v)]
    assert sorted(selected, key=repr) == sorted(producer_row_actors(), key=repr)
