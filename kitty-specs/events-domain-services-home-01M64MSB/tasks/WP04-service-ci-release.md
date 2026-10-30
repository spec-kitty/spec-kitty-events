---
work_package_id: WP04
title: Service CI and release workflows
dependencies:
- WP03
requirement_refs:
- FR-008
- NFR-003
- C-003
- C-004
planning_base_branch: issue-147-events-services-home
merge_target_branch: issue-147-events-services-home
branch_strategy: Planning artifacts for this mission were generated on issue-147-events-services-home. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into issue-147-events-services-home unless the human explicitly redirects the landing branch.
subtasks:
- T015
- T016
- T017
- T018
- T019
history:
- timestamp: '2026-10-29T20:40:00Z'
  lane: planned
  agent: system
  action: Prompt generated via /spec-kitty.tasks
agent_profile: java-jenny
authoritative_surface: .github/workflows/service
create_intent:
- .github/workflows/service.yml
- .github/workflows/service-release.yml
- scripts/release/service-release-assets.sh
- tests/test_service_workflows.py
execution_mode: code_change
owned_files:
- .github/workflows/service.yml
- .github/workflows/service-release.yml
- scripts/release/service-release-assets.sh
- tests/test_service_workflows.py
role: implementer
tags: []
tracker_refs: []
---
# Work Package Prompt: WP04 – Service CI and release workflows

## ⚡ Do This First: Load Agent Profile

Load your assigned profile before reading anything else: `/spk-charter-profile-load java-jenny`
(or `spec-kitty agent profile show java-jenny`). Adopt its identity, scope and boundaries.

## Branch Strategy

The planning base and the merge target are both `issue-147-events-services-home`; the PR targets `main`. The workspace comes from `lanes.json`.
`spec-kitty agent action implement WP04 --agent claude --mission events-domain-services-home-01M64MSB`

## Objective

Add the service CI and the `service-v*` release workflow now. The binding's Gradle job runs immediately. Every job that needs `service/` or the imported contracts is guarded, so it no-ops until the cutover import (WP07) and turns on with no workflow edit.

## Context (read-only references)
- **Core**: `/home/user/spec-kitty/spec-kitty/.github/workflows/mission-status-api.yml` (644 lines). Jobs: `contract-bundle`, `jvm`, `native` (matrix), `lock-interop`, the `mission-status-api-gate` terminal job, `release`, `nightly-corpus`. This is the source for the native matrix, GraalVM setup, PyYAML install on macOS/Windows, the lock-interop venv and attestation.
- **Charter**: `/home/user/spec-kitty/spec-kitty-charter/.github/workflows/service.yml` and `contract-release.yml`. These are the source for the verify job shape, the tag regex gate, ancestor-of-main, the read-only assemble job vs the write publish job, `sha256sum --check --strict`, the tag re-check and `gh release create --verify-tag`.
- **The asset contract**: `kitty-specs/events-domain-services-home-01M64MSB/contracts/service-release-assets.md`. Names are exact.
- **Approved action SHAs.** Reuse only these, and copy the version comment:
  - `actions/checkout@08c6903cd8c0fde910a37f88322edcfb5dd907a8` # v5.0.0 (core). The charter uses `fbc6f3992d24b796d5a048ff273f7fcc4a7b6c09` # v5.1.0; pick one and use it consistently.
  - `actions/setup-java@b6effb05e454b25005698d916606bdc6ffcbf961` # v5.7.0
  - `graalvm/setup-graalvm@27b360dbb857524eaf0b4c4cef364d38e5b4f173` # v1.6.7
  - `gradle/actions/setup-gradle@3f5f9adaf7d9fecd50b5935e54106014257a94e6` # v6.4.0
  - `gradle/actions/wrapper-validation@3f5f9adaf7d9fecd50b5935e54106014257a94e6` # v6.4.0
  - `astral-sh/setup-uv@20cfd1bf945f4377ade1205e4dbc17946fc9a30d` # v10.0.1
  - `actions/setup-python@83679a892e2d95755f2dac6acb0bfd1e9ac5d548` # v6.1.0
  - `actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a` # v7.0.1
  - `actions/download-artifact@9000827ccba6bdab643e8b6fd33ac0654aef8333` # v8.0.2
  - `actions/attest-build-provenance@4d101475d8b20a2381f78447822ac1eab6504dd8` # v4.2.2

## Subtasks

### T015 `service.yml`
- **Triggers**:
  - `pull_request` and `push` to `main`, with `paths`: `service/**`, `contracts/**`, `java/**`, `build-logic/**`, `gradle/**`, `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradlew`, `gradlew.bat`, `src/spec_kitty_events/producer_contract/**` (the binding reads it), and the workflow itself
  - `workflow_dispatch`
  - `schedule` (nightly, minute not 0, e.g. `41 3 * * *`), for the corpus job
  - `workflow_call`, so the release workflow can reuse it
- Top-level `permissions: contents: read`. Set `concurrency` per ref.
- **Job `gradle`** (ubuntu-24.04): checkout, wrapper-validation, setup-java temurin 25, setup-gradle, `./gradlew --no-daemon verifyAll`. Upload test reports on failure. This job runs now.
- **Job `detect`**: outputs `service=true` when `service/build.gradle.kts` exists (`test -f` in a step; use `hashFiles` in `if:` where it is allowed). Service jobs `needs: detect` and use `if: needs.detect.outputs.service == 'true'`.
- **Job `contract-bundle`** (guarded): uv, then `uv run --frozen python contracts/tools/bundle.py --root contracts --out "$RUNNER_TEMP/bundle-out" --bundle-only` for every module, then upload. **Check that events' `uv.lock` has PyYAML**: pyproject already lists `pyyaml>=6.0,<7`.
- **Job `lock-interop`** (guarded):
  - read `service/core-pin.json` (jq `.sha`)
  - checkout `spec-kitty/spec-kitty` at that SHA into `core/` (`persist-credentials: false`)
  - create a uv venv from core's lock
  - run `./gradlew :event-log:test --tests '*LockInteropSpikeTest'` with `SPEC_KITTY_LOCK_INTEROP_PYTHON` and the peer path, as core's job does. In core's workflow, read how the peer script path is passed, and adapt it to `core/scripts/ci/lock_interop_peer.py`. If the test hard-codes a relative path, note in a comment that WP07 adds a property or env for it.
- **Job `nightly-corpus`** (guarded, `schedule` or `workflow_dispatch` only): the same core checkout, `bootJar`, then core's `scripts/ci/mission_status_corpus_compare.py`.
- **Job `service-gate`**: the terminal job, `needs` all of the above, `if: always()`. It fails when any needed job failed; skipped counts as success.

### T016 Native matrix (in `service.yml`, guarded)
- Matrix `{target, os}`: linux-x64/ubuntu-24.04, linux-arm64/ubuntu-24.04-arm, macos-arm64/macos-15, windows-x64/windows-2025.
- setup-graalvm (`java-version: '25.0.2'`, `distribution: 'graalvm-community'`), then `./gradlew --no-daemon :mission-status-api:nativeCompile` plus core's native smoke and golden replay tasks (mirror core's `native` job steps; it runs `nativeTagJvmTest`/`nativeSmoke`).
- PyYAML on macOS/Windows: mirror core's hash-verified install.
- Rename the binary to `mission-status-api-${VER}-${target}[.exe]`, where `VER` comes from the tag on release and `0.0.0-dev+<sha7>` otherwise. Upload it as an artifact `native-${target}`.
- Record the binary size in the job summary (`$GITHUB_STEP_SUMMARY`), so WP08 can read it.

### T017 `scripts/release/service-release-assets.sh <tag> <outdir> <native-artifacts-dir>`
- `set -euo pipefail`. Validate `<tag>` against `^service-v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$` and derive `VER`.
- Build these deterministic tarballs, with `SOURCE_DATE_EPOCH` set to the commit time (`git log -1 --format=%ct`), `tar --sort=name --owner=0 --group=0 --numeric-owner --mtime=@$SOURCE_DATE_EPOCH -cf - … | gzip -n`:
  - `contract-bundles-$VER.tar.gz` from the bundle-out `bundle/` tree
  - `contract-tools-$VER.tar.gz`: `contracts/tools/*.py`, `contracts/tools/pins.json`, `contracts/tools/enum_pins.json` only
  - `conformance-fixtures-$VER.tar.gz`: the `service/` relative paths from the contract
- Copy in the 4 natives and `mission-status-api-$VER.jar` (from `bootJar`).
- Write a `.sha256` for each asset and `SHA256SUMS` (sorted). Then verify the asset set equals the contract list exactly: fail on a missing or extra file.
- Make it runnable locally against a fake tree, so it can be tested (T019).

### T018 `service-release.yml`
- **Triggers**: `push: tags: ['service-v*']` and `workflow_dispatch` (input `tag`, rehearsal only: never publishes).
- **Job `gate`**:
  - regex check of the tag
  - `git merge-base --is-ancestor "$GITHUB_SHA" origin/main` (fetch-depth 0)
  - outputs `ver` and `prerelease` (true when `VER` contains `-`)
- **Job `build`**: `uses: ./.github/workflows/service.yml` (workflow_call) with an input `release-version`, so the natives are named with the real version.
- **Job `assemble`** (read-only): download the artifacts, run `service-release-assets.sh`, upload `service-release`.
- **Job `publish`**:
  - runs only on tag pushes
  - `permissions: contents: write, id-token: write, attestations: write`
  - steps:
    1. download the assets
    2. `sha256sum --check --strict SHA256SUMS`
    3. attest-build-provenance with `subject-path` covering every non-checksum asset
    4. re-check through the `gh api repos/${{ github.repository }}/git/ref/tags/$TAG` that the tag still points at `GITHUB_SHA`
    5. `gh release create "$TAG" --verify-tag --title "$TAG" [--prerelease] --notes-file notes.md dist/*`. The notes state the package version from `pyproject.toml` that the build was tested against (ADR 0004).
- `concurrency: service-release-${{ github.ref }}`, `cancel-in-progress: false`.
- Guard: if `service/` does not exist, `gate` fails with a clear message ("service-v* releases need the cutover import").

### T019 Static checks (`tests/test_service_workflows.py`, pytest; it runs in `make test-fast`)
- Parse both workflows with PyYAML.
- Assert every `uses:` matches `@[0-9a-f]{40}` (local `./` reusable workflows excepted).
- Assert `service-release.yml` triggers only on `service-v*` tags, and `publish-pypi.yml` tag filters don't match `service-v1.0.0`. Use `fnmatch` on its patterns; GitHub glob `v*` does not match `service-v1.0.0`. Encode that as a test with the comment.
- Assert the asset name list in `service-release-assets.sh` equals the contract table: parse the contract markdown table's backticked names from `kitty-specs/events-domain-services-home-01M64MSB/contracts/service-release-assets.md`, or embed the list once and cross-check.
- Run the assets script against a temp fake tree, assert the exact file set, and assert that running it twice gives identical sha256 sums (determinism).
- If `actionlint` is available, also run it and record the result. It is not required.

## Definition of Done
- Both workflows exist, every `uses` is SHA-pinned, and the service jobs are guarded.
- `tests/test_service_workflows.py` passes. `make test-fast`, the conformance gate and ruff are green.
- The hand-off lists the job graph and states which jobs run today (`gradle`, `detect`, `service-gate`).

## Risks
- `hashFiles` is not available in a job-level `if` everywhere, so use the `detect` job output.
- A reusable workflow plus a matrix artifact naming collision: use unique artifact names.
- macos-15/arm64 and ubuntu-24.04-arm availability for the org: note it if unknown. The release is not attempted before cutover.
