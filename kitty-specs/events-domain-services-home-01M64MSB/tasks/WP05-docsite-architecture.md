---
work_package_id: WP05
title: Docsite bump and service architecture docs
dependencies:
- WP01
requirement_refs:
- FR-006
- FR-007
- NFR-004
planning_base_branch: issue-147-events-services-home
merge_target_branch: issue-147-events-services-home
branch_strategy: Planning artifacts for this mission were generated on issue-147-events-services-home. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into issue-147-events-services-home unless the human explicitly redirects the landing branch.
subtasks:
- T020
- T021
- T022
- T023
- T024
- T025
history:
- timestamp: '2026-10-29T20:40:00Z'
  lane: planned
  agent: system
  action: Prompt generated via /spec-kitty.tasks
agent_profile: scribe-sally
authoritative_surface: docs/architecture/services/
create_intent:
- docs/architecture/services/README.md
- docs/architecture/services/overview.md
- docs/architecture/services/read-write-split.md
- docs/architecture/services/event-log-lock-and-append.md
- docs/architecture/services/write-service-protocol.md
execution_mode: code_change
owned_files:
- site/package.json
- site/package-lock.json
- docs/architecture/services/**
- docs/architecture/README.md
- docs/_meta/sections.yaml
role: implementer
tags: []
tracker_refs: []
---
# Work Package Prompt: WP05 – Docsite bump and service architecture docs

## ⚡ Do This First: Load Agent Profile

Load your assigned profile before reading anything else: `/spk-charter-profile-load scribe-sally`
(or `spec-kitty agent profile show scribe-sally`). Adopt its identity, scope and boundaries. For the diagrams, also load `spk-practice-show-me`.

## Branch Strategy

The planning base and the merge target are both `issue-147-events-services-home`; the PR targets `main`. The workspace comes from `lanes.json`.
`spec-kitty agent action implement WP05 --agent claude --mission events-domain-services-home-01M64MSB`

## Context
- **DocKitty conventions**: `docs/README.md`, `docs/_meta/sections.yaml`, and existing pages such as `docs/architecture/cloudevents-envelope.md`. Frontmatter: `title`, `description` ≤180 chars, `doc_status`, `updated`, `type`, `kind`, `okf_version` where peers use it. doc-toolkit 0.5.0 **requires `kind` on every page**.
- **Sources**, read-only in core at `/home/user/spec-kitty/spec-kitty` (main @ `4eecb124`):
  - `docs/architecture/event-ledger-writers-and-readers.md` (293 lines)
  - `services/mission-status-api/README.md` (236 lines)
  - `docs/development/reference/lock-interop-spike.md`
  - the C4 diagrams `01_context` and `02_containers` (find them with `grep -rl 01_context docs`)
  - ADRs `docs/adr/4.x/2026-10-01-2-*`, `2026-10-14-1-*`, `2026-10-22-1-*`, `2026-10-27-1-*`
  - code facts: `services/mission-status-api/{domain-model,event-log}/` and the ArchUnit tests `ArchitectureRulesTest`, `DomainModelBoundaryTest`, `EventLogBoundaryTest`
  - the protocol contract `contracts/mission-write/`
- Every page states at the top that **the code moves into this repo at cutover** (ADR 0002). Until then the source of truth is core at the cited SHA. Cite paths as `spec-kitty/spec-kitty@4eecb124:<path>` links. Do not describe behaviour that the code or contracts do not have; when unsure, quote the contract.
- **Write-side caveat**: the write core (`command-cli`) is **not built yet**. Only its protocol contract (#6675, PR #6729) and the event-log write-side library (#6193: lock interop, atomic append, torn-tail repair) exist. Say so plainly.

## Subtasks

### T020 doc-toolkit 0.5.0
- In `site/package.json`, change `"@spec-kitty/doc-toolkit": "0.4.0"` to `"0.5.0"` and refresh `site/package-lock.json` (`npm install --package-lock-only` in `site/`; the registry is `npm.pkg.github.com` for `@spec-kitty` and needs `NODE_AUTH_TOKEN`). If no token is available in the sandbox, edit the lockfile entry by hand (version, resolved, integrity), with the integrity taken from the doc-kitty release or tag, and say so in the hand-off. CI's `docs-site.yml` proves the build either way.
- Review the lockfile diff: only doc-toolkit and its own transitive deps should change (DIRECTIVE_051, control 4). List any other change.
- Read `/home/user/spec-kitty/doc-kitty/docs/migrations/0.4-to-0.5.md` and apply anything it asks for.

### T021 `docs/architecture/services/overview.md` (kind: Architecture)
- **C4 level 1 (context)**, Mermaid: CLI (core) → read service (CI-only pin) and write service (resident per repo, future); dashboard/desktop consumers → read API; the event log files in the repo.
- **C4 level 2 (containers)**: `mission-status-api` (Spring Boot, native), `domain-model` (library), `event-log` (library), contracts (OpenAPI), and `command-cli` (future write deployable).
- **Onion**: domain-model (pure) ← application (query services, ports) ← adapters (http in, filesystem out). Give the rule list the ArchUnit tests enforce, with the test class names as evidence.
- Where it lives: today in core; after cutover in `service/` here.

### T022 `read-write-split.md`: the CQRS split
- The reads are the Mission Status Read API (core ADR 2026-10-01-2): two granularities, read-only, drain-independent, fail-loud reads, allowlists.
- The writes are the Java write service as the one canonical writer (core ADRs 2026-10-14-1 and 2026-10-22-1): the CLI decides what and when, the service decides how and where; one resident service per repo; native first, JVM fallback.
- Include a table of current writers and readers vs the target state, from `event-ledger-writers-and-readers.md`, summarized and linked, not copied wholesale. The original file is imported with history at cutover and then retired into this page (WP07 T033).

### T023 `event-log-lock-and-append.md`
- Cover the lock protocol shared with the Python appender (core `src/kernel/locks.py`, `src/kernel/event_log_append.py`), atomic append, durability, torn-tail detection and repair (`event-log` `torn-tail/repair-cases.json`), and the lock-interop spike result (lock-interop-spike.md).
- Explain why CI checks out core at a pinned SHA for the interop test (ADR 0002, D5).

### T024 `write-service-protocol.md`
- Core ADR 2026-10-27-1 plus `contracts/mission-write/`:
  - loopback HTTP
  - the bearer token from the git common dir and the endpoint file
  - handshake and version negotiation
  - durability-acked writes
  - refusal codes matching the CLI's
- Add a sequence diagram (Mermaid): CLI → endpoint file → handshake → write → ack/refusal.
- List the operations from the contract's `openapi.yaml` (operationIds).

### T025 Index, sections, checks
- Add `docs/architecture/services/README.md` (kind: Hub) listing T021–T024, and link it from `docs/architecture/README.md`. If the registry needs a nested section, update `docs/_meta/sections.yaml` (the `architecture` purpose line may mention the services).
- Run:
  - `node /home/user/spec-kitty/doc-kitty/src/scripts/validate-frontmatter.mjs docs`
  - `node /home/user/spec-kitty/doc-kitty/src/scripts/check-links.mjs docs`

  Both must show 0 errors.
- If npm auth allows, also run `npm ci && npm run validate && npm run build` in `site/`. Otherwise note that `docs-site.yml` is the proof.

## Definition of Done
- Version 0.5.0 is pinned and locked. The 5 pages exist with valid frontmatter and diagrams. The doc checks show 0 errors.
- Every behavioural claim cites a core path or contract.
