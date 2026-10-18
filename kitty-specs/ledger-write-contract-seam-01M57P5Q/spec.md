# Mission Specification: Ledger-write contract seam

**Mission**: `ledger-write-contract-seam-01M57P5Q` · **Issue**: #118 (parent epic, context only: #117)
**Created**: 2026-10-18 · **Status**: Draft · **Type**: software-dev

## Intent Summary (confirmed with the operator, 2026-10-18)

- **Primary actor:** a producer writing to a local JSONL ledger. Today that is core's glossary
  emitter (`spec-kitty/spec-kitty` `src/glossary/events.py`). Later it is a second binding, such
  as the Java write-side sidecar, held to the same bytes.
- **Trigger:** the producer calls `spec_kitty_events.persistence.append_event(event, path)`.
- **Desired outcome:** exactly one validated, canonically serialised, LF-terminated line is
  appended to the ledger. If anything is wrong, nothing is written and an error is raised.
- **Invariant:** fail-closed. An event that is invalid, of an unknown type or impossible to
  classify never touches the file.
- **Boundary decisions:**
  - `LOCAL_ONLY_EVENT_TYPES` is retired as a concept. `MissionReopened` and `FollowUpRecorded`
    are canonical, shippable events and are not added to it. It stays exported, empty and
    deprecated through 10.x, and is removed in 11.0.
  - The second import in core's guard (`spec_kitty_events.glossary.events`) is out of scope.
    Fixing it is a core follow-up.

### Grounding (core `main` @ `0746bec3`, this repo `main` @ `dcf6e3b`, 10.7.0)

- Core imports `from spec_kitty_events.persistence import append_event` inside a `try/except
  ImportError` that sets `EVENTS_AVAILABLE` (`src/glossary/events.py:61-77`).
- Core passes `append_event` two kinds of input:
  - a **flat JSON dict** carrying an `event_type` key (`events.py:141`);
  - an **instance of a model class** (`events.py:567`).
- Every exception core gets back is swallowed: core falls back to its own append,
  `json.dumps(d, sort_keys=True, default=str) + "\n"` written in text-append mode
  (`events.py:174-176`). A raise from this package therefore degrades safely in core.
- Core's guard also imports `spec_kitty_events.glossary.events`, which does not exist here
  (`glossary` is a flat module with only `*Payload` classes). Shipping `persistence` alone
  will not set `EVENTS_AVAILABLE=True`; that is the core follow-up.
- `LOCAL_ONLY_EVENT_TYPES` already exists in this package as an empty `frozenset`
  (`__init__.py:278`). Core imports it without a guard (`src/specify_cli/status/lifecycle_events.py:376`).
  Removing it within 10.x would therefore break core.
- `MissionReopened` and `FollowUpRecorded` are already in the conformance model map
  (`conformance/validators.py:282-283`). Treating them as canonical needs no new schema.

## User Scenarios & Testing

### User Story 1: Append a canonical event to a local ledger (P1)

A producer appends one event to `<path>.events.jsonl` and can read it straight back with
`json.loads` per line.

**Acceptance scenarios**
1. **Given** a valid `Event` envelope of a known type, **when** `append_event(event, path)` is
   called, **then** exactly one line is appended, byte-identical to the fixture's expected line.
2. **Given** a flat JSON dict with a known `event_type` whose payload validates, **when**
   appended, **then** one line is written whose bytes match the fixture.
3. **Given** a Pydantic payload-model instance of a known type, **when** appended, **then**
   one line is written whose bytes match the fixture.
4. **Given** a ledger file that does not exist yet, **when** appended, **then** the file is
   created (parent directories included) and holds exactly that one line.

### User Story 2: Refuse anything that is not a valid canonical event (P1)

**Acceptance scenarios**
1. **Given** an event of an unknown or unregistered type, **when** appended, **then** an error
   is raised and the file is byte-for-byte unchanged (or still absent).
2. **Given** a known type whose payload fails model or schema validation, **then** an error is
   raised and nothing is written.
3. **Given** an input that is not an `Event`, a dict or a Pydantic model, or a dict with no
   string `event_type`, **then** an error is raised and nothing is written.
4. **Given** an existing ledger whose last byte is not LF (a torn tail), **then** an error is
   raised and nothing is appended, so the producer never concatenates onto a partial line.

### User Story 3: Hold a second binding to the same bytes (P2)

A binding author runs the packaged conformance fixtures. For every append case they get the
input and the exact expected output bytes. For every refusal case they get the input and the
expected outcome, which is a refusal with an unchanged file.

### User Story 4: Consumers see the retirement of `LOCAL_ONLY_EVENT_TYPES` (P2)

`from spec_kitty_events import LOCAL_ONLY_EVENT_TYPES` still resolves to an empty
`frozenset`. Its deprecation (removal in 11.0) is documented in the CHANGELOG and
COMPATIBILITY. Classification and validation treat `MissionReopened` and `FollowUpRecorded`
exactly like every other canonical type.

### Edge Cases

- Non-ASCII payload text is serialised deterministically, following the pinned byte rules.
- A Pydantic model whose class maps to no known event type is refused.
- A dict that is a full envelope (it has `payload`, `aggregate_id`, …) is validated as an `Event`
  envelope and its inner payload is validated against its `event_type`.
- Two consecutive appends give two lines, in call order.
- An append whose target path is a directory, or is unwritable, raises and leaves no partial line.

## Requirements

### Functional Requirements

| ID | Requirement | Status |
|----|-------------|--------|
| FR-001 | The package MUST provide module `spec_kitty_events.persistence` exporting `append_event(event, path) -> None`, importable as `from spec_kitty_events.persistence import append_event` (core's exact import site). | Proposed |
| FR-002 | `append_event` MUST accept three input kinds, each mapped to one **record** (a JSON object): (a) a canonical `Event` instance, whose record is `event.model_dump(mode="json")`; (b) a `dict` carrying a string `event_type`, whose record is the input dict itself with every key kept and no projection or defaults added; (c) a Pydantic `BaseModel` instance whose class is registered in the payload-class → `event_type` map derived from the conformance model map, whose record is `{"event_type": <T>, **instance.model_dump(mode="json")}`. Any other input is refused. | Proposed |
| FR-003 | Before writing, `append_event` MUST validate the record against the canonical conformance contract (model and schema layers, `strict=True`) for its `event_type`. For an `Event` (input or envelope-shaped dict), both the envelope and its inner payload are validated. A flat dict is validated as the payload of its `event_type` after its identity keys are set aside. Those keys are `event_type`, plus `event_id` and `timestamp`, which are optional and must be strings when present. They stay in the written line, and a key is not set aside when the payload model itself declares that field (e.g. `ReviewerSelfApproval.timestamp`). | Proposed |
| FR-004 | `append_event` MUST raise `LedgerValidationError` and write nothing for: an unknown or unregistered event type, a validation failure, an unsupported input kind, a missing or non-string `event_type`, or a record holding a value that is not JSON-native (anything but str, int, float, bool, None, list or dict) or a non-finite float. All ledger errors MUST subclass a public `LedgerAppendError(SpecKittyEventsError)`. | Proposed |
| FR-005 | **Byte rules (pinned now, held by fixtures):** a line is `json.dumps(record, sort_keys=True, ensure_ascii=True, allow_nan=False)` followed by exactly one `\n`. Keys are sorted recursively. Separators are the defaults `", "` and `": "`. Non-ASCII text is written as `\uXXXX` escapes, so the line is pure ASCII (no BOM question). Floats use the shortest round-trip representation (Python `repr`, e.g. `1e-05`, `0.5`). There is no `default=` coercion. These bytes equal those of core's local fallback (`json.dumps(d, sort_keys=True, default=str) + "\n"`) for every JSON-native record, so a ledger has one byte shape whichever path wrote a line. | Proposed |
| FR-006 | `append_event` MUST create missing parent directories and the ledger file, and MUST write each line with a single `os.write` on an `O_APPEND` file descriptor. Appending to an empty (0-byte) file is allowed. It MUST refuse with `LedgerTornTailError` to append to a non-empty file whose last byte is not LF. Atomicity is guaranteed only for a regular local file, not on network filesystems. | Proposed |
| FR-007 | The package MUST ship conformance fixtures under `src/spec_kitty_events/conformance/fixtures/` with: (a) append cases giving input plus the exact expected line bytes, one per input kind, a non-ASCII case and a multi-append case; (b) refusal cases giving input plus the expected refusal. All are registered in the fixture manifest with `min_version` `10.8.0`. | Proposed |
| FR-008 | The packaged conformance suite (`pytest --pyargs spec_kitty_events.conformance`) MUST run the append fixtures against the Python `append_event`. | Proposed |
| FR-009 | `LOCAL_ONLY_EVENT_TYPES` MUST stay exported from the package root as an empty `frozenset` through 10.x. It MUST be documented as deprecated, with removal in 11.0, in documentation only: importing it MUST emit no warning, even under `-W error::DeprecationWarning`, because core imports it without a guard. `append_event` MUST NOT consult it. | Proposed |
| FR-010 | `MissionReopened`, `FollowUpRecorded` and `GlossarySenseUpdated` MUST be appendable through `append_event` like any other canonical type. Whether a producer *ships* a ledger line off-host is the producer's decision and outside this contract. No membership is added to `LOCAL_ONLY_EVENT_TYPES`. | Proposed |
| FR-011 | The release MUST be an additive minor bump to `10.8.0` across `__version__`, `pyproject.toml`, the README masthead and pip line, `COMPATIBILITY.md` and `CHANGELOG.md`. The envelope `schema_version` stays unchanged. | Proposed |
| FR-012 | The docs MUST describe the write contract (a page under `docs/api/` or `docs/architecture/`) and the `LOCAL_ONLY_EVENT_TYPES` deprecation. | Proposed |

### Non-Functional Requirements

| ID | Requirement | Threshold | Status |
|----|-------------|-----------|--------|
| NFR-001 | Deterministic output | Every append fixture reproduces byte-for-byte on every interpreter in the CI matrix (Python ≥ 3.10, the charter floor). | Proposed |
| NFR-002 | Append latency | A benchmark test reports the median per-append time for a < 4 KiB event. It is reported, not gated; the target is < 5 ms on local disk. | Proposed |
| NFR-003 | Static quality | `mypy --strict` and `ruff check` / `ruff format --check` report zero issues on the new code. Line coverage of `persistence.py` is at least 95 %. | Proposed |
| NFR-004 | No partial records | When validation or serialisation fails, the file is byte-unchanged. A hypothesis property over at least 200 generated inputs checks this: generated inputs are records of known types with one field replaced by a wrong-typed or non-JSON-native value, plus unknown `event_type` strings. | Proposed |

### Constraints

| ID | Constraint | Status |
|----|------------|--------|
| C-001 | Mirror, don't depend: the package imports nothing from `spec-kitty`, `spec-kitty-saas`, `spec-kitty-tracker` or `spec-kitty-runtime`. | Fixed |
| C-002 | The change is additive only: no existing export, schema, fixture or field is removed or renamed in 10.x. | Fixed |
| C-003 | Fixtures and schemas are the source of truth. Every binding is held to them (charter, Language Bindings). | Fixed |
| C-004 | The `spec_kitty_events.glossary.events` shape core expects is out of scope (follow-up in core). | Fixed |
| C-005 | No new runtime dependency is added. | Fixed |

### Key Entities

- **Ledger:** a local, append-only JSONL file. Each line is one record.
- **Record:** the JSON object written for one event, built per input kind as FR-002 defines.
- **`LedgerAppendError` / `LedgerValidationError` / `LedgerTornTailError`:** the public refusal types.
- **Append fixture:** an (input, expected bytes) pair, or an (input, expected refusal) pair.
- **`LOCAL_ONLY_EVENT_TYPES`:** a deprecated, empty classification registry, kept only for
  compatibility.

## Success Criteria

| ID | Criterion |
|----|-----------|
| SC-001 | Core's exact import line `from spec_kitty_events.persistence import append_event` resolves against the 10.8.0 build. |
| SC-002 | Every append fixture reproduces byte-for-byte, and every refusal fixture leaves the file unchanged, under the packaged conformance gate. |
| SC-003 | The packaged conformance gate, the version guard, `mypy --strict` and ruff are all green on the PR. |
| SC-004 | A core follow-up issue is filed covering: the `glossary.events` import; retiring `LOCAL_ONLY_LIFECYCLE_EVENT_TYPES`; not falling back (and making a torn tail worse) on `LedgerTornTailError`. |
