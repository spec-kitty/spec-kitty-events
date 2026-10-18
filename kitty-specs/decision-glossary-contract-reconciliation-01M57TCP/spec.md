# Mission Specification: Decision and glossary contract reconciliation (11.0)

**Mission**: `decision-glossary-contract-reconciliation-01M57TCP` · **Issue**: #120 (parent epic, context only: #117)
**Created**: 2026-10-18 · **Status**: Draft · **Type**: software-dev · **Release**: **11.0.0 (major)**

## Intent Summary (confirmed with the operator, 2026-10-18)

- **Primary actor:** core (spec-kitty CLI) as a producer and reader of local ledgers. Secondary actors are any other binding and the read-side consumers that replay those ledgers.
- **Trigger:** a decision (open → defer → resolve) or a glossary pipeline step is recorded with `append_event` and later replayed from the ledger.
- **Desired outcome:** every decision and glossary record core produces validates against the package contract, appends with `append_event`, and folds back through a package reducer to the state core itself derives.
- **Invariant:** fail-closed and fixture-pinned. Contracts stay the single source of truth, and no compatibility aliases are added.
- **Scope boundary:** **only the events side changes here.** Core's alignment is filed as follow-up tickets in core once this mission's outcome is settled.

### Operator decisions (binding)

| # | Decision |
|---|---|
| D1 | Core aligns to the **full canonical `Event` envelope**. An envelope field becomes optional only for a functional reason, never because core doesn't emit it today. |
| D2 | `lamport_clock` becomes **optional**: core keeps no logical clock on the status path, and a per-ledger counter would assert causality that doesn't exist. An unclocked record orders by `(timestamp, event_id)`. |
| D3 | `project_uuid` **stays required**. |
| D4 | The DecisionPoint reducer **allows the reopen deferred → resolved**. The resolved event wins whichever order the two events arrive in, matching core's `ALLOWED_TERMINAL_REOPEN = {(DEFERRED, RESOLVED)}`. |
| D5 | A package **read-side fold over ledger records** is added, so the records `append_event` writes fold back. |
| D6 | **The glossary payloads adopt the shapes core emits** (renames and type changes), and **`StepCheckpointed` is registered**. |
| D7 | Core **changes its glossary import** to the existing flat `spec_kitty_events.glossary` module. The package ships no `glossary.events` submodule. |
| D8 | **`LOCAL_ONLY_EVENT_TYPES` is removed**, as 10.8.0 announced. |
| D9 | The release is a **major, 11.0.0**. The envelope `schema_version` stays `3.0.0`. |

### Grounding (squad, 2026-10-18; core `main`, events `main` @ `e3a5d47`)

- **Decision payloads already validate.** All 675 decision rows in core's ledgers pass strict payload validation.
- **Decision record shape differs.** Core writes `{event_id, at, event_type, payload}` rows (`decisions/emit.py:117`), which are not an `Event`.
- **Reopen diverges.** The package reducer allows only `RESOLVED → {OVERRIDDEN}` (`decisionpoint.py:105-129`). A second Resolved event therefore always produces `invalid_transition`, whereas core's fold accepts one deferred plus one resolved in either order (`index_fold.py:87`, `:215-270`).
- **Two version numbers.** `DECISIONPOINT_SCHEMA_VERSION = "3.0.0"` is the wire envelope version. The "4.0.0" in `decision_moment.py` is the package release that froze Decision Moment V1, not a wire version.
- **Glossary records don't validate.** None of the 8 glossary records core builds (`src/glossary/events.py:215-530`) validates under 10.8.0. The field-level differences are recorded in [research.md](research.md) §G.
- **Core's glossary import always fails.** It imports `spec_kitty_events.glossary.events`, which doesn't exist, so `EVENTS_AVAILABLE` is always False. The flat local-fallback dict is therefore core's real wire shape.
- **`lamport_clock` reaches every ordering surface:**
  - `status.status_event_sort_key` (`status.py:747`);
  - `storage.py:156,160`, `work_replay.py:112`, `merge.py:45`, `conflict.py`, `lifecycle.py:707`;
  - the reducer sort docstrings;
  - the strict profile's required-field list (`strict.py:92`).
- **Core imports `LOCAL_ONLY_EVENT_TYPES` without a guard** (`lifecycle_events.py:376`). That import breaks on 11.0, which is the core follow-up.

## User Scenarios & Testing

### US1: Replay a reopened decision (P1)
**Given** a ledger with Opened, Resolved(deferred) and Resolved(resolved) for one `decision_point_id`, in either file order, **when** it is folded with the package read-side, **then** the reduced state is RESOLVED with the resolved event's `final_answer` and time. It reports an informational reopen marker and no error anomaly. **Given** two deferred events, deferred plus canceled, or three Resolved events, **then** the extras are reported as `invalid_transition` and the first legal terminal by sort order stands.

### US2: Append and fold back decision events as full envelopes (P1)
**Given** a full `Event` envelope (no `lamport_clock`) for each DecisionPoint type, **when** it is passed to `append_event` and the ledger is folded with `reduce_decision_point_records`, **then** the result equals reducing the same envelopes directly. Non-decision records in the same ledger are skipped.

### US3: Core's glossary records validate and replay (P1)
**Given** each of the 8 glossary records core builds today (flat dicts with `event_id` and `timestamp`), **when** passed to `append_event`, **then** each is accepted. Folding them with the glossary reducer yields the term, check, clarification (correlated by `conflict_id`), sense and checkpoint state.

### US4: Unclocked envelopes order deterministically (P1)
**Given** envelopes with `lamport_clock = None`, **when** they are sorted by the canonical ordering key, **then** they order by `(timestamp, event_id)`. In a stream mixing clocked and unclocked envelopes the order is still deterministic and pinned by a fixture. The strict journal profile still refuses an unclocked envelope.

### US5: Consumers migrate to 11.0 (P2)
**Given** a consumer on 10.x, **when** they read COMPATIBILITY.md, **then** they find every breaking change in 11.0 with the migration for each role (producer, reader, binding author).

### Edge Cases
- A Resolved(resolved) timestamped *before* its Resolved(deferred) folds the same way as the normal order.
- An Overridden must come after the effective (folded) resolve, otherwise it is `invalid_transition`.
- `semantic_check_event_id` may be absent. An empty string is refused.
- A strict-profile envelope with no `lamport_clock` is refused (`MISSING_FIELD`-class).

## Requirements

### Functional Requirements

| ID | Requirement | Status |
|----|-------------|--------|
| FR-001 | `Event.lamport_clock` MUST become `Optional[int]` (default `None`; when present `ge=0`). All other required envelope fields stay required, `project_uuid` included. | Proposed |
| FR-002 | **Amended at landing (operator ruling, 2026-10-18):** the leading part was flipped to `(1 if clocked else 0, …)` so that unclocked envelopes sort **first**, as in ADR 0001's accepted total order. The original text follows. **Ordering key.** The package MUST define one canonical key, `(0 if clocked else 1, lamport_clock or 0, timestamp, event_id)`: clocked envelopes come first in Lamport order, and unclocked envelopes follow, ordered by `(timestamp, event_id)`. A mixed stream never raises `TypeError`. The key replaces the ad-hoc keys at every `(lamport_clock, timestamp, event_id)` site: `status.status_event_sort_key` (`status.py:752`) and the reducers in `glossary.py:335`, `mission_next.py:316`, `collaboration.py:439`, `dossier.py:300` and `lifecycle.py:655`. The `(lamport_clock, node_id)` storage adapters (`storage.py:156,160`) use the same rule, with `node_id` in place of `timestamp`. | Proposed |
| FR-002a | **Concurrency and grouping.** An unclocked envelope is never concurrent with any other envelope and always forms a singleton group. This applies to `conflict.py:36-55` (`None == None` is not concurrency), `merge.py:45`, the lifecycle clock grouping (`lifecycle.py:704-707`) and the status rollback grouping by `(wp_id, lamport_clock)` (`status.py:885-890`). Each site is pinned by a test. | Proposed |
| FR-002b | **Clock-required surfaces stay clocked.** `work_replay` (LW-10: timestamp never orders durable history; `WorkStreamItem.lamport_clock: int`) and the `WorkObservation` / `HarnessObservation` contracts ("lamport_clock = journal clock") keep requiring a clock, and refuse an unclocked envelope with a clear error. | Proposed |
| FR-003 | The strict profile (`validate_strict_envelope`, `strict.py:92`) MUST keep requiring `lamport_clock`: the durable journal and TeamSpace ingress have a functional need for a logical clock. The existing `class_taxonomy/envelope_strict_journal/local_appender_envelope_5_0_0.json` expectation (`missing` includes `lamport_clock`) stays correct for that reason. | Proposed |
| FR-004 | **Two-pass reopen fold.** Pass 1 takes a decision's deduplicated Resolved events and picks the effective terminal: a single Resolved is used as is; exactly one deferred plus one resolved (either order) resolves to the *resolved* event; any other multi-Resolved set is unfoldable. Pass 2 replays the stream with the deferred event of a valid reopen pair treated as a non-terminal reopen point:
- Widened and Discussing events after the deferred one and before the effective resolve are applied normally.
- `last_state_entered_at` and the projection come from the resolved event.
- Overridden is legal only after the effective resolve by the canonical key. An Overridden that sorts before the effective resolve, including one between the two Resolved events, is `invalid_transition`.
- The reduced state exposes `reopened_from_deferred: bool`. No anomaly is emitted for a valid reopen, so consumers counting anomalies don't regress.
- An unfoldable combination reports the new anomaly kind `unfoldable_terminal_combination` (added to the anomaly-kind literal at `decisionpoint.py:145`), and the first Resolved by the canonical key stands. | Proposed |
| FR-005 | The reopen rule MUST be published as one constant (`DECISION_POINT_TERMINAL_REOPEN = {(deferred, resolved)}`) that the reducer uses and core can import. | Proposed |
| FR-006 | The package MUST export `reduce_decision_point_records(records, *, strict=True)`. It validates each ledger record (as `append_event` writes it) fail-closed, skips non-DecisionPoint types, groups by `decision_point_id`, and reduces each group with the same rules. With `strict=True` (the default) it MUST raise `LedgerValidationError` on a malformed record and raise `DecisionPointFoldError` on an `unfoldable_terminal_combination`, matching core's `FoldError`. With `strict=False` both are reported as anomalies. | Proposed |
| FR-007 | The 8 glossary payload models MUST adopt core's emitted shapes as research.md §G records them: field names, types, enums and nested `Finding` / `SenseRef` / `Actor` / `Provenance` models, with accidental core values (`semantic_check_event_id=""`) tightened to `Optional[str]` with `min_length=1`. Each model class stays unique to one event type. | Proposed |
| FR-008 | `StepCheckpointed` MUST be registered: a payload model, a committed JSON Schema, an entry in the conformance model and schema maps, and membership in the glossary event-type set. | Proposed |
| FR-009 | The glossary reducer MUST be re-keyed for the new shapes: term candidates by `term`, senses by `(scope, term_surface)`, clarifications correlated by `conflict_id`, scope gating only where a record carries `scope`, and checkpoints added to the reduced state. | Proposed |
| FR-010 | `LOCAL_ONLY_EVENT_TYPES` MUST be removed from the package root and `__all__`, and every reference in src, tests and docs updated. | Proposed |
| FR-011 | Committed JSON Schemas MUST be regenerated for every changed or added model, and the schema drift check MUST stay green. This includes `schemas/event.schema.json`: `lamport_clock` leaves `required` and takes the type `integer` (minimum 0) or `null`. | Proposed |
| FR-012 | Conformance fixtures MUST cover the new accept and reject boundaries with `min_version` `11.0.0`: (a) rewritten glossary valid and invalid fixtures, plus StepCheckpointed; (b) DecisionPoint reopen replay streams (both orders, plus Overridden) and the malformed combinations, each with an expected reducer output; (c) `ledger_append` cases for full DecisionPoint envelopes (unclocked) and for core-shaped glossary records, plus regenerated 10.8.0 `ledger_append` glossary cases; (d) ordering fixtures for unclocked and mixed streams; (e) envelope fixtures: valid "lamport_clock absent" under `validate_event`, invalid "lamport_clock absent" under the strict profile, and the existing `event_invalid_lamport_clock` (-1) still invalid; (f) DecisionPoint fixtures for an Overridden between the two Resolved events, Discussing after a deferred, and an unfoldable set.
- Every `cutover_boundary/accepted_by_validate_event` row is audited.
- Fixtures captured from core are committed as static JSON with provenance (core SHA plus builder name). No generator imports `specify_cli` (C-001).
- No existing replay fixture has more than one Resolved, so no committed `_output.json` changes. The CHANGELOG says so, which bounds the break. | Proposed |
| FR-013 | The release MUST follow the major-bump rules in `contracts/versioning-and-compatibility.md:49-58`. That means a CHANGELOG `### Breaking` section (one line per change plus its consequence), a COMPATIBILITY `11.0.0 (breaking)` section with a migration for each role, `__version__`, pyproject, the README masthead and pip lines, and `uv.lock` all at 11.0.0.
- The COMPATIBILITY section MUST carry an **old → new field map** for each glossary model (with C-002 there is no other migration path).
- The fixture `min_version` and support-matrix rows MUST be updated for the changed glossary types and `StepCheckpointed`.
- The removal of `LOCAL_ONLY_EVENT_TYPES` MUST be pinned by a test asserting it is absent from `__all__`. | Proposed |
| FR-014 | The docs MUST be updated: `docs/events/semantics-and-analytics.md` (glossary payload table and type count), `docs/events/review-audit-decisions.md` (reopen and fold), `docs/api/python-package.md`, `docs/api/ledger-write-contract.md` (LOCAL_ONLY removal, unclocked envelopes), and the "Decision Moment V1 (introduced in package 4.0.0; envelope `schema_version` 3.0.0)" wording. They MUST also say that unclocked envelopes are valid for local ledgers but not for durable-journal or TeamSpace ingress. | Proposed |

### Non-Functional Requirements

| ID | Requirement | Threshold | Status |
|----|-------------|-----------|--------|
| NFR-001 | Order independence | The reopen fold produces byte-identical reducer output for both file orders, checked by a hypothesis property over at least 100 shuffles. | Proposed |
| NFR-002 | Core-shape fidelity | Each of the 8 glossary records captured from core's builders (committed as fixtures) validates strictly, with 0 violations. | Proposed |
| NFR-003 | Static quality | `mypy --strict`, `ruff check` and `ruff format --check` report 0 issues. New and changed reducer and fold code has at least 95 % line coverage. | Proposed |
| NFR-004 | Determinism | Every new fixture reproduces byte-identically under `tests/test_fixture_determinism.py` and on the CI interpreter matrix. | Proposed |

### Constraints

| ID | Constraint | Status |
|----|------------|--------|
| C-001 | Mirror, don't depend: nothing is imported from core, saas, tracker or runtime. Core shapes are captured as data, not imported. | Fixed |
| C-002 | No silent compatibility aliases. Old glossary field names are not accepted alongside the new ones (charter Project Directive 1). | Fixed |
| C-003 | The envelope `schema_version` stays `3.0.0`. | Fixed |
| C-004 | Only the events repository is changed. Core alignment goes into follow-up tickets filed by planner-priti after the outcome. | Fixed |
| C-005 | The package ships no `glossary.events` submodule (D7). | Fixed |

### Key Entities
- **Event envelope:** canonical; `lamport_clock` is now optional.
- **Canonical ordering key:** orders unclocked envelopes by time and id.
- **DecisionPoint reopen pair:** one Resolved(deferred) plus one Resolved(resolved) for the same decision.
- **Ledger record:** a dict as `append_event` writes it.
- **`GlossaryStrictnessSet`:** kept unchanged as a package-only contract. It is valid and consumed by the package glossary reducer's strictness tracking, even though core does not emit it today, so removing it would be a gratuitous break.
- **Glossary 11.0 models:** the 8 core-shaped payloads plus nested `Finding`, `SenseRef`, `Actor` and `Provenance`.

## Success Criteria

| ID | Criterion |
|----|-----------|
| SC-001 | Every one of core's 8 glossary record shapes and all 675 decision payload shapes (as full envelopes) is accepted by `append_event` in 11.0.0. |
| SC-002 | Real reopen pairs fold to RESOLVED, as core's own fold does. |
| SC-003 | The packaged conformance gate, the schema drift check, the version guard, `mypy --strict` and ruff are green. |
| SC-004 | The core follow-up tickets are filed (planner-priti) and linked from the PR. |
