# Grounding note: spec-kitty-events #119 (scope B) + #62  (partial pass)

## Headline
- Mission `kitty-specs/mission-lifecycle-reconciliation-01M574ET` (#119) already exists on the checked-out branch `issue-119-mission-lifecycle-reconciliation`. It has one WP, WP01-lifecycle-event-schema-fixtures, and status.json shows it in lane `done`. The schemas it called for are already in `src/spec_kitty_events/schemas/`: mission_reopened, follow_up_recorded, reviewer_self_approval. The open question is whether that work is on `main` or still has to be pushed or PR'd.
- That branch sits one commit ahead of main: `9d5b890 chore: apply spec-kitty upgrade (rc7->rc8)`.
- The design docs `docs/architecture/ledger-event-contract-authority.md` and `4x-rework-program.md` are NOT present in this checkout.

## 1. Sizes
lifecycle.py 761, project_lifecycle.py 449, mission_audit.py 419, mission_next.py 561 lines (2190 in total).
Schemas found: mission_audit_* (5), next_step_issued and next_step_auto_completed, retrospective_lifecycle_*, mission_created, project_initialized, wp_created, plus the three #119 additions.

## 2. Core usage (spec-kitty: src)
- runtime/next/_internal_runtime/engine.py:26, events.py:17, schema.py:15 import `spec_kitty_events.mission_next` (RuntimeActorIdentity and payloads).
- core/mission_payload.py:30 imports `lifecycle.MissionCreatedPayload`.
- status/lifecycle_events.py:661, :830, :966 import `project_lifecycle` (ProjectInitializedPayload, WPCreatedPayload, ...). Lines 73 and 825 keep duplicated event-type constants "in sync" by hand, and note the models are extra="forbid".
- No usage of mission_audit was found in core. It is a candidate for "a package shape nothing consumes".
- Not done: a field-level comparison of what core sets and reads against the models.

## 3. Invariants
Additive-only, SemVer, never import core ("mirror-not-depend", spec.md Domain Language), fail-closed validate_event. Not yet cited against the charter, COMPATIBILITY.md or ADRs.

## 4/5. Gates and cost
`uv run --frozen pytest --pyargs spec_kitty_events.conformance -q` gives 727 passed in 7.8s (12.6s wall). Lint: `ruff check . && ruff format --check .`. Not checked: schema-generate drift and count-pinned tests.

## 6. Core pin
spec-kitty pyproject.toml:80 has `spec-kitty-events>=11.0.0,<12`. The package is at 11.0.0. The ">=10.5.1,<11" claim in #119 and in the mission's spec.md is stale.

## 7. 90-day churn
Each of the four modules has exactly 1 commit since 90 days ago.

## 8. #62
Add test cases in tests/unit/test_zeitgeist_attrs.py (around lines 641 and 662), plus ideally a conformance fixture alongside `wp_status_changed_summary_rides_bounded`. The cases are WPStatusChanged with `summary` present, for a rejection/rework lane (e.g. in_review->in_progress / planned) and for `to_lane=done`. Today only doing->in_review and in_review->approved are covered. The issue rates the risk nil because the projection is lane-agnostic, so this is a pure evidence gap. It is not linked to #119's code paths.
