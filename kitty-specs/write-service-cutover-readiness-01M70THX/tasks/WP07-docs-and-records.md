---
work_package_id: WP07
title: Docs, runbook, changelog fragments, records
dependencies:
- WP03
- WP04
- WP05
- WP06
requirement_refs:
- FR-018
- FR-019
- FR-021
- FR-022
- C-007
- C-008
- C-009
planning_base_branch: kitty/mission-write-service-cutover-readiness-01M70THX
merge_target_branch: main
branch_strategy: Planning artifacts for this mission were generated on kitty/mission-write-service-cutover-readiness-01M70THX. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into main unless the human explicitly redirects the landing branch.
subtasks:
- T032
- T033
- T034
- T035
- T036
- T037
history:
- timestamp: '2026-11-09T20:30:00Z'
  lane: planned
  agent: system
  action: Prompt generated via /spec-kitty.tasks
agent_profile: scribe-sally
authoritative_surface: docs/
create_intent:
- docs/changelog/unreleased/236-status-log-repair.md
- docs/changelog/unreleased/236-evidence-wire-field.md
- docs/changelog/unreleased/236-write-service-hardening.md
execution_mode: code_change
owned_files:
- docs/architecture/services/event-log-lock-and-append.md
- docs/architecture/services/write-service-protocol.md
- docs/guides/service-protocol-and-releases.md
- docs/guides/service-cutover-runbook.md
- docs/changelog/unreleased/236-status-log-repair.md
- docs/changelog/unreleased/236-evidence-wire-field.md
- docs/changelog/unreleased/236-write-service-hardening.md
- service/command-cli/README.md
role: implementer
tags: []
tracker_refs: []
---
# Work Package Prompt: WP07 — Docs, runbook, changelog fragments, records

## ⚡ Do This First: Load Agent Profile

Load your assigned profile before reading anything else: `/spk-charter-profile-load scribe-sally`
(or `spec-kitty agent profile show scribe-sally`). Adopt its identity, scope and boundaries.

## Objective

Make the docs say what the merged code (WP01–WP06) does, add the mission's changelog fragments, and fix the prior mission's record. **Docs only.** Read the merged code and contract to confirm every claim; never describe behaviour from this prompt alone.

Write plainly: short sentences, no hype. Match the surrounding pages' voice.

## Context (read first)

- Spec: [spec.md](../spec.md) (Maintainer Rulings D1–D4, D9, D10 and the corrections; FR-018, FR-019, FR-021), [plan.md](../plan.md) D-H and the squad section, [research.md](../research.md).
- **The merged truth:**
  - `contracts/mission-write/` (the new path, schemas and CHANGELOG; `LAUNCHER.md`);
  - `service/command-cli/src/main/java/.../application/repair/`, `adapter/in/http/operations/StatusLogRepairController.java`, `application/write/WriteService.java`, `application/diagnostics/FailureRecord.java`.
- Docs rules: `AGENTS.md` "Documentation"; frontmatter (`title`, `description` ≤ 180 chars, `doc_status`, `updated`); `docs/changelog/unreleased/README.md` for fragments.

## Subtasks

### T032 — `event-log-lock-and-append.md` and `service-protocol-and-releases.md`

- **`docs/architecture/services/event-log-lock-and-append.md:140-146`.** It says the status-log repair comes "after core #6195". Rewrite it: the explicit repair lands **before** #6195, as `POST /api/v1/missions/{missionId}/status-log-repairs` (#222, #236).
  - Describe it in a short paragraph: the same Mission lock and turn as a write; `none`/`completed`/`quarantined`; the quarantine file `status.events.jsonl.torn`; and the durability order (quarantine forced and directory synced before the truncate; the size re-check).
  - Keep the ledger `TornTailRepair` description distinct: a different lock and a different file.
  - Bump `updated`.
- **`docs/guides/service-protocol-and-releases.md:109`.** It says "A torn tail is refused, never repaired". Correct it to: refused by every write, repaired only by the explicit operation. Check the draining rows at :96-101 against D10 (a waiting request answers `503 SERVICE_DRAINING`). Bump `updated`.

### T033 — command-cli README and `write-service-protocol.md`

**`service/command-cli/README.md`**

- **"HTTP surface" (59-71):** add the repair operation row.
- **"The checks, in order" (108-141):** insert **read-back verification** as its own step between the authority step and the append. The append becomes the last step.
  - Describe the step: decode and re-fold the new rows with the log; refuse `400 REQUEST_MALFORMED` (with `instance` for a batch unit) for nesting beyond 256, an integer beyond 4300 digits, an undecodable actor object, or a unit the fold rejects.
  - Remove the duplicate from "Durability" (165-168), or leave a one-line pointer.
- **Repair links (151-154):** make them point at the operation.
- **"Binding notes" (236-254):** add `implementationEvidencePresent` (optional, request-only, honoured as core honours an explicit flag; an older service answers 400). Add the repair operation's binding.
- **"Unreachable reasons" (285-289):** keep `workspace_context_required` with **why** (core's fail-closed batch guard, operator decision 2026-09-07, #946, DRIFT-3). Remove the evidence half, which is reachable now.
- **Drain (293-301):** a waiting request answers `503 SERVICE_DRAINING`, and timings are unchanged (D10).
- **Logging:** add a sentence on the WARNING record (operation, code, exception classes; no messages, paths or content).
- **Stale line (333-335):** remove or correct the #6910 "core still calls bare paths" line. It is fixed on core main (`3dc4ca94f7`).

**`docs/architecture/services/write-service-protocol.md`**

- **Step list (89-112):** add the same read-back step.
- **Repair links (118-122, 209-211):** make them point at the operation.
- **"Operations" table (225-238):** add the repair row.
- **"Unreachable reasons" (216-223):** give the reason, as above.
- **Stop section (287-291):** the drain-aware wording.
- Bump `updated`.

### T034 — Cut-over runbook

In `docs/guides/service-cutover-runbook.md`, add a section **"Repairing a torn status log"**.

- **When:** a write answers `500 STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED` and the Mission's `status.events.jsonl` does not end in LF.
- **Until core's verb ships** (spec-kitty/spec-kitty#7482, linked to #6195), call the operation directly with `curl`, using the endpoint file's port and token.
  - Confirm the endpoint file's real location and field names from `LAUNCHER.md` and the code; do not guess.
  - Show the request body (`context` with `writeSurface: checkout_append`, `destinationRef: null`) and the three possible answers.
- **Afterwards:**
  - `quarantined` keeps the fragment in `status.events.jsonl.torn`. Inspect it, then delete it or commit it. It is an untracked file in a tracked Mission directory, so core's dirty-tree checks may notice it. Check core's behaviour, or state the uncertainty plainly.
  - `readableAfterRepair: false` means writes still fail for another reason. Escalate.
  - A torn **multi-row** unit: the earlier rows stay, so retrying the same unit answers `409 EVENT_ID_CONFLICT`. Re-issue it with new event ids.
- **Limit:** a coordination-routed Mission answers `422` until migration step 4. Give the manual remedy: cut the bytes after the last newline under the Mission lock with the service stopped, mirroring core's `kernel/event_log_append.py` remedy text.

### T035 — Changelog fragments

Write each fragment as in `docs/changelog/unreleased/README.md`: frontmatter `section`, `- ` bullets, repository-root links. Cite `(#236; #NNN)`. WP04 already added `236-evidence-corpus-case.md`; do not duplicate it.

| File | Section | Content |
|---|---|---|
| `236-status-log-repair.md` | `Added` | The status-log repair operation (#222) |
| `236-evidence-wire-field.md` | `Added` | `TransitionUnit.implementationEvidencePresent` (#224), including the rolling-upgrade note |
| `236-write-service-hardening.md` | `Changed` | The real root in the guard context (#223); `workspace_context_required` documented; the drain-aware wait (#231 4.7, D10); catch-all logging (#231 4.14); `WriteOperation` provisional (#231 4.12); the read-back step and `REQUEST_MALFORMED` (#231 4.15) |

Run `uv run python scripts/changelog.py check && uv run python scripts/changelog.py guard`.

### T036 — The prior mission's #222 row

Add a `#222` row to `kitty-specs/events-write-core-01M6TP6W/issue-matrix.json`, a record-only fix that #236 asks for. Match the existing rows' keys (`evidence_ref`, `fr`, `nfr`, `repo`, `sc`, `scope`, `source_file`, `title`, `verdict`, `wp`), with:

- `verdict: "deferred-with-followup"`;
- `evidence_ref: "Follow-up: #222, delivered by mission write-service-cutover-readiness-01M70THX (#236); operator ruling 2026-10-31 (refuse on the write path), 2026-11-08 (file the repair)"`;
- the rest null or as the #223 and #224 rows have them.

Validate the JSON (`python3 -m json.tool`). **This is a declared out-of-map edit** (a `kitty-specs/` path outside this mission): it is a record-only change the issue requires.

### T037 — Validation

```bash
cd site && npm ci --silent 2>/dev/null; npm run validate; cd ..
python3 scripts/docs_index.py --clean
uv run python scripts/changelog.py check && uv run python scripts/changelog.py guard
uv run pytest tests/test_docs_service_reference.py -q
```

`npm run validate` must report no frontmatter or link errors. `docs_index.py --clean` must leave no stray generated files: check `git status`.

## Definition of Done

- **Checks:** every check in T037 is green.
- **Claims match the code:** each claim about behaviour is checked against the merged code or contract (cite file:line in the commit message for the non-obvious ones).
- **No stale text:** no remaining "after core #6195" for repair, no "never repaired", no stale #6910 line.
- **Commits:** commit messages end with `Co-Authored-By:` (your model).

## Do not

- Do not edit code, contracts, `CHANGELOG.md`'s `[Unreleased]`, or AGENTS.md/CLAUDE.md.
- Do not run `spec-kitty` lifecycle commands, push, or open a PR.
