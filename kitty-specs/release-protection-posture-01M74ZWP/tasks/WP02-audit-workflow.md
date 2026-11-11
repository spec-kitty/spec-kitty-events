---
work_package_id: WP02
title: Audit workflow and its guard tests
dependencies:
- WP01
requirement_refs:
- FR-001
- FR-002
- FR-003
- FR-015
- NFR-001
- C-005
- C-003
- C-006
planning_base_branch: issue-239-release-protection
merge_target_branch: issue-239-release-protection
branch_strategy: Planning artifacts for this mission were generated on issue-239-release-protection. This mission is single_branch; the work lands on issue-239-release-protection, which goes to main as one PR.
subtasks:
- T007
- T008
- T009
history:
- timestamp: '2026-11-11T11:35:00Z'
  lane: planned
  agent: system
  action: Prompt generated via /spec-kitty.tasks
agent_profile: implementer-ivan
authoritative_surface: .github/workflows/release-protection-audit.yml
create_intent:
- .github/workflows/release-protection-audit.yml
- tests/test_release_protection_workflow.py
execution_mode: code_change
model: haiku
owned_files:
- .github/workflows/release-protection-audit.yml
- tests/test_release_protection_workflow.py
- .github/workflows/service-release.yml
- AGENTS.md
- CLAUDE.md
role: implementer
tags: []
tracker_refs: []
---
# Work Package Prompt: WP02 — Audit workflow and its guard tests

## ⚡ Do This First: Load Agent Profile

Load your assigned profile before reading anything else: `/spk-charter-profile-load implementer-ivan`
(or `spec-kitty agent profile show implementer-ivan`). Adopt its identity, scope and boundaries.

## Objective

Run WP01's script on a schedule, with a token that can change nothing, and pin that shape with tests (#239 AC 1; FR-001–FR-003, FR-015).

Read first: `spec.md` (User Story 4, FR-001–FR-003, FR-015, C-004, C-005), `contracts/audit-cli-and-expected-settings.md`, `scripts/release/release_protection_audit.py` (WP01's CLI), `.github/workflows/sonar.yml` (an existing scheduled workflow) and `tests/test_service_workflows.py` (pinning helpers `_load`, `_on`, `_uses`, `SHA_PIN`; copy the patterns, do **not** edit that file).

## Branch Strategy

Planning base and merge target: `issue-239-release-protection` (single_branch). One PR to `main`. Run `spec-kitty agent action implement WP02 --agent <name> --mission release-protection-posture-01M74ZWP`.

## Subtasks

### T007 — `.github/workflows/release-protection-audit.yml`

Write exactly this shape. Keep the header comment informative: what it checks, D5–D8, and "red run = signal, no issue filed".

```yaml
# Release-protection audit (#239, closes #221): the compensating control for the paths the
# `service-release` environment does not hold (it guards only service-release.yml's `publish` job).
# Daily and on demand, read-only:
#  - the live release-protection settings (environments, the service-release environment's
#    reviewers and tag policy, the rulesets) must equal scripts/release/release-protection-expected.json;
#  - every service-v* release must be authored by github-actions[bot], and every non-checksum asset must
#    pass `gh attestation verify` as signed by service-release.yml at refs/tags/<tag>. A final release
#    fails the run; a prerelease only warns (D8).
# The token is contents: read: it cannot see the rulesets' bypass lists (accepted risk D6) and it can
# change nothing. A red run is the signal; nothing is filed. See docs/guides/service-protocol-and-releases.md,
# "Release-protection posture".
name: release-protection-audit

on:
  schedule:
    # daily at 06:41 UTC: off the :00/:30 marks, clear of sonar (04:17) and the canary (05:23)
    - cron: "41 6 * * *"
  workflow_dispatch: {}

permissions:
  contents: read

concurrency:
  group: release-protection-audit
  cancel-in-progress: false

jobs:
  audit:
    runs-on: ubuntu-24.04
    timeout-minutes: 30
    steps:
      - uses: actions/checkout@08c6903cd8c0fde910a37f88322edcfb5dd907a8 # v5.0.0
        with:
          persist-credentials: false
      - name: Audit the release-protection settings and the service-v* releases
        env:
          GH_TOKEN: ${{ github.token }}
          REPO: ${{ github.repository }}
        run: >-
          python3 scripts/release/release_protection_audit.py
          --repo "$REPO"
          --expected scripts/release/release-protection-expected.json
          --summary "$GITHUB_STEP_SUMMARY"
```

Check the CLI flags against WP01's actual `argparse`; adjust only if WP01 named them differently (it should not have).

### T008 — `tests/test_release_protection_workflow.py`

Load the YAML with PyYAML (`on` parses as `True`; use the `_on` helper pattern). Tests:

1. `test_audit_runs_only_on_schedule_and_dispatch`: the trigger keys are exactly `{"schedule", "workflow_dispatch"}`.
2. `test_audit_token_is_contents_read_only`: workflow-level `permissions == {"contents": "read"}`, and no job declares `permissions` (a job-level block replaces the workflow's and could widen it).
3. `test_every_audit_action_is_sha_pinned`: every `uses:` (walk the whole `jobs` tree) matches `^[^@\s]+@[0-9a-f]{40}$` and its line carries a `# v…` comment (copy `test_every_pin_carries_a_version_comment`'s approach); at least one `uses:` exists (positive control).
4. `test_audit_runs_the_audit_script_with_the_expected_file`: a step's `run` invokes `scripts/release/release_protection_audit.py` with `--expected scripts/release/release-protection-expected.json`, and both files exist.
5. **Negative controls (AC 1 non-vacuity)**: write the guard checks as functions taking the parsed workflow, then show each fails on a mutated copy, built from the same loaded YAML: add `push: {branches: [main]}` → trigger check fails; set `permissions: {contents: write}` → fails; add a job-level `permissions: {contents: write}` → fails; replace the checkout ref with `actions/checkout@v5` → fails. Use `pytest.raises(AssertionError)` or boolean checker functions; the point is the same checker that passes on the real file fails on each mutant.
6. `test_actionlint_accepts_the_audit_workflow`: skipped when `actionlint` is not installed (`shutil.which`), else `subprocess.run(["actionlint", str(path)], check=True)`.
7. `test_audit_timeout_bounds_the_run`: `timeout-minutes` ≤ 30 (NFR-001).

### T009 — Mentions (FR-015) [P]

- `.github/workflows/service-release.yml`: append to the header comment (lines 13–16) one sentence: the paths the environment does not hold (another workflow with `contents: write`, settings drift) are watched by `release-protection-audit.yml` (#221, #239). **Comment only**; change no YAML key. The existing `tests/test_service_workflows.py` must stay green.
- `AGENTS.md` and `CLAUDE.md` (identical opening paragraph): where the workflows are listed ("…and the daily CI canary (`ci-pr-lane-canary.yml`)…"), add the daily release-protection audit (`release-protection-audit.yml`, read-only) in the same sentence style. Keep both files' paragraphs identical to each other.

## Verification

```bash
uv run pytest tests/test_release_protection_workflow.py tests/test_service_workflows.py -q
uv run ruff check tests/test_release_protection_workflow.py && uv run ruff format --check tests/test_release_protection_workflow.py
```

Record commands and counts in the handoff note. `actionlint` may not be installed locally; say so if the test skipped.

## Definition of Done

- Workflow and tests exist; tests pass; mutants fail the same checkers.
- `service-release.yml` changed in its header comment only (`git diff` shows comment lines only).
- `AGENTS.md`/`CLAUDE.md` mention the audit identically.
- `spec-kitty agent tasks mark-status T007 T008 T009 --status done --mission release-protection-posture-01M74ZWP`.

## Risks

- A job-level `permissions:` block would replace the workflow default; the test forbids it.
- Do not add `actions: read` or `attestations: read`: the spike showed they add nothing (research.md §1).

## Reviewer guidance

Verify the SHA equals the one already pinned in `service-release.yml` (`08c6903cd8c0fde910a37f88322edcfb5dd907a8 # v5.0.0`), the trigger set, and that each guard has a failing mutant.
