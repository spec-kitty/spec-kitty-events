# Feature Specification: Release-protection posture for the service-v* line

**Feature Branch**: `issue-239-release-protection` (single branch, one PR to `main`)
**Created**: 2026-11-11
**Status**: Draft
**Input**: Issue #239 (Mission M4: Release-protection posture), which closes #221 (service-v* releases: close the paths around the protected publish job, follow-up to #152). Inputs are the maintainer ruling D5 of 2026-11-09 recorded on #239, the token-capability spike of 2026-11-11 (research.md §1), and the operator's answers of 2026-11-11 (the Decision Moments below).

## Intent Summary

The `service-release` environment protects only the job that declares it (`publish`, `service-release.yml:131-141`). #221 names the paths around it:

- a workflow on any pushed branch can ask for `contents: write` and run `gh release create` against an existing `service-v*` tag, which produces an unreviewed, unattested release;
- the PR-lane canary mints an App token for every repository the org-wide installation reaches (`ci-pr-lane-canary.yml:64-72`);
- the `main` ruleset (26558116) needs 0 approving reviews and has an always-bypass actor;
- nothing checks the live environment and rulesets for drift: `tests/test_service_workflows.py` pins the YAML only.

This mission gives each of those paths a control or a recorded, signed-off accepted risk, before the first non-rc `service-v*` tag.

- **Primary actor:** the maintainer (the operator), who owns the repository settings and cuts `service-v*` releases. Core and other consumers are secondary actors: they rely on every published `service-v*` asset being the one the protected `publish` job attested.
- **Trigger:** a daily schedule, or a manual run by the operator (for example right after changing a setting or publishing a release).
- **Desired outcome:** the run is green when the live release-protection settings equal a committed expected-settings file and every `service-v*` release came from the protected `publish` job. It is red, and says exactly what differs, when a setting drifted or a final release was not authored by `github-actions[bot]` or carries an asset without a valid provenance attestation from `service-release.yml` at its own tag.
- **Invariant:** the audit itself can change nothing. It holds a read-only token (`contents: read`), runs only on schedule or manual dispatch, and uses only SHA-pinned actions.
- **Accepted risks** (each signed off by the operator in the release guide): the `main` ruleset's 0 approvals and operator bypass (D5), the org-wide canary token, and the ruleset bypass lists the read-only token cannot see.

Intent was confirmed by the operator on 2026-11-11 after the spike.

## Maintainer Rulings and Decisions (binding)

| Ruling | Decision | Decision Moment |
|---|---|---|
| **D5** (maintainer, 2026-11-09, on #239) | The `main` ruleset (0 approvals, operator bypass) is an accepted risk, with the audit workflow as the compensating control. Revisit when a second maintainer exists. This mission records it and does not change it. | — (ruled on the issue) |
| **D6** (operator, 2026-11-11) | Ruleset bypass lists: accept the blind spot. A read-only `GITHUB_TOKEN` gets `bypass_actors: null` for both rulesets, so the audit keeps `contents: read` and checks everything it can read. The guide records the bypass lists as an accepted risk with the operator's sign-off and a read-only command the operator runs by hand. No new credential. | `DM-01M755J5E9J11CFV1JEA9T2X1A` |
| **D7** (operator, 2026-11-11) | Canary token: no change. `ci-pr-lane-canary.yml` stays as is. The org-wide App token is recorded as an accepted risk with sign-off. Narrowing the App installation is an operator action, not part of this change. | `DM-01M755JB9N018XSX5VJ39HQRXP` |
| **D8** (operator, 2026-11-11) | Prereleases warn. A final `service-v*` release fails the audit on a non-bot author or any unattested non-checksum asset. A prerelease (`-rc.N` or any `-` suffix) gets the same checks, reported as warnings. A rehearsal (`workflow_dispatch`) publishes no release, so there is nothing to audit. | `DM-01M755JGMGCZPF51EY86DH0DR5` |

**Spike result (research.md §1).** The issue's open claim was whether a read-only `GITHUB_TOKEN` can read rulesets and environments. Measured on 2026-11-11 (run 42997732131): with `contents: read` alone it reads the ruleset list and each ruleset's target, enforcement, conditions and rules; every environment; `service-release`'s reviewers (user and team), `prevent_self_review`, `can_admins_bypass` and deployment policy; the deployment-branch policies; the releases and their assets; and the attestations by digest. `gh attestation verify` with `--signer-workflow` and `--source-ref` succeeds. It cannot read `bypass_actors` (returned `null`; `current_user_can_bypass: never`), branch protection or Actions permissions (403). Adding `actions: read` and `attestations: read` changes nothing.

## Divergence from core

None. Core has no release-protection audit; this mission adds a repository-local control. Core's only contact with it is as a consumer: it already pins assets by tag and sha256 (charter, Services), and the guide adds the provenance check consumers can run.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - The operator learns when a release-protection setting drifts (Priority: P1)

Every day, and whenever the operator starts it by hand, the audit reads the live settings that protect releases and compares them with the committed expected-settings file. If someone loosens the `service-release` environment (drops a reviewer, turns off self-review prevention, lets administrators bypass it, widens its tag policy), changes or disables a ruleset, or adds or deletes one, the run turns red and names each difference.

**Why this priority:** #221's last bullet: the YAML is pinned, the live settings are not. Every other control in the release path rests on those settings.

**Independent Test:** run the comparison on a captured live snapshot with the committed expected file: no difference. Change one value in the expected file: one difference, naming that value.

**Acceptance Scenarios:**

1. **Given** live settings equal to the expected file, **When** the audit runs, **Then** it reports no settings drift.
2. **Given** the expected file says `service-release` has two reviewers, **When** the live environment has one, **Then** the audit fails and names the missing reviewer.
3. **Given** a third ruleset is added live (or one is deleted), **When** the audit runs, **Then** it fails and names the ruleset.
4. **Given** a ruleset's enforcement changes from `active` to `evaluate` or `disabled`, **When** the audit runs, **Then** it fails.

---

### User Story 2 - The operator learns when a release did not come from the protected publish job (Priority: P1)

The audit lists every `service-v*` release. A release the protected `publish` job created is authored by `github-actions[bot]`, and each of its assets except the checksum files carries a build-provenance attestation signed by `service-release.yml` running at `refs/tags/<that tag>`. A release created by hand, or by a workflow on another branch, fails one of those checks. A final release that fails turns the run red; a prerelease that fails produces a warning.

**Why this priority:** this is the "branch with an edited workflow" vector (#152, #221). Such a release is also authored by `github-actions[bot]`, so only the attestation identity check catches it; both checks are needed.

**Independent Test:** feed the release check a release list and a verifier result per asset, from fixtures: the two published rcs pass; a final release with a human author fails; a final release with one asset whose attestation fails fails; the same faults on a prerelease warn.

**Acceptance Scenarios:**

1. **Given** `service-v0.1.0-rc.1` and `service-v0.1.0-rc.2` as published today, **When** the audit runs, **Then** neither fails nor warns.
2. **Given** a final `service-v1.0.0` release authored by a user, **When** the audit runs, **Then** it fails and names the release and the author.
3. **Given** a final release whose binary asset has no attestation, or one signed by another workflow or ref, **When** the audit runs, **Then** it fails and names the asset.
4. **Given** the same faults on `service-v1.1.0-rc.1`, **When** the audit runs, **Then** it reports warnings and does not fail.
5. **Given** a release of a package tag (`v11.3.0`), **When** the audit runs, **Then** it ignores it.

---

### User Story 3 - The operator and reviewers can see every release vector and how it is covered (Priority: P2)

The release guide gains a table: each vector that can produce or alter a `service-v*` release, the control that covers it, or the accepted risk with the operator's sign-off (who, when, why, when to revisit), and how to check it by hand.

**Why this priority:** #239 AC 4. Without it the accepted risks live only in issue comments.

**Independent Test:** read the guide section: every vector from #221 and the bypass-list gap has a row; each accepted-risk row names the operator and a date.

**Acceptance Scenarios:**

1. **Given** the guide, **When** a reviewer looks up "a branch workflow with `contents: write`", **Then** they find the audit's provenance check and the consumer `gh attestation verify` command as its controls.
2. **Given** the guide, **When** a reviewer looks up the `main` ruleset, the canary token or the bypass lists, **Then** each is an accepted risk with the operator's sign-off.

---

### User Story 4 - The audit cannot itself become a release vector (Priority: P1)

The audit workflow can be started only by its schedule or by hand, holds only `contents: read`, and pins every action by a 40-hex commit SHA. Tests fail if any of that changes.

**Why this priority:** #239 AC 1. A compensating control that holds a write token, or runs on a push from any branch, would add the very path it watches.

**Independent Test:** the workflow tests pass on the committed workflow and fail on a copy that adds a `push` trigger, a write permission, or a tag-pinned action.

**Acceptance Scenarios:**

1. **Given** the committed audit workflow, **When** the tests run, **Then** they find only `schedule` and `workflow_dispatch` triggers, `contents: read` at every level and only SHA-pinned actions.

### Edge Cases

- The API pages its results: a repository with more than one page of releases or rulesets must be read completely, or a release on page two escapes the audit.
- A release with no assets (or a `service-v*` release whose assets were deleted) is reported: a final one fails (it cannot be what `publish` uploads, which always includes `SHA256SUMS`).
- The checksum files (`SHA256SUMS`, `*.sha256`) are not attested by design (`service-release.yml:150`) and are not checked for attestations.
- A draft release is invisible to a read-only token; drafts are out of reach and out of scope (a draft is not visible to consumers either).
- A transient API or network failure must fail the run as an error, distinct from a drift finding, never pass it.
- Volatile fields in the live responses (ids of protection rules, node ids, URLs, avatars, timestamps) must not cause drift; identities (user login, team slug) and every setting must.
- A field GitHub adds to a ruleset rule in future (as `require_extra_approval_for_unattributed_changes` appeared) shows as drift, which is the intended, fail-closed behaviour; the fix is a reviewed PR to the expected file.

## Domain Language

| Term | Meaning | Avoid |
|---|---|---|
| **audit** | the scheduled read-only workflow and its checks | "monitor", "scanner" |
| **expected-settings file** | the committed file the live settings are compared with | "baseline", "snapshot" (a snapshot is a captured live response, used in tests) |
| **settings drift** | any difference between the live settings and the expected-settings file | |
| **release provenance** | a release's author and its assets' attestations, checked against the `publish` job's identity | "signature" (the binaries are unsigned) |
| **final release** / **prerelease** | a `service-v*` tag without / with a `-` suffix (the `gate` job's rule) | "GA" for final |
| **accepted risk** | a vector left open on purpose, with the operator's sign-off in the guide | |

## Requirements *(mandatory)*

### Functional Requirements

| ID | Requirement | Status | Delivery | No-op passable? |
|---|---|---|---|---|
| FR-001 | A new workflow MUST run the audit on a daily schedule and on manual dispatch, and on no other event. | Proposed | [build] | no |
| FR-002 | The audit workflow MUST hold only `contents: read` (workflow level, and no job may widen it) and MUST pin every action it uses by a 40-hex commit SHA with a version comment. | Proposed | [build] | no |
| FR-003 | A test MUST fail when the audit workflow gains another trigger, any permission other than `contents: read`, or an action reference that is not a full commit SHA (#239 AC 1). | Proposed | [build] | no |
| FR-004 | A committed expected-settings file MUST record, for the repository: the set of environment names; for `service-release`, its required reviewers (users by login, teams by slug), `prevent_self_review`, `can_admins_bypass`, its deployment-branch policy and its deployment-branch/tag policies; and the set of rulesets, each with name, target, enforcement, conditions and rules. | Proposed | [build] | no |
| FR-005 | The audit MUST read the same settings live, normalise away volatile fields, compare them with the expected-settings file, and fail with one line per difference naming its path, the expected and the live value. | Proposed | [build] | no |
| FR-006 | A test MUST show that the committed expected-settings file matches a captured live snapshot, and that changing any one recorded value in it (each one, in turn) makes the comparison fail (#239 AC 2). | Proposed | [build] | no |
| FR-007 | The audit MUST check every `service-v*` release, across all pages: its author MUST be `github-actions[bot]`, it MUST have at least one asset, and every asset other than `SHA256SUMS` and `*.sha256` MUST pass `gh attestation verify` with `--signer-workflow` set to this repository's `service-release.yml` and `--source-ref refs/tags/<tag>`. | Proposed | [build] | no |
| FR-008 | A failed FR-007 check MUST fail the run for a final release and MUST be reported as a warning, not a failure, for a prerelease (D8). Releases whose tag is not `service-v*` MUST be ignored. | Proposed | [build] | no |
| FR-009 | Tests MUST show, from fixtures: the two published rc releases pass with no warning; a final release with a non-bot author fails; a final release with an asset that fails verification fails; a final release with no assets fails; the same faults on a prerelease warn and do not fail; a package release is ignored (#239 AC 3). | Proposed | [build] | no |
| FR-010 | An API or verifier error that is not a verification failure (network, rate limit, unexpected status) MUST end the run with an error, never a pass. | Proposed | [build] | no |
| FR-011 | The audit MUST write a readable summary of its findings to the run summary, and exit non-zero when there is any failure or error. | Proposed | [build] | no |
| FR-012 | The release guide (`docs/guides/service-protocol-and-releases.md`) MUST gain a section that maps each release vector of #221, plus the bypass-list gap, to its control or to an accepted risk; each accepted risk MUST carry the operator's sign-off (who, date, ruling, revisit condition) (#239 AC 4). | Proposed | [build] | no |
| FR-013 | The guide MUST tell the operator how to run the audit by hand, how to read a red run, and that a deliberate settings change lands with a reviewed PR to the expected-settings file. | Proposed | [build] | no |
| FR-014 | The guide MUST give consumers the provenance check for a downloaded asset with `--signer-workflow` and `--source-ref` (the existing step 5 command), referenced from the vector table as the consumer-side control. | Proposed | [ratchet] | yes — positive control: FR-012's table row names it; a docs test or review checks the row exists |
| FR-015 | `service-release.yml`'s header and `AGENTS.md`/`CLAUDE.md`'s workflow list MUST mention the audit as the compensating control for the paths `publish`'s environment does not hold. | Proposed | [build] | no |

### Non-Functional Requirements

| ID | Requirement | Threshold | Status |
|---|---|---|---|
| NFR-001 | Audit run time on a GitHub-hosted runner, with the releases that exist at merge. | ≤ 20 minutes wall clock; job `timeout-minutes` ≤ 30. | Proposed |
| NFR-002 | Settings comparison determinism: the same live responses compared twice give the same findings in the same order. | 100% identical output across two runs on the same snapshot. | Proposed |
| NFR-003 | False positives on today's live state. | 0 failures and 0 warnings on the live settings and releases at merge (verified by one dispatched run after merge, recorded as an operator action). | Proposed |
| NFR-004 | Disk use: an asset is downloaded, verified and deleted before the next is fetched. | Peak extra disk ≤ the largest single asset + 50 MB. | Proposed |

### Constraints

| ID | Constraint | Status |
|---|---|---|
| C-001 | No repository settings, rulesets, environments or App installations are changed by this mission. Needed changes go to the PR's "Operator actions". | Active |
| C-002 | D5: the `main` ruleset stays as it is; it is recorded as accepted risk. | Active |
| C-003 | D7: `ci-pr-lane-canary.yml` is not changed. | Active |
| C-004 | The audit holds no write token and files no issue; the red run is the signal (GitHub notifies on a failed scheduled run). | Active |
| C-005 | Every action in the new workflow is SHA-pinned (charter, Quality Gates; ADR 0003, NFR-003). | Active |
| C-006 | Session scope: `service/command-cli`, `contracts/mission-write` and `service/mission-status-api` are not touched (sibling missions #237, #170). | Active |

### Key Entities

- **Expected-settings file:** the committed description of the release-protection settings (FR-004), changed only by a reviewed PR.
- **Settings snapshot:** the normalised live settings, built from the API responses.
- **Finding:** one difference or one release fault, with a severity (failure, warning) and a message.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: On today's settings and releases the audit finds 0 failures and 0 warnings. — [build] · no-op passable: no (no audit exists today)
- **SC-002**: Each of the recorded settings values, changed one at a time in the expected file, produces exactly one failing finding. — [build] · no-op passable: no
- **SC-003**: Each release fault in FR-009 produces a failure for a final release and a warning for a prerelease, 100% of fixture cases. — [build] · no-op passable: no
- **SC-004**: Every vector named in #221, plus the bypass-list gap, has exactly one row in the guide's table, and every accepted-risk row names the operator and a date. — [build] · no-op passable: no

## Assumptions

- GitHub notifies the operator of a failed scheduled run (the default for the user who last changed the workflow's schedule); no extra alerting is needed (C-004).
- The repository stays public, so a read-only token can download release assets and verify attestations. If it goes private, `contents: read` still covers both (the spike ran with only that).
- `gh` is preinstalled on GitHub-hosted runners, so the audit needs no third-party action beyond checkout.

## Out of Scope

- Changing the `main` ruleset's approval or bypass policy (D5), the App installation's scope (D7), or any setting (C-001).
- Restricting who can create releases at the platform level (GitHub has no release-creation rule); the audit detects, it does not prevent.
- Reading ruleset bypass lists (D6).
- The PyPI `v*` line's release protection.
