# Decision Moment `01M758AR0XV801HVGA8ZPQWATR`

- **Mission:** `write-fold-encoder-parity-01M74ZY0`
- **Origin flow:** `plan`
- **Slot key:** `plan.d4.contract-fixtures`
- **Input key:** `d4_fixture_conflict`
- **Status:** `resolved`
- **Created:** `2026-11-11T12:11:40.957478+00:00`
- **Resolved:** `2026-11-11T12:14:43.268268+00:00`
- **Opened by:** `cli`
- **Other answer:** `false`

## Question

D4 (validate_transition = core edge graph) conflicts with two packaged valid fixtures and the 'force-exempt by contract' rule for for_review->in_progress (#48), and reduce_status_events would drop those events as anomalies. How to resolve?

## Options

- Full D4: update fixtures + contract text, minor bump
- D4 except the contract-protected edges
- Document validate_transition as legacy; no edge change
- Other

## Final answer

Full D4: adopt core's graph everywhere; move the two fixtures to force=true (or invalid/ with a forced valid twin); rewrite the force-exempt-by-contract section to match core (#6514); note the reduce_status_events behaviour change in COMPATIBILITY 11.5.0.

## Rationale

_(none)_

## Change log

- `2026-11-11T12:11:40.957478+00:00` - opened
- `2026-11-11T12:14:43.268268+00:00` - resolved (final_answer="Full D4: adopt core's graph everywhere; move the two fixtures to force=true (or invalid/ with a forced valid twin); rewrite the force-exempt-by-contract section to match core (#6514); note the reduce_status_events behaviour change in COMPATIBILITY 11.5.0.")
