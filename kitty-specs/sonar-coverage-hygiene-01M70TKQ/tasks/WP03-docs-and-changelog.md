---
work_package_id: WP03
title: Docs and changelog
dependencies:
- WP02
requirement_refs:
- FR-010
planning_base_branch: issue-238-sonar-coverage-hygiene
merge_target_branch: issue-238-sonar-coverage-hygiene
branch_strategy: Planning artifacts for this mission were generated on issue-238-sonar-coverage-hygiene. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into issue-238-sonar-coverage-hygiene unless the human explicitly redirects the landing branch.
subtasks:
- T008
- T009
history:
- timestamp: '2026-11-09T19:00:00Z'
  lane: planned
  agent: system
  action: Prompt generated via /spec-kitty.tasks
agent_profile: curator-carla
authoritative_surface: docs/guides/contributing.md
create_intent:
- docs/changelog/unreleased/238-sonar-coverage-hygiene.md
execution_mode: planning_artifact
owned_files:
- docs/guides/contributing.md
- docs/changelog/unreleased/238-sonar-coverage-hygiene.md
role: implementer
tags: []
tracker_refs: []
---
# Work Package Prompt: WP03 - Docs and changelog

## Do This First: Load Agent Profile

Load your profile before anything else: `spec-kitty agent profile show curator-carla`. Adopt its identity, scope and boundaries.

## Objective

Update the Sonar section of `docs/guides/contributing.md` and add one changelog fragment. Spec: FR-010. Read `kitty-specs/sonar-coverage-hygiene-01M70TKQ/spec.md`, then `git log -3 --stat` for WP01 and WP02 so the text matches what shipped.

## Subtasks

### T008 `docs/guides/contributing.md`
Find the Sonar section (added by #233). Update it, keeping its voice and the page frontmatter (bump `updated` to 2026-11-09; keep `description` at most 180 characters): the scan imports one JaCoCo XML per Gradle module with main sources (listed from `settings.gradle.kts` by `scripts/sonar_java_modules.py`); the collect step warns for a missing module report; library classpaths come from the Gradle `sonarLibraries` task (fallback to the `modules-2` glob with a warning); `sonar.test.inclusions=**/test_*.py` counts the packaged conformance tests as tests; steps use `!cancelled()`; the scan carries `SONAR_TOKEN` only; the step summary lists failed build steps; `tests/test_sonar_workflow.py` pins these. Do not invent anything WP01/WP02 did not do. Link files relative to the page like the neighbouring text does.

### T009 Changelog fragment
Create `docs/changelog/unreleased/238-sonar-coverage-hygiene.md` following `docs/changelog/unreleased/README.md`. Frontmatter `section: Changed`, then one bullet (bold impact-first lead, `(#234, #235)` refs, before to after; nested bullets allowed): every Java module now reports JaCoCo XML to the nightly Sonar scan, the scan resolves library types from Gradle classpaths, counts the conformance tests as tests, no longer scans after a cancel or passes an unused token, and reports failed build steps. Do not edit `CHANGELOG.md`.

## Ground rules

- Run `uv run python scripts/changelog.py check && uv run python scripts/changelog.py guard`. Do NOT run `npm run validate` (the orchestrator does, then cleans the generated index).
- Commit on the current branch with a message ending `Co-Authored-By: <your model> <noreply@anthropic.com>`. Do not push. Do not merge.
- Edit only the two files you own.
