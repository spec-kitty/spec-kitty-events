# #95 grounding: fork PRs and the required `cross-repo-harness-tests` check

Date: 2026-11-13. Issue: [#95](https://github.com/spec-kitty/spec-kitty-events/issues/95) (P0, CI bugfix).
Operator ruling Q1, option (a): fork PRs get a deterministic skip-to-success on the check; the
harness re-runs on a maintainer's upstream branch before merge; the fork path is documented. No
`pull_request_target` running fork code, no secret exposed to fork code.

## Evidence

- #95 has no comments. Its root cause holds on today's `main`: the gate's first credentialed step,
  `actions/create-github-app-token`, reads `secrets.SK_CI_APP_ID` / `SK_CI_APP_PRIVATE_KEY`, which
  GitHub does not pass to a `pull_request` run whose head repository is a fork.
- Fork PR #189 (`Ivan-Melck/spec-kitty-events`, head `2f9869c`): `cross-repo-harness-tests`
  **failure** after ~5 s at the mint step (`[@octokit/auth-app] appId option is required`). On the
  same head: `acceptance` success, `service-gate` success (with `gradle` and every native leg),
  `docs-build` success (nothing under the docs paths, so the build skipped in-job). The change was
  re-landed from the upstream branch `issue-6236-unscoped-connect-ledger-end` as #204, merged
  2026-11-04; that is the maintainer path this fix documents.
- Fork PR #93 (docs, 2026-10-14): `cross-repo-harness-tests` failure, `acceptance` success.

## The workflow

`.github/workflows/cross-repo-harness-tests.yml`: `pull_request` to `main` and `push` to `main`;
one job, id and name `cross-repo-harness-tests`, `if:` skips it on a `pr:pause-ci` PR. Steps:
checkout PR head (no persisted credentials) → mint App token (secrets) → checkout the pinned
private harness → revoke the token → install → run the identity-boundary unit tests.

## Required checks and how a skip reports

Required on `main` (ADR 0005 D3; operator ruleset): `acceptance`, `cross-repo-harness-tests`,
`service-gate`, `docs-build`. A required check matches a job's `name:`. GitHub reports a job
skipped by its `if:` as a success to a required check (it does not block the merge); this is the
same mechanism the `pr:pause-ci` guard already relies on (the pause workflow then posts explicit
failing checks so a paused PR is not mergeable). A workflow skipped by a trigger path filter, by
contrast, leaves the check pending forever, which is why ADR 0005 moved path filters into jobs.
Consequence: no ruleset change is needed for option (a).

## Design chosen

- Gate job `if:` gains `github.event.pull_request.head.repo.full_name == github.repository` on the
  `pull_request` branch of the condition. Comparing the full name (not `head.repo.fork`) treats a
  deleted head repository (`head.repo` null) as a fork too, and is not fooled by a fork whose
  owner name resembles ours. Push to `main` always runs.
- A second job, `fork-pr-harness-deferred` (name `cross-repo-harness-tests deferred (fork PR)`,
  deliberately not the required name), runs only on unpaused fork PRs: no checkout, no action,
  `permissions: {}`, no secret, event values through `env:`; it writes a `::notice` and a step
  summary naming the head SHA and the maintainer path.
- Rejected: `pull_request_target`/`workflow_run` (operator ruling); gating each step instead of
  the job (would break the existing "nothing conditional before the revoke" invariants);
  `workflow_dispatch` for the maintainer re-run (an upstream-branch PR already runs the gate with
  secrets and leaves a reviewable record, as #204 did).
- Residual risk (stated in the PR): the fork PR's own check is green without the harness having
  run; the upstream re-run before merge is procedural, not enforced. The `push` to `main` run is
  the deterministic backstop.

## Similar-surfaces squad: the other required checks on forks

| Check | Workflow | Secrets used | Fork evidence | Disposition |
|---|---|---|---|---|
| `acceptance` | `acceptance.yml` | none | success on #93, #189 | out of scope, no defect |
| `service-gate` | `service.yml` | none (the pinned core checkout and `GITHUB_TOKEN` only) | success on #189 with a full Gradle run | out of scope, no defect |
| `docs-build` | `docs-site.yml` | `GITHUB_TOKEN` (`packages: read`) for `@spec-kitty/doc-toolkit` on GitHub Packages | success on #189, but the build skipped in-job: never observed building on a fork PR | a fork run's token keeps read scopes, so it should install; unverified, filed as #265 |

Non-required workflows that read secrets: `ci.yml` `suite` (label-gated by the squad labels,
not required), `sonar.yml` (no `pull_request` trigger), `ci-pr-lane-canary.yml` (scheduled). None
blocks a fork PR's merge; out of scope.

## Tracked-issues squad

Searched open issues for fork, harness, required check, secrets, `pull_request_target`:

- #95: this fix.
- #208: notes the `main` ruleset as `enforcement: disabled`, with classic protection requiring only
  `cross-repo-harness-tests`. Operator-owned; unaffected by this fix (a skipped job passes under
  either), not changed here.
- #59, #203, #219, #151: workflow follow-ups with no fork/secret aspect; unaffected.
- #261, #7, #121: "harness" in another sense; unrelated.
