---
section: Compatibility
---

- `ledger_append` gains one invalid document, `ledger_append/invalid/invalid_payload_existing_ledger.json`
  (manifest id `ledger-append-invalid-payload-existing-ledger`, `min_version` `11.5.0`): the
  `invalid_payload` input appended over the well-formed ledger `{"prior": 1}\n` is refused with
  `LedgerValidationError`, and the ledger is left byte-identical. Additive: no validator, schema
  or accept/reject boundary moves, and the Python reference already behaves this way. A second
  binding that opens, truncates or rewrites the ledger before it validates now fails this
  document; before, every validation refusal started from an absent file. See `COMPATIBILITY.md` (#279).
