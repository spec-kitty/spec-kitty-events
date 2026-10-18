---
title: Collaboration Events
description: The in-mission collaboration family (presence, drive intent, focus, step execution, warnings, comments, decisions) plus the bounded cross-mission coordination message.
doc_status: active
updated: 2026-10-18
type: Integration
kind: Reference
related:
  - events/runtime-observation
  - events/mission-lifecycle
---

# Collaboration Events

Collaboration events record how multiple participants work together on a
mission: who is present, who is driving, what each is focused on, when steps
execute, and when two drivers risk a collision. A second, narrower family
carries bounded messages between missions. Both are emitted by the Spec Kitty
runtime and consumed by the live collaboration view and the cross-mission
coordination surface.

## In-mission collaboration — `collaboration.py`

The collaboration family has 14 event types, all members of
`COLLABORATION_EVENT_TYPES`. They cover participant presence, drive intent,
focus, prompt-step execution, collision warnings, comments, decisions and
session links.

| Event type | Payload | Meaning |
|---|---|---|
| `ParticipantInvited` / `ParticipantJoined` / `ParticipantLeft` | participant identity | A participant is invited, joins, or leaves. |
| `PresenceHeartbeat` | heartbeat | A participant reports liveness. |
| `DriveIntentSet` | drive intent | A participant sets (or clears) intent to drive. |
| `FocusChanged` | focus target | A participant changes what they are focused on. |
| `PromptStepExecutionStarted` / `PromptStepExecutionCompleted` | step execution | A prompt step starts / completes. |
| `ConcurrentDriverWarning` | warning | Two participants drive concurrently. |
| `PotentialStepCollisionDetected` | warning | A potential step collision is detected. |
| `WarningAcknowledged` | acknowledgement | A warning is acknowledged. |
| `CommentPosted` | comment | A comment is posted. |
| `DecisionCaptured` | decision | A decision is captured. |
| `SessionLinked` | session link | A collaboration session is linked. |

The full list of constants is `PARTICIPANT_INVITED`, `PARTICIPANT_JOINED`,
`PARTICIPANT_LEFT`, `PRESENCE_HEARTBEAT`, `DRIVE_INTENT_SET`, `FOCUS_CHANGED`,
`PROMPT_STEP_EXECUTION_STARTED`, `PROMPT_STEP_EXECUTION_COMPLETED`,
`CONCURRENT_DRIVER_WARNING`, `POTENTIAL_STEP_COLLISION_DETECTED`,
`WARNING_ACKNOWLEDGED`, `COMMENT_POSTED`, `DECISION_CAPTURED` and
`SESSION_LINKED`.

**Reducer.** `reduce_collaboration_events` filters to
`COLLABORATION_EVENT_TYPES` and folds into a frozen
`ReducedCollaborationState` scoped to one `mission_id`: the active
`participants` roster and `departed_participants`, last-heartbeat `presence`
per participant, the `active_drivers` set, `focus_by_participant` and the
reverse `participants_by_focus` index, and ordered `warnings`, `decisions` and
`comments` timelines.

**Durability.** Journaled and reduced into durable collaboration state; the
live view projects presence and focus onto the Zeitgeist relay as volatile
moments.

## Cross-mission coordination — `coordination_message.py`

The coordination-message family is the bounded cross-mission message contract.
One event type carries one authored message between missions.

| Event type | Payload model | Meaning |
|---|---|---|
| `CoordinationMessage` | `CoordinationMessagePayload` | One bounded, one-line authored message routed between missions. |

`COORDINATION_MESSAGE_EVENT_TYPES` is the single-member frozenset
`{CoordinationMessage}`. The message `kind` is the closed `CoordinationMessageKind`
set: `fact`, `proposal`, `question`, `answer`, `closure` — `answer` and
`closure` both require a `reply_to` naming the message they conclude.
`RequestedAction` is the closed set of coordination requests a sender may make:
`review`, `answer`, `unblock`, `decide`. A requested action rides as data and
never overrides the recipient's task or decision authority; forbidden keys are
pinned by `FORBIDDEN_COORDINATION_KEYS`. `coordination_aggregate_id` derives the
message's aggregate id.

**Durability.** Volatile. A coordination message publishes fire-and-forget over
the Zeitgeist relay (`CoordinationMessage` is in `VOLATILE_EVENT_TYPES`);
inline prose is bounded to a one-line, broadcast-safe value.

## See also

- [Runtime observation events](/events/runtime-observation/) — the volatile
  harness-presence feed and the durable work vocabulary alongside collaboration.
- [Mission lifecycle events](/events/mission-lifecycle/) — the mission a
  collaboration state is scoped to.
- [The Python package](/api/python-package/) — how to import, validate and
  reduce these events.
- [Durability and relay](/architecture/durability-and-relay/) — how volatile
  coordination messages reach the relay.
