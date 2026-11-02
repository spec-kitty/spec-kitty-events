---
title: Contributing
description: How a change lands in spec-kitty-events — the one-issue-one-branch-one-PR flow, the tests and conformance gate to run, and the adversarial review.
doc_status: active
updated: 2026-11-01T00:00:00Z
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
