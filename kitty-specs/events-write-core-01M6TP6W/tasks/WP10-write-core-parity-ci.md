---
work_package_id: WP10
title: write-core-parity CI drift job
dependencies:
- WP01
- WP03
- WP04
requirement_refs:
- FR-003
- FR-015
- NFR-005
- SC-006
- C-001
planning_base_branch: kitty/mission-events-write-core-01M6TP6W
merge_target_branch: main
branch_strategy: Planning artifacts for this mission were generated on kitty/mission-events-write-core-01M6TP6W. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into main unless the human explicitly redirects the landing branch.
subtasks:
- T026
- T057
- T058
history:
- timestamp: '2026-11-07T10:30:00Z'
  lane: planned
  agent: system
  action: Prompt generated via /spec-kitty.tasks
agent_profile: python-pedro
authoritative_surface: .github/workflows/service.yml
create_intent: []
execution_mode: code_change
owned_files:
- .github/workflows/service.yml
- tests/test_service_workflows.py
- tests/test_ci_gate_workflows.py
role: implementer
tags: []
tracker_refs: []
---
# Work Package Prompt: WP10 - write-core-parity CI drift job

## ⚡ Do This First: Load Agent Profile

Load your assigned profile before reading anything else: `/spk-charter-profile-load python-pedro`
(or `spec-kitty agent profile show python-pedro`). Adopt its identity, scope and boundaries.

## Objective

Add the CI job that keeps the mission's three oracles honest on every pull request (FR-003,
FR-015, NFR-005): `write-core-parity` checks out core at the pin, runs WP01's
`generate_status_append.py --check` and WP04's `generate_transition_parity.py --check` under core's
own locked interpreter, runs WP03's `generate_fromisoformat_vectors.py --check` under Python 3.12,
and fails on any drift. It never runs in a release run (it executes core's code), it is a need of
`service-gate`, and every action is pinned by a 40-hex SHA. The detect paths learn the new fixture
trees so a fixture-only change still runs the Gradle build and this job.

This WP was split out of WP04 by orchestrator ruling R2. You own the whole of
`.github/workflows/service.yml` except the `lock-interop` job block, which WP02 edits as a declared
out-of-map change (an extra `--tests` filter, a result-count step and an artifact path).

## Context

- Spec: FR-003 (CI diffs the `status_append` expected bytes), FR-015 (a CI job fails on corpus
  drift), NFR-005 (every action in a changed workflow pinned by a 40-hex SHA), C-001 (core only
  test-only, never in a release run), SC-006 (the PR's new checks are part of `service-gate`).
- Plan: "The parity oracle (FR-015)": `write-core-parity` checks out core at the pin as
  `lock-interop` does, runs both generators with `--check`, is skipped on release runs and is added
  to `service-gate`'s `needs`.
- Research: §9 (run core in CI: `uv sync --frozen`, pack sync; the release-run exclusion), §11.13.
- The generators (read their `--help` and module docstrings first):
  `scripts/conformance/generate_status_append.py` (WP01),
  `scripts/conformance/generate_transition_parity.py` (WP04),
  `scripts/conformance/generate_fromisoformat_vectors.py` (WP03). The parity corpus lives at
  `src/spec_kitty_events/conformance/status_transition_parity/` (ruling R1); `status_append` stays
  under `src/spec_kitty_events/conformance/fixtures/status_append/`.
- Existing seams:
  - `.github/workflows/service.yml:480-580` (`lock-interop`: the "Require the core pin" step, the
    two `actions/checkout` steps, `astral-sh/setup-uv` with `version: "0.13.6"` and
    `python-version: "3.12"`, `uv sync --frozen` in `core`, the pack sync, the guard
    `inputs.release-version == ''`); `:20-40` the push paths; `:107-137` the detect `PATTERN`;
    `:663-680` `service-gate` (`needs` and the skipped-is-success loop).
  - `tests/test_service_workflows.py:30` (`SERVICE_JOBS`), `:70-85` (SHA pins and version
    comments), `:120` (jobs guarded by detect), `:145-150` (release runs execute no core code),
    `:173-180` (the gate needs every job), `:425` (actionlint).
  - `tests/test_ci_gate_workflows.py:300-316` (push paths agree with `PATTERN`; jobs skip through
    detect).

## Branch Strategy

Planning happens on `kitty/mission-events-write-core-01M6TP6W` in an owned checkout
(`single_branch` topology). The mission lands as one PR to `main` from `issue-160-write-core`.
`spec-kitty agent action implement WP10 --agent <name> --mission events-write-core-01M6TP6W`

## Ground rules

- ATDD, red first: extend the workflow tests (T058) before the workflow and see them fail on the
  missing job.
- Every action pinned by a 40-hex SHA with its version comment; reuse the exact pins already in
  the file, add no new action.
- No dependency on core outside this CI job; the job never runs when `inputs.release-version` is
  set.
- Do not touch the `lock-interop` block beyond keeping WP02's edit intact; if a workflow test pins
  that block, update the test for WP02's change.
- Lint: `uv run ruff check . && uv run ruff format --check .`; complexity of any helper you add:
  `uv run ruff check --select C901 --config 'lint.mccabe.max-complexity = 15' <files>`.
- Do not run `make test-full`.
- Record the test commands you ran and their pass counts in your final commit message, and the URL
  of the first green `write-core-parity` run once the PR exists.

## Subtasks

### T026 The `write-core-parity` job

**Files**: `.github/workflows/service.yml`.

**Steps**:
1. New job `write-core-parity` (one `ubuntu-24.04` leg, `timeout-minutes: 30`), modelled on
   `lock-interop`: `needs: detect`; `if: needs.detect.outputs.service == 'true' &&
   needs.detect.outputs.run == 'true' && github.event_name != 'schedule' &&
   inputs.release-version == ''`; the "Require the core pin" step; `actions/checkout` twice (this
   repo; `spec-kitty/spec-kitty` at `needs.detect.outputs.core-sha` into `core/`, both with
   `persist-credentials: false`); the same pinned `setup-uv` (`version: "0.13.6"`,
   `python-version: "3.12"`); `uv sync --frozen` in `core`; the pack sync if core's imports need
   it (WP01's and WP04's commit messages say whether they did).
2. Steps, each failing the job on drift and each printing the generator's summary to
   `$GITHUB_STEP_SUMMARY`:
   - `SPEC_KITTY_CORE_CHECKOUT="$GITHUB_WORKSPACE/core" core/.venv/bin/python scripts/conformance/generate_status_append.py --check`
   - `SPEC_KITTY_CORE_CHECKOUT="$GITHUB_WORKSPACE/core" core/.venv/bin/python scripts/conformance/generate_transition_parity.py --check`
   - `uv run --python 3.12 --no-project python scripts/conformance/generate_fromisoformat_vectors.py --check`
     (WP03's vectors; no core needed, but this is the drift job).
3. A comment block above the job like the one above `lock-interop`: why it runs core (the oracles
   are core's behaviour at the pin), why it never runs in a release run, and that it is a hard need
   of `service-gate`.

### T057 `service-gate` and the detect paths

**Files**: `.github/workflows/service.yml`.

**Steps**:
1. `service-gate.needs`: add `write-core-parity`.
2. Detect paths: the Java runners (WP05, WP06, WP07, WP08) read the new fixture trees and the
   generators live under `scripts/conformance/`, so a change there must run the Gradle build and
   this job. Add `src/spec_kitty_events/conformance/fixtures/status_append/**`,
   `src/spec_kitty_events/conformance/status_transition_parity/**` and `scripts/conformance/**` to
   `on.push.paths` **and** to the detect `PATTERN` (they must agree:
   `tests/test_ci_gate_workflows.py:300-308`).
3. Leave the `nightly-corpus` schedule alone (the new job is skipped on `schedule`).

### T058 Workflow tests

**Files**: `tests/test_service_workflows.py`, `tests/test_ci_gate_workflows.py`.

**Steps** (write them first):
1. Add `write-core-parity` to `SERVICE_JOBS` (`tests/test_service_workflows.py:30`) and to the
   detect-guard list (`tests/test_ci_gate_workflows.py:314`).
2. Extend `test_release_runs_execute_no_core_code` (`:145-150`) to the new job's `if`.
3. A test that the job runs each of the three `--check` commands (assert on the step `run` texts),
   and that its checkouts set `persist-credentials: false`.
4. `test_gate_needs_every_job_and_tolerates_skips` (`:173-180`) already requires the gate to need
   every job; confirm it fails before T057 and passes after.
5. Run `actionlint` through the existing test (`:425`) if it is available locally.

## Test strategy

```bash
uv run pytest tests/test_service_workflows.py tests/test_ci_gate_workflows.py -q
uv run ruff check . && uv run ruff format --check .
# Locally, the three commands the job runs (core worktree at 512567ee, as WP04 T022 step 5 sets it up):
SPEC_KITTY_CORE_CHECKOUT="$CORE" "$CORE/.venv/bin/python" scripts/conformance/generate_status_append.py --check
SPEC_KITTY_CORE_CHECKOUT="$CORE" "$CORE/.venv/bin/python" scripts/conformance/generate_transition_parity.py --check
uv run --python 3.12 --no-project python scripts/conformance/generate_fromisoformat_vectors.py --check
```

## Definition of Done

- [ ] `write-core-parity` exists, runs the three `--check` commands, is skipped on release and
      scheduled runs, and is in `service-gate.needs`.
- [ ] Push paths and the detect `PATTERN` include the two fixture trees and `scripts/conformance/`,
      and agree.
- [ ] Every action is SHA-pinned with its version comment; no new action.
- [ ] The workflow tests cover the job, the release exclusion and the paths, and are green.
- [ ] The three commands exit 0 locally against core at the pin; the final commit message lists
      the commands and counts.

## Risks

- **Release leakage**: the job executes core's code; the `inputs.release-version == ''` guard and
  its workflow test are mandatory.
- **Detect drift**: a push path without the matching `PATTERN` entry fails
  `tests/test_ci_gate_workflows.py`.
- **Merge order**: WP02 edits the `lock-interop` block of the same file; keep its change intact
  when you rebase.

## Reviewer guidance

- Diff the workflow: the new job, `service-gate.needs`, the paths; nothing else moved except
  WP02's declared `lock-interop` edit.
- Check every `uses:` line in the new job reuses an existing SHA pin.
- Check the release-run test really reads the new job's `if`.
