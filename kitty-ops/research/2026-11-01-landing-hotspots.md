# Research: PR landing hotspots and merge-queue readiness

Op `01M6B1AEFY60CCWT7409ZVY6AB` (architect-alphonso), 2026-11-01, read-only squad of two
lenses (changelog and indexes; workflows and merge queue) plus the orchestrator's own
checks. `origin/main` at `9796adcc`. Nothing was changed by the research.

## Why PRs collide

Commits on `main` since 2026-10-18 (87, one of them the parentless root `831707c8`):

| File | Commits touching it | Who edits it |
|---|---|---|
| `CHANGELOG.md` | 13 | feature, fix, test and release commits alike |
| `docs/changelog/README.md` | 11 | every PR that adds a changelog page (list line + `updated:`) |
| `docs/adr/README.md` | 5 | every ADR PR (hand table + `updated:`) |
| `pyproject.toml`, `__init__.py`, `uv.lock`, `COMPATIBILITY.md` | 4–6 | release commits **and** feature commits (`3733d2af`, `b3dc28d0`) |

Two shared hunks cause most conflicts: the top of `## [Unreleased]` in `CHANGELOG.md`
(each PR appends under `### Added`/`### Changed`/`### Fixed`) and the top of the
hand-kept list plus the `updated:` frontmatter line in every section `README.md`.

## Changelog facts

- `CHANGELOG.md` (2026 lines) is Keep a Changelog shaped: `## [Unreleased]`, then 57
  `## [x.y.z] - YYYY-MM-DD` sections. Each section opens with a prose paragraph, then
  `###` subsections. Subsection names go beyond Keep a Changelog (Breaking 10,
  Migration 7, Compatibility 4, …). Older headings (`## 4.0.0 — …` and below) are free-form.
  Link references exist only for `[2.3.1]` and older.
- `service-v*` releases have no heading of their own: `service-v0.1.0-rc.1` is a bullet
  under `[Unreleased]`. Contract modules keep their own `contracts/*/CHANGELOG.md`
  (checked by `contracts/tools/*`); those are per-module and not hot.
- **No check inspects `CHANGELOG.md`, `docs/changelog/` or `[Unreleased]`** today. The
  version checks are `tests/test_compatibility_doc.py` (COMPATIBILITY/README masthead equal
  `__version__`), `tests/unit/test_placeholder.py`, `scripts/check_version_not_amended.py`
  (`make lint`), `java/build.gradle.kts` `checkVersionMatchesPyproject`, and the tag checks in
  `publish-pypi.yml` / `service-release.yml`. "Port the existing changelog checks" therefore
  means new checks; nothing has to move.
- **No release tooling reads the changelog.** `publish-pypi.yml` creates no GitHub release;
  `service-release.yml` writes boilerplate notes. Assembly is a manual release-PR step today.
- `docs/changelog/*.md` are narrative per-release/per-change pages (frontmatter: title,
  description, doc_status, updated, type/kind Changelog, `sidebar.order`, tags). No `date`
  field; the date is the filename prefix. Naming is inconsistent (`diary-`, versioned,
  unversioned). `sidebar.order` already collides once (`-202610192` twice).
- Instructions to edit `CHANGELOG.md`: `contracts/versioning-and-compatibility.md:48-58,114`
  and `docs/api/versioning-and-compatibility.md:57-62` (the bump artifact set). No PR
  template exists; CLAUDE.md/AGENTS.md and `docs/guides/contributing.md` say nothing about
  where an entry goes.

## Index facts

- `docs/changelog/README.md:26-39` is a hand list `- [date — text](./file.md)`. Nine of 14
  lines equal the page title; four add a suffix ("(package unchanged)", …). Rebuilding it
  needs title + filename date + `sidebar.order`.
- `docs/adr/README.md:34-39` is a hand table `ID | Title | Status | Date`. Status lives in
  the ADR body (`**Status:** Accepted`), not in frontmatter.
- Other hand lists (`docs/guides`, `docs/architecture`, `docs/architecture/services`,
  `docs/api`, `docs/events`, `docs/README.md`, `contracts/README.md`) change rarely
  (≤3 touches). Every section README carries an `updated:` line that colliding PRs bump.
- Docs site: Astro + Starlight + `@spec-kitty/doc-toolkit` (`site/`), `README.md` is each
  section's index, the sidebar follows `sidebar.order`. `npm run validate` checks `../docs`
  before `astro build`, so a build-time generator must run before validate.

## Workflow facts (merge queue)

No workflow has `merge_group:`; `pr:pause-ci` does not exist; no branch protection or
ruleset is configured (AGENTS.md:14).

| Workflow / check | PR trigger | merge_group hazard |
|---|---|---|
| `ci.yml` / `suite` | `labeled, synchronize, reopened` (not `opened`), squad-label `if:` | "Resolve target" `case` falls to `*)` → empty `inputs.sha` → **exit 64**; concurrency group collapses to `ci-main` and cancels across queue entries and main pushes |
| `acceptance.yml` / `acceptance` | every PR to main | none; trigger only. Tag-pinned actions |
| `cross-repo-harness-tests.yml` | every PR to main | none in YAML; `tests/test_cross_repo_harness_workflow.py:98` asserts `on == {push, pull_request}`. Tag-pinned actions |
| `service.yml` / `service-gate` | **path-filtered** | `merge_group` has no `paths`; a path-filtered PR check never reports, so it cannot be required as-is. `detect` only tests `service/` presence. `service-gate` already passes on `skipped` |
| `docs-site.yml` / `build` | **path-filtered** | as above; the upload step's `if: != 'pull_request'` would upload on merge_group (deploy is safe: `ref == refs/heads/main`) |
| canary, publish-pypi, publish-testpypi, service-release | none | no change; `tests/test_service_workflows.py:103-106` pins `service-release.yml` triggers |

- Charter Quality Gates: every workflow **changed** from the 2026-10-29 amendment on must
  SHA-pin its actions (`@<40-hex> # vX.Y.Z`). So the merge_group change also pins
  `ci.yml`, `acceptance.yml` and `cross-repo-harness-tests.yml`.
- `tests/test_service_workflows.py:173-179` requires `service-gate.needs` = every other job.
- Main uses rebase merges (linear `(#156)` commits) and occasional merge commits.
- Squad label gate on merge_group: `event_name != 'pull_request'` → the suite runs. A
  skipped `suite` on the PR side reports "skipped", which satisfies a required check.

## Core prior art (`spec-kitty/spec-kitty@f2a4544d`)

- Core keeps a single hand-edited `docs/changelog/CHANGELOG.md` (root symlink); its fragment
  remedy is not merged yet, so there is nothing to copy byte-for-byte.
- Core's `pr:pause-ci` (#6961): `!contains(labels, 'pr:pause-ci')` on every PR job, plus
  `pr-pause-ci.yml` (`pull_request_target` labeled/unlabeled; cancels runs for the head SHA,
  posts failing check runs under the required gate names, re-runs on unlabel; never checks
  out PR code).
- No `merge_group:` on core main yet.

## Also open: version-file hotspot

`scripts/check_version_not_amended.py` fails a PR that changes `src/` while the version is
already tagged, so the **first** `src/` PR after each release must bump `pyproject.toml`,
`__version__`, `uv.lock`, `COMPATIBILITY.md` and the README masthead. Two such PRs in
parallel conflict on all five. Not in this delivery's three changes; raised as a decision.

## Decisions (recommendations sent to the parent)

1. **Fragments:** `changelog.d/<issue>-<slug>.md` at repo root (outside the docs site),
   frontmatter `section:` from a fixed set (Breaking, Migration, Added, Changed, Deprecated,
   Removed, Fixed, Security), body = the bullet(s) verbatim. `scripts/changelog.py`
   `check` / `preview` / `collect [--version X.Y.Z --date D]`; `collect` folds fragments into
   `[Unreleased]` (service-v* or interim) or cuts a version section (PyPI `v*`), then
   deletes them. A guard fails a PR that edits `CHANGELOG.md` without collecting fragments.
   `docs/changelog/` narrative pages stay optional and unchanged.
2. **Indexes:** generate the list between markers at docs-site build time (before
   `validate`), plus a `--check` that the committed README carries only the markers. No
   bot commit, no follow-up PR, nothing written to `main`. Covers `docs/changelog/README.md`
   and `docs/adr/README.md`; the rarely-touched READMEs stay hand-kept.
3. **Squad label on merge_group:** run the full suite unconditionally (the label gate only
   saves PR-side cost); add `opened`/`ready_for_review` to ci.yml's PR types so `suite`
   always reports.
4. **Path filters:** drop PR `paths:` from `service.yml` and `docs-site.yml`; `detect`
   diffs `merge_group.base_sha..head_sha` / PR base..head and the jobs skip, so the gate
   always reports and can be required.
5. **Queue settings:** rebase merge, build concurrency 5, group size 1–5, 5 min wait,
   check timeout 120 min (native legs), required: `suite`, `acceptance`,
   `cross-repo-harness-tests`, `service-gate`, docs `build`.
6. **pr:pause-ci:** port core's design scoped to the required gates.
7. **Version files:** follow-up issue: after each tag, a release chore opens the next
   `.dev0` version so feature PRs never bump.

## Operator rulings (2026-11-01, relayed by the orchestrator)

All seven recommendations stand except the fragment location. Fragments live in
`docs/changelog/unreleased/` to match core and doc-kitty (doc-kitty ADR-0072); the folder
is excluded from the docs site, its build, validate, frontmatter rules and any index, with a
test. Toolkit 0.5.0 cannot exclude a folder, so the docs job removes it from its checkout
until a release carries doc-kitty's `exclude` (#181). The version-file hotspot is #180.
ADR 0005 records the rulings.
