---
work_package_id: WP04
title: Generated schema pages, family hubs and the drift check
dependencies:
- WP03
requirement_refs:
- FR-005
- FR-006
- FR-007
- FR-008
- C-001
- NFR-003
- SC-001
- SC-005
planning_base_branch: issue-122-docs-site-overhaul
merge_target_branch: issue-122-docs-site-overhaul
branch_strategy: Planning artifacts for this mission were generated on issue-122-docs-site-overhaul. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into issue-122-docs-site-overhaul unless the human explicitly redirects the landing branch.
base_branch: kitty/mission-docs-site-overhaul-01M6GBDP
base_commit: 8e891fe38f626805dbf753192a447e0810146559
created_at: '2026-11-03T14:20:03.177114+00:00'
subtasks:
- T019
- T020
- T021
- T022
- T023
- T024
phase: Phase 2 - Reference surfaces
history:
- at: '2026-11-03T09:52:50Z'
  actor: system
  action: Prompt generated via /spec-kitty.tasks
agent_profile: implementer-ivan
authoritative_surface: docs/events/
create_intent:
- scripts/schema_pages.py
- scripts/schema_pages_map.json
- docs/events/shared/README.md
- docs/architecture/producer-row-schemas/README.md
- docs/api/envelope/README.md
- tests/test_schema_pages.py
execution_mode: code_change
model: ''
owned_files:
- scripts/schema_pages.py
- scripts/schema_pages_map.json
- docs/events/**
- docs/architecture/producer-row-schemas.md
- docs/architecture/producer-row-schemas/**
- docs/api/envelope.md
- docs/api/envelope/**
- Makefile
- tests/test_schema_pages.py
role: implementer
tags: []
task_type: implement
tracker_refs: []
---

# Work Package Prompt: WP04 – Generated schema pages, family hubs and the drift check

## ⚡ Do This First: Load Agent Profile

Use the `/spk-charter-profile-load` skill to load the agent profile specified in the frontmatter (or any user-defined profile), and behave according to its guidance before parsing the rest of this prompt.

- **Profile**: `implementer-ivan`
- **Role**: `implementer`
- **Agent/tool**: `claude`

---

## ⚠️ IMPORTANT: Review Feedback

Check the `review_ref` field (`spec-kitty agent tasks status`); address all feedback; log fixes via `add-history WP04`.

---

## Review Feedback

*[If returned from review, the reviewer feedback reference appears in the status event log.]*

---

## Markdown Formatting

Wrap HTML/XML tags in backticks. Use language identifiers in code blocks.

---

## Objectives & Success Criteria

Closes the core of #195 (with DM-01M6GBE4ZAKE68Y49MZWR69FS5): every in-scope schema has exactly one
committed, generator-produced `kind: Schematic` page; hubs link every member; the drift check fails closed
in `make lint` and in the docs build.

Done means (168 = 130 `src/spec_kitty_events/schemas/*.schema.json` + 36 `producer_contract/data/*.schema.json`
+ `producer_contract/envelope.schema.json` + `producer_contract/envelope.read.schema.json`):
- `python3 scripts/schema_pages.py --write` regenerates **byte-identical** pages/hub blocks (idempotent);
  `--check` passes on a clean tree.
- Page placement (plan.md A2): `docs/events/<family>/` for families, `docs/events/shared/` for sub-models,
  `docs/architecture/producer-row-schemas/` for the 36 rows + 2 CloudEvents-profile envelopes,
  `docs/api/envelope/` for the normative `event.schema.json`. Two envelope KINDS, three envelope PAGES
  (P-A1 reconciliation): normative page says "normative 3.0.0 envelope"; both producer envelope pages say
  "CloudEvents profile — non-normative until 12.0.0" (write vs read labelled).
- Excluded by rule and asserted: `producer_contract/composed/**`, `producer_contract/registry.json`,
  `producer_contract/fixtures/**`, `schemas/generate.py`/`__init__.py`, `__pycache__/`.
- Body links: pages carry cross-file-`$ref` sub-model links whenever a schema actually refs another file —
  the corpus has none today (squad S2), so `## Uses` is emitted conditionally and US1 AS2 engages on the first
  future cross-file ref; today's shared types are reached via the shared-types hub (FR-008).
- Drift: `make lint` includes the check; the docs workflow runs it pre-build; staging mjs parity is the
  backstop; failure names the file (SC-005/US4 acceptance 1–2).

## Context & Constraints

- Spec: FR-005..FR-008, C-001 (never hand-edit generated pages; staged copies never committed), NFR-003,
  SC-001/SC-005; plan.md A1–A4; research findings 8–10.
- Toolkit facts (v0.6.0 verified): page frontmatter `kind: Schematic` + `schematic_path: schemas/...`
  relative to the SITE root; toolkit prepends the schema `description` as lead paragraph and appends
  `## Diagram`/`## Properties`/`## Source` unless you author a same-named `##` section; `$ref` followed
  inside the staged tree; the diagram needs build-mode diagrams + PlantUML server (otherwise omitted).
- Charters exemplar to imitate in spirit: `../spec-kitty-charter/docs/architecture/pack-schemas/` (hub + one
  page per schema) and `../spec-kitty-charter/site/scripts/stage-docs-inputs.mjs` (parity check).
- Schema reality check (survey): `schemas/` is FLAT (no family folders); families come from the committed
  map file. Schemas carry `$id` + rich `description`, no top-level `title`.
- `type:` frontmatter comes from the hosting section (verified): events pages → `type: Integration`;
  the producer-row hub → `type: Architecture`; api pages → `type: API` (e.g. docs/api/envelope.md today).

## Branch Strategy

- Planning base / merge target: `issue-122-docs-site-overhaul` (serial after WP03).

Implement with: `spec-kitty agent action implement WP04 --agent claude --mission docs-site-overhaul-01M6GBDP`

## Subtasks & Detailed Guidance

### Subtask T019 – The family map and the generator

- **Purpose**: FR-007's committed generator research finding 8; fail-closed mapping.
- **Files**: `scripts/schema_pages_map.json`, `scripts/schema_pages.py` (stdlib only: json, re, pathlib, argparse, datetime).
- **Map shape** (`schema_pages_map.json`):

  ```json
  {
    "version": 1,
    "families": {
      "mission-lifecycle": {"hub": "docs/events/mission-lifecycle.md", "schemas": ["mission_created_payload.schema.json", "..."]},
      "...5 more families...": {},
      "shared": {"hub": "docs/events/shared/", "schemas": ["<everything not hubbed to a family>"]}
    }
  }
  ```

  Fill it by cross-reading `docs/events/*.md` tables (Event type → payload model) and the payload filenames;
  every `*_payload.schema.json` MUST land in exactly one named family; every other schema file defaults to
  `shared` unless a family claims it. The generator FAILS (exit 1, naming the file) on any payload schema not
  in the map and on any map entry naming a file that does not exist. This failure IS the family half of US4.
- **Generator behaviour** (`schema_pages.py [--write|--check]`, run from repo root):
  1. Enumerate the 4 source sets (see inventory), compute target route per file:
     `docs/architecture/producer-row-schemas/<stem>.md` (stems dotted like `mission.created.v1`; the dotted-stem
     question is a T019 EXIT CRITERION — generate ONE dotted page, build it, confirm the route, before any
     mass generation; if the route degenerates, normalise dots→double-dashes ONCE in generator+tests);
  2. Page frontmatter: `title` = humanised stem (e.g. `Schema: mission.created v1 (producer row)`);
     `description` = first sentence of schema `description` trimmed ≤ 180 chars (append "…" never — just cut at a word boundary); `doc_status: active`; `updated: <run-date>T00:00:00Z` STABLE per content-hash
     (content-stable tree: if the schema file bytes didn't change, keep the existing page's `updated` — `--check` must be
     idempotent on clean trees); `type`/`kind: Schematic`; `schematic_path` = staged path
     (`schemas/<name>` / `schemas/producer-rows/<name>` / `schemas/envelope.read.schema.json` style —
     coordinate exact staged layout with T020).
  3. Page body (generator-owned, clearly marked): one line "Generated from `<repo path>` by
     `scripts/schema_pages.py`; never hand-edit (C-001)." + `## Family` sentence linking the hub route +
     `## Uses` (ONLY when the schema carries cross-file `$ref`s: bullet links to those schemas' page routes;
     the corpus has zero cross-file refs today — squad S2 / research.md disposition — so the section simply
     does not appear on v1 pages; the rule engages when the first cross-file `$ref` lands) +
     a short "What this is" line from the map (payload event name, enum, etc.). Do NOT author
     `## Diagram`/`## Properties`/`## Source` — the toolkit emits them.
  4. Envelope overrides: `event.schema.json` page body starts with "**Normative envelope 3.0.0.** …";
     both producer envelope pages start with "**CloudEvents profile** (non-normative until 12.0.0). …"
     (FR-006; write/read distinguished).
  5. Hub blocks: for each family hub README/page insert/update
     `<!-- BEGIN GENERATED: schemas -->` … `<!-- END GENERATED: schemas -->` bullet list
     `- [<Title>](</route/>) — <one-line description>`; before the marker, any existing authored content
     is preserved; the six family pages get the block appended; shared/producer/envelope hubs get blocks too.
  6. `--check`: regenerate into a temp dir, diff file contents AND file sets against the working tree; every
     difference fails with `SCHEMA-PAGES DRIFT: <path> (missing page|orphan page|content differs|hub link missing)`.
  7. Route-collision guard (spec edge case): build the route set first; duplicates → exit 1 naming both.
- **Determinism**: no timestamps beyond `updated`; sorted enumeration; LF endings; ruff-clean; mypy-clean
  under the repo's configured python gates (`make lint` covers ruff only — if the repo type-checks scripts/,
  conform; check `pyproject.toml [tool.mypy] files`).

### Subtask T020 – Staging extension + parity assertion

- **Files**: `site/scripts/stage-docs-inputs.mjs` (out-of-map on WP03-owned file; serial; rationale: one staging
  script, single prebuild call).
- **Behaviour**: mirror-copy the three source trees into `site/schemas/` (top-level files of
  `src/spec_kitty_events/schemas/` → `site/schemas/`, `producer_contract/data/*.schema.json` →
  `site/schemas/producer-rows/`, the two envelopes → `site/schemas/envelope.schema.json` +
  `site/schemas/envelope.read.schema.json`); assert parity fail-closed between the staged set and the set of
  `schematic_path:` values found across `docs/events/**`, `docs/architecture/producer-row-schemas/**`,
  `docs/api/envelope/**` (count + name match; charter's section 3 pattern, adapted). Also keep bundle staging
  from WP03 working unchanged.

### Subtask T021 – Generate everything + hub conversions

- **Files**: 168 page files under the four trees; converted `docs/architecture/producer-row-schemas/README.md`
  (absorbs the old single page's prose; fix the stale "28 types, all v1" → current count statement from disk,
  and draft/Proposed → accepted/shipped per FR-014's producer-row sentence) and `docs/api/envelope/README.md`
  (absorbs old `docs/api/envelope.md` prose + links the normative page; DELETE the old .md files so routes stay
  `/architecture/producer-row-schemas/` and `/api/envelope/`); new `docs/events/shared/README.md` hub
  (frontmatter consistent with its section; description "Shared event-model types: …").
- The old files' `related:`/links that pointed INTO the old pages keep working because the routes are stable.
- After generation: run staging + a real build (T024) before committing.

### Subtask T022 – Wire the drift check everywhere

- **Makefile**: in `lint:` immediately after the `docs_index.py --check` line, add
  `uv run python scripts/schema_pages.py --check` (or `python3` — match neighbouring lines exactly).
- **`.github/workflows/docs-site.yml`** (out-of-map, WP02-owned; each new step carries `working-directory: .`
  since the job default is `site/`):
  1. Add a step after index generation: `"$RUNNER_TEMP/docs-venv/bin/python" scripts/schema_pages.py --check`
     (stdlib → the bare venv python suffices).
  2. Extend-to-final the post-build diagram gate (charter's count pattern): count staged schemas under
     `site/schemas/**/*.schema.json`-minus-nothing vs built schematic pages (`dist/events/**`… simpler invariant per
     NFR-003: EVERY staged `.schema.json` has a built page AND a rendered diagram:
     `comm -3 <(cd site/schemas && find . -name '*.schema.json' | sort) <(…map pages to names…)` — implement the
     simplest provably-correct comparison, comment it; then two greps:
     `grep -LE '<figure class="dk-diagram"[^>]*><svg' <schematic pages>` must be empty (charter's exact idiom) AND
     the error-SVG exclusion (squad S4 — a PlantUML error response IS an SVG): provoke one render failure locally
     (e.g. feed the PlantUML server a broken diagram), capture its exact error marker text inside the SVG
     (charter-era knowledge: `(cannot find)|(syntax error)`-style in the rendered text), and assert
     `grep -l <marker> <schematic figures>` is empty.
- The detect step already covers `scripts/schema_pages.py` (WP02 T007).

### Subtask T023 – tests/test_schema_pages.py [P wrt T021 after T019 lands]

- House style: tmp_path trees; no network; deterministic.
  - fixture mini-tree (2 families, 3 schemas incl. one shared $ref, 1 row schema, 2 fake envelopes) →
    `--write` produces exactly the expected file set; `--check` passes; rerun → byte-identical.
  - add one schema file → `--check` fails, stderr names it.
  - delete the schema, keep page → fails names the page (orphan).
  - unmapped `*_payload` → fails naming it (family map).
  - composed/registry/fixtures excluded from enumeration.
  - envelope labelling strings present on the three envelope pages.
  - cross-file `$ref` fixture schema → its page carries a `## Uses` link to the referenced schema's page route;
    the zero-cross-ref fixture corpus yields NO `## Uses` sections anywhere (squad S2 vacuity guard).
  - count-gate negative (squad S9): a `_run_step`-style bash test in `tests/test_docs_site_workflow.py` fixtures —
    a staged schema with no built page makes the T022.2 step exit non-zero NAMING it (steal the harness shape
    from `tests/test_ci_gate_workflows.py`'s step runner).
  - route-collision probe (two files mapping to same slug) → fails naming both.

### Subtask T024 – Verification sweep (the heavy one)

Commands + counts to history:
- `uv run pytest --no-cov -q tests/test_schema_pages.py tests/test_docs_site_workflow.py tests/test_changelog_fragments.py tests/test_docs_index.py`
- conformance gate; `ruff check . && ruff format --check .`; changelog check+guard; `make lint` (now includes the drift check).
- Produce bundles (quickstart recipe), start local PlantUML (docker), then
  `cd site && npm ci && npm run validate && DK_DIAGRAM_BUILD_RENDER=build DK_PLANTUML_SERVER_URL=http://localhost:8080/svg/ DK_PUBLISH_DRAFTS=on npm run build && npm run check:built-links`
  with fragments present; assert 168 pages built with rendered diagrams locally:
  `find dist -path '*schemas*' …` — actually assert per NFR-003 via the same command the workflow uses once T022.2 ships;
  record the per-page count + sample.
- Spot-verify US1: open 2 payload pages + the 3 envelope pages; sub-model link lands on its page.

## Test Strategy

Mandatory per above. The generator gets unit tests (T023) because US4/SC-005 are behavioural requirements.
No Java. No contract content changes.

## Risks & Mitigations

- **Dotted stems in routes**: the spike is T019's exit criterion (run BEFORE T021's mass generation); if Astro/doc-kitty routing
  rejects, normalise dots→double-dashes ONCE (code + tests) and record the rule in the generator header.
- **idempotency traps**: YAML dump ordering/quotes — hand-roll the frontmatter emitter (no yaml dependency!)
  with a fixed field order; never `yaml.dump`.
- **168-page build time**: first cold build renders every schematic diagram; local expectation ~minutes,
  CI warm-cache ≤15 min (NFR-001 recorded at WP07).
- **`updated:` churn** on regeneration without content change → keep existing value (see T019.2).

## Review Guidance (for reviewer-renata)

- `git diff --stat` sanity: docs/events/** carries ONLY generated pages + hub blocks (no hand-prose edits
  smuggled into family hubs outside blocks).
- The generator has no third-party imports; `--check` actually fails closed on all four failure modes
  (reproduce one locally from the test file).
- C-001: site/schemas/, site/openapi/ absent from the commit (git-ignored) and .gitignore lists them.
- FR-006 labels verbatim on the three envelope pages.

## Progress & Status

Event log only — `add-history`, `move-task`, `status`.
