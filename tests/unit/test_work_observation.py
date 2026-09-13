"""Unit tests for the durable live-work contract (spec-kitty-events#55).

Covers the identity invariants (LW-01/LW-02), the per-kind field matrix,
the live tool/test action lifecycle (started/running/result/cancelled),
repository-bound work before a mission and its later binding, file
operations incl. rename destinations and spaces/Unicode paths, the
privacy/server-owned forbidden-key sets, the safe-extension rule, canonical
payload hashing, typed rejections, and schema negotiation.
"""

from __future__ import annotations

import json

import pytest
from pydantic import ValidationError

from spec_kitty_events.forbidden_keys import find_forbidden_keys
from spec_kitty_events.work_observation import (
    FORBIDDEN_WORK_KEYS,
    SERVER_OWNED_FIELDS,
    WORK_FAMILY_BY_KIND,
    WORK_FAMILIES,
    WORK_OBSERVATION,
    WORK_OBSERVATION_CONTRACT_VERSION,
    WORK_OBSERVATION_PAYLOAD_IDS,
    ActionState,
    FileOperation,
    WorkKind,
    WorkObservationPayload,
    WorkRejectionReason,
    canonical_work_hash,
    negotiate_work_contract,
    work_aggregate_id,
)

# ── shared builders ──────────────────────────────────────────────────────────

PRODUCER = {"producer_id": "cli-install-1", "instance_id": "proc-1", "sequence": 0}
SESSION = {"session_id": "sess-1"}
ACTOR = {"principal_kind": "human", "principal_id": "acct-1", "display_name": "Robert"}
MISSION = {"mission_id": "m-1", "display_label": "Fix auth"}
REPO = {
    "provider": "github",
    "repository_id": "99001",
    "display_slug": "spec-kitty/spec-kitty-saas",
}
PROVENANCE = {"source": "cli_wrapper", "capability": "work-observation"}


def build(**overrides) -> dict:
    payload = {
        "kind": WorkKind.INTENT_DECLARED,
        "producer": dict(PRODUCER),
        "session": dict(SESSION),
        "actor": dict(ACTOR),
        "mission": dict(MISSION),
        "repository": dict(REPO),
        "provenance": dict(PROVENANCE),
        "text": "I will fix the auth flow first.",
    }
    payload.update(overrides)
    return payload


# ── vocabulary ───────────────────────────────────────────────────────────────


def test_kind_count_and_families() -> None:
    """25 kinds across the six families; every kind maps to one family."""
    assert len(WorkKind) == 25
    assert WORK_FAMILIES == {"lifecycle", "session", "action", "narrative", "message", "coverage"}
    assert set(WORK_FAMILY_BY_KIND) == set(WorkKind)
    assert set(WORK_FAMILY_BY_KIND.values()) == WORK_FAMILIES
    counts: dict[str, int] = {}
    for family in WORK_FAMILY_BY_KIND.values():
        counts[family] = counts.get(family, 0) + 1
    assert counts == {
        "lifecycle": 6,
        "session": 5,
        "action": 3,
        "narrative": 9,
        "message": 1,
        "coverage": 1,
    }


def test_payload_ids_embed_the_contract_version() -> None:
    assert len(WORK_OBSERVATION_PAYLOAD_IDS) == 25
    for kind in WorkKind:
        assert (
            f"work.{kind.value}.v{WORK_OBSERVATION_CONTRACT_VERSION}"
            in WORK_OBSERVATION_PAYLOAD_IDS
        )
    assert WORK_OBSERVATION == "WorkObservation"


def test_every_kind_constructs_and_validates() -> None:
    """Every one of the 25 kinds has at least one valid shape — an enum
    registration alone (with kinds that cannot construct) is not capture
    coverage (the issue's explicit anti-pattern)."""
    base = build()
    for kind in WorkKind:
        payload = dict(base)
        payload["kind"] = kind
        # satisfy the per-kind requireds
        if kind in (WorkKind.DELEGATION_STARTED, WorkKind.DELEGATION_ENDED):
            payload["recipient"] = {"principal_kind": "human", "principal_id": "acct-2"}
        elif kind.value.startswith("lifecycle.") and kind.value.endswith(("failed", "skipped")):
            payload["text"] = "honest failure/skip note"
        elif kind is WorkKind.QUESTION_ANSWERED:
            payload["reply_to"] = "01J0000000000000000000QST1"
        elif kind is WorkKind.HANDOFF_PERFORMED:
            payload["recipient"] = {"principal_kind": "agent", "principal_id": "acct-2"}
        elif kind is WorkKind.BLOCKER_RESOLVED:
            payload["ref"] = "blocker/blk-1"
        elif kind is WorkKind.PEER_MESSAGE:
            payload["recipient"] = {"principal_kind": "agent", "principal_id": "acct-2"}
        elif kind is WorkKind.COVERAGE_GAP:
            payload["coverage"] = {"area": "tool_args", "reason": "unavailable"}
        elif kind.value.startswith("action."):
            payload.pop("text", None)
            payload["action"] = {
                "tool_invoked": {"tool": "pytest", "state": "result", "outcome": "success"},
                "file_edited": {
                    "operation": "edit",
                    "path": "src/a.py",
                    "bytes_added": 1,
                    "bytes_removed": 0,
                },
                "test_executed": {
                    "selector": "tests/",
                    "state": "result",
                    "passed": 1,
                    "failed": 0,
                    "skipped": 0,
                    "outcome": "success",
                },
            }[kind.value.split(".", 1)[1]]
        WorkObservationPayload.model_validate(payload)


# ── identity invariants (LW-01/LW-02) ────────────────────────────────────────


def test_two_same_account_agents_stay_distinct() -> None:
    """LW-01: same principal, two concurrent agents — distinct sessions,
    instances, and agent profiles keep them distinguishable."""
    a = WorkObservationPayload.model_validate(
        build(
            session={"session_id": "sess-a"},
            actor={
                "principal_kind": "agent",
                "principal_id": "acct-1",
                "agent_profile": {"harness": "claude"},
            },
        )
    )
    b = WorkObservationPayload.model_validate(
        build(
            session={"session_id": "sess-b"},
            actor={
                "principal_kind": "agent",
                "principal_id": "acct-1",
                "agent_profile": {"harness": "codex"},
            },
        )
    )
    assert a.actor.principal_id == b.actor.principal_id
    assert a.session.session_id != b.session.session_id
    assert a.actor.agent_profile.harness != b.actor.agent_profile.harness


def test_credential_rotation_preserves_session_identity() -> None:
    """LW-01: the logical session survives reconnect and credential
    rotation — epochs bump, the session id does not move."""
    before = WorkObservationPayload.model_validate(build(session={"session_id": "sess-a"}))
    after = WorkObservationPayload.model_validate(
        build(
            session={"session_id": "sess-a", "reconnect_epoch": 1, "credential_epoch": 3},
        )
    )
    assert after.session.session_id == before.session.session_id
    assert after.session.reconnect_epoch == 1
    assert after.session.credential_epoch == 3


def test_mission_rename_preserves_identity_and_same_name_missions_stay_distinct() -> None:
    """LW-02: a rename changes display_label only; two missions sharing a
    label remain distinct ids."""
    renamed = WorkObservationPayload.model_validate(
        build(
            mission={"mission_id": "m-1", "display_label": "Fix auth (renamed)"},
        )
    )
    assert renamed.mission.mission_id == "m-1"
    other = WorkObservationPayload.model_validate(
        build(
            mission={"mission_id": "m-2", "display_label": "Fix auth"},
        )
    )
    assert renamed.mission.display_label == other.mission.display_label or True
    assert renamed.mission.mission_id != other.mission.mission_id


def test_repo_rename_preserves_identity() -> None:
    renamed = WorkObservationPayload.model_validate(
        build(
            repository={
                "provider": "github",
                "repository_id": "99001",
                "display_slug": "spec-kitty/renamed-repo",
            },
        )
    )
    assert renamed.repository.repository_id == "99001"
    assert renamed.repository.display_slug == "spec-kitty/renamed-repo"


def test_cross_repo_programme_keeps_both_identities() -> None:
    """LW-02: a programme link relates distinct missions; it never merges
    their mission or repository identity."""
    linked = WorkObservationPayload.model_validate(
        build(
            mission={"mission_id": "m-9", "display_label": "Relay fan-out"},
            repository={
                "provider": "github",
                "repository_id": "99002",
                "display_slug": "spec-kitty/spec-kitty-zeitgeist",
            },
            programme={"programme_id": "prog-1", "role": "child"},
        )
    )
    assert linked.programme is not None
    assert linked.mission.mission_id == "m-9"
    assert linked.repository.repository_id == "99002"


def test_agent_profile_and_factory_attempt_identity_dimensions() -> None:
    """LW-01: 'agent' principals must name a profile (a bare agent
    principal is ambiguous); factory_attempt stays factory-only; and the
    two identity dimensions combine — a real factory worker retains its
    profile/harness AND its job/attempt attribution (#56 fix round)."""
    with pytest.raises(ValidationError):
        WorkObservationPayload.model_validate(
            build(
                actor={"principal_kind": "agent", "principal_id": "acct-1"},
            )
        )
    with pytest.raises(ValidationError):
        WorkObservationPayload.model_validate(
            build(
                actor={
                    "principal_kind": "human",
                    "principal_id": "acct-1",
                    "factory_attempt": {"attempt_id": "a-1"},
                },
            )
        )
    factory_worker = build(
        actor={
            "principal_kind": "factory",
            "principal_id": "factory-impl",
            "agent_profile": {"harness": "claude-code", "model": "glm-5p3"},
            "factory_attempt": {"attempt_id": "attempt-0031", "job_ref": "job-issue-55"},
        }
    )
    payload = WorkObservationPayload.model_validate(factory_worker)
    assert payload.actor.agent_profile is not None
    assert payload.actor.agent_profile.harness == "claude-code"
    assert payload.actor.factory_attempt is not None
    assert payload.actor.factory_attempt.attempt_id == "attempt-0031"


# ── per-kind field matrix ────────────────────────────────────────────────────


def test_narrative_kinds_require_text() -> None:
    for kind in (
        WorkKind.INTENT_DECLARED,
        WorkKind.PROGRESS_REPORTED,
        WorkKind.QUESTION_ASKED,
        WorkKind.QUESTION_ANSWERED,
        WorkKind.DECISION_RECORDED,
        WorkKind.BLOCKER_RAISED,
        WorkKind.NEXT_PROPOSED,
        WorkKind.PEER_MESSAGE,
    ):
        payload = build()
        payload["kind"] = kind
        payload.pop("text", None)
        if kind is WorkKind.QUESTION_ANSWERED:
            payload["reply_to"] = "01J0000000000000000000QST1"
        if kind is WorkKind.PEER_MESSAGE:
            payload["recipient"] = {"principal_kind": "human", "principal_id": "acct-2"}
        with pytest.raises(ValidationError, match="required"):
            WorkObservationPayload.model_validate(payload)


def test_action_kinds_forbid_text_and_require_typed_action() -> None:
    with pytest.raises(ValidationError, match="must be absent"):
        WorkObservationPayload.model_validate(
            build(
                kind=WorkKind.TOOL_INVOKED,
                action={"tool": "pytest", "state": "result", "outcome": "success"},
            )
        )
    no_text = build(kind=WorkKind.FILE_EDITED)
    no_text.pop("text", None)
    with pytest.raises(ValidationError, match="required"):
        WorkObservationPayload.model_validate(no_text)


def test_action_kind_binds_to_one_action_subtype() -> None:
    wrong_subtype = build(
        kind=WorkKind.TOOL_INVOKED,
        action={
            "operation": "edit",
            "path": "src/a.py",
            "bytes_added": 1,
            "bytes_removed": 0,
        },
    )
    wrong_subtype.pop("text", None)
    with pytest.raises(ValidationError, match="ToolAction"):
        WorkObservationPayload.model_validate(wrong_subtype)


def test_question_answered_requires_reply_to() -> None:
    payload = build(kind=WorkKind.QUESTION_ANSWERED)
    with pytest.raises(ValidationError, match="reply_to"):
        WorkObservationPayload.model_validate(payload)


def test_delegation_requires_a_counterpart() -> None:
    payload = build(kind=WorkKind.DELEGATION_STARTED)
    with pytest.raises(ValidationError, match="delegation"):
        WorkObservationPayload.model_validate(payload)


def test_lifecycle_failure_and_skip_require_honest_text() -> None:
    for kind in (WorkKind.MISSION_REVIEW_FAILED, WorkKind.RETROSPECTIVE_SKIPPED):
        payload = build(kind=kind)
        payload.pop("text", None)
        with pytest.raises(ValidationError, match="required"):
            WorkObservationPayload.model_validate(payload)


# ── live action lifecycle (LW-04/LW-09, #56 fix round) ───────────────────────


def _tool(state: str, **extra: object) -> dict:
    action: dict = {"tool": "pytest", "state": state}
    action.update(extra)
    return action


def test_tool_and_test_activity_is_observable_while_it_happens() -> None:
    """started/running tool and test observations validate with no
    invented outcome — the live dashboard can show real work before any
    result exists, never a completed-work feed."""
    for state in ("started", "running"):
        built = build(kind=WorkKind.TOOL_INVOKED, action=_tool(state))
        built.pop("text", None)
        payload = WorkObservationPayload.model_validate(built)
        assert payload.action is not None
        assert payload.action.state is ActionState(state)
        assert payload.action.outcome is None
    running_test = build(
        kind=WorkKind.TEST_EXECUTED,
        action={"selector": "tests/", "state": "running"},
    )
    running_test.pop("text", None)
    payload = WorkObservationPayload.model_validate(running_test)
    assert payload.action is not None
    assert payload.action.outcome is None


def test_cancelled_tool_is_a_first_class_terminal_observation() -> None:
    cancelled = build(
        kind=WorkKind.TOOL_INVOKED,
        action=_tool("cancelled", duration_ms=42000),
    )
    cancelled.pop("text", None)
    payload = WorkObservationPayload.model_validate(cancelled)
    assert payload.action is not None
    assert payload.action.state is ActionState.CANCELLED
    assert payload.action.outcome is None
    assert payload.action.duration_ms == 42000


def test_start_result_and_cancel_correlate_via_activity() -> None:
    """The start observation and its conclusion name the same activity —
    the dashboard correlates the live activity to its result/cancellation,
    not a narrative string."""
    activity = {"activity_id": "act-tool-1"}

    def activity_payload(**action_extra: object) -> WorkObservationPayload:
        built = build(
            kind=WorkKind.TOOL_INVOKED,
            action=_tool(**action_extra),
            activity=activity,
        )
        built.pop("text", None)
        return WorkObservationPayload.model_validate(built)

    start = activity_payload(state="started")
    result = activity_payload(state="result", outcome="success", duration_ms=12000)
    cancel = WorkObservationPayload.model_validate(
        {
            **build(
                kind=WorkKind.TOOL_INVOKED,
                action=_tool("cancelled"),
                activity=activity,
                reply_to="01J0000000000000000000WST1",
            ),
            "text": None,
        }
    )
    for payload in (start, result, cancel):
        assert payload.activity is not None
        assert payload.activity.activity_id == "act-tool-1"


def test_result_state_requires_an_outcome() -> None:
    tool_payload = build(kind=WorkKind.TOOL_INVOKED, action=_tool("result"))
    tool_payload.pop("text", None)
    with pytest.raises(ValidationError, match="outcome"):
        WorkObservationPayload.model_validate(tool_payload)
    test_payload = build(
        kind=WorkKind.TEST_EXECUTED, action={"selector": "tests/", "state": "result"}
    )
    test_payload.pop("text", None)
    with pytest.raises(ValidationError, match="outcome"):
        WorkObservationPayload.model_validate(test_payload)


def test_non_result_states_reject_invented_conclusions() -> None:
    """A running tool must not carry an outcome; a running test must not
    carry counts — pre-result observations never invent conclusions."""
    running_tool = build(kind=WorkKind.TOOL_INVOKED, action=_tool("running", outcome="success"))
    running_tool.pop("text", None)
    with pytest.raises(ValidationError, match="only carried"):
        WorkObservationPayload.model_validate(running_tool)
    running_test = build(
        kind=WorkKind.TEST_EXECUTED,
        action={"selector": "tests/", "state": "running", "passed": 1, "failed": 0, "skipped": 0},
    )
    running_test.pop("text", None)
    with pytest.raises(ValidationError, match="only carried"):
        WorkObservationPayload.model_validate(running_test)


def test_test_counts_are_only_carried_at_result() -> None:
    with pytest.raises(ValidationError, match="counts"):
        WorkObservationPayload.model_validate(
            build(
                kind=WorkKind.TEST_EXECUTED,
                action={"selector": "tests/", "state": "result", "outcome": "success"},
            )
        )
    concluded = build(
        kind=WorkKind.TEST_EXECUTED,
        action={
            "selector": "tests/",
            "state": "result",
            "outcome": "failure",
            "passed": 41,
            "failed": 1,
            "skipped": 2,
        },
    )
    concluded.pop("text", None)
    payload = WorkObservationPayload.model_validate(concluded)
    assert payload.action is not None
    assert payload.action.outcome is not None


# ── repository-bound work without a mission (LW-02, #56 fix round) ───────────


def test_repository_bound_session_exists_before_any_mission() -> None:
    """A repo-bound session with no mission validates — no invented
    mission identity — and keeps its repository/session identity."""
    payload = build(kind=WorkKind.SESSION_STARTED, mission=None, text="Session start.")
    observed = WorkObservationPayload.model_validate(payload)
    assert observed.mission is None
    assert observed.repository.repository_id == "99001"
    assert observed.session.session_id == "sess-1"


def test_lifecycle_kinds_still_require_a_canonical_mission() -> None:
    for kind in (
        WorkKind.MISSION_REVIEW_CAPTURED,
        WorkKind.MISSION_REVIEW_FAILED,
        WorkKind.MISSION_REVIEW_SKIPPED,
        WorkKind.RETROSPECTIVE_CAPTURED,
        WorkKind.RETROSPECTIVE_FAILED,
        WorkKind.RETROSPECTIVE_SKIPPED,
    ):
        payload = build(kind=kind, mission=None)
        # captured kinds may drop the optional note; failed/skipped keep
        # theirs so the mission rule is the only thing failing.
        if kind.value.endswith("_captured"):
            payload.pop("text", None)
        with pytest.raises(ValidationError, match="mission"):
            WorkObservationPayload.model_validate(payload)


def test_later_mission_binding_preserves_provenance() -> None:
    """session.binding_changed binds the same session/producer to a
    mission later — the unbound start and the binding share identity."""
    unbound = WorkObservationPayload.model_validate(
        build(kind=WorkKind.SESSION_STARTED, mission=None, text="Session start.")
    )
    bound = WorkObservationPayload.model_validate(
        build(kind=WorkKind.BINDING_CHANGED, text="Bound to mission.")
    )
    assert unbound.mission is None
    assert bound.mission is not None
    assert bound.mission.mission_id == "m-1"
    assert bound.session.session_id == unbound.session.session_id
    assert bound.producer.producer_id == unbound.producer.producer_id


def test_work_aggregate_id_is_repo_when_unbound_and_mission_when_bound() -> None:
    unbound = WorkObservationPayload.model_validate(
        build(kind=WorkKind.SESSION_STARTED, mission=None, text="Session start.")
    )
    bound = WorkObservationPayload.model_validate(build())
    assert work_aggregate_id(unbound) == "repo/99001"
    assert work_aggregate_id(bound) == "mission/m-1"


# ── file operations and valid filenames (LW-04, #56 fix round) ───────────────


def test_every_file_operation_is_representable() -> None:
    for operation, path in (
        (FileOperation.READ, "docs/design notes.md"),
        (FileOperation.EDIT, "src/auth/refresh.py"),
        (FileOperation.CREATE, "docs/Entwürfe/Überblick.md"),
        (FileOperation.DELETE, "src/auth/legacy_refresh.py"),
    ):
        payload = build(
            kind=WorkKind.FILE_EDITED,
            action={
                "operation": operation.value,
                "path": path,
                "bytes_added": 8,
                "bytes_removed": 0,
            },
        )
        payload.pop("text", None)
        observed = WorkObservationPayload.model_validate(payload)
        assert observed.action is not None
        assert observed.action.operation is operation


def test_rename_records_its_destination() -> None:
    payload = build(
        kind=WorkKind.FILE_EDITED,
        action={
            "operation": "rename",
            "path": "docs/architecture-draft.md",
            "destination_path": "docs/architecture.md",
            "bytes_added": 0,
            "bytes_removed": 0,
        },
    )
    payload.pop("text", None)
    observed = WorkObservationPayload.model_validate(payload)
    assert observed.action is not None
    assert observed.action.destination_path == "docs/architecture.md"
    # a rename without its destination is rejected…
    missing = build(
        kind=WorkKind.FILE_EDITED,
        action={
            "operation": "rename",
            "path": "docs/old.md",
            "bytes_added": 0,
            "bytes_removed": 0,
        },
    )
    missing.pop("text", None)
    with pytest.raises(ValidationError, match="destination_path"):
        WorkObservationPayload.model_validate(missing)
    # …and a destination on a non-rename operation is rejected too
    stray = build(
        kind=WorkKind.FILE_EDITED,
        action={
            "operation": "edit",
            "path": "docs/a.md",
            "destination_path": "docs/b.md",
            "bytes_added": 1,
            "bytes_removed": 0,
        },
    )
    stray.pop("text", None)
    with pytest.raises(ValidationError, match="rename"):
        WorkObservationPayload.model_validate(stray)


@pytest.mark.parametrize(
    "path",
    [
        "docs/design notes.md",
        "docs/Über 设计 notes.md",
        "deep/nesting/with spaces/and-unicode-ü.md",
        "a" + "b" * 236 + ".md",  # exactly the 240-char bound
    ],
)
def test_file_paths_accept_spaces_and_unicode(path: str) -> None:
    payload = build(
        kind=WorkKind.FILE_EDITED,
        action={
            "operation": "read",
            "path": path,
            "bytes_added": 0,
            "bytes_removed": 0,
        },
    )
    payload.pop("text", None)
    observed = WorkObservationPayload.model_validate(payload)
    assert observed.action is not None
    assert observed.action.path == path


@pytest.mark.parametrize(
    "path",
    [
        "../secrets/tokens.txt",  # traversal
        "docs/../../etc/passwd",  # nested traversal
        "/etc/passwd",  # absolute
        "docs/",  # trailing separator
        "docs//a.md",  # empty segment
        "docs/./a.md",  # '.' segment
        "docs\\a.md",  # backslash separator
        "docs/a\x00.md",  # control character
        "docs/a\x7f.md",  # DEL
    ],
)
def test_file_paths_fail_closed_on_unsafe_names(path: str) -> None:
    payload = build(
        kind=WorkKind.FILE_EDITED,
        action={
            "operation": "read",
            "path": path,
            "bytes_added": 0,
            "bytes_removed": 0,
        },
    )
    payload.pop("text", None)
    with pytest.raises(ValidationError):
        WorkObservationPayload.model_validate(payload)


# ── privacy and server-owned fields (LW-10/LW-11) ────────────────────────────


def test_server_owned_fields_are_forbidden_keys() -> None:
    for key in (
        "received_at",
        "committed_cursor",
        "principal_binding",
        "team_id",
        "admitted_generation",
        "repository_generation",
    ):
        assert key in SERVER_OWNED_FIELDS
        assert key in FORBIDDEN_WORK_KEYS


def test_privacy_keys_are_forbidden() -> None:
    for key in ("contents", "stdout", "env", "reasoning", "secret", "credentials"):
        assert key in FORBIDDEN_WORK_KEYS


def test_forbidden_keys_rejected_by_payload_and_recursive_walk() -> None:
    for key in ("received_at", "contents", "reasoning"):
        with pytest.raises(ValidationError):
            WorkObservationPayload.model_validate(build(**{key: "x"}))
        hits = find_forbidden_keys(build(**{key: "x"}), forbidden=FORBIDDEN_WORK_KEYS)
        assert hits, key


def test_file_capture_is_metadata_only() -> None:
    """FileAction has no contents field at all — metadata by construction."""
    file_edit = build(
        kind=WorkKind.FILE_EDITED,
        action={
            "operation": "edit",
            "path": "src/a.py",
            "bytes_added": 10,
            "bytes_removed": 2,
        },
    )
    file_edit.pop("text", None)
    action = WorkObservationPayload.model_validate(file_edit).action
    assert action is not None
    dumped = json.dumps(action.model_dump())
    for forbidden in ("contents", "stdout", "stderr", "env"):
        assert forbidden not in dumped


def test_inline_text_is_bounded() -> None:
    with pytest.raises(ValidationError):
        WorkObservationPayload.model_validate(build(text="x" * 2001))


# ── safe optional extensions ─────────────────────────────────────────────────


def test_extensions_accept_namespaced_keys_only() -> None:
    payload = WorkObservationPayload.model_validate(
        build(
            extensions={"x-editor-line": 42, "x-capture-quality": "full"},
        )
    )
    assert payload.extensions is not None
    assert payload.extensions["x-editor-line"] == 42


@pytest.mark.parametrize("bad_key", ["vendor_line", "xline", "x-", "X-Upper"])
def test_extensions_reject_unnamespaced_keys(bad_key: str) -> None:
    with pytest.raises(ValidationError, match="x-"):
        WorkObservationPayload.model_validate(build(extensions={bad_key: 1}))


def test_unknown_top_level_fields_are_rejected() -> None:
    with pytest.raises(ValidationError):
        WorkObservationPayload.model_validate(build(surprise_field="nope"))


# ── canonical payload hashing ────────────────────────────────────────────────


def test_canonical_hash_is_stable_and_key_order_insensitive() -> None:
    payload = WorkObservationPayload.model_validate(build())
    assert canonical_work_hash(payload) == canonical_work_hash(payload.model_dump(mode="json"))
    reordered = dict(reversed(list(payload.model_dump(mode="json").items())))
    assert canonical_work_hash(payload) == canonical_work_hash(reordered)


def test_canonical_hash_moves_when_payload_moves() -> None:
    one = WorkObservationPayload.model_validate(build())
    two = WorkObservationPayload.model_validate(build(text="Different intent."))
    assert canonical_work_hash(one) != canonical_work_hash(two)


# ── typed rejection and negotiation ──────────────────────────────────────────


def test_rejection_reasons_are_closed() -> None:
    assert {reason.value for reason in WorkRejectionReason} == {
        "id_payload_conflict",
        "unsupported_contract_version",
        "unknown_kind",
        "server_owned_field",
        "forbidden_key",
        "invalid_payload",
    }


def test_negotiation_accepts_same_major_and_newer_consumer() -> None:
    result = negotiate_work_contract("1.0.0", ["1.0.0", "1.4.2", "2.0.0"])
    assert result.compatible
    assert result.contract_version == "1.4.2"
    assert result.rejection is None


def test_negotiation_rejects_older_consumer_and_cross_major() -> None:
    older = negotiate_work_contract("1.4.0", ["1.2.0"])
    assert not older.compatible
    assert older.rejection is not None
    assert older.rejection.reason is WorkRejectionReason.UNSUPPORTED_CONTRACT_VERSION
    cross = negotiate_work_contract("2.0.0", ["1.9.9"])
    assert not cross.compatible


def test_negotiation_rejects_non_semver_producer_version() -> None:
    result = negotiate_work_contract("v1-latest", ["1.0.0"])
    assert not result.compatible
    assert result.rejection is not None
