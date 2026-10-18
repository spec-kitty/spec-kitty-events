---
title: Semantics and Analytics Events
description: The glossary semantic-integrity family that gates generation on term consistency, and the lightweight analytics family that records token usage and diff summaries.
doc_status: active
updated: 2026-10-18
type: Integration
kind: Reference
related:
  - events/runtime-observation
  - events/review-audit-decisions
---

# Semantics and Analytics Events

Two small families sit on the semantic and measurement edge of a mission. The
glossary family enforces semantic integrity — keeping the terms a mission uses
consistent, and blocking generation when they conflict. The analytics family
records lightweight runtime measurements: token usage and diff summaries. Both
are emitted by the Spec Kitty runtime and consumed by the semantic-integrity
checker and the analytics journal.

## Glossary semantic integrity — `glossary.py`

The glossary family activates a semantic scope, observes candidate terms, runs
semantic checks, requests and resolves clarifications, updates a term's sense,
blocks generation on an unresolved conflict, and checkpoints a step.
Since 11.0.0 the payloads carry the shapes core emits
([#120](https://github.com/spec-kitty/spec-kitty-events/issues/120)).

| Event type | Payload model | Meaning |
|---|---|---|
| `GlossaryScopeActivated` | `GlossaryScopeActivatedPayload` | A semantic scope is activated for the mission. |
| `TermCandidateObserved` | `TermCandidateObservedPayload` | A candidate term is observed in produced content. |
| `SemanticCheckEvaluated` | `SemanticCheckEvaluatedPayload` | A semantic check is evaluated against the glossary. |
| `GlossaryClarificationRequested` | `GlossaryClarificationRequestedPayload` | A clarification is requested for an ambiguous term. |
| `GlossaryClarificationResolved` | `GlossaryClarificationResolvedPayload` | A requested clarification is resolved. |
| `GlossarySenseUpdated` | `GlossarySenseUpdatedPayload` | A term's recorded sense is updated. |
| `GenerationBlockedBySemanticConflict` | `GenerationBlockedBySemanticConflictPayload` | Generation is blocked by an unresolved semantic conflict. |
| `GlossaryStrictnessSet` | `GlossaryStrictnessSetPayload` | The glossary strictness level is set. |
| `StepCheckpointed` | `StepCheckpointedPayload` | A step is checkpointed with its scope versions, input hash and cursor. |

The nine types make up `GLOSSARY_EVENT_TYPES`. The payloads nest `GlossaryFinding`,
`SenseRef`, `GlossaryActor`, `GlossaryProvenance` and `ScopeRef`, and are imported from the
flat `spec_kitty_events.glossary` module. Each carries `mission_id` and `run_id`, except
`GlossaryStrictnessSet`, which is unchanged, and the sense and clarification-resolved
records, which are keyed by `scope`/`term_surface` and `conflict_id`.

**Reducer.** `reduce_glossary_events` filters to `GLOSSARY_EVENT_TYPES` and
folds into a frozen `ReducedGlossaryState`, tracking the active scope, the
current strictness (default `medium`), term candidates keyed by `term`, senses keyed
by `(scope, term_surface)`, `ClarificationRecord`s correlated by `conflict_id`,
`semantic_checks`, `generation_blocks`, `checkpoints` and `anomalies`.

**Durability.** Journaled and reduced into durable glossary state.

## Analytics — `analytics.py`

The analytics family records two lightweight runtime measurements. It defines
payloads only; it has no reducer.

| Event type | Payload model | Meaning |
|---|---|---|
| `TokenUsageRecorded` | `TokenUsageRecordedPayload` | Token-usage totals for a unit of work (totals are self-validated). |
| `DiffSummaryRecorded` | `DiffSummaryRecordedPayload` | A summary of a code diff (files/lines changed). |

The two types make up `ANALYTICS_EVENT_TYPES`.

**Durability.** Journaled measurement rows; not projected onto the volatile
relay.

## See also

- [Runtime observation events](/events/runtime-observation/) — the broader
  runtime-observation families that measure what a mission does.
- [Review, audit and decision events](/events/review-audit-decisions/) — the
  governance surface that semantic blocking relates to.
- [The Python package](/api/python-package/) — how to import, validate and
  reduce these events.
- [Ordering and reduction](/architecture/ordering-and-reduction/) — how the
  glossary reducer orders and deduplicates its events.
