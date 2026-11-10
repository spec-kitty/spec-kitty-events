# Decision Moment `01M723SBFP2Y9MTA2JYF654RGQ`

- **Mission:** `write-service-cutover-readiness-01M70THX`
- **Origin flow:** `plan`
- **Slot key:** `plan.contract.breaking-check-provisional`
- **Input key:** `breaking_check_provisional`
- **Status:** `resolved`
- **Created:** `2026-11-10T06:54:33.462391+00:00`
- **Resolved:** `2026-11-10T06:54:34.732396+00:00`
- **Opened by:** `cli`
- **Other answer:** `false`

## Question

D11: #236 AC 6 (breaking_check clean) conflicts with x-provisional on WriteOperation (#231 4.12): stripping the provisional schema removes the inlined TransitionUnit.operation and reports 2 false breaks. How is it resolved?

## Options

_(none)_

## Final answer

Maintainer ruling D11 (2026-11-10): fix breaking_check. Keep x-provisional on WriteOperation. contracts/tools/breaking_check.py must not delete a stable property that only $refs a provisional schema when stripping provisional elements: it still compares that property's presence and requiredness, and skips only the provisional schema's own body and enum. Tool tests: stable property $ref'ing a provisional schema is kept (no false break); removing that property is still BREAKING; changing the provisional enum is not reported. AC 6 is then met literally (exit 0, no breaks). Recorded on #236; tooling change noted on #151.

## Rationale

_(none)_

## Change log

- `2026-11-10T06:54:33.462391+00:00` - opened
- `2026-11-10T06:54:34.732396+00:00` - resolved (final_answer="Maintainer ruling D11 (2026-11-10): fix breaking_check. Keep x-provisional on WriteOperation. contracts/tools/breaking_check.py must not delete a stable property that only $refs a provisional schema when stripping provisional elements: it still compares that property's presence and requiredness, and skips only the provisional schema's own body and enum. Tool tests: stable property $ref'ing a provisional schema is kept (no false break); removing that property is still BREAKING; changing the provisional enum is not reported. AC 6 is then met literally (exit 0, no breaks). Recorded on #236; tooling change noted on #151.")
