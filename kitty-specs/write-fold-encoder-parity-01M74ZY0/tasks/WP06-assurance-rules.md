---
work_package_id: WP06
title: 'Assurance: workflow pin, process rule, pin purpose (#231 4.10, 4.11, 4.13)'
dependencies:
- WP05
requirement_refs:
- FR-015
- FR-016
- FR-017
- SC-005
planning_base_branch: issue-237-write-fold-encoder-parity
merge_target_branch: issue-237-write-fold-encoder-parity
branch_strategy: Planning artifacts for this mission were generated on issue-237-write-fold-encoder-parity. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into issue-237-write-fold-encoder-parity unless the human explicitly redirects the landing branch.
subtasks:
- T027
- T028
- T029
- T030
history:
- timestamp: '2026-11-11T11:30:00Z'
  lane: planned
  agent: system
  action: Prompt generated via /spec-kitty.tasks
agent_profile: implementer-ivan
authoritative_surface: service/command-cli/src/test/java/org/speckitty/commandcli/architecture/
create_intent:
- service/command-cli/src/test/java/org/speckitty/commandcli/architecture/ProcessCreationRuleTest.java
- service/command-cli/src/test/java/org/speckitty/commandcli/architecture/planted/PlantedProcessCreator.java
- docs/changelog/unreleased/237-assurance-rules.md
execution_mode: code_change
owned_files:
- tests/test_service_workflows.py
- service/command-cli/src/test/java/org/speckitty/commandcli/architecture/ArchitectureRulesTest.java
- service/command-cli/src/test/java/org/speckitty/commandcli/architecture/ProcessCreationRuleTest.java
- service/command-cli/src/test/java/org/speckitty/commandcli/architecture/planted/**
- service/core-pin.json
- docs/changelog/unreleased/237-assurance-rules.md
role: implementer
tags: []
tracker_refs: []
---
# Work Package Prompt: WP06 — Assurance: workflow pin, process rule, pin purpose (#231 4.10, 4.11, 4.13)

## ⚡ Do This First: Load Agent Profile

Load your assigned profile before reading anything else: `/spk-charter-profile-load implementer-ivan`
(or `spec-kitty agent profile show implementer-ivan`). Adopt its identity, scope and boundaries.

## Objective

Three mechanical assurance gaps from the #230 squad:

1. **4.10** — `test_write_core_parity_checks_oracles_with_core_python` does not assert that core's
   venv uses core's `.python-version`, so the regression fixed in #230 (`7ae9dde9`) would pass again.
2. **4.11** — command-cli has no architecture rule confining process creation to
   `adapter.out.mission` (where `RootCheckout` runs git). The read service has its rule 10.
3. **4.13** — `service/core-pin.json`'s `purpose` names only lock interop and the nightly corpus
   oracle; the pin also drives the `write-core-parity` oracles (and, from WP05, the ack-bytes check).

Depends on WP05 because the workflow test must also list WP05's new `extract_ack_bytes.py --check`
step.

## Context

- `tests/test_service_workflows.py:160-180`: the test lists the three oracle commands and checks
  `tee`. The workflow (`.github/workflows/service.yml` `write-core-parity`) has:
  - `UV_PYTHON="$(cat .python-version)" uv sync --frozen` (working-directory `core`)
  - `PYTHONPATH="$GITHUB_WORKSPACE/core/src" UV_PYTHON="$(cat .python-version)" uv run --frozen --no-sync python scripts/sync_packs.py --pack all`
- ArchUnit: `service/command-cli/src/test/java/org/speckitty/commandcli/architecture/ArchitectureRulesTest.java`
  (imports main classes, `DO_NOT_INCLUDE_TESTS`). The read service's equivalent:
  `service/src/test/java/org/speckitty/missionstatus/architecture/ArchitectureRules.java:195-197`
  (rule 10: `dependOnClassesThat(java.lang.ProcessBuilder)` or `callMethodWhere(Runtime.exec)`).
  Read only; do not edit `service/src`.
- Allowed process creation: `adapter/out/mission/RootCheckout.java:69` (grep the package for others).
  `config/EndpointPublisher.java` uses `ProcessHandle.current().pid()` — **not** process creation;
  must stay allowed.

## Subtasks

### T027 — Workflow test
In `test_write_core_parity_checks_oracles_with_core_python`:
1. Find the step whose `working-directory` is `core` and whose `run` contains `uv sync`; assert its
   `run` starts with `UV_PYTHON="$(cat .python-version)" uv sync`.
2. Same for the `sync_packs.py` step: `run` contains `UV_PYTHON="$(cat .python-version)"` before
   `uv run`.
3. Add WP05's command
   `SPEC_KITTY_CORE_CHECKOUT="$GITHUB_WORKSPACE/core" core/.venv/bin/python scripts/conformance/extract_ack_bytes.py --check`
   to the `commands` list.
4. Positive control (run once, note in the activity log; do not commit): a copy of the workflow
   without the prefix makes the assertion fail.

### T028 — ArchUnit rule
In `ArchitectureRulesTest`, add a rule (as a `static final ArchRule` so T029 can reuse it):
`noClasses().that().resideOutsideOfPackage(ROOT + ".adapter.out.mission..")
.should().dependOnClassesThat(name java.lang.ProcessBuilder or java.lang.Process)
.orShould().callMethodWhere(Runtime.exec)` with `.as("process creation only in adapter.out.mission")`.
Check it passes on `main`.

### T029 — Planted fixture
`architecture/planted/PlantedProcessCreator.java` (test sources) that calls
`new ProcessBuilder("true")` (never executed — just a method body). `ProcessCreationRuleTest`
imports that class explicitly with `ClassFileImporter().importClasses(PlantedProcessCreator.class)`
and asserts `rule.evaluate(classes).hasViolation()` is true (AssertJ). Also assert a planted class
that only uses `ProcessHandle.current().pid()` passes (add a second planted class), proving the rule
does not over-reach. The planted package must not be imported by the main-class import (it is under
test sources; `DO_NOT_INCLUDE_TESTS` already excludes it — verify).

### T030 — Pin purpose + fragment
1. `service/core-pin.json`: change only `purpose`, e.g. `"CI-only peers: lock interop, the nightly
   corpus oracle and the write-core-parity oracles (status_append, transition parity, fromisoformat
   vectors, ack bytes) (ADR 0002 D4)"`. Keep the SHA and key order; keep the file's one-line JSON
   format. Grep for tests/scripts that parse `core-pin.json` (`grep -rn core-pin tests scripts .github`)
   and run them.
2. Fragment `docs/changelog/unreleased/237-assurance-rules.md`, section `Changed`: the workflow test
   asserts core's interpreter pin; a command-cli ArchUnit rule confines process creation to
   `adapter.out.mission`; the core pin's purpose names every peer (#237; #231 items 4.10, 4.11, 4.13).

## Definition of Done
- `uv run pytest tests/test_service_workflows.py -q` green (count recorded).
- `./gradlew :command-cli:test --tests '*architecture*'` green (count recorded).
- Every test that reads `core-pin.json` green.

## Do not
- Do not edit `.github/workflows/service.yml` (WP05 owns it) or `service/src`.
- Implementation command: `spec-kitty agent action implement WP06 --agent <name> --mission write-fold-encoder-parity-01M74ZY0`
