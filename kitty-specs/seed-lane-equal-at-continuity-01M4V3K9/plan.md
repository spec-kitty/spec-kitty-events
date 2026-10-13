# Implementation Plan: Seed-lane equal-`at` continuity fix

**Branch**: `kitty/mission-seed-lane-equal-at-continuity-01M4V3K9` | **Date**: 2026-10-13 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `kitty-specs/seed-lane-equal-at-continuity-01M4V3K9/spec.md`

## Summary

Fix spec-kitty-events#87: in the wall-clock continuity reducer (`spec_kitty_events.diary`),
a real `approved -> done` that shares its approval's `at` but sorts **before** it by
`event_id` is (a) dropped by arm-(ii) continuity and (b) not counted by `_is_bare_sibling`
as "built upon" the approval — so the approval is judged bare and a later rollback seed
reopens the WP. Make the "built upon by a real `approved -> done`" determination robust to
the equal-`at`/earlier-`event_id` ordering, so the done settles the approval regardless of
id order. Red-first regression pinned to issue 87.

## Technical Context

**Language/Version**: Python 3.11+
**Primary Dependencies**: none new (stdlib + existing `spec_kitty_events`)
**Storage**: N/A (pure reducer over JSONL event rows)
**Testing**: pytest (`tests/test_status_diary_reducer.py`, packaged conformance replay)
**Target Platform**: library (contract package)
**Project Type**: single
**Performance Goals**: unchanged; reducer stays O(events) with the existing look-ahead
**Constraints**: no public API change; snapshot shape + `3.0.0` schema + Lamport path untouched; touched-function complexity ≤ 15
**Scale/Scope**: one helper in `diary.py`, one regression test; ~10–20 lines

## Charter Check

- ATDD / red-first (ADR 2026-07-17-1): the regression repro is RED through `reduce()` before the fix. PASS (planned).
- Canonical sources: edit `src/spec_kitty_events/diary.py` (the SoT), not a generated copy. PASS.
- Terminology: no operator/user prose added. N/A.
- No retired-surface work; no schema/version bump. PASS.

## Project Structure

### Documentation (this feature)

```
kitty-specs/seed-lane-equal-at-continuity-01M4V3K9/
├── spec.md
├── plan.md
├── tasks.md
└── tasks/WP01.md
```

### Source Code (repository root)

```
src/spec_kitty_events/diary.py          # _is_bare_sibling / arm-(ii) continuity (the fix)
tests/test_status_diary_reducer.py       # red-first regression (issue 87) + edge cases
```

## Complexity Tracking

No new abstractions. The fix tightens an existing predicate; no deviation from the current
reducer design requiring justification.

## Parallel Work Analysis

### Dependency Graph

Single work package (WP01); no parallelism.

### Work Distribution

- **Sequential work**: WP01 (red-first test → fix → green). Nothing else.
- **Parallel streams**: none.
- **Agent assignments**: one implementer (python-pedro), `src/spec_kitty_events/diary.py` + the reducer test.

### Coordination Points

- **Sync schedule**: N/A (single WP, single branch).
- **Integration tests**: the full `tests/test_status_diary_reducer.py` + packaged conformance replay must stay green (no regression to #6061/#6058/#4941).
