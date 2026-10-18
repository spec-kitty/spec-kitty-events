# Research: Decision and glossary contract reconciliation (11.0)

Sources: a three-lens squad (decision lifecycle, glossary shapes, 11.0 compatibility), 2026-10-18. Core's builders were run and their output was checked with `persistence.build_record` (10.8.0).

## G. Glossary: core shape vs package 10.8.0

Every core record carries top-level `event_id` (uuid4) and `timestamp` (ISO string), which are flat identity keys. All fields are required strings unless noted.

| Event | Core builder | Core fields | Change vs package |
|---|---|---|---|
| GlossaryScopeActivated | `events.py:215` | scope_id, glossary_version_id, mission_id, run_id | remove scope_type; add run_id |
| TermCandidateObserved | `:245` | term, source_step, actor_id, confidence (float 0–1), extraction_method, context, mission_id, run_id | term_surface→term, step_id→source_step, actor→actor_id; remove scope_id, step_metadata; add extraction_method, context, run_id |
| SemanticCheckEvaluated | `:287` | step_id, mission_id, run_id, findings[Finding], overall_severity (low/medium/high), confidence, effective_strictness (off/medium/max), recommended_action (proceed/warn/block), blocked (bool) | conflicts→findings, severity→overall_severity, "pass"→"proceed"; remove scope_id, step_metadata; add run_id, blocked |
| GenerationBlockedBySemanticConflict | `:332` | step_id, mission_id, run_id, conflicts[Finding], strictness_mode, effective_strictness | remove conflict_event_ids, blocking_strictness; add conflicts, strictness_mode, effective_strictness (strictness enum), run_id |
| GlossaryClarificationRequested | `:368` | question, term, options[str], urgency (low/medium/high), mission_id, run_id, step_id, conflict_id, semantic_check_event_id (Optional, min 1) | remove scope_id, actor; add run_id, conflict_id |
| GlossaryClarificationResolved | `:410` | conflict_id, term_surface, selected_sense (SenseRef), actor (Actor), resolution_mode (interactive/async), provenance (Provenance) | remove mission_id, clarification_event_id, selected_meaning; actor str→Actor |
| GlossarySenseUpdated | `:446` | term_surface, scope, new_sense (SenseRef + status), actor (Actor), update_type (create/update), provenance | remove mission_id, scope_id, before_sense, after_sense, reason; add scope |
| StepCheckpointed | `:482` | mission_id, run_id, step_id, strictness, scope_refs[{scope, version_id}], input_hash, cursor, retry_token | new |

The nested models follow core's glossary models. **Finding** (`models.py:113`) is `term{surface_text}`, `conflict_type` (unknown, ambiguous, inconsistent, unresolved_critical), `severity`, `confidence`, `candidate_senses[SenseRef]` and `context`. **SenseRef** is surface, scope, definition and confidence. **Actor** is actor_id, actor_type (an enum of human, llm and service) and display_name. **Provenance** is source, timestamp and actor_id. The **Scope** enum is mission_local, team_domain, audience_domain and spec_kitty_core.

`GlossaryStrictnessSet` stays as a package-only type. It is a valid contract that core doesn't emit today.

No real glossary ledger exists on disk in core (EVENTS_AVAILABLE has always been False and no run was captured), so fixtures are captured from core's builder output.

## D. Decision lifecycle

- **Decision:** reopen per D4. The reducer partitions each decision's Resolved events by outcome before its walk and resolves the set against `DECISION_POINT_TERMINAL_REOPEN`.
- **Rationale:** this matches core's order-independent fold. Core's merge driver re-sorts the log by `(at, event_id)`.
- **Decision:** `lamport_clock` is Optional, but the strict profile keeps requiring it.
- **Rationale:** core keeps no logical clock (#4941), and the durable journal does need one.
- **Alternative considered:** pin `0` to mean "no clock". Rejected, because the value claims an ordering it doesn't carry.

## R. 11.0 release surface

- **Major rules** come from `contracts/versioning-and-compatibility.md:49-58`. The 10.0.0 and 9.0.0 sections of COMPATIBILITY are the format precedent.
- **Fixtures:** rewritten and new fixtures take `min_version` 11.0.0. The precedent is that a fixture's `min_version` is the version of the boundary it pins.
- **References to change:**
  - `LOCAL_ONLY_EVENT_TYPES`: `__init__.py:271-280` and `:1080`, `persistence.py:32`, `manifest.json:3572`, `tests/test_local_only_deprecation.py`, `tests/unit/test_seven_event_contracts.py`, and docs.
  - `docs/api/versioning-and-compatibility.md` has drifted from `contracts/`. Re-sync it.
