---
title: Shared event-model types
description: "Shared event-model types: the sub-model schemas the event payloads compose (identities, refs, enums and records), each with diagram, properties and source."
doc_status: active
updated: 2026-11-03T00:00:00Z
type: Integration
kind: Hub
okf_version: "0.2"
related:
  - events/mission-lifecycle
  - events/work-and-status
  - api/envelope
---

# Shared event-model types

The event payloads of the six families compose a set of shared building blocks:
identity records (`mission_identity`, `session_identity`, `runtime_actor_identity`, …),
references (`activity_ref`, `content_hash_ref`, `teamspace_ref`, …) and value sets such
as `sync_lane_v1`/`sync_lane_v2`. They are not event types themselves and carry no
payload of their own; validators and consumers reach them from the payload that
embeds them.

The pages below are generated from `src/spec_kitty_events/schemas/` by
`scripts/schema_pages.py` (one page per schema, with a rendered diagram); never
hand-edit them (C-001). The family hubs that consume them are listed on the
[event families](/events/) index.

<!-- BEGIN GENERATED: schemas -->
## Schemas

- [Schema: activity_ref](/events/shared/activity_ref/) — Activity grouping: an observation may belong to a logical activity and an activity may name its parent — the durable analogue of the volatile focus/activity signal in
- [Schema: actor_identity](/events/shared/actor_identity/) — The acting principal, with optional agent/factory framing.
- [Schema: agent_profile_ref](/events/shared/agent_profile_ref/) — The agent profile a principal acted through — distinct from the principal itself (LW-01: principal, agent profile, session, and factory attempt are four different identities
- [Schema: artifact_identity](/events/shared/artifact_identity/) — Canonical identity for one artifact instance.
- [Schema: artifact_reference](/events/shared/artifact_reference/) — A reference to authenticated artifact content — long content is never inlined (LW-11): it is referenced by content hash, byte length, media type, and completeness.
- [Schema: auth_principal_binding](/events/shared/auth_principal_binding/) — Roster-level auth principal to participant binding.
- [Schema: closure_message_ref](/events/shared/closure_message_ref/) — Reference to a Slack closure message posted when a decision is resolved.
- [Schema: content_hash_ref](/events/shared/content_hash_ref/) — Content fingerprint with optional size and encoding metadata.
- [Schema: coverage_gap](/events/shared/coverage_gap/) — An explicitly recorded capture gap (LW-08/LW-10): an area of work this producer could not observe, with the honest reason — gaps are recorded as data, never silently dropped.
- [Schema: default_channel_ref](/events/shared/default_channel_ref/) — Reference to the default Slack channel for a teamspace.
- [Schema: factory_attempt_ref](/events/shared/factory_attempt_ref/) — The factory job/attempt an observation was produced under (planning #2270 binds these into continuous missions).
- [Schema: file_action](/events/shared/file_action/) — A file operation, as metadata only (LW-04/LW-11): operation, path (and rename destination), size deltas.
- [Schema: focus_target](/events/shared/focus_target/) — Structured focus reference.
- [Schema: lane](/events/shared/lane/) — Work-package lifecycle lanes.
- [Schema: local_namespace_tuple](/events/shared/local_namespace_tuple/) — Minimum collision-safe key for parity baseline scoping.
- [Schema: mission_identity](/events/shared/mission_identity/) — Canonical mission identity — stable before, through, and after rename.
- [Schema: participant_external_refs](/events/shared/participant_external_refs/) — Optional cross-channel identity references for a ParticipantIdentity.
- [Schema: participant_identity](/events/shared/participant_identity/) — SaaS-minted, mission-scoped participant identity.
- [Schema: principal_ref](/events/shared/principal_ref/) — A lightweight reference to a principal (message recipient, handoff target) — the full identity is carried by the sender's ActorIdentity.
- [Schema: producer_identity](/events/shared/producer_identity/) — Who produced this observation, at which instance, at which sequence.
- [Schema: programme_link](/events/shared/programme_link/) — Cross-repo programme linkage (LW-02): distinct missions in distinct repositories that belong to one programme.
- [Schema: provenance_ref](/events/shared/provenance_ref/) — Source trace connecting an artifact or event to its authoritative origin.
- [Schema: raci_role_binding](/events/shared/raci_role_binding/) — Single actor-role binding; ``actor_id`` is required (R2).
- [Schema: repository_identity](/events/shared/repository_identity/) — Admitted repository identity — stable across provider renames.
- [Schema: resolved_raci_snapshot](/events/shared/resolved_raci_snapshot/) — Fully resolved RACI binding with provenance (core ``ResolvedRACIBinding``).
- [Schema: retrospective_actor_ref](/events/shared/retrospective_actor_ref/) — Actor reference embedded in runtime retrospective events.
- [Schema: retrospective_mode](/events/shared/retrospective_mode/) — Resolved retrospective execution mode.
- [Schema: retrospective_mode_source_signal](/events/shared/retrospective_mode_source_signal/) — How retrospective execution mode was resolved.
- [Schema: runtime_actor_identity](/events/shared/runtime_actor_identity/) — Identity of a runtime actor (human, LLM, or service).
- [Schema: session_identity](/events/shared/session_identity/) — The stable logical session that produced this observation.
- [Schema: source_provenance](/events/shared/source_provenance/) — How this observation was captured (LW-04's capability matrix): the capture source, the named capability that produced it, and — when capture is partial — the honest limitation.
- [Schema: summary_block](/events/shared/summary_block/) — Structured summary of a resolved decision with provenance.
- [Schema: sync_lane_v1](/events/shared/sync_lane_v1/) — V1 compatibility sync lanes for downstream consumers.
- [Schema: sync_lane_v2](/events/shared/sync_lane_v2/) — V2 sync lanes preserving the explicit approved review outcome.
- [Schema: teamspace_ref](/events/shared/teamspace_ref/) — Reference to a Slack-connected teamspace.
- [Schema: test_action](/events/shared/test_action/) — A test execution at any lifecycle state: selector plus explicit counts (carried only once the run concludes, ``state='result'``).
- [Schema: thread_ref](/events/shared/thread_ref/) — Reference to a Slack thread.
- [Schema: tool_action](/events/shared/tool_action/) — A tool invocation at any lifecycle state.
- [Schema: typed_rejection](/events/shared/typed_rejection/) — A typed rejection returned by a consumer that could not accept a durable work observation.
- [Schema: user_connection_status](/events/shared/user_connection_status/) — Per-user connection state entry in ReducedConnectorState roster.
- [Schema: work_context](/events/shared/work_context/) — Optional work context slots — all optional, all presentation/scoping, none of them identity (identity is mission/session/repository above).
<!-- END GENERATED: schemas -->
