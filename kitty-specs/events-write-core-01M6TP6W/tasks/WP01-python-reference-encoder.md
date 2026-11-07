---
work_package_id: WP01
title: Python reference encoder and the status_append fixtures
dependencies: []
requirement_refs:
- FR-001
- FR-002
- FR-003
- FR-028
- NFR-003
- NFR-004
- SC-001
- C-001
planning_base_branch: kitty/mission-events-write-core-01M6TP6W
merge_target_branch: main
branch_strategy: Planning artifacts for this mission were generated on kitty/mission-events-write-core-01M6TP6W. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into main unless the human explicitly redirects the landing branch.
subtasks:
- T001
- T002
- T003
- T004
- T005
- T006
history:
- timestamp: '2026-11-07T10:30:00Z'
  lane: planned
  agent: system
  action: Prompt generated via /spec-kitty.tasks
agent_profile: python-pedro
authoritative_surface: src/spec_kitty_events/status_log.py
create_intent:
- src/spec_kitty_events/status_log.py
- src/spec_kitty_events/conformance/test_status_append.py
- src/spec_kitty_events/conformance/fixtures/status_append/README.md
- src/spec_kitty_events/conformance/fixtures/status_append/valid/
- src/spec_kitty_events/conformance/fixtures/status_append/invalid/
- scripts/conformance/generate_status_append.py
- tests/test_status_log.py
- tests/test_generate_status_append.py
execution_mode: code_change
owned_files:
- src/spec_kitty_events/status_log.py
- src/spec_kitty_events/conformance/loader.py
- src/spec_kitty_events/conformance/fixtures/manifest.json
- src/spec_kitty_events/conformance/fixtures/status_append/**
- src/spec_kitty_events/conformance/test_status_append.py
- scripts/conformance/generate_status_append.py
- tests/test_status_log.py
- tests/test_generate_status_append.py
- pyproject.toml
role: implementer
tags: []
tracker_refs: []
---
# Work Package Prompt: WP01 - Python reference encoder and the `status_append` fixtures

## ⚡ Do This First: Load Agent Profile

Load your assigned profile before reading anything else: `/spk-charter-profile-load python-pedro`
(or `spec-kitty agent profile show python-pedro`). Adopt its identity, scope and boundaries.

## Objective

Give this package a public, typed reference of the one byte rule core uses for status rows, and a
conformance family that pins those bytes for every binding:

- `spec_kitty_events.status_log` (new, public): `sanitize_status_row(row)` and
  `encode_status_row(row)`. The encoder returns exactly what core writes for one row of
  `status.events.jsonl`: `json.dumps(sanitize_event_for_log(row), sort_keys=True)` plus one LF.
- A `status_append` fixture family beside `ledger_append`: valid cases carry the input row and
  the byte-exact expected line; invalid cases carry the input row and the refusal the protocol
  answers.
- The packaged runner `conformance/test_status_append.py`, so
  `uv run pytest --pyargs spec_kitty_events.conformance` holds this package to the fixtures.
- A test-only generator that re-derives every expected line by running core's own encoder at the
  pin, and a `--check` mode that diffs. WP04 wires the CI job that runs it; WP05 holds the Java
  encoder to the same fixtures.

## Context

- Spec: FR-001 (encoder input is the `json.loads` value; sorted keys, `, ` and `: `, ASCII
  escaping with surrogate pairs, Python float repr, one LF), FR-002 (sanitizer), FR-003 (the
  family, generator, CI diff, pinned Python version), FR-028 (non-finite and mixed naive/aware →
  refused, pinned by invalid fixtures), NFR-003 (≥ 12 valid, ≥ 4 invalid), NFR-004 (mypy
  --strict, ruff, complexity ≤ 15), C-001 (no dependency on core outside the test-only generator).
- Research: §4.1 (core's algorithm: `store.py:293-300`, `sanitizer.py:24-166`, the
  `TypeError` outside the parse `try` for a mixed pair), §4.2 (the dump rules the bytes must
  follow), §8.4 (`ledger_append` loader, manifest entries and package data to model on),
  §11.9 (non-finite and mixed pair are deliberate parity exceptions), §12 (`fromisoformat`
  depends on the Python version: FR-003 pins the oracle's version).
- Existing seams:
  - `src/spec_kitty_events/persistence.py:185-196`: `serialize_record` spells the same dump
    defaults but sets `allow_nan=False`. It is an oracle for finite values only. Do not import
    it from `status_log.py` (different input model); reuse is not required.
  - `src/spec_kitty_events/_iso8601.py`: `normalize_iso8601_shape` reshapes a timestamp so
    `fromisoformat` behaves the same on every supported interpreter (3.10 included). Use it,
    privately, so the 3.10 runtime reproduces core's 3.11+ parse. Verify each case against the
    generator; do not assume the reshape covers every form core accepts.
  - `src/spec_kitty_events/conformance/loader.py:17-56` (`_VALID_CATEGORIES`,
    `_SPECIAL_FIXTURE_TYPES`) and `:141-159` (`load_ledger_append_cases`): the pattern for a
    special, non-envelope family.
  - `src/spec_kitty_events/conformance/fixtures/manifest.json`: the `ledger_append` entries carry
    `id`, `path`, `expected_result`, `event_type`, `notes`, `min_version` and
    `fixture_type: "ledger_append"`. Model the new entries on them.
  - `src/spec_kitty_events/conformance/test_ledger_append.py:46-67`: the packaged runner pattern.
  - `pyproject.toml:109-111`: the `ledger_append` package-data lines.
- Core at the pin (`service/core-pin.json`, `512567ee`): `src/specify_cli/status/store.py`
  (`_serialize` / `serialize_event_line`), `src/specify_cli/events/sanitizer.py`
  (`sanitize_event_for_log`), `src/kernel/clock.py:233-237` (the parse helper). Read them; port
  behaviour, never import them from packaged code.

## Branch Strategy

Planning happens on `kitty/mission-events-write-core-01M6TP6W` in an owned checkout
(`single_branch` topology). The mission lands as one PR to `main` from `issue-160-write-core`;
the planning base and merge target recorded here are `main`.
`spec-kitty agent action implement WP01 --agent <name> --mission events-write-core-01M6TP6W`

## Ground rules

- ATDD, red first: write each subtask's acceptance tests before the code and see them fail for
  the right reason (an `ImportError` is not the right reason once the module exists; a wrong byte
  is).
- Typed: `uv run mypy --strict src/spec_kitty_events` clean. Lint:
  `uv run ruff check . && uv run ruff format --check .` clean (the generator under `scripts/` is
  linted too).
- Cyclomatic complexity ≤ 15 per function (NFR-004). Split the encoder by value kind.
- No dependency on core. Only `scripts/conformance/generate_status_append.py` imports core, and
  only from the checkout `SPEC_KITTY_CORE_CHECKOUT` names. No packaged module and no test under
  `src/` or `tests/` imports core.
- No new runtime dependency (`pyproject.toml` `dependencies` unchanged).
- Any workflow you touch keeps every action pinned by a 40-hex SHA (you should touch none; WP04
  owns `service.yml`).
- Run `uv run pytest --pyargs spec_kitty_events.conformance` whenever a fixture changes.
- Do not run `make test-full`. Run what you wrote plus your blast radius.
- Record the test commands you ran and their pass counts in your final commit message.

## Subtasks

### T001 `sanitize_status_row`

**Purpose**: port `sanitize_event_for_log` at the pin, value for value.

**Files**: `src/spec_kitty_events/status_log.py` (new); `tests/test_status_log.py` (new).

**Steps**:
1. Write the tests first (table-driven, `pytest.mark.parametrize`): each PII key
   (`machine_name`, `hostname`, `workspace_path`, `developer_name`, `developer_email`) removed at
   the top level, inside a nested dict, inside a dict inside a list, inside a list inside a list;
   other keys preserved in place; the input not mutated (deep-compare before and after).
2. Session pair (top level only):
   - both present and both parse → both removed, `session_duration_s = int((ended -
     started).total_seconds())` (truncation toward zero; a negative duration stays negative; it
     overwrites an existing `session_duration_s`);
   - one missing, or either unparseable, or either `None` → both keys still removed, no duration
     computed, an existing `session_duration_s` survives;
   - nested keys named `session_started_at` / `session_ended_at` untouched.
3. Mixed naive/aware pair (both parse, exactly one carries an offset): core raises an uncaught
   `TypeError` at subtraction. The reference raises `SessionTimesNotSubtractable` (a subclass of
   `StatusRowRefused`, itself a `ValueError`). This is the protocol's refusal (FR-028).
4. Parsing: reproduce `datetime.fromisoformat` of core's pinned interpreter (3.11+ grammar: `Z`,
   basic format, fractional digits of any length, a space separator, date-only). Route through
   `_iso8601.normalize_iso8601_shape` first so 3.10 agrees, then `datetime.fromisoformat`.
   Non-string values: follow core's behaviour (read `sanitizer.py:89-118`: does the `try`
   catch `TypeError` as well as `ValueError`?). A fixture pins it (T004).
5. Circular containers (`sanitizer.py:43-68`) cannot arrive from `json.loads`; do not port the
   seen-set. Note this in the docstring.

**Validation**:
- [ ] Every branch of step 2 has a test; one test proves no mutation.
- [ ] `SessionTimesNotSubtractable` is raised for both naive-start/aware-end and
      aware-start/naive-end.

**Edge cases**: an empty dict; a list at the top level (rejected: a status row is an object,
`TypeError`/`StatusRowRefused`, choose and test); `session_duration_s` present with only one
timestamp (survives); a timestamp with a `-00:00` offset; fractional seconds `.5` and
`.1234567`.

### T002 `encode_status_row`

**Purpose**: the bytes core writes, plus one LF, refusing what the protocol refuses.

**Files**: `src/spec_kitty_events/status_log.py`; `tests/test_status_log.py`.

**Steps**:
1. Tests first: `encode_status_row(row) -> bytes` equals
   `(json.dumps(sanitize_status_row(row), sort_keys=True) + "\n").encode("ascii")` for finite
   inputs. Pin the spelled-out rules separately so the test does not just restate the
   implementation: separators `, ` and `: `; keys in code-point order at every depth (a key with
   an astral character sorts after a BMP key); `é` and `😀` escapes; `\b \f \n
   \r \t` short escapes and `\u001f` for the rest of C0; `/` unescaped; `1.0` stays `1.0`;
   `-0.0` stays `-0.0`; `1e-05`, `0.0001`, `1e+16`, `1000000000000000.0`; integers of any size
   stay integers; `True`/`False`/`None` → `true`/`false`/`null`.
2. Non-finite: any `float('nan')`, `inf` or `-inf` anywhere in the sanitized value raises
   `NonFiniteNumber` (a `StatusRowRefused`). Core would write `NaN`/`Infinity`; the protocol
   refuses (research §11.9). Use `allow_nan=False` and translate the `ValueError`, or walk the
   value first; either way the exception type is yours, not `ValueError` bare.
3. Implementation: `json.dumps(sanitized, sort_keys=True, ensure_ascii=True,
   separators=(", ", ": "), allow_nan=False)`. Python's `sort_keys` sorts `str` by code point,
   which is the rule.
4. Export: define `__all__ = ["sanitize_status_row", "encode_status_row", "StatusRowRefused",
   "NonFiniteNumber", "SessionTimesNotSubtractable"]`. The module is public by name; do not edit
   `spec_kitty_events/__init__.py`.
5. Module docstring: cite core's `store._serialize` and `sanitize_event_for_log` at the pin, the
   two deliberate parity exceptions, and that the input is the value `json.loads` produces from
   the wire (so `1` and `1.0` differ).

**Validation**:
- [ ] `uv run mypy --strict src/spec_kitty_events` clean; public signatures use
      `Mapping[str, Any]` in, `dict[str, Any]` / `bytes` out.
- [ ] Hypothesis property (optional but recommended, hypothesis is a dev dependency): for any
      JSON-finite value tree without PII keys and without session keys,
      `encode_status_row(v) == (json.dumps(v, sort_keys=True) + "\n").encode()`.

### T003 `status_append` family scaffolding

**Purpose**: register the family the way `ledger_append` is registered, and ship both new
families' files in the wheel.

**Files**: `src/spec_kitty_events/conformance/loader.py`,
`src/spec_kitty_events/conformance/fixtures/manifest.json`,
`src/spec_kitty_events/conformance/fixtures/status_append/README.md` (new), `pyproject.toml`.

**Steps**:
1. `loader.py`: add `"status_append"` to `_VALID_CATEGORIES` and to `_SPECIAL_FIXTURE_TYPES`;
   add `load_status_append_cases() -> List[Dict[str, Any]]`, modelled on
   `load_ledger_append_cases` (document plus manifest `id` and `expected_result`).
2. Document shape (write it in the README first):
   - `input_json` (string): the row as **raw JSON text**. Both bindings parse this text
     themselves (`json.loads` in Python, `PythonJson` in Java), so `1.0`, `-0.0`, `1e400` and big
     integers survive exactly; a fixture file's own JSON parser never normalizes them.
   - `expected_line` (string, valid only): the exact ASCII line including its LF.
   - `expected_error` (string, invalid only): `NonFiniteNumber` or
     `SessionTimesNotSubtractable` (the Python class names; WP05 maps them to Java exceptions).
   - `core_behaviour` (string, invalid only): what core does with the same input at the pin
     (`writes NaN`, `raises TypeError`), recorded by the generator as evidence.
   - `covers` (list of strings): the NFR-003 classes the case covers (see T004), so coverage is
     machine-checked.
3. `manifest.json`: one entry per case, `fixture_type: "status_append"`, `event_type:
   "StatusRow"`, `min_version` set to the next package version (read `pyproject.toml`), `notes`
   one sentence.
4. `pyproject.toml` package data: add
   `conformance/fixtures/status_append/README.md`, `.../status_append/valid/*.json`,
   `.../status_append/invalid/*.json`, **and** the parity corpus's
   `conformance/fixtures/status_transition_parity/README.md`,
   `.../status_transition_parity/*.json` (the case schema and the unreachable-reason record),
   `.../status_transition_parity/cases/*.json` (WP04 creates those files; you own
   `pyproject.toml`, so the globs are added once, here).

**Validation**:
- [ ] `load_fixtures("status_append")` returns `[]` (special type skipped) and does not raise.
- [ ] `load_status_append_cases()` returns every case with `id` and `expected_result`.
- [ ] `uv build` (or `python -m build --wheel`) then listing the wheel shows the new files.

### T004 The fixture cases

**Purpose**: NFR-003 coverage, every expected line produced by core (T006), never by hand.

**Files**: `src/spec_kitty_events/conformance/fixtures/status_append/valid/*.json`,
`.../invalid/*.json`, manifest entries.

**Valid cases (≥ 12; one class each at least)**:
1. `transition_row_plain`: a realistic lane row (the shape of
   `contracts/mission-write/examples/TransitionUnit.claim.yaml`).
2. `annotation_row_plain`: `kind: annotation` with a nested `delta`.
3. `pii_top_level`: every PII key at the top level.
4. `pii_nested_and_in_lists`: PII keys inside a nested object and inside objects inside lists.
5. `session_pair_both_parse`: aware pair, positive fractional duration (truncation).
6. `session_pair_negative`: ended before started (negative, truncated toward zero).
7. `session_pair_overwrites_duration`: both parse and an existing `session_duration_s`.
8. `session_pair_one_missing_keeps_duration`: only one timestamp, existing duration survives.
9. `session_pair_unparseable`: one malformed timestamp, both removed, no duration.
10. `session_pair_zulu_and_basic`: `Z` suffix and basic format (pins the 3.11+ grammar).
11. `session_pair_both_naive`: two naive timestamps (subtractable).
12. `nested_session_keys_untouched`: the names inside a nested object.
13. `escaping_non_ascii_and_astral`: `é`, an em dash (U+2014), `😀` (surrogate pair).
14. `escaping_control_characters`: `\b \f \n \r \t`, `\u0000`, `\u001f`, DEL `\u007f`
    (Python escapes everything outside space..tilde), `"`, `\\`, `/`, and a lone surrogate.
15. `key_order_code_points`: keys whose UTF-16 order differs from code-point order.
16. `numbers_float_repr`: `1.0`, `-0.0`, `0.1`, `1e-05`, `0.0001`, `1e+16`,
    `1000000000000000.0`, `5e-324`, `1.7976931348623157e+308`.
17. `numbers_big_integers`: `2**64`, `-2**70`, `0`.
18. `nulls_bools_empty_containers`: `null`, `true`, `false`, `{}`, `[]`.
19. `session_value_non_string`: a number as `session_started_at` (pins core's behaviour).

**Invalid cases (≥ 4)**: `non_finite_nan` (`NaN` token in `input_json`), `non_finite_infinity`
(`Infinity`), `non_finite_overflow` (`1e400`, which `json.loads` reads as `inf`),
`session_pair_naive_then_aware`, `session_pair_aware_then_naive`.

**Validation**:
- [ ] Each `covers` list is non-empty; the union covers every class above.
- [ ] Every expected line was written by the generator (T006) and `--check` passes locally
      against a core checkout at the pin (or record in the commit that it ran in CI only).

### T005 Packaged runner and unit tests

**Files**: `src/spec_kitty_events/conformance/test_status_append.py` (new);
`tests/test_status_log.py`.

**Steps**:
1. Module docstring modelled on `test_ledger_append.py:1-7`: the second binding (WP05's Java
   runner) is held to the same documents.
2. `test_status_append_category_is_populated`: both `valid` and `invalid` present; counts
   ≥ 12 and ≥ 4 (NFR-003).
3. Parametrized over `load_status_append_cases()`: parse `input_json` with `json.loads`; for
   `valid`, `encode_status_row(row) == expected_line.encode("ascii")`; for `invalid`,
   `pytest.raises(getattr(status_log, expected_error))`.
4. A coverage test: the union of `covers` equals the class list in the README.
5. Run: `uv run pytest --pyargs spec_kitty_events.conformance -k status_append -q`, then the
   whole packaged gate.

**Validation**:
- [ ] `uv run pytest --pyargs spec_kitty_events.conformance` green (every family).
- [ ] `uv run pytest tests/test_status_log.py -q` green.

### T006 Test-only generator

**Purpose**: FR-003's "generated from core's encoder at the pin (Python version pinned) by a
test-only generator, and CI diffs them".

**Files**: `scripts/conformance/generate_status_append.py` (new);
`tests/test_generate_status_append.py` (new, tests the non-core parts only).

**Steps**:
1. Standard library only, plus core. It reads `SPEC_KITTY_CORE_CHECKOUT` (required; a clear
   error when unset), puts `<checkout>/src` on `sys.path`, and imports core's `store` serializer
   and `sanitize_event_for_log`. It must run under **core's** interpreter (CI:
   `core/.venv/bin/python`), because core's modules need core's dependencies.
2. Pin the interpreter: a constant `PINNED_PYTHON = (3, 12)` (match the `python-version` the
   `lock-interop` job gives `setup-uv`, `.github/workflows/service.yml` around line 536, and
   core's `.python-version` at the pin; if they disagree, stop and report). Refuse to run on any
   other `major.minor` and record the version in the README.
3. Case inputs live in the script as a table (id, `input_json`, `covers`, notes). For each case:
   `json.loads(input_json)`, then core's serializer. A valid case's `expected_line` is core's
   output plus `"\n"`. An invalid case records `core_behaviour` (what core did: wrote
   `NaN`, raised `TypeError`) and the protocol's `expected_error`.
4. Modes: default writes the case documents (the manifest entries are written once, by hand,
   and checked by T005); `--check`
   rewrites nothing, diffs each committed file against what it would write, prints the
   differing ids and exits 1 on any drift, 0 otherwise. A case file present on disk but absent
   from the table is drift too.
5. Determinism: `json.dumps(document, indent=2, ensure_ascii=False, sort_keys=False)` plus a
   trailing LF; stable key order inside each document (`id`-free: the manifest holds the id).
6. `tests/test_generate_status_append.py`: import the module by path (`importlib.util`) without
   core on `sys.path`; test the `--check` diff logic and the document writer on a temp directory
   with a stub serializer. The module must import without core (lazy core import inside `main`).

**Validation**:
- [ ] Locally, with a core checkout at the pin and core's venv:
      `SPEC_KITTY_CORE_CHECKOUT=/path/to/core /path/to/core/.venv/bin/python
      scripts/conformance/generate_status_append.py --check` exits 0.
- [ ] Without `SPEC_KITTY_CORE_CHECKOUT` the script exits 2 with a one-line message.
- [ ] `uv run pytest tests/test_generate_status_append.py -q` green without core.

## Test strategy

```bash
uv run pytest tests/test_status_log.py tests/test_generate_status_append.py -q
uv run pytest --pyargs spec_kitty_events.conformance -q
uv run mypy --strict src/spec_kitty_events
uv run ruff check . && uv run ruff format --check .
# Optional, with a core checkout at service/core-pin.json and core's venv:
SPEC_KITTY_CORE_CHECKOUT=/path/to/core /path/to/core/.venv/bin/python scripts/conformance/generate_status_append.py --check
```

Blast radius: `tests/test_fixture_determinism.py` and `tests/test_conformance_classes.py` read
`manifest.json`; run them (`uv run pytest tests/test_fixture_determinism.py
tests/test_conformance_classes.py -q`) after adding the entries.

## Definition of Done

- [ ] `spec_kitty_events.status_log` exists, typed, documented, with the five public names.
- [ ] ≥ 12 valid and ≥ 4 invalid `status_append` cases, each produced by the generator, each
      registered in `manifest.json`, shipped as package data.
- [ ] The packaged runner and the unit tests are green; mypy --strict and ruff are clean.
- [ ] `generate_status_append.py --check` passes against core at the pin (locally or as recorded
      for WP04's CI job).
- [ ] `pyproject.toml` ships both new families' globs.
- [ ] The final commit message lists the commands run and their pass counts.

## Risks

- **Interpreter drift**: Python 3.10's `fromisoformat` rejects `Z` and basic format; core's 3.12
  accepts them. The reference must agree with the generator's output on every supported
  interpreter (CI runs the package on 3.10+). The `session_pair_zulu_and_basic` case catches it.
- **Mis-modelled invalid cases**: core does not refuse NaN; the expected error is the protocol's.
  Keep `core_behaviour` honest so reviewers see the deliberate divergence.
- **Hand-edited expected lines**: never. `--check` is the gate.

## Reviewer guidance

- Diff three expected lines by eye against the rules of research §4.2 (spaces after `,` and `:`,
  lowercase `\u` escapes, surrogate pairs).
- Check that `status_log.py` imports nothing from core and nothing heavier than the standard
  library plus the private `_iso8601` helper.
- Check that `--check` fails on a deliberately edited expected line (ask for the evidence in the
  PR, or try it).
- Check that `covers` really reflects each case and that NFR-003's counts are asserted by a test,
  not just met.
