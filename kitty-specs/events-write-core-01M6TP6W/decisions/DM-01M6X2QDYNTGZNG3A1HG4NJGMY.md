# Decision Moment `01M6X2QDYNTGZNG3A1HG4NJGMY`

- **Mission:** `events-write-core-01M6TP6W`
- **Origin flow:** `plan`
- **Slot key:** `plan.contract.unreachable-reasons`
- **Input key:** `unreachable_reasons`
- **Status:** `resolved`
- **Created:** `2026-11-08T07:59:49.717682+00:00`
- **Resolved:** `2026-11-08T07:59:51.044734+00:00`
- **Resolved by:** `maintainer`
- **Opened by:** `claude`
- **Other answer:** `false`

## Question

What happens to the guard reasons that cannot be produced over the wire?

## Options

_(none)_

## Final answer

Maintainer ruling 2026-11-08: keep unreachable.json as is; one issue per reason: workspace_context_required spec-kitty/spec-kitty-events#223, the evidence half of implementation_evidence_required #224.

## Rationale

Both are unreachable because core's pipeline fills the input before the guard runs (transition_pipeline.py:289-304; emit.py:541-548).

## Change log

- `2026-11-08T07:59:49.717682+00:00` - opened
- `2026-11-08T07:59:51.044734+00:00` - resolved (final_answer="Maintainer ruling 2026-11-08: keep unreachable.json as is; one issue per reason: workspace_context_required spec-kitty/spec-kitty-events#223, the evidence half of implementation_evidence_required #224.")
