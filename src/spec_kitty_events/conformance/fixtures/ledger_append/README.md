# `ledger_append` — local-ledger write-contract fixtures (#118, 10.8.0; 11.0.0 glossary shapes)

These documents are the normative contract for appending one event to a local JSONL
ledger. The Python reference is `spec_kitty_events.persistence.append_event`. Every
other binding (for example a Java write-side sidecar) must reproduce them exactly.
Each document is registered in `../manifest.json` with `"fixture_type": "ledger_append"`.

## Document keys

| Key | Required | Meaning |
|---|---|---|
| `input_kind` | yes | `event`, `dict` or `model` (see below). |
| `input` | yes | The JSON fields the input is built from. |
| `model` | `model` kind only | Name of the payload-model class exported by the package (e.g. `GlossaryScopeActivatedPayload`). Its event type is the one the conformance model map registers it under. |
| `existing_ledger` | no | Exact text already in the ledger file before the append. If absent, the file does not exist. |
| `nan_field` | no | Top-level key of `input` whose value the runner replaces with an IEEE-754 NaN before appending. (JSON cannot hold NaN.) |
| `expected_line` | valid only | The exact ASCII line the append must add, including the trailing `\n`. |
| `expected_error` | invalid only | Refusal class: `LedgerValidationError` or `LedgerTornTailError`. Both are `LedgerAppendError`s. |

## Input kinds → record

- `event`: a canonical `Event` envelope. The record is the envelope's JSON form. Both the envelope and its `payload` (against the envelope's `event_type`) are validated. Since 11.0.0 `lamport_clock` is optional: an unclocked envelope is appended with `"lamport_clock": null` in the record.
- `dict`: a flat record. The record is `input` exactly as given, with no keys added or removed. It is validated as the payload of `input.event_type`. The identity keys `event_type`, `event_id` and `timestamp` (the last two optional strings) are written but not validated as payload fields, unless that payload model itself declares them. An `input` that has a `payload` object and an `aggregate_id` is treated as an `event`.
- `model`: an instance of the named payload model. The record is `{"event_type": <its type>, ...model fields}`. If the named class is not a registered payload model (the fixture uses the non-existent `NotARegisteredModel`), the binding passes an object of a model type that is not registered, and the append must refuse it.

## Byte rule

`line = json.dumps(record, sort_keys=True, ensure_ascii=True, allow_nan=False) + "\n"`:

- object keys are sorted recursively;
- separators are `", "` and `": "`;
- every non-ASCII code point is written as `\uXXXX` (a UTF-16 surrogate pair above the BMP);
- floats use the shortest round-trip form (`1e-05`, `0.75`).

## Refusals

A refusal raises before anything is written. The ledger must end up byte-identical to
`existing_ledger`, or still absent. A non-empty ledger whose last byte is not `\n` is
refused (`LedgerTornTailError`).
