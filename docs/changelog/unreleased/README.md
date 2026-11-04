# Changelog fragments

One file per change, so parallel pull requests never edit the same lines of
`CHANGELOG.md` ([ADR 0005](../../adr/0005-parallel-landing.md), issue #175). The layout
matches core and doc-kitty (doc-kitty ADR-0072).

This folder holds **entries, not pages**. It is outside the docs site: the site build and
the local gates exclude it natively (toolkit 0.6.0's `exclude`, in `site/astro.config.mjs`
and in `--exclude` on `npm run validate`; #181), a change here alone does not trigger the
docs build, and no index lists it.

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
  `Removed`, `Fixed`, `Security`, `Compatibility`. One fragment, one section; a change
  with entries in two sections adds two fragments (`175-x.md`, `175-x-fix.md`).
- The body is one or more `- ` bullets, written as they will read in `CHANGELOG.md`.
  No headings. Link files relative to the repository root (`docs/adr/…`), never with
  `../`: the entry is read from `CHANGELOG.md`.
- Do **not** add entries to `## [Unreleased]` in `CHANGELOG.md`: `make lint` fails a pull
  request that changes its `###` subsections by hand (`scripts/changelog.py guard`,
  compared with the branch's merge-base, so a branch that is merely behind `main` passes)
  and checks every fragment (`scripts/changelog.py check`). The opening paragraph of
  `[Unreleased]` is free text: a pull request that bumps the version may correct it.

## Releasing

The release pull request folds the fragments into `CHANGELOG.md` and deletes them:

```bash
uv run python scripts/changelog.py preview                     # what [Unreleased] will say
uv run python scripts/changelog.py collect --version 11.4.0 --date 2026-11-02   # PyPI v*
uv run python scripts/changelog.py collect                     # service-v*: stays under [Unreleased]
```

`collect` appends each fragment to its `###` subsection of `[Unreleased]` (creating the
subsection in the order above if it is missing), ordered by issue number. With `--version`
it renames that section `## [X.Y.Z] - DATE` under a fresh, empty `[Unreleased]`; it refuses
to cut an empty section unless you pass `--allow-empty`. Write or adjust the section's
opening paragraph in the same pull request.

Tag the release pull request's **merge commit**, not a later `main`: `publish-pypi.yml` runs
`scripts/changelog.py release-check --version X.Y.Z` before it builds, which refuses a
pending fragment (one that merged after the release pull request belongs to the next
release) and a version without its section. A `workflow_dispatch` run checks pyproject's
version the same way.
