---
affected_files: []
cycle_number: 1
mission_slug: write-fold-encoder-parity-01M74ZY0
reproduction_command:
reviewed_at: '2026-11-11T12:06:45Z'
reviewer_agent: claude-reviewer
wp_id: WP03
---

# WP03 review feedback (cycle 1) — claude-reviewer

Verdict: **changes requested** — one blocking defect (C-006 / FR-007).

## [MAJOR] `IntegerTooLarge` refuses a negative integer that core writes (byte change on a json.loads-reachable row)

`src/spec_kitty_events/status_log.py:110-114` (`_check_int`): `digits = len(str(value))` counts the
minus sign, so a negative integer with exactly `limit` digits (4300 by default) gives
`digits == 4301 > 4300` and raises `IntegerTooLarge`. CPython's int-to-str limit counts digits, not
the sign: `str(-int("9"*4300))` succeeds, `json.loads` produces this value, and core's
`json.dumps` writes it.

Reproduction (3.11):

```python
import json
from spec_kitty_events.status_log import encode_status_row

row = json.loads('{"a": -' + "9" * 4300 + "}")
json.dumps(row)  # fine (what core writes)
encode_status_row(row)  # IntegerTooLarge  <- wrong
```

This changes the result of a row the encoder accepted before the change (and core accepts), which
C-006 forbids, and it contradicts the module docstring's claim that `IntegerTooLarge` is not
reachable from `json.loads` output.

Fix: when `str()` succeeds, the interpreter has already accepted the digit count, so the length
test is only needed for the no-limit fallback (3.10 before 3.10.7). Count digits without the sign,
for example `len(str(abs(value)))` (or skip the comparison when `sys.get_int_max_str_digits`
exists). Add a regression test next to `test_integer_at_the_digit_limit_is_accepted`:
`-(10**4300 - 1)` (4300 nines, negative) must encode, and `-(10**4300)` (4301 digits) must refuse.

## Non-blocking (fix while you are in there)

1. `docs/changelog/unreleased/237-reference-encoder.md`: the #231 item numbers are off. Per the spec,
   item 4.4 is the 3.11 grammar (FR-009) and item 4.5 is the no-mutation checks (FR-010). The
   fragment cites "4.2, 4.4" for the typed refusals and "4.5" for the timestamp grammar. Use 4.2
   for the refusals and 4.4 for the grammar.
2. `tests/test_status_log.py` (hypothesis property, `assert b"hostname" not in encoded`): `st.text(max_size=8)`
   can generate the value `"hostname"`, which would be a false failure. Assert on the sanitised
   structure (use `_assert_key_absent`) instead of a substring of the bytes.

## Checked and fine

- Tuples: core's `_strip_pii_recursive` at `512567ee` returns `tuple(...)`; the port matches and the docstring says so.
- Validating on the sanitised value matches core (it dumps the sanitised value), so an oversized int
  in a dropped session field or a PII value is accepted, as in core.
- Refusing non-string keys diverges from core (core's `json.dumps` would coerce them), but the spec
  requires it (FR-007), `json.loads` cannot produce such keys, and the module docstring states it.
- The `fromisoformat` port gave 0 differences from native 3.11 `datetime.fromisoformat` (which is what core's `kernel.clock.parse_iso` calls) on 200,000 fuzzed forms.
  `+00:00:00.000001` gives offset 0 with the port on 3.10, 3.11 and 3.13; native 3.13 gives 1 µs, so the port is needed.
- The status_append valid fixtures gave the same byte hash on 3.10.20, 3.11.15 and 3.13.18, and every row matched its `expected_line`.
