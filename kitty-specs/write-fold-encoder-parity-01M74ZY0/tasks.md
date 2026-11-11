# Tasks: Write-fold and reference-encoder parity (M2)

**Mission**: `write-fold-encoder-parity-01M74ZY0` · **Spec**: [spec.md](./spec.md) · **Plan**: [plan.md](./plan.md) · **Research**: [research.md](./research.md) · **Data model**: [data-model.md](./data-model.md)
**Branch**: planning, base and merge target `issue-237-write-fold-encoder-parity` (single branch); one PR to `main` closes #237 and the rest of #231.

## Subtask Index

| ID | Description | WP | Parallel |
|---|---|---|---|
| T001 | `TransitionId` model (Identified / Absent) with factories, on `WriteFold.Transition` | WP01 | |
| T002 | Arbitration: store the setter's id, look it up through the model; Absent = no setter | WP01 | |
| T003 | Id-keyed maps and dedupe spelling check keyed by `TransitionId`, not `pyText` | WP01 | |
| T004 | Differential fold cases + generator from the package reducer + pytest drift check | WP01 | [P] |
| T005 | Java differential test reading the cases; the `"None"`-string edge case | WP01 | |
| T006 | Non-finite log `event_id` test (coded 500, nothing written) + changelog fragment | WP01 | |
| T007 | `StatusLogAppender` takes a nano clock; remaining wait from it | WP02 | |
| T008 | command-cli wiring passes the shared `nanoClock` bean | WP02 | |
| T009 | Fake-clock tests: lock wait = deadline − fake now, write and repair paths | WP02 | |
| T010 | Changelog fragment | WP02 | |
| T011 | Tuples recurse in the PII strip | WP03 | [P] |
| T012 | `IntegerTooLarge`, `NonStringKey` refusals; narrow the catch | WP03 | |
| T013 | Package docstring lists `status_log` | WP04 | [P] |
| T014 | 3.11 grammar port on every interpreter; docstrings updated | WP03 | |
| T015 | No-mutation deep-copy checks on session-pair tests and the hypothesis property | WP03 | |
| T016 | Cross-interpreter byte check (3.10, 3.11, 3.13) + changelog fragment | WP03 | |
| T017 | `_ALLOWED_TRANSITIONS` and blocked/canceled rules = core's edge graph at the pin | WP04 | |
| T018 | Exhaustive (from, to) matrix test against the literal core graph | WP04 | |
| T019 | Fix existing tests/fixtures that relied on removed edges | WP04 | |
| T020 | `docs/events/work-and-status.md` | WP04 | [P] |
| T021 | Version 11.5.0 + `Changed` fragment | WP04 | |
| T022 | `scripts/conformance/extract_ack_bytes.py` (core mode, encoder mode, `--check`) | WP05 | |
| T023 | Commit `ack-bytes.json` test resource (91 ack cases) | WP05 | |
| T024 | `ProtocolCorpusTest`: every ack case over HTTP, appended bytes asserted | WP05 | |
| T025 | `write-core-parity` step runs the extraction `--check`; corpus README note | WP05 | |
| T026 | Python test for the extraction tool (encoder mode reproduces the resource) + fragment | WP05 | |
| T027 | Workflow test: `UV_PYTHON` prefix on core `uv sync` / `sync_packs.py`; extraction check listed | WP06 | |
| T028 | ArchUnit rule: process creation only in `adapter.out.mission` | WP06 | |
| T029 | Planted fixture + test that the rule fails on it | WP06 | |
| T030 | `service/core-pin.json` purpose text + fragment | WP06 | |
| T031 | Issue-matrix rows (#237, #231) and research record of counts and rulings | WP07 | |
| T032 | Mission record: divergence and follow-up notes | WP07 | |

## WP01 — Id-less transitions mirror core (#231 4.16, 4.17) — P1, first

Goal: the write fold judges a transition with no `event_id` as core does (no setter), modelled explicitly (M2-R1). Landable alone before core #6195. Prompt: [tasks/WP01-idless-transition-fold.md](./tasks/WP01-idless-transition-fold.md).
Independent test: differential cases agree with the package reducer in both directions.

T001 `TransitionId` model (WP01)
T002 Arbitration through the model (WP01)
T003 Id-keyed maps and dedupe (WP01)
T004 Differential cases + generator + pytest (WP01)
T005 Java differential test (WP01)
T006 Non-finite id test + fragment (WP01)

Dependencies: none. Risks: changing a map key type in `EventOrdering` can alter ordering; the full `ParityCorpusTest`/`WriteFoldTest`/`TransitionFoldAdvancedTest` set is the guard.

## WP02 — One clock for the write budget and the Mission lock (#231 4.8) — P2

Prompt: [tasks/WP02-one-clock.md](./tasks/WP02-one-clock.md).

T007 Appender clock (WP02)
T008 Wiring (WP02)
T009 Fake-clock tests (WP02)
T010 Fragment (WP02)

Dependencies: none. Risk: `event-log` is shared with the read service; the change is additive (default constructor keeps `System::nanoTime`).

## WP03 — Reference encoder parity (#231 4.1, 4.2, 4.4, 4.5) — P2

Prompt: [tasks/WP03-reference-encoder.md](./tasks/WP03-reference-encoder.md).

T011 Tuples (WP03)
T012 Typed refusals (WP03)
T014 3.11 grammar everywhere (WP03)
T015 No-mutation checks (WP03)
T016 Cross-interpreter check + fragment (WP03)

Dependencies: none.

## WP04 — `validate_transition` = core's edge graph (D4, #231 4.6) — P2

Prompt: [tasks/WP04-validate-transition-edges.md](./tasks/WP04-validate-transition-edges.md).

T013 Package docstring lists `status_log` (WP04)
T017 Edge graph (WP04)
T018 Matrix test (WP04)
T019 Existing tests (WP04)
T020 Docs (WP04)
T021 11.5.0 + fragment (WP04)

Dependencies: none. Risk: fixtures or conformance cases that assert the old edges; the packaged conformance gate must stay green.

## WP05 — Ack bytes over HTTP (#231 4.9) — P3

Prompt: [tasks/WP05-ack-bytes-over-http.md](./tasks/WP05-ack-bytes-over-http.md).

T022 Extraction tool (WP05)
T023 Resource (WP05)
T024 ProtocolCorpusTest (WP05)
T025 Workflow step + README (WP05)
T026 Tool test + fragment (WP05)

Dependencies: none.

## WP06 — Assurance: workflow pin, process rule, pin purpose (#231 4.10, 4.11, 4.13) — P3

Prompt: [tasks/WP06-assurance-rules.md](./tasks/WP06-assurance-rules.md).

T027 Workflow test (WP06)
T028 ArchUnit rule (WP06)
T029 Planted fixture (WP06)
T030 Pin purpose + fragment (WP06)

Dependencies: WP05 (the workflow test asserts WP05's new check step).

## WP07 — Mission records (FR-018, FR-019) — P3

Prompt: [tasks/WP07-mission-records.md](./tasks/WP07-mission-records.md).

T031 Issue matrix (WP07)
T032 Research record (WP07)

Dependencies: WP01–WP06 (records their outcomes).
