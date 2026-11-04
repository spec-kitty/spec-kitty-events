# Implementation Plan: docs site overhaul — doc-kitty 0.6.0, service docs, schema and API reference

**Branch**: `issue-122-docs-site-overhaul` (planning base AND merge target for all WPs; the branch later lands on `main` as ONE PR closing #181, #193, #194, #195 — spec C-005) | **Date**: 2026-11-03 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `kitty-specs/docs-site-overhaul-01M6GBDP/spec.md`, grounded by [research.md](./research.md) (25 dispositioned findings, two maintainer rulings, three adjudicated toolkit facts) and the accepted DM-01M6GBE4ZAKE68Y49MZWR69FS5 (every schema gets a generated page).

## Summary

Adopt `@spec-kitty/doc-toolkit` **0.6.0** so the site natively excludes `docs/changelog/unreleased/` (#181), renders every diagram at build time in CI from a digest-pinned Playwright container + sandboxed PlantUML service (new ADR-0006), publishes the two service contracts as OpenAPI reference pages (#194), and turns all **168** in-scope JSON schemas into committed, generator-produced `kind: Schematic` pages guarded by a drift check (#195). In parallel, bring the README, docs entry pages, architecture and guides and the contracts README/changelogs to post-cutover truth (#193), and record the one-branch-one-PR multi-issue rule in AGENTS.md (FR-018). One PR closes all four issues (spec C-005).

## Engineering Alignment

Planning questions were settled up front by the maintainer rulings and squad dispositions in research.md (all 25 accepted or deferred-with-rationale, none contested-open), plus the following plan-level decisions:

- **A1 — Page inventory (168 pages).** 130 schemas in `src/spec_kitty_events/schemas/` (including `event.schema.json`, the normative 3.0.0 envelope) + 36 row schemas in `src/spec_kitty_events/producer_contract/data/` + the two producer-contract envelopes `producer_contract/envelope.schema.json` and `producer_contract/envelope.read.schema.json`. Excluded by rule (FR-005 edge cases): `composed/**` (generated from `data/`), `registry.json`, `fixtures/**`, `generate.py`. **Envelope reconciliation (FR-005 vs FR-006/DM wording):** all three envelope files get pages; the two producer-contract envelope pages both carry the label "CloudEvents profile, non-normative until 12.0.0" (write and read labelled as such), and `event.schema.json`'s page carries "normative 3.0.0 envelope". The normative-vs-profile *distinction* required by FR-006 and US1.3 is satisfied; the DM's "two envelope pages" is read as the two envelope *kinds*. No FR is relaxed.
- **A2 — Page homes.** The generator emits `## Uses` links to referenced sub-model pages **only when a schema actually `$ref`s another file** — the corpus today carries zero cross-file `$ref`s (grep-verified; squad S2), so shared sub-models are reached via the shared-types hub, and US1 AS2's link behaviour engages automatically the day a schema gains a cross-file reference. Event-family pages under `docs/events/<family>/` (6 existing hubs), shared sub-models under a new `docs/events/shared/` hub ("Shared types", FR-008), producer rows under `docs/architecture/producer-row-schemas/` (the existing page becomes a folder + `README.md`; route unchanged), normative envelope under `docs/api/envelope/` (existing page becomes folder + generated schema page inside), CloudEvents-profile pair inside the producer-row hub. Schema pages carry a hand-stable body block the generator owns; the toolkit adds Diagram/Properties/Source (doc-kitty ADR-0067).
- **A3 — Generator + drift check.** New stdlib-only `scripts/schema_pages.py` (`--write`/`--check`): receives schema set from disk, reads a committed family map (`scripts/schema_pages_map.json`, fail-closed on unmapped payload schema), writes pages + `BEGIN/END GENERATED` blocks in the family hubs and the two envelope/profile hubs, `--check` regenerates to a temp tree and diffs (fails naming the drifted file: missing page, orphan page, or hub block mismatch — FR-007, US4). `make lint` gains the `--check` line after `docs_index.py --check`. The docs build runs the check as a workflow step and the staging script (mjs, charter pattern) asserts staged-schemas ⇋ pages parity fail-closed.
- **A4 — Staged inputs (never committed, C-001).** `site/scripts/stage-docs-inputs.mjs` (Node ESM, no deps, charter analogue) copies: schemas tree → `site/schemas/` preserving relative paths (so toolkit-followed `$ref`s resolve), row schemas → `site/schemas/producer-rows/`, both envelopes → matching staged names; and the two contract bundles → `site/openapi/` + writes `.sha256`. `site/.gitignore` gains `schemas/`, `openapi/`. **Bundle location (squad S1):** `contracts/tools/bundle.py` refuses any `--out` inside the repository (`OUT_INSIDE_REPOSITORY`), so bundles NEVER live in-tree: CI writes `$RUNNER_TEMP/bundle-out` (service.yml already does), locally the recipe is `SKE_CONTRACT_BUNDLES_DIR=$(mktemp -d)/bundle-out uv run --frozen --no-sync python contracts/tools/bundle.py --root contracts --out "$SKE_CONTRACT_BUNDLES_DIR" --bundle-only`; the staging script reads `SKE_CONTRACT_BUNDLES_DIR` (default `${RUNNER_TEMP:-/tmp}/bundle-out/bundle`, i.e. CI works unset). Docs workflow produces the bundle inline (build job), not via cross-workflow artifacts: docs-build must report on every PR independently (ADR-0005 D3).
- **A5 — docs-site.yml rework (build-mode rendering, FR-003).** Charter pattern (`.github/workflows/docs-site.yml` in spec-kitty-charter): jobs `build` moves into the digest-pinned `mcr.microsoft.com/playwright:v1.62.1-noble@sha256:dcc5531e…7580e` container (tag == new `playwright@1.62.1` devDependency) with `services: plantuml` at `plantuml/plantuml-server:jetty-v1.2026.9@sha256:065c66ad…3618`, `PLANTUML_SECURITY_PROFILE: SANDBOX`. Readiness probe ≤120s (NFR-002), fails naming the service. `DK_DIAGRAM_BUILD_RENDER=build` + in-step guard (`test "$DK_DIAGRAM_BUILD_RENDER" = build`); `DK_PLANTUML_SERVER_URL=http://plantuml:8080/svg/`; `DK_PUBLISH_DRAFTS=on`. Diagram cache `DK_BEOE_CACHE_DIR=$GITHUB_WORKSPACE/.dk-diagram-cache`, actions/cache keyed `dk-diagram-0.6.0-${{ hashFiles('docs/**/*.md') }}` with `dk-diagram-0.6.0-` restore-keys. Python in-container via `apt python3-venv` + `pip install -r scripts/docs/requirements.txt` (new, pins PyYAML==6.0.3 == uv.lock) for `docs_index.py` and `bundle.py --bundle-only`. Post-build gates (charter pattern): schema-page count == staged schema count AND every schematic page holds a rendered `<svg>` figure; published `openapi.yaml(.sha256)` per service verifies and equals the in-run bundle's digest; `npm run check:built-links`. Job name stays `build`/`docs-build` (C-002). All action refs stay SHA-pinned (charter quality gate for changed workflows); both images digest-pinned (NFR-005). Push trigger paths and the in-job `PATTERN` both gain `contracts/**`, `src/spec_kitty_events/schemas/**`, `src/spec_kitty_events/producer_contract/**`, `scripts/schema_pages.py` — the parity test keeps the two lists in agreement (FR-011).
- **A6 — Slicing + shared-file ownership.** Serial lane for the shared plumbing files, then parallel content, then gates (see Parallel Work Analysis). `site/astro.config.mjs`, `site/package.json`, `.github/workflows/docs-site.yml`, `docs/api/README.md` each have exactly one owning WP per landing order; later WPs inherit them merged.
- **A7 — Content last.** README (FR-012), entry pages (FR-013), architecture/guides/truth fixes (FR-014/015), contracts release-rule docs (FR-016), glossary (FR-017), AGENTS.md (FR-018), ADR-0005 amendment (FR-019) land after the reference routes exist, so `check:built-links` proves every new link (SC-002).
- **A8 — Tests (FR-020).** `tests/test_changelog_fragments.py:695-723` rewritten to assert `exclude: ['changelog/unreleased']` in `site/astro.config.mjs`, `--exclude` in the `validate` script, and the absence of the `rm -rf` step; `tests/test_ci_gate_workflows.py` gains DOCS_DETECT cases (`contracts/mission-status/openapi.yaml`→true, a schema file→true, a producer row→true; `docs/changelog/unreleased/*`→false stays) while `test_docs_detect_covers_every_push_path` enforces push-path/PATTERN parity; a new small test pins `DK_DIAGRAM_BUILD_RENDER=build` + guard and both image digests in docs-site.yml. New `tests/test_schema_pages.py` covers the generator and drift check (US4/SC-005).
- **A9 — Registry auth (DM-01M6GC6ZQ7ATZR6WBA49C03CXV).** Operator refreshes `gh auth` with `read:packages` and writes the token to user `~/.npmrc`; WP01 verifies `npm view @spec-kitty/doc-toolkit@0.6.0` before regenerating the lockfile. CI needs nothing new (`setup-node` registry-url + `packages: read` already works).
- **A10 — `CLAUDE.md`** is a symlink to `AGENTS.md`; FR-018 edits AGENTS.md once.

## Technical Context

**Language/Version**: Python 3.10+ (scripts, stdlib-only additions; ruff-linted), Node 22.22+ (docsite), Java 25 (untouched, not installed locally), Bash/GitHub Actions YAML (workflows)
**Primary Dependencies**: `@spec-kitty/doc-toolkit` 0.6.0 (from 0.5.0) with exact peers `starlight-openapi@0.27.0` + `@astrojs/markdown-satteri@0.4.4`, devDep `playwright@1.62.1`; doc-kitty 0.6.0 features: `exclude` (ADR-0070), `openapi` option (ADR-0049/#244), `kind: Schematic` (ADR-0067/#245), build-time diagrams (ADR-0040)
**Storage**: N/A — static site; generated pages are committed Markdown; staged inputs are git-ignored build copies
**Testing**: `uv run pytest --no-cov -q tests/test_changelog_fragments.py tests/test_ci_gate_workflows.py tests/test_schema_pages.py tests/test_docs_index.py tests/test_docs_site_workflow.py` (new ones named); packaged conformance gate `uv run pytest --pyargs spec_kitty_events.conformance`; `ruff check . && ruff format --check .`; `make lint` (adds `schema_pages.py --check`); site: `npm ci && npm run validate && DK_DIAGRAM_BUILD_RENDER=build DK_PLANTUML_SERVER_URL=http://localhost:8080/svg/ npm run build && npm run check:built-links` with `docs/changelog/unreleased/` present
**Target Platform**: GitHub Pages static site (project site, base `/spec-kitty-events`); CI `ubuntu-24.04` + containers
**Project Type**: docs/tooling overhaul on a polyglot single repo (no contract or package changes — C-004)
**Performance Goals**: CI docs build ≤15 min warm cache / ≤30 min cold (NFR-001); PlantUML readiness ≤120 s (NFR-002)
**Constraints**: one branch, one PR (C-005); `docs-build` job name unchanged and still required (C-002); staged inputs never committed (C-001); merged `kitty-specs/` missions untouched (C-003); every workflow image/action pinned by digest/SHA (NFR-005, charter amendment); ADRs immutable, 0005 amended not rewritten (C-007); no Java locally — Gradle verification deferred to CI (operator instruction)
**Scale/Scope**: 168 generated schema pages total — 129 in **`docs/events/`** (88 family payloads + 41 shared), 38 under `docs/architecture/producer-row-schemas/` (36 rows + 2 CloudEvents-profile envelopes), 1 normative envelope under `docs/api/envelope/`; 2 OpenAPI references (mission-status, mission-write; `released: false` until first final `service-v*` tag — FR-009); ~20 authored pages updated (README, entry, context, architecture, guides, contracts docs, glossary); 1 new ADR + 1 ADR amendment; 1 workflow substantially reworked; 4 changelog fragments; ~5 test files touched/added

### Local environment (verified 2026-11-03)

Node 22.23.4, npm 10.9.9, Docker 29.9.2 available; **no GitHub Packages token yet** (A9/DM); Java absent by instruction — `service.yml`-style Gradle legs are CI-only here.

## Charter Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Charter rule | Status | Note |
|---|---|---|
| Quality gates: pytest, schema checks, mypy --strict; PR passes conformance + Gradle verifyAll | Pass | Mission touches no package or Java code paths (C-004); new Python scripts are stdlib-only and enter the ruff/mypy surface; conformance gate is re-run per AGENTS.md. |
| SHA-pinned actions in any workflow added or changed | Pass | docs-site.yml is changed: all `uses:` stay/are SHA-pinned (charter's v5.1.0 checkout etc.); FR-020's new test pins the two container digests; NFR-005 covers images. |
| Compatibility review for envelope/payload/schema-version changes | Pass (not triggered) | Zero contract/schema content changes (C-004); pages are generated *from* committed schemas. |
| Package boundary / release lines (PyPI `v*`; services `service-v*`, ADR-0002/0003/0004) | Pass | Docs are brought into compliance with the lines (FR-012/016); nothing ships or versions. |
| Docs stay synchronized (charter directive) | Pass — this mission *is* the sync | research.md findings 11–19 enumerate the drift being fixed. |
| DocKitty authority paths (docs/context terminology, docs/adr intent) | Pass | Glossary additions (FR-017) go to `docs/glossary/README.md`; new decision recorded as ADR-0006. |

Re-check after Phase 1 design: every schema/route surface above resolves inside `site/` (adjudicated in research.md from toolkit source), the render-mode switch is site-wide and accepted, and no gate regresses.

**Complexity Tracking**: none — no charter violations require justification. (The single lockfile regeneration and two new exact peer pins are reviewed in-PR per the supply-chain posture in research.md addendum.)

## Project Structure

### Documentation (this feature)

```
kitty-specs/docs-site-overhaul-01M6GBDP/
├── spec.md               # committed a360aadd
├── research.md           # Phase 0 squad synthesis + plan-phase addendum (this plan)
├── plan.md               # this file
├── data-model.md         # Phase 1: page/hub/reference/staged-input entities
├── quickstart.md         # Phase 1: local docs-build recipe + verification commands
├── meta.json             # gains "contracts": "none" + rationale (mission defines no interfaces)
├── checklists/requirements.md
├── decisions/            # DM-01M6GBE4ZAKE68Y49MZWR69FS5, DM-01M6GC6ZQ7ATZR6WBA49C03CXV
└── tasks/                # /spec-kitty.tasks output
```

### Source Code (repository root) — delivery surface

```
site/package.json, site/package-lock.json   # toolkit 0.6.0 + exact peers + playwright (WP01/WP02/WP03, serial)
site/astro.config.mjs                        # exclude + diagrams build-mode comment + openapi block (same chain)
site/scripts/stage-docs-inputs.mjs           # NEW staging + parity check (WP03, extended WP04)
site/.gitignore                              # schemas/, openapi/
scripts/docs_index.py                        # unchanged (no page registration needed)
scripts/schema_pages.py                      # NEW generator + drift check (WP04)
scripts/schema_pages_map.json                # NEW committed family map (WP04)
scripts/docs/requirements.txt                # NEW PyYAML==6.0.3 for the CI venv that runs bundle.py (WP03)
Makefile                                     # lint gains schema_pages.py --check (WP04)
docs/events/<family>*/                       # generated payload/sub-model schema pages (WP04)
docs/events/shared/                          # NEW shared-types hub + pages (WP04, FR-008)
docs/architecture/producer-row-schemas/      # folder hub + 38 generated pages (WP04; was a single .md)
docs/api/envelope/                           # folder hub + normative envelope page (WP04)
docs/api/services.md                         # NEW services hub (WP03, FR-010)
docs/api/README.md                           # links services hub (WP03)
.github/workflows/docs-site.yml              # container + plantuml + probes + path filters (WP01 rm-removal, WP02 rework, WP03 bundle, WP04 count check)
docs/adr/0006-*.md (NEW), 0005 amendment     # WP02 / WP06
README.md, docs/README.md, docs/context/*, docs/_meta/sections.yaml,
docs/architecture/{README,overview}.md, docs/guides/*, docs/glossary/README.md,
contracts/README.md, contracts/{mission-status,mission-write}/CHANGELOG.md,
AGENTS.md, .github/workflows/service{,-release}.yml comments  # WP05/WP06 (#193 + truth fixes + FR-016..019)
docs/changelog/unreleased/{181,193,194,195}-*.md             # WP07 (FR-021)
tests/…                                       # FR-020 rewrites + new pins/generator tests
```

**Structure Decision**: the site consumes staged single sources; nothing generated or staged is committed except the 168 page files themselves (C-001). The generator and maps are committed; routes follow existing section identity (`docs/_meta/sections.yaml` unchanged except purpose text).

## Implementation Concern Map (for /spec-kitty.tasks)

| IC | Concern | FRs | Issues |
|---|---|---|---|
| IC-01 | Toolkit 0.6.0 bump + native exclude (#181) | FR-001, FR-002, FR-020(fragments), FR-021(181 via final WP) | #181 |
| IC-02 | Build-time diagram rendering in CI + ADR-0006 + docsite guide + trigger paths | FR-003, FR-004, FR-011, FR-020(mode/pins) | #195 |
| IC-03 | Service API reference (bundle staging + openapi pages + hub) (#194) | FR-009, FR-010 | #194 |
| IC-04 | Schema page generator + 168 pages + drift check (#195) | FR-005, FR-006, FR-007, FR-008 | #195 |
| IC-05 | Entry-point docs: README, docs home, context, sections, api/architecture indexes (#193) | FR-012, FR-013 | #193 |
| IC-06 | Truth pass: architecture, guides, contracts docs, glossary, AGENTS.md, ADR-0005 amendment | FR-014..FR-019 | #193 |
| IC-07 | Changelog fragments (FR-021) + full verification sweep vs NFR/SC | FR-021, NFR-001..005, SC-001..006 | #181 #193 #194 #195 |

## Parallel Work Analysis

### Dependency Graph

```
WP01 (IC-01, #181) ──> WP02 (IC-02, render/paths/ADR-0006) ──> WP03 (IC-03, #194) ──> WP04 (IC-04, #195)
                                                                                         │
                                                              WP05 (IC-05) <─────────────┤ (routes final)
                                                              WP06 (IC-06) <─────────────┘
                                                                                         │
                                                              WP07 (IC-07) <── both + all WPs
```

WP01→WP02→WP03→WP04 are strictly serial: they converge on the same plumbing files and each WP's landing must leave the branch green (validate/build/check:built-links pass locally). WP05 and WP06 are parallel after WP04 (disjoint file sets; WP05 touches no WP06 files). WP07 is the gate.

### Work Distribution

- **Shared-file ownership (single owner per landing):**
  - `site/package.json`/lockfile: WP01 (bump) → WP02 (playwright devDep) → WP03 (openapi peers)
  - `site/astro.config.mjs`: WP01 (exclude) → WP02 (diagrams comment) → WP03 (openapi option)
  - `.github/workflows/docs-site.yml`: WP01 (drop `rm -rf`) → WP02 (container/plantuml/probes/paths) → WP03 (bundle steps + checksum gate) → WP04 (168-page diagram count)
  - `site/scripts/stage-docs-inputs.mjs`: WP03 (bundle staging) → WP04 (schema staging + parity)
  - `docs/api/README.md`: WP03 only
  - `docs/architecture/producer-row-schemas.*`: WP04 only (WP06 must not touch it)
  - `AGENTS.md`: WP06 only (WP01 does NOT edit it; the rewritten fragment tests no longer require the "#181" sentence)
- **WP05 owns**: root README.md, docs/README.md, docs/context/*, docs/_meta/sections.yaml (purpose text), docs/architecture/README.md.
- **WP06 owns**: docs/architecture/overview.md + services/* descriptions, docs/guides/*, contracts/README.md + contracts/*/CHANGELOG.md, docs/glossary/README.md, AGENTS.md, service.yml/service-release.yml comments, ADR-0005 amendment.
- **WP07 owns**: docs/changelog/unreleased/* and the verification record only.

### Coordination Points

- Every WP keeps the Python gates green (`make lint`, touched tests, conformance gate) and runs the site chain per quickstart.md once the token (DM-01M6GC6Z…) exists; WP01 is the first npm consumer and re-verifies auth first.
- Links from WP05/WP06 pages into `reference/api/<label>/` and family/schema routes are written against the WP03/WP04 contract in this plan (routes: `<base>/reference/api/mission-status/`, `<base>/reference/api/mission-write/`; envelope routes under `/api/envelope/` and `/architecture/producer-row-schemas/`).
- One adversarial-squad review (reviewer-renata, planner-priti, implementer-ivan) runs after tasks-finalize, before WP01 implementation (operator instruction); its contested findings are dispositioned here in research.md.
- The final PR receives exactly one adversarial-squad review and the full verification record (commands + counts), per AGENTS.md §6–§7.
