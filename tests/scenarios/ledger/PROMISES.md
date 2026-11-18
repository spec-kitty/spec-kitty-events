# `ledger/` promises

Each row maps a `@promise:` id to the guarantee its scenario asserts, the requirement that states
it, and the binding that runs it. The fixtures and their `README.md` stay the single source of
truth.

JVM binding: none

| Promise | Guarantee | Requirement | Binding |
|---------|-----------|-------------|---------|
| `PR-EVENTS-LEDGER-001` | An accepted append adds exactly one line, the `expected_line` its fixture pins, after the prior ledger bytes. | `persistence.append_event`; byte rule in `conformance/fixtures/ledger_append/README.md`; #118 (10.8.0), #120 (11.0.0 unclocked envelopes and glossary shapes) | python |
| `PR-EVENTS-LEDGER-002` | A refused append raises the refusal class its fixture pins before anything is written: the ledger stays byte-identical, or absent. | "Refusals" in `conformance/fixtures/ledger_append/README.md`; #118, #120 (old glossary shapes refused) | python |
| `PR-EVENTS-LEDGER-003` | An append to a non-empty ledger whose last byte is not LF is refused with `LedgerTornTailError`, and the ledger is left as it was. | `persistence.LedgerTornTailError`; #118 | python |
| `PR-EVENTS-LEDGER-004` | An append whose input carries NaN is refused with the class its fixture pins, and nothing is written. | Byte rule (`allow_nan=False`), `nan_field` in `conformance/fixtures/ledger_append/README.md`; #118 | python |
