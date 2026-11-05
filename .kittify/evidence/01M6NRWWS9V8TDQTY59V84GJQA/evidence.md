---
verdict: fail
mode: lightweight
reviewed_at: 2026-11-05T11:53:52.907852+00:00
findings: 1
gates_recorded:
  - id: gate_1
    name: wp_lane_check
    command: spec-kitty review (internal gate 1)
    exit_code: 0
    result: pass
  - id: gate_2
    name: dead_code_scan
    command: spec-kitty review (internal gate 2)
    exit_code: 1
    result: fail
  - id: gate_3
    name: ble001_audit
    command: spec-kitty review (internal gate 3)
    exit_code: 0
    result: pass
issue_matrix_present: not_applicable
mission_exception_present: not_applicable
---

## Findings

- **dead_code_baseline_missing** `LIGHTWEIGHT_REVIEW_MISSING_BASELINE`: This mission was accepted via PR (acceptance_mode: pr): the missing baseline_merge_commit means the PR merge was never recorded, not that the mission never merged. If the PR has merged, record the real merge commit with `spec-kitty migrate backfill-merge-commit --mission <slug> --merge-commit <sha>` (or re-run `spec-kitty accept --mode pr --merge-commit <sha>`); if it has not, merge it, then rerun review with `--mode post-merge`.

---

# Mission Review Report: events-domain-services-home-01M64MSB

**Reviewer**: claude, as reviewer-renata, with three read-only reviewer lenses: governance/build/docs, cutover/CI/release, and history/risk. Op `01M6NRWWS9V8TDQTY59V84GJQA`.
**Date**: 2026-11-05
**Mission**: `events-domain-services-home-01M64MSB`: the events domain owns its Java services (#147)
**Baseline commit**: none is recorded (see DRIFT-2). The truthful pre-landing tip is `50e84242`, the base of PR #148.
**HEAD at review**: `030f4ec8` (`main`)
**WPs reviewed**: WP01..WP08. The mission landed through PRs #148, #150, #154, #162 and #205, and releases `service-v0.1.0-rc.1` (`2f5e0394`) and `rc.2` (`14682f94`).

## About the frontmatter verdict

The `verdict: fail` above comes from `spec-kitty review --mode lightweight`, and its only finding is `dead_code_baseline_missing`. The CLI could not run in `--mode post-merge` because `meta.json` has no `baseline_merge_commit` (`MISSION_REVIEW_MODE_MISMATCH`).

That baseline cannot be recorded truthfully with the current tooling:
- PR #148 was rebase-merged as 6 commits on `50e84242`. `kitty-specs/<slug>/meta.json` first appears in the 5th of them, `5bd8eb94`, whose parent `1f322410` belongs to the same PR.
- `spec-kitty migrate backfill-merge-commit` accepts only `5bd8eb94`, and only with `--attest-first-landing-commit`. That attestation would be false, and it would anchor the baseline at `1f322410`, which leaves out WP01–WP06.

So the field stays absent and the frontmatter verdict stands as the tool computed it. The missing anchor did not hide dead code. I did the scan by hand instead (DEAD-1), and it found some:
- **Scope.** The CLI's scan covers every changed non-test `.py` file, inside or outside `src/`. NFR-001 holds (no mission PR changed `src/spec_kitty_events/`), but #150 imported 26 non-test, non-fixture Python modules under `contracts/tools/` and `scripts/ci/`.
- **What is live.** Only `bundle.py`, its `contract_resolver` import, and `scripts/ci/mission_status_gate_checks.py` have a caller from a workflow, the Makefile, a Gradle build or a test.
- **What is not.** The other 23 are referenced only by each other or in comments; #151 tracks them.

The manual verdict is below. #208 tracks the anchor.

## Gate Results

### Gate 1: Contract tests
- Command: `uv run pytest --pyargs spec_kitty_events.conformance` (this repo's packaged contract gate)
- Exit code: 0. 1200 passed.
- Result: PASS

### Gate 2: Architectural tests
- Command: `scripts/verify_all.sh --continue` (`make test-gradle`, which runs `./gradlew verifyAll` with the contract bundle). It covers the ArchUnit onion and boundary rules and the contract conformance tests of `service/`.
- Exit code: 1. 62 tasks ran and only `:mission-status-api:perfTest` failed, on one test: `Nfr001OverviewLatencyTest` cold first call, ratio 112.0 and then 104.1 against a limit of 70.0.
- Result: PASS (ArchUnit and conformance). The perfTest miss is not a regression, and it is not counted against this mission:
  - **The deciding evidence is CI.** The same `service/` tree passed `perfTest` in CI. `git diff 8dbfbf26 030f4ec8` touches only `kitty-specs/`, and `service.yml` run 41909590376 on `8dbfbf26` passed its `gradle` job (126853576981), which ran `:mission-status-api:perfTest` (not from cache).
  - **The local runs cannot decide either way.** Both had load 12.1 and 17.1 on 32 CPUs, from other sessions' JVM builds on the same machine. Their ratios are above even the planted 50 ms-regression figures (78.3–87.4, `PerfBudgets.java:48`), so on a contended machine the local gate cannot tell contention from a regression.
  - **The second run was not a retry into green.** It stayed red. Both measurements are recorded in #208 as calibration data for `NFR001_COLD_RATIO_LIMIT`, under core ADR 2026-10-05-2 and `docs/development/testing/testing-flakiness.md`.

### Gate 3: Cross-repo E2E
- Command: `cross-repo-harness-tests.yml` on `main`.
- Result: PASS. Run 41986236459 on `030f4ec8` succeeded. `acceptance` (41986236307) and `suite` (41986236334) are also green on `030f4ec8`. `docs-build` is green on `41fda9bc` (41890688332).

### Gate 4: Issue Matrix
- File: `kitty-specs/events-domain-services-home-01M64MSB/issue-matrix.json`
- Rows: 4. Empty or `unknown` verdicts: 0. `deferred-with-followup` rows without a handle: 0 (core #6676 names #6676 and #6915).
- `#92: not-applicable` is a terminal verdict in spec-kitty (`IssueMatrixVerdict.NOT_APPLICABLE`), although the mission-review skill lists only four.
- The `#6692` evidence is imprecise: #6692 closed at 2026-10-30T06:58Z, when core PR #6782 merged, and the comment linking ADR 0002 came on 2026-10-31.
- Result: PASS

## FR Coverage Matrix

| ID | Brief | WP | Evidence | Adequacy | Finding |
|---|---|---|---|---|---|
| FR-001 | ADR 0002, events is canonical | WP01 | `docs/adr/0002-…md`: Accepted; links the 4 core ADRs and charter ADR-0015, all of which resolve | ADEQUATE | DOC-1 |
| FR-002 | ADR 0003, build and release model | WP01 | `0003-…md:87-150`; the asset table equals `scripts/release/service-release-assets.sh:36-51` | ADEQUATE | — |
| FR-003 | ADR 0004, dual artifact line | WP01 | `0004-…md:82-111`; `checkVersionMatchesPyproject` enforces that the binding follows the package version | ADEQUATE | — |
| FR-004 | Charter amendment | WP02 | `1f322410`; amendment-log row of 2026-10-29; `AGENTS.md` lists the Gradle commands (`CLAUDE.md` is a symlink to it) | PARTIAL | GOV-1 |
| FR-005 | Root Gradle build; Maven gone | WP03 | `settings.gradle.kts`, the version catalog, strict `verification-metadata.xml`, `build-logic/`; no pom/mvnw/.mvn; `-Werror` and `upcast.cases` kept | ADEQUATE | GOV-2 |
| FR-006 | doc-toolkit 0.5.0 | WP05 | `edaeba0c`; docs-site run 41005166543 green; superseded by 0.6.0 in #201 (a separate mission) | ADEQUATE | — |
| FR-007 | Service docs plus the moved core material | WP05/06/07 | Architecture pages and guides present; `event-ledger-writers-and-readers.md` moved with history; README is `service/README.md`; **the lock-interop-spike report stayed in core** | PARTIAL | FR-1 (#207) |
| FR-008 | `service.yml` / `service-release.yml` | WP04 | Every `uses:` pinned to a SHA that resolves to its `# vX` tag; `detect` guard; run 41909590376 green | ADEQUATE | REL-2 |
| FR-009 | Cutover runbook | WP06 | Runbook covers filter-repo, path fixes, core pin, labels, issue table, handshake and freeze breach | ADEQUATE | §7 is stale (#207) |
| FR-010 | Import with history; build integration | WP07 | `1e9ec1d3`: `service/` tree equals core `512567ee:services/mission-status-api`; 141 commits; ArchUnit and conformance in `check`; Gate 2 | ADEQUATE | — |
| FR-011 | First `service-v0.x` pre-release; core pins it | WP08 | rc.1 prerelease, operator go on core #6117 (2026-10-31T13:49Z); 8 of 8 assets attested; core `pins/read-service.source.yaml` sha256 equal the release digests | ADEQUATE | — |
| NFR-001 | Python line untouched | all | `git diff --stat -- src/spec_kitty_events/` is empty for every mission PR range | MET | — |
| NFR-002 | Binding parity | WP03 | 366 = 366 (363 corpus cases plus 3 `@Test`); `java/` unchanged since `b286d6c8` | MET | — |
| NFR-003 | Supply chain | WP03/04 | Mission workflows fully SHA-pinned; wrapper sha256 equals services.gradle.org's; `verify-metadata=true` | MET | REL-3 |
| NFR-004 | Docs validity | WP05/06 | `npm run validate`: 232 files valid, 171 references resolve; docs-site runs green | MET | — |
| C-001 | No import before the go-signal | WP07/08 | Freeze #6782 merged 06:58:08Z, then the import at 07:05:50Z; tags after the 10-31 ruling | MET | REC-3 |
| C-002 | No core coupling in the build | WP03/07 | The core checkout happens only in `lock-interop` and `nightly-corpus`, never in release runs | MET | — |
| C-003 | Stable asset names | WP04/08 | rc.1 names are a subset of rc.2's; `command-cli` is additive | MET | REC-4 |
| C-004 | PyPI line separate | WP04 | `publish-pypi.yml` triggers on `v*`; only `service-release` ran for the rc tags | MET | — |
| C-005 | One issue, one branch, one PR | all | 5 operator-merged PRs; WP07 allowed a separate import PR | MET (deviation) | — |

## Drift Findings

### FR-1: FR-007's lock-interop-spike report was not moved
**Type**: PUNTED-FR. **Severity**: MEDIUM. **Spec reference**: FR-007 (`spec.md:136`).
**Evidence**:
- `git ls-files` has no `lock-interop-spike`. The report is in core at `docs/development/reference/lock-interop-spike.md`.
- `LedgerLock.java:10`, `LedgerLockTest.java:21` and `LockInteropSpikeTest.java:47` cite that path, which does not exist in this repo.
- `event-log-lock-and-append.md:24` links the report in core at `4eecb124`.

**Analysis**: two of the three named items moved. The acceptance matrix marks FR-007 `pass` and notes the deviation; the honest verdict is PARTIAL. Tracked by #207, which now also lists the third citation.

### DRIFT-1: mission records cite commits that are not on `main`
**Type**: RECORD-DRIFT. **Severity**: MEDIUM.
**Evidence**:
- `lanes.json` `planning_commit_sha` `d45c5071` is not in the local clone. GitHub still serves it, but it has diverged from `main` (13 ahead, 227 behind) and its branch is deleted.
- `meta.json` `accept_commit` `a21ed3db` and `accepted_from_commit` `5517c507` are reachable from no branch.
- `target_branch` `issue-147-events-services-home` has been deleted.

**Analysis**: `spec-kitty accept --diagnose` cannot check `implementation_evidence`. #205 disclosed this. Tracked in #208.

### DRIFT-2: no truthful `baseline_merge_commit` can be recorded
**Type**: RECORD-DRIFT. **Severity**: MEDIUM. See [About the frontmatter verdict](#about-the-frontmatter-verdict). Tracked in #208, and upstream through core #4277.

### GOV-1: the charter "regenerate" step was not performed
**Type**: RECORD-DRIFT. **Severity**: LOW.
**Evidence**:
- `charter.md:5` still says `Generated: 2026-04-20T07:35:23Z`.
- `charter.yaml` changed only by the 2 hand-added `catalog.languages` lines, and `framework: pytest` is unchanged.
- The acceptance matrix says "regenerated charter.md".

Tracked in #208.

### GOV-2: `contracts/build.gradle` stays Groovy
**Type**: LOCKED-DECISION VIOLATION (minor and deliberate). **Severity**: LOW.
**Evidence**: D3 and ADR 0003 decision 1 make the Kotlin DSL the single toolchain with no exception. The exception exists only in `plan.md:29` and the runbook. Tracked in #208.

### REC-1 to REC-4: record drift in the mission corpus
**Type**: RECORD-DRIFT. **Severity**: LOW.
- **REC-1**: the acceptance matrix has rows only for FR-001..FR-011. This review covers the NFRs and constraints.
- **REC-2**: events 146 and 149 still say `verdict: approved` for WP07 and WP08, which ran outside the WP loop. The log is append-only, and the corrected `review-cycle-1.md` files say so.
- **REC-3**: no go-signal record exists in the mission directory; only PR #150's body records it.
- **REC-4**: `contracts/service-release-assets.md` still lists `command-cli` as a future deployable.

REC-1 and REC-4 are tracked in #208. REC-2 and REC-3 are disclosed in #205.

### DOC-1: stale docs and changelog statements
**Type**: RECORD-DRIFT. **Severity**: LOW.
- `CHANGELOG.md:42-44` still says "refuses every tag until the cutover import".
- `docs/changelog/2026-10-29-events-services-home.md` is still titled "Unreleased".
- The rc.1 page uses `--check --strict`.
- The "#6782 merges" notes remain in `overview.md:21` and `0002-…md:218`.
- `write-service-protocol.md:36` (Host guard) and `:104` (parity fixture) disagree with the code.
- Spec US2's `./gradlew check` needs `CONTRACT_BUNDLE` since the cutover.
- The comment at `service.yml:545` is stale.

All tracked in #208. rc.2's missing `CHANGELOG.md` entry is #196.

### DEAD-1: 23 imported contract-tool modules have no live caller
**Type**: DEAD-CODE. **Severity**: MEDIUM. **Location**: `contracts/tools/*.py`, imported by #150 (`48be13c9..f75022f6`).
**Evidence**: #150 added 26 non-test, non-fixture Python modules. I traced each to a workflow, the Makefile, a Gradle build, `scripts/*.sh` or a Java test that runs it:
- **Live (3):**
  - `bundle.py`, run by `service.yml`, `docs-site.yml`, `scripts/verify_all.sh` and `ContractDocumentTest`;
  - `contract_resolver.py`, which `bundle.py` imports;
  - `scripts/ci/mission_status_gate_checks.py`, run by `service.yml`.
- **Not live (23):** every other module, including `breaking_check`, `structure_check`, `leak_scan`, `citation_check`, `verify_pins`, `gradle_pin_check`, `lint_check`, `tamper_check`, `client_smoke`, `resolver_parity` and `run_negative_cases`. They are reached only from each other or named in comments (`contracts/build.gradle:54,71`, `ArtifactPathRule.java:7`, `VerificationTamperTest.java:22`, `LeakClassesPythonParityTest.java:20`).

**Analysis**:
- This is the core contract-gate tooling, imported with its history but not its CI (core's `contracts.yml`). A breaking change to `contracts/mission-status` or `contracts/mission-write` is therefore detected nowhere.
- It is a documented, accepted gap, not a hidden one. `contracts/README.md:499,577` and both module CHANGELOGs say no workflow runs the tools yet, and #151 (with #202's points folded in) tracks porting the CI or retiring tools explicitly.
- This is the finding the CLI's dead-code gate would have raised with an anchor.

## Risk Findings

### REL-1: `main` does not enforce the checks the release path assumes
**Type**: BOUNDARY-CONDITION. **Severity**: MEDIUM. **Location**: repository settings and `service-release.yml:80-87`.
**Trigger condition**: a commit whose `lock-interop` is red, or that skipped review, reaches `main` and is tagged `service-v*`.
**Analysis**:
- The `main` ruleset 26558116 requires `acceptance`, `service-gate` and `cross-repo-harness-tests`, but it has `enforcement: disabled`.
- Classic protection requires only `cross-repo-harness-tests`, with no reviews. So `service-release.yml:80`'s "only a commit on main (so, reviewed)" does not hold.
- A release run re-runs `service.yml` (`service-release.yml:89-97`), including `gradle` and `service-gate`, before it assembles anything. But `lock-interop` and `nightly-corpus` are skipped in release runs (`service.yml:478-481`), so a red `lock-interop` on the tagged commit goes unchecked.
- rc.1's commit `2f5e0394` had a green push-triggered `service-gate`. rc.2's `14682f94` has none, but its service tree equals `e59a84ab`, whose run 41302099905 was green on every job, including `lock-interop`.

The tag and environment side is #152. Enabling the ruleset also closes #111. Tracked in #208.

### REL-2: no cross-leg native digest comparison
**Type**: CROSS-WP-INTEGRATION. **Severity**: MEDIUM. **Location**: `service.yml:347-351`.
**Analysis**: the `digest-<target>` artifacts are uploaded but never compared, and `nativePerf` is not wired. The guide says to compare by hand. Tracked in #153.

### REL-3: the release build floats uv and the JDK
**Type**: SECURITY (supply chain). **Severity**: LOW. **Location**: the `contract-bundle` job (uv) and the `gradle` job (`service.yml:184-188`, JDK) of the release runs.
**Analysis**:
- Job 124722315393 of the rc.1 release run logs "Could not determine uv version … Falling back to latest" and installs uv 0.13.6.
- `setup-java` takes `java-version: "25"`.
- Dependencies stay hash-locked through `uv sync --frozen`.

Tracked in #208.

### CUT-1: the moved issues lost every label
**Type**: BOUNDARY-CONDITION. **Severity**: MEDIUM.
**Analysis**: core originals with `priority:*` and `domain:*` labels (for example, #6117 and #6409 are `priority:P1`) map to events #155–#161 and #163–#170, none of which carries a label. The spec's edge case and runbook §7.1 exist to prevent this. Tracked on #207.

## Silent Failure Candidates

None found in mission-owned code:
- **Python.** The mission changed no Python under `src/`.
- **Java.** The import `1e9ec1d3` is identical to core `512567ee:services/mission-status-api`. The pre-merge squad's evidence lens read the two later Java changes for swallowed failures and found none: the `EventsController` shutdown-race fix (#162) and the `PythonPeer` lookup (#150).
- **Release scripts.** They fail closed on a missing `service/`, a tag that is not on `main`, a moved tag, or an existing release.

## Security Notes

| Finding | Location | Risk class | Recommendation |
|---|---|---|---|
| A tag on a branch commit whose workflow has an edited ancestor check would publish attested assets | `service-release.yml:82-87`; no release environment or tag ruleset | Release integrity | #152, before the first non-rc tag |
| `main` does not require `service-gate` or reviews | ruleset 26558116 disabled | Release integrity | REL-1 (#208, #111) |
| uv and the JDK float in the release build | `contract-bundle` and `gradle` jobs | Supply chain | REL-3 (#208) |

Fine as built:
- The workflow-level token is `contents: read`, and `publish` runs no repository code.
- Untrusted inputs reach shell steps only through `env:` and a regex check.
- `persist-credentials: false` is set everywhere.
- `--verify-tag` stops an existing release from being replaced.
- Every non-checksum asset is attested: rc.1 8 of 8, rc.2 13 of 13.
- Two of the three tarballs rebuild byte for byte from `2f5e0394`.

## Final Verdict

**PASS WITH NOTES**

### Verdict rationale

The merged work realizes the spec:
- **Governance:** three ADRs, the charter amendment and the agent guides landed.
- **Build:** one Gradle build with binding parity of 366 tests.
- **CI and release:** SHA-pinned service CI and release workflows.
- **Import:** a history-preserving import whose tree equals core's at the frozen SHA.
- **Release:** a pre-release whose sha256 sums core pins.

The Python line is untouched, Gates 1, 3 and 4 pass, and Gate 2 passes for its ArchUnit and conformance content.

What falls short:
- **FR-007 is partial.** The spike report did not move, a disclosed gap tracked in #207.
- **23 imported contract-tool modules have no live caller** (DEAD-1). The contracts README and CHANGELOGs disclose this, and #151 tracks it.
- **One locked decision is not followed:** `contracts/build.gradle` is still Groovy (GOV-2). It is deliberate and the only one.

No finding is CRITICAL or HIGH.

The CLI's `fail` is the missing review anchor (DRIFT-2), which could not be recorded without a false attestation. The dead code an anchored scan would have found is DEAD-1, which is already tracked.

### Open items (non-blocking)
- #207: spike report, runbook §7, labels on the moved issues, golden regeneration, rc.1 attestation verification.
- #208: release path (`main` ruleset, uv/JDK pins), mission-record drift, docs and changelog drift, charter regeneration, Groovy exception, perfTest calibration data.
- #151: contract-tooling CI, with `breaking_check`, `structure_check`, `leak_scan` and a `service-v*` baseline (DEAD-1).
- #153: cross-leg native digest comparison and `nativePerf` (REL-2).
- #111: required checks on `main` (REL-1).
- #152: release environment and tag ruleset.
- #196: placement of service-line release notes and the missing rc.2 entry.

## Retrospective Reminder

`retrospective.yaml` is authored next to this report with `spec-kitty retrospect create`, because the mission was accepted through a PR and never consolidated. The generated record was then hand-edited. spec-kitty has no amend path, so these edits are listed here:

| Edit | Detail |
|---|---|
| Removed | The generated h-004 and h-005, "WP07/WP08 completed without rejection cycles". Both WPs ran outside the WP loop (n-004, n-005). |
| Added | h-006 and h-007, which are new IDs. |
| Reworded | n-004 and n-005, with the out-of-loop detail. |
| Added | n-008 to n-010, g-001 to g-003, and evidence e-021 to e-024. |

The record still carries `created_by: spec-kitty retrospect`. The appended `RetrospectiveCaptured` event says `evidence_ref_count: 20`, because it was written before the edits; the record now holds 24, and the event log is append-only. `spec-kitty retrospect create --overwrite` would discard these edits.

Surface the findings with `spec-kitty retrospect summary`. Inspect proposals with `spec-kitty agent retrospect synthesize --mission events-domain-services-home-01M64MSB` (a dry run by default).
