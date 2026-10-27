# Decision Moment `01M5Z6YGKWKTJZ9YRPMGKSCP67`

- **Mission:** `cloudevents-envelope-registry-upcaster-01M5Z6VM`
- **Origin flow:** `specify`
- **Slot key:** `specify.envelope.clock`
- **Input key:** `clock_required`
- **Status:** `resolved`
- **Created:** `2026-10-27T17:36:23.164462+00:00`
- **Resolved:** `2026-10-27T17:36:26.405209+00:00`
- **Opened by:** `cli`
- **Other answer:** `false`

## Question

How to reconcile required lamportclock/nodeid with clockless upcast rows?

## Options

_(none)_

## Final answer

Two profiles: read profile (upcast output) has them optional; write profile keeps requiring them.

## Rationale

_(none)_

## Change log

- `2026-10-27T17:36:23.164462+00:00` — opened
- `2026-10-27T17:36:26.405209+00:00` — resolved (final_answer="Two profiles: read profile (upcast output) has them optional; write profile keeps requiring them.")
