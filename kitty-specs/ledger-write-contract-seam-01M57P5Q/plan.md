# Implementation Plan: Ledger-write contract seam

**Branch**: `issue-118-ledger-write-contract-seam` | **Date**: 2026-10-18 | **Spec**: [spec.md](spec.md)
**Mission**: `ledger-write-contract-seam-01M57P5Q` · **Issue**: #118

## Summary

Add `spec_kitty_events.persistence.append_event(event, path)`. It is the Python reference of the
local-ledger write contract: it normalises three input kinds (`Event`, flat `dict`, registered
payload model) to one JSON record, validates the record fail-closed against the existing conformance
validators, and appends one pinned-byte line with a single `O_APPEND` write. A new packaged fixture
category, `ledger_append`, carries the (input → exact line) and (input → refusal) pairs with
`min_version` `10.8.0`. A packaged runner drives that category under `pytest --pyargs
spec_kitty_events.conformance`. `LOCAL_ONLY_EVENT_TYPES` stays empty and is deprecated in the docs
only. The release is an additive minor bump to 10.8.0.

## Technical Context

**Language/Version**: Python ≥ 3.10 (`requires-python = ">=3.10"`), Pydantic v2
**Primary Dependencies**: none new; stdlib `json`, `os`, `pathlib`; the existing `spec_kitty_events.conformance.validators`
**Storage**: local append-only JSONL files (the ledger), regular local filesystem
**Testing**: pytest, hypothesis (property: no partial records), the packaged conformance runner, `mypy --strict`, ruff
**Target Platform**: POSIX and Windows (`os.O_APPEND` exists on both; `O_BINARY` is applied when present)
**Project Type**: single library package
**Performance Goals**: median under 5 ms per append for an event under 4 KiB (reported benchmark, not gated)
**Constraints**: additive only (C-002); no core/saas/tracker/runtime imports (C-001); fixtures are the source of truth (C-003)
**Scale/Scope**: one new module (~150 LOC), one fixture category (~14 fixtures), one packaged runner, docs and version bump

## Charter Check

| Charter rule | How this plan meets it |
|---|---|
| Testing: pytest, hypothesis, schema drift, fixture validation | Unit tests and a hypothesis no-partial-write property; the fixtures run in the packaged gate. No schema changes, so the drift check stays green unchanged. |
| Quality gates: pytest, committed schema generation, `mypy --strict` | All run before the PR is opened; no schemas are regenerated. |
| Review policy: deliberate compatibility review for fixture changes | New fixture category only (additive); a compatibility note goes in COMPATIBILITY.md and is called out in the PR body. |
| Fail-closed behaviour for live consumers | Every refusal path raises before any byte is written (FR-004 and FR-006; the property test is NFR-004). |
| Package boundary | `persistence.py` imports only the stdlib and this package. |
| Directive: keep docs synchronised | Adds a `docs/api/` write-contract page and the CHANGELOG/COMPATIBILITY entries. |
| Language Bindings: all bindings pass the same fixtures | The fixtures carry exact expected bytes and are language-neutral JSON. |

**Gate result:** PASS, with no violations to justify.

## Design

### Module `src/spec_kitty_events/persistence.py`

```
append_event(event: Event | Mapping[str, Any] | BaseModel, path: str | os.PathLike[str]) -> None
build_record(event) -> dict[str, Any]   # public: normalise + validate, no I/O (binding authors / tests)
serialize_record(record) -> bytes       # public: the pinned byte rule
class LedgerAppendError(SpecKittyEventsError)
class LedgerValidationError(LedgerAppendError, ValueError)
class LedgerTornTailError(LedgerAppendError)
```

1. **Normalise** (FR-002):
   - An `Event` becomes `model_dump(mode="json")`.
   - A `Mapping` becomes `dict(event)` with its keys kept.
   - A `BaseModel` is looked up in the payload-class → `event_type` reverse map, built once from
     `conformance.validators._EVENT_TYPE_TO_MODEL`. Only classes that map to exactly one type are
     included; an ambiguous class is refused. The record is `{"event_type": T, **model_dump(mode="json")}`.
   - Anything else raises `LedgerValidationError`.
2. **Type check**: the record must have a string `event_type` that is in the conformance model map.
   For an `Event` envelope (input kind `event`, or a dict that has a `payload` dict and
   `aggregate_id`), the envelope is validated as `"Event"` and the inner payload as its own
   `event_type`.
3. **Validate** (FR-003): call `validate_event(record_or_payload, T, strict=True)`. Any model or
   schema violation raises `LedgerValidationError`, listing the violations.
4. **JSON-native check** (FR-004): a recursive walk refuses values that are not JSON-native and
   non-finite floats.
5. **Serialise** (FR-005): `json.dumps(record, sort_keys=True, ensure_ascii=True, allow_nan=False).encode("ascii") + b"\n"`.
6. **Write** (FR-006):
   - Create the parent directories with `mkdir(parents=True, exist_ok=True)`.
   - Open with `os.open(path, O_WRONLY|O_APPEND|O_CREAT[|O_BINARY], 0o644)`.
   - If `fstat` reports a non-zero size, `pread`/`seek` the last byte; if it is not `\n`, raise
     `LedgerTornTailError`.
   - Write the whole line with one `os.write`. A short write raises `LedgerAppendError`
     (it cannot happen on a regular local file).
   - Close in `finally`.

All validation (steps 1–5) completes before the file is opened, so a refusal touches neither the
file nor its directory.

### Fixture category `ledger_append` (FR-007)

Each document under `conformance/fixtures/ledger_append/{valid,invalid}/`:

```json
{"input_kind": "event|dict|model", "model": "<PayloadClassName, model kind only>",
 "input": { ... JSON fields ... },
 "existing_ledger": "<optional exact prior file text, for torn-tail / multi-append cases>",
 "expected_line": "<exact ASCII line incl. trailing \\n>",      // valid only
 "expected_error": "LedgerValidationError|LedgerTornTailError"   // invalid only
}
```

Manifest entries carry `"fixture_type": "ledger_append"`; that value is added to the loader's
`_SPECIAL_FIXTURE_TYPES` and to `_VALID_CATEGORIES`, so the generic event gate skips them, in the
same way as `zeitgeist_attrs`. The runner, `conformance/test_ledger_append.py`, is shipped in the
wheel. For valid cases it writes into `tmp_path` and asserts the file bytes equal
`existing_ledger + expected_line`. For invalid cases it asserts the error type and that the file is
byte-unchanged.

Planned cases:

- **Valid:**
  - an `Event` envelope;
  - a flat glossary dict shaped like core's (`GlossaryScopeActivated`);
  - a `MissionReopened` dict and a `FollowUpRecorded` dict (FR-010);
  - a `GlossarySenseUpdated` dict;
  - a payload model;
  - non-ASCII text;
  - a float (`confidence`);
  - an append onto an existing LF-terminated ledger.
- **Invalid:**
  - an unknown `event_type`;
  - a missing `event_type`;
  - a payload that fails validation;
  - an unregistered model class name;
  - a NaN value;
  - a torn tail.

### `LOCAL_ONLY_EVENT_TYPES` (FR-009)

The value is unchanged: an empty `frozenset`. Only the comment at `__init__.py:272-278` changes, to
record the retirement and the 11.0 removal. No `__getattr__` and no warning are added. A test
imports the name under `warnings.simplefilter("error")`.

### Version and docs (FR-011, FR-012)

- Bump to 10.8.0 in `__version__`, `pyproject.toml`, the README masthead and pip line,
  `COMPATIBILITY.md` (its version line plus a section on the new surface and the deprecation) and
  `CHANGELOG.md`, and refresh `uv.lock`.
- New page `docs/api/ledger-write-contract.md`, with frontmatter, linked from `docs/api/README.md`.

## Project Structure

### Documentation (this mission)

```
kitty-specs/ledger-write-contract-seam-01M57P5Q/
├── spec.md · plan.md · research.md · data-model.md · quickstart.md
├── contracts/ledger-append.md
└── tasks.md + tasks/   (from /spec-kitty.tasks)
```

### Source Code (repository root)

```
src/spec_kitty_events/
├── persistence.py                              # NEW
├── __init__.py                                 # version, error re-exports, LOCAL_ONLY comment
└── conformance/
    ├── loader.py                               # +ledger_append category / fixture_type
    ├── test_ledger_append.py                   # NEW packaged runner
    └── fixtures/ledger_append/{valid,invalid}/ # NEW + manifest.json entries
tests/
├── test_persistence.py                         # NEW unit + hypothesis + benchmark
docs/api/ledger-write-contract.md               # NEW
```

## Complexity Tracking

No charter violations. `build_record` dispatches on the input kind through small helpers to keep
each function at complexity 15 or below.

## Parallel Work Analysis

### Dependency Graph

```
WP01 persistence module + unit tests (red-first)
  └─> WP02 ledger_append fixtures + packaged runner + loader registration
        └─> WP03 LOCAL_ONLY deprecation, version bump 10.8.0, CHANGELOG/COMPATIBILITY/README, docs page
```

### Work Distribution

The work packages run sequentially in one lane. The work is small, and each package depends on its
predecessor's surface: the fixtures need the module, and the bump needs the fixtures' `min_version`.

### Coordination Points

- WP02 and WP03 both touch `manifest.json` and must agree on `min_version` `10.8.0`.
- The version-not-amended guard runs at WP03. WP01 and WP02 run their targeted tests only.

## Implementation Concern Map

| Concern | Requirement | WP |
|---|---|---|
| Three-kind normalisation and the registry | FR-002 | WP01 |
| Fail-closed validation | FR-003, FR-004, NFR-004 | WP01 |
| Byte rules and atomic append | FR-005, FR-006, NFR-001 | WP01 (rules), WP02 (pinned) |
| Packaged fixtures and runner | FR-007, FR-008, SC-002 | WP02 |
| Deprecation and canonical lifecycle types | FR-009, FR-010 | WP02 (fixtures), WP03 (docs) |
| Release and docs | FR-011, FR-012, SC-003 | WP03 |
| Core follow-up issue | SC-004 | WP03 |
