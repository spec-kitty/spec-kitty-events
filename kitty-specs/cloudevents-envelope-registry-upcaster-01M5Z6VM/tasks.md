# Tasks: CloudEvents envelope, type registry and legacy-row upcaster

**Mission**: `cloudevents-envelope-registry-upcaster-01M5Z6VM` · **Branch**: `issue-92-cloudevents-envelope`
(planning base and merge target; the PR then targets `main`).

## Subtask Index

| ID | Description | WP | Parallel |
|---|---|---|---|
| T001 | Move every `dataschema`/`$id` to `urn:ai.spec-kitty:schema:`; write-profile envelope pattern | WP01 | |
| T002 | Read-profile envelope schema; `envelope_schemas` in the registry; `row_schema(profile=)` | WP01 | |
| T003 | `transport` mapping on the 18 entries with a strict `event_type` (lane: lifted members, `doing` alias) | WP01 | |
| T004 | Eight transport-only entries with data schemas pinned to their payload models | WP01 | |
| T005 | Lane data schema gains optional `summary`; regenerate composed schemas (rows, legacy, read); fixtures | WP01 | |
| T006 | Red-first registry tests (coverage of `STRICT_EVENT_TYPES`, URN, profiles, pinning) | WP01 | |
| T007 | Canonical serialization (`canonical_bytes`) with red-first tests | WP02 | [P] |
| T008 | Result, context, issue and error types; shape detection | WP02 | |
| T009 | Shape A mapping (aggregate split, lifts, aliases, extensions, clock bound) | WP02 | |
| T010 | Flat and payload-envelope mapping driven by the registry (diary, annotation, retrospective, lifecycle, decision, journal) | WP02 | |
| T011 | Strict/permissive policy, `upcast_line`, original-line retention | WP02 | |
| T012 | Property tests: determinism, no mutation, idempotence; throughput check | WP02 | |
| T013 | Lane CloudEvent → diary row decode | WP03 | |
| T014 | Non-normative validator `validate_cloudevent(row, profile=)` | WP03 | |
| T015 | Corpus generator script: shape A per strict type, diary replay rows, producer legacy rows, edge cases | WP03 | |
| T016 | Committed corpus and goldens under `producer_contract/fixtures/upcast/` | WP03 | |
| T017 | Packaged conformance test over the corpus (golden bytes, read-profile validity, idempotence) | WP03 | |
| T018 | Equal reduced state over the 8 status-diary replay fixtures | WP03 | |
| T019 | JDK 25 provisioning script and Maven Wrapper | WP04 | |
| T020 | Maven module skeleton reading `producer_contract/` in place | WP04 | |
| T021 | Java canonical writer (Python-compatible floats, sorted keys) | WP04 | |
| T022 | Java upcaster and validator | WP04 | |
| T023 | JUnit corpus runner (same goldens), `make test-java` in `test-full` | WP04 | |
| T024 | Design doc: row-level upcaster contract (detection, outcomes, codes); producer-row-schemas doc | WP05 | [P] |
| T025 | Version 11.3.0: pyproject, `__version__`, COMPATIBILITY section, CHANGELOG | WP05 | |
| T026 | Producer contract README and docs index updates; core release/lock-refresh note | WP05 | |

## WP01 – Registry and schemas complete (P1)

Goal: the registry covers all 26 strict types plus the diary rows, every `dataschema` is a URN,
and read/write envelope profiles exist. Independent test: `tests/test_producer_contract.py`
and the packaged gate.

T001 Move dataschema base to the URN (WP01)
T002 Read-profile envelope (WP01)
T003 Transport mappings (WP01)
T004 Eight transport-only entries (WP01)
T005 Lane summary + composed regeneration (WP01)
T006 Red-first registry tests (WP01)

Dependencies: none. Prompt: `tasks/WP01-registry-and-schemas.md`.

## WP02 – Python upcaster (P1)

Goal: `spec_kitty_events.cloudevents.upcast` per `contracts/upcaster-contract.md`.

T007 Canonical serialization (WP02)
T008 Types and detection (WP02)
T009 Shape A mapping (WP02)
T010 Registry-driven flat/envelope mapping (WP02)
T011 Strict/permissive and lines (WP02)
T012 Property tests (WP02)

Dependencies: WP01. Prompt: `tasks/WP02-python-upcaster.md`.

## WP03 – Corpus, goldens, validator, reduced state (P1)

T013 Lane decode (WP03)
T014 Validator (WP03)
T015 Corpus generator (WP03)
T016 Committed corpus (WP03)
T017 Packaged gate test (WP03)
T018 Equal reduced state (WP03)

Dependencies: WP02. Prompt: `tasks/WP03-corpus-goldens-validator.md`.

## WP04 – Java binding (P2)

T019 JDK 25 + wrapper (WP04)
T020 Module skeleton (WP04)
T021 Canonical writer (WP04)
T022 Upcaster + validator (WP04)
T023 Corpus runner + make target (WP04)

Dependencies: WP03. Prompt: `tasks/WP04-java-binding.md`.

## WP05 – Docs and release 11.3.0 (P2)

T024 Design doc contract (WP05)
T025 Version and compatibility (WP05)
T026 READMEs and core note (WP05)

Dependencies: WP03, WP04. Prompt: `tasks/WP05-docs-and-release.md`.
