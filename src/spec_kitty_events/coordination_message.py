"""CoordinationMessage: the bounded cross-mission message contract (events#54).

The smallest shared coordination-message contract demonstrated by
spec-kitty/spec-kitty-planning#2163 (its question 4 and deliverable "a small
additional contract/tool is needed"), carried through the **existing**
Zeitgeist event transport: the volatile ``zeitgeist_attrs`` codec
(:mod:`spec_kitty_events.zeitgeist_attrs`), never a new transport, chat
store, or delivery layer. Generic event publishing is transport plumbing;
this module is the vocabulary that makes one published frame a *message*
another agent can act on.

Authority basis (planning#2188, resolved)
-----------------------------------------
planning#2188 asked for the standing-authorization and disclosure policy
before any publish tool ships. It is supplied by the Live Work owner record
``HIC-LIVE-WORK-DURABLE-ZEITGEIST-2026-09-13.md`` §"Standing authorization"
(recorded in ``docs/LIVE-WORK.md`` §8's authority reconciliation ledger):
agents publish authored communication within the authorized team/repo/
mission scope without per-message human approval, superseding the
human-outbox-only constraint for this programme. The 2026-09-14 amendment
(``HIC-ZEITGEIST-NOW-GIT-DONE-2026-09-14.md``) supersedes only the *durable*
half of that record; authored messages continue as live relay frames
(spec-kitty#4269 re-scoped to "live authored messages over the relay").
Nothing here infers ratification from shipped receiving code, and nothing
here re-opens the prose question: inline prose is bounded per
``HIC-TEAM-TRUST-BOUNDARY-BOUNDED-PROSE-2026-09-14.md`` — a team is a trust
boundary; a moment may carry producer-supplied, bounded, one-line prose
within the relay's 240-UTF-8-byte per-attr bound, validated at creation
with an error rather than silent truncation.

Server-derived versus untrusted producer claims
-----------------------------------------------
Every field of this payload is an **untrusted producer claim**. The
authenticated principal (the user account behind the credential), the team,
the deployment, and the admitted-repository generation are derived
server-side from the credential at the relay — a client-supplied copy of
any of them is not evidence and fails closed via
:data:`FORBIDDEN_COORDINATION_KEYS` (the same "caller fields never grant
authority" decision as HarnessObservation decision 7 and WorkObservation's
``SERVER_OWNED_FIELDS``).

``sender_agent_id`` is the **logical agent** identity — the agent that
authored the message, distinct from the user account it ran under
(planning#2163: "do not equate another agent using the same account with
the sender itself"). The account↔agent binding is service-side; across
command/reader processes a reader filtering its own messages (zeitgeist#295
``filterOwn``) keys on the server-attested identity, never on this field:
two agents sharing one account are distinct senders, and the same
``sender_agent_id`` under two accounts is not the same sender. The relay
stamps the authenticated actor itself (the codec's "Actor narrowing"
doctrine); this label is presentation and addressing, never authority.

Target semantics
----------------
``scope`` is the one authorized scope the message publishes to (a mission
or repository identity within the team); the frame reaches exactly that
scope's subscribers. ``addressed_to`` is a **mention** — a mention cannot
provide privacy: publishing to the scope means every scope subscriber may
read the body, so broadcast is permitted only when the content is readable
by all of them (the sender's obligation, enforced nowhere else). Narrow
delivery would require service-side enforcement that does not exist; per
issue #54's boundaries, a relay/SaaS change for it is a separate issue only
if integration demonstrates an actual gap. An ``addressed_to`` value
cannot even carry a scope qualification — the ident grammar rejects it —
so no cross-scope target is expressible on this wire.

Outcome semantics (acceptance is not delivery)
----------------------------------------------
Publishing is fire-and-forget over the volatile relay: acceptance (a valid
frame admitted to the relay) is not delivery, acknowledgement, or
completion. An absent recipient changes nothing — the message publishes to
the scope whether the addressed agent is online or not; there are no
per-recipient delivery receipts, and outcome claims (``delivered``,
``acknowledged``, ``read``) are server-owned and forbidden on this payload.
A moment dropped by a downed relay or an expired budget is lost by design,
exactly like every other volatile moment.

Identity, expiry, replies
-------------------------
``message_id`` is the stable, scoped message identity (unique within its
``scope``). Re-publishing the same ``message_id`` under a new ``event_id``
is a **duplicate-intent** republication: consumers deduplicate on
``(scope, message_id)`` (the same dedup Team Kitty already applies on
``(team, event_id)``); the codec itself never deduplicates. ``ttl_s`` is
the sender-declared freshness budget: a consumer treats the message as
expired at ``occurred_at + ttl_s`` (created time is the envelope's own
``occurred_at`` — R-T-02: no client time field owns order or expiry). A
reply arriving after the referenced message's expiry is **stale** —
consumer-side state this contract cannot see, so the contract's mechanical
guarantees are narrower: ``answer`` and ``closure`` MUST reference the
message they respond to (``reply_to`` required), a reply never references
itself, and whether the referenced message was a ``question``/``proposal``
is matching guidance for consumers, not a validation this package can
perform without thread state.

Prose framing is not authority
------------------------------
``body`` is authored prose — supplied by the producer's user or agent for
this purpose, never harvested from local files, environment values,
command output, or private reasoning (the HIC bounded-prose record's
"authored, never harvested" rule). It is shape-validated at creation (one
printable line, ≤240 UTF-8 bytes, error not truncation — the codec's
generic per-attr bound re-checks both directions). Shape validation limits
size and line tricks, not intent: instruction-shaped prose is
shape-*valid* and travels as data. Every agent-facing surface renders it
only inside the nonce-framed untrusted-content block, and received prose
never overrides the recipient's task authority — a message authorizes
nothing: not scope expansion, gate bypass, command execution, or
impersonation (Live Work ADR §"Standing authorization", second bullet).

No runtime tool is advertised
-----------------------------
This module is the payload contract only. The CLI send wrapper
(spec-kitty#4269, live authored messages over the relay) is the future
producer and does not exist yet; nothing here ships, names, or advertises a
send command, and a skill must not instruct a model to invoke one until
that wrapper lands. Automatic lifecycle events (the existing volatile
families) stay separate: a coordination message is authored communication,
never a status transition, and reusing a lifecycle kind for it is a
contract violation by construction — this is its own event type.
"""

from __future__ import annotations

import re
from enum import Enum
from typing import FrozenSet, Optional, Tuple

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from spec_kitty_events.forbidden_keys import validate_no_forbidden_keys

__all__ = [
    "COORDINATION_MESSAGE",
    "COORDINATION_MESSAGE_CONTRACT_VERSION",
    "COORDINATION_MESSAGE_EVENT_TYPES",
    "FORBIDDEN_COORDINATION_KEYS",
    "FORBIDDEN_COORDINATION_KEYS_VERSION",
    "CoordinationMessageKind",
    "RequestedAction",
    "CoordinationMessagePayload",
    "coordination_aggregate_id",
]


# ── Constants ────────────────────────────────────────────────────────────────

COORDINATION_MESSAGE: str = "CoordinationMessage"
"""The single ``event_type`` string carrying every coordination message.

One event type, one payload model, the message ``kind`` in the payload —
the same single-type-carries-kinds shape as ``HarnessObservation``, and
exactly one new member of the volatile vocabulary."""

COORDINATION_MESSAGE_CONTRACT_VERSION: int = 1
"""The only ``contract_version`` this package currently knows how to encode
and decode (the ``ops_invocation`` contract-versioning pattern: a decoder
that has not yet learned a newer shape gets a named
``UnknownContractVersionError`` instead of silently misinterpreting a
future revision's attrs)."""

COORDINATION_MESSAGE_EVENT_TYPES: FrozenSet[str] = frozenset({COORDINATION_MESSAGE})


# Server-derived identity/authority claims and outcome claims a producer
# must never make, plus the privacy set. The authenticated principal (user
# account), team, deployment, membership/role, and every delivery outcome
# are derived server-side from the credential or owned by the relay; a
# client-supplied copy of any of them is not evidence. Outcome claims are
# forbidden because acceptance is not delivery/acknowledgement/completion
# (issue #54): no payload may assert its own receipt.
FORBIDDEN_COORDINATION_KEYS: FrozenSet[str] = frozenset(
    {
        # server-derived identity (the relay attests all of these itself)
        "user",
        "user_id",
        "user_account",
        "account",
        "account_id",
        "sender_account",
        "principal",
        "principal_id",
        "team",
        "team_id",
        "team_slug",
        "deployment",
        "deployment_id",
        "membership",
        "role",
        # authority claims a producer cannot make about its own sender
        "authenticated",
        "authenticated_sender",
        "verified",
        "attested",
        "server",
        "server_sender",
        # delivery/acknowledgement outcomes (server-side, never payload data)
        "received_at",
        "delivered",
        "delivered_at",
        "delivery",
        "ack",
        "acknowledged",
        "acknowledged_at",
        "receipt",
        "read",
        "read_at",
        "completed",
        # privacy
        "token",
        "password",
        "secret",
        "credential",
        "credentials",
    }
)

FORBIDDEN_COORDINATION_KEYS_VERSION: str = "v1"
"""Bump on any membership change to :data:`FORBIDDEN_COORDINATION_KEYS`."""


# ── Grammars and bounds ──────────────────────────────────────────────────────

_MESSAGE_ID = r"^[A-Za-z0-9][A-Za-z0-9._-]{7,63}$"
"""Message identity: 8–64 chars, ASCII identifier grammar (so the joined
wire form is unambiguous and the byte bound is the char bound)."""

_AGENT_ID = r"^[A-Za-z0-9][A-Za-z0-9._@+-]{0,63}$"
"""Logical agent / addressee identity: a bare opaque identifier. Deliberately
has no scope syntax — an addressee cannot carry a team/repo qualification,
which is what makes a cross-scope target unexpressible (issue #54)."""

_SCOPE = r"^[A-Za-z0-9][A-Za-z0-9._@+/-]{0,239}$"
"""Publishing scope: the same grammar as the codec's ``ref`` bound — opaque,
slash-allowed (``mission/<id>``, ``repo/<id>``), ≤240 ASCII chars."""

_EVIDENCE_REF = r"^[A-Za-z0-9][A-Za-z0-9._@+/-]{0,71}$"
"""One evidence reference: ≤72 ASCII chars, no commas — ≤3 of them join
with ``','`` into at most 218 bytes, inside the 240-byte attr bound."""

_EVIDENCE_REF_MAX = 3

_BODY_MAX_BYTES = 240
"""The relay's per-attr prose bound (HIC-TEAM-TRUST-BOUNDARY-2026-09-14:
"a team is a trust boundary; keep 240"). Checked in UTF-8 bytes at
creation, with an error rather than truncation."""

_TTL_MIN_S = 1
_TTL_MAX_S = 604800
"""Freshness budget: 1 second to 7 days. Expiry is ``occurred_at + ttl_s``;
the default is one day."""


# ── Enums ────────────────────────────────────────────────────────────────────


class CoordinationMessageKind(str, Enum):
    """Closed set of coordination-message kinds (issue #54: "fact/proposal/
    question/answer/closure kind").

    ``fact`` and ``proposal`` are statements (a verified-shared observation;
    a change someone could adopt); ``question`` opens a thread; ``answer``
    and ``closure`` conclude one — both REQUIRE ``reply_to`` naming the
    message they respond to (answer/closure matching, issue #54). Verified
    facts, proposals and requests are distinct kinds precisely so a
    consumer never has to infer which it is reading.
    """

    FACT = "fact"
    PROPOSAL = "proposal"
    QUESTION = "question"
    ANSWER = "answer"
    CLOSURE = "closure"


class RequestedAction(str, Enum):
    """What the sender asks a reader to do (issue #54: "requested action") —
    a closed vocabulary of *coordination* requests only.

    A requested action is a request, never an instruction with authority:
    it rides as data, the recipient's task/decision authority is preserved
    (Live Work ADR: received prose — and every field here is prose-adjacent
    data — never overrides task authority), and no member of this set names
    a command, a gate, or a scope change.
    """

    REVIEW = "review"
    ANSWER = "answer"
    UNBLOCK = "unblock"
    DECIDE = "decide"


# ── Payload model ────────────────────────────────────────────────────────────


class CoordinationMessagePayload(BaseModel):
    """Typed payload for ``CoordinationMessage`` events — one authored
    coordination message.

    Envelope conventions (the standard ``Event`` semantics, no new envelope
    field): ``event_type="CoordinationMessage"``, ``aggregate_id`` =
    :func:`coordination_aggregate_id` (``coordination/<scope>``), the
    envelope ``timestamp`` is the created time (R-T-01), and
    ``correlation_id``/``causation_id`` carry the standard causal semantics
    — a re-publication of the same ``message_id`` is a new ``event_id``
    under the same ``correlation_id``. The zeitgeist projection carries
    ``event_id``/``occurred_at`` from the envelope and every payload field
    as one bounded attr; ``scope`` is the frame ``ref``.
    """

    model_config = ConfigDict(frozen=True, extra="forbid")

    message_id: str = Field(
        ...,
        pattern=_MESSAGE_ID,
        description=(
            "Stable scoped message identity — unique within its scope. "
            "Re-publishing the same message_id under a new event_id is a "
            "duplicate-intent republication; consumers deduplicate on "
            "(scope, message_id)."
        ),
    )
    kind: CoordinationMessageKind = Field(
        ...,
        description="The message kind; answer and closure require reply_to.",
    )
    sender_agent_id: str = Field(
        ...,
        pattern=_AGENT_ID,
        description=(
            "The logical agent that authored this message — an untrusted "
            "producer claim and a label only. The authenticated principal "
            "(user account) is server-derived from the credential and never "
            "a payload field; the account↔agent binding is service-side "
            "(zeitgeist#295)."
        ),
    )
    scope: str = Field(
        ...,
        pattern=_SCOPE,
        description=(
            "The one authorized scope this message publishes to (e.g. "
            "'mission/<id>' or 'repo/<id>' within the team). The frame's "
            "aggregate identity; every scope subscriber may read the body."
        ),
    )
    body: str = Field(
        ...,
        min_length=1,
        description=(
            "The authored message prose: one printable line, at most 240 "
            "UTF-8 bytes, validated at creation with an error rather than "
            "truncation. Authored, never harvested; data, never authority."
        ),
    )
    mission_ref: Optional[str] = Field(
        None,
        pattern=_SCOPE,
        description=(
            "The sender's mission when the message is mission-bound and "
            "distinct from the publishing scope; optional, never invented."
        ),
    )
    addressed_to: Optional[str] = Field(
        None,
        pattern=_AGENT_ID,
        description=(
            "A mention of one logical recipient. A mention cannot provide "
            "privacy — the message publishes to the whole scope; this field "
            "cannot carry a scope qualification, so no cross-scope target "
            "is expressible."
        ),
    )
    evidence_refs: Optional[Tuple[str, ...]] = Field(
        None,
        description=(
            "Opaque evidence references (a CI run id, a PR number, a "
            "decision record pointer) — at most 3, each ≤72 ASCII chars, "
            "joined with ',' on the wire (218 bytes worst case, inside the "
            "attr bound). Pointers only, never evidence content."
        ),
    )
    requested_action: Optional[RequestedAction] = Field(
        None,
        description=(
            "What the sender asks a reader to do — a closed vocabulary of "
            "coordination requests (review/answer/unblock/decide). A "
            "request, never an instruction with authority."
        ),
    )
    ttl_s: int = Field(
        86400,
        ge=_TTL_MIN_S,
        le=_TTL_MAX_S,
        description=(
            "Sender-declared freshness budget in seconds; the message is "
            "expired at occurred_at + ttl_s. Created time is the envelope's "
            "occurred_at (R-T-02: no client time field owns expiry)."
        ),
    )
    reply_to: Optional[str] = Field(
        None,
        pattern=_MESSAGE_ID,
        description=(
            "The message_id this message responds to. Required for answer "
            "and closure; a reply never references its own message_id. "
            "Whether the referenced message was a question/proposal, and "
            "whether it had already expired, is consumer-side thread state."
        ),
    )
    contract_version: int = Field(
        COORDINATION_MESSAGE_CONTRACT_VERSION,
        ge=1,
        description="Version of this payload shape (see module docstring).",
    )

    @field_validator("body")
    @classmethod
    def _body_is_one_bounded_printable_line(cls, v: str) -> str:
        """Creation-time prose validation (HIC-TEAM-TRUST-BOUNDARY's binding
        mitigation): one line, printable, within the bound, error rather
        than silent truncation. This limits shape (newline/delimiter/bidi
        tricks — ``str.isprintable()`` rejects the C0/C1 controls, the
        Unicode line/paragraph separators, and bidi/zero-width formatting
        characters, the same predicate the codec applies on both
        directions), never intent: it is not an injection control and is
        not described as one."""
        if not v.strip():
            raise ValueError("body must not be blank")
        if not v.isprintable():
            raise ValueError(
                "body must be one printable line (no newlines, control, "
                "bidi, or zero-width characters)"
            )
        size = len(v.encode("utf-8"))
        if size > _BODY_MAX_BYTES:
            raise ValueError(
                f"body is {size} UTF-8 bytes; the bound is {_BODY_MAX_BYTES} "
                f"(error, never silent truncation — a longer message is not "
                f"publishable as one moment)"
            )
        return v

    @field_validator("evidence_refs")
    @classmethod
    def _evidence_refs_shape(cls, v: Optional[Tuple[str, ...]]) -> Optional[Tuple[str, ...]]:
        """1–3 comma-free ASCII refs: the joined wire form is unambiguous and
        bounded. An empty tuple is rejected — absence is ``None``, never a
        zero-length claim of evidence."""
        if v is None:
            return v
        if not (1 <= len(v) <= _EVIDENCE_REF_MAX):
            raise ValueError(
                f"evidence_refs carries {len(v)} entr"
                f"{'y' if len(v) == 1 else 'ies'}; the range is 1..{_EVIDENCE_REF_MAX} "
                f"(use None when there is no evidence)"
            )
        for ref in v:
            if not re.fullmatch(_EVIDENCE_REF, ref):
                raise ValueError(
                    f"evidence ref {ref!r} is not a bounded comma-free "
                    f"ASCII pointer (≤72 chars, pattern {_EVIDENCE_REF})"
                )
        return v

    @model_validator(mode="after")
    def _kind_thread_and_authority_rules(self) -> "CoordinationMessagePayload":
        # answer/closure matching (issue #54): a reply kind must name the
        # message it responds to; a reply never names itself (the mechanical
        # half of "stale reply" — post-expiry staleness is consumer-side).
        if self.kind in (CoordinationMessageKind.ANSWER, CoordinationMessageKind.CLOSURE):
            if self.reply_to is None:
                raise ValueError(
                    f"kind {self.kind.value!r} requires reply_to naming the message it responds to"
                )
        if self.reply_to is not None and self.reply_to == self.message_id:
            raise ValueError("reply_to must name another message, never this one")
        # No server-owned identity, authority, or outcome claim anywhere in
        # the payload (defense in depth over extra='forbid': a future field
        # added to this model whose name claims server provenance or a
        # delivery outcome fails here even before a reviewer notices).
        hit = validate_no_forbidden_keys(self.model_dump(), forbidden=FORBIDDEN_COORDINATION_KEYS)
        if hit is not None:
            raise ValueError(
                f"payload claims the server-owned field {hit.details['key']!r} "
                f"(identity, authority, and delivery outcomes are derived "
                f"server-side, never producer claims)"
            )
        return self


def coordination_aggregate_id(payload: CoordinationMessagePayload) -> str:
    """The envelope ``aggregate_id`` for a coordination message.

    ``coordination/<scope>`` — one aggregate per publishing scope, so a
    consumer's journal groups a scope's whole message thread under one
    identity (the thread itself is keyed by ``reply_to``/``message_id``).
    """
    return f"coordination/{payload.scope}"
