---
work_package_id: WP01
title: 'Contract: repair operation, evidence field, wording, CHANGELOG'
dependencies: []
requirement_refs:
- FR-001
- FR-008
- FR-013
- FR-017
- FR-018
- FR-020
- C-003
- SC-006
planning_base_branch: kitty/mission-write-service-cutover-readiness-01M70THX
merge_target_branch: main
branch_strategy: Planning artifacts for this mission were generated on kitty/mission-write-service-cutover-readiness-01M70THX. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into main unless the human explicitly redirects the landing branch.
subtasks:
- T001
- T002
- T003
- T004
- T005
- T006
history:
- timestamp: '2026-11-09T20:30:00Z'
  lane: planned
  agent: system
  action: Prompt generated via /spec-kitty.tasks
agent_profile: architect-alphonso
authoritative_surface: contracts/mission-write/
create_intent:
- contracts/mission-write/paths/missions_missionId_status-log-repairs.yaml
- contracts/mission-write/schemas/StatusLogRepairRequest.yaml
- contracts/mission-write/schemas/StatusLogRepairAck.yaml
- contracts/mission-write/schemas/StatusLogRepairAction.yaml
execution_mode: code_change
owned_files:
- contracts/mission-write/**
- contracts/tools/enum_pins.json
- contracts/tools/fixtures/example_check/required_examples.json
role: implementer
tags: []
tracker_refs: []
---
# Work Package Prompt: WP01 — Contract: repair operation, evidence field, wording, CHANGELOG

## ⚡ Do This First: Load Agent Profile

Load your assigned profile before reading anything else: `/spk-charter-profile-load architect-alphonso`
(or `spec-kitty agent profile show architect-alphonso`). Adopt its identity, scope and boundaries.

## Objective

Make every `contracts/mission-write` change of this mission. All of them are additive inside the unreleased `1.0.1-SNAPSHOT` (no `service-v*` tag carries it: rc.1 and rc.2 carry `1.0.0-SNAPSHOT`):

1. a new operation, `POST /missions/{missionId}/status-log-repairs` (#222, maintainer rulings D1/D2);
2. three new schemas: `StatusLogRepairRequest`, `StatusLogRepairAck` and `StatusLogRepairAction`;
3. an optional `TransitionUnit.implementationEvidencePresent` (#224, D3);
4. description changes: `WriteOperation` gets `x-provisional` (#231 4.12); `WriteRefusalReason` (#223, #224); `WriteRefusalCode.REQUEST_MALFORMED` (the read-back cases, #231 4.15 and the comment on `64ba3483`); and drain-aware wording on `SERVICE_DRAINING` (ruling D10);
5. the `CHANGELOG.md` entries;
6. a recorded run of every contract tool **against a real baseline**.

No Java or Python code changes in this WP.

## Context (read first)

- Spec: [spec.md](../spec.md), FR-001, FR-008, FR-013, FR-017, FR-018, FR-020, C-003, SC-006, User Story 4.
- Plan: [plan.md](../plan.md) D-G and **"Post-plan adversarial squad: folded findings → Contract, parity and scope"**. That section overrides D-G where they differ.
- Design input: [contracts/status-log-repair.md](../contracts/status-log-repair.md) and [data-model.md](../data-model.md).
- Contract rules: `contracts/README.md`.
  - Closed response schemas: 536-558.
  - Versioning: 528-534.
  - Unchecked tools: 582-586 (#151).
- Templates to copy shape from:
  - `paths/missions_missionId_inner-state-annotations.yaml`: the response map, the parameters `../parameters/MissionId.yaml` and `../parameters/WriteProtocolVersion.yaml`, and the response refs.
  - `schemas/AnnotationRequest.yaml` and `schemas/DurabilityAck.yaml`: request and ack shapes.
  - `schemas/WriteRefusalReason.yaml:41-49`: the `x-provisional` shape.
- Citation rule (`contracts/tools/citation_check.py:3-33`, `:363-382`): every property needs an `x-source{path,symbol}` or an `x-derived{rule,inputs}`. Cited paths must be **core** Python, YAML or JSON paths, or contract fields. **Never cite events' Java**: the checker resolves against a core checkout passed with `--repo-root`.

## Subtasks

### T001 — Repair path

- Create `contracts/mission-write/paths/missions_missionId_status-log-repairs.yaml`:
  - `post`, `operationId: repairStatusLog`, tag `WorkPackages`, parameters `MissionId` and `WriteProtocolVersion`.
  - Request body `../schemas/StatusLogRepairRequest.yaml`, required.
- **Responses:** `200` with `StatusLogRepairAck`, then 400/401/403/404/409/413/422/500/503, reusing the same `../responses/*.yaml` files as the annotations path.
- **Description** (concise, the operator's view). It must say:
  - It is explicit and operator-invoked. The write path never repairs (C-001).
  - It takes the same in-process turn and per-Mission status lock as a write, under the same budget.
  - The actions are `none`/`completed`/`quarantined`, and a healthy log answers `200 action:none` (D1).
  - A coordination-routed Mission answers `422 WRITE_LOCATION_UNVERIFIED` until migration step 4 (ADR-0007 D-1). Name the gap.
  - It must land, and ship in a `service-v*` rc, before core #6195.
- Add the entry to `openapi.yaml`'s `paths:` map, after the inner-state-annotations entry:
  ```yaml
    /missions/{missionId}/status-log-repairs:
      $ref: paths/missions_missionId_status-log-repairs.yaml
  ```

### T002 — Schemas, index, pin, examples

- **`schemas/StatusLogRepairAction.yaml`:** `title: StatusLogRepairAction`, `type: string`, `enum: [none, completed, quarantined]`. Describe each value:
  - `none`: missing, empty, or LF-terminated log; nothing touched.
  - `completed`: the fragment was exactly one row that folds with the log; one LF was appended.
  - `quarantined`: the fragment was moved to the quarantine file and the log truncated to its last LF.

  Give it an `x-derived` with a rule and inputs (cite core `src/specify_cli/status/store.py` symbol `append_raw_rows_atomic` as the heal it replaces).
- **`schemas/StatusLogRepairRequest.yaml`:** `type: object`, `additionalProperties: false`, `required: [context]`, `context: $ref RequestContext.yaml`. Cite with `x-derived`; inputs may be the contract field `TransitionUnit.context`, or core `src/specify_cli/status/write_service_protocol.py` symbol `TransitionUnit.context` (that is what `TransitionUnit.yaml:33-37` cites).
- **`schemas/StatusLogRepairAck.yaml`:** closed. All five properties are required:

| Property | Shape |
|---|---|
| `repaired` | boolean |
| `action` | `$ref: StatusLogRepairAction.yaml` |
| `movedBytes` | integer, minimum 0 |
| `quarantinedFile` | `type: [string, 'null']`, `pattern: '^status\.events\.jsonl\.torn$'`. **Not an enum**: the `enum-case` lint rule rejects dots |
| `readableAfterRepair` | boolean |

  - Each property gets an `x-derived` with contract-field inputs (for example `repaired` is derived from `action`).
  - The description states the invariants: `repaired` is true iff `action` is not `none`; `movedBytes` is greater than 0 only for `quarantined`; `quarantinedFile` is non-null iff `quarantined`; no host path, ever.
- **Index:** add all three to `schemas/_index.yaml`. `layout_check.py` enforces that every file is listed.
- **Pin:** add `"StatusLogRepairAction": ["completed", "none", "quarantined"]` under the mission-write module in `contracts/tools/enum_pins.json`. Match the file's existing key and order conventions; read it first.
- **Examples:** add them under `contracts/mission-write/examples/`, following the naming of `TransitionUnit.claim.yaml`:
  - `StatusLogRepairRequest.yaml`;
  - `StatusLogRepairAck.none.yaml`, `StatusLogRepairAck.completed.yaml` and `StatusLogRepairAck.quarantined.yaml`.

  Reference them from the schemas' `examples:`. Add entries under the existing `mission-write` key of `contracts/tools/fixtures/example_check/required_examples.json`.

### T003 — `TransitionUnit.implementationEvidencePresent`

Add the property to `schemas/TransitionUnit.yaml`, optional (not in `required`):

```yaml
  implementationEvidencePresent:
    type:
      - boolean
      - 'null'
    description: >-
      Whether implementation evidence exists, for the unforced in_progress -> for_review guard.
      Absent or null: the service infers it as core's pipeline does (any earlier transition of the
      work package). true or false: used verbatim. It never affects the subtask half of the guard,
      is ignored for other transitions and under force, and is never persisted (the appended bytes
      and replay are identical with and without it). An older service that does not know the field
      answers 400 REQUEST_MALFORMED (additionalProperties is false), so a client omits it unless
      the caller gave an explicit value (core #6195 documents the rolling upgrade).
    x-source:
      path: src/specify_cli/status/models.py
      symbol: TransitionRequest.implementation_evidence_present
```

Check that core `origin/main` has that symbol (`git -C ~/Documents/code/kitty/spec-kitty show origin/main:src/specify_cli/status/models.py | grep -n implementation_evidence_present`). If the citation checker cannot resolve a dataclass field, use the symbol form it resolves for `StatusEvent.actor` (`RequestContext.yaml`), or an `x-derived` citing `src/specify_cli/status/transition_pipeline.py`.

Optionally add one example, `TransitionUnit.review-handoff-no-evidence.yaml`.

### T004 — Descriptions

- **`schemas/WriteOperation.yaml`:** add `x-provisional` with `open_decision`. Say that the vocabulary follows core's operation doors (`x-derived`), and that a new core door adds a value; the three values are settled, the closure is not. Copy the key shape from `WriteRefusalReason.yaml:41-49`.
- **`schemas/WriteRefusalReason.yaml`:**
  - `workspace_context_required` (:19-20): say that no wire request can produce it. Every wire door supplies or synthesises a context; core's guard is a fail-closed invariant for its own batch API (`transition_pipeline.py:322-337` at core main @ `366ff233`; operator decision 2026-09-07, #946, DRIFT-3), kept so the vocabulary mirrors core.
  - `implementation_evidence_required` (:21-22): both halves are now reachable. The subtask half comes from the roster; the evidence half comes from `implementationEvidencePresent: false`.
- **`schemas/WriteRefusalCode.yaml:49-50` `REQUEST_MALFORMED`:** add the read-back case. Before appending, the service decodes and re-folds the new rows with the log, and refuses a row it could not read back: JSON nesting beyond 256, an integer beyond 4300 digits, an actor object the fold cannot decode, or a unit the fold rejects. That gives `400` with `instance` for a batch unit.
- **`responses/ServiceBusy.yaml` description, `LAUNCHER.md:50-57` "Stop" and `paths/shutdown.yaml`:** add that a write or repair still **waiting** for the turn or the Mission lock when draining begins answers `503 SERVICE_DRAINING` and writes nothing; one already holding the lock finishes. **Do not change any timing** (5 s drain, 10 s SIGTERM; D10).

### T005 — CHANGELOG

In `contracts/mission-write/CHANGELOG.md`, under `## 1.0.1-SNAPSHOT`:

- extend the intro paragraph (:8-18) with one sentence on #236;
- **Added:** the repair path and its three schemas; `TransitionUnit.implementationEvidencePresent`;
- **Changed:** the four description changes (workspace-context reason, evidence reason, `REQUEST_MALFORMED` read-back, drain-aware `SERVICE_DRAINING` with `LAUNCHER.md`);
- **Provisional:** `WriteOperation`. This bullet must name `WriteOperation` (`provisional_check.py`), and must say that `TransitionUnit.operation` leaves `breaking_check` coverage (provisional elements are stripped) while `enum_pins.json` still guards its values.

Follow the file's bullet style. Reference the issues as `(#236; #222)` and so on.

### T006 — Run every contract tool and record the output

Run from the repository root:

```bash
uv run python contracts/tools/install_tools.py            # breaking_check needs oasdiff
B=$(mktemp -d); git archive origin/main contracts | tar -x -C "$B"
uv run python contracts/tools/breaking_check.py --root contracts --module mission-write --baseline-root "$B/contracts"
uv run python contracts/tools/citation_check.py --module mission-write --repo-root ~/Documents/code/kitty/spec-kitty   # core checkout; see note
for t in layout_check structure_check provisional_check enum_pin_check example_check lint_check; do uv run python contracts/tools/$t.py --help >/dev/null 2>&1 && echo "== $t" && uv run python contracts/tools/$t.py --root contracts; done
uv run python contracts/tools/bundle.py --help   # then bundle as CI does
./gradlew :contracts:check   # validate_*, validateBundle_*, clientSmoke_*
```

- **Acceptance for `breaking_check`:** the only findings allowed are `BUNDLE_CHANGED_VERSION_SAME`, and the removal of the `TransitionUnit.operation` request property, which is an artefact of stripping `x-provisional`. Anything else fails this WP, in particular any other `BREAKING_WITHOUT_MAJOR` and any `response-*-added` on an existing path.
- **Core checkout for `citation_check`:** it must be at `origin/main`. Use `git -C ~/Documents/code/kitty/spec-kitty worktree add <tmp> origin/main` (a throwaway worktree, removed afterwards) rather than touching the user's working tree.
- **Record:** check each tool's `--help` for its exact flags; the invocations above are best-known, not verified. Paste every command and its last lines into `kitty-specs/write-service-cutover-readiness-01M70THX/tasks/WP01-contract.tool-run.md`. That one record is out of map and allowed: it is the evidence the orchestrator merges.

## Definition of Done

- **Files:** all new files exist and are indexed. `openapi.yaml` lists 7 paths.
- **Tools:** every tool in T006 passes, or reports only the allowed findings, and the output is recorded.
- **Gradle:** `./gradlew :contracts:check` is green.
- **CHANGELOG:** it lists every addition, and the provisional bullet names `WriteOperation`.
- **Hygiene:** no Java, Python or docs/ file outside `contracts/` is changed, except the two `contracts/tools` JSON files and the tool-run record.
- **Commits:** one or a few commits with clear messages ending in `Co-Authored-By:` (your model).

## Risks and reviewer guidance

- A `breaking_check` run that prints `NO_BASELINE_INITIAL_VERSION` is **vacuous**. The reviewer rejects unless `--baseline-root` was used.
- Response schemas must stay closed. No existing response gains a key.
- Reviewer: open each new schema and check the closedness, the invariants, the citations (core paths only), the examples, and the pin. Diff `LAUNCHER.md` to confirm no number changed.

## Do not

- Run `spec-kitty` lifecycle commands (`move-task`, `mark-status`); the orchestrator does.
- Push, open a PR, or touch `main`.
