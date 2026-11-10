---
work_package_id: WP04
title: 'Evidence wire field, corpus parity, and the real root (#224, #223)'
dependencies:
- WP01
requirement_refs:
- FR-010
- FR-011
- FR-012
- FR-013
- FR-014
- NFR-002
- SC-003
planning_base_branch: kitty/mission-write-service-cutover-readiness-01M70THX
merge_target_branch: main
branch_strategy: Planning artifacts for this mission were generated on kitty/mission-write-service-cutover-readiness-01M70THX. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into main unless the human explicitly redirects the landing branch.
subtasks:
- T017
- T018
- T019
- T020
- T021
- T022
- T023
- T024
history:
- timestamp: '2026-11-09T20:30:00Z'
  lane: planned
  agent: system
  action: Prompt generated via /spec-kitty.tasks
agent_profile: java-jenny
authoritative_surface: service/command-cli/src/main/java/org/speckitty/commandcli/domain/authority/
create_intent:
- docs/changelog/unreleased/236-evidence-corpus-case.md
execution_mode: code_change
owned_files:
- service/command-cli/src/main/java/org/speckitty/commandcli/domain/authority/UnitInput.java
- service/command-cli/src/main/java/org/speckitty/commandcli/domain/authority/Authority.java
- service/command-cli/src/main/java/org/speckitty/commandcli/domain/authority/batch/BatchJudge.java
- service/command-cli/src/main/java/org/speckitty/commandcli/domain/authority/UnitJudge.java
- service/command-cli/src/main/java/org/speckitty/commandcli/domain/authority/Guards.java
- service/command-cli/src/main/java/org/speckitty/commandcli/domain/authority/GuardContext.java
- service/command-cli/src/main/java/org/speckitty/commandcli/application/write/WriteRequest.java
- service/command-cli/src/main/java/org/speckitty/commandcli/adapter/in/http/operations/WriteBodies.java
- service/command-cli/src/test/java/org/speckitty/commandcli/domain/authority/**
- service/command-cli/src/test/java/org/speckitty/commandcli/adapter/in/http/operations/ProtocolCorpusTest.java
- service/command-cli/src/test/java/org/speckitty/commandcli/adapter/in/http/operations/WriteBodiesTest.java
- service/command-cli/src/test/java/org/speckitty/commandcli/adapter/out/mission/DiskMissionFactsParityTest.java
- src/spec_kitty_events/conformance/status_transition_parity/**
- src/spec_kitty_events/conformance/README.md
- scripts/conformance/generate_transition_parity.py
- tests/test_generate_transition_parity.py
- docs/changelog/unreleased/236-evidence-corpus-case.md
role: implementer
tags: []
tracker_refs: []
---
# Work Package Prompt: WP04 — Evidence wire field, corpus parity, and the real root (#224, #223)

## ⚡ Do This First: Load Agent Profile

Load your assigned profile before reading anything else: `/spk-charter-profile-load java-jenny`
(or `spec-kitty agent profile show java-jenny`). You will also edit Python. Apply `python-pedro`'s
standards there: `mypy --strict`, ruff, and tests in the same commit.

## Objective

Give the wire the same evidence input core's authority has, and prove it against core's oracle:

- **The rule (D3).** An optional `TransitionUnit.implementationEvidencePresent` (boolean or null) is used **verbatim** as the evidence flag of the unforced `in_progress → for_review` guard. Absent or null keeps today's inference (`wp.hasTransitions()`).
- **What it never touches.** It never affects the subtask half. It is ignored for other transitions and under force, and it is never persisted:
  - the appended bytes are identical with and without it (NFR-002);
  - a retry with or without it is a replay.
- **Corpus.** New cases are generated from core at the pin. `unreachable.json` drops its `partial` entry. The refusal goes over HTTP **and** through the domain (FR-012).

## Context (read first)

- Spec: [spec.md](../spec.md) User Story 2 (6 scenarios), FR-010 to FR-013, NFR-002, SC-003, the D3 correction, and the edge cases (`null`, other transitions).
- Plan: [plan.md](../plan.md) D-C and **"Post-plan adversarial squad → Contract, parity and scope"**: the HTTP representative per (reason, field), the extra corpus readers, the package record, and `unreachable.json`.
- Contract (from WP01): `contracts/mission-write/schemas/TransitionUnit.yaml` `implementationEvidencePresent`.
- Core (read-only): `~/Documents/code/kitty/spec-kitty`, pin SHA in `service/core-pin.json` (`512567ee…`).
  - At the pin, `emit_status_transition` accepts `implementation_evidence_present` (`src/specify_cli/status/emit.py:888`, `:968`).
  - The pipeline infers only when it is `None` (`transition_pipeline.py:174-176` on main).
  - Guard order: subtasks, then evidence (`wp_state.py` :388 at the pin).
- Java seams (base `62af21d0`):
  - **`WriteBodies.java`:**
    - `UNIT_REQUIRED` and `UNIT_FIELDS` are at 44-45;
    - `onlyFields` is at :140, and the detail text at :141 lists the members;
    - operation parse is at 175-188, the typed unit at :171.
  - `WriteRequest.UnitRequest(wpId, operation, rows)` at :27-32.
  - **`UnitInput(wpId, operation, rows)`** at :7. Constructor callers: `WriteService:221`; tests `CorpusCases:51`, `CorpusAuthorityCases:47`, `DiskMissionFactsParityTest:60`, `AuthorityTest:14`, `UnitJudgeTest:137/152`, `BatchJudgeTest:71/128`, `ClaimRulesTest:162`.
  - **`UnitJudge.judge`** builds the guard context at :40.
  - **`Guards.context`:**
    - the signature is at 30-31;
    - **the evidence is `wp.hasTransitions()` at :43**;
    - `executionMode + ":<root>"` is at :44-45 (fixed in T023).
  - `GuardContext` is at 4-14 (callers `Guards:45`, `AuthorityFixtures:13/17/22/28`); `GuardContextTest:38`.
  - `ReplayClassifier` compares encoded rows only (40-51).
  - **`ProtocolCorpusTest`:**
    - `everyGuardReasonOverHttp` is at 54-71 (one case per reason, `putIfAbsent`, first alphabetically);
    - `requestBody` is at 181-199 (copies only `wpId`, `operation` and `rows`);
    - the report is at 248-255 (`GUARD_REASONS hasSize(15)` :250, unreachable :252, `PASSED hasSize(34)` :253, printf :254).
  - `ParityCorpusTest:25-63`; `CorpusCases.java:49-53`; `CorpusAuthorityCases.java:47`, which feeds `FullParityCorpusTest` and `ReasonLedgerTest`.
- Corpus and generator:
  - **`src/spec_kitty_events/conformance/status_transition_parity/`:**
    - `case.schema.json` (request units allow only `wpId`, `operation` and `rows`; `additionalProperties: false`);
    - `unreachable.json` (:2 `workspace_context_required`; :3-5 `partial` `implementation_evidence_required`);
    - `cases/`, `README.md:24-26`, and `parity_corpus.py` (147-163; an absent `partial` is fine).
  - The packaged test `test_status_transition_parity.py` (reasons == 16 at :61; algebra at 79-89).
  - **`scripts/conformance/generate_transition_parity.py`:**
    - `ROW_CALL_KWARGS` is at 41-56;
    - core call kwargs are at 1630-1674;
    - wire units are at :1888 and :1906, and the request at :1936-1940;
    - reason extras are at 1561-1568.

    Its tests are `tests/test_generate_transition_parity.py`. CI runs `--check` against the pin (`.github/workflows/service.yml`, job `write-core-parity`).

## Subtasks

### T017 — Wire parsing

- **`WriteBodies`:** add `implementationEvidencePresent` to `UNIT_FIELDS`. Accept a JSON boolean or `null`; any other type is `400 REQUEST_MALFORMED` (a test for a string, a number and an object). Update the detail text at :141.
- **Domain records.** `WriteRequest.UnitRequest` and `UnitInput` gain a trailing `Boolean implementationEvidencePresent`, where null means absent. **Keep the old constructors as delegating overloads** passing `null`, so no caller breaks. Then update every **corpus** caller explicitly (T021); the other test callers may keep the overload.
- **Misspelling.** A misspelt key (`implementationEvidence`) still gives `400 REQUEST_MALFORMED` (a test).

### T018 — Authority

- `UnitJudge` passes the unit's value into `Guards.context` (add a parameter).
- In `Guards.context`, compute `evidence` as:
  ```java
  Boolean evidence = from == IN_PROGRESS && to == FOR_REVIEW
      ? (supplied != null ? supplied : wp.hasTransitions())
      : null;
  ```
- **Scope of the field.** It must not touch the subtask derivation. The guard is skipped under force by `LaneMachine`, as today.
- **Unit tests in `UnitJudgeTest` and `GuardContextTest`:**
  - `false` refuses `implementation_evidence_required` (complete roster);
  - `true` and absent accept;
  - `true` with an incomplete roster refuses for subtasks;
  - force with `false` accepts;
  - another transition with `false` is unaffected.

### T019 — Corpus schema, generator, cases

- **Schema.** `case.schema.json`: allow `implementationEvidencePresent` (boolean or null) on request units.
- **Generator** (`generate_transition_parity.py`):
  1. Add `implementation_evidence_present` to the transition kwargs in `ROW_CALL_KWARGS`.
  2. When a case spec sets it, pass it to core's `emit_status_transition`.
  3. Emit `implementationEvidencePresent` on the wire unit (:1888 and :1906) **only when set**.
- **New case specs**, generated by running core at the pin:
  - (a) `in_progress → for_review`, complete roster, `false`: core refuses with "requires implementation evidence", reason `implementation_evidence_required`;
  - (b) the same with `true`: accepted;
  - (c) absent: accepted (this exists; reuse);
  - (d) `true`, incomplete roster: refused for subtasks.
- **Running the oracle.** Use a throwaway clone at the pin; never touch the user's checkout:
  ```bash
  T=$(mktemp -d); git clone -q --no-checkout ~/Documents/code/kitty/spec-kitty "$T/core" && git -C "$T/core" checkout -q $(jq -r .sha service/core-pin.json)
  (cd "$T/core" && UV_PYTHON="$(cat .python-version)" uv sync --frozen)
  ```
  Follow the CI job's steps in `service.yml` `write-core-parity` exactly, including `sync_packs.py`, then run the generator to write and then `--check`.
- **Names.** Choose case names deliberately; see T021.
- **Generator tests.** Extend `tests/test_generate_transition_parity.py` for the kwarg, the wire emission, and "absent when unset".

### T020 — `unreachable.json` and READMEs

- **`unreachable.json`:**
  - remove the `partial` entry;
  - rewrite the `workspace_context_required` entry's reason: no wire request can produce it, because core's guard is fail-closed for its own batch API (`transition_pipeline.py:322-337` at core `origin/main` @ `366ff233`, `:289-304` at the pin `512567ee`; operator decision 2026-09-07, #946, DRIFT-3).
- **READMEs.** Update `status_transition_parity/README.md:24-26` (units may carry `implementationEvidencePresent`) and `src/spec_kitty_events/conformance/README.md:102-112` to match.
- **Tests.** Keep `parity_corpus.py` and the packaged test green (`uv run pytest --pyargs spec_kitty_events.conformance`).

### T021 — Java corpus consumers

- **`requestBody`** (`ProtocolCorpusTest`) copies `implementationEvidencePresent` when present.
- **`everyGuardReasonOverHttp`** picks **one representative per (reason, has-`implementationEvidencePresent`)**, so both the subtask half and the evidence half of `implementation_evidence_required` go over HTTP. Update the report:
  - `GUARD_REASONS` stays at 15;
  - `PASSED` goes from 34 to 35 (verify the real number and explain it in the commit);
  - update the printf.
- **Field threading.** `CorpusCases.java:49-53`, `CorpusAuthorityCases.java:47` and `DiskMissionFactsParityTest.java:60` build `UnitInput` **with the field**, so `ParityCorpusTest`, `FullParityCorpusTest` and `ReasonLedgerTest` exercise it.
- **Non-vacuity.** Prove it by temporarily dropping the field in `CorpusCases`: the new refusal case must fail. Revert.

### T022 — Byte parity, replay, package record

- **HTTP tests (`ProtocolCorpusTest` or a sibling):**
  - **Byte parity (NFR-002):** the same accepted unit, with `true` and without the field, gives byte-identical logs.
  - **Replay:** an accepted unit sent with the field, then retried without it (and the reverse), gives `appendedLines: 0, replayed: true`.
  - **Refusal:** the `false` refusal leaves the log byte-identical (with a positive control).
- **Changelog fragment.** Add `docs/changelog/unreleased/236-evidence-corpus-case.md` for the **package**, section `Changed` (or `Compatibility`). It records that the packaged `status_transition_parity` corpus gains an optional `implementationEvidencePresent` unit key, the new oracle-backed cases, and the removal of `unreachable.json`'s `partial` entry. Cite `(#236; #224)`. Follow `docs/changelog/unreleased/README.md`.

### T023 — Real root in the guard context (#223, FR-014)

- **What is wrong.** `Guards.java:44-45` builds `executionMode + ":<root>"`, a fabricated placeholder. Core's default synthesis is `f"{execution_mode}:{repo_root or feature_dir}"` (`transition_pipeline.py:364-367` on core main).
- **Why it is honesty, not behaviour.** The value's only consumer is the blank check of `workspace_context_required` (`Guards.java:66`), and it is never persisted.
- **Carrier.** Pass the root **into the authority at construction**, not through `MissionFacts`, which holds authored per-Mission facts:
  - `Authority.standard(String root)` creates a `BatchJudge` whose `UnitJudge` holds `root`, and `UnitJudge` passes it to `Guards.context`.
  - Keep `Authority.standard()` and `BatchJudge.standard()` as **delegating overloads**, so the test fakes and fixtures do not break. Choose their default deliberately: for example a constant `"."`, documented as "tests only; production wires the real root". No `<root>` literal may remain.
- **Never leaked.** The context never reaches refusal details (`DetailPolicy`) or logs.

### T024 — Wiring and tests

- **Declared out-of-map edit:** `config/ServiceWiring.java` (owned by WP03). At :72-74 the resolved root is already in hand, so pass `root.toString()` (or the catalog's `root()`) into `Authority.standard(root)`. This is a one-line change.
- **Tests:**
  - `GuardContextTest` asserts the exact string `worktree:<the root passed in>`.
  - `UnitJudgeTest` (or a wiring test) asserts that the root from construction reaches the context.
  - A byte test (an existing accepted-unit HTTP case) shows the appended bytes are unchanged.
  - `grep -rn '"<root>"\|:<root>' service/command-cli/src/main` returns nothing.

### Declared out-of-map edits (rationale: the evidence field and the root must reach these call sites)

- `application/write/WriteService.java:220-223`: `input()` builds `UnitInput`. Pass the unit's `implementationEvidencePresent` (one line). WP05 owns this file.
- `config/ServiceWiring.java`: `Authority.standard(root)` (one line). WP03 owns this file.

## Definition of Done

- **Java:** `./gradlew :command-cli:check` is green.
- **Python:**
  - `uv run pytest tests/test_generate_transition_parity.py` and `uv run pytest --pyargs spec_kitty_events.conformance` are green;
  - `uv run mypy --strict` on the touched Python is green;
  - `ruff check . && ruff format --check .` is green;
  - `uv run python scripts/changelog.py check` is green.
- **Generator against the pin:** `--check` passes. Record the command and output in the commit message or a note to the orchestrator.
- **Commits:** commit messages end with `Co-Authored-By:` (your model).

## Risks and reviewer guidance

- **Displaced representative:** the new case silently replacing the subtask-half representative over HTTP. Check the per-(reason, field) selection.
- **A reader dropping the field:** check the deliberate red in T021.
- **Hand-written cases:** a case not from core's oracle is a parity lie. The reviewer re-runs `--check`.

## Do not

- Do not edit drain (WP05), logging (WP06), the repair (WP03), or the contract (WP01).
- Do not run `spec-kitty` lifecycle commands, push, or open a PR.
