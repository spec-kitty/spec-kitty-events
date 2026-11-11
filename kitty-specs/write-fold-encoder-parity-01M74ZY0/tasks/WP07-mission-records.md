---
work_package_id: WP07
title: 'Mission records: issue matrix, counts and rulings (FR-018, FR-019)'
dependencies:
- WP01
- WP02
- WP03
- WP04
- WP05
- WP06
requirement_refs:
- FR-018
- FR-019
planning_base_branch: issue-237-write-fold-encoder-parity
merge_target_branch: issue-237-write-fold-encoder-parity
branch_strategy: Single-branch mission. Planning, base and merge target are issue-237-write-fold-encoder-parity; the finished branch goes to main through one PR.
subtasks:
- T031
- T032
history:
- timestamp: '2026-11-11T11:30:00Z'
  lane: planned
  agent: system
  action: Prompt generated via /spec-kitty.tasks
agent_profile: curator-carla
authoritative_surface: kitty-specs/write-fold-encoder-parity-01M74ZY0/
create_intent:
- kitty-specs/write-fold-encoder-parity-01M74ZY0/issue-matrix.json
execution_mode: planning_artifact
owned_files:
- kitty-specs/write-fold-encoder-parity-01M74ZY0/issue-matrix.json
- kitty-specs/write-fold-encoder-parity-01M74ZY0/research.md
role: implementer
tags: []
tracker_refs: []
---
# Work Package Prompt: WP07 — Mission records (FR-018, FR-019)

## ⚡ Do This First: Load Agent Profile

Load your assigned profile before reading anything else: `/spk-charter-profile-load curator-carla`
(or `spec-kitty agent profile show curator-carla`). Adopt its identity, scope and boundaries.

## Objective

Keep the mission record truthful and approvable: an issue matrix for every GitHub issue the
mission's artefacts cite, and the research record updated with what the WPs actually found.

## Subtasks

### T031 — Issue matrix
Create `kitty-specs/write-fold-encoder-parity-01M74ZY0/issue-matrix.json` in the shape of
`kitty-specs/write-service-cutover-readiness-01M70THX/issue-matrix.json` (`{"rows": {"#N": {...}}}`
with `evidence_ref`, `fr`, `nfr`, `repo`, `sc`, `scope`, `source_file`, `title`, `verdict`, `wp`).
1. `grep -on "#[0-9]\+" kitty-specs/write-fold-encoder-parity-01M74ZY0/{spec,plan,research,tasks,data-model}.md kitty-specs/write-fold-encoder-parity-01M74ZY0/tasks/*.md`
   to list every cited number.
2. `#237` and `#231`: `in-mission`, mapping each #231 item to its WP and FR
   (4.1–4.5 WP03; 4.6 WP04; 4.8 WP02; 4.9 WP05; 4.10, 4.11, 4.13 WP06; 4.16, 4.17 WP01; 4.7, 4.12,
   4.14, 4.15 landed in #236/M1).
3. Every other cited number (core #6195, #230, #236, #247, #248, #170, …): `not-applicable` with a
   one-line reason (context, sibling mission, or core issue), `repo` set for spec-kitty/spec-kitty
   references.
4. Validate with the CLI's issue-matrix check if one exists (`spec-kitty agent ... --help`), else by
   loading the JSON.

### T032 — Research record
Append to `research.md` a "Findings during implementation" section: the final ack-case count
(WP05), the edge-graph differences found at the pin (WP04), which differential directions WP01
could construct, the 3.10 availability for WP03's cross-interpreter check, and any divergence from
core recorded as a ruling with its core issue (C-003). Read each WP's activity log / review file.

## Definition of Done
- `issue-matrix.json` parses and covers every cited issue; `finalize-tasks --validate-only` and the
  review gate do not report a missing row.
- research.md section present and factual (no claim without a WP source).

Implementation command: `spec-kitty agent action implement WP07 --agent <name> --mission write-fold-encoder-parity-01M74ZY0`
