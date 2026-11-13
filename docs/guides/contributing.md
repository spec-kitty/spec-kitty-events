---
title: Contributing
description: How a change lands in spec-kitty-events — the one-issue-one-branch-one-PR flow, the tests and conformance gate to run, and the adversarial review.
doc_status: active
updated: 2026-11-13T00:00:00Z
type: Guide
kind: How-To
related:
  - guides/docsite
---

# Contributing

`spec-kitty-events` follows the Spec Kitty programme process. `main` is the integration
branch: every PR targets `main`, nothing deploys on merge, and implementers never merge their
own work. The binding process is the programme constitution; see
[`AGENTS.md`](https://github.com/spec-kitty/spec-kitty-events/blob/main/AGENTS.md) and
[`PROGRAM.md`](https://github.com/spec-kitty/EXPERIMENTAL-spec-kitty-planning/blob/main/PROGRAM.md).

## Pausing CI

Label a PR `pr:pause-ci` to stop its CI while you rework it: the label cancels the PR's
running workflows and marks its required checks (`acceptance`, `cross-repo-harness-tests`,
`service-gate`, `docs-build`) failed, and removing it re-runs what was cancelled. A push made
under the label re-posts the failures for the new head; the gates that would have run have no
run from before the pause, so push again once the label is off. `suite` (`ci.yml`) is not a
required check and is skipped under the label.

The ruleset on `main` requires those four checks, with "require branches to be up to date
before merging" off, and there is no merge queue (ADR 0005 D3, amended 2026-11-02). A PR
therefore merges on its own passing checks, without being re-tested against a newer `main`.

## Pull requests from forks

GitHub gives no repository secrets to a `pull_request` run whose head is a fork, and
`cross-repo-harness-tests` needs them to clone the private harness. On a fork PR that gate
job is therefore skipped, which GitHub reports as a pass to the required check, and a
`cross-repo-harness-tests deferred (fork PR)` job says so on the PR (#95). The other three
required checks run on fork PRs as on any other. Nothing runs fork code with secrets: there
is no `pull_request_target` path.

The harness has not tested the fork's change, so a maintainer runs it before merge:

1. Push the fork PR's head, unchanged, to an upstream branch:
   `git fetch origin pull/<n>/head && git push origin FETCH_HEAD:refs/heads/issue-<issue>-<slug>`.
2. Open a pull request from that branch to `main` (a draft is enough). Its
   `cross-repo-harness-tests` runs with the secrets.
3. Once it is green on that exact head SHA, merge that pull request and close the fork PR
   with a link to it (as #189 landed as #204), or, if the fork head has not moved, merge the
   fork PR and close the upstream one. A push to `main` runs the harness again either way.

## The flow

- **One issue → one branch → one PR.** Cut `issue-<n>-<slug>` from `main`; open one PR back to
  `main`. Never push to `main`.
- **Run what you touched, plus the blast radius.** Run every test you write or change and the
  tests around it, and record the commands and counts in the PR. Do not run a whole-repo suite
  before opening the PR — that is the CI agent's job.
- **One adversarial review per PR.** `[MAJOR]` findings block; minors become issues.

## Commands

| Purpose | Command |
|---|---|
| Implementer blast-radius baseline | `make test-fast` |
| Whole suite with coverage (CI agent only) | `make test-full` |
| Packaged conformance gate — **every PR** | `uv run pytest --pyargs spec_kitty_events.conformance` |
| Lint (ruff, the version guard, the changelog fragment checks) | `make lint` |

The conformance gate is the packaged contract other repos consume; the in-repo suite does not
collect it, so run it explicitly on every PR.

## Nightly SonarCloud scan

An informational [SonarCloud](https://sonarcloud.io/dashboard?id=spec-kitty_spec-kitty-events)
analysis of project `spec-kitty_spec-kitty-events` (organization `spec-kitty`) runs from
`.github/workflows/sonar.yml`, configured by `sonar-project.properties`. It listens on
`schedule` (nightly at 04:17 UTC, clear of the nightly corpus and the CI canary) and
`workflow_dispatch` **only**: there is no `pull_request` trigger, so it reports no required
check and never gates a merge. A red quality gate is surfaced in the run summary and in
SonarCloud, not as a failed workflow.

The job generates its own coverage so it always describes the scanned revision: the Python
suite writes `coverage.xml`, and `./gradlew verifyAll` writes one JaCoCo XML report per Gradle
project with main sources, after the contract bundle is built, as `service.yml` does. The
project list is read from `settings.gradle.kts` by `scripts/sonar_java_modules.py`, so a new
module with main sources is picked up without editing the workflow. The collect step warns with
a `::warning::` for each module whose report is missing, and the step summary lists the reports
it found and the missing modules. It never downloads another run's reports; if one is missing
the run warns and scans without that module's coverage rather than reusing another commit's.
Without the `SONAR_TOKEN` secret (forks) the job skips green with a notice.

Java library types resolve from explicit classpaths. The `sonarLibraries` Gradle task, registered
by the root build, writes each Java project's compile classpath to `build/sonar/libraries.txt` and
its test classpath to `build/sonar/test-libraries.txt`. The collect step joins those files into
`sonar.java.libraries` and `sonar.java.test.libraries`. Only when the classpaths step failed or wrote no such file does it fall
back to the `modules-2` Gradle cache glob, with a warning.

`sonar-project.properties` sets no `sonar.test.inclusions` (it only narrows files already under
`sonar.tests`, so it would drop the Java tests), and the packaged conformance tests under
`src/spec_kitty_events/conformance/` are scanned as main code. `sonar.sources` lists
`java/src/main/java` and no source root overlaps a test root.

The analysis steps run under `!cancelled()` rather than `always()`, so a cancelled run does not
scan. The scan step's `env` carries `SONAR_TOKEN` alone, and no build step receives the token.
The report step's step summary names each failed build step (the Python tests, JDK, contract
bundle, Gradle build and classpath steps). Those steps are `continue-on-error`, so the scan still
runs and the summary says what it ran without.

`tests/test_sonar_workflow.py` pins these properties, and `tests/test_sonar_java_modules.py`
pins the module derivation.

Run it by hand against a branch:

```bash
gh workflow run sonar.yml --ref <branch>
```

## Recording the change

Every pull request with a user-visible change adds one changelog fragment,
`docs/changelog/unreleased/<issue>-<slug>.md`, and does **not** add entries to `[Unreleased]` in
`CHANGELOG.md`:

```markdown
---
section: Added
---

- What changed and why it matters to a consumer (#<issue>).
```

`section` is one of Breaking, Migration, Added, Changed, Deprecated, Removed, Fixed,
Security or Compatibility; the body is one or more `- ` bullets with no headings. `make lint` runs
`scripts/changelog.py check` (the fragments are well formed) and `guard` (nobody edited
`[Unreleased]`'s subsections by hand; its opening paragraph is free text, so a pull request
that bumps the version may correct it). The release pull request runs `scripts/changelog.py collect`, with
`--version X.Y.Z --date YYYY-MM-DD` for a PyPI `v*` release, which folds the fragments into
`CHANGELOG.md` and deletes them; `preview` shows the result first. Parallel pull requests
then never touch the same lines. See [ADR 0005](../adr/0005-parallel-landing.md) and
`docs/changelog/unreleased/README.md`.

## When you change the contract

A change that flips any accept/reject boundary is a major package bump and must ship the full
artifact set (changelog, compatibility, schemas, fixtures). Read
[versioning & compatibility](/api/versioning-and-compatibility/) before you start, and
regenerate committed JSON Schemas with `python -m spec_kitty_events.schemas.generate` when you
touch a payload model.

## When you change the docs

Validate and build the docsite as [Building the docsite](/guides/docsite/) describes; the docs
CI runs frontmatter validation, a full build and a built-link check.
