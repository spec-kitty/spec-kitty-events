---
work_package_id: WP03
title: Service API reference pages (OpenAPI)
dependencies:
- WP02
requirement_refs:
- FR-009
- FR-010
- NFR-004
- SC-002
planning_base_branch: issue-122-docs-site-overhaul
merge_target_branch: issue-122-docs-site-overhaul
branch_strategy: Planning artifacts for this mission were generated on issue-122-docs-site-overhaul. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into issue-122-docs-site-overhaul unless the human explicitly redirects the landing branch.
base_branch: kitty/mission-docs-site-overhaul-01M6GBDP
base_commit: 6849816027f5ac8afc1e0e28b01bd41d57c0b9e9
created_at: '2026-11-03T13:55:37.104105+00:00'
subtasks:
- T012
- T013
- T014
- T015
- T016
- T017
- T018
phase: Phase 2 - Reference surfaces
history:
- at: '2026-11-03T09:52:50Z'
  actor: system
  action: Prompt generated via /spec-kitty.tasks
agent_profile: implementer-ivan
authoritative_surface: docs/api/
create_intent:
- site/scripts/stage-docs-inputs.mjs
- scripts/docs/requirements.txt
- docs/api/services.md
- tests/test_docs_service_reference.py
execution_mode: code_change
model: ''
owned_files:
- site/scripts/stage-docs-inputs.mjs
- site/.gitignore
- scripts/docs/requirements.txt
- docs/api/services.md
- docs/api/README.md
- tests/test_docs_service_reference.py
role: implementer
tags: []
task_type: implement
tracker_refs: []
---

# Work Package Prompt: WP03 – Service API reference pages (OpenAPI)

## ⚡ Do This First: Load Agent Profile

Use the `/spk-charter-profile-load` skill to load the agent profile specified in the frontmatter (or any user-defined profile), and behave according to its guidance before parsing the rest of this prompt.

- **Profile**: `implementer-ivan`
- **Role**: `implementer`
- **Agent/tool**: `claude`

---

## ⚠️ IMPORTANT: Review Feedback

Check the `review_ref` field (`spec-kitty agent tasks status`); address all feedback; log fixes via `add-history WP03`.

---

## Review Feedback

*[If returned from review, the reviewer feedback reference appears in the status event log.]*

---

## Markdown Formatting

Wrap HTML/XML tags in backticks. Use language identifiers in code blocks.

---

## Objectives & Success Criteria

Closes #194: the read service (`mission-status`) and write service (`mission-write`) contracts render as
API reference pages from single-file bundles, with raw file + sha256 download, both flagged unreleased (FR-009),
introduced by a hub page (FR-010).

Done means:
- the quickstart bundle recipe (out-of-repo `--out`, `SKE_CONTRACT_BUNDLES_DIR`) + site build yields `dist/reference/api/mission-status/` and `dist/reference/api/mission-write/`
  plus `openapi.yaml` + `openapi.yaml.sha256` that `sha256sum -c` accepts, byte-equal to the bundle
  (charter's published-check pattern).
- **Objectives note**: the flip rule for the first final `service-v*` tag is written in `docs/api/services.md`
  (this WP) and mirrored into `docs/guides/service-protocol-and-releases.md` by WP06.
- Staging is fail-closed: missing bundle → clear error naming the bundle command (charter pattern).
- `docs/api/README.md` links `services.md`; new pinning tests pass; full site chain green incl. built-links.

## Context & Constraints

- Toolkit contract (v0.6.0, verified at tag): `openapi: { renderer, section?, specs: [{path, label, released}] }`;
  `path` is site-root-relative, single self-contained OpenAPI 3.x file (external `$ref` is refused),
  `label`/`section` match `a-z0-9._-`; published at `<base>/<section>/<label>/` with raw bytes + `.sha256`.
- `bundle.py --bundle-only` needs only Python + PyYAML (no JVM/Gradle) — `contracts/tools/bundle.py:297`.
  PyYAML pinned 6.0.3 == uv.lock; the container job's venv (WP02) installs from the requirements file you create.
- Prior art: spec-kitty-charter `stage-docs-inputs.mjs` and its workflow's bundle download/checksum steps.
- FR-008-style discovery: contracts README at :581-585 currently describes the retired tag line — WP06 owns that;
  do NOT edit contracts/** content here (C-004 + file ownership), except nothing at all.
- Out-of-map edits required (serial, justified): `site/astro.config.mjs` + `site/package.json` (WP01-owned),
  `.github/workflows/docs-site.yml` (WP02-owned).

## Branch Strategy

- Planning base / merge target: `issue-122-docs-site-overhaul` (serial after WP02).

Implement with: `spec-kitty agent action implement WP03 --agent claude --mission docs-site-overhaul-01M6GBDP`

## Subtasks & Detailed Guidance

### Subtask T012 – The staging script [P]

- **Files (create)**: `site/scripts/stage-docs-inputs.mjs`; edit `site/.gitignore` (add `openapi/` and `schemas/`);
  edit `site/package.json` predev/prebuild chain (out-of-map).
- **Squad S1 — bundle source**: the bundles are NEVER inside the repo (`bundle.py` refuses, exit
  `OUT_INSIDE_REPOSITORY`). The script reads `SKE_CONTRACT_BUNDLES_DIR` (default
  `${RUNNER_TEMP:-/tmp}/bundle-out/bundle`), i.e. CI needs nothing set and locals follow quickstart.md.
- **Content** (Node 22 ESM, no deps; header comment naming the sources and fail-closed rule):
  1. Read `$SKE_CONTRACT_BUNDLES_DIR/mission-status/openapi.yaml` and `…/mission-write/openapi.yaml`.
     Missing → push problem `contract bundle missing: run 'export SKE_CONTRACT_BUNDLES_DIR="$(mktemp -d)/bundle-out/bundle"; uv run --frozen --no-sync python contracts/tools/bundle.py --root contracts --out "$(dirname "$SKE_CONTRACT_BUNDLES_DIR")" --bundle-only'` and at end `process.exit(1)`.
  2. `rm -rf openapi && mkdir openapi`, copy both, write sibling `.sha256` files (`sha256sum` format: `<hex>  <name>`).
  3. End with a summary log line: `stage-docs-inputs: 2 contract bundles staged (schemas staged by WP04 extension)` —
     keep the message generic: report counts for what THIS version stages.
- prebuild/predev chain becomes:
  `"prebuild": "python3 ../scripts/docs_index.py --write && node scripts/stage-docs-inputs.mjs"` (same for predev).
- **Edge**: run from anywhere → script must `import.meta.url`-resolve its own `site/` root and `chdir` there.

### Subtask T013 – The openapi option [P after T012 files exist; serial within WP]

- **Files**: `site/astro.config.mjs`, `site/package.json`, `site/package-lock.json` (all out-of-map, recorded).
- **Edits**:
  1. `import starlightOpenAPI from 'starlight-openapi';` at top.
  2. After the `base:`/`docsDir:` block (keep the comment style), add:

     ```js
     // The two service contracts as API reference pages at <base>/reference/api/<label>/, each from the
     // single-file bundle staged by scripts/stage-docs-inputs.mjs (#194). Both are unreleased until the
     // first final service-v* tag; the flip rule lives in docs/api/services.md (FR-009).
     openapi: {
       renderer: starlightOpenAPI,
       specs: [
         { path: 'openapi/mission-status.yaml', label: 'mission-status', released: false },
         { path: 'openapi/mission-write.yaml', label: 'mission-write', released: false },
       ],
     },
     ```
  3. package.json dependencies: add `"starlight-openapi": "0.27.0"` and `"@astrojs/markdown-satteri": "0.4.4"`
     (exact pins, toolkit's peer contract); regenerate lockfile, prove `npm ci`.
- **Verify**: with bundles produced locally, `npm run build` emits the four files per service and the
  emitted `.sha256` verifies.

### Subtask T014 – Workflow: bundle production + checksum gates

- **File**: `.github/workflows/docs-site.yml` (out-of-map on WP02-owned file; serial).
- **Add after the python venv step**:
  1. `pip install -r scripts/docs/requirements.txt` into `"$RUNNER_TEMP/docs-venv"`.
  2. "Write the contract bundles": `"$RUNNER_TEMP/docs-venv/bin/python" contracts/tools/bundle.py --root contracts --out "$RUNNER_TEMP/bundle-out" --bundle-only` (out-of-repo path — `OUT_INSIDE_REPOSITORY` otherwise) then `find "$RUNNER_TEMP/bundle-out/bundle" -type f -exec sha256sum {} +`. Both of these steps carry `working-directory: .` (job default is `site/`).
- **Add before Check links**:
  3. "Check the published contracts equal the bundles": for each of mission-status / mission-write:
     `sha256sum -c` in `dist/reference/api/<label>/`, then compare against `"$RUNNER_TEMP/bundle-out/bundle/<module>/openapi.yaml"` hash; fail naming both digests (charter's step, adapted for a loop over two modules). Same `working-directory: .` rule.

### Subtask T015 – CI python requirements [P]

- **Create** `scripts/docs/requirements.txt`:
  ```
  # Docs-build python deps: bundle.py (PyYAML, pinned to uv.lock's version, kept in sync on bumps).
  pyyaml==6.0.3
  ```
- Sanity note in the header: docs_index.py itself is stdlib-only.

### Subtask T016 – The services hub [P]

- **Create** `docs/api/services.md`: frontmatter per house rules (title "Service APIs", description ≤180 chars
  naming read/write services + the `service-v*` line + unreleased status, `doc_status: active`,
  `updated: <today>T00:00:00Z`, `type: API` (verified convention of the section — `docs/api/envelope.md:6`);
- Body: what the read service and write service are (use spec glossary terms), which release line they ship on
  (`service-v*`), how to run (link `/guides/running-the-service/`), contracts source (link GitHub tree URLs —
  full URLs for out-of-docs files per docsite.md link rules), the release rule pointer (ADR-0003: bundles ride
  on `service-v*`), the reference links `/reference/api/mission-status/` + `/reference/api/mission-write/`
  (unreleased until the first final `service-v*` tag; the flip rule: set `released: true` in
  `site/astro.config.mjs` when the first final tag exists — this is the rule FR-009 asks to have written down;
  also recorded in WP06's guide edit).
- **Edit** `docs/api/README.md`: add one bullet linking `services.md` between the envelope entry and the guides
  pointer, keeping the existing style.

### Subtask T017 – Pinning tests [P]

- **Create** `tests/test_docs_service_reference.py`:
  - `site/astro.config.mjs` text contains both `{ path: 'openapi/mission-status.yaml', label: 'mission-status', released: false }`
    and the mission-write twin (whitespace-tolerant regex).
  - `site/package.json` pins `starlight-openapi` == "0.27.0" and `@astrojs/markdown-satteri` == "0.4.4".
  - `docs/api/README.md` references `services.md`; `docs/api/services.md` exists with required frontmatter fields.
  - `site/scripts/stage-docs-inputs.mjs` exists and names both bundles.
- Keep assertions coarse (textual), in the style of tests/test_changelog_fragments.py.

### Subtask T018 – Verification sweep

Commands + counts into history:
`uv run pytest --no-cov -q tests/test_docs_service_reference.py tests/test_docs_site_workflow.py tests/test_ci_gate_workflows.py`, conformance gate, ruff, changelog check+guard, `make lint`; then the FULL site chain with bundles produced
(the SKE_CONTRACT_BUNDLES_DIR recipe from quickstart.md — never `--out` inside the repo) and local PlantUML (docker per quickstart.md):
validate → `DK_DIAGRAM_BUILD_RENDER=build DK_PLANTUML_SERVER_URL=… npm run build` → check:built-links;
verify the four published artefacts for both services and validate `sha256sum -c` against the bundle tree.
`python3 scripts/docs_index.py --clean` pre-commit hygiene.

## Test Strategy

Tests are required (FR-020-style pinning + repo rule "run tests you write/change plus blast radius").
Add no Vitest/Astro tests; python-side pinning only.

## Risks & Mitigations

- **Label/case**: toolkit lower-cases labels and rejects odd chars — `mission-status`/`mission-write` are already legal; the tests pin them.
- **Bundle drift on disk**: staging refuses stale/absent bundles — contributors re-run the bundle command (message says which).
- **Peering**: if npm yells about starlight-openapi peers of starlight versions, DO NOT widen ranges; record the conflict and escalate — the toolkit documents exact peers.

## Review Guidance (for reviewer-renata)

- Only the three declared out-of-map edits, each recorded in history with rationale.
- Published byte-equality evidence (commands in history) for BOTH services.
- Hub/frontmatter house style matches docs/api siblings; links use root-absolute routes.

## Progress & Status

Event log only — `add-history`, `move-task`, `status`.
