# Contract: Bounded Cross-Mission Coordination Message

**Status**: volatile (live relay frame) · **Introduced**: 10.3.0 · **Owner
module**: `spec_kitty_events.coordination_message` (vocabulary, rules),
`spec_kitty_events.zeitgeist_attrs` (wire projection) · **Issue**:
spec-kitty/spec-kitty-events#54 (design spike
spec-kitty/spec-kitty-planning#2163; policy decision
spec-kitty/spec-kitty-planning#2188)

> This document governs the smallest shared coordination-message contract
> the planning#2163 spike demonstrated was needed, carried through the
> **existing** Zeitgeist event transport (the volatile `zeitgeist_attrs`
> codec). It is the payload contract only: the CLI send wrapper
> (spec-kitty/spec-kitty#4269, "live authored messages over the relay") is
> the future producer, does not exist yet, and is not advertised by
> anything here. No relay chat database, no durable delivery receipts, no
> ratification inferred from source.

## 0. Authority basis (planning#2188, resolved)

planning#2188 asked for the standing-authorization and disclosure policy
before any publish tool ships. It is supplied by the Live Work owner record
`HIC-LIVE-WORK-DURABLE-ZEITGEIST-2026-09-13.md` §"Standing authorization"
(recorded in `docs/LIVE-WORK.md` §8's authority reconciliation ledger in
the planning repo): agents publish authored communication within the
authorized team/repo/mission scope without per-message human approval,
superseding the human-outbox-only constraint for this programme. The
2026-09-14 amendment (`HIC-ZEITGEIST-NOW-GIT-DONE-2026-09-14.md`) supersedes
only the *durable* half of that record — authored messages continue as live
relay frames, which is exactly the transport this contract uses. Two
bindings travel with that authorization:

- **Received prose never overrides task authority.** A message is data, not
  instructions with superior authority. It never authorizes scope
  expansion, gate bypass, arbitrary command execution, or impersonation.
- **Bounded inline prose** per `HIC-TEAM-TRUST-BOUNDARY-BOUNDED-PROSE-2026-09-14.md`:
  a team is a trust boundary; prose rides the relay only as one printable
  line within the 240-UTF-8-byte per-attr bound, validated at creation with
  an error rather than silent truncation, and never silently truncated
  anywhere (unlike the derived `summary` attr, a truncated message would
  corrupt meaning).

## 1. Envelope and wire shape

One volatile event type, `event_type="CoordinationMessage"`, one payload
model. The envelope is the standard `Event` with no new field:
`aggregate_id` = `coordination_aggregate_id(payload)` =
`coordination/<scope>`, `timestamp` = the created time (R-T-01), and the
standard `correlation_id`/`causation_id` causal semantics (a republication
of the same `message_id` is a new `event_id` under the same correlation).
The projection carries `event_id`/`occurred_at` from the envelope and every
payload field as one bounded attr; `scope` is the frame `ref`.
`contract_version` rides as an explicit attr (today `"1"` only) and both
codec directions reject an unknown version with
`UnknownContractVersionError` — the `ops_invocation` versioning pattern.

## 2. Fields: server-derived vs untrusted producer claims

Every payload field is an **untrusted producer claim**:

| Field | Meaning | Rules |
|---|---|---|
| `message_id` | stable scoped message identity | 8–64 chars, ASCII ident grammar; unique within `scope` |
| `kind` | `fact`/`proposal`/`question`/`answer`/`closure` | closed enum |
| `sender_agent_id` | the **logical agent** that authored the message | opaque label, never authority (see §3) |
| `scope` | the one authorized publishing scope | `mission/<id>` or `repo/<id>` shaped; the frame `ref` |
| `body` | the authored prose | one printable line, ≤240 UTF-8 **bytes**, error not truncation |
| `mission_ref` | sender's mission when distinct from scope | optional, never invented |
| `addressed_to` | a **mention** of one logical recipient | optional; bare ident, no scope syntax (see §4) |
| `evidence_refs` | opaque evidence pointers | 1–3, each ≤72 comma-free ASCII chars; joined `,` on the wire (218 B worst case) |
| `requested_action` | `review`/`answer`/`unblock`/`decide` | optional; a request, never an instruction |
| `ttl_s` | freshness budget | 1..604800, default 86400; expiry = `occurred_at + ttl_s` |
| `reply_to` | the `message_id` responded to | required for `answer`/`closure`; never self-referential |
| `contract_version` | payload-shape version | codec-gated both directions |

**Server-derived, never payload fields** (enforced by
`FORBIDDEN_COORDINATION_KEYS` inside the payload model — the same
"caller fields never grant authority" decision as HarnessObservation and
WorkObservation — plus `extra="forbid"`): the authenticated principal (user
account), team, deployment, membership/role, and every delivery outcome
(`delivered`, `acknowledged`, `read`, `received_at`). A payload that claims
any of them is invalid at construction.

## 3. Sender identity: user account vs logical agent

`sender_agent_id` is the *logical agent* — the agent that authored the
message — deliberately distinct from the user account it ran under
(planning#2163: "do not equate another agent using the same account with
the sender itself"). The account↔agent binding is service-side; the relay
attests the authenticated actor from the credential and this package never
broadcasts a producer-asserted identity breakdown (the codec's Actor
narrowing doctrine: a plain opaque label rides, nothing structured).
Across command/reader processes, a reader filtering its own messages
(zeitgeist#295 `filterOwn`) keys on the **server-attested** identity, never
on `sender_agent_id`: two agents sharing one account are distinct senders,
and the same `sender_agent_id` under two accounts is not the same sender.

## 4. Target semantics

- **A mention cannot provide privacy.** `addressed_to` is a mention; the
  frame publishes to the whole `scope` and every scope subscriber may read
  the body. Broadcast is permitted only when the content is readable by all
  of the scope's subscribers — the sender's obligation, enforced nowhere
  else.
- **No cross-scope target is expressible.** `addressed_to` is a bare ident;
  its grammar rejects any scope qualification (`team-b:agent-9`,
  `repo/other/agent-9`), and there is no other audience field.
- **Narrow delivery requires service-side enforcement** that does not
  exist. Per issue #54's boundaries, a relay/SaaS change for it is a
  separate issue only if integration demonstrates an actual gap.

## 5. Outcome semantics: acceptance is not delivery

Publishing is fire-and-forget over the volatile relay. Acceptance (a valid
frame admitted to the relay) is not delivery, acknowledgement, or
completion. An **absent recipient** changes nothing — the message publishes
to the scope whether the addressed agent is online or not; there are no
per-recipient receipts (and a payload asserting one is invalid, §2). A
moment dropped by a downed relay or an expired budget is **lost by design**
— the next authoritative heartbeat restores current context; older work
links to Git (`HIC-ZEITGEIST-NOW-GIT-DONE-2026-09-14.md`).

## 6. Identity, expiry, replies, duplicates

- **Idempotency / duplicate intent**: re-publishing the same `message_id`
  under a new `event_id` is a valid *republication* (retry after a relay
  outage, or re-assert). The codec never deduplicates; consumers
  deduplicate on `(scope, message_id)` — the same shape Team Kitty applies
  on `(team, event_id)`.
- **Expiry**: a consumer treats the message as expired at
  `occurred_at + ttl_s`. Created time is the envelope's `occurred_at`
  (R-T-02: no client time field owns order or expiry).
- **Answer/closure matching**: `answer` and `closure` MUST carry `reply_to`
  naming the message they respond to; a reply never references itself.
  Whether the referenced message was a `question`/`proposal`, and whether
  it had already expired (**stale reply**), needs thread state this
  contract deliberately does not keep — consumer-side matching guidance,
  not validation. An expired question may still be answered; the answerer
  should expect the thread to have lapsed.
- **Automatic lifecycle events stay separate**: a coordination message is
  authored communication, never a status transition; reusing a lifecycle
  event kind for it is unexpressible — this is its own event type with its
  own vocabulary.

## 7. Prose framing is not authority

`body` is authored prose — supplied by the producer's user or agent for
this purpose, never harvested from local files, environment values,
command output, or private reasoning. Creation-time validation limits
**shape** (one printable line — `str.isprintable()` rejects newlines,
C0/C1 controls, the Unicode line/paragraph separators, and bidi/zero-width
formatting characters — within the 240-byte bound, error not truncation),
never intent; it is not an injection control and is not described as one.
Instruction-shaped prose is shape-*valid* and travels as data (pinned by
the `coordination_message_malicious_prose_is_data` fixture). Binding on
every implementation: agent-facing surfaces render `body` only inside the
nonce-framed untrusted-content block, never interpolated into
instructions, tool arguments, or commands; human surfaces escape it;
`evidence_refs` carry pointers only.

## 8. Conformance

16 golden fixtures in the `zeitgeist_attrs` conformance category (8 valid,
8 invalid) cover the issue's required list: oversized body, false sender,
cross-scope target, stale (self-referential) reply, unknown version, and
malicious prose (both the shape-level control-character rejection and the
shape-valid instruction-shaped data case), plus answer/closure matching,
duplicate-intent republication, the multibyte 240-byte boundary, and a
delivery-outcome claim. They run in-repo
(`tests/test_zeitgeist_attrs_conformance.py`) and packaged
(`pytest --pyargs spec_kitty_events.conformance`,
`conformance/test_zeitgeist_attrs_codec.py`).
