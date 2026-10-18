---
title: Event families
description: The event families the Spec Kitty runtime emits, grouped by concern — mission lifecycle, work and status, review and audit, runtime observation, collaboration, semantics.
doc_status: active
updated: 2026-10-18
type: Integration
kind: Hub
---

# Event families

An **event family** is one module's set of typed payloads and event-type constants for a
single concern. The Spec Kitty CLI and runtime emit them as a Mission moves through its
lifecycle; a consumer validates and reduces them through [the Python package](/api/python-package/).
This section groups the families by concern. For the envelope they all ride in, see
[the envelope](/api/envelope/); for how a stream folds to state, see
[ordering and reduction](/architecture/ordering-and-reduction/).

## The six groups

- **[Mission lifecycle](/events/mission-lifecycle/)** — the mission from creation to close:
  lifecycle transitions and phases, the project/artifact phases (specify, plan, tasks) and
  work-package creation, and run-scoped execution (`mission next`).
- **[Work and status](/events/work-and-status/)** — the work-package lane model, the
  `WPStatusChanged` transition contract, and the append-only status-diary reducer that reads
  the CLI's `status.events.jsonl`.
- **[Review, audit and decisions](/events/review-audit-decisions/)** — decision points,
  mission audit, retrospectives, mission-dossier artifact parity, and CI gates.
- **[Runtime observation](/events/runtime-observation/)** — volatile harness presence,
  durable work observation and replay, build lifecycle, profile and ops invocations, and
  external-connector health.
- **[Collaboration](/events/collaboration/)** — multi-participant presence, drive intent,
  focus, comments and decisions, and bounded cross-mission coordination messages.
- **[Semantics and analytics](/events/semantics-and-analytics/)** — glossary semantic-integrity
  enforcement, and token-usage and diff accounting.

## Families at a glance

| Group | Module(s) | Reducer |
|---|---|---|
| [Mission lifecycle](/events/mission-lifecycle/) | `lifecycle`, `project_lifecycle`, `mission_next` | `reduce_lifecycle_events`, `reduce_mission_next_events` |
| [Work and status](/events/work-and-status/) | `status`, `diary` | `reduce_status_events`, `diary.reduce` |
| [Review, audit & decisions](/events/review-audit-decisions/) | `decisionpoint`, `mission_audit`, `retrospective`, `dossier`, `gates` | `reduce_decision_point_events`, `reduce_mission_audit_events`, `reduce_mission_dossier` |
| [Runtime observation](/events/runtime-observation/) | `harness_observation`, `work_observation`, `work_replay`, `build_lifecycle`, `profile_invocation`, `ops_invocation`, `connector` | `reduce_connector_events`, `classify_work_stream` |
| [Collaboration](/events/collaboration/) | `collaboration`, `coordination_message` | `reduce_collaboration_events` |
| [Semantics & analytics](/events/semantics-and-analytics/) | `glossary`, `analytics` | `reduce_glossary_events` |

## Durable or volatile?

A family's [durability class](/architecture/durability-and-relay/) matters as much as its
payload: journal families carry the durable record (gated by the
[strict profile](/api/strict-profile/)), volatile families are relayed as in-the-moment
signals, and the `WorkObservation` family is durable live-work on its own path. Each family
page states which it is.
