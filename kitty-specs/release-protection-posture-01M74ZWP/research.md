# Research: Release-protection posture for the service-v* line

Mission `release-protection-posture-01M74ZWP` (#239, closes #221). Base: `origin/main` @ `dbf95385`.

## 1. Spike: what can a read-only `GITHUB_TOKEN` read? (2026-11-11)

**Question (#239, "unverified").** Can the default read-only `GITHUB_TOKEN` GET `/rulesets` and `/environments`?

**Method.** A throwaway workflow on branch `issue-239-token-spike` (never merged; push-triggered on that branch only), two jobs:

- `contents-read-only`: `permissions: contents: read`;
- `plus-actions-attestations-read`: `contents: read`, `actions: read`, `attestations: read`.

Each job called `gh api` with `GH_TOKEN: ${{ github.token }}` and ran one `gh attestation verify`. Runs 42997528378 and 42997732131 (the second dumped the full JSON). Local calls from the agent environment were **not** used as evidence: its proxy authenticates as the maintainer (`current_user_can_bypass: always`).

**Result (identical for both jobs).**

| Endpoint | Status | What comes back |
|---|---|---|
| `GET /repos/{r}/rulesets` | 200 | both rulesets (`main` 26558116, `service-release-tags` 26752412) |
| `GET /repos/{r}/rulesets/{id}` | 200 | `name`, `target`, `enforcement`, `conditions`, `rules` (with parameters); **`bypass_actors: null`**, `current_user_can_bypass: never` |
| `GET /repos/{r}/environments` | 200 | `github-pages`, `pypi`, `service-release`, `testpypi` |
| `GET /repos/{r}/environments/service-release` | 200 | `protection_rules` with both reviewers (User `stijn-dejongh`, Team `admins`), `prevent_self_review: true`, a `branch_policy` rule; `deployment_branch_policy: {protected_branches: false, custom_branch_policies: true}`; `can_admins_bypass: false` |
| `GET …/deployment-branch-policies` | 200 | `[{name: "service-v*", type: "tag"}]` |
| `GET /repos/{r}/releases` | 200 | releases, assets, `author`, asset `digest` |
| `GET /repos/{r}/attestations/sha256:{d}` | 200 | 1 attestation for `command-cli-0.1.0-rc.2-linux-x64` |
| `GET /repos/{r}/branches/main/protection` | 403 | — |
| `GET /repos/{r}/actions/permissions[/workflow]` | 403 | — |
| `gh attestation verify <asset> --signer-workflow …/service-release.yml --source-ref refs/tags/service-v0.1.0-rc.2` | ok | — |

**Decision.** The audit runs on `contents: read` alone. The extra `actions`/`attestations` read scopes buy nothing, so they are not requested. The bypass lists cannot be read: D6 (operator) accepts that as a documented blind spot.

**Live values confirmed against the guide** ("Release protection", items 1–3): every claimed setting matches, including "Administrators cannot bypass" (`can_admins_bypass: false`) and both reviewers. The live `main` ruleset also carries `require_extra_approval_for_unattributed_changes: true` and `allowed_merge_methods: [merge, squash, rebase]`, which the guide does not mention; the expected-settings file records them as found.

## 2. Release provenance: which check catches the #221 vector?

- **Decision**: check both the release author (`github-actions[bot]`) **and** each non-checksum asset with `gh attestation verify --repo R --signer-workflow R/.github/workflows/service-release.yml --source-ref refs/tags/<tag> --deny-self-hosted-runners`.
- **Rationale**: a release created by a branch workflow with `contents: write` is also authored by `github-actions[bot]`, so the author check alone misses the very vector #221 names. Such a workflow could even attest its own assets (`id-token: write`, `attestations: write`), but the attestation certificate then names *its* workflow file and ref. Only an identity-pinned verification (signer workflow + source ref) separates it from `publish`. The author check still catches a release made by hand.
- **Alternatives considered**: (a) the attestations API by asset digest, with no download: it proves an attestation exists, not who signed it, unless the script re-implements Sigstore verification; rejected. (b) `gh attestation verify` with a digest instead of a file: the CLI (2.89.0) has no such input; it needs the file. (c) Checking asset `uploader`: implied by (author + attestation) and adds nothing.
- **Cost**: download each asset, compare its sha256 with the API `digest` (cheap integrity check), verify, delete. ~1.2 GB for the two rcs at merge; well inside NFR-001 on a hosted runner. Re-evaluate if the release count makes the run exceed 20 minutes (follow-up: cache verified `(tag, asset, digest)` triples).

## 3. Failure vs. warning vs. error (D8, FR-010)

| Outcome | Final release | Prerelease |
|---|---|---|
| author ≠ `github-actions[bot]` | failure | warning |
| no assets | failure | warning |
| asset digest ≠ downloaded sha256 | failure | warning |
| `gh attestation verify` non-zero | failure | warning |
| API error, download error, unexpected response | error (run fails) | error (run fails) |

A verification failure on a prerelease must not hide an infrastructure fault, so download and API failures are errors regardless of release type.

## 4. Normalisation of live settings (FR-004, FR-005)

- Keep identities and every setting; drop volatile fields: protection-rule `id`/`node_id`, URLs, avatars, team/user metadata other than `login`/`slug`, timestamps, `_links`, `source`, `source_type`, ruleset `id`.
- Reviewers become sorted strings `User:<login>` / `Team:<slug>`.
- Rulesets are keyed by name. Rules are sorted by `type`; lists inside parameters are sorted canonically (JSON text), because GitHub's order there carries no meaning.
- A field GitHub adds later shows up as drift. That is fail-closed and intended: the fix is a reviewed PR to the expected file.

## 5. Accepted risks (operator sign-off, recorded in the guide)

| Risk | Ruling | Sign-off | Revisit |
|---|---|---|---|
| `main` ruleset: 0 approvals, `stijn-dejongh` always-bypass | D5 | stijn-dejongh, 2026-11-09 (#239) | a second maintainer exists |
| Ruleset bypass lists unreadable by the audit | D6 | stijn-dejongh, 2026-11-11 | GitHub exposes `bypass_actors` to read tokens, or an admin-read credential is provisioned |
| Canary's org-wide App token (`ci-pr-lane-canary.yml`) | D7 | stijn-dejongh, 2026-11-11 | the App installation is narrowed, or the canary moves to the planning repo |

## 6. Supply chain (DIRECTIVE_051)

No dependency is added, upgraded or removed. The workflow uses `actions/checkout` at the SHA already pinned and reviewed in `service-release.yml` (`08c6903c…`, v5.0.0) and the `gh` CLI that ships on the runner image. The script is stdlib-only. No adversarial pass is required for a dependency decision; the mission's single PR-level squad covers the whole change.

## 7. Divergence from core

None (spec §Divergence). No core `file:line` is cited, so there is nothing to re-verify against core `main`.

## 8. Not done, noted for the operator

- **Immutable releases** (repository setting): every release reads `immutable: false` today. Enabling it would stop a published release's assets from being replaced afterwards. It is a setting change (C-001), so it goes under "Operator actions" as optional, not into the expected file.
