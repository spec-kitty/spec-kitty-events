---
work_package_id: WP06
title: Service usage guides, cutover runbook and changelog
dependencies:
- WP01
- WP03
- WP04
requirement_refs:
- FR-007
- FR-009
- C-001
- C-005
planning_base_branch: issue-147-events-services-home
merge_target_branch: issue-147-events-services-home
branch_strategy: Planning artifacts for this mission were generated on issue-147-events-services-home. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into issue-147-events-services-home unless the human explicitly redirects the landing branch.
subtasks:
- T026
- T027
- T028
- T029
history:
- timestamp: '2026-10-29T20:40:00Z'
  lane: planned
  agent: system
  action: Prompt generated via /spec-kitty.tasks
agent_profile: scribe-sally
authoritative_surface: docs/guides/
create_intent:
- docs/guides/running-the-service.md
- docs/guides/service-protocol-and-releases.md
- docs/guides/service-cutover-runbook.md
- docs/changelog/2026-10-29-events-services-home.md
execution_mode: code_change
owned_files:
- docs/guides/running-the-service.md
- docs/guides/service-protocol-and-releases.md
- docs/guides/service-cutover-runbook.md
- docs/guides/README.md
- docs/changelog/2026-10-29-events-services-home.md
- docs/changelog/README.md
- CHANGELOG.md
role: implementer
tags: []
tracker_refs: []
---
# Work Package Prompt: WP06 – Service usage guides, cutover runbook and changelog

## ⚡ Do This First: Load Agent Profile

Load your assigned profile before reading anything else: `/spk-charter-profile-load scribe-sally`
(or `spec-kitty agent profile show scribe-sally`). Adopt its identity, scope and boundaries.

## Branch Strategy

The planning base and the merge target are both `issue-147-events-services-home`; the PR targets `main`. The workspace comes from `lanes.json`.
`spec-kitty agent action implement WP06 --agent claude --mission events-domain-services-home-01M64MSB`

## Context
- ADRs 0002–0004 (WP01). The Gradle build (WP03). The workflows `service.yml`, `service-release.yml` and `scripts/release/service-release-assets.sh` (WP04).
- In the mission folder: `research.md` R-1, R-2, R-5, R-8 and `data-model.md` (the IssueMove table).
- In core (read-only): `services/mission-status-api/README.md`, covering how to run it, routes, paging, stream, allowlists and release.
- The format to follow is existing guides such as `docs/guides/contributing.md`. Changelog conventions: `CHANGELOG.md` and `docs/changelog/` (see the 11.3.0 entry `2026-10-27-11-3-0-cloudevents-registry-upcaster.md`).

## Subtasks

### T026 `docs/guides/running-the-service.md` (kind: Guide, doc_status: draft until cutover)
- Prerequisites: JDK 25, or a released native binary.
- Two ways to run it:
  - from a release: download `mission-status-api-<ver>-<target>`, verify with `sha256sum -c` and `gh attestation verify --repo spec-kitty/spec-kitty-events`, then run it
  - from source after cutover: `./gradlew :mission-status-api:bootRun` or `nativeCompile`
- Configuration: take properties from core's service README and `application.properties`, quoting the names and not inventing any. Cover the read routes (link the OpenAPI) and the CI-only use in core.

### T027 `docs/guides/service-protocol-and-releases.md`
- **The CLI↔service protocol in practice**: the endpoint file, the token, the handshake and the refusal codes. Link the architecture page (WP05) and core ADR 2026-10-27-1. This page is the how-to; the architecture page is the why.
- **Releases**:
  - the two release lines (ADR 0004)
  - the `service-v*` asset table, linked to ADR 0003
  - how core pins a release: tag plus sha256 per asset, and the first-run fetch with digest verification
  - how `acceptance-jvm` fetches `contract-tools`
  - the steps a maintainer takes to cut a release. Pushing the tag needs operator approval; follow the `service-release.yml` gates.

### T028 `docs/guides/service-cutover-runbook.md` (FR-009)
A step-by-step maintainer procedure, numbered, with each step's command and expected output.
1. **Preconditions**:
   - the operator's go-signal is recorded
   - the active core missions are merged
   - core's freeze gate has landed
   - the frozen core SHA is chosen and written down
2. **Extraction (scratch)**: the full `git clone --no-local` (not shallow) at the frozen SHA, `pip install git-filter-repo` (2.47.0 used in the dry run), then the exact filter-repo command from research R-2. Verification: `git rev-list --count HEAD` (141 at 4eecb124, ≥141 later), and `diff -r` of `service/` against core `services/mission-status-api/` at the SHA, and of each contracts path.
3. **Merge**:
   - `git remote add core-extract <scratch>`
   - `git fetch core-extract`
   - `git merge --allow-unrelated-histories core-extract/<branch>`
   - resolve `contracts/README.md` (keep the events prose and add a "Service contracts" section)
   - merge message: `Import events-domain services from spec-kitty/spec-kitty@<SHA> (#147)`, with the filter-repo command in the body
4. **Post-import edits**: the path fixes (R-1), the root Gradle integration (WP07 T032), `service/core-pin.json` and the guarded jobs turning on, plus moving `docs/architecture/event-ledger-writers-and-readers.md` into `docs/architecture/services/` with `git mv` so history is kept.
5. **Verification**: the full Java suite and the Python gates, with the counts recorded.
6. **Core handshake**: send the core session the tag, asset names and sha256 sums (WP08).
7. **Issue moves**:
   - include the IssueMove table from data-model.md
   - include the label pre-creation step
   - transfer the epic and its children together
   - for split issues, give the re-file template (title prefix, back-link, forward-link comment)
   - close #6692 with a link to ADR 0002

   **Every step needs operator approval first.**
8. **Rollback**: before the PR merges, delete the branch. After a merge, revert the merge commit. Never force-push `main`.
9. **If the freeze is breached** after the frozen SHA: re-run from the new SHA, never cherry-pick.

Validate the runbook by re-running steps 2–3 yourself in a scratch dir (never pushed) at core `4eecb124`. Record the commit count and the clean diff in the hand-off.

### T029 Index, changelog, checks
- Add the three guides to `docs/guides/README.md`.
- Under `## [Unreleased]` in `CHANGELOG.md`, list: the ADRs 0002–0004, the charter amendment, the Gradle build replacing Maven for the binding, doc-toolkit 0.5.0, the service workflows (inert until cutover), and the guides and runbook. **Do not bump the package version**: there is no package change.
- Add `docs/changelog/2026-10-29-events-services-home.md` in the existing entry format, and index it if `docs/changelog/README.md` lists entries.
- Run both doc-kitty checks: 0 errors.

## Definition of Done
- The 3 guides, the changelog entries and the indexes exist. The runbook has been re-validated against a scratch dry run. The doc checks show 0 errors.
