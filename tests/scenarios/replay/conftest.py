"""Step definitions for the ``replay/`` area (pytest-bdd, the reference binding).

The steps name a replay fixture folder (``spec_kitty_events/conformance/fixtures/<category>/
replay/``) and a reducer, fold every ``<stem>.jsonl`` stream there and compare the result with
the ``<stem>_output.json`` it pins. They never carry stream data, so a stream added to a covered
folder is picked up without touching a feature file. The pairing is the test-only
``tests/support/replay.py`` (not package API, C-007). Keep ``README.md``'s available-steps table
in step with this module.
"""

from __future__ import annotations

import random
from collections.abc import Callable
from dataclasses import dataclass
from importlib.resources import files
from pathlib import Path
from typing import Any

from pytest_bdd import given, parsers, then, when

from spec_kitty_events.decisionpoint import reduce_decision_point_events
from spec_kitty_events.lifecycle import reduce_lifecycle_events
from spec_kitty_events.models import Event
from tests.support.class_taxonomy import FIXTURES_ROOT
from tests.support.replay import ReplayPair, list_replay_pairs

# The reducers a step can name, by the name the feature files use.
REDUCERS: dict[str, Callable[[list[Event]], Any]] = {
    "lifecycle": reduce_lifecycle_events,
    "decision point": reduce_decision_point_events,
}
# Fixed seeds, so a reordering failure reproduces.
SHUFFLE_SEEDS = (11, 23, 42, 97, 131)


@dataclass(frozen=True)
class Fold:
    pair: ReplayPair
    order: str  # "file order", "reversed", or the seed the rows were shuffled with
    state: Any


def _fold(reducer: str, rows: list[dict[str, Any]]) -> Any:
    assert reducer in REDUCERS, f"no reducer named {reducer!r}; known: {sorted(REDUCERS)}"
    return REDUCERS[reducer]([Event(**row) for row in rows]).model_dump(mode="json")


@given("the conformance fixtures shipped with the package")
def packaged_fixtures() -> None:
    root = files("spec_kitty_events.conformance").joinpath("fixtures")
    assert root.joinpath("manifest.json").is_file(), (
        "spec_kitty_events.conformance ships no fixtures"
    )
    assert Path(str(root)) == FIXTURES_ROOT


@given(
    parsers.parse('every replay stream in the fixture folder "{folder}"'),
    target_fixture="pairs",
)
def every_replay_stream(folder: str) -> list[ReplayPair]:
    pairs = list_replay_pairs(folder)
    assert pairs, f"the fixture folder {folder!r} holds no replay stream"
    for pair in pairs:
        assert pair.rows(), f"{pair.rel_path} holds no event"
    return pairs


@when(
    parsers.parse('each stream is folded by the "{reducer}" reducer'),
    target_fixture="folds",
)
def folded(pairs: list[ReplayPair], reducer: str) -> list[Fold]:
    return [Fold(pair, "file order", _fold(reducer, pair.rows())) for pair in pairs]


@when(
    parsers.parse('each stream is folded by the "{reducer}" reducer with its rows shuffled'),
    target_fixture="folds",
)
def folded_shuffled(pairs: list[ReplayPair], reducer: str) -> list[Fold]:
    folds = []
    for pair in pairs:
        # Reversed is always a different order for a stream of two or more rows.
        folds.append(Fold(pair, "reversed", _fold(reducer, pair.rows()[::-1])))
        for seed in SHUFFLE_SEEDS:
            rows = pair.rows()
            random.Random(seed).shuffle(rows)
            folds.append(Fold(pair, f"seed {seed}", _fold(reducer, rows)))
    return folds


@then("every fold is the state its fixture pins")
def every_fold_as_pinned(folds: list[Fold]) -> None:
    for fold in folds:
        assert fold.state == fold.pair.pinned_output(), (
            f"{fold.pair.rel_path} ({fold.order}) folds to a state other than "
            f"{fold.pair.output.name}"
        )
