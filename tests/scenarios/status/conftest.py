"""Step definitions for the ``status/`` area (pytest-bdd, the reference binding; #270).

The steps select fixtures and corpus cases through ``tests/support/status_fixtures.py`` and
check each outcome against what the fixture or case pins (``expected_line``,
``expected_error``, the reducer golden, ``expected``). They never carry fixture data, so a new
fixture in a covered family is picked up without editing a feature file. Every selection fails
when it is empty. Keep ``README.md``'s available-steps table in step with this module.
"""

from __future__ import annotations

import json
import random
from dataclasses import dataclass
from importlib import resources
from typing import Any

import jsonschema
from pydantic import ValidationError
from pytest_bdd import given, parsers, then, when

from spec_kitty_events import producer_contract, status_log
from spec_kitty_events.conformance.loader import load_reducer_output, load_replay_stream
from spec_kitty_events.conformance.parity_corpus import (
    REASON_URNS,
    WRITE_REFUSAL_CODES,
    coverage,
    load_parity_cases,
    load_partial_unreachable,
    load_unreachable,
)
from spec_kitty_events.diary import decode_actor, reduce
from spec_kitty_events.status import (
    DoneEvidence,
    ExecutionMode,
    Lane,
    RepoEvidence,
    ReviewVerdict,
    StatusTransitionPayload,
    validate_transition,
)
from tests.support.status_fixtures import (
    ActorFixture,
    Replay,
    accepted_actors_with_keys,
    refused_actors,
    status_append_cases,
    status_diary_replays,
)

_ILLEGAL_TRANSITION = "urn:spec-kitty:write-refusal:illegal_transition"
_EDGE_VIOLATION = "is not allowed"

# --- status/append --------------------------------------------------------------------------


@dataclass(frozen=True)
class Appended:
    case: dict[str, Any]
    line: bytes | None
    refusal: Exception | None


@given("every status-append fixture", target_fixture="append_cases")
def every_append_fixture() -> list[dict[str, Any]]:
    return status_append_cases()


@given(
    parsers.parse('every status-append fixture pinned as "{result:w}"'),
    target_fixture="append_cases",
)
def every_append_fixture_pinned(result: str) -> list[dict[str, Any]]:
    return status_append_cases(result)


@when("each fixture's row is appended", target_fixture="appended")
def each_row_appended(append_cases: list[dict[str, Any]]) -> list[Appended]:
    appended = []
    for case in append_cases:
        row = json.loads(case["input_json"])  # each binding parses the raw text itself
        try:
            appended.append(Appended(case, status_log.encode_status_row(row), None))
        except Exception as refusal:  # noqa: BLE001 - the Then step judges the refusal class
            appended.append(Appended(case, None, refusal))
    return appended


def _assert_bytes(item: Appended) -> None:
    case = item.case
    assert item.refusal is None, f"{case['id']}: refused with {item.refusal!r}"
    assert item.line == case["expected_line"].encode("ascii"), (
        f"{case['id']}: wrote {item.line!r}, pins {case['expected_line']!r}"
    )


def _assert_refusal(item: Appended) -> None:
    case = item.case
    pinned = getattr(status_log, case["expected_error"])
    assert item.line is None, f"{case['id']}: wrote {item.line!r}, pins {case['expected_error']}"
    assert isinstance(item.refusal, pinned), (
        f"{case['id']}: refused with {item.refusal!r}, pins {case['expected_error']}"
    )


@then("every line written is the exact bytes its fixture pins")
def every_line_is_pinned_bytes(appended: list[Appended]) -> None:
    for item in appended:
        assert item.case["expected_result"] == "valid", f"{item.case['id']} pins no line"
        _assert_bytes(item)


@then("every append is refused with the refusal its fixture pins")
def every_append_refused(appended: list[Appended]) -> None:
    for item in appended:
        assert item.case["expected_result"] == "invalid", f"{item.case['id']} pins no refusal"
        _assert_refusal(item)


@then("every append writes the bytes or raises the refusal its fixture pins")
def every_append_as_pinned(appended: list[Appended]) -> None:
    for item in appended:
        if item.case["expected_result"] == "valid":
            _assert_bytes(item)
        else:
            _assert_refusal(item)


# --- status/replay --------------------------------------------------------------------------


@dataclass(frozen=True)
class Reduced:
    replay: Replay
    order: str
    state: dict[str, Any]


@given("every status-diary replay stream with its recorded output", target_fixture="replays")
def every_replay_stream() -> tuple[Replay, ...]:
    return status_diary_replays()


@when("each stream is reduced in its recorded order", target_fixture="reduced")
def each_stream_reduced(replays: tuple[Replay, ...]) -> list[Reduced]:
    return [
        Reduced(replay, "recorded", reduce(load_replay_stream(replay.stream_id)).to_dict())
        for replay in replays
    ]


@when("each stream is reduced in reverse and in a shuffled order", target_fixture="reduced")
def each_stream_reduced_reordered(replays: tuple[Replay, ...]) -> list[Reduced]:
    reduced = []
    for replay in replays:
        rows = load_replay_stream(replay.stream_id)
        shuffled = list(rows)
        random.Random(270).shuffle(shuffled)
        for order, permuted in (("reversed", rows[::-1]), ("shuffled", shuffled)):
            reduced.append(Reduced(replay, order, reduce(permuted).to_dict()))
    return reduced


@then("every stream reduces to its recorded output")
def every_stream_matches_its_output(reduced: list[Reduced]) -> None:
    for item in reduced:
        assert item.state == load_reducer_output(item.replay.output_id), (
            f"{item.replay.stream_id} ({item.order} order) does not reduce to "
            f"{item.replay.output_id}"
        )


# --- status/transition: the parity corpus ---------------------------------------------------


def _corpus_resource(name: str) -> Any:
    return resources.files("spec_kitty_events.conformance").joinpath(
        "status_transition_parity", name
    )


@given("every status-transition parity case", target_fixture="parity_cases")
def every_parity_case() -> list[dict[str, Any]]:
    cases = load_parity_cases()
    assert cases, "the status-transition parity corpus holds no case"
    return cases


@given(
    "every status-transition parity case the corpus pins as a refusal",
    target_fixture="parity_cases",
)
def every_refused_parity_case() -> list[dict[str, Any]]:
    cases = [c for c in every_parity_case() if c["expected"]["result"] == "refusal"]
    assert cases, "the status-transition parity corpus pins no refusal"
    return cases


@given(
    "every parity case refused as an illegal transition from one unforced request",
    target_fixture="parity_cases",
)
def every_illegal_single_request() -> list[dict[str, Any]]:
    cases = [
        c
        for c in every_refused_parity_case()
        if c["expected"].get("reason") == _ILLEGAL_TRANSITION
        and len(c["request"]["units"]) == 1
        and c["request"]["units"][0]["rows"][0].get("force", False) is False
    ]
    assert cases, "no parity case refuses one unforced request as an illegal transition"
    return cases


@then("every case conforms to the corpus case schema")
def every_case_conforms(parity_cases: list[dict[str, Any]]) -> None:
    schema = json.loads(_corpus_resource("case.schema.json").read_text(encoding="utf-8"))
    validator = jsonschema.Draft202012Validator(schema)
    validator.check_schema(schema)
    for case in parity_cases:
        errors = [e.message for e in validator.iter_errors(case)]
        assert not errors, f"{case['id']}: {errors}"


@then("every refusal pins a write-refusal code and a reason from the closed vocabularies")
def every_refusal_in_vocabulary(parity_cases: list[dict[str, Any]]) -> None:
    for case in parity_cases:
        expected = case["expected"]
        assert expected["code"] in WRITE_REFUSAL_CODES, f"{case['id']}: code {expected['code']!r}"
        reason = expected.get("reason")
        assert reason is None or reason in REASON_URNS, f"{case['id']}: reason {reason!r}"


@then("every refusal reason is reached by a case or has its cause recorded as unreachable")
def every_reason_reached_or_explained(parity_cases: list[dict[str, Any]]) -> None:
    reached = {c["expected"]["reason"] for c in parity_cases if c["expected"].get("reason")}
    unreachable = load_unreachable()
    partial = load_partial_unreachable()
    assert all(cause.strip() for cause in (*unreachable.values(), *partial.values()))
    assert reached.isdisjoint(unreachable), sorted(reached & set(unreachable))
    assert set(partial) <= reached, sorted(set(partial) - reached)
    assert reached | set(unreachable) == REASON_URNS, sorted(
        REASON_URNS - reached - set(unreachable)
    )


# --- status/transition: validate_transition's edge graph ------------------------------------


@dataclass(frozen=True)
class EdgeVerdicts:
    corpus_edges: frozenset[tuple[str, str]]
    unforced: dict[tuple[str, str], list[str]]  # lane pair -> its edge violations
    forced: dict[tuple[str, str], list[str]]
    unforced_all: dict[tuple[str, str], list[str]]  # lane pair -> every violation


def _evidence() -> DoneEvidence:
    return DoneEvidence(
        repos=[RepoEvidence(repo="r", branch="main", commit="abc123")],
        verification=[],
        review=ReviewVerdict(reviewer="alice", verdict="approved"),
    )


def _payload(from_lane: str, to_lane: str, *, force: bool) -> StatusTransitionPayload:
    """A payload carrying every field a guard asks for (reason, review_ref, done evidence).

    Guards that depend on the lane pair alone -- the terminal-lane exit and the review-rejection
    family, both lifted only by force -- can still object; the edge steps judge the edge check
    (``... is not allowed``) on its own, and one step pins which guards remain.
    """
    return StatusTransitionPayload(
        mission_slug="m",
        wp_id="WP01",
        from_lane=from_lane,
        to_lane=to_lane,
        actor="a",
        force=force,
        reason="because",
        execution_mode=ExecutionMode.WORKTREE,
        review_ref="feedback://WP01",
        evidence=_evidence(),
    )


def _violations(from_lane: str, to_lane: str, *, force: bool) -> list[str]:
    return list(validate_transition(_payload(from_lane, to_lane, force=force)).violations)


def _edge_violations(from_lane: str, to_lane: str, *, force: bool) -> list[str]:
    return [v for v in _violations(from_lane, to_lane, force=force) if _EDGE_VIOLATION in v]


@given(
    "the lane edges the parity corpus accepts from an unforced request",
    target_fixture="corpus_edges",
)
def corpus_accepted_edges() -> frozenset[tuple[str, str]]:
    edges = coverage(load_parity_cases()).accepted_unforced_edges
    assert edges, "the parity corpus accepts no unforced edge"
    return frozenset((str(Lane(f).value), str(Lane(t).value)) for f, t in edges)


@when(
    "every pair of lanes is validated as a transition carrying every guarded field",
    target_fixture="edge_verdicts",
)
def every_lane_pair_validated(corpus_edges: frozenset[tuple[str, str]]) -> EdgeVerdicts:
    pairs = [(f.value, t.value) for f in Lane for t in Lane]
    return EdgeVerdicts(
        corpus_edges,
        {p: _edge_violations(*p, force=False) for p in pairs},
        {p: _edge_violations(*p, force=True) for p in pairs},
        {p: _violations(*p, force=False) for p in pairs},
    )


@then("exactly the edges the corpus accepts pass the unforced edge check")
def legal_edges_are_the_corpus_edges(edge_verdicts: EdgeVerdicts) -> None:
    legal = {pair for pair, violations in edge_verdicts.unforced.items() if not violations}
    assert legal == edge_verdicts.corpus_edges, (
        f"validate_transition admits {sorted(legal - edge_verdicts.corpus_edges)} beyond the "
        f"corpus and refuses {sorted(edge_verdicts.corpus_edges - legal)} the corpus accepts"
    )


@then("of those edges only the review-rejection family is refused unforced, by its force guard")
def only_review_rejection_needs_force(edge_verdicts: EdgeVerdicts) -> None:
    """The package requires force=True for the review-rejection family (``* -> planned``
    rewinds; docs/events/work-and-status.md), although core's write service acknowledges those
    edges unforced. Every other corpus edge is fully valid once its fields are present."""
    for pair in sorted(edge_verdicts.corpus_edges):
        refusals = edge_verdicts.unforced_all[pair]
        assert all("review-rejection" in v and "force=True" in v for v in refusals), (
            f"{pair}: refused unforced by {refusals}"
        )
    family = {p for p in edge_verdicts.corpus_edges if edge_verdicts.unforced_all[p]}
    assert family, "no corpus edge is in the review-rejection family"
    assert {to for _from, to in family} == {Lane.PLANNED.value}, sorted(family)


@then("every other pair is refused unforced with an edge violation")
def illegal_twins_are_refused(edge_verdicts: EdgeVerdicts) -> None:
    for pair, violations in edge_verdicts.unforced.items():
        if pair not in edge_verdicts.corpus_edges:
            assert violations == [
                f"Transition {Lane(pair[0])} -> {Lane(pair[1])} is not allowed"
            ], f"{pair}: {violations}"


@then("forcing any pair lifts the edge check")
def force_lifts_the_edge_check(edge_verdicts: EdgeVerdicts) -> None:
    assert {pair: v for pair, v in edge_verdicts.forced.items() if v} == {}


@dataclass(frozen=True)
class RequestVerdict:
    case: dict[str, Any]
    pair: tuple[str, str]
    refusals: list[str]


@when("each case's requested lane pair is validated unforced", target_fixture="request_verdicts")
def each_request_validated(parity_cases: list[dict[str, Any]]) -> list[RequestVerdict]:
    verdicts = []
    for case in parity_cases:
        row = case["request"]["units"][0]["rows"][0]
        pair = (row["from_lane"], row["to_lane"])
        try:
            refusals = _edge_violations(*pair, force=False)
        except ValidationError as refusal:
            # A lane outside the vocabulary never reaches the edge check: the payload refuses it.
            known = {lane.value for lane in Lane}
            assert set(pair) - known, f"{case['id']}: {pair} refused as a payload: {refusal}"
            refusals = [f"payload refused: lane outside the vocabulary {sorted(set(pair) - known)}"]
        verdicts.append(RequestVerdict(case, pair, refusals))
    return verdicts


@then("validate_transition refuses every requested pair")
def every_request_refused(request_verdicts: list[RequestVerdict]) -> None:
    for verdict in request_verdicts:
        assert verdict.refusals, (
            f"{verdict.case['id']}: {verdict.pair} passes validate_transition's edge check, "
            "but the corpus refuses it as an illegal transition"
        )


# --- status/transition: the services (JVM; the Python package has no write service) ---------
# "each case's request is applied to its prior log" and "every outcome is the acknowledgement or
# refusal its case pins" are deliberately unbound here: only service/command-cli and
# service/event-log apply a request to a log. PR-EVENTS-STATUS-TRANSITION-007 is @jvm-only
# (operator ruling on #289), bound by TransitionSteps.java in service/command-cli.

# --- status/actor ---------------------------------------------------------------------------


@dataclass(frozen=True)
class DecodedActor:
    fixture: ActorFixture
    decoded: Any
    refusal: ValueError | None


@given(
    parsers.parse("every structured status actor with {count:d} keys that its fixture accepts"),
    target_fixture="actors",
)
def every_accepted_actor(count: int) -> list[ActorFixture]:
    return accepted_actors_with_keys(count)


@given(
    parsers.parse('every structured status actor its fixture refuses for "{violation:w}"'),
    target_fixture="actors",
)
def every_refused_actor(violation: str) -> list[ActorFixture]:
    return refused_actors(violation)


@when("each actor is decoded as a status actor", target_fixture="decoded_actors")
def each_actor_decoded(actors: list[ActorFixture]) -> list[DecodedActor]:
    decoded = []
    for fixture in actors:
        try:
            decoded.append(DecodedActor(fixture, decode_actor(fixture.actor), None))
        except ValueError as refusal:
            decoded.append(DecodedActor(fixture, None, refusal))
    return decoded


@then("every actor decodes to itself, every key kept")
def every_actor_kept(decoded_actors: list[DecodedActor]) -> None:
    for item in decoded_actors:
        assert item.refusal is None, f"{item.fixture.origin}: refused: {item.refusal}"
        assert item.decoded == item.fixture.actor, f"{item.fixture.origin}: {item.decoded!r}"


@then("every actor is refused")
def every_actor_refused(decoded_actors: list[DecodedActor]) -> None:
    for item in decoded_actors:
        assert item.refusal is not None, f"{item.fixture.origin}: accepted as {item.decoded!r}"


@then("the producer contract's actor schema gives the same verdict")
def schema_agrees(decoded_actors: list[DecodedActor]) -> None:
    for item in decoded_actors:
        schema = producer_contract.data_schema(item.fixture.base)["properties"]["actorbinding"]
        accepted = jsonschema.Draft202012Validator(schema).is_valid(item.fixture.actor)
        assert accepted is (item.refusal is None), (
            f"{item.fixture.origin}: the actorbinding schema "
            f"{'accepts' if accepted else 'refuses'} what the decoder "
            f"{'accepts' if item.refusal is None else 'refuses'}"
        )
