---
work_package_id: WP07
title: Changelog fragments and final verification gates
dependencies:
- WP05
- WP06
requirement_refs:
- FR-021
- NFR-001
- NFR-002
- NFR-003
- NFR-004
- NFR-005
- SC-001
- SC-002
- SC-003
- SC-004
- SC-005
- SC-006
- C-005
planning_base_branch: issue-122-docs-site-overhaul
merge_target_branch: issue-122-docs-site-overhaul
branch_strategy: Planning artifacts for this mission were generated on issue-122-docs-site-overhaul. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into issue-122-docs-site-overhaul unless the human explicitly redirects the landing branch.
base_branch: kitty/mission-docs-site-overhaul-01M6GBDP
base_commit: f085400ec310858d042a24e4658e13b6dfe92d7a
created_at: '2026-11-03T17:04:26.117233+00:00'
subtasks:
- T036
- T037
- T038
phase: Phase 4 - Close-out
history:
- at: '2026-11-03T09:52:50Z'
  actor: system
  action: Prompt generated via /spec-kitty.tasks
agent_profile: implementer-ivan
authoritative_surface: docs/changelog/unreleased/
create_intent:
- docs/changelog/unreleased/181-docs-site-fragment-exclusion.md
- docs/changelog/unreleased/193-services-entry-docs.md
- docs/changelog/unreleased/194-service-api-reference.md
- docs/changelog/unreleased/195-schema-pages-and-build-render.md
execution_mode: code_change
model: ''
owned_files:
- docs/changelog/unreleased/181-docs-site-fragment-exclusion.md
- docs/changelog/unreleased/193-services-entry-docs.md
- docs/changelog/unreleased/194-service-api-reference.md
- docs/changelog/unreleased/195-schema-pages-and-build-render.md
role: implementer
tags: []
task_type: implement
tracker_refs: []
---

# Work Package Prompt: WP07 – Changelog fragments and final verification gates

## ⚡ Do This First: Load Agent Profile

Use the `/spk-charter-profile-load` skill to load the agent profile specified in the frontmatter (or any user-defined profile), and behave according to its guidance before parsing the rest of this prompt.

- **Profile**: `implementer-ivan`
- **Role**: `implementer`
- **Agent/tool**: `claude`

---

## ⚠️ IMPORTANT: Review Feedback

Check the `review_ref` field (`spec-kitty agent tasks status`); address all feedback; log fixes via `add-history WP07`.

---

## Review Feedback

*[If returned from review, the reviewer feedback reference appears in the status event log.]*

---

## Markdown Formatting

Wrap HTML/XML tags in backticks. Use language identifiers in code blocks.

---

## Objectives & Success Criteria

FR-021 + the release-shape evidence for the single PR (spec C-005). Done means:
- Four fragments exist (one per issue), accepted by `changelog.py check` and invisible to `changelog.py guard`
  violations; they are NOT pages (`docs/changelog/unreleased/` is excluded — WP01).
- The complete verification record (commands + counts, AGENTS.md §6 style) is in the WP history, ready to be
  pasted into the PR. Includes site timings, NFR checks, US4/SC-005 negative-probe evidence, and the
  SC-003 sweep output (WP06's, re-run clean here against the final tree).
- A PR body skeleton commit message/notes exists in the WP history for the operator (who opens the PR;
  agents never merge).

## Context & Constraints

- Fragment format and SECTIONS come from `docs/changelog/unreleased/README.md` + `scripts/changelog.py`
  (`SECTIONS`); filename `<issue>-<slug>.md`; frontmatter per the README (no page frontmatter).
- Existing fragments already present in the folder (e.g. from other merged work) are not yours — do not touch.
- Out of scope (C-006): planning repo, #196 (service-line placement in CHANGELOG.md), CSP hashes.
- This is the only WP whose owned files are all created here (create_intent).

## Branch Strategy

- Planning base / merge target: `issue-122-docs-site-overhaul` (after WP05 + WP06).

Implement with: `spec-kitty agent action implement WP07 --agent claude --mission docs-site-overhaul-01M6GBDP`

## Subtasks & Detailed Guidance

### Subtask T036 – Four fragments [P]

- `#181` (Changed): "The docs site runs on doc-kitty 0.6.0 and excludes `docs/changelog/unreleased/`
  natively (`exclude:` + `--exclude` gates); the CI-only fragment-removal stopgap is gone."
- `#193` (Changed/Added): README + entry/context/architecture docs describe the dual release lines and the
  read/write services and route readers to them (incl. the glossary terms and the contracts release-rule
  corrections).
- `#194` (Added): service API reference pages at `/reference/api/{mission-status,mission-write}/` with raw
  bundle download + `.sha256`, marked unreleased until the first final `service-v*` tag; new
  `docs/api/services.md` hub.
- `#195` (Added): 168 generated schema pages (payloads, producer rows, envelopes — normative vs CloudEvents
  profile labelled) with build-time-rendered diagrams, drift check in `make lint` and the docs build;
  CI renders all diagrams at build time from digest-pinned images (ADR-0006); docs build triggers on
  contracts/schemas/producer-contract changes.
- Style: past-tense summaries, one feature per bullet line, links to docs routes (not code) — read two
  collected entries from `CHANGELOG.md` history (e.g. 11.3.0 section) to match voice.
- **Check**: `uv run python scripts/changelog.py check` passes; `guard` passes
  (i.e. `[Unreleased]` in CHANGELOG.md untouched — never hand-edited).

### Subtask T037 – The final verification sweep

Run the complete AGENTS.md §6 set with `docs/changelog/unreleased/` present, and record command + count per
step in history:

1. `make test-fast` (blast-radius baseline for this WP: changelog fragments tests).
2. `uv run pytest --pyargs spec_kitty_events.conformance`.
3. `ruff check . && ruff format --check .`, `make lint` (includes `schema_pages.py --check` and
   `docs_index.py --check` — MUST pass with indexes clean).
4. Site chain, full parity incl. diagrams:
   - fragments present on disk;
   - bundles produced (quickstart recipe: out-of-repo `--out`, `SKE_CONTRACT_BUNDLES_DIR` set);
   - local PlantUML running (docker, pinned digest);
   - `cd site && npm ci && npm run validate && DK_DIAGRAM_BUILD_RENDER=build DK_PLANTUML_SERVER_URL=http://localhost:8080/svg/ DK_PUBLISH_DRAFTS=on npm run build && npm run check:built-links`;
   - drift probes: (a) drop one schema → `--check` fails naming it; restore; (b) add a throwaway `.md` page
     with a `schematic_path` naming no schema → fails; delete it;
   - NFR-003 spot: every built schematic page holds `<figure class="dk-diagram"` with `<svg`;
   - NFR-004: validate + built-links reports zero;
   - NFR-005: grep the workflow — 100% of `image:`/`uses:` lines carry `@sha256:`;
   - US2 click paths re-verified against the FINAL tree.
5. Record timings of the local full build as an indicative note. TRUE NFR-001/002 numbers come from the PR's
   CI run: the PR body's NFR table carries open slots with the CI run URL — those slots are MERGE-BLOCKING
   (squad S3): "pending-CI" never counts as a verdict, and a breach (warm >15 min / cold >30 min / probe
   >120 s) triggers ADR-0006's cache notes before anyone merges.

### Subtask T038 – PR body skeleton

Write into the WP history a PR body skeleton (operator opens the real PR):
- Title: `docs: site overhaul — doc-kitty 0.6.0, schema pages, service API reference (#181 #193 #194 #195)`
- Summary paragraph + the FR table with ✅ links to evidence sections in the PR description;
- "Tests run" table (commands + counts from T037);
- The click-audit and sweep outputs;
- Explicit note of the one-branch-one-PR rule change (FR-018, maintainer ruling in research.md);
- Out-of-scope list (C-006); closes lines: `Closes #181`, `Closes #193`, `Closes #194`, `Closes #195`,
  `Part of #122`.

## Test Strategy

Re-runs of all touched suites + the above sweeps; the negative probes are MANDATORY evidence for SC-005.

## Risks & Mitigations

- **Fragment format drift** → `changelog.py check` is the single arbiter; never touch CHANGELOG.md.
- **Late discovery during sweep** → fix in place ONLY if trivial and inside owned_files; otherwise record as
  review feedback against the owning WP and let the loop handle it — WP07 must not grow a code scope.

## Review Guidance (for reviewer-renata)

- Exactly 4 new files, all fragments, all passing `check`.
- History contains the full evidence table; every NFR/SC has a recorded verdict — pass locally, or an open
  CI-evidence slot that is understood to be merge-blocking (squad S3), never silently complete.

## Progress & Status

Event log only — `add-history`, `move-task`, `status`.
