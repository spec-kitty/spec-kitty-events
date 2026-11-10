# Decision Moment `01M70VBEVRGGBE4BJGMFSFDYH2`

- **Mission:** `write-service-cutover-readiness-01M70THX`
- **Origin flow:** `plan`
- **Slot key:** `plan.lifecycle.drain-budget`
- **Input key:** `drain_budget_fix`
- **Status:** `resolved`
- **Created:** `2026-11-09T19:07:55.128912+00:00`
- **Resolved:** `2026-11-09T19:07:56.460487+00:00`
- **Opened by:** `cli`
- **Other answer:** `false`

## Question

D10: #236 AC 5 (drain budget > write budget) conflicts with LAUNCHER.md ('at most 5 s') and core STOP_TIMEOUT_S = 10.0. How does M1 stop a queued write from losing its answer at shutdown?

## Options

_(none)_

## Final answer

Orchestrator ruling D10, confirmed by the maintainer 2026-11-09: drain-aware wait. Keep the 5 s drain and the launcher contract unchanged (LAUNCHER.md:50-57; core launcher.py:83 STOP_TIMEOUT_S = 10.0; LauncherContractProcessTest NFR-003). Once draining starts, a write (or repair) still waiting for the turn or the Mission lock answers 503 SERVICE_DRAINING without writing; one already holding the lock finishes. #236 AC 5 is rewritten to match.

## Rationale

_(none)_

## Change log

- `2026-11-09T19:07:55.128912+00:00` - opened
- `2026-11-09T19:07:56.460487+00:00` - resolved (final_answer="Orchestrator ruling D10, confirmed by the maintainer 2026-11-09: drain-aware wait. Keep the 5 s drain and the launcher contract unchanged (LAUNCHER.md:50-57; core launcher.py:83 STOP_TIMEOUT_S = 10.0; LauncherContractProcessTest NFR-003). Once draining starts, a write (or repair) still waiting for the turn or the Mission lock answers 503 SERVICE_DRAINING without writing; one already holding the lock finishes. #236 AC 5 is rewritten to match.")
