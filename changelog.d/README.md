# Changelog fragments

One file per change, so parallel pull requests never edit the same lines of
`CHANGELOG.md` ([ADR 0005](../docs/adr/0005-parallel-landing.md), issue #175).

## Writing one

Name it `<issue>-<slug>.md`: the issue number the branch is cut for, then a
lowercase kebab-case slug (`175-changelog-fragments.md`). Two pull requests for
one issue use two slugs.

```markdown
---
section: Added
---

- What changed and why it matters to a consumer (#175).
  - Nested detail is kept as written.
```

- `section` is one of `Breaking`, `Migration`, `Added`, `Changed`, `Deprecated`,
  `Removed`, `Fixed`, `Security`. One fragment, one section; a change with entries in
  two sections adds two fragments (`175-x.md`, `175-x-fix.md`).
- The body is one or more `- ` bullets, written as they will read in `CHANGELOG.md`.
  No headings.
- Do **not** edit `## [Unreleased]` in `CHANGELOG.md`: `make lint` fails a pull request
  that does (`scripts/changelog.py guard`) and checks every fragment
  (`scripts/changelog.py check`).

## Releasing

The release pull request folds the fragments into `CHANGELOG.md` and deletes them:

```bash
uv run python scripts/changelog.py preview                     # what [Unreleased] will say
uv run python scripts/changelog.py collect --version 11.4.0 --date 2026-11-02   # PyPI v*
uv run python scripts/changelog.py collect                     # service-v*: stays under [Unreleased]
```

`collect` appends each fragment to its `###` subsection of `[Unreleased]` (creating the
subsection in the order above if it is missing), ordered by issue number. With `--version`
it renames that section `## [X.Y.Z] - DATE` under a fresh, empty `[Unreleased]`. Write or
adjust the section's opening paragraph in the same pull request; the guard allows any
edit that consumes fragments or cuts a release. `publish-pypi.yml` runs
`scripts/changelog.py release-check --version X.Y.Z` before it builds: no fragment may be
pending and the version needs its section.
