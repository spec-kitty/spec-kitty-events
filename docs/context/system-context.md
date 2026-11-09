---
title: System context
description: The systems around the event contract — the CLI core that produces events, the transports they travel on, the consumers that reduce them, and the loopback read and write services.
doc_status: active
updated: 2026-11-08T00:00:00Z
type: Context
kind: Explanation
related:
  - architecture/durability-and-relay
  - api/python-package
---

# System context

This page places `spec-kitty-events` between the Spec Kitty CLI that produces events and the
systems that consume them. It explains the three transports an event can travel on and why
consumers stay decoupled from the producer.

## The neighbours

```mermaid
flowchart TB
  subgraph build["Build Kitty"]
    cli["spec-kitty CLI / runtime<br/>central event emitter"]
    events["spec-kitty-events<br/>contract package"]
    cli -->|imports + emits| events
  end
  diary[("status.events.jsonl<br/>local append-only log")]
  relay[["Zeitgeist relay<br/>volatile, bounded attrs"]]
  journal[("SaaS durable journal")]
  cli -->|append| diary
  cli -->|broadcast volatile moments| relay
  cli -->|durable work observations| journal

  subgraph services["Events-domain services<br/>(loopback-only)"]
    readsvc["mission-status-api<br/>read service"]
    writesvc["command-cli<br/>write service"]
  end
  cli -->|launcher starts and drives| writesvc
  writesvc -->|append — durable status appends| diary
  diary -->|reads| readsvc
  readsvc -->|status over loopback HTTP| cli

  go["spec-kitty-go<br/>(untyped consumer)"]
  dossier["Team Kitty dossier<br/>reduces the diary"]
  dash["dashboards / analytics"]
  diary --> dossier
  diary --> go
  relay --> dash
  journal --> dash
  events -. validates / reduces .-> dossier
  events -. validates / reduces .-> dash
```

## How the CLI core produces events

The CLI holds a single sanctioned path for emitting canonical events. Centralising it means
one place enforces the envelope shape, the Lamport clock (optional for local ledgers since
11.0.0) and the forbidden-key rules, so no corner of the CLI invents its own vocabulary.
The local-only classification (`LOCAL_ONLY_EVENT_TYPES`) is removed in 11.0.0: every
modelled event type is canonical.

Identity on every envelope is build-scoped, not team-scoped: `build_id` is the checkout that
produced the event and `node_id` is the causal emitter. Team routing, sharing and approval
are host-product policy and deliberately **not** part of this contract.

## Three transports

An event reaches consumers on one of three paths, and the contract's
[durability split](/architecture/durability-and-relay/) decides which:

| Transport | Carries | Validity |
|---|---|---|
| **`status.events.jsonl`** | the local, append-only status diary the CLI writes to disk | read permissively by the CLI so users never lose their own history |
| **Zeitgeist relay** | volatile "moments" (presence, lane signals, decision broadcasts) projected to a bounded `attrs` wire shape | retention-window only; never the durable record |
| **Durable journal** | durable `WorkObservation` streams and journal-profile events | gated fail-closed by the [strict profile](/api/strict-profile/) |

## The loopback services

The repository also ships two events-domain services on its own `service-v*` release line
([ADR-0003](/adr/0003-gradle-build-and-service-release-line/)). Both are strictly local:
each binds `127.0.0.1`, guards the `Host` and `Origin` headers, and answers to no other
address.

- **The read service, `mission-status-api`**, is detached and read-only: nothing starts it,
  and it never writes to the project. Run against one project directory, it serves the
  project header, the mission page, a change stream and two capped artifact reads from that
  project's event logs — the diary transport lifted to HTTP. See
  [Running the Mission Status Read API](/guides/running-the-service/).
- **The write service, `command-cli`**, is the canonical event writer. The CLI's launcher
  starts one process per git common dir and drives it; the service publishes its host, its
  OS-picked port and its bearer token to the launcher alone in `endpoint.json`. Its write core
  is built (#160, #161): a write that passes every check is appended and synced, or replayed,
  and the service answers a `DurabilityAck`; `coordination_ref_commit` waits for migration
  step 4. The protocol is in
  [write-service protocol](/architecture/services/write-service-protocol/); both services
  are mapped in [Services](/architecture/services/).

## Why consumers stay orthogonal

A consumer depends on `spec_kitty_events` — a small Pydantic package — and nothing else of
Spec Kitty's. It validates a payload with `validate_event`, gates an envelope with
`validate_strict_envelope`, and folds a stream with a family reducer. The
[Team Kitty dossier](/events/) re-uses the very same status-diary reducer the CLI uses, so
producer and consumer agree by construction rather than by convention.

Not every consumer is Python. `spec-kitty-go` carries the work-package lane as a bare string
by design; it cannot import the `Lane` enum, which is one reason a lane change is always a
major version bump — the version number is the only reliable cross-language coordination
signal. See [lane vocabulary](/api/lane-vocabulary/) and
[versioning](/api/versioning-and-compatibility/).

## See also

- [Services](/architecture/services/) — the architecture of the read and write services.
- [Durability and the relay](/architecture/durability-and-relay/) — journal versus volatile,
  and the Zeitgeist `attrs` codec.
- [The Python package](/api/python-package/) — the import surface a consumer uses.
