---
work_package_id: WP02
title: Build-time diagram rendering, trigger paths and ADR-0006
dependencies:
- WP01
requirement_refs:
- FR-003
- FR-004
- FR-011
- FR-020
- NFR-001
- NFR-002
- NFR-005
- C-002
planning_base_branch: issue-122-docs-site-overhaul
merge_target_branch: issue-122-docs-site-overhaul
branch_strategy: Planning artifacts for this mission were generated on issue-122-docs-site-overhaul. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into issue-122-docs-site-overhaul unless the human explicitly redirects the landing branch.
base_branch: kitty/mission-docs-site-overhaul-01M6GBDP
base_commit: 97efe2882a86b3a127c240fe30090e1131d571eb
created_at: '2026-11-03T13:25:51.146989+00:00'
subtasks:
- T006
- T007
- T008
- T009
- T010
- T011
phase: Phase 1 - Docsite plumbing
history:
- at: '2026-11-03T09:52:50Z'
  actor: system
  action: Prompt generated via /spec-kitty.tasks
agent_profile: implementer-ivan
authoritative_surface: .github/workflows/
create_intent:
- tests/test_docs_site_workflow.py
- docs/adr/0006-build-time-diagram-rendering.md
execution_mode: code_change
model: ''
owned_files:
- .github/workflows/docs-site.yml
- tests/test_ci_gate_workflows.py
- tests/test_docs_site_workflow.py
- docs/adr/0006-build-time-diagram-rendering.md
- docs/guides/docsite.md
role: implementer
tags: []
task_type: implement
tracker_refs: []
---

# Work Package Prompt: WP02 – Build-time diagram rendering, trigger paths and ADR-0006

## ⚡ Do This First: Load Agent Profile

Use the `/spk-charter-profile-load` skill to load the agent profile specified in the frontmatter (or any user-defined profile), and behave according to its guidance before parsing the rest of this prompt.

- **Profile**: `implementer-ivan`
- **Role**: `implementer`
- **Agent/tool**: `claude`

---

## ⚠️ IMPORTANT: Review Feedback

Check the `review_ref` field in the event log (`spec-kitty agent tasks status`). Address ALL feedback before marking done; log each fix via `spec-kitty agent tasks add-history WP02 --note "..."`.

---

## Review Feedback

*[If this WP was returned from review, the reviewer feedback reference appears in the status event log.]*

---

## Markdown Formatting

Wrap HTML/XML tags in backticks. Use language identifiers in code blocks.

---

## Objectives & Success Criteria

Closes the rendering/CI half of #195 plus the trigger-path gap (FR-011); records the decision as ADR-0006 (FR-004).

Done means:
- `.github/workflows/docs-site.yml` `build` job runs in the digest-pinned Playwright container with the
  digest-pinned PlantUML SANDBOX service, bounded readiness probe (≤120 s, NFR-002), the
  `DK_DIAGRAM_BUILD_RENDER=build` env + in-step guard, `DK_PLANTUML_SERVER_URL`, the diagram cache, and
  `npm ci --ignore-scripts`. The job name stays `docs-build`, id `build` (C-002).
- Push `paths:` and the in-job detection `PATTERN` both include `contracts/**`,
  `src/spec_kitty_events/schemas/**`, `src/spec_kitty_events/producer_contract/**`,
  `scripts/schema_pages.py`; `test_docs_detect_covers_every_push_path` passes.
- `playwright@1.62.1` devDependency pinned in `site/package.json`, equal to the container tag; lockfile regenerated.
- `tests/test_docs_site_workflow.py` pins mode + digests; DOCS_DETECT cases cover contracts/schemas/producer paths.
- `docs/adr/0006-build-time-diagram-rendering.md` accepted-status ADR; `docs/guides/docsite.md` tells the truth
  (0.6.0, build mode, local PlantUML recipe, `--exclude` validate).

Known after-images you must NOT "fix": schema diagram counting (`168 pages` gate) is WP04's out-of-map edit on
YOUR workflow; the OpenAPI bundle steps are WP03's out-of-map edit. Structure the workflow file so both insert
cleanly (clear step ordering + comment anchors).

## Context & Constraints

- Prior art to mirror near-verbatim: `/home/stijn/Documents/code/kitty/spec-kitty-charter/.github/workflows/docs-site.yml`
  (container, services, probe, cache, guards) — the digests and comments there are the accepted pattern
  (research.md findings 2, 3, 6, 7).
- Render mode is site-wide (adjudicated): build mode renders Mermaid through Playwright Chromium and
  schematic/PlantUML through `DK_PLANTUML_SERVER_URL`; auto-detection upgrades to build whenever a Chromium
  resolves — hence the in-step guard, and hence `site/astro.config.mjs`'s `diagrams:` comment changes.
- Charter quality gate: every `uses:` SHA-pinned; both images digest-pinned (NFR-005).
- FR-011 wording: BOTH the push trigger and the PR detection list — and the repo test
  `test_docs_detect_covers_every_push_path` keeps them in agreement automatically.
- DO NOT add the fragment-folder negation anywhere new; it already exists on push paths
  (`!docs/changelog/unreleased/**`) and inside the detect step (`grep -vE`).

## Branch Strategy

- **Strategy**: lane per finalize-tasks output; serial after WP01.
- **Planning base branch**: `issue-122-docs-site-overhaul`
- **Merge target branch**: `issue-122-docs-site-overhaul`

Implement with: `spec-kitty agent action implement WP02 --agent claude --mission docs-site-overhaul-01M6GBDP`

## Subtasks & Detailed Guidance

### Subtask T006 – Rework the `build` job

- **Purpose**: FR-003. This is the core of the WP: identical shape to charter's build job.
- **Steps** (work from the current events file + the charter file side by side; port these blocks):
  1. `container:` block: `mcr.microsoft.com/playwright:v1.62.1-noble@sha256:dcc5531e97840b9b5e794f2814476b21571c5124a3fca2267d73041f56e7580e`,
     `options: --ipc=host --init`, with the charter comment adapted (digest resolution date + inspector command +
     "tag equals the playwright pin; bump tag, digest and pin together").
  2. `services: plantuml:` with `plantuml/plantuml-server:jetty-v1.2026.9@sha256:065c66adc6728ba6dd353a29d4ada73f8358b68549a0f710c4bf46b43c103618`
     + `PLANTUML_SECURITY_PROFILE: SANDBOX` + the SANDBOX comment.
  3. `defaults.run.shell: bash`, `working-directory: site`.
  4. Steps, in order: probe (toolchain versions incl. `python3 --version`, note no pip), checkout,
     setup-node 22.22 (SHA-pinned pin-as-is) with cache + `cache-dependency-path`, `npm ci --ignore-scripts`
     (NODE_AUTH_TOKEN), python venv step (`apt-get install -y --no-install-recommends python3-venv`,
     `python3 -m venv "$RUNNER_TEMP/docs-venv"` — all `run:` shell lines stay inside the container job),
     PlantUML readiness probe (`for i in $(seq 1 24); curl -s --max-time 3 … ; sleep 2` — worst case
     24×5 = 120 s, exactly the NFR-002 bound; failure echoes the service name/URL and exits 1),
     diagram cache restore (`path: .dk-diagram-cache`, key `dk-diagram-0.6.0-${{ hashFiles('docs/**/*.md') }}`,
     restore-keys `dk-diagram-0.6.0-`, with the "bump 0.6.0 in the key with the toolkit pin" comment),
     index generation (`"$RUNNER_TEMP/docs-venv/bin/python" scripts/docs_index.py --write` — stdlib only, no deps),
     `npm run validate`, build step with env `DK_PUBLISH_DRAFTS: on`, `DK_DIAGRAM_BUILD_RENDER: build`,
     `DK_PLANTUML_SERVER_URL: http://plantuml:8080/svg/`, run guard `test "$DK_DIAGRAM_BUILD_RENDER" = build || { echo "::error::DK_DIAGRAM_BUILD_RENDER must be build"; exit 1; }`
     + `export DK_BEOE_CACHE_DIR="$GITHUB_WORKSPACE/.dk-diagram-cache"`,
     `npm run check:built-links`, pages artifact upload + deploy job UNCHANGED.
  5. The detect step (`Decide whether the change touches the docs`) and its `run:` script change ONLY per T007;
     keep `pr:pause-ci` handling byte-identical.
- **Files**: `.github/workflows/docs-site.yml`.
- **Notes**: every step needs `if: steps.changes.outputs.run == 'true'` exactly as today (name/justification comments
  preserved). Remove the now-dead comment about "Mermaid renders in the browser; never switch…" at the build env.
  **Every step you add that runs from the repo root** (probe pre-checkout is fine; venv install, index generation,
  bundle production, drift check, post-build checks) **must set `working-directory: .`** — the job default is
  `site/` (squad S12). Charter does this per-step the same way; copy that shape.

### Subtask T007 – Trigger paths and detection pattern (FR-011)

- **Steps**:
  1. Push `paths:` gains, after `site/**`:
     - `contracts/**`
     - `src/spec_kitty_events/schemas/**`
     - `src/spec_kitty_events/producer_contract/**`
     - `scripts/schema_pages.py`
     with a one-line comment ("contracts and schemas feed generated pages; their changes must publish the site").
  2. Detect `PATTERN` becomes
     `^(docs/|site/|\.github/workflows/docs-site\.yml$|scripts/docs_index\.py$|scripts/schema_pages\.py$|contracts/|src/spec_kitty_events/schemas/|src/spec_kitty_events/producer_contract/)`.
  3. Update the step's leading comment to mention the new sources.
- **Files**: `.github/workflows/docs-site.yml`.
- **Edge**: `docs/changelog/unreleased/-only` change must still not run the build (existing `grep -vE` filter);
  add the regression case in T009.

### Subtask T008 – Playwright devDependency pin [P vs T006/T007 after coordination of package.json write point]

- **Purpose**: local build-mode parity; container tag === this pin (NFR-005 traceability).
- **Steps**: add `"playwright": "1.62.1"` to `site/package.json` devDependencies; regenerate lockfile
  (`npm install` then `npm ci` proof); DO NOT run `npx playwright install` (CI uses the container image;
  document that local build-mode needs `npx playwright install chromium` OR the container path, in docsite.md).
- **Files**: `site/package.json`, `site/package-lock.json` — **out-of-map** (WP01-owned), serial per tasks.md matrix;
  rationale: "devDep belongs to the rendering decision this WP lands; pin must equal container tag atomically".

### Subtask T009 – Tests: pins, mode, detect (FR-020 mode/pin half + FR-011)

- **New file** `tests/test_docs_site_workflow.py` (house style: PyYAML `_REPO_ROOT`, textual asserts like
  test_changelog_fragments.py):
  - load `.github/workflows/docs-site.yml`; assert `jobs.build.container.image` matches
    `mcr.microsoft.com/playwright:v{pin}-noble@sha256:<digest>` where `{pin}` is DERIVED from the `playwright`
    version in `site/package.json` (squad S7 — never hardcode the tag in the test), and the digest equals the
    pinned `dcc5531e…7580e`;
  - NFR-005 sweep (squad S8): iterate EVERY `jobs.*.container.image` and `jobs.*.services.*.image` and assert
    each carries `@sha256:`; assert the plantuml service env `PLANTUML_SECURITY_PROFILE: SANDBOX`;
  - assert the Build step env has `DK_DIAGRAM_BUILD_RENDER: build` and its run block contains the
    `DK_DIAGRAM_BUILD_RENDER must be build` guard;
  - assert `DK_PLANTUML_SERVER_URL` present; assert cache key prefix `dk-diagram-0.6.0-`;
  - readiness-probe arithmetic (squad S6): extract the `seq 1 N`/`--max-time M`/`sleep S` constants from the
    probe step and assert `N * (M + S) <= 120`, and assert the failure line names the service (message
    contains `plantuml`).
- Edit `tests/test_ci_gate_workflows.py`: DOCS_DETECT parametrization gains
  `(DOCS_DETECT, "contracts/mission-status/openapi.yaml", "true")`,
  `(DOCS_DETECT, "src/spec_kitty_events/schemas/mission_created_payload.schema.json", "true")`,
  `(DOCS_DETECT, "src/spec_kitty_events/producer_contract/data/mission.created.v1.schema.json", "true")`,
  `(DOCS_DETECT, "src/spec_kitty_events/analytics.py", "false")`;
  verify `test_docs_detect_covers_every_push_path` passes with the new push paths (it derives expectations
  from the file itself).
- **Files**: `tests/test_docs_site_workflow.py` (new), `tests/test_ci_gate_workflows.py`.
- **Validation**: `uv run pytest --no-cov -q tests/test_docs_site_workflow.py tests/test_ci_gate_workflows.py tests/test_changelog_fragments.py`.

### Subtask T010 – ADR-0006

- **Purpose**: FR-004; research finding: client mode was never an ADR decision, so NEW ADR, not an amendment.
- **Steps**: `docs/adr/0006-build-time-diagram-rendering.md` frontmatter copied from ADR-0005's shape
  (title, description ≤180 chars, `doc_status: active`, `updated: <today>T00:00:00Z`, `type: ADR`, `kind: ADR`,
  `authors: [{human: stijn}]`, related: adr/0002, adr/0005). Status: Accepted (maintainer ruling 2026-11-03,
  research.md). Content MUST cover: decision (build-time rendering in CI via digest-pinned Playwright +
  SANDBOX PlantUML; site auto-detect upgraded the local default too — it is now intentional), pins table +
  bump rule (tag+digest+pin together; cache key follows toolkit), readiness bound 120 s and failure semantics,
  consequences (CI needs containers; local needs PlantUML or accepts client mode difference; the in-step guard
  prevents silent fallback), and trade-offs vs client mode (cold-build NFR-001 budget).
- **Files**: `docs/adr/0006-build-time-diagram-rendering.md` (new). DO NOT touch `docs/adr/README.md`
  (generated block is refreshed by `scripts/docs_index.py --write` during validate/build).

### Subtask T011 – docsite.md rewrite of the stale parts (FR-004 tail)

- **Purpose**: the guide teaches exactly what CI does now (US3, SC-004).
- **Edits** (surgical):
  1. Intro pin sentence: `0.4.0` → `0.6.0`.
  2. "Build locally" script block: `validate` line unchanged in text form but note it excludes the fragment
     folder natively; replace the `DK_DIAGRAM_BUILD_RENDER=client npm run build` line with the full-parity
     recipe from quickstart.md (PlantUML docker one-liner, `DK_DIAGRAM_BUILD_RENDER=build`,
     `DK_PLANTUML_SERVER_URL`), plus the client-mode fallback warning ("CI never uses it; schematic pages
     omit diagrams without the server").
  3. Delete the "Mermaid diagrams render in the browser…" paragraph; replace with build-mode explanation
     (auto-detect, the workflows' guard, the sentinel theming fact is toolkit-internal — don't re-explain).
  4. "What CI runs": add contracts/schemas trigger paths, the container + PlantUML + probe, and the drift/diagram
     count + checksum gates (named as landing with WP03/WP04 where not yet present — write the FINAL state;
     the workflow file is the reference).
  4b. Note the openapi/schema staging (prebuild) and the out-of-repo bundle contract (`SKE_CONTRACT_BUNDLES_DIR`,
     never in-tree — `OUT_INSIDE_REPOSITORY`); include the quickstart bundle recipe:
     `export SKE_CONTRACT_BUNDLES_DIR="$(mktemp -d)/bundle-out/bundle";` then
     `uv run --frozen --no-sync python contracts/tools/bundle.py --root contracts --out "$(dirname "$SKE_CONTRACT_BUNDLES_DIR")" --bundle-only`.
- **Files**: `docs/guides/docsite.md`.
- **Frontmatter**: bump `updated:` to the landing date; description may gain "OpenAPI and schema staging".

### Verification sweep for WP02 (not a subtask — part of every subtask's done definition)

(record under history; commands + counts for the PR):
- `uv run pytest --no-cov -q tests/test_docs_site_workflow.py tests/test_ci_gate_workflows.py tests/test_changelog_fragments.py tests/test_docs_index.py`
- `uv run pytest --pyargs spec_kitty_events.conformance`
- `ruff check . && ruff format --check .`
- `uv run python scripts/changelog.py check && uv run python scripts/changelog.py guard`; `make lint`
- Site chain: `npm ci && npm run validate`; and IF Docker available: start local PlantUML
  (`docker run -d -p 8080:8080 plantuml/plantuml-server:jetty-v1.2026.9@sha256:065c66ad…`), then
  `DK_DIAGRAM_BUILD_RENDER=build DK_PLANTUML_SERVER_URL=http://localhost:8080/svg/ npm run build`,
  `npm run check:built-links`; confirm `dist/` pages hold rendered `<svg>` figures for Mermaid
  (`grep -rl 'dk-diagram' dist --include=index.html | head` + eyeball one). Client-mode build remains the fallback check.

## Test Strategy

New + edited tests listed per subtask; all run per WP05-style blast radius above. Tests are required
(spec FR-020). No Java. Workflow YAML validity proven via the python workflow tests (they YAML-load).

## Risks & Mitigations

- **Charter divergence**: only paths/repo names differ; copy comment style but fact-check every path exists HERE.
- **Runner without Docker for local verify**: capture the CI-only risk; the PR's first CI run is part of evidence
  (NFR-001/002 timings land on the PR table, WP07 records).
- **actions versions**: reuse the exact SHA-pinned refs already present in charter's file; do not "helpfully" bump.

## Review Guidance (for reviewer-renata)

- Diff docs-site.yml against the prior version AND charter's analogue; every difference must be path- or
  repo-name-shaped.
- Guard must be a hard fail; probe must fail within 120 s; images digest-pinned; actions SHA-pinned.
- ADR-0006 present and internally consistent with the workflow comments and docsite.md.
- No edits to ADR-0005 (amendment is WP06), none to AGENTS.md (WP06), none to `site/package-lock.json` outside
  the playwright/toolkit subtrees.

## Progress & Status

Event log only — `add-history`, `move-task`, `status` as in WP01's template block.
