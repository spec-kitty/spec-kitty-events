---
affected_files: []
cycle_number: 2
mission_slug: events-domain-services-home-01M64MSB
reproduction_command:
reviewed_at: '2026-10-29T21:38:01Z'
reviewer_agent: claude
wp_id: WP02
---

# WP02 review feedback (cycle 2) — reviewer-renata

Verdict: **changes requested** (one [MAJOR]). Minors 2–4 from cycle 1 are resolved. The rest of the
cycle-1 MAJOR fix is good. One clause in the new sentence is false once WP04 lands, and no later WP owns
AGENTS.md, so it has to be fixed here. It is a one-clause edit.

## [MAJOR] 1. AGENTS.md says the service workflows "do not build ... anything" before cutover

AGENTS.md (CLAUDE.md is a symlink to it) now ends the Actions paragraph with:

> The service build and release workflows (ADR-0003; their service jobs stay inert until the services are
> imported at cutover) do not build or release anything before then.

I judged this sentence on the basis that WP04 lands in the same PR, so describing the workflows as
present is acceptable. Its content is still wrong. WP04's T015 says that `service.yml` has a job
`gradle` that runs `./gradlew --no-daemon verifyAll` ("This job runs now"; the WP04 objective says "The
binding's Gradle job runs immediately"). It runs on `pull_request` and on `push` to `main` for paths
under `java/**`, `gradle/**`, `build-logic/**`, `*.gradle.kts` and similar. So from the moment the PR
merges, `service.yml` builds and checks the Java binding. Only the guarded service jobs (`contract-bundle`,
`lock-interop`, the native matrix, `nightly-corpus`) and `service-release.yml` are inert. As written, the
binding orientation tells agents that no service workflow runs a build, while a Gradle check from
`service.yml` will appear on every Java/Gradle PR. That is the same accuracy DoD that blocked cycle 1.
WP04 owns only the workflow files and WP06 does not own AGENTS.md, so nothing later in the mission
corrects it.

Fix (suggested wording; name the files too):

> The service workflows ([ADR-0003](...)) are `service.yml`, which runs the Gradle check
> (`./gradlew verifyAll`) on PRs and pushes to `main` that touch the Java build, and `service-release.yml`
> (`service-v*` tags). Their service jobs and the release stay inert until the services are imported at
> cutover.

## Resolved from cycle 1

- **[MAJOR] Actions sentence: existing workflows.** Checked against `ls .github/workflows` and each `on:`
  block. It is accurate: `ci.yml` is the CI suite; `acceptance.yml` and `cross-repo-harness-tests.yml`
  are on PR/push; `ci-pr-lane-canary.yml` is daily cron `23 5 * * *`; `publish-pypi.yml` runs on `v*`
  tags; `publish-testpypi.yml` is `workflow_dispatch` only; `docs-site.yml`'s `deploy` job is gated
  `github.event_name != 'pull_request' && github.ref == 'refs/heads/main'` with `docs/**` and `site/**`
  push paths. "Nothing deploys on merge except the docs site" is correct.
- **[minor] SHA-pin wording.** The Quality Gates bullet, the Policy Summary Quality Gates line and
  `answers.yaml` `quality_gates` now read the same: the service workflows plus any workflow added or
  changed from this amendment on (NFR-003). The non-existent "Java workflow" is gone.
- **[minor] Core ADR link.** The row now says it "lands on core main with spec-kitty/spec-kitty#6782".
  Verified: `refs/pull/6782/head` carries
  `docs/adr/4.x/2026-10-29-1-the-events-domain-and-its-java-services-live-in-spec-kitty-events.md`
  (exact filename), and that file is not on core `origin/main`.
- **[minor] `kotlin` in `catalog.languages`.** The justification is recorded in the amendment row and is
  true: `charter.activation.language_authority.declared_languages(<languages_frameworks answer>)` returns
  `['python', 'java', 'kotlin']` (because of "Gradle (Kotlin DSL)").

## Re-checked (no action)

- The Package Boundary test-only exception is unchanged from cycle 1 (5eb0470 touches only charter.md lines
  for Quality Gates, the Policy Summary and the amendment row). It is still scoped exactly to ADR 0002 D4
  and D5.
- The AGENTS.md `<!-- spec-kitty:orientation -->` block has the same md5 at c48540f and at HEAD
  (`7274cb29…`). CLAUDE.md is still mode 120000.
- `spec-kitty charter context --action plan --json --no-mark-loaded` → `success`,
  `Languages: java, kotlin, python`.
- `ruff check . && ruff format --check .` → All checks passed; 773 files already formatted.
- c1a64b9 and 5eb0470 touch only owned files (`.kittify/charter/{charter.md,charter.yaml,interview/answers.yaml}`,
  `AGENTS.md`).
