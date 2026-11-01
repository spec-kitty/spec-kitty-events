---
title: 'ADR-0005: land pull requests in parallel: changelog fragments, generated indexes, merge-queue readiness'
description: 'Proposed: PRs add changelog.d/ fragments, section indexes are generated, and every required-check workflow runs on merge_group, so one merge no longer conflicts every open PR.'
doc_status: active
updated: 2026-11-01T00:00:00Z
type: ADR
kind: ADR
authors:
  - human:stijn
---

# ADR-0005: land pull requests in parallel: changelog fragments, generated indexes, merge-queue readiness

**Status:** Proposed. The operator approved the remedy on 2026-11-01, the same one
`spec-kitty/spec-kitty` is adopting. The detail below is the recommendation sent with the
research; it becomes Accepted when the operator merges this record.

**Date:** 2026-11-01

**Deciders:** operator stijn-dejongh.

**Reader:** anyone opening a pull request against this repository, and the maintainer who
cuts a release or configures the merge queue.

**Technical Story:** [#175](https://github.com/spec-kitty/spec-kitty-events/issues/175)
(changelog fragments), [#176](https://github.com/spec-kitty/spec-kitty-events/issues/176)
(generated indexes), [#177](https://github.com/spec-kitty/spec-kitty-events/issues/177)
(merge-queue readiness). Research: `kitty-ops/research/2026-11-01-landing-hotspots.md`.

Related records: [ADR-0004](./0004-dual-artifact-line.md) (the PyPI `v*` and `service-v*`
lines, which both consume the changelog).

## Context

Several sessions open pull requests at once, and each merge has put every other open pull
request into conflict. Of 87 commits on `main` from 2026-10-18 to 2026-11-01:

- 13 touched `CHANGELOG.md`, every one of them at the top of `## [Unreleased]`;
- 11 touched `docs/changelog/README.md` and 5 touched `docs/adr/README.md`, each adding a
  list line and bumping the same `updated:` frontmatter line.

Nothing checks the changelog and no release tooling reads it. Nothing on GitHub enforces a
check either: there is no branch protection and no merge queue. No workflow runs on
`merge_group`, and `ci.yml` would fail if one did, because its target resolution has no
`merge_group` arm.

## Decision

### D1. One changelog fragment per change (#175)

A pull request adds `changelog.d/<issue>-<slug>.md` and never edits `CHANGELOG.md`.
- The fragment's frontmatter names one `section`: Breaking, Migration, Added, Changed,
  Deprecated, Removed, Fixed or Security.
- Its body is the bullet(s), as they will read in the changelog.

`scripts/changelog.py` owns the format:
- `check` validates every fragment.
- `guard` fails a pull request that edits `[Unreleased]`'s subsections by hand. It compares
  with the branch's merge-base, so a branch that is merely behind `main` passes. It allows
  exactly what `collect` writes for the fragments a change deletes, or a release cut.
- `preview` prints the result.
- `collect` folds the fragments into `[Unreleased]` and deletes them. With
  `--version X.Y.Z --date D` it also cuts `## [X.Y.Z] - D`.

The release pull request runs `collect`: with `--version` for a PyPI `v*` release, and
without it for a `service-v*` release, which keeps recording itself under `[Unreleased]`
(ADR-0004). `make lint` runs `check` and `guard`. `publish-pypi.yml` runs `release-check`
before it builds, so no tag publishes with a fragment still pending.

The released history and the file's shape are unchanged. `collect` writes only
`[Unreleased]`, and it writes the same subsections in the same style.

The `docs/changelog/` narrative pages stay. They are optional, one per notable change or
release, and only their index changes (D2).

### D2. Generated section indexes (#176)

The lists in `docs/changelog/README.md` and `docs/adr/README.md` are generated from the
pages themselves (title, file name, `sidebar.order`, and an ADR's `**Status:**` and
`**Date:**` lines) at docs-site build time. A check keeps the committed index free of a
hand list. No pull request edits either index, and nothing writes to `main` after a merge:
no bot commit and no follow-up pull request. The other section indexes change rarely and
stay hand-kept.

### D3. Merge-queue readiness (#177)

These workflows run on `merge_group`:
- `ci.yml`
- `acceptance.yml`
- `cross-repo-harness-tests.yml`
- `service.yml`
- `docs-site.yml`

Every `pull_request`-only expression gets a `merge_group` branch. Path filters move from
the trigger into the jobs, which diff the PR's or the merge group's base and head SHAs. A
gate job always reports, so it can be a required check.

On `merge_group`, the `squad:passed`/`squad:exhausted` label gate does not apply: the full
suite always runs there. The label only saves cost on the pull request side.

A `pr:pause-ci` label pauses a pull request's CI, as in core (spec-kitty/spec-kitty#6961).

The operator turns the queue on, in a ruleset on `main`; the #177 pull request lists the
settings.

## Consequences

- Two feature pull requests no longer share a changelog or index hunk. What remains shared
  is the version declaration, which the first `src/` change after a tag must bump
  (`scripts/check_version_not_amended.py`). That is recorded as a follow-up, not decided
  here.
- A release pull request has one more step (`collect`), and `publish-pypi.yml` refuses a
  tag that skipped it.
- An entry is reviewed as its own file, and it can no longer be misfiled under another
  release's heading by a rebase.
- Workflows changed for D1 and D3 pin every action by commit SHA, as the charter's Quality
  Gates require for any changed workflow.
