---
title: 'ADR-0005: land pull requests in parallel: changelog fragments, generated indexes, required checks'
description: 'Accepted: fragments per PR, generated indexes, always-on required checks. Amended 2026-11-02: no merge queue; amended 2026-11-03: fragment stopgap replaced by toolkit exclusion.'
doc_status: active
updated: 2026-11-03T00:00:00Z
type: ADR
kind: ADR
authors:
  - human:stijn
---

# ADR-0005: land pull requests in parallel: changelog fragments, generated indexes, required checks

**Status:** Accepted. The operator approved the remedy on 2026-11-01, the same one
`spec-kitty/spec-kitty` and doc-kitty (ADR-0072) are adopting, and ruled on each open
point the research raised the same day; the decisions below record those rulings. The
three decisions land as three pull requests in order: D1 (changelog fragments) in
[#178](https://github.com/spec-kitty/spec-kitty-events/pull/178), D2 (generated indexes)
in [#179](https://github.com/spec-kitty/spec-kitty-events/pull/179), and D3 (required
checks) in #182. "Accepted" means the rulings are decided, not that all three are yet
built on `main`.

**Amended 2026-11-02:** D3 originally also called for a merge queue. The operator decided
against it; see the amendment under D3. D1 and D2 are unchanged.

**Amended 2026-11-03:** D1's CI-only `rm -rf` stopgap is gone; doc-kitty 0.6.0 excludes
`docs/changelog/unreleased/` natively (toolkit ADR-0070) — nothing about fragments' format
or the collect flow changes.

**Date:** 2026-11-01

**Deciders:** operator stijn-dejongh.

**Reader:** anyone opening a pull request against this repository, and the maintainer who
cuts a release or configures the ruleset.

**Technical Story:** [#175](https://github.com/spec-kitty/spec-kitty-events/issues/175)
(changelog fragments), [#176](https://github.com/spec-kitty/spec-kitty-events/issues/176)
(generated indexes), [#177](https://github.com/spec-kitty/spec-kitty-events/issues/177)
(required checks; first filed as merge-queue readiness). Research: `kitty-ops/research/2026-11-01-landing-hotspots.md`.

Related records: [ADR-0004](./0004-dual-artifact-line.md) (the PyPI `v*` and `service-v*`
lines, which both consume the changelog).

## Context

Several sessions open pull requests at once, and each merge has put every other open pull
request into conflict. Of 87 commits on `main` from 2026-10-18 to 2026-11-01:

- 13 touched `CHANGELOG.md`, every one of them at the top of `## [Unreleased]`;
- 11 touched `docs/changelog/README.md` and 5 touched `docs/adr/README.md`, each adding a
  list line and bumping the same `updated:` frontmatter line.

Nothing checks the changelog and no release tooling reads it. Nothing on GitHub enforces a
check either: there is no branch protection and no merge queue.

## Decision

### D1. One changelog fragment per change (#175)

A pull request adds `docs/changelog/unreleased/<issue>-<slug>.md` and never adds entries to
`[Unreleased]` in `CHANGELOG.md`. The folder matches core and doc-kitty (operator ruling;
the research had proposed a root `changelog.d/`). It holds entries, not pages: the docs
build, its frontmatter and link gates and every index leave it out, and a change to it
alone does not trigger the docs build. While the site pins toolkit 0.5.0, which cannot
exclude a folder, the docs job removes the folder from its checkout; a toolkit release with
doc-kitty's `exclude` (its ADR-0070) replaces that step
([#181](https://github.com/spec-kitty/spec-kitty-events/issues/181)).
- The fragment's frontmatter names one `section`: Breaking, Migration, Added, Changed,
  Deprecated, Removed, Fixed, Security or Compatibility (#186).
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

### D3. Required checks (#177)

**Amended 2026-11-02.** The original decision (2026-11-01) made the repository ready for a
GitHub merge queue: the five gate workflows ran on `merge_group`, `ci.yml` resolved a queue
run to the queued PR and failed the entry unless its verdict was green, and the operator
would turn the queue on with rebase merge, build concurrency 5, groups of 1 to 5, a
5-minute wait and a 120-minute check timeout. **Changed decision:** there is no merge queue
in this repository. **Reason:** the operator found that in core the queue duplicated CI runs
instead of avoiding reruns. The `merge_group` triggers and everything that existed only for
them are removed.

What stands:
- `acceptance.yml`, `cross-repo-harness-tests.yml`, `service.yml` and `docs-site.yml` produce
  the required checks. Path filters moved from the trigger into the jobs, which diff the
  PR's base and head SHAs (without rename detection). A gate job always reports, so it can be
  required.
- `ci.yml`'s `suite` keeps its `squad:passed`/`squad:exhausted` label gate and is **not** a
  required check. It therefore no longer runs on `opened` and `ready_for_review`, which only
  existed so it reported on every pull request.
- A `pr:pause-ci` label pauses a pull request's CI, as in core (spec-kitty/spec-kitty#6961).

The ruleset on `main` (the operator sets it) lists required status checks only, with "require
branches to be up to date before merging" off:
- `acceptance`
- `cross-repo-harness-tests`
- `service-gate`
- `docs-build`

## Consequences

- Two feature pull requests no longer share a changelog or index hunk. What remains shared
  is the version declaration, which the first `src/` change after a tag must bump
  (`scripts/check_version_not_amended.py`). That is a follow-up
  ([#180](https://github.com/spec-kitty/spec-kitty-events/issues/180)), not decided here.
- A release pull request has one more step (`collect`), and `publish-pypi.yml` refuses a
  tag that skipped it.
- An entry is reviewed as its own file, and it can no longer be misfiled under another
  release's heading by a rebase.
- Workflows changed for D1 and D3 pin every action by commit SHA, as the charter's Quality
  Gates require for any changed workflow.
