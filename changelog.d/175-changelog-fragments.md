---
section: Changed
---

- Pull requests record their changelog entry as a `changelog.d/<issue>-<slug>.md` fragment
  instead of editing `CHANGELOG.md` (#175, ADR 0005), so parallel pull requests stop
  colliding on `[Unreleased]`.
  - `scripts/changelog.py` checks the fragments, previews `[Unreleased]`, and `collect`s
    them into `CHANGELOG.md` at release time in the existing shape; the released history is
    untouched.
  - `make lint` runs `check` and `guard`; `guard` fails a pull request that edits
    `[Unreleased]` by hand.
  - `publish-pypi.yml` runs `release-check` before it builds (no pending fragment, a section
    for the tagged version) and pins its actions by commit SHA, per the charter's Quality Gates.
