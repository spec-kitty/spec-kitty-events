# Decision Moment `01M74ZYSR06CFAAN7FAK274HX6`

- **Mission:** `write-fold-encoder-parity-01M74ZY0`
- **Origin flow:** `specify`
- **Slot key:** `specify.assurance.ack-bytes-oracle`
- **Input key:** `ack_bytes_oracle`
- **Status:** `resolved`
- **Created:** `2026-11-11T09:45:20.896547+00:00`
- **Resolved:** `2026-11-11T11:20:27.755857+00:00`
- **Opened by:** `cli`
- **Other answer:** `true`

## Question

Where do the ack cases' expected appended bytes come from (#231 4.9)?

## Options

- Core generator records appended bytes in the parity corpus
- Compare with the Python reference encoder only
- Other

## Final answer

Extract the expected appended bytes from core's corpus/system and store them as test resources in spec-kitty-events; no hard coupling between the repositories (events owns the events system now).

## Rationale

_(none)_

## Change log

- `2026-11-11T09:45:20.896547+00:00` - opened
- `2026-11-11T11:20:27.755857+00:00` - resolved (final_answer="Extract the expected appended bytes from core's corpus/system and store them as test resources in spec-kitty-events; no hard coupling between the repositories (events owns the events system now).")
