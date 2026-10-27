# Decision Moment `01M5Z6X95H455KQZNJ7YNM96ZR`

- **Mission:** `cloudevents-envelope-registry-upcaster-01M5Z6VM`
- **Origin flow:** `specify`
- **Slot key:** `specify.ordering.mode`
- **Input key:** `ordering_mode`
- **Status:** `resolved`
- **Created:** `2026-10-27T17:35:42.769289+00:00`
- **Resolved:** `2026-10-27T17:35:46.049232+00:00`
- **Opened by:** `cli`
- **Other answer:** `false`

## Question

How does #92 treat ordering given ADR 0001 decision C vs the 4.0.0 wall-clock reducer?

## Options

_(none)_

## Final answer

Keep C (Lamport-first) as the target and shipped total order; #92 does not switch the diary reducer, which stays wall-clock (at, event_id) for 4.0.0; the reducer switch is post-GA (#6609).

## Rationale

_(none)_

## Change log

- `2026-10-27T17:35:42.769289+00:00` — opened
- `2026-10-27T17:35:46.049232+00:00` — resolved (final_answer="Keep C (Lamport-first) as the target and shipped total order; #92 does not switch the diary reducer, which stays wall-clock (at, event_id) for 4.0.0; the reducer switch is post-GA (#6609).")
