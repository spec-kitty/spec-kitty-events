# Decision Moment `01M70TJENKYFJP8TAX04SQR3ZT`

- **Mission:** `write-service-cutover-readiness-01M70THX`
- **Origin flow:** `specify`
- **Slot key:** `specify.evidence.wire-field`
- **Input key:** `evidence_wire_field`
- **Status:** `resolved`
- **Created:** `2026-11-09T18:54:15.731446+00:00`
- **Resolved:** `2026-11-09T18:54:17.102521+00:00`
- **Opened by:** `cli`
- **Other answer:** `false`

## Question

D3: how is the missing-evidence half of implementation_evidence_required (#224) made reachable?

## Options

_(none)_

## Final answer

Maintainer ruling D3 (2026-11-09): add the optional implementationEvidencePresent wire field. Core #6195 must document rolling-upgrade behaviour, since an older service answers 400 to the unknown field; comment that on #6195.

## Rationale

_(none)_

## Change log

- `2026-11-09T18:54:15.731446+00:00` - opened
- `2026-11-09T18:54:17.102521+00:00` - resolved (final_answer="Maintainer ruling D3 (2026-11-09): add the optional implementationEvidencePresent wire field. Core #6195 must document rolling-upgrade behaviour, since an older service answers 400 to the unknown field; comment that on #6195.")
