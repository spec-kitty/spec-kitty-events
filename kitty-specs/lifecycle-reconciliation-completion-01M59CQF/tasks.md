# Work Packages: Lifecycle reconciliation completion and RACI decision events

**Mission**: `lifecycle-reconciliation-completion-01M59CQF` | **Spec**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md)
**Branch**: planning/base and merge target `issue-119-lifecycle-reconciliation-completion`

## Subtask Index

| ID | Description | WP | Parallel |
|---|---|---|---|
| T001 | Create `decision_significance.py` with constants and the four models | WP01 | |
| T002 | Register the two types with the validator dispatch; export from the package root | WP01 | |
| T003 | Register the models with the schema generator; regenerate schemas | WP01 | |
| T004 | Add the `decision_significance` and `lifecycle` fixture categories; write decision fixtures and manifest entries | WP01 | |
| T005 | Unit tests for the new models | WP01 | [P] |
| T006 | Producer-row agreement test (FR-007) | WP01 | [P] |
| T007 | WP01 gates | WP01 | |
| T008 | Invalid fixtures for the 18 lifecycle types | WP02 | [P] |
| T009 | Valid WPStatusChanged fixtures for genesis, in_review, approved, blocked, canceled | WP02 | [P] |
| T010 | Summary-on-rework and summary-on-done unit tests and fixtures (#62) | WP02 | |
| T011 | Lifecycle replay stream and reducer golden | WP02 | |
| T012 | Packaged replay runner and in-repo lifecycle conformance test | WP02 | |
| T013 | WP02 gates | WP02 | |
| T014 | Reconciliation document for the four modules | WP03 | [P] |
| T015 | Docs entries for the new events and doc index updates | WP03 | [P] |
| T016 | CHANGELOG and COMPATIBILITY 11.1.0 entries | WP03 | |
| T017 | Version bump to 11.1.0 | WP03 | |
| T018 | Stale core-pin corrections and final gates | WP03 | |

## WP01 - RACI decision events: models, schemas, fixtures

**Prompt**: [tasks/WP01-raci-decision-events.md](tasks/WP01-raci-decision-events.md)
**Priority**: P1 (User Story 1) | **Dependencies**: none
**Goal**: `SignificanceEvaluated` and `DecisionTimeoutExpired` are package contracts with a `RaciRoleBinding` whose `actor_id` is required and non-empty.
**Independent test**: valid fixtures accepted, invalid refused (null/empty/missing `actor_id`), the producer-row agreement test passes, and the schema drift test is green.

T001 Create `decision_significance.py` with constants and the four models (WP01)
T002 Register the two types with the validator dispatch; export from the package root (WP01)
T003 Register the models with the schema generator; regenerate schemas (WP01)
T004 Add fixture categories; write decision fixtures and manifest entries (WP01)
T005 Unit tests for the new models (WP01)
T006 Producer-row agreement test (WP01)
T007 WP01 gates (WP01)

Risks: an over-strict mirror of core's RACI provenance rules; adding to a count-pinned set. Both are escalation triggers.

## WP02 - Lifecycle conformance breadth, replay and WPStatusChanged lanes (#62)

**Prompt**: [tasks/WP02-lifecycle-conformance-breadth.md](tasks/WP02-lifecycle-conformance-breadth.md)
**Priority**: P1 (User Stories 2, 3) | **Dependencies**: WP01 (shares the append-only registration in `conformance/fixtures/manifest.json`; WP01 adds the `lifecycle` category)
**Goal**: every listed lifecycle type has a refused invalid fixture, a replay stream folds to its golden, and WPStatusChanged is evidenced on every lane and with a summary on rework and done.
**Independent test**: the packaged conformance gate, including the new replay runner, and the new tests pass.

T008 Invalid fixtures for the 18 lifecycle types (WP02)
T009 Valid WPStatusChanged lane fixtures (WP02)
T010 Summary on rework and done, unit tests and fixtures (WP02)
T011 Lifecycle replay stream and reducer golden (WP02)
T012 Packaged replay runner and in-repo conformance test (WP02)
T013 WP02 gates (WP02)

Risks: an invalid fixture that relies on a rule the model does not have; any edit to existing tests or count pins.

## WP03 - Reconciliation document, release notes, 11.1.0

**Prompt**: [tasks/WP03-reconciliation-and-release.md](tasks/WP03-reconciliation-and-release.md)
**Priority**: P2 (User Stories 4, 5) | **Dependencies**: WP01, WP02
**Goal**: one reconciliation record, an honest 11.1.0 changelog and compatibility entry, the version bump, and the stale-pin corrections.
**Independent test**: the document covers 4 of 4 modules and 6 of 6 follow-ups, the version reads 11.1.0 everywhere, and no maintained text claims `>=10.5.1,<11`.

T014 Reconciliation document (WP03)
T015 Docs entries and doc index (WP03)
T016 CHANGELOG and COMPATIBILITY 11.1.0 (WP03)
T017 Version bump (WP03)
T018 Stale-pin corrections and final gates (WP03)

## Dependency graph

WP01 -> WP02 -> WP03; WP01 -> WP03.
MVP: WP01.
