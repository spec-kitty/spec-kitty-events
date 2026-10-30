---
work_package_id: WP08
title: First service pre-release and core handshake (cutover-gated)
dependencies:
- WP07
requirement_refs:
- FR-011
- C-003
- SC-004
planning_base_branch: issue-147-events-services-home
merge_target_branch: issue-147-events-services-home
branch_strategy: Planning artifacts for this mission were generated on issue-147-events-services-home. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into issue-147-events-services-home unless the human explicitly redirects the landing branch.
subtasks:
- T035
- T036
- T037
history:
- timestamp: '2026-10-29T20:40:00Z'
  lane: planned
  agent: system
  action: Prompt generated via /spec-kitty.tasks
agent_profile: implementer-ivan
authoritative_surface: docs/guides/service-releases/
create_intent:
- docs/guides/service-releases/service-v0.1.0-rc.1.md
execution_mode: code_change
owned_files:
- docs/guides/service-releases/**
role: implementer
tags:
- cutover-gated
tracker_refs: []
---
# Work Package Prompt: WP08 – First service pre-release and core handshake

## ⛔ GATE
This WP needs two things first: WP07 merged to `main`, and **explicit operator approval to push the tag** (`service-v0.1.0-rc.1`, or the version the operator names). Issue transfers need separate operator approval. Never push `main`.

## ⚡ Do This First: Load Agent Profile

Load your assigned profile before reading anything else: `/spk-charter-profile-load implementer-ivan`
(or `spec-kitty agent profile show implementer-ivan`). Adopt its identity, scope and boundaries.

## Branch Strategy
The planning base and the merge target are both `issue-147-events-services-home`. The tag is pushed on the merged `main` commit.
`spec-kitty agent action implement WP08 --agent claude --mission events-domain-services-home-01M64MSB`

## Subtasks

### T035 Tag
Once the operator approves, tag the merged `main` commit as `service-v0.1.0-rc.1` and push the tag only. Watch `service-release.yml` until it finishes.

### T036 Verify the release
- Download every asset and run `sha256sum --check --strict SHA256SUMS`.
- Run `gh attestation verify <asset> --repo spec-kitty/spec-kitty-events` for each non-checksum asset.
- Check that the asset set equals `contracts/service-release-assets.md` exactly.
- Record the native binary size per target. A binary over 100 MB is fine because core uses the first-run fetch (D6), but report it.
- Write `docs/guides/service-releases/service-v0.1.0-rc.1.md` (kind: Reference): the tag, commit, assets with their sha256 sums and sizes, and the package version tested against.

### T037 Handshake and issue moves
- `send_message` to the core session (id from the operator, currently `session_019C2Tk37WcLbdx5dHU3JzP7`) with the tag, the asset names and the sha256 sums.
- With operator approval, run the issue moves from the runbook (IssueMove table): create the labels, transfer the epic and its children, split the rest, and close #6692 as done with a link to ADR 0002.

## Definition of Done
- The pre-release exists with all assets verified, the sizes are recorded, the core session has the pins, and the issue moves are done or explicitly deferred by the operator.
