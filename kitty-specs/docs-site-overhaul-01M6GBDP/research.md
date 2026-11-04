# Pre-spec grounding squad: docs-site overhaul (#181, #193, #194, #195)

Date: 2026-11-03. Point-cut: pre-spec. Cast: researcher-robbie (Sonnet), architect-alphonso (Opus), planner-priti (Sonnet), all profile-loaded and read-only.
Question: what must one mission change to land doc-kitty 0.6.0 (exclude, OpenAPI reference, Schematic pages with build-mode PlantUML) and bring README, entry, architecture and planning docs in line with the services?

## Maintainer rulings (2026-11-03)
- Build-mode PlantUML trade-off accepted.
- One mission, ONE branch, ONE PR closing #181 #193 #194 #195; AGENTS.md must be updated to allow a multi-issue mission landing as one PR.
- Programme planning repo drift is out of scope: filed spec-kitty/spec-kitty-planning#2683.

## Adjudicated divergences (from source, doc-kitty v0.6.0)
- Diagram mode is global (`DK_DIAGRAM_BUILD_RENDER`, src/lib/integrations/diagrams.ts:152): build mode also renders Mermaid at build time through Playwright Chromium. Mermaid cannot stay client-side.
- `schematic_path` resolves against the Astro root = site/ (src/lib/schematic/core.mjs:7,73-81); `openapi.specs[].path` likewise. Inputs outside site/ must be staged into git-ignored site/ folders.
- New ADR-0006 (architect) over an ADR-0005 amendment (planner): client mode was never an ADR decision, so there is nothing to amend. ADR-0005 still gets a dated amendment note that the `rm -rf` stopgap is gone.

## Findings and dispositions
| # | Source | Finding | Disposition |
|---|---|---|---|
| 1 | planner | tests/test_changelog_fragments.py:695-723 pin the rm stopgap and forbid `--exclude` in AGENTS.md | accepted: rewrite to assert `exclude` in astro.config.mjs and `--exclude` in validate |
| 2 | planner | docs-site.yml push paths + in-job PATTERN miss contracts/**, src/spec_kitty_events/schemas/**, producer_contract/** | accepted: extend both, add DOCS_DETECT cases and a parity test |
| 3 | planner/architect | client mode stated in docs-site.yml, astro.config.mjs comment, docs/guides/docsite.md:36,51-53 | accepted: change all, add a test pinning build mode and the PlantUML digest |
| 4 | planner/researcher | OpenAPI contracts are multi-file; 0.6.0 refuses external $ref | accepted: stage the single-file output of contracts/tools/bundle.py |
| 5 | researcher | toolkit 0.6.0 + exact peers starlight-openapi@0.27.0, @astrojs/markdown-satteri@0.4.4, playwright | accepted: one bump, regenerated lockfile, `npm ci` |
| 6 | researcher | charter pattern: Playwright container pinned by digest, plantuml-server service (SANDBOX) pinned by digest, readiness probe, diagram cache, post-build diagram-count check | accepted |
| 7 | researcher | Playwright container has no Python; docs_index.py prebuild and bundle.py need python3 | accepted: install python3 in the job or run staging before container steps |
| 8 | researcher/architect | ~166 Schematic pages; hand-authoring impractical | accepted: committed generator + drift test (schema set == schematic_path set), run in make lint |
| 9 | researcher/architect | producer_contract: data/*.v1.schema.json only (36); exclude composed/*, registry.json, fixtures/ | accepted |
| 10 | researcher vs architect | envelope: one canonical page vs two | changed: two clearly distinguished pages - schemas/event.schema.json (normative 3.0.0) and producer_contract/envelope(.read).schema.json (CloudEvents profile, non-normative until 12.0.0) |
| 11 | architect | contracts/README.md:581-585 + contracts/*/CHANGELOG.md describe a retired `contract-<module>-v*` tag line contradicting ADR-0003 D4; contracts at 1.0.0-SNAPSHOT | accepted: align to ADR-0003; OpenAPI pages `released: false` until first final service-v* tag |
| 12 | architect | docs/architecture/overview.md:16 "not a service" contradicts ADR-0002 | accepted |
| 13 | architect | README.md package-only (#193) | accepted |
| 14 | architect | system-context, docs/README, sections.yaml purposes (context/api/guides) omit services | accepted; new docs/api/services.md hub, no new top-level section |
| 15 | architect | guides/README "draft until cutover", service-protocol-and-releases "first release after #156", running-the-service rc.1 example, cutover runbook pending-tense | accepted |
| 16 | architect | command-cli-research.md active but historical; producer-row-schemas.md still draft/Proposed | accepted |
| 17 | architect | AGENTS.md/CLAUDE.md, service.yml, service-release.yml "inert until cutover" wording | accepted |
| 18 | architect | glossary lacks service terms | accepted |
| 19 | architect | event-log-lock-and-append description overstates ("appends atomically") | accepted |
| 20 | architect | CHANGELOG.md service rcs under package [Unreleased] | deferred_with_rationale: release-process question, not docs-site; tracked in #196 |
| 21 | architect | kitty-specs/* are historical records | accepted: do not rewrite |
| 22 | architect | planning repo PROGRAM.md drift | deferred_with_rationale: tracked spec-kitty/spec-kitty-planning#2683 |
| 23 | planner | PR shape | changed: maintainer ruled one PR; AGENTS.md updated accordingly |
| 24 | planner | changelog fragment(s) missing from the issues | accepted |
| 25 | researcher | CSP hashes | deferred_with_rationale: GitHub Pages sends no CSP |

## Plan-phase addendum (2026-11-03, /spec-kitty.plan)

### Decisions
- **P-A1 Page reconciliation (FR-005 vs FR-006/DM).** All three envelope files get pages: `event.schema.json` labelled "normative 3.0.0 envelope"; both `producer_contract/envelope(.read)`.schema.json pages labelled "CloudEvents profile, non-normative until 12.0.0". Rationale: FR-005's per-file paging is explicit and the drift check stays a pure set-equality; the FR-006/DM wording is satisfied on the distinction it actually requires (normative vs profile). Alternative considered: one shared CloudEvents page folding both producer envelopes — rejected (breaks one-file/one-page, complicates the drift check).
- **P-A2 Generator-first layout.** Committed `scripts/schema_pages.py` + committed family map `scripts/schema_pages_map.json` (fail-closed on unmapped payload). Rationale (research #8): ~168 pages are impractical to hand-author; the map makes family assignment auditable. Alternative: infer family from `$id`/name patterns — rejected (heuristic drift, opaque failures).
- **P-A3 Docs workflow produces the contract bundle inline** (`bundle.py --bundle-only` in the build job's venv). Alternative considered: `download-artifact` from service.yml's `contract-bundle` job — rejected: couples docs-build's always-report requirement (ADR-0005 D3) to service.yml's detect gating, and service.yml skips on docs-only changes.
- **P-A4 Slicing and shared-file ownership** (site/astro.config.mjs, site/package.json, docs-site.yml, docs/api/README.md): strict serial chain WP01→WP04, parallel content WPs after, WP07 gates last. Matches the squad's sequence guidance; ownership table in plan.md.
- **P-A5 Registry auth via `gh auth refresh -s read:packages` → `~/.npmrc`** (DM-01M6GC6ZQ7ATZR6WBA49C03CXV, resolved 2026-11-03). Unblocks lockfile regeneration and local `npm ci`; CI unchanged.

### Supply-chain posture (DIRECTIVE_051 / supply-chain-install-safety)
- npm: one exact pin bump `@spec-kitty/doc-toolkit` 0.5.0→**0.6.0** (GitHub Packages, `@spec-kitty` scope, provenance = the tagged v0.6.0 source reviewed in-repo); exact peers `starlight-openapi@0.27.0`, `@astrojs/markdown-satteri@0.4.4` per the toolkit's documented peer contract (sources: research finding 5, toolkit CHANGELOG); devDep `playwright@1.62.1` equal to the container tag. One regenerated, committed `package-lock.json`, reviewed in the PR; installs are `npm ci` (exact; `--ignore-scripts` in CI).
- Containers/services (NFR-005, 100% digest-pinned): Playwright `v1.62.1-noble` → sha256:dcc5531e97840b9b5e794f2814476b21571c5124a3fca2267d73041f56e7580e and PlantUML `jetty-v1.2026.9` → sha256:065c66adc6728ba6dd353a29d4ada73f8358b68549a0f710c4bf46b43c103618, both adopted 2026-11-03 from charter's 2026-11-02 `docker buildx imagetools inspect` resolution of the same pins (charter's recorded procedure; SANDBOX profile on PlantUML). Bump rule: tag+digest+package pin move together.
- Python in CI: `apt python3-venv` + `scripts/docs/requirements.txt` pinning PyYAML **6.0.3** (== uv.lock) for `bundle.py --bundle-only`; all apt/pip installs version-pinned and network-bounded.
- GitHub Actions: every `uses:` in the reworked docs-site.yml stays SHA-pinned (existing convention; charter quality gate for changed workflows).
- Adversarial evidence: pre-spec squad (this file, 25 findings, dispositions recorded) + operator-mandated post-tasks squad (reviewer-renata, planner-priti, implementer-ivan) before implementation; contested findings from that pass will be dispositioned here. Nothing contested is dropped silently.

## Post-tasks adversarial squad (2026-11-03; cast: reviewer-renata, planner-priti, implementer-ivan — all profile-loaded, read-only)

Verdicts: renata approve-with-findings, priti approve-with-findings, ivan **block** (2 MAJOR). Convergent on decomposition soundness (lanes acyclic, WP05∥WP06 verified disjoint). Every finding dispositioned below; none dropped.

| # | Lens | Sev | Finding | Disposition |
|---|---|---|---|---|
| S1 | ivan | MAJOR | `bundle.py --out build/contracts` can never work — preflight refuses any out-dir inside the repo (`contracts/tools/bundle.py:145-146`, `OUT_INSIDE_REPOSITORY`); service.yml already stages at `$RUNNER_TEMP/bundle-out` | **accepted — fixed everywhere**: canonical bundle location is `$RUNNER_TEMP/bundle-out` in CI and `$SKE_CONTRACT_BUNDLES_DIR` (a documented `mktemp`-style path outside the repo) locally; staging mjs reads `SKE_CONTRACT_BUNDLES_DIR` (default: `${RUNNER_TEMP:-/tmp}/bundle-out`); plan.md A4, quickstart.md, WP02/WP03/WP04/WP07 corrected. |
| S2 | ivan | MAJOR | Corpus has **zero cross-file `$ref`s** (grep-verified): WP04's `## Uses` sub-model links would be vacuous and US1 AS2 has no data instance today | **accepted with adjudication**: keep the cross-ref machinery + the shared hub (FR-008 demands it), but `--write` emits `## Uses` ONLY when cross-file refs exist; the corpus fact and the US1 AS2 engagement rule ("links appear when the first cross-file ref exists; shared types are reachable today via the shared-types hub") are recorded here and in WP04. Spec not amended: the acceptance scenario is conditional by nature; flagged for the PR. Fabricated name-matching links were rejected (would falsify claims). |
| S3 | renata | MAJOR | NFR-001/002 could merge as "pending-CI" verdicts (WP07 T037.5) | **accepted**: T037.5/T038 reworded — the PR cannot leave `pending-CI` slots: timing evidence from the PR's CI run is merge-blocking per tasks.md's own risk note. |
| S4 | renata | MAJOR | NFR-003 diagram gate greps `<figure class="dk-diagram"><svg` — a PlantUML ERROR response is also an SVG → hollow green | **accepted**: T022.2 adds a negative-marker assertion (provoke one PlantUML failure locally in T024 to capture the exact error signature, then grep-exclude it); probe cannot catch per-diagram errors, so the built-page grep must. |
| S5 | renata | MAJOR | `docs/architecture/services/event-ledger-writers-and-readers.md` :142-144 carries operative stale text (`contract-mission-status-v1.0.0`, `services/` path) not in research 11–19 nor in any WP's ownership | **accepted — gap in my survey enumeration**: file added to WP06 ownership + T031b; sweep regex extended. (Historical-record judgment: the page is architecture narrative → corrected like the other operative pages.) |
| S6 | renata | MINOR | Readiness-probe worst case ≈210 s (> NFR-002 120 s) under slow-but-answering service | **accepted**: probe constants changed to `seq 1 24` with `--max-time 3`, `sleep 2` (≤120 s hard bound) and the constants pinned in `test_docs_site_workflow.py`. |
| S7 | renata | MINOR | container-tag ⇄ playwright-pin equality never cross-asserted | **accepted**: pin test derives expected tag from `site/package.json` playwright version. |
| S8 | renata | MINOR | NFR-005 pin test covers only the two known images | **accepted**: test iterates every `container.image`/`services.*.image` asserting `@sha256:`. |
| S9 | renata | MINOR | Untested inline-shell count gate | **accepted**: one `_run_step`-style negative test added to WP04 T023. |
| S10 | renata | MINOR | Click budgets self-attested | **accepted**: WP05 T028 records literal UI element paths per click row. |
| S11 | renata | MINOR | WP06 content has no positive-presence guard | **accepted**: T035 gains positive greps (six glossary terms; ADR-0003 sentence in contracts docs). |
| S12 | ivan | MINOR | Repo-root-relative CI steps need `working-directory: .` (job default is `site/`) | **accepted**: explicit sentence in WP02 T006 (+ reminders T014/T022). |
| S13 | ivan | MINOR | WP04 quotes wrong section `type:` values (events → `Integration`, api → `API`) | **accepted**: corrected verbatim. |
| S14 | ivan | MINOR | contracts/README.md:678 is namespace comparison, not the release blurb (:581-585 is) | **accepted**: T031 says edit :581-585; :678 gets at most a "retired" qualifier. |
| S15 | ivan | MINOR | Stale cutover wording also in workflow RUN-BLOCK strings (service.yml:94, service-release.yml:54), T033 said comments-only | **accepted**: T033 covers both; implementer grep-checks pins tests before editing strings. |
| S16 | ivan | MINOR | `service-protocol-and-releases.md` :101/:236 missed by T030/T035 | **accepted**: :236 swept; :101 rewritten for accuracy (fail-closed by design, #160/#161 landed) and phrase family added to the sweep regex. |
| S17 | priti | MINOR | lanes write_scope omits all declared out-of-map edits → commit-guard warn-noise each planned commit | **accepted-by-design**: recorded once (tasks.md matrix note): warn-mode violations on the four plumbing files are expected-and-planned; the prose matrix is the binding record; no lanes.json surgery. |
| S18 | priti | MINOR | tasks.md ownership prose only describes later→earlier edits; WP01→WP02 forward-edit (rm stopgap) unacknowledged | **accepted**: sentence added to tasks.md. |
| S19 | priti | MINOR | WP04 dotted-stem route spike risks post-generation rework | **accepted**: spike promoted to a T019 exit criterion (prove one dotted page builds before mass generation). |
| S20 | priti/ivan mixed | NIT ×6 | mojibake in WP04; WP01/WP03 editorial self-corrections; wrong false-case path in WP02 T009; `type: Api` case; sections.yaml/glossary quote fidelity | **accepted**: all folded into the prompts. |

Adversarial evidence (supply chain) unchanged by the squad pass; contested-finding count: S1–S5 resolved pre-implementation as required.
