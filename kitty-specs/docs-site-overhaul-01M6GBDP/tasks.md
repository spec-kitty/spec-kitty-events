# Work Packages: docs-site-overhaul-01M6GBDP

**Inputs**: Design documents from `kitty-specs/docs-site-overhaul-01M6GBDP/`
**Prerequisites**: plan.md (required), spec.md (user stories), research.md, data-model.md, quickstart.md, no contracts/ ("contracts": "none" in meta.json)

**Tests**: Explicitly in scope — FR-020 demands test rewrites and new pins; US4/SC-005 demand a drift-check with failing-probe evidence; per-PR gates per AGENTS.md §6.

**Organization**: 38 subtasks (`T001`–`T038`) roll into 7 work packages (`WP01`–`WP07`). WP01–WP04 form a strict serial chain over the shared docsite plumbing files (`site/package.json`, `site/astro.config.mjs`, `.github/workflows/docs-site.yml`, `site/scripts/stage-docs-inputs.mjs`) — each file has exactly one owning WP; later WPs that must touch it do so as small, justified, recorded out-of-map edits. WP05 and WP06 run in parallel after WP04 (disjoint file sets). WP07 is the closing gate.

**Prompt Files**: one file per WP under `kitty-specs/docs-site-overhaul-01M6GBDP/tasks/`.

**Shared-file ownership matrix** (from plan.md A6; the finalize validator enforces non-overlap):

> Two recorded exceptions to keep in mind when reading guard output (squad S17/S18):
> (1) WP01 T003 forward-edits WP02-owned `docs-site.yml` (removes the `rm -rf` stopgap) because the rewritten WP01 tests assert its absence — every commit stays green and WP02's prompt anticipates it.
> (2) The out-of-map edits named below are planned and serial; the commit guard runs in warn mode and will legitimately flag them — that noise is expected, not a violation to "fix".

| File | Owning WP | Others touching it |
|---|---|---|
| `site/package.json`, `site/package-lock.json` | WP01 | WP02 (playwright devDep), WP03 (openapi peers) — out-of-map, justified, serial |
| `site/astro.config.mjs` | WP01 | WP02 (diagrams comment), WP03 (openapi option) — out-of-map |
| `.github/workflows/docs-site.yml` | WP02 | WP01 (`rm -rf` removal), WP03 (bundle + checksum steps), WP04 (drift step + 168-diagram count) — out-of-map |
| `site/scripts/stage-docs-inputs.mjs` | WP03 | WP04 (schema staging + parity) — out-of-map |
| `docs/api/README.md` | WP03 | none |
| `docs/architecture/producer-row-schemas/**` | WP04 | none |
| `AGENTS.md` | WP06 | none (WP01's test rewrite stops requiring the "#181" sentence but does not edit AGENTS.md) |

---

## Work Package WP01: Toolkit 0.6.0 bump + native changelog exclusion (Priority: P0 — #181)

**Goal**: Move the docsite to `@spec-kitty/doc-toolkit` 0.6.0 with the fragment folder excluded natively; the CI-only `rm -rf` stopgap disappears (FR-001, FR-002).
**Independent Test**: With `docs/changelog/unreleased/` present and holding fragments, `npm run validate` and a client-mode build succeed and build no fragment page; rewritten fragment tests pass.
**Prompt**: `/tasks/WP01-toolkit-bump-and-exclude.md`
**Requirement Refs**: FR-001, FR-002, FR-020

### Included Subtasks

T001 Verify GitHub Packages auth (DM-01M6GC6ZQ7ATZR6WBA49C03CXV); bump `@spec-kitty/doc-toolkit` to 0.6.0 in `site/package.json`; regenerate `site/package-lock.json`; prove `npm ci` is green
T002 Add `exclude: ['changelog/unreleased']` to `site/astro.config.mjs`; add `--exclude ../docs/changelog/unreleased` to both sides of the `validate` script
T003 Remove the `rm -rf docs/changelog/unreleased` step from `.github/workflows/docs-site.yml` (out-of-map on WP02-owned file — recorded with rationale)
T004 Rewrite `tests/test_changelog_fragments.py` stopgap tests into exclusion assertions (site config, validate script, no rm step; keep push-path negation and fragment-README tests)
T005 Run the WP verification suite (pytest touched+blast, conformance gate, ruff, changelog check+guard, site chain client-mode)

### Implementation Notes

- Lockfile regen is the first GitHub Packages consumer: run the auth check before anything else.
- Nothing else about diagrams/openapi/schematics changes here; build stays client-mode until WP02.

### Parallel Opportunities

- None inside the WP beyond T004 [P] vs T005 sequencing; the WP is the chain head.

### Dependencies

- None (starting package).

### Risks & Mitigations

- Registry 401 → stop, get the token per quickstart.md §Prerequisites; never vendor the tarball.
- Peer drift → bump ONLY the toolkit pin in T001; peers arrive in WP03, playwright in WP02.

---

## Work Package WP02: Build-time diagram rendering, trigger paths, ADR-0006 (Priority: P0 — #195 infrastructure)

**Goal**: docs-site.yml renders all diagrams at build time from digest-pinned images with a bounded readiness probe and diagram cache; trigger paths cover contracts and schema folders in push AND in-job detect; ADR-0006 records the decision (FR-003, FR-004, FR-011).
**Independent Test**: New pins/mode tests and the extended DOCS_DETECT + push-path parity tests pass; the workflow file parses; a local build with `DK_DIAGRAM_BUILD_RENDER=build` + local PlantUML renders Mermaid to SVG.
**Prompt**: `/tasks/WP02-build-mode-diagram-rendering.md`
**Requirement Refs**: FR-003, FR-004, FR-011, FR-020, NFR-001, NFR-002, NFR-005

### Included Subtasks

T006 Rework the `build` job in `.github/workflows/docs-site.yml`: Playwright container (digest-pinned, tag == playwright devDep), PlantUML service (digest-pinned, SANDBOX), readiness probe bounded ≤120 s failing w/ service named, `DK_DIAGRAM_BUILD_RENDER=build` + in-step guard, `DK_PLANTUML_SERVER_URL`, diagram cache (`DK_BEOE_CACHE_DIR` + actions/cache keyed on toolkit version + docs hash), `npm ci --ignore-scripts`, container python3-venv for `scripts/docs_index.py`
T007 Extend push `paths:` and the in-job `PATTERN` with `contracts/**`, `src/spec_kitty_events/schemas/**`, `src/spec_kitty_events/producer_contract/**`, `scripts/schema_pages.py`; keep the fragment negation
T008 [P] Pin `playwright@1.62.1` devDependency in `site/package.json` + lockfile (out-of-map on WP01 file, serial, justified); add the digest-resolution comment convention (tag+digest+pin move together)
T009 New `tests/test_docs_site_workflow.py` pinning build mode + guard + image pins (DERIVES the expected playwright tag from site/package.json's pin; iterates EVERY container/service image asserting `@sha256:`; pins probe constants max-time×iterations+sleep ≤ 120 s and the named-service failure message); extend `tests/test_ci_gate_workflows.py` DOCS_DETECT cases and keep `test_docs_detect_covers_every_push_path` green
T010 Write `docs/adr/0006-build-time-diagram-rendering.md` (decision, pins + how to bump, trade-offs; per FR-004); ensure the generated ADR index needs no manual edit
T011 Rewrite the build/rendering sections of `docs/guides/docsite.md` (toolkit pin sentence, local PlantUML recipe from quickstart.md, `--exclude` validate line, what CI runs)

### Implementation Notes

- Mirror spec-kitty-charter's `.github/workflows/docs-site.yml` (cited in research.md) — reuse its probe, cache and pin-comment shape verbatim where the only difference is this repo's paths.
- Site defaults stay client-mode on missing Playwright; the WORKFLOW pins build mode via env + guard (never silently fall back).

### Dependencies

- Depends on WP01.

### Risks & Mitigations

- Ready-probe flakiness → same bounded retry loop as charter (30 × (curl 5 s cap + sleep 2) ≈ ≤90 s worst case within the 120 s budget) and fail naming the service.
- Cache poisoning across toolkit versions → cache key embeds the toolkit version; bump rule documented in ADR-0006.

---

## Work Package WP03: Service API reference pages (Priority: P1 — #194)

**Goal**: Publish the bundled `mission-status` and `mission-write` contracts as OpenAPI reference pages with downloadable raw file + checksum, marked unreleased, introduced by a new `docs/api/services.md` hub (FR-009, FR-010).
**Independent Test**: After producing bundles locally, the build emits `<base>/reference/api/{mission-status,mission-write}/` plus raw `openapi.yaml` + `.sha256`; the published bytes hash-equal the bundle; both pages carry the unreleased badge; the hub and `docs/api/README.md` link them.
**Prompt**: `/tasks/WP03-service-api-reference.md`
**Requirement Refs**: FR-009, FR-010, NFR-004, SC-002

### Included Subtasks

T012 [P] Create `site/scripts/stage-docs-inputs.mjs` (bundle staging from `build/contracts/bundle/<module>/`, fail-closed with remediation message) + `site/.gitignore` entries (`openapi/`, and `schemas/` pre-declared for WP04) + wire `prebuild`/`predev` chain (`docs_index.py --write` stays first)
T013 [P] Add the `openapi` option to `site/astro.config.mjs` (`starlight-openapi` renderer; specs for both services, `released: false`, with the flip rule comment pointing at FR-009's guide text) and the exact peers to `site/package.json` + lock regen (both out-of-map edits, serial, justified)
T014 Add to `.github/workflows/docs-site.yml` (out-of-map on WP02 file): pip install of `scripts/docs/requirements.txt` in the venv, then `bundle.py --bundle-only` for both modules, and post-build published-vs-bundle sha256 assertions for both services
T015 Create `scripts/docs/requirements.txt` pinning PyYAML 6.0.3 (== uv.lock, for `bundle.py`)
T016 [P] Write `docs/api/services.md` hub (two services, release lines, run/integrate pointer to guides, reference links, unreleased rule) and link it from `docs/api/README.md`
T017 New `tests/test_docs_service_reference.py`: astro.config holds the two specs with correct labels and `released: false`; `docs/api/README.md` links `services.md`; hub frontmatter valid
T018 Full site verification incl. built-link check and checksum equivalence

### Implementation Notes

- Bundle staging input path convention: `build/contracts/bundle/{mission-status,mission-write}/openapi.yaml` (bundle.py `--out build/contracts`, dir git-ignored). Docsite guide updated by WP06's running-the-service/docsite cross-links only if paths shift — they don't.
- Label choice `mission-status` / `mission-write` doubles as the route segment; document in the hub that these are contract versions of the `service-v*` line.

### Dependencies

- Depends on WP02.

### Risks & Mitigations

- Multi-file contract (external `$ref`) → bundle first, never point the option at `contracts/<module>/openapi.yaml`.
- Route collision on `reference/api` → mount `section: 'reference/api'` (default) is outside docs/ ownership; generator (WP04) never writes there.

---

## Work Package WP04: Generated schema pages, hubs and drift check (Priority: P1 — #195 core; DM-01M6GBE4ZAKE68Y49MZWR69FS5)

**Goal**: A committed generator produces one `kind: Schematic` page for each of the 168 in-scope schemas, family hubs link them all, and a drift check (build + `make lint`) fails closed on any schema/page/hub-link mismatch (FR-005..008).
**Independent Test**: `python3 scripts/schema_pages.py --check` passes; adding a temp schema or renaming a page fails the check naming the file (SC-005); full build renders a diagram on every page (NFR-003); route count == staged count gate green.
**Prompt**: `/tasks/WP04-generated-schema-pages.md`
**Requirement Refs**: FR-005, FR-006, FR-007, FR-008, C-001, NFR-003, SC-001, SC-005

### Included Subtasks

T019 Write `scripts/schema_pages_map.json` (family assignment for every `schemas/*.schema.json` payload; fail-closed) and `scripts/schema_pages.py` (`--write`/`--check`; cross-file-`$ref`-derived sub-model links, conditional — the corpus carries zero cross-file `$ref`s today (squad S2); envelope labelling per plan A1/FR-006; hub `BEGIN/END GENERATED` blocks). EXIT CRITERION before T021: prove one dotted-stem page (e.g. a `data/*.v1.schema.json`) builds to a valid route, else normalise dots→double-dashes ONCE in generator+tests.
T020 Extend `site/scripts/stage-docs-inputs.mjs` (out-of-map on WP03 file): mirror the three schema source trees into `site/schemas/` and add the staged⇋page parity assertion
T021 Run the generator (168 pages); convert `docs/architecture/producer-row-schemas.md` → folder hub and `docs/api/envelope.md` → folder hub, keeping routes stable; land hub blocks in the six `docs/events/*.md` family pages + new `docs/events/shared/` hub
T022 Wire the drift check: `make lint` gains `schema_pages.py --check` after `docs_index.py --check`; docs-site.yml gains a drift-check step and the 168-page rendered-diagram assertion (out-of-map on WP02 file) that ALSO excludes PlantUML error-SVGs (a render error is still an SVG — squad S4: provoke one locally and grep-exclude its exact error signature)
T023 New `tests/test_schema_pages.py`: generator determinism, exclusion rules (`composed/`, `registry.json`, `fixtures/`), drift failure naming, envelope labels, hub-link parity
T024 Verification incl. build w/ local PlantUML; `python3 scripts/docs_index.py --clean` before commit hygiene where applicable

### Implementation Notes

- Page frontmatter derives title/description from each schema's `$id`/`description` (generator-normalised); `updated:` stamped by the generator run date — `--check` normalises volatile fields so determinism holds.
- Producer-row hub converts the stale "draft/Proposed" wording while converting to a folder (the FR-014 status wording for this page lives here, not WP06).

### Dependencies

- Depends on WP03.

### Risks & Mitigations

- Route collisions (spec edge case) → generator asserts route uniqueness before writing; build fails otherwise.
- Family map forgetting a NEW payload → generator exits non-zero naming the unmapped file (this IS the drift behaviour).

---

## Work Package WP05: Entry-point refresh (Priority: P1 — #193)

**Goal**: Root README and docs entry surfaces describe the dual-line repository (package + services) and route readers to services/API reference within the click budgets (FR-012, FR-013).
**Independent Test**: From README and from docs home, reach each service's reference in ≤3 clicks and the running guide in ≤2 (SC-002); zero stale "package-only" phrasing on entry pages.
**Prompt**: `/tasks/WP05-entry-point-refresh.md`
**Requirement Refs**: FR-012, FR-013, SC-002, SC-003

### Included Subtasks

T025 Rewrite root `README.md`: both release lines, read/write services + how to run them, Gradle build + make targets, contracts, updated mermaid diagram, links to guides and `<base>/reference/api/` routes; every version/path/command proven to exist
T026 [P] `docs/README.md` (+ its Start here), `docs/context/README.md`, `docs/context/system-context.md`: services in prose AND the context diagram
T027 [P] `docs/_meta/sections.yaml` purposes (context, api, guides) and `docs/architecture/README.md` routing (+ the FR-014 archive-status wording that page owes)
T028 Verification: validate + full chain; click-path audit table in the WP history note — each row records the LITERAL UI element path clicked (squad S10: makes reviewer reproduction a two-minute check)

### Implementation Notes

- All new links use root-absolute routes per docsite.md conventions; reference links target `/reference/api/mission-status/` and `/reference/api/mission-write/`.

### Dependencies

- Depends on WP04 (routes stabilised).

### Risks & Mitigations

- Drift between README commands and `make`/`uv` reality → every command/state in the README is executed or verified to exist before writing (FR-012 acceptance).

---

## Work Package WP06: Post-cutover truth pass (Priority: P1 — #193 balance + FR-014..019)

**Goal**: Architecture, guides, contracts docs, glossary, AGENTS.md and the ADR-0005 amendment stop contradicting ADR-0002/0003 and the shipped `service-v*` line.
**Independent Test**: SC-003 sweep — zero pages describe the repo as package-only or the cutover as pending (checked verbatim against research.md findings 11–19 lines).
**Prompt**: `/tasks/WP06-truth-pass.md`
**Requirement Refs**: FR-014, FR-015, FR-016, FR-017, FR-018, FR-019, SC-003

### Included Subtasks

T029 [P] Architecture: `docs/architecture/overview.md` (:16 "not a service" + frontmatter description) and `docs/architecture/services/event-log-lock-and-append.md` description; mark `docs/architecture/services/command-cli-research.md` historical
T030 [P] Guides: `docs/guides/README.md` (drop "draft until cutover"), `service-protocol-and-releases.md` (#160/#161 landed language; FIRST-FINAL-tag → released-flag rule, FR-009), `running-the-service.md` (rc.2), cutover runbook "Executed …; record" banner
T031 [P] Contracts docs (FR-016): the release blurb `contracts/README.md` :581-585 (squad S14: :675-682 is namespace comparison — at most a "retired" qualifier there), `contracts/mission-status/CHANGELOG.md:9`, `contracts/mission-write/CHANGELOG.md:9` → ADR-0003 release rule; retire `contract-<module>-v*`
T031b [P] Ledger page: `docs/architecture/services/event-ledger-writers-and-readers.md` (squad S5 — missed by research 11–19): operative corrections at :142-144 (unreleased-until-`contract-mission-status-v1.0.0` → ADR-0003 `service-v*` rule; `services/mission-status-api/` → this repo's `service/` layout)
T032 [P] Glossary (FR-017): read service, write service, `service-v*` line, launcher, `endpoint.json`, fail-closed write gate in `docs/glossary/README.md`
T033 AGENTS.md (FR-018): 0.5.0 caveat → native exclude + `--exclude` in the docs commands, trigger-path sentence, "inert until cutover" wording (also `.github/workflows/service.yml` + `service-release.yml` comments AND their now-dead run-block cutover strings, service.yml:94 / service-release.yml:54 — squad S15; grep pins scanners before editing strings), multi-issue-one-PR rule; verify `CLAUDE.md` symlink untouched
T034 ADR-0005 dated amendment: the fragment stopgap is replaced by toolkit exclusion (C-007: amendment, not rewrite)
T035 Verification: lint/validate green; findings 11–19 + T031b grep sweep recorded, PLUS positive-presence greps (squad S11): the six new glossary terms in `docs/glossary/README.md`, the ADR-0003 sentence in `contracts/README.md`

### Implementation Notes

- `CLAUDE.md` is a symlink to `AGENTS.md` — never edit it directly.
- service.yml/service-release.yml: comment-only edits; the pin scanners (`contracts/tools/verify_pins.py` family) must stay green — do not reorder steps.

### Dependencies

- Depends on WP04. Parallel with WP05 (disjoint file sets).

### Risks & Mitigations

- Over-correction risk on contracts changelogs (release-critical docs) → minimal-line edits; quote ADR-0003 D4; reviewer checks the diff counts.

---

## Work Package WP07: Changelog fragments + final gates (Priority: P1 — closing)

**Goal**: One changelog fragment per closed issue (FR-021) and the recorded full verification sweep before the PR.
**Independent Test**: `changelog.py check` accepts all four fragments; the evidence table (commands + counts) is pasted into the WP history and ready for the PR.
**Prompt**: `/tasks/WP07-fragments-and-final-gates.md`
**Requirement Refs**: FR-021, NFR-001, NFR-002, NFR-003, NFR-004, NFR-005, SC-001, SC-002, SC-003, SC-004, SC-005, SC-006

### Included Subtasks

T036 Write `docs/changelog/unreleased/{181-docs-site-fragment-exclusion,193-services-entry-docs,194-service-api-reference,195-schema-pages-and-build-render}.md` (filenames per the fragment README convention; one per issue)
T037 Run the full local sweep incl. site chain with fragments present and the US4/SC-005 negative probes; record commands + counts. NFR-001/002 timing evidence from the PR's CI run is MERGE-BLOCKING (squad S3): the PR body slots stay open until the real run URL + durations are pasted; a breach triggers ADR-0006's cache break-glass BEFORE merge — "pending-CI" never counts as a verdict
T038 Prepare the PR body skeleton (linked issues #181 #193 #194 #195 + epic #122, verification record, out-of-scope notes per C-006) for the operator to open

### Implementation Notes

- Fragment sections follow `docs/changelog/unreleased/README.md`; bodies use Added/Changed per `scripts/changelog.py` SECTIONS.
- WP07 adds no code beyond fragments; any fix found by T037 goes back to its owning WP via the review loop.

### Dependencies

- Depends on WP05 and WP06.

### Risks & Mitigations

- NFR-001 timing unknown until CI → record measured per-job durations on the PR; if breached, break glass per ADR-0006 cache notes before merging.

---

## Dependency & Execution Summary

- **Sequence**: WP01 → WP02 → WP03 → WP04 → (WP05 ∥ WP06) → WP07.
- **Parallelization**: WP05 and WP06 only; the serial chain is forced by shared plumbing files.
- **MVP Scope**: WP01 alone already closes #181's site-visible behaviour; WP01–WP04 deliver every P1 story's testable surface.

---

## Requirements Coverage Summary

| Requirement | Covered by |
|---|---|
| FR-001, FR-002 | WP01 |
| FR-003, FR-004, FR-011 | WP02 |
| FR-005, FR-006, FR-007, FR-008 | WP04 |
| FR-009, FR-010 | WP03 |
| FR-012, FR-013 | WP05 |
| FR-014 | WP04 (producer-row status), WP05 (architecture index), WP06 (overview, event-log) |
| FR-015..FR-019 | WP06 |
| FR-020 | WP01 (fragment tests), WP02 (mode/pin/path tests) |
| FR-021 | WP07 |
| NFR-001/002/005 | WP02, WP07 |
| NFR-003, SC-001, SC-005 | WP04, WP07 |
| NFR-004 | WP03, WP05, WP07 |
| SC-002 | WP03, WP05 |
| SC-003 | WP05, WP06 |
| SC-004 | WP02, WP07 |
| SC-006 | WP07 |
| C-001..C-007 | all WPs (constraints, honoured in prompts) |

---

## Subtask Index (Reference)

| Subtask | Summary | WP | Parallel? |
|---|---|---|---|
| T001 | Toolkit 0.6.0 bump + lockfile + auth check | WP01 | No |
| T002 | exclude option + validate --exclude | WP01 | No |
| T003 | Remove rm stopgap from docs-site.yml | WP01 | No |
| T004 | Rewrite fragment-exclusion tests | WP01 | [P] |
| T005 | WP01 verification sweep | WP01 | No |
| T006 | docs-site.yml container+plantuml+probe+cache rework | WP02 | No |
| T007 | Trigger paths + PATTERN extension | WP02 | No |
| T008 | playwright devDep pin + digest convention | WP02 | [P] |
| T009 | Mode/pin/detect tests | WP02 | [P] |
| T010 | ADR-0006 | WP02 | [P] |
| T011 | docsite.md build/render rewrite | WP02 | [P] |
| T012 | stage-docs-inputs.mjs + .gitignore + prebuild chain | WP03 | [P] |
| T013 | openapi option + peers | WP03 | [P] |
| T014 | Workflow bundle + checksum steps | WP03 | No |
| T015 | scripts/docs/requirements.txt (PyYAML pin) | WP03 | [P] |
| T016 | docs/api/services.md hub + api index link | WP03 | [P] |
| T017 | Service-reference pinning tests | WP03 | [P] |
| T018 | WP03 site verification | WP03 | No |
| T019 | schema_pages.py generator + family map | WP04 | No |
| T020 | Stage mjs schema staging + parity | WP04 | No |
| T021 | 168 pages + hub conversions/blocks | WP04 | No |
| T022 | Drift check wiring (lint + workflow + count) | WP04 | No |
| T023 | tests/test_schema_pages.py | WP04 | [P] |
| T024 | WP04 verification (diagrams incl.) | WP04 | No |
| T025 | Root README rewrite | WP05 | No |
| T026 | docs home + context pages | WP05 | [P] |
| T027 | sections.yaml + architecture index | WP05 | [P] |
| T028 | WP05 verification + click audit | WP05 | No |
| T029 | overview + event-log + research-mark fixing | WP06 | [P] |
| T030 | guides truth pass | WP06 | [P] |
| T031 | contracts README/changelogs release rule | WP06 | [P] |
| T031b      | Ledger page truth fix           | WP06 | [P] |
| T032 | glossary terms | WP06 | [P] |
| T033 | AGENTS.md + service workflow comments | WP06 | No |
| T034 | ADR-0005 amendment | WP06 | [P] |
| T035 | WP06 verification + stale sweep | WP06 | No |
| T036 | 4 changelog fragments | WP07 | [P] |
| T037 | Full verification sweep + probes | WP07 | No |
| T038 | PR body skeleton | WP07 | No |

(`mark-status` tracks completion; these rows are references.)
