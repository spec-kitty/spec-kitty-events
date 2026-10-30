---
affected_files: []
cycle_number: 1
mission_slug: events-domain-services-home-01M64MSB
reproduction_command:
reviewed_at: '2026-10-29T21:27:30Z'
reviewer_agent: claude
wp_id: WP02
---

# WP02 review feedback (cycle 1) — reviewer-renata

Verdict: **changes requested** (one [MAJOR]). Everything else checks out; the fix is a one-sentence edit to AGENTS.md.

## [MAJOR] 1. AGENTS.md says the service workflows already exist

AGENTS.md (CLAUDE.md is a symlink to it) now says: "the other workflows build the docs site, run the
acceptance and cross-repo harness checks and the CI canary, publish to PyPI on `v*` tags, and build and
release the services (ADR-0003)". `.github/workflows/` today holds `acceptance.yml`, `ci-pr-lane-canary.yml`,
`ci.yml`, `cross-repo-harness-tests.yml`, `docs-site.yml`, `publish-pypi.yml` and `publish-testpypi.yml`.
No workflow builds or releases the services: `service.yml` and `service-release.yml` are added by WP04
(FR-008), which is still `planned`. The DoD item was "Keep the GitHub Actions sentence accurate", and the
agent guide is binding orientation for every agent in this repo, so a false present-tense claim is a blocker.
If WP04 is reworked or delayed, the claim stays false.

Fix: state the service workflows as future or conditional, for example "... publish to PyPI on `v*` tags;
the service build and release workflows (`service.yml`, `service-release.yml`, ADR-0003) join them, inert
until `service/` exists." WP04 can then make it present tense, or leave it as is if the wording stays true.

While you edit the paragraph (same sentence group, optional but recommended): the kept clause "nothing
deploys on merge" is contradicted by `docs-site.yml`, whose `deploy` job publishes GitHub Pages on a push to
`main` touching `docs/**` or `site/**`. Something like "nothing deploys on merge except the docs site" keeps
the paragraph accurate. `publish-testpypi.yml` is also not mentioned; fold it into "publish to (Test)PyPI"
if you want the list complete.

## [minor] 2. The SHA-pin gate is narrower than NFR-003 and names a "Java workflow" that does not exist

Quality Gates: "Every action in the Java and service workflows is pinned by a 40-hex commit SHA (ADR 0003).
The Python workflows that predate this amendment still reference actions by tag; moving them to SHA pins is
outside this amendment."

- Faithfulness: the narrowing weakens nothing for the Python package (the charter had no SHA-pin rule before)
  and it honestly records the tag refs in `ci.yml`, `acceptance.yml`, `publish-*.yml` and
  `cross-repo-harness-tests.yml`. Good.
- Gap: NFR-003 covers *new or changed* workflows. As worded, a workflow added or edited later that is not
  "Java or service" (for example, a change to `ci.yml`) is outside the gate. Suggest: "Every action in the
  service workflows, and in any workflow added or changed from this amendment on, is pinned by a 40-hex
  commit SHA (ADR 0003, NFR-003)."
- There is no Java-specific workflow: the Java binding runs in `ci.yml` through `make test-full` ->
  `test-java`. "Java workflows" therefore refers to nothing today. Name the files, or drop "Java".
  Mirror the change in `interview/answers.yaml` `quality_gates` and in the Policy Summary line.

## [minor] 3. Amendment-log row links a core ADR that is not on core `main`

The row links `spec-kitty/spec-kitty/blob/main/docs/adr/4.x/2026-10-29-1-the-events-domain-and-its-java-services-live-in-spec-kitty-events.md`.
That file exists only on core branches `issue-6117-events-services-move-plan` and
`issue-6117-events-services-freeze`, not on `main` (checked at d68c95a4e), so the link 404s until that core PR
merges. Either cite it as "core ADR 2026-10-29-1 (pending, spec-kitty/spec-kitty issue-6117 move-plan)" or
track it for the link check at cutover. The spec and ADRs 0002–0004 do not cite this ADR. This minor does not
block approval on its own.

## [minor] 4. `kotlin` in `catalog.languages`

The only Kotlin in the repo is the Gradle Kotlin DSL build scripts. Adding `kotlin` activates Kotlin-scoped
doctrine for a repo with no Kotlin sources. This is defensible, because build logic is code, but record why
in the amendment row. Otherwise drop it. Not blocking.

## Verified (no action)

- CLAUDE.md is a git symlink (mode 120000) to AGENTS.md, so the two stay identical.
- charter.md is a curated companion in v4: `charter generate` only seeds it when it is absent
  (`_seed_charter_md_companion_if_absent`, generate.py), and never clobbers it. The answers.yaml updates back
  the four Policy Summary lines; `catalog.languages` was hand-edited, and `.kittify/config.yaml` has no
  `languages` override, so the compiled tier is authoritative at runtime.
- The Package Boundary exception matches ADR 0002 D4 and spec D5/C-002 and is no wider than them: test-only,
  a SHA pinned in `service/core-pin.json`, moved only by a reviewed PR, the three named peers, no
  build/runtime/packaging/Python-package dependency, and never in a shipped artifact. The package rule is kept
  verbatim.
- No Python-package rule is weakened: the pytest/schema/mypy gates and the Python binding bullets are
  unchanged.
- Services cites ADRs 0002–0004 and states rules only. Language Bindings changes only the Java bullet. The
  amendment-log row is present.
- The AGENTS.md `<!-- spec-kitty:orientation -->` block is byte-identical to the base (same md5).
- `spec-kitty charter context --action plan --json --no-mark-loaded` returns success with
  `Languages: java, kotlin, python`.
- `ruff check . && ruff format --check .` passes. c1a64b9 touches only owned files.
