---
work_package_id: WP05
title: 'Entry-point refresh: README, docs home, context, sections'
dependencies:
- WP04
requirement_refs:
- FR-012
- FR-013
- SC-002
- SC-003
planning_base_branch: issue-122-docs-site-overhaul
merge_target_branch: issue-122-docs-site-overhaul
branch_strategy: Planning artifacts for this mission were generated on issue-122-docs-site-overhaul. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into issue-122-docs-site-overhaul unless the human explicitly redirects the landing branch.
base_branch: kitty/mission-docs-site-overhaul-01M6GBDP
base_commit: 527725578a68a498469918ba79747b6d3b2e91b7
created_at: '2026-11-03T15:49:52.097458+00:00'
subtasks:
- T025
- T026
- T027
- T028
phase: Phase 3 - Content
history:
- at: '2026-11-03T09:52:50Z'
  actor: system
  action: Prompt generated via /spec-kitty.tasks
agent_profile: implementer-ivan
authoritative_surface: docs/
create_intent: []
execution_mode: code_change
model: ''
owned_files:
- README.md
- docs/README.md
- docs/context/README.md
- docs/context/system-context.md
- docs/_meta/sections.yaml
- docs/architecture/README.md
role: implementer
tags: []
task_type: implement
tracker_refs: []
---

# Work Package Prompt: WP05 – Entry-point refresh: README, docs home, context, sections

## ⚡ Do This First: Load Agent Profile

Use the `/spk-charter-profile-load` skill to load the agent profile specified in the frontmatter (or any user-defined profile), and behave according to its guidance before parsing the rest of this prompt.

- **Profile**: `implementer-ivan`
- **Role**: `implementer`
- **Agent/tool**: `claude`

---

## ⚠️ IMPORTANT: Review Feedback

Check the `review_ref` field (`spec-kitty agent tasks status`); address all feedback; log fixes via `add-history WP05`.

---

## Review Feedback

*[If returned from review, the reviewer feedback reference appears in the status event log.]*

---

## Markdown Formatting

Wrap HTML/XML tags in backticks. Use language identifiers in code blocks.

---

## Objectives & Success Criteria

Closes the entry half of #193 (FR-012, FR-013). Done means:
- Root `README.md` names BOTH release lines (PyPI `v*`, currently 11.3.0; `service-v*`, current
  `service-v0.1.0-rc.2`), both services (read = mission-status-api, write = command-cli) and how to run them,
  The Gradle build (`./gradlew verifyAll`, `make test-java`/`make test-gradle` — pick what exists; VERIFY
  every command by reading the Makefile/scripts before writing), the contracts surface, an updated mermaid
  flowchart, and links to the guides and to `<site>/reference/api/mission-status/` /
  `<site>/reference/api/mission-write/`.
- Docs entry pages route to services (FR-013): `docs/README.md` (incl. its "Start here"),
  `docs/context/README.md`, `docs/context/system-context.md` (services present in diagram AND prose),
  `docs/architecture/README.md`, and the `context`/`api`/`guides` purposes in `docs/_meta/sections.yaml`.
- US2 acceptance: from README and from docs home, each service reference in ≤3 clicks, the running guide ≤2.
- SC-003 subset: none of the edited pages describes the repo as package-only.

## Context & Constraints

- Routes available by now (WP03/WP04 landed): `/reference/api/mission-status|mission-write/`,
  `/api/services/`, family schema pages `/events/<family>/<slug>/`, `/architecture/producer-row-schemas/…`,
  normative envelope at `/api/envelope/event-envelope/`.
- docsite.md link rules: root-absolute routes for docs pages; FULL GitHub URLs for files outside docs/
  (e.g. `src/spec_kitty_events/schemas/`, `contracts/…`, Makefile).
- `docs/context/system-context.md` has a mermaid diagram (update it to include the services) AND prose;
  frontmatter `updated:` bumps to landing date; descriptions stay ≤180 chars.
- The root README is NOT a docs-site page (docsDir is `docs/`) — links to site routes there must be absolute
  `https://spec-kitty.github.io/spec-kitty-events/...` URLs.
- Keep README tone/structure of the current file (What this package gives you / Documentation / Installation /
  Quick start / Versioning / Development / License) — extend, don't reorganise.
- The architecture index carries one FR-014 sentence (producer-row/architecture items as accepted/shipped);
  WP06 handles the rest of FR-014. Touch `docs/architecture/README.md` minimally for FR-013 routing + that
  one status sentence; overview.md itself is WP06's.

## Branch Strategy

- Planning base / merge target: `issue-122-docs-site-overhaul` (parallel with WP06 after WP04).

Implement with: `spec-kitty agent action implement WP05 --agent claude --mission docs-site-overhaul-01M6GBDP`

## Subtasks & Detailed Guidance

### Subtask T025 – Root README rewrite (FR-012)

- **Steps**:
  1. Inventory reality: `make help`-equivalent — read `Makefile`, `pyproject.toml` (11.3.0),
     `service/*/README*`, `contracts/README.md` headings, docs/guides listing; record the exact runnable
     commands and paths you reference. NOTHING in the README may be aspirational.
  2. Rewrite with house style: two-line repo identity ("the canonical TeamSpace event contract package
     AND the home of the events-domain services"), quick orientation diagram (small mermaid flowchart:
     package→consumers; services→status/write paths; contracts→services), install/quick-start paragraphs,
     new "Services" section (read vs write, how to run from the runbook/guide links, release line and rc
     state), "Contracts and API reference" section (links to site routes), "Two release lines" section,
     development section pointing at AGENTS.md + make test-fast/conformance/lint.
  3. Version strings: state 11.3.0 and service-v0.1.0-rc.2 exactly once each, near the versioning section.
- **Files**: `README.md`.

### Subtask T026 – docs home + context pages [P with T027]

- **Steps**:
  1. `docs/README.md`: extend "Start here" so a newcomer learns services exist: add a services row
     (read/write, links `/api/services/` + `/guides/running-the-service/`); adjust the purpose paragraph.
  2. `docs/context/README.md`: add the services to the reading-order list.
  3. `docs/context/system-context.md`: extend the context diagram with `mission-status-api` (read) and
     `command-cli` (write) nodes + edges to CLI/SaaS consumers, prose pass listing the services and their
     loopback posture; bump `updated:`.
- Keep every existing accurate sentence; this is additive correction, not a rewrite.

### Subtask T027 – sections.yaml purposes + architecture index [P with T026]

- **`docs/_meta/sections.yaml`**: update purpose strings for `context` (mention read/write services),
  `api` (mention the service reference hub), `guides` — current text: "Task-oriented how-tos for contributors
  and consumers of the contract." → make operators of the services an explicit second audience. Keep all other
  sections byte-identical.
- **`docs/architecture/README.md`**: add services to the map (link `/architecture/services/`… verify that
  folder's hub route exists first — read the file), add the FR-014 status sentence about producer-row
  schemas and accepted/shipped architecture material.

### Subtask T028 – Verification

- `cd site && npm ci && npm run validate`; full build (bundles + PlantUML per quickstart.md)
  + `npm run check:built-links`.
- Click-audit: write into `add-history` the exact click paths you verified for US1 (schema page ≤3 from home)
  and US2 (README and docs home → reference ≤3; → running guide ≤2). Each row records the LITERAL element
  path clicked (e.g. `README.md "API reference" link → /reference/api/mission-status/` or
  `sidebar: Events → Mission lifecycle → <schema page>`), so a reviewer can reproduce in minutes (squad S10).
- Python gates: conformance, ruff, changelog check+guard, `make lint` (drift check green).

## Test Strategy

No new python tests (contentWP); verification is the site chain + audits above. `check:built-links` is the
mechanical guarantee for every added route link.

## Risks & Mitigations

- **README lying later**: every command/version cited is either (a) read from the file that owns it or
  (b) executed; cite the provenance in the WP history.
- **Link rot to reference routes**: they exist — built-links would fail otherwise; if a route name differs
  from this prompt, fix the prompt-side reference, not the WP03 route.

## Review Guidance (for reviewer-renata)

- No file outside `owned_files` changed.
- Click budgets demonstrably met (history table).
- Entry surfaces zero "package-only" phrasing; sections.yaml edits confined to the three purpose strings.

## Progress & Status

Event log only — `add-history`, `move-task`, `status`.
