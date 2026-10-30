# Research: events domain services home

Phase 0 sources:
- core `spec-kitty/spec-kitty` @ `4eecb12414dac53432f4af21ba958d199d5ce808`
- `spec-kitty/spec-kitty-charter` @ `64d7192`
- `spec-kitty/doc-kitty` (tag `v0.5.0`)
- a `git filter-repo` dry run in a scratch clone, never pushed
- issue states from core's GitHub API, relayed by the core-side session on 2026-10-29

## R-1 Layout and `contracts/` coexistence
- **Decision**:
  - `service/` ← core `services/mission-status-api/`
  - `contracts/{mission-status,mission-write,_shared,tools,lint,gradle,build.gradle,settings.gradle}` ← core `contracts/` (operator, D1)
  - The events repo already has `contracts/` (6 Markdown files for the Python package). The only path collision is `contracts/README.md`, resolved in the cutover merge commit by keeping the events prose and adding a "Service contracts" section from core's README.
- **Rationale**: the operator chose `service/`. Keeping `contracts/` at the root keeps `../contracts` one hop from `service/`.
- **Required post-import edits**:
  - `service/build.gradle.kts:72`: `../../contracts` → `../contracts`
  - `ContractDocumentTest.java:38`: `../../contracts` → `../contracts`
  - `ArchitectureControlsTest.java:174` uses a synthetic `file:/w/services/mission-status-api/...` URL. Verify at import time that it does not depend on the real path.
- **Alternatives**: keep `services/mission-status-api/` (zero edits). The operator rejected it in favour of the charter-like `service/`.

## R-2 History-preserving extraction
- **Decision**: `git filter-repo` on a fresh `--no-local` clone of core at the frozen SHA. Then `git merge --allow-unrelated-histories` into the events topic branch, with the SHA in the merge message.
  ```
  git filter-repo \
    --path services/mission-status-api/ \
    --path contracts/mission-status/ --path contracts/mission-write/ --path contracts/_shared/ \
    --path contracts/tools/ --path contracts/gradle/ --path contracts/lint/ \
    --path contracts/README.md --path contracts/build.gradle --path contracts/settings.gradle \
    --path docs/architecture/event-ledger-writers-and-readers.md \
    --path-rename services/mission-status-api/:service/
  ```
- **Dry-run evidence (core 4eecb124)**:
  - 141 commits, no merges, root `999a41f97` (2026-10-03), tip `95e201f`
  - 1,904 files, 1.35 MiB
  - `diff -r` against the source tree is clean
  - all history falls after the shallow boundary; a full clone is used at cutover anyway
- **Caveat**: `docs/architecture/event-ledger-writers-and-readers.md` is imported with history. It is then moved into DocKitty format under `docs/architecture/services/` in a follow-up commit, so `git log --follow` keeps working.
- **Alternatives**: `git subtree split` handles one prefix only and would need several splits for the contract paths. A plain copy loses history, which D2 rejects.

## R-3 Gradle unification
- **Decision**: a root Kotlin DSL build with an included `build-logic/` and Gradle 9.8.1. This follows spec-kitty-charter: `settings.gradle.kts` with `pluginManagement { includeBuild("build-logic") }`, `RepositoriesMode.FAIL_ON_PROJECT_REPOS`, mavenCentral only, a version catalog, and strict `verification-metadata.xml`.
- **Binding now**: `:java-binding` (projectDir `java/`).
  - `processResources` copies `src/spec_kitty_events/producer_contract/{*.json,data/*.json,composed/rows/*.json,composed/read/*.json}` into `spec_kitty_events/producer_contract`, exactly like the pom.
  - The test task passes system property `upcast.cases` (the path to `producer_contract/fixtures/upcast/cases`, as the pom does) and runs on the JUnit Platform.
  - `testRuntimeOnly` adds `junit-platform-launcher` and `slf4j-nop`.
  - Compiler flags are `-Xlint:all -Werror`.
- **At cutover**:
  - The service build (Gradle 9.7.1, no build-logic, a 2,573-line verification metadata file) joins the root: its `settings.gradle.kts` include list moves to the root, and its catalog entries merge into the root catalog.
  - `contracts/` (Groovy `build.gradle`, Gradle 8.14.5 via `pins.json`) is re-wrapped onto 9.8.1. Its `gradle_pin_check.py` pins are updated to match.
- **Alternatives**: keep standalone builds per directory. That is rejected because it means several wrappers and catalogs.

## R-4 Native image and release
- **Precedent correction**: spec-kitty-charter has no GraalVM, no OS matrix and no attestation. The native and attestation design comes from core's `mission-status-api.yml`:
  - `graalvm/setup-graalvm@27b360dbb857524eaf0b4c4cef364d38e5b4f173` (v1.6.7), GraalVM CE 25.0.2
  - matrix: linux-x64 (`ubuntu-24.04`), linux-arm64 (`ubuntu-24.04-arm`), macos-arm64 (`macos-15`), windows-x64 (`windows-2025`)
  - `actions/attest-build-provenance@4d101475d8b20a2381f78447822ac1eab6504dd8` (v4.2.2)
- **Release gate**: taken from the charter's `contract-release.yml`:
  - the tag must match `^service-v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$`
  - the tag must be an ancestor of `origin/main`
  - assets are built in a read-only job
  - the publish job (`contents: write`, `id-token: write`, `attestations: write`) runs `sha256sum --check --strict`, re-checks that the tag still names the built commit, and runs `gh release create --verify-tag` (with `--prerelease` for a `-` suffix)
- **Reflection/serialization config**: none today. Hints are in code (`MissionStatusApplicationHints`, `@ImportRuntimeHints`) plus Spring AOT. Keep it that way; publishing the metadata the native tests generate as a review artifact is a cutover follow-up (runbook section 4).
- **Asset contract**: see `contracts/service-release-assets.md`.

## R-5 Coupling to core Python (D5)
- **Decision**: `service/core-pin.json` holds a 40-hex SHA of `spec-kitty/spec-kitty`. Pin bumps happen only through a reviewed PR.
- **Jobs that use it**:
  - lock-interop: a uv venv from the pinned core, `SPEC_KITTY_LOCK_INTEROP_PYTHON`, `scripts/ci/lock_interop_peer.py`
  - nightly corpus: `scripts/ci/mission_status_corpus_compare.py`
- **Goldens**: vendored test data. They are regenerated by a maintainer from the pinned core (`tests/contract/_mission_status_*.py --write`), and the PR records the SHA.
- **Charter**: needs a scoped exception to the Package Boundary (CI-only, SHA-pinned, never in shipped artifacts).

## R-6 Runbook location
- **Decision**: `docs/guides/service-cutover-runbook.md`, a DocKitty page with status `draft`. It is a maintainer procedure that should outlive the mission. The mission's `quickstart.md` links to it.

## R-7 doc-toolkit 0.5.0
- **Decision**: bump `site/package.json` from 0.4.0 to 0.5.0 and refresh the lockfile.
- 0.5.0 is the latest tag (`v0.5.0`, CHANGELOG 2026-10-25).
- The only breaking change is that `kind` is required on every page, including the bundle-root README (doc-kitty ADR-0065). All 44 events pages already carry `kind`.

## R-8 Issue moves at cutover
The table is in `data-model.md` (IssueMove). Two methods apply:
- **Transfer**: the epic and its children move together, so the sub-issue links survive. Matching labels are created in events first.
- **Split**: a new events issue is filed with a back-link, and a forward-link comment goes on the core issue.

## Supply-chain evidence (DIRECTIVE_051)

| Dependency change | (1) Registry authenticity | (2) Freshness | (3) Lifecycle scripts | (4) Lockfile / frozen install | (5) Incident / IoC posture |
|---|---|---|---|---|---|
| Gradle wrapper 9.8.1 (new) | `services.gradle.org` distribution, pinned with `distributionSha256Sum`; CI runs `gradle/actions/wrapper-validation` | already in production use by spec-kitty-charter; the release date is checked when the wrapper is generated | none (no install hooks) | wrapper properties are committed; `--offline` is not needed | wrapper-validation checks the jar against Gradle's published checksums |
| Binding deps (Jackson 3.2.4, json-schema-validator 3.0.8, JUnit 6.1.3, slf4j-nop 2.0.17): **unchanged versions**, moved from Maven to Gradle | Maven Central only (`FAIL_ON_PROJECT_REPOS`) | unchanged from the reviewed pom (PR #142) | n/a for Maven artifacts | `verification-metadata.xml` strict sha256 replaces `--strict-checksums` | no change in the dependency set; verification blocks substitution |
| `@spec-kitty/doc-toolkit` 0.4.0 → 0.5.0 | `npm.pkg.github.com` scope `@spec-kitty` (first-party) | v0.5.0 tagged 2026-10-25 (4 days old; first-party; changelog reviewed) | `npm ci` from the committed lockfile; lockfile diff reviewed for new transitive install scripts | the `package-lock.json` entry copied byte-for-byte from spec-kitty-charter's committed lockfile (no registry token here), then `npm ci` in CI verifies the sha512 | lockfile diff inspected for unexpected new packages |
| GraalVM / setup actions (CI only) | GitHub Actions pinned by 40-hex SHA, reusing SHAs already reviewed in core and spec-kitty-charter | as in those repos | n/a | SHA pins | SHA pinning blocks tag re-pointing |

Adversarial challenge: the toolchain change is a security-impacting dependency decision, so one adversarial-squad pass runs before the PR (programme §7). Its findings are recorded below as they are disposed.

### Adversarial findings disposition
Pre-PR squad, 2026-10-29: architect-alphonso (coherence), debugger-debbie (regressions on main), researcher-robbie (supply chain). No MAJOR finding.

| Finding | Disposition | Evidence / tracking |
|---|---|---|
| `contracts/` build model split between ADR 0003 and plan/overview | accepted | contracts joins the root build as `:contracts` at cutover: plan.md Engineering Alignment, runbook section 4.2, overview container diagram |
| Fixtures tarball "paths relative to `service/`" | accepted | ADR 0003 asset table and contracts/service-release-assets.md now say "entry names in the tarball" and link the layout table |
| Reachability metadata promised but not uploaded | changed | ADR 0003 Risks and R-4 now make it a cutover follow-up (runbook section 4.7) |
| `make test-full` would miss the services after cutover | deferred_with_rationale | no services exist before cutover; runbook section 4.7 points `test-full` at `verifyAll` |
| plan.md `hashFiles` and path-filter drift; overview "root project"; #6676 naming | accepted | plan.md, overview.md, data-model.md, runbook section 7 |
| lock-interop (core code) runs inside release runs | accepted | service.yml `lock-interop` guard `inputs.release-version == ''`; test `test_release_runs_execute_no_core_code` |
| Gradle cache restored into release runs | accepted | `cache-disabled: ${{ inputs.release-version != '' }}` on every setup-gradle; test `test_release_runs_restore_no_gradle_cache` |
| `gh attestation verify --repo` accepts any workflow or ref | accepted | `--signer-workflow` and `--source-ref` in release notes, ADR 0003 and the guides |
| publish uploads `dist/*` | accepted | publish uploads exactly the SHA256SUMS-listed assets; test `test_publish_uploads_only_the_listed_assets` |
| No `environment:` or tag ruleset protecting publish | deferred_with_rationale | a repository-settings change (environment with reviewers, `service-v*` tag ruleset) for the operator; must land before the first `service-v*` tag (WP08); raised in the PR |
| Tag move between recheck and release create | deferred_with_rationale | needs write access; closed by the tag ruleset above |
| CI lane needs services.gradle.org and plugins.gradle.org | deferred_with_rationale | the planning repo's `bin/ci-run.sh` is not readable here; raised in the PR for the CI agent |
| `.gitignore` duplicate build lines | deferred_with_rationale | harmless; `test_dockerignore` mirrors them; not worth a churn commit |
| Dockerfile `COPY RELEASE_NOTES.md` already broken on main | deferred_with_rationale | predates this branch; raised in the PR as a separate issue |
| Makefile comment "GitHub Actions are off programme-wide" vs the new workflows | deferred_with_rationale | programme policy (planning#274); the operator asked for these workflows; raised in the PR |
