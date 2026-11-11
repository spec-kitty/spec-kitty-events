---
work_package_id: WP05
title: Ack bytes over HTTP against core-extracted resources (#231 4.9)
dependencies: []
requirement_refs:
- FR-014
- NFR-002
- SC-004
- C-001
planning_base_branch: issue-237-write-fold-encoder-parity
merge_target_branch: issue-237-write-fold-encoder-parity
branch_strategy: Single-branch mission. Planning, base and merge target are issue-237-write-fold-encoder-parity; the finished branch goes to main through one PR.
subtasks:
- T022
- T023
- T024
- T025
- T026
history:
- timestamp: '2026-11-11T11:30:00Z'
  lane: planned
  agent: system
  action: Prompt generated via /spec-kitty.tasks
agent_profile: java-jenny
authoritative_surface: service/command-cli/src/test/resources/conformance/
create_intent:
- scripts/conformance/extract_ack_bytes.py
- service/command-cli/src/test/resources/conformance/ack-bytes.json
- tests/test_extract_ack_bytes.py
- docs/changelog/unreleased/237-ack-bytes-over-http.md
execution_mode: code_change
owned_files:
- scripts/conformance/extract_ack_bytes.py
- service/command-cli/src/test/resources/conformance/ack-bytes.json
- service/command-cli/src/test/java/org/speckitty/commandcli/adapter/in/http/operations/ProtocolCorpusTest.java
- .github/workflows/service.yml
- src/spec_kitty_events/conformance/status_transition_parity/README.md
- tests/test_extract_ack_bytes.py
- docs/changelog/unreleased/237-ack-bytes-over-http.md
role: implementer
tags: []
tracker_refs: []
---
# Work Package Prompt: WP05 — Ack bytes over HTTP against core-extracted resources (#231 4.9)

## ⚡ Do This First: Load Agent Profile

Load your assigned profile before reading anything else: `/spk-charter-profile-load java-jenny`
(or `spec-kitty agent profile show java-jenny`). Adopt its identity, scope and boundaries.
This WP is Java plus one Python script; follow `python-pedro` conventions for the script.

## Objective

Every ack case of the status-transition parity corpus — **all 91** (86 `domain` + 5 `service`;
re-count and record it) — runs over real HTTP and asserts the **bytes appended to the log** against a
committed expectation extracted from core (FR-014, NFR-002, SC-004).

Operator ruling **M2-R2**: store the expectations as test resources in this repository; no hard
coupling to core. The tests read only the committed file. The extraction tool may consult core only
through the existing CI-only, SHA-pinned peer (`write-core-parity` job), like the other oracles.

## Context

- Spec: User Story 4 scenario 1, FR-014, FR-018. Research "Counts re-derived", D-7. Data model
  "Ack-bytes expectation".
- `ProtocolCorpusTest.java` (`service/command-cli/src/test/java/.../adapter/in/http/operations/`):
  `protocolCases` (:46-54) runs only non-`domain` cases; `everyGuardReasonOverHttp` runs one domain
  **refusal** per reason; `runCase` (:117-169) for ack asserts status 201, `appendedLines`,
  `replayed`, `fsyncs` — never bytes. `initialLog` (:215) writes the prior log with
  `StatusRowEncoder.encodeLine`. `rig.log()` is the log path. `before` bytes are captured (:139).
- Corpus: `src/spec_kitty_events/conformance/status_transition_parity/cases/*.json` (258 files).
  Ack expectation: `expected.units[i].appendedLines` / `replayed`; batch `fsyncs`.
- Core serializer at the pin: `serialize_event_line` (see `scripts/conformance/generate_status_append.py`
  for how a test-only script imports core with `SPEC_KITTY_CORE_CHECKOUT`, checks the interpreter
  `PINNED_PYTHON = (3, 11)` and `PINNED_CORE_SHA`). Mirror that pattern exactly.
- This session **cannot execute core** (permission classifier). The committed resource is therefore
  produced in **encoder mode** with `spec_kitty_events.status_log.encode_status_row`, which the
  committed `status_append` oracle proves core-exact for such rows; CI's `write-core-parity` runs
  the tool in **core mode** with `--check` and proves the committed file equals core's output.
  Say this plainly in the script docstring and the corpus README.
- Domain ack cases may need mission/task setup that `runCase` already does via
  `rig.replaceMission(...)`; domain cases were excluded only because WP06/WP07 of the earlier mission
  consumed them in-process. Check that each domain ack case is reproducible over HTTP (fixtures like
  `own-meta-bom` need the special setup `runCase` already has). If a domain ack case cannot be run
  over HTTP for a principled reason, list it with that reason in the test (an explicit, asserted
  exclusion list — never a silent skip) and in the activity log.

## Subtasks

### T022 — `scripts/conformance/extract_ack_bytes.py`
1. Reads every corpus case whose `expected.result == "ack"`; for each expected unit computes the
   appended text: `""` when `replayed` is true, else the concatenation of the serialised lines of
   that unit's request `rows`, in order. (Confirm against the service: the service appends exactly
   the unit's rows when not replayed — read `WriteService.insideLock` to be sure, including the
   start-from-planned two-unit batch and annotation units.)
2. Modes: `--serializer core` (imports core's `serialize_event_line` from
   `SPEC_KITTY_CORE_CHECKOUT`, enforces 3.11 and the pin SHA as the other generators do) and
   `--serializer encoder` (the package's `encode_status_row`). Default: `core`.
3. Output: `service/command-cli/src/test/resources/conformance/ack-bytes.json`,
   `{"<case id>": ["<unit text>", ...]}`, keys sorted, `json.dumps(..., indent=2, sort_keys=True,
   ensure_ascii=True)` + LF. `--check` writes nothing and exits 1 with a diff summary on drift.
4. Typed, `ruff`/`mypy --strict`-clean; module docstring states provenance and the M2-R2 ruling.

### T023 — Commit the resource
Run `uv run python scripts/conformance/extract_ack_bytes.py --serializer encoder`; commit the file.
Sanity checks: 91 keys (or the re-counted number), each unit text ends with LF or is empty, every
line parses as JSON.

### T024 — `ProtocolCorpusTest`
1. Run **every** ack case over HTTP: extend `protocolCases` (or add `everyAckCaseOverHttp`) to
   include domain ack cases. Keep the existing domain-refusal representative test.
2. In `runCase` for ack: `after = Files.readAllBytes(rig.log())`; assert `after` starts with
   `before` (or is the whole file when `before == null`) and the appended suffix equals the
   concatenation of the case's expected unit texts from `ack-bytes.json` (load once, classpath
   resource). Assert the resource has an entry for every ack case run, and every resource entry
   was run (no orphan keys) — a final `@AfterAll` or a dedicated test.
3. Keep counts/flags assertions as they are.

### T025 — Workflow step + README
1. `.github/workflows/service.yml` `write-core-parity`: add a step after the parity oracle check:
   `SPEC_KITTY_CORE_CHECKOUT="$GITHUB_WORKSPACE/core" core/.venv/bin/python scripts/conformance/extract_ack_bytes.py --check | tee -a "$GITHUB_STEP_SUMMARY"`
   (same shape as the neighbouring steps; `shell: bash` with pipefail is already the job default).
   Do not change action pins or anything else in the workflow.
2. `status_transition_parity/README.md`: a short "Appended bytes" paragraph: where the expectation
   lives, how it is extracted, the encoder-mode bootstrap and the CI core check. Note it is a test
   resource of the service, not part of the packaged contract.

### T026 — Tool test + fragment
1. `tests/test_extract_ack_bytes.py`: in encoder mode, the tool reproduces the committed resource
   byte-for-byte (call its function, do not shell out to core); a replayed unit yields `""`; the
   case count equals the corpus's ack count.
2. Fragment `docs/changelog/unreleased/237-ack-bytes-over-http.md`, section `Changed`: every ack
   case of the parity corpus now runs over HTTP and asserts the appended bytes against a
   core-extracted test resource (#237; #231 item 4.9).
3. Tell the orchestrator (via the activity log) that WP06's workflow test must list the new step.

## Definition of Done
- `./gradlew :command-cli:test --tests '*ProtocolCorpusTest*'` green; record the number of ack cases
  asserted over HTTP.
- `uv run pytest tests/test_extract_ack_bytes.py -q` green.
- `uv run python scripts/conformance/extract_ack_bytes.py --serializer encoder --check` exits 0.
- `ruff`, `mypy --strict` clean.

## Risks and reviewer guidance
- Reviewer: open three resource entries (a batch, a start-from-planned, a replay) and check them by
  hand against the request rows.
- If CI's core `--check` reports drift, the encoder is not core-exact for some row: that is a real
  finding — escalate, do not edit the resource by hand.

## Do not
- Do not change the corpus cases or `case.schema.json`.
- Do not edit `tests/test_service_workflows.py` (WP06 owns it).
- Implementation command: `spec-kitty agent action implement WP05 --agent <name> --mission write-fold-encoder-parity-01M74ZY0`
