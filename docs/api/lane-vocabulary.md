---
title: Lane vocabulary
description: The canonical work-package Lane enum, the display subset, the producer-only genesis lane, and why changing the vocabulary is always a major version bump.
doc_status: active
updated: 2026-10-18T00:00:00Z
type: API
kind: Reference
related:
  - api/versioning-and-compatibility
  - events/work-and-status
---

# Lane vocabulary

There is exactly one canonical wire/event lane vocabulary, and this package owns it. The
`Lane` enum in [`status.py`](https://github.com/spec-kitty/spec-kitty-events/blob/main/src/spec_kitty_events/status.py)
is the single source of truth; consumers import `Lane` from `spec_kitty_events`. Host
products may keep internal state enums, but they must translate them before the event
boundary and never emit a non-canonical value.

> **This page summarises. The governing contract is**
> [`contracts/lane-vocabulary.md`](https://github.com/spec-kitty/spec-kitty-events/blob/main/contracts/lane-vocabulary.md),
> enforced by `tests/test_lane_vocabulary.py`.

## The ten canonical lanes

| Lane | Value | Display |
|---|---|---|
| `Lane.GENESIS` | `genesis` | no — pre-finalize origin state |
| `Lane.PLANNED` | `planned` | yes |
| `Lane.CLAIMED` | `claimed` | yes |
| `Lane.IN_PROGRESS` | `in_progress` | yes |
| `Lane.FOR_REVIEW` | `for_review` | yes |
| `Lane.IN_REVIEW` | `in_review` | yes |
| `Lane.APPROVED` | `approved` | yes |
| `Lane.DONE` | `done` | yes |
| `Lane.BLOCKED` | `blocked` | yes |
| `Lane.CANCELED` | `canceled` | yes |

`genesis` is producer-side only: a work package with no recorded lane events derives as
`genesis` until `finalize-tasks` seeds it to `planned`. Its only legal edges are
`genesis -> planned` (the seed, no `force`) and `genesis -> canceled`.

## Display versus validation

`Lane.GENESIS` is canonical for validation and replay but is **not** displayable. Display
surfaces (board columns, chips, progress rows, filters) must use `DISPLAY_LANES`, and
`NON_DISPLAY_LANES` for explicit exclusions — never derive columns from every `Lane` member.

## Validation and forbidden patterns

- An envelope whose payload references a lane outside the vocabulary is rejected with
  `ValidationError(code="UNKNOWN_LANE", ...)`. Values match exactly — no case folding, no
  aliases.
- Compare to the enum (`lane == Lane.IN_REVIEW`), never to a string literal, at API
  boundaries.

## Why a lane change is always major

Adding or removing a lane is a breaking contract change requiring a major **package** bump,
even when the on-wire `schema_version` is unchanged. Consumers on the previous major **fail
closed** on a new value, and untyped consumers (for example `spec-kitty-go`, which carries
the lane as a bare string) accept an unknown value silently — so the version number is the
only reliable cross-language coordination signal. Precedent: `6.0.0` added `genesis` and took
a major bump while leaving the envelope at `3.0.0`. See
[versioning & compatibility](/api/versioning-and-compatibility/).
