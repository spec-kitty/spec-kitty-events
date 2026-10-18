# Contract: local ledger append (v10.8.0)

Normative bytes live in `src/spec_kitty_events/conformance/fixtures/ledger_append/`. This page only explains them.

1. Input → record (see data-model.md). Unknown types and invalid records are refused.
2. line = `json.dumps(record, sort_keys=True, ensure_ascii=True, allow_nan=False) + "\n"` (ASCII).
3. Append it with a single write on an O_APPEND descriptor. Refuse if the existing file's last byte is not LF.
4. Any refusal leaves the file byte-identical.
