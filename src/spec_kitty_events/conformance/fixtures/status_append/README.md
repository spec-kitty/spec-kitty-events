# `status_append` — status-row sanitizer and encoder fixtures (#160)

These documents are the normative byte contract for one row of
`status.events.jsonl`. The Python reference is `spec_kitty_events.status_log`;
the Java encoder is held to the same documents. Every case is registered in
`../manifest.json` with `"fixture_type": "status_append"` and
`"event_type": "StatusRow"`.

Expected lines are generated, never hand-written, by
`scripts/conformance/generate_status_append.py` from core commit
`512567eee71e31bb39f580c4f7acf8b865e9d1b2`. The oracle interpreter is pinned to
Python 3.11 (the supplied checkout runs 3.11.15); the generator refuses another
major.minor version. Its `--check` mode diffs every committed case without
writing.

## Document keys

| Key | Required | Meaning |
|---|---|---|
| `input_json` | yes | The row as raw JSON text. Each binding parses this itself (`json.loads` in Python, `PythonJson` in Java), preserving `1.0`, `-0.0`, `1e400` and arbitrary-size integers without a fixture parser normalizing them. |
| `expected_line` | valid only | Exact sanitized ASCII output, including its final LF. |
| `expected_error` | invalid only | Protocol refusal class: `NonFiniteNumber` or `SessionTimesNotSubtractable`. |
| `core_behaviour` | invalid only | What pinned core actually did: `writes NaN`, `writes Infinity`, or `raises TypeError`. This records the deliberate protocol divergence. |
| `covers` | yes | Non-empty list of coverage classes from the list below. |

Valid rows encode as `json.dumps(sanitize_status_row(row), sort_keys=True,
ensure_ascii=True, separators=(", ", ": "), allow_nan=False) + "\n"`.
Non-finite numbers anywhere in the sanitized value are refused. A pair of
parseable top-level session timestamps with mixed naive/awareness is refused;
both parseable timestamps otherwise become an integer `session_duration_s`.

## Timestamp subset (R12)

Every timestamp in a fixture is in the shared subset that the package parses
identically on Python 3.10 and 3.11, and WP03's Java `PyIsoDateTime` parses
identically as well. On Python 3.10 the package uses
`_iso8601._parse_isoformat_311`, a port of CPython 3.11's `fromisoformat`, so the
encoder accepts exactly what core (Python 3.11) accepts and rejects exactly what
it rejects. That closes the forms 3.10's own parser could not handle: basic-format
dates (`20260101`), ISO week dates (`2026-W01-1`, `2026-W01`), an hour-only offset
(`+05`), an offset with seconds or a fraction (`+05:30:15.5`), reduced precision
and exotic separators now produce core's `session_duration_s`; non-ASCII digits
and the other malformed forms core rejects stay unparseable. The fixture family
nevertheless stays inside the conservative shared subset so every binding
consumes the same documents; forms outside that subset belong only in WP03's
`PyIsoDateTime` vectors, never in this family.

## Coverage classes

- `transition_row_plain`
- `annotation_row_plain`
- `pii_top_level`
- `pii_nested_and_in_lists`
- `session_pair_both_parse`
- `session_pair_negative`
- `session_pair_long_span`
- `session_pair_overwrites_duration`
- `session_pair_one_missing_keeps_duration`
- `session_pair_unparseable`
- `session_pair_zulu_and_basic`
- `session_pair_both_naive`
- `nested_session_keys_untouched`
- `escaping_non_ascii_and_astral`
- `escaping_control_characters`
- `key_order_code_points`
- `numbers_float_repr`
- `numbers_big_integers`
- `nulls_bools_empty_containers`
- `session_value_non_string`
- `non_finite_number`
- `session_pair_mixed_awareness`
