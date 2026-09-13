"""WorkObservation: the durable live-work vocabulary (spec-kitty-events#55).

Normative ownership (planning#2268 "Zeitgeist Live Work"): this module is
the *single* owner of the durable work-observation vocabulary — the closed
:class:`WorkKind` set, the per-kind field matrix, the identity sub-models
(:class:`ProducerIdentity`, :class:`SessionIdentity`, :class:`ActorIdentity`,
:class:`MissionIdentity`, :class:`RepositoryIdentity`), the artifact-reference
and provenance models, :data:`FORBIDDEN_WORK_KEYS`, the typed-rejection
vocabulary, and the schema-negotiation rule. Consumers (SaaS durable
ingestion saas#1814, relay fan-out zeitgeist#304, CLI publisher spec-kitty#4266,
harness capture spec-kitty#4268) map onto exactly these payload IDs, never
define a second closed vocabulary, and never route durable work through the
lossy volatile codec (:mod:`spec_kitty_events.zeitgeist_attrs`) — that codec
is for volatile mission/WP moments only.

Durable vs volatile (the line this module draws):

* **Volatile** — :mod:`spec_kitty_events.harness_observation` presence
  heartbeats and the E2 mission/WP moment vocabulary. Observed for the
  retention window, never durable state.
* **Durable** — everything here. Accepted into the durable journal
  (saas#1814), fanned out with recoverable cursors (zeitgeist#304),
  projected into the People/Missions/Repositories views (saas#1816), and
  replayable with explicit gaps (saas#1818).

Envelope conventions (validated by ``spec_kitty_events.strict``, same shape
as HarnessObservation's): ``event_type="WorkObservation"``,
``aggregate_id`` = :func:`work_aggregate_id` — ``mission/<mission_id>``
when the observation is mission-bound, ``repo/<repository_id>`` for
repository-bound work with no mission yet (LW-02: repo-bound sessions
before a mission exists keep a real aggregate identity, never an invented
mission), ``schema_version="3.0.0"``,
``timestamp`` = producer occurrence time (R-T-01), ``correlation_id`` /
``causation_id`` = the standard Event causal semantics, ``node_id`` = the
producer instance, ``lamport_clock`` = the journal clock at emission. The
identity fields below live in the *payload* (where every consumer already
reads them) — the envelope keeps exactly the Event semantics it already
has, so no new envelope field is introduced.

Server-owned fields never appear on the wire (decision mirrored from F1's
"caller fields never grant authority"): the authenticated principal/team
binding, the admitted-repository generation, ``received_at``, and the
committed cursor are derived server-side from the credential at ingestion
(saas#1814). :data:`FORBIDDEN_WORK_KEYS` fails closed on all of them, plus
the privacy set (file contents, raw command environments, secrets, private
reasoning): file capture records *metadata* only.
"""

from __future__ import annotations

import hashlib
import json
import re
from enum import Enum
from types import MappingProxyType
from typing import Any, Dict, Literal, Mapping, Optional, Sequence, Union

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

__all__ = [
    "WORK_OBSERVATION",
    "WORK_OBSERVATION_CONTRACT_VERSION",
    "WorkKind",
    "WORK_FAMILY_BY_KIND",
    "WORK_FAMILIES",
    "PAYLOAD_ID_BY_KIND",
    "WORK_OBSERVATION_PAYLOAD_IDS",
    "FORBIDDEN_WORK_KEYS",
    "FORBIDDEN_WORK_KEYS_VERSION",
    "SERVER_OWNED_FIELDS",
    "ActionOutcome",
    "ActionState",
    "ActorIdentity",
    "ActivityRef",
    "AgentProfileRef",
    "ArtifactReference",
    "CoverageGap",
    "FactoryAttemptRef",
    "FileAction",
    "FileOperation",
    "MissionIdentity",
    "PrincipalRef",
    "ProducerIdentity",
    "ProgrammeLink",
    "RepositoryIdentity",
    "SessionIdentity",
    "SourceProvenance",
    "TestAction",
    "ToolAction",
    "WorkContext",
    "WorkObservationPayload",
    "WorkRejectionReason",
    "TypedRejection",
    "NegotiationResult",
    "canonical_work_hash",
    "work_aggregate_id",
    "negotiate_work_contract",
]


WORK_OBSERVATION: str = "WorkObservation"
"""The single ``event_type`` string carrying every durable work kind."""

WORK_OBSERVATION_CONTRACT_VERSION: str = "1"
"""Work-contract major lineage, embedded in every payload ID (``work.<kind>.v1``)."""


class WorkKind(str, Enum):
    """Closed set of durable work-observation kinds — 25 members, 6 families.

    ``lifecycle`` (6) closes the LW-03 gap: mission review and retrospective
    outcomes are captured/failed/skipped, not just completed — failure and
    skip are first-class, never laundered into silence.
    ``session`` (5) covers session lifecycle, delegation, and mission/repo
    binding changes (LW-01/LW-02). ``action`` (3) covers tool/file/test
    observation with explicit outcomes (LW-04). ``narrative`` (9) covers the
    authored intent/progress/question/answer/decision/handoff/blocker/
    resolution/next vocabulary (LW-05/LW-09). ``message`` (1) is the durable
    peer message. ``coverage`` (1) makes capture gaps explicit (LW-08/LW-10).
    """

    # lifecycle (6)
    MISSION_REVIEW_CAPTURED = "lifecycle.mission_review_captured"
    MISSION_REVIEW_FAILED = "lifecycle.mission_review_failed"
    MISSION_REVIEW_SKIPPED = "lifecycle.mission_review_skipped"
    RETROSPECTIVE_CAPTURED = "lifecycle.retrospective_captured"
    RETROSPECTIVE_FAILED = "lifecycle.retrospective_failed"
    RETROSPECTIVE_SKIPPED = "lifecycle.retrospective_skipped"
    # session (5)
    SESSION_STARTED = "session.started"
    SESSION_ENDED = "session.ended"
    DELEGATION_STARTED = "session.delegation_started"
    DELEGATION_ENDED = "session.delegation_ended"
    BINDING_CHANGED = "session.binding_changed"
    # action (3)
    TOOL_INVOKED = "action.tool_invoked"
    FILE_EDITED = "action.file_edited"
    TEST_EXECUTED = "action.test_executed"
    # narrative (9)
    INTENT_DECLARED = "narrative.intent_declared"
    PROGRESS_REPORTED = "narrative.progress_reported"
    QUESTION_ASKED = "narrative.question_asked"
    QUESTION_ANSWERED = "narrative.question_answered"
    DECISION_RECORDED = "narrative.decision_recorded"
    HANDOFF_PERFORMED = "narrative.handoff_performed"
    BLOCKER_RAISED = "narrative.blocker_raised"
    BLOCKER_RESOLVED = "narrative.blocker_resolved"
    NEXT_PROPOSED = "narrative.next_proposed"
    # message (1)
    PEER_MESSAGE = "message.peer_sent"
    # coverage (1)
    COVERAGE_GAP = "coverage.gap_recorded"


WORK_FAMILIES: frozenset[str] = frozenset(
    {"lifecycle", "session", "action", "narrative", "message", "coverage"}
)
"""The six kind families (support-matrix ``family`` values for this module)."""

WORK_FAMILY_BY_KIND: Mapping[WorkKind, str] = MappingProxyType(
    {
        WorkKind.MISSION_REVIEW_CAPTURED: "lifecycle",
        WorkKind.MISSION_REVIEW_FAILED: "lifecycle",
        WorkKind.MISSION_REVIEW_SKIPPED: "lifecycle",
        WorkKind.RETROSPECTIVE_CAPTURED: "lifecycle",
        WorkKind.RETROSPECTIVE_FAILED: "lifecycle",
        WorkKind.RETROSPECTIVE_SKIPPED: "lifecycle",
        WorkKind.SESSION_STARTED: "session",
        WorkKind.SESSION_ENDED: "session",
        WorkKind.DELEGATION_STARTED: "session",
        WorkKind.DELEGATION_ENDED: "session",
        WorkKind.BINDING_CHANGED: "session",
        WorkKind.TOOL_INVOKED: "action",
        WorkKind.FILE_EDITED: "action",
        WorkKind.TEST_EXECUTED: "action",
        WorkKind.INTENT_DECLARED: "narrative",
        WorkKind.PROGRESS_REPORTED: "narrative",
        WorkKind.QUESTION_ASKED: "narrative",
        WorkKind.QUESTION_ANSWERED: "narrative",
        WorkKind.DECISION_RECORDED: "narrative",
        WorkKind.HANDOFF_PERFORMED: "narrative",
        WorkKind.BLOCKER_RAISED: "narrative",
        WorkKind.BLOCKER_RESOLVED: "narrative",
        WorkKind.NEXT_PROPOSED: "narrative",
        WorkKind.PEER_MESSAGE: "message",
        WorkKind.COVERAGE_GAP: "coverage",
    }
)
"""Total mapping: every :class:`WorkKind` to exactly one family."""

PAYLOAD_ID_BY_KIND: Mapping[WorkKind, str] = MappingProxyType(
    {kind: f"work.{kind.value}.v{WORK_OBSERVATION_CONTRACT_VERSION}" for kind in WorkKind}
)
"""Total mapping: every :class:`WorkKind` to exactly one payload ID."""

WORK_OBSERVATION_PAYLOAD_IDS: frozenset[str] = frozenset(PAYLOAD_ID_BY_KIND.values())
"""The 25 payload ID strings consumers pin against (support-matrix rows)."""


# Server-owned fields (planning#2268 LW-10: "caller fields never grant
# authority", same shape as F1's decision 7). The authenticated
# principal/team binding, admitted-repository generation, server receipt
# time, and committed cursor are all derived at ingestion from the
# credential — a client-supplied copy of any of them is not evidence and
# must fail closed.
SERVER_OWNED_FIELDS: frozenset[str] = frozenset(
    {
        "received_at",
        "cursor",
        "committed_cursor",
        "principal",
        "principal_binding",
        "principal_id_server",
        "team",
        "team_id",
        "team_slug",
        "admission",
        "admitted",
        "admitted_generation",
        "repository_generation",
    }
)

# Privacy set: file capture is metadata-only; raw command environments,
# secrets, and private agent reasoning are never observable work (LW-11
# safe capture/redaction). Exact-key matches only — `credential_epoch` on
# SessionIdentity is not `credentials`.
_PRIVACY_KEYS: frozenset[str] = frozenset(
    {
        "contents",
        "file_contents",
        "stdout",
        "stderr",
        "output",
        "env",
        "environment",
        "command_env",
        "reasoning",
        "private_reasoning",
        "secret",
        "secrets",
        "token",
        "password",
        "api_key",
        "credential",
        "credentials",
    }
)

FORBIDDEN_WORK_KEYS: frozenset[str] = SERVER_OWNED_FIELDS | _PRIVACY_KEYS
"""Closed, versioned set of keys that must never appear anywhere inside a
WorkObservation envelope (checked by the recursive walker in
:mod:`spec_kitty_events.forbidden_keys`, same primitive as
:data:`spec_kitty_events.forbidden_keys.FORBIDDEN_LEGACY_KEYS` and the
strict profile's per-type union in ``validate_strict_envelope`` step 4)."""

FORBIDDEN_WORK_KEYS_VERSION: str = "v1"
"""Bump on any membership change to :data:`FORBIDDEN_WORK_KEYS`."""


# Character-class and length bounds — mirrors the F1/_harness_observation
# grammar ( zeitgeist editor character classes; no whitespace/control chars).
_IDENT = r"^[A-Za-z0-9][A-Za-z0-9._@+-]{0,63}$"
_REF = r"^[A-Za-z0-9][A-Za-z0-9._@+/-]{0,239}$"
_ENTITY_ID = r"^[A-Za-z0-9][A-Za-z0-9._-]{0,119}$"
_SHA256 = r"^[0-9a-f]{64}$"
_EXTENSION_KEY = r"^x-[a-z0-9][a-z0-9-]{0,63}$"

_MAX_INLINE_TEXT = 2000


# ── Identity models ──────────────────────────────────────────────────────────


class ProducerIdentity(BaseModel):
    """Who produced this observation, at which instance, at which sequence.

    ``producer_id`` is the stable logical producer (a CLI install identity,
    an agent identity) — NOT the authenticated principal (server-owned) and
    NOT the person. ``instance_id`` is one process/run of that producer.
    ``sequence`` is the producer's monotonic per-instance sequence number:
    consumers use it (with the envelope's ``node_id``/``lamport_clock``) for
    duplicate, late, and gap detection — never the client ``timestamp``
    (LW-10: no client timestamp establishes authoritative total order).
    """

    model_config = ConfigDict(frozen=True, extra="forbid")

    producer_id: str = Field(..., max_length=128, pattern=_IDENT)
    instance_id: str = Field(..., max_length=128, pattern=_IDENT)
    sequence: int = Field(..., ge=0)


class SessionIdentity(BaseModel):
    """The stable logical session that produced this observation.

    Identity survives reconnect (``reconnect_epoch`` bumps) and credential
    rotation (``credential_epoch`` bumps) — the session is the *logical*
    conversation, not the transport connection and not the credential
    (LW-01: two same-account agents stay distinct through reconnect and
    rotation because they hold distinct ``session_id``s).

    ``delegated_from`` names the delegating session's ``session_id`` when
    this session was spawned by a delegation (LW-05 delegated sessions).
    """

    model_config = ConfigDict(frozen=True, extra="forbid")

    session_id: str = Field(..., min_length=1, max_length=128, pattern=_IDENT)
    reconnect_epoch: int = Field(default=0, ge=0)
    credential_epoch: int = Field(default=0, ge=0)
    delegated_from: Optional[str] = Field(None, min_length=1, max_length=128, pattern=_IDENT)


class AgentProfileRef(BaseModel):
    """The agent profile a principal acted through — distinct from the
    principal itself (LW-01: principal, agent profile, session, and factory
    attempt are four different identities, never collapsed into one). Valid
    for every ``principal_kind``: they are independent identity dimensions,
    not exclusive modes — a factory worker retains both its profile/harness
    identity and its job/attempt attribution, and a human may act through
    an agent profile. Only ``principal_kind='agent'`` *requires* one (a
    bare agent principal is ambiguous)."""

    model_config = ConfigDict(frozen=True, extra="forbid")

    harness: str = Field(..., max_length=32, pattern=_IDENT)
    model: Optional[str] = Field(None, max_length=64, pattern=_IDENT)
    profile_id: Optional[str] = Field(None, max_length=128, pattern=_IDENT)


class FactoryAttemptRef(BaseModel):
    """The factory job/attempt an observation was produced under (planning
    #2270 binds these into continuous missions). Distinct from the session
    and the principal: one attempt may span sessions and one session may
    outlive several attempts."""

    model_config = ConfigDict(frozen=True, extra="forbid")

    attempt_id: str = Field(..., min_length=1, max_length=128, pattern=_IDENT)
    job_ref: Optional[str] = Field(None, max_length=240, pattern=_REF)


class ActorIdentity(BaseModel):
    """The acting principal, with optional agent/factory framing.

    ``principal_id`` is the stable principal (a person account, an agent
    identity, a service, a factory role) — the *server* resolves it to the
    authenticated principal at ingestion; this field is producer-side
    attribution only. ``display_name`` is mutable presentation, never
    identity: renames change it, identity does not (LW-01).
    """

    model_config = ConfigDict(frozen=True, extra="forbid")

    principal_kind: Literal["human", "agent", "service", "factory"]
    principal_id: str = Field(..., min_length=1, max_length=128, pattern=_IDENT)
    display_name: Optional[str] = Field(None, min_length=1, max_length=128)
    agent_profile: Optional[AgentProfileRef] = None
    factory_attempt: Optional[FactoryAttemptRef] = None

    @model_validator(mode="after")
    def _kind_profile_agreement(self) -> "ActorIdentity":
        if self.principal_kind == "agent" and self.agent_profile is None:
            raise ValueError(
                "principal_kind='agent' requires agent_profile (the profile is "
                "the agent's identity; a bare agent principal is ambiguous)"
            )
        # agent_profile is valid for EVERY principal_kind: profile/harness
        # identity and factory job/attempt attribution are distinct
        # dimensions, never exclusive identity modes — a real factory worker
        # carries both at once (#55 review P1).
        if self.factory_attempt is not None and self.principal_kind != "factory":
            raise ValueError("factory_attempt is only meaningful for principal_kind='factory'")
        return self


class PrincipalRef(BaseModel):
    """A lightweight reference to a principal (message recipient, handoff
    target) — the full identity is carried by the sender's ActorIdentity."""

    model_config = ConfigDict(frozen=True, extra="forbid")

    principal_kind: Literal["human", "agent", "service", "factory"]
    principal_id: str = Field(..., min_length=1, max_length=128, pattern=_IDENT)


class MissionIdentity(BaseModel):
    """Canonical mission identity — stable before, through, and after rename.

    ``mission_id`` is the canonical opaque identity: it exists from the
    first specify activity, before any Git push, and survives label changes,
    worktree moves, PRs, and merges (LW-02). ``display_label`` is mutable
    presentation only — two concurrent missions may share a label and remain
    distinct missions, and a rename changes the label, never the id.
    """

    model_config = ConfigDict(frozen=True, extra="forbid")

    mission_id: str = Field(..., min_length=1, max_length=120, pattern=_ENTITY_ID)
    display_label: Optional[str] = Field(None, min_length=1, max_length=240)


class RepositoryIdentity(BaseModel):
    """Admitted repository identity — stable across provider renames.

    ``provider`` namespaces the forge (closed set; github today).
    ``repository_id`` is the provider-canonical stable repository id (e.g.
    the numeric GitHub repository id) — the admitted-repo *generation* that
    vets this id against the team's installation is server-owned (saas#1814).
    ``display_slug`` is the mutable ``owner/name`` presentation: a rename
    changes it, never the id (LW-02).
    """

    model_config = ConfigDict(frozen=True, extra="forbid")

    provider: Literal["github"]
    repository_id: str = Field(
        ..., min_length=1, max_length=64, pattern=r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$"
    )
    display_slug: Optional[str] = Field(None, min_length=1, max_length=240, pattern=_REF)


class ProgrammeLink(BaseModel):
    """Cross-repo programme linkage (LW-02): distinct missions in distinct
    repositories that belong to one programme. Each linked mission keeps its
    own repository identity — the link relates missions, it never merges
    their identities."""

    model_config = ConfigDict(frozen=True, extra="forbid")

    programme_id: str = Field(..., min_length=1, max_length=120, pattern=_ENTITY_ID)
    role: Optional[str] = Field(None, min_length=1, max_length=64)


class WorkContext(BaseModel):
    """Optional work context slots — all optional, all presentation/scoping,
    none of them identity (identity is mission/session/repository above)."""

    model_config = ConfigDict(frozen=True, extra="forbid")

    wp_id: Optional[str] = Field(None, min_length=1, max_length=32, pattern=_IDENT)
    run_id: Optional[str] = Field(None, min_length=1, max_length=128, pattern=_IDENT)
    operation: Optional[str] = Field(None, min_length=1, max_length=64, pattern=_IDENT)
    branch: Optional[str] = Field(None, min_length=1, max_length=240, pattern=_REF)
    worktree: Optional[str] = Field(None, min_length=1, max_length=240, pattern=_REF)


class ActivityRef(BaseModel):
    """Activity grouping: an observation may belong to a logical activity
    and an activity may name its parent — the durable analogue of the
    volatile focus/activity signal in :mod:`spec_kitty_events.harness_observation`."""

    model_config = ConfigDict(frozen=True, extra="forbid")

    activity_id: str = Field(..., min_length=1, max_length=128, pattern=_IDENT)
    parent_activity_id: Optional[str] = Field(None, min_length=1, max_length=128, pattern=_IDENT)


class ArtifactReference(BaseModel):
    """A reference to authenticated artifact content — long content is
    never inlined (LW-11): it is referenced by content hash, byte length,
    media type, and completeness. ``complete=False`` marks a partial
    capture (e.g. truncated log tail) so consumers never mistake a
    fragment for the whole."""

    model_config = ConfigDict(frozen=True, extra="forbid")

    artifact_id: str = Field(..., min_length=1, max_length=128, pattern=_IDENT)
    content_hash: str = Field(..., pattern=_SHA256, description="SHA-256 of the referenced content")
    byte_length: int = Field(..., ge=0)
    media_type: str = Field(..., min_length=1, max_length=128)
    complete: bool = True


class ActionOutcome(str, Enum):
    """Explicit outcome for every concluded action — failure and skip are
    first-class observations, never laundered into success or silence
    (LW-03/LW-10). Only carried once the action has actually concluded
    (``state='result'``): an observation emitted while a tool/test is still
    running must not be forced to invent one."""

    SUCCESS = "success"
    FAILURE = "failure"
    SKIPPED = "skipped"


class ActionState(str, Enum):
    """Lifecycle state of an observed tool/test activity (LW-04/LW-09: the
    live dashboard shows work *while it happens*, then correlates the
    conclusion to that same activity).

    ``STARTED``/``RUNNING`` are the pre-result states — no outcome, no test
    counts (nothing has concluded yet; forcing one would launder a live
    activity into a completed-work feed). ``RESULT`` is the terminal state
    that carries the :class:`ActionOutcome` (and test counts). ``CANCELLED``
    records the abandonment of a previously started activity — an honest
    terminal observation in its own right, never a missing result.

    Start→result/cancel correlation is the observation *pair* sharing one
    ``activity`` (:class:`ActivityRef`): the start observation and the
    terminal observation name the same ``activity_id`` (the terminal one
    may also ``reply_to`` the start observation's event ID).
    """

    STARTED = "started"
    RUNNING = "running"
    RESULT = "result"
    CANCELLED = "cancelled"


class ToolAction(BaseModel):
    """A tool invocation at any lifecycle state. Tool *name*, state,
    outcome (terminal only), duration — never the raw command environment
    or tool output (those are privacy keys)."""

    model_config = ConfigDict(frozen=True, extra="forbid")

    tool: str = Field(..., min_length=1, max_length=64, pattern=_IDENT)
    state: ActionState
    outcome: Optional[ActionOutcome] = None
    duration_ms: Optional[int] = Field(None, ge=0)

    @model_validator(mode="after")
    def _state_outcome_agreement(self) -> "ToolAction":
        if self.state == ActionState.RESULT and self.outcome is None:
            raise ValueError("state='result' requires an outcome")
        if self.state != ActionState.RESULT and self.outcome is not None:
            raise ValueError(
                "outcome is only carried at state='result' — a started/running/"
                "cancelled tool observation must not invent a concluded outcome"
            )
        return self


class FileOperation(str, Enum):
    """The operation a file observation records (LW-04: read/edit/create/
    delete/rename are all supported observations — a byte-delta alone
    cannot distinguish them)."""

    READ = "read"
    EDIT = "edit"
    CREATE = "create"
    DELETE = "delete"
    RENAME = "rename"


_FILE_PATH_MAX = 240


def _validate_repository_relative_path(value: Optional[str]) -> Optional[str]:
    """Validate one repository-relative file path (FileAction fields).

    Accepts any bounded relative path a real repository can contain —
    POSIX-style ``/`` separators, spaces, and Unicode included
    (``docs/design notes.md``, ``docs/Über 设计.md``). Fails closed on
    everything that is not a safe in-repo name: absolute paths, backslash
    separators, control characters, and empty/``.``/``..`` segments
    (traversal). This grammar is deliberately *not* the ``_REF`` grammar:
    real filenames contain spaces and non-ASCII characters, and a contract
    that rejects them rejects real work (#55 review P1).
    """
    if value is None:
        return value
    if value.startswith("/") or value.endswith("/"):
        raise ValueError(f"path {value!r} must be repository-relative (no leading or trailing '/')")
    if "\\" in value:
        raise ValueError(
            f"path {value!r} must use '/' separators (backslash is not a repository path separator)"
        )
    for char in value:
        if ord(char) < 0x20 or ord(char) == 0x7F:
            raise ValueError(f"path {value!r} contains a control character")
    bad = [seg for seg in value.split("/") if seg in ("", ".", "..")]
    if bad:
        raise ValueError(
            f"path {value!r} must not contain empty, '.', or '..' segments "
            f"(repository traversal is never a valid file observation)"
        )
    return value


class FileAction(BaseModel):
    """A file operation, as metadata only (LW-04/LW-11): operation, path
    (and rename destination), size deltas. There is deliberately no
    ``contents`` field — file contents are never observable work, and
    ``contents`` is a :data:`FORBIDDEN_WORK_KEYS` member enforced at the
    envelope level as defense in depth.

    ``operation`` names what happened (read/edit/create/delete/rename);
    ``destination_path`` carries the rename's destination and is valid only
    for ``operation='rename'``. ``path``/``destination_path`` are
    repository-relative and may contain spaces and Unicode — validated by
    :func:`_validate_repository_relative_path`, not the ``_REF`` grammar.
    """

    model_config = ConfigDict(frozen=True, extra="forbid")

    operation: FileOperation
    path: str = Field(..., min_length=1, max_length=_FILE_PATH_MAX)
    destination_path: Optional[str] = Field(None, min_length=1, max_length=_FILE_PATH_MAX)
    bytes_added: int = Field(..., ge=0)
    bytes_removed: int = Field(..., ge=0)
    language: Optional[str] = Field(None, min_length=1, max_length=32, pattern=_IDENT)

    @field_validator("path", "destination_path")
    @classmethod
    def _repository_relative(cls, v: Optional[str]) -> Optional[str]:
        return _validate_repository_relative_path(v)

    @model_validator(mode="after")
    def _rename_agreement(self) -> "FileAction":
        if self.operation == FileOperation.RENAME and self.destination_path is None:
            raise ValueError("operation='rename' requires destination_path (the rename target)")
        if self.operation != FileOperation.RENAME and self.destination_path is not None:
            raise ValueError("destination_path is only carried at operation='rename'")
        return self


class TestAction(BaseModel):
    """A test execution at any lifecycle state: selector plus explicit
    counts (carried only once the run concludes, ``state='result'``).
    Never raw output."""

    model_config = ConfigDict(frozen=True, extra="forbid")

    selector: str = Field(..., min_length=1, max_length=240, pattern=_REF)
    state: ActionState
    passed: Optional[int] = Field(None, ge=0)
    failed: Optional[int] = Field(None, ge=0)
    skipped: Optional[int] = Field(None, ge=0)
    outcome: Optional[ActionOutcome] = None

    @model_validator(mode="after")
    def _state_terminal_agreement(self) -> "TestAction":
        if self.state == ActionState.RESULT:
            if self.outcome is None:
                raise ValueError("state='result' requires an outcome")
            if self.passed is None or self.failed is None or self.skipped is None:
                raise ValueError("state='result' requires the passed/failed/skipped counts")
        elif (
            self.outcome is not None
            or self.passed is not None
            or self.failed is not None
            or self.skipped is not None
        ):
            raise ValueError(
                "outcome and counts are only carried at state='result' — a "
                "started/running/cancelled test observation must not invent "
                "concluded results"
            )
        return self


class CoverageGap(BaseModel):
    """An explicitly recorded capture gap (LW-08/LW-10): an area of work
    this producer could not observe, with the honest reason — gaps are
    recorded as data, never silently dropped."""

    model_config = ConfigDict(frozen=True, extra="forbid")

    area: str = Field(..., min_length=1, max_length=64, pattern=_IDENT)
    reason: Optional[str] = Field(None, min_length=1, max_length=240)


class SourceProvenance(BaseModel):
    """How this observation was captured (LW-04's capability matrix): the
    capture source, the named capability that produced it, and — when
    capture is partial — the honest limitation. ``limitation`` is the
    machine-readable home of the "record real capture/source limitations"
    requirement: a producer that cannot see something says so here."""

    model_config = ConfigDict(frozen=True, extra="forbid")

    source: Literal["harness_hook", "cli_wrapper", "emitter", "manual", "factory"]
    capability: str = Field(..., min_length=1, max_length=64, pattern=_IDENT)
    limitation: Optional[str] = Field(None, min_length=1, max_length=240)


_ExtensionValue = Union[str, int, float, bool, None]


# ── Per-kind field matrix ────────────────────────────────────────────────────
#
# Only the conditionally-varying fields are listed. `producer`, `session`,
# `actor`, `repository`, `kind`, and `provenance` are required for every
# kind (enforced by their Field(...) definitions). `mission` is required
# only for the lifecycle kinds — an actual mission lifecycle record
# (review/retrospective) names a canonical mission — and optional for every
# other kind: repository-bound sessions and work exist before (and outside)
# any mission, and never invent one (LW-02, #55 review P1). `context`,
# `activity`, `programme`, `artifact`, and `extensions` are optional for
# every kind (never constrained here).
#
# R = required, O = optional, F = must be absent.
_REQUIRED = "R"
_OPTIONAL = "O"
_FORBIDDEN = "F"

_KIND_FIELD_RULES: Mapping[WorkKind, Mapping[str, str]] = MappingProxyType(
    {
        # lifecycle: outcome note optional; action/coverage never ride a
        # lifecycle observation. A mission lifecycle record is exactly that —
        # it requires the canonical mission it records.
        WorkKind.MISSION_REVIEW_CAPTURED: MappingProxyType(
            {
                "mission": _REQUIRED,
                "text": _OPTIONAL,
                "action": _FORBIDDEN,
                "coverage": _FORBIDDEN,
                "recipient": _FORBIDDEN,
                "reply_to": _FORBIDDEN,
                "ref": _FORBIDDEN,
            }
        ),
        WorkKind.MISSION_REVIEW_FAILED: MappingProxyType(
            {
                "mission": _REQUIRED,
                "text": _REQUIRED,
                "action": _FORBIDDEN,
                "coverage": _FORBIDDEN,
                "recipient": _FORBIDDEN,
                "reply_to": _FORBIDDEN,
                "ref": _FORBIDDEN,
            }
        ),
        WorkKind.MISSION_REVIEW_SKIPPED: MappingProxyType(
            {
                "mission": _REQUIRED,
                "text": _REQUIRED,
                "action": _FORBIDDEN,
                "coverage": _FORBIDDEN,
                "recipient": _FORBIDDEN,
                "reply_to": _FORBIDDEN,
                "ref": _FORBIDDEN,
            }
        ),
        WorkKind.RETROSPECTIVE_CAPTURED: MappingProxyType(
            {
                "mission": _REQUIRED,
                "text": _OPTIONAL,
                "action": _FORBIDDEN,
                "coverage": _FORBIDDEN,
                "recipient": _FORBIDDEN,
                "reply_to": _FORBIDDEN,
                "ref": _FORBIDDEN,
            }
        ),
        WorkKind.RETROSPECTIVE_FAILED: MappingProxyType(
            {
                "mission": _REQUIRED,
                "text": _REQUIRED,
                "action": _FORBIDDEN,
                "coverage": _FORBIDDEN,
                "recipient": _FORBIDDEN,
                "reply_to": _FORBIDDEN,
                "ref": _FORBIDDEN,
            }
        ),
        WorkKind.RETROSPECTIVE_SKIPPED: MappingProxyType(
            {
                "mission": _REQUIRED,
                "text": _REQUIRED,
                "action": _FORBIDDEN,
                "coverage": _FORBIDDEN,
                "recipient": _FORBIDDEN,
                "reply_to": _FORBIDDEN,
                "ref": _FORBIDDEN,
            }
        ),
        # session: an optional note; never an action or a coverage gap.
        WorkKind.SESSION_STARTED: MappingProxyType(
            {
                "text": _OPTIONAL,
                "action": _FORBIDDEN,
                "coverage": _FORBIDDEN,
                "recipient": _FORBIDDEN,
                "reply_to": _FORBIDDEN,
                "ref": _FORBIDDEN,
            }
        ),
        WorkKind.SESSION_ENDED: MappingProxyType(
            {
                "text": _OPTIONAL,
                "action": _FORBIDDEN,
                "coverage": _FORBIDDEN,
                "recipient": _FORBIDDEN,
                "reply_to": _FORBIDDEN,
                "ref": _FORBIDDEN,
            }
        ),
        WorkKind.DELEGATION_STARTED: MappingProxyType(
            {
                "text": _OPTIONAL,
                "action": _FORBIDDEN,
                "coverage": _FORBIDDEN,
                "recipient": _OPTIONAL,
                "reply_to": _FORBIDDEN,
                "ref": _FORBIDDEN,
            }
        ),
        WorkKind.DELEGATION_ENDED: MappingProxyType(
            {
                "text": _OPTIONAL,
                "action": _FORBIDDEN,
                "coverage": _FORBIDDEN,
                "recipient": _OPTIONAL,
                "reply_to": _FORBIDDEN,
                "ref": _FORBIDDEN,
            }
        ),
        WorkKind.BINDING_CHANGED: MappingProxyType(
            {
                "text": _OPTIONAL,
                "action": _FORBIDDEN,
                "coverage": _FORBIDDEN,
                "recipient": _FORBIDDEN,
                "reply_to": _FORBIDDEN,
                "ref": _FORBIDDEN,
            }
        ),
        # action: the typed action detail is required (and must be the
        # right subtype for the kind — checked separately); inline text is
        # forbidden — commentary about work is a narrative observation.
        # ``reply_to`` is optional here purely for retry linkage: a retried
        # action names the failed attempt's event ID (the envelope's
        # causation_id carries the same link at the causal layer).
        WorkKind.TOOL_INVOKED: MappingProxyType(
            {
                "text": _FORBIDDEN,
                "action": _REQUIRED,
                "coverage": _FORBIDDEN,
                "recipient": _FORBIDDEN,
                "reply_to": _OPTIONAL,
                "ref": _FORBIDDEN,
            }
        ),
        WorkKind.FILE_EDITED: MappingProxyType(
            {
                "text": _FORBIDDEN,
                "action": _REQUIRED,
                "coverage": _FORBIDDEN,
                "recipient": _FORBIDDEN,
                "reply_to": _OPTIONAL,
                "ref": _FORBIDDEN,
            }
        ),
        WorkKind.TEST_EXECUTED: MappingProxyType(
            {
                "text": _FORBIDDEN,
                "action": _REQUIRED,
                "coverage": _FORBIDDEN,
                "recipient": _FORBIDDEN,
                "reply_to": _OPTIONAL,
                "ref": _FORBIDDEN,
            }
        ),
        # narrative: the authored text is the observation.
        WorkKind.INTENT_DECLARED: MappingProxyType(
            {
                "text": _REQUIRED,
                "action": _FORBIDDEN,
                "coverage": _FORBIDDEN,
                "recipient": _FORBIDDEN,
                "reply_to": _FORBIDDEN,
                "ref": _OPTIONAL,
            }
        ),
        WorkKind.PROGRESS_REPORTED: MappingProxyType(
            {
                "text": _REQUIRED,
                "action": _FORBIDDEN,
                "coverage": _FORBIDDEN,
                "recipient": _FORBIDDEN,
                "reply_to": _OPTIONAL,
                "ref": _OPTIONAL,
            }
        ),
        WorkKind.QUESTION_ASKED: MappingProxyType(
            {
                "text": _REQUIRED,
                "action": _FORBIDDEN,
                "coverage": _FORBIDDEN,
                "recipient": _OPTIONAL,
                "reply_to": _FORBIDDEN,
                "ref": _FORBIDDEN,
            }
        ),
        WorkKind.QUESTION_ANSWERED: MappingProxyType(
            {
                "text": _REQUIRED,
                "action": _FORBIDDEN,
                "coverage": _FORBIDDEN,
                "recipient": _OPTIONAL,
                "reply_to": _REQUIRED,
                "ref": _FORBIDDEN,
            }
        ),
        WorkKind.DECISION_RECORDED: MappingProxyType(
            {
                "text": _REQUIRED,
                "action": _FORBIDDEN,
                "coverage": _FORBIDDEN,
                "recipient": _OPTIONAL,
                "reply_to": _OPTIONAL,
                "ref": _OPTIONAL,
            }
        ),
        WorkKind.HANDOFF_PERFORMED: MappingProxyType(
            {
                "text": _OPTIONAL,
                "action": _FORBIDDEN,
                "coverage": _FORBIDDEN,
                "recipient": _REQUIRED,
                "reply_to": _FORBIDDEN,
                "ref": _OPTIONAL,
            }
        ),
        WorkKind.BLOCKER_RAISED: MappingProxyType(
            {
                "text": _REQUIRED,
                "action": _FORBIDDEN,
                "coverage": _FORBIDDEN,
                "recipient": _OPTIONAL,
                "reply_to": _FORBIDDEN,
                "ref": _OPTIONAL,
            }
        ),
        WorkKind.BLOCKER_RESOLVED: MappingProxyType(
            {
                "text": _OPTIONAL,
                "action": _FORBIDDEN,
                "coverage": _FORBIDDEN,
                "recipient": _OPTIONAL,
                "reply_to": _FORBIDDEN,
                "ref": _REQUIRED,
            }
        ),
        WorkKind.NEXT_PROPOSED: MappingProxyType(
            {
                "text": _REQUIRED,
                "action": _FORBIDDEN,
                "coverage": _FORBIDDEN,
                "recipient": _OPTIONAL,
                "reply_to": _OPTIONAL,
                "ref": _OPTIONAL,
            }
        ),
        # message: durable peer message — recipient and text both required.
        WorkKind.PEER_MESSAGE: MappingProxyType(
            {
                "text": _REQUIRED,
                "action": _FORBIDDEN,
                "coverage": _FORBIDDEN,
                "recipient": _REQUIRED,
                "reply_to": _OPTIONAL,
                "ref": _FORBIDDEN,
            }
        ),
        # coverage: the gap record is the observation.
        WorkKind.COVERAGE_GAP: MappingProxyType(
            {
                "text": _OPTIONAL,
                "action": _FORBIDDEN,
                "coverage": _REQUIRED,
                "recipient": _FORBIDDEN,
                "reply_to": _FORBIDDEN,
                "ref": _FORBIDDEN,
            }
        ),
    }
)

# Action-subtype binding: which typed action detail each action kind accepts.
_ACTION_MODEL_BY_KIND: Mapping[WorkKind, type[BaseModel]] = MappingProxyType(
    {
        WorkKind.TOOL_INVOKED: ToolAction,
        WorkKind.FILE_EDITED: FileAction,
        WorkKind.TEST_EXECUTED: TestAction,
    }
)


class WorkObservationPayload(BaseModel):
    """Typed payload for the 25 durable WorkObservation kinds.

        Envelope conventions (validated by ``spec_kitty_events.strict``, see the
        module docstring): ``event_type="WorkObservation"``,
        ``aggregate_id`` = :func:`work_aggregate_id` — ``mission/<mission_id>``
    when the observation is mission-bound, ``repo/<repository_id>`` for
    repository-bound work with no mission yet (LW-02: repo-bound sessions
    before a mission exists keep a real aggregate identity, never an invented
    mission), ``schema_version="3.0.0"``,
        ``timestamp`` = producer occurrence time, ``correlation_id`` /
        ``causation_id`` = standard Event causal semantics (a retry attempt
        causally follows the observation it retries), ``node_id`` = producer
        instance, ``lamport_clock`` = journal clock at emission. No time field
        in the payload (R-T-02, same as F1); no server-owned field (the
        principal/team binding, admitted-repo generation, ``received_at``, and
        committed cursor are ingestion-owned and forbidden here).
    """

    model_config = ConfigDict(frozen=True, extra="forbid")

    kind: WorkKind
    producer: ProducerIdentity
    session: SessionIdentity
    actor: ActorIdentity
    repository: RepositoryIdentity
    provenance: SourceProvenance

    mission: Optional[MissionIdentity] = Field(
        None,
        description=(
            "Canonical mission identity when this observation is mission-bound. "
            "Required only for the lifecycle kinds (mission review/retrospective); "
            "repository-bound sessions and work exist before (and outside) any "
            "mission and leave it unset rather than inventing one (LW-02)."
        ),
    )

    context: Optional[WorkContext] = None
    activity: Optional[ActivityRef] = None
    programme: Optional[ProgrammeLink] = None
    artifact: Optional[ArtifactReference] = None

    text: Optional[str] = Field(
        None,
        min_length=1,
        max_length=_MAX_INLINE_TEXT,
        description="Inline authored text (narrative/message kinds). Long content is an artifact reference, never inline.",
    )
    action: Optional[Union[ToolAction, FileAction, TestAction]] = None
    recipient: Optional[PrincipalRef] = None
    reply_to: Optional[str] = Field(
        None,
        min_length=26,
        max_length=36,
        description="Event ID of the observation this one answers or retries.",
    )
    ref: Optional[str] = Field(
        None,
        min_length=1,
        max_length=240,
        description="Opaque reference to the entity this observation resolves or records (blocker ref, decision record link).",
    )
    coverage: Optional[CoverageGap] = None
    extensions: Optional[Dict[str, _ExtensionValue]] = Field(
        None,
        description="Safe optional extensions: keys MUST be x- prefixed; unknown un-namespaced keys are rejected by extra='forbid'.",
    )

    @model_validator(mode="after")
    def _per_kind(self) -> "WorkObservationPayload":
        rules = _KIND_FIELD_RULES[self.kind]
        for field_name, rule in rules.items():
            value = getattr(self, field_name)
            if rule == _REQUIRED and value is None:
                raise ValueError(f"{field_name!r} is required for kind {self.kind.value!r}")
            if rule == _FORBIDDEN and value is not None:
                raise ValueError(f"{field_name!r} must be absent for kind {self.kind.value!r}")
        # Delegation kinds must actually carry a delegation: the delegated
        # session is named either here or on the session model.
        if self.kind in (WorkKind.DELEGATION_STARTED, WorkKind.DELEGATION_ENDED):
            if self.session.delegated_from is None and self.recipient is None:
                raise ValueError(
                    f"kind {self.kind.value!r} requires session.delegated_from or "
                    f"recipient to name the delegation counterpart"
                )
        # Action kinds bind to exactly one typed action detail.
        action_model = _ACTION_MODEL_BY_KIND.get(self.kind)
        if action_model is not None and self.action is not None:
            if not isinstance(self.action, action_model):
                raise ValueError(
                    f"kind {self.kind.value!r} requires a "
                    f"{action_model.__name__} action detail, got "
                    f"{type(self.action).__name__}"
                )
        return self

    @field_validator("extensions")
    @classmethod
    def _extension_keys_namespaced(
        cls, v: Optional[Dict[str, _ExtensionValue]]
    ) -> Optional[Dict[str, _ExtensionValue]]:
        if v is None:
            return v
        bad = sorted(key for key in v if not re.match(_EXTENSION_KEY, key))
        if bad:
            raise ValueError(
                f"extension keys must be x- prefixed (got {bad}); "
                f"un-namespaced keys are not a safe optional extension surface"
            )
        return v


# ── Canonical payload hashing ────────────────────────────────────────────────


def canonical_work_hash(payload: Union[WorkObservationPayload, Dict[str, Any]]) -> str:
    """SHA-256 of the canonical JSON serialization of a work payload.

    Canonical form: ``json.dumps(..., sort_keys=True, separators=(",", ":"),
    ensure_ascii=False)`` over the payload dict in JSON mode. This is the
    hash consumers compare for the exact-duplicate vs same-ID/different-
    payload decision (LW-10): two envelopes with the same ``event_id`` and
    the same canonical hash are an idempotent retry; the same ``event_id``
    with a different hash is a conflict, never a silent overwrite.
    """
    if isinstance(payload, WorkObservationPayload):
        data = payload.model_dump(mode="json")
    else:
        data = payload
    canonical = json.dumps(data, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def work_aggregate_id(payload: WorkObservationPayload) -> str:
    """The envelope ``aggregate_id`` for a work observation.

    ``mission/<mission_id>`` when the observation is mission-bound;
    ``repo/<repository_id>`` for repository-bound work with no mission yet
    (LW-02, #55 review P1): a repo-bound session that starts before any
    mission exists keeps a real, stable aggregate identity — the repository
    it is bound to — instead of an invented mission. When the session later
    binds to a mission (``session.binding_changed``), the aggregate moves
    to that mission with the session's provenance preserved.
    """
    if payload.mission is not None:
        return f"mission/{payload.mission.mission_id}"
    return f"repo/{payload.repository.repository_id}"


# ── Typed rejection and schema negotiation ──────────────────────────────────


class WorkRejectionReason(str, Enum):
    """Closed vocabulary for typed rejections of durable work observations.

    Rejections are typed data, never dropped-on-the-floor and never a
    generic 400: the producer can distinguish "my contract version is too
    old" (renegotiate) from "this event ID already exists with different
    bytes" (investigate) from "I set a server-owned field" (fix my emitter).
    """

    ID_PAYLOAD_CONFLICT = "id_payload_conflict"
    UNSUPPORTED_CONTRACT_VERSION = "unsupported_contract_version"
    UNKNOWN_KIND = "unknown_kind"
    SERVER_OWNED_FIELD = "server_owned_field"
    FORBIDDEN_KEY = "forbidden_key"
    INVALID_PAYLOAD = "invalid_payload"


class TypedRejection(BaseModel):
    """A typed rejection returned by a consumer that could not accept a
    durable work observation. ``contract`` names the payload-ID lineage the
    consumer judged against (e.g. ``work.narrative.intent_declared.v1``)."""

    model_config = ConfigDict(frozen=True, extra="forbid")

    reason: WorkRejectionReason
    detail: str = Field(..., min_length=1, max_length=2000)
    event_id: Optional[str] = Field(None, min_length=26, max_length=36)
    contract: Optional[str] = Field(None, min_length=1, max_length=128)


class NegotiationResult(BaseModel):
    """Outcome of producer/consumer work-contract negotiation.

    ``compatible=True`` with a chosen ``contract_version`` means the
    consumer can validate the producer's payloads as-is. ``compatible=
    False`` carries the typed rejection explaining why — producers
    capability-gate emission on this (contracts/versioning-and-compatibility.md:
    a minor version does not make an unknown event safe for every consumer).
    """

    model_config = ConfigDict(frozen=True, extra="forbid")

    compatible: bool
    contract_version: Optional[str] = None
    rejection: Optional[TypedRejection] = None

    @model_validator(mode="after")
    def _shape(self) -> "NegotiationResult":
        if self.compatible and self.contract_version is None:
            raise ValueError("a compatible negotiation names the chosen contract_version")
        if not self.compatible and self.rejection is None:
            raise ValueError("an incompatible negotiation carries a typed rejection")
        return self


_SEMVER_RE = re.compile(r"^(\d+)\.(\d+)\.(\d+)$")


def _parse_semver(version: str) -> tuple[int, int, int]:
    match = _SEMVER_RE.match(version)
    if match is None:
        raise ValueError(f"not a semver string: {version!r}")
    return (int(match.group(1)), int(match.group(2)), int(match.group(3)))


def negotiate_work_contract(
    producer_version: str,
    consumer_versions: Sequence[str],
) -> NegotiationResult:
    """Negotiate the durable work contract between one producer and a
    consumer's supported versions.

    Rule (contracts/versioning-and-compatibility.md, restated for the work
    lineage): the consumer must support the producer's exact version, or a
    later version within the same major — a newer consumer major is a
    contract-behaviour change the producer has not agreed to, and an older
    consumer major predates the lineage. The chosen version is the
    *consumer's* newest same-major version (the producer emits v1-shaped
    payloads and the consumer reads them with its v1.x validator).

    Version/package pins follow actual compatibility (planning#2268): never
    an equal-major-number requirement across packages — this negotiation is
    over the work-contract lineage, not the package version.
    """
    try:
        producer = _parse_semver(producer_version)
    except ValueError:
        return NegotiationResult(
            compatible=False,
            rejection=TypedRejection(
                reason=WorkRejectionReason.UNSUPPORTED_CONTRACT_VERSION,
                detail=f"producer version {producer_version!r} is not semver",
                event_id=None,
                contract=None,
            ),
        )

    same_major: list[tuple[int, int, int]] = []
    for candidate in consumer_versions:
        try:
            parsed = _parse_semver(candidate)
        except ValueError:
            continue
        if parsed[0] == producer[0]:
            same_major.append(parsed)

    if not same_major or max(same_major) < producer:
        supported = ", ".join(sorted(consumer_versions)) or "(none)"
        return NegotiationResult(
            compatible=False,
            rejection=TypedRejection(
                reason=WorkRejectionReason.UNSUPPORTED_CONTRACT_VERSION,
                detail=(
                    f"consumer supports [{supported}], which does not cover "
                    f"producer work-contract {producer_version}"
                ),
                event_id=None,
                contract=None,
            ),
        )

    chosen = max(same_major)
    return NegotiationResult(
        compatible=True,
        contract_version=f"{chosen[0]}.{chosen[1]}.{chosen[2]}",
    )
