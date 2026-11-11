---
work_package_id: WP03
title: Reference encoder parity (#231 4.1-4.5)
dependencies: []
requirement_refs:
- C-006
- FR-006
- FR-007
- FR-009
- FR-010
- NFR-001
- NFR-005
- SC-002
- SC-003
planning_base_branch: issue-237-write-fold-encoder-parity
merge_target_branch: issue-237-write-fold-encoder-parity
branch_strategy: Single-branch mission. Planning, base and merge target are issue-237-write-fold-encoder-parity; the finished branch goes to main through one PR.
subtasks:
- T011
- T012
- T014
- T015
- T016
history:
- timestamp: '2026-11-11T11:30:00Z'
  lane: planned
  agent: system
  action: Prompt generated via /spec-kitty.tasks
agent_profile: python-pedro
authoritative_surface: src/spec_kitty_events/status_log.py
create_intent:
- docs/changelog/unreleased/237-reference-encoder.md
execution_mode: code_change
owned_files:
- src/spec_kitty_events/status_log.py
- src/spec_kitty_events/_iso8601.py
- tests/test_status_log.py
- tests/test_iso8601_fromisoformat.py
- docs/changelog/unreleased/237-reference-encoder.md
role: implementer
tags: []
tracker_refs: []
---
# Work Package Prompt: WP03 — Reference encoder parity (#231 4.1–4.5)

## ⚡ Do This First: Load Agent Profile

Load your assigned profile before reading anything else: `/spk-charter-profile-load python-pedro`
(or `spec-kitty agent profile show python-pedro`). Adopt its identity, scope and boundaries.

## Objective

`spec_kitty_events.status_log` is the published byte model of core's status writer. Close five
squad findings so it is interpreter-independent, refuses every unencodable input with a typed
refusal, handles tuples like core, and is tested for no mutation everywhere.

## Context

- Spec: User Story 2, FR-006–FR-010, NFR-001, NFR-005, C-006. Research D-4, D-5.
- `src/spec_kitty_events/status_log.py` (132 lines): `_strip_pii_value` (:61-66) handles dict and
  list only; `encode_status_row` (:115-132) catches every `ValueError` from `json.dumps` as
  `NonFiniteNumber`; `__all__` (:25-31).
- `src/spec_kitty_events/_iso8601.py:411-423` `parse_iso8601_datetime`: 3.11+ uses native
  `datetime.fromisoformat(normalize_iso8601_shape(value))`; 3.10 uses `_parse_isoformat_311`.
- Squad findings (#231): on 3.11+ the native path writes different bytes from core for 15 of 60,000
  fuzzed forms on 3.11 and 31 on 3.13; 3.13 differs from 3.11 on `+00:00:00.000001`.
- `src/spec_kitty_events/__init__.py`: module docstring inventory lacks `status_log` (keep it
  un-re-exported, consistent with `total_order` and `producer_contract`).
- Tests: `tests/test_status_log.py`, `tests/test_iso8601_fromisoformat.py` (has
  `sys.version_info` skips at :67 and :80 that may become obsolete), packaged
  `src/spec_kitty_events/conformance/test_status_append.py` + `fixtures/status_append/` (the
  core-generated oracle; must stay green unchanged).
- Interpreters here: 3.11 (uv), 3.12, 3.13 system; 3.10 via `uv python install 3.10` if the network
  allows (try once; if refused, record it and rely on CI's 3.10 lane if one exists — check
  `.github/workflows/ci.yml`).

## Subtasks

### T011 — Tuples [P]
`_strip_pii_value`: recurse into `tuple` as core's sanitiser does. Check core's
`events/sanitizer.py` behaviour statically (at the pin) for whether a tuple stays a tuple or becomes
a list; either way `json.dumps` writes an array, so bytes are the same — return a list or tuple to
match core's sanitised value shape and say which in the docstring. Test:
`encode_status_row({"x": ({"hostname": "h"},)})` drops `hostname`; nested tuple-in-list-in-tuple;
empty tuple.

### T012 — Typed refusals
1. New exported classes in `status_log.py`, both subclassing `StatusRowRefused`:
   - `IntegerTooLarge` — an `int` whose decimal form exceeds the interpreter's int-to-str digit
     limit (`sys.get_int_max_str_digits()` where available; 3.10.x before 3.10.7 has no limit —
     check what the floor patch does and decide; document).
   - `NonStringKey` — a mapping key that is not `str`, at any depth (top level included).
2. Validate keys and ints in one recursive pre-pass (or in the strip pass) so `json.dumps` never
   raises `TypeError`; keep `NonFiniteNumber` only for `allow_nan=False`'s non-finite float.
   No bare `TypeError`/`ValueError` may escape `encode_status_row` or `sanitize_status_row` for any
   JSON-like input (dict/list/tuple/str/int/float/bool/None). Other unsupported types (e.g. a
   `set`) — decide: a third subclass or `StatusRowRefused` itself; document.
3. Update `__all__` and the module docstring's list of deliberate differences from core (core would
   leak `ValueError`/`TypeError` for these; they are unreachable from `json.loads`, so no reachable
   byte changes).
4. Tests: `{"a": 10**5000}` → `IntegerTooLarge` (not `NonFiniteNumber`); `{1: "x"}` and
   `{"a": {1: "x"}}` → `NonStringKey`; existing `test_status_refusals_are_value_errors` extended to
   the new classes.

### (T013 moved to WP04)
The package docstring bullet for `status_log` (#231 4.3) is done by WP04, which owns `__init__.py`.

### T014 — 3.11 grammar everywhere
1. `parse_iso8601_datetime` always calls `_parse_isoformat_311`. Remove the version branch; keep
   `normalize_iso8601_shape` only if something else still uses it (grep).
2. Update docstrings in `_iso8601.py`, `status_log.py` (`sanitize_status_row`, `encode_status_row`)
   so they say "the CPython 3.11 port on every interpreter".
3. Tests: `+00:00:00.000001` gives the same duration on every interpreter (assert a literal expected
   value computed for 3.11); remove now-obsolete `sys.version_info` skips in
   `test_iso8601_fromisoformat.py` so the port is exercised on every interpreter.
4. The committed `fromisoformat` vectors (`scripts/conformance/generate_fromisoformat_vectors.py`
   output) must still pass on every interpreter.

### T015 — No-mutation checks
Add `copy.deepcopy(row)` before and `assert row == snapshot` after on the session-pair tests
(`test_session_pair_*`, `test_mixed_naive_and_aware_*`, `test_incomplete_or_unparseable_*`) and
inside the hypothesis property (find the `@given` test). Include tuple inputs in at least one
no-mutation check.

### T016 — Cross-interpreter byte check + fragment
1. Run `tests/test_status_log.py`, `tests/test_iso8601_fromisoformat.py` and
   `uv run pytest --pyargs spec_kitty_events.conformance -k status_append` under 3.11 and 3.13
   (and 3.10 if installable): `uv run --python 3.13 pytest ...`. Also produce, once, a byte dump of
   `encode_status_row` over every `fixtures/status_append/valid` input on each interpreter and diff
   them (scratch script, not committed); record "0 differing bytes" (NFR-001) in the activity log.
2. Fragment `docs/changelog/unreleased/237-reference-encoder.md`, section `Changed` (or `Fixed`):
   tuples sanitised; `IntegerTooLarge` and `NonStringKey` added; timestamps use the 3.11 grammar
   on every interpreter, so 3.11+ output is now core-exact for malformed session timestamps; issue
   refs `(#237; #231 items 4.1–4.5)`.

## Definition of Done
- Listed tests green on 3.11 and 3.13 (3.10 if available); counts recorded.
- Packaged conformance gate green: `uv run pytest --pyargs spec_kitty_events.conformance`.
- `ruff check . && ruff format --check .`; `uv run mypy --strict src/spec_kitty_events/status_log.py src/spec_kitty_events/_iso8601.py` clean.
- C-006: no fixture in `status_append/valid` changes bytes.

## Risks and reviewer guidance
- Reviewer: run the new refusal tests; check that `NonFiniteNumber` is raised only for floats;
  confirm no 3.11 byte changes via the status_append oracle.
- `sys.set_int_max_str_digits` is process-global; tests must not change it without restoring.

## Do not
- Do not edit `status.py` (WP04) or the parity corpus (WP05).
- Implementation command: `spec-kitty agent action implement WP03 --agent <name> --mission write-fold-encoder-parity-01M74ZY0`
