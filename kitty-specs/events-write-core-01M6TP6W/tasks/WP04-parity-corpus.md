---
work_package_id: WP04
title: Parity corpus and its generator
dependencies: []
requirement_refs:
- FR-012
- FR-015
- FR-023
- NFR-002
- NFR-005
- SC-002
- C-001
- C-003
planning_base_branch: kitty/mission-events-write-core-01M6TP6W
merge_target_branch: main
branch_strategy: Planning artifacts for this mission were generated on kitty/mission-events-write-core-01M6TP6W. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into main unless the human explicitly redirects the landing branch.
subtasks:
- T020
- T021
- T022
- T023
- T024
- T025
history:
- timestamp: '2026-11-07T10:30:00Z'
  lane: planned
  agent: system
  action: Prompt generated via /spec-kitty.tasks
agent_profile: python-pedro
authoritative_surface: src/spec_kitty_events/conformance/status_transition_parity/
create_intent:
- src/spec_kitty_events/conformance/status_transition_parity/README.md
- src/spec_kitty_events/conformance/status_transition_parity/case.schema.json
- src/spec_kitty_events/conformance/status_transition_parity/unreachable.json
- src/spec_kitty_events/conformance/status_transition_parity/cases/
- src/spec_kitty_events/conformance/parity_corpus.py
- src/spec_kitty_events/conformance/test_status_transition_parity.py
- scripts/conformance/generate_transition_parity.py
- tests/test_generate_transition_parity.py
execution_mode: code_change
owned_files:
- src/spec_kitty_events/conformance/status_transition_parity/**
- src/spec_kitty_events/conformance/parity_corpus.py
- src/spec_kitty_events/conformance/test_status_transition_parity.py
- scripts/conformance/generate_transition_parity.py
- tests/test_generate_transition_parity.py
role: implementer
tags: []
tracker_refs: []
---
# Work Package Prompt: WP04 - Parity corpus and its generator

## ⚡ Do This First: Load Agent Profile

Load your assigned profile before reading anything else: `/spk-charter-profile-load python-pedro`
(or `spec-kitty agent profile show python-pedro`). Adopt its identity, scope and boundaries.

## Objective

Build the acceptance oracle of the lane authority (FR-015, NFR-002, SC-002): a committed,
language-neutral corpus of `(prior log, tasks files, request) → expected outcome` cases under the
package's conformance tree, produced by **running core's real entry points at the pin** against
materialized temporary Missions, plus a handful of hand-written `protocol` cases for rules core
has no analogue for. Ship a packaged Python test that validates every case's shape and NFR-002's
coverage counts. The corpus lives **outside** `conformance/fixtures/`, at
`src/spec_kitty_events/conformance/status_transition_parity/` (`README.md`, `case.schema.json`,
`unreachable.json`, `cases/`), so the fixtures manifest test
(`tests/unit/test_fixtures.py:390-405`, every `.json` under `fixtures/` needs a manifest entry) and
the loader are unaffected (orchestrator ruling R1). The CI drift job that re-runs your generator is
WP10's (ruling R2); running the generator against core **locally** is part of this WP's Definition
of Done (ruling R3).

WP06 and WP07 run this corpus in Java (red first: their runners start red on the cases they own).
WP08 runs the `edge` and `service` layer protocol cases over HTTP.

## Context

- Spec: FR-012 (every reason reached by a case or recorded unreachable over the wire, with its
  cause), FR-014 (batches judged unit by unit), FR-015 (the corpus, its fields, the oracle, the
  generator, the CI drift job), FR-023 (stale `from_lane` is a `protocol` case), FR-024 (the
  operation selects core's door), FR-025 (core's message), NFR-002 (≥ 1 refusal per reachable
  reason, ≥ 1 accepted case per legal edge (29), ≥ 5 batch cases with ≥ 2 spanning work
  packages, ≥ 150 cases), NFR-005 (SHA-pinned actions), C-001 (core only in the test-only
  generator and only in CI).
- Plan: "The parity oracle (FR-015)" (binding: the case schema, the generator calling
  `emit_status_transition`, `start_implementation_status`, `start_review_status`, one call per
  unit for batches stopping at the first refusal, exceptions mapped through core's own
  `REASON_CODE` and the source texts of `tests/contract/fixtures/mission_write_refusal_parity.yaml`,
  deterministic ids and timestamps; the Python shape check). The `write-core-parity` CI job is WP10's.
- Research: §1 (the state machine, guards and messages: your coverage map), §1.5 (the claim and
  start rules live in `work_package_lifecycle.py`), §1.6 (dependency declarations come from WP
  frontmatter under `tasks/`), §2 (15 reasons and their producers), §9 (no existing corpus; how
  to run core in CI: `uv sync --frozen`, pack sync), §10 risks 2, 3, 12, §11.1 (replay before
  authority), §11.5 (stale `from_lane`: `TRANSITION_REJECTED`, no URN), §11.13 (run core's real
  entry points; never reimplement core's derivation in the generator).
- Existing seams:
  - `.github/workflows/service.yml:519-537`: how CI installs core at the pin (`uv sync --frozen`,
    pack sync); mirror it locally (T022 step 5). WP10 owns the workflow.
  - `src/spec_kitty_events/conformance/validators.py:500-520`: how packaged code uses
    `jsonschema` (`Draft202012Validator`, from the `conformance` extra) and degrades without it.
  - `pyproject.toml` package data: WP01 owns `pyproject.toml` and adds the globs for this corpus
    (`conformance/status_transition_parity/README.md`, `conformance/status_transition_parity/*.json`,
    `conformance/status_transition_parity/cases/*.json`). Do not edit `pyproject.toml`, `loader.py` or
    `manifest.json` (WP01's); the corpus is loaded by your own `parity_corpus.py`, not through
    the manifest.
- Core at the pin (`512567ee`): `src/specify_cli/status/emit.py` (`emit_status_transition`, the
  done-evidence builder at `:461-489`, dependency readiness at `:420-458`, the tasks-file match at
  `:591-607`), `src/specify_cli/status/transition_pipeline.py` (the derived inputs `:133-177`,
  the unnoted force exit `:222-242`), `src/specify_cli/status/work_package_lifecycle.py`
  (`start_implementation_status`, `start_review_status`), `src/specify_cli/status/write_service_protocol.py:186-224`
  (`REASON_CODE`), `tests/contract/fixtures/mission_write_refusal_parity.yaml`.

## Branch Strategy

Planning happens on `kitty/mission-events-write-core-01M6TP6W` in an owned checkout
(`single_branch` topology). The mission lands as one PR to `main` from `issue-160-write-core`;
the planning base and merge target recorded here are `main`.
`spec-kitty agent action implement WP04 --agent <name> --mission events-write-core-01M6TP6W`

## Ground rules

- ATDD, red first: the packaged shape-and-coverage test (T025) is written first and runs red on
  an empty corpus.
- Typed: `uv run mypy --strict src/spec_kitty_events` clean (`parity_corpus.py` and the packaged
  test are typed). Lint: `uv run ruff check . && uv run ruff format --check .`.
- Cyclomatic complexity ≤ 15 per function (NFR-004): split the generator into materialize, call,
  map and write steps.
- No dependency on core outside `scripts/conformance/generate_transition_parity.py`, which runs only
  under core's interpreter with `SPEC_KITTY_CORE_CHECKOUT` set. No packaged module and no test
  imports core (C-001).
- The generator **calls** core; it never re-implements a guard, a verdict or a claim rule. A
  case's expected outcome is whatever core did.
- Complexity gate for the new Python (NFR-004, ruling R6), no config change:
  `uv run ruff check --select C901 --config 'lint.mccabe.max-complexity = 15' scripts/conformance/generate_transition_parity.py src/spec_kitty_events/conformance/parity_corpus.py src/spec_kitty_events/conformance/test_status_transition_parity.py`.
- You touch no workflow (WP10 owns `service.yml`).
- Run `uv run pytest --pyargs spec_kitty_events.conformance` whenever a fixture changes.
- Do not run `make test-full`.
- Record the test commands you ran and their pass counts in your final commit message.

## Subtasks

### T020 Case schema and corpus README

**Files**: `.../status_transition_parity/case.schema.json` (new, JSON Schema 2020-12),
`.../status_transition_parity/README.md` (new).

**Case fields** (the plan's schema, made precise):
- `id` (kebab-case, unique, equals the file stem); `oracle`: `core` | `protocol`;
- `layer`: `domain` (judged by the authority alone: WP06/WP07), `edge` (refused by
  `WriteBodies`: WP08's HTTP runner) or `service` (needs the replay classification or the acks:
  WP08's HTTP runner);
- `area`: one of `lane_machine`, `guard`, `force`, `done_evidence`, `force_note`, `stale`,
  `unknown_lane`, `dependency`, `claim`, `review_claim`, `start_rejected`, `batch`,
  `annotation`, `fold`, `replay`, `alias`. WP06's runner skips `dependency`, `claim`,
  `review_claim`, `start_rejected`, `batch` (WP07 owns them); keep this list in the README;
- `operation`: `transition` | `start_implementation` | `start_review` | `annotation`;
- `mission`: `{ "directory": "<name>", "mission_id": "<ULID>", "mission_slug": "<slug>" }`
  (fixed values for the whole corpus are fine);
- `log`: the prior lines, each a JSON value (core's rows), in file order; `log_text` (optional
  string) instead, for fold cases that need a blank line, a CRLF line or an unparseable line;
- `tasks`: object, file name (under `tasks/`) → full file text (frontmatter included), so a
  malformed or ambiguous declaration can be expressed;
- `request`: `{ "kind": "unit" | "batch" | "annotation", "units": [ { "wpId", "operation"?,
  "rows": [ ... ] } ] }` (the contract's shapes; the context is implied: `checkout_append`,
  `checkout: "."`, the Mission's directory);
- `expected`: `{ "result": "ack", "units"?: [ {"replayed": bool, "appendedLines": int} ],
  "fsyncs"?: 0|1 }` or `{ "result": "refusal", "code", "reason": <URN>|null, "message":
  <string>|null, "unit": <int>|null }`. `message` is core's exception text verbatim (FR-025's
  "core's message"; whether the service may show it or must use a fixed text is decided by the
  Java side, not here);
- `core_call` (core cases only): the entry point and keyword arguments the generator used, so a
  reviewer can replay it;
- `notes`: one sentence.

**README**: the fields, the layers and areas and which WP's runner consumes each, how to
regenerate, the pinned interpreter, the shared-subset rule for timestamps (ruling R12), and the
**start-from-`planned` exception** (ruling R13): it is the one case where **one core call yields two
units**. `start_implementation_status` on a `planned` work package appends two transitions
(`planned → claimed`, `claimed → in_progress`); the corpus records them as a **batch of two units**,
each with `operation: start_implementation` (FR-027 allows one transition row per unit). Every other
case maps one core call to one unit.

### T021 Generator: materialize temporary Missions and call core

**Files**: `scripts/conformance/generate_transition_parity.py` (new).

**Steps**:
1. Same skeleton as WP01's generator: standard library plus core; `SPEC_KITTY_CORE_CHECKOUT`
   required; refuses any interpreter but the pinned `major.minor` (3.12, matching the CI job);
   core imported lazily inside `main` so the module imports without core (T025's unit tests rely
   on it).
2. Case **inputs** are a table in the script (or a sibling `scripts/conformance/parity_intents/`
   package if the table grows large; keep it in this WP's owned `scripts/conformance/` file set):
   each intent names the setup (the prior core calls that build the log from `genesis`), the
   tasks files, the operation, and the core keyword arguments of the request (wp id, target
   lane, actor, force, reason, evidence, review_ref, review_result, note).
3. For each `core` intent, in a fresh temporary git repository (`git init -q`):
   - write `kitty-specs/<dir>/meta.json` (with `mission_id`), `tasks/*.md`, and run the setup
     calls through core so the prior log lines **are core's rows**; capture them as `log`;
   - call core's entry point for the operation: `emit_status_transition` for `transition`,
     `start_implementation_status` / `start_review_status` for the starts; for a batch, one call
     per unit in order, stopping at the first refusal (record its `unit` index); **exception**: a
     `start_implementation` from `planned` is one core call whose two appended rows become the two
     units of the batch (ruling R13);
   - on success, the request rows are the rows core appended (diff the log before and after);
   - on refusal, build the rows the client would have sent with core's own row model
     (`StatusEvent(...).to_dict()` / the annotation model) from the same arguments, so the Java
     side judges the same row.
4. Read each core function's signature at the pin before calling it; record the exact keyword
   arguments in `core_call`.

### T022 Generator: exception mapping, determinism, `--check`

**Steps**:
1. Map core's exception to `(code, reason, message)`: the exception class through core's
   `REASON_CODE` and orchestrator mapping (research §2: `TransitionError` →
   `TRANSITION_REJECTED`, `WorkPackageClaimConflict` → `WP_ALREADY_CLAIMED`,
   `WorkPackageStartRejected` → `start_rejected`), the reason by matching the message against
   the source texts of `mission_write_refusal_parity.yaml`. An exception that matches no known
   reason is a generator **error** (stop and report), never a guessed URN (research §2 "must not
   be silently assigned a made-up URN").
2. Determinism: `event_id` and `at` are deterministic per case (inject through core's
   parameters where it takes them; otherwise monkeypatch core's id and clock functions for the
   duration of the call, or rewrite after the run and re-run to confirm). Two consecutive runs
   produce byte-identical files.
3. Output: one file per case, `cases/<id>.json`, `json.dumps(indent=2, ensure_ascii=False)`
   plus LF, stable key order. The generator rewrites only `oracle: core` files; it never touches
   `oracle: protocol` files, and a committed `core` file whose intent is gone is drift.
4. `--check`: write nothing; diff every `core` case and the set of `core` ids; print the
   differing ids; exit 1 on drift, 0 otherwise; on success print the case count per `area` and
   the interpreter version.
5. **Mandatory local core run (ruling R3).** Never modify the core clone's working tree; add a
   worktree at the pin into a scratch directory and sync it there:
   ```bash
   CORE_SRC=/home/stijn/Documents/code/kitty/spec-kitty
   CORE=$(mktemp -d)/core-512567ee
   git -C "$CORE_SRC" fetch -q origin 512567eee71e31bb39f580c4f7acf8b865e9d1b2 || true
   git -C "$CORE_SRC" worktree add --detach "$CORE" 512567eee71e31bb39f580c4f7acf8b865e9d1b2
   (cd "$CORE" && uv sync --frozen && PYTHONPATH="$CORE/src" uv run --frozen --no-sync python scripts/sync_packs.py --pack all)
   SPEC_KITTY_CORE_CHECKOUT="$CORE" "$CORE/.venv/bin/python" scripts/conformance/generate_transition_parity.py          # write
   SPEC_KITTY_CORE_CHECKOUT="$CORE" "$CORE/.venv/bin/python" scripts/conformance/generate_transition_parity.py --check  # exit 0
   git -C "$CORE_SRC" worktree remove --force "$CORE"    # when done
   ```
   The `--check` run must exit 0; record its per-area counts and `python --version` of core's venv
   in the final commit message.

### T023 The committed `core` corpus

**Coverage to reach (NFR-002)**, checked by T025:
1. One accepted, unforced case for **each of the 29 legal edges** (research §1.2 table, which
   includes the `genesis` edges and the evidence-gated rework edges).
2. One refusal for each reason core can produce through these doors: `unknown_lane` (a lane
   string core does not know, e.g. `in-review`), `illegal_transition` (`planned → done`;
   leaving `done` unforced; any target `genesis` even forced), `force_requires_actor_and_reason`,
   `actor_required` (a whitespace actor), `dependencies_unsatisfied` (both gated edges; a
   missing, an ambiguous and a malformed declaration; a dependency `canceled` with synthetic
   provenance), `workspace_context_required` (try it; if the single-door default always fills
   the context, record it as unreachable, T025), `implementation_evidence_required` (both
   halves), `reason_required`, `review_ref_required`, `reviewer_approval_required`,
   `review_evidence_invalid` (each `in_review` exit field, `review_ref` mismatch, evidence
   mismatch), `force_note_required`, `done_evidence_incomplete`, `claim_held_by_other_actor`
   (implementation and review re-claim), `start_rejected` (not finalized, wrong lane, force
   misuse).
3. Force: forced moves out of `done` and `canceled`, forced to `uninitialized` and to the same
   lane (code-legal, research §1.4), forced with a blank reason.
4. Dependencies satisfied by `approved`, by `done`, and by `canceled` with operator provenance;
   force with actor and reason bypassing an unsatisfied gate.
5. Claims: generic placeholder actors (`implement-command`, `unknown`, `user`) yielding; the
   implementer of record resuming after rework; a structured actor projected to its tool;
   role-aware review re-claim (holder role `reviewer` versus another role).
6. Batches: ≥ 5 multi-unit batches, ≥ 2 spanning work packages, one where unit 2 is legal only
   after unit 1, one refused at unit 3 (its `unit` index), the two-unit start from `planned`.
7. Fold: annotation deltas (role, agent, subtasks, review) changing a later decision; first-wins
   duplicate ids in the prior log; a log ordering case core's reducer reorders (research §8.2
   risk 3: the clock-ahead approved departure).
8. ≥ 150 cases in total.

### T024 Hand-written `protocol` cases

Commit these by hand (`oracle: protocol`), each with a `notes` line naming the spec row:
1. `stale` (layer `domain`): a unit whose `from_lane` is not the work package's lane in the
   fold → `TRANSITION_REJECTED`, `reason: null` (FR-023, §11.5); one inside a batch, after an
   earlier unit moved the work package.
2. `alias` (layer `edge`): `from_lane: "doing"`, `to_lane: "Planned"` → `REQUEST_MALFORMED`
   (FR-023; WP03's rule); and `to_lane: "in-review"` reaching the authority → `unknown_lane`
   (layer `domain`, may be a `core` case instead).
3. `replay` (layer `service`): an identical replay of a logged unit → ack `replayed: true`,
   `appendedLines: 0`, `fsyncs: 0` for an all-replayed batch; a batch `[replayed, new]` → ack
   with `fsyncs: 1`; a batch `[new, replayed]` → `EVENT_ID_CONFLICT` (FR-008); a unit with one of
   two ids logged → `EVENT_ID_CONFLICT`; the same id twice in one request →
   `BOOKKEEPING_DOUBLE_EVENT_ID`; a retry whose stale `from_lane` would be refused by the
   authority is still a replay (FR-026: replay before authority).
4. `fold` (layer `service`): a torn tail and an unreducible log (invalid JSON line, unknown
   `kind`) → `STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED`; a CRLF log replays (research
   §11.10).
5. **Decision 15 cases** (research §11 decision 15; the ruling block below), layer `domain`, each
   named in `notes`. Where core raises (conflict, rejection) the case is `oracle: core` with the
   would-be row built from core's row model; the zero-event outcome is `oracle: protocol`:
   - `start_implementation` on `claimed` held by an **incompatible** actor, with a row whose
     `from_lane` is also stale → `WP_ALREADY_CLAIMED` / `claim_held_by_other_actor` (the claim
     check beats the stale check);
   - `start_implementation` on a lane the start does not accept (`for_review`, no force note), stale
     `from_lane` → `TRANSITION_REJECTED` / `start_rejected`;
   - `start_review` on `done` → `start_rejected`;
   - `start_implementation` by the **compatible** holder of a work package already `in_progress`
     (core's zero-event no-op), row `claimed → in_progress` → stale refusal (`TRANSITION_REJECTED`,
     `reason: null`, `oracle: protocol`);
   - the review re-claim: a `start_review` unit with row `for_review → in_review` while the fold
     shows `in_review` held by a different actor whose folded role is `reviewer` →
     `WP_ALREADY_CLAIMED`; the same with a holder whose role is not `reviewer` → stale refusal
     (`oracle: protocol`);
   - the two-unit start from `planned` (accepted), and the same batch where unit 1 is accepted and
     unit 2 conflicts because another actor holds the claim in the fold → refusal with `unit: 1`.

### T025 Packaged shape and coverage test

**Files**: `src/spec_kitty_events/conformance/parity_corpus.py` (new, typed),
`src/spec_kitty_events/conformance/test_status_transition_parity.py` (new),
`.../status_transition_parity/unreachable.json` (new), `tests/test_generate_transition_parity.py`
(new).

**Steps**:
1. `parity_corpus.py`: `load_parity_cases() -> list[dict[str, Any]]` (reads `cases/*.json` via
   `importlib.resources`, sorted by id), `load_unreachable() -> dict[str, str]` (reason URN →
   cause), and `coverage(cases) -> ParityCoverage` (a frozen dataclass: accepted unforced edges,
   refused reasons, batch count, cross-WP batch count, total). The 29 legal edges are a constant
   here as a **coverage target only** (cite research §1.2), not a decision table.
2. `test_status_transition_parity.py` (written first): every case validates against
   `case.schema.json` (skip with a clear reason when `jsonschema` is absent, as
   `validators.py` degrades); `id` equals the file stem; ids unique; every reason URN is one of the
   15 and every code is a `WriteRefusalCode` value (read
   `contracts/mission-write/schemas/` only when present; the packaged copy should not depend on
   the repo: carry the 15 URNs and the codes in `parity_corpus.py`); coverage meets NFR-002;
   every one of the 15 reasons is either refused by ≥ 1 case or listed in `unreachable.json`
   with a non-empty cause, never both.
3. `unreachable.json`: start empty; add an entry only when the generator proves core cannot
   reach the reason through the wire (cause in one sentence, e.g. "rows never carry a workspace
   context and the single-door default always fills it"). WP07 confirms or extends it in Java.
4. `tests/test_generate_transition_parity.py`: import the generator by path without core; test
   the exception-to-reason mapping on stub exceptions and source texts, the `--check` diff, and
   that `protocol` files are never rewritten.

## Test strategy

```bash
uv run pytest --pyargs spec_kitty_events.conformance -q
uv run pytest tests/test_generate_transition_parity.py tests/unit/test_fixtures.py -q
uv run mypy --strict src/spec_kitty_events
uv run ruff check . && uv run ruff format --check .
uv run ruff check --select C901 --config 'lint.mccabe.max-complexity = 15' scripts/conformance/generate_transition_parity.py src/spec_kitty_events/conformance/parity_corpus.py src/spec_kitty_events/conformance/test_status_transition_parity.py
# Mandatory (T022 step 5): core worktree at 512567ee, uv sync --frozen in it
SPEC_KITTY_CORE_CHECKOUT="$CORE" "$CORE/.venv/bin/python" scripts/conformance/generate_transition_parity.py --check
```

## Definition of Done

- [ ] `case.schema.json`, the README and ≥ 150 committed cases meeting NFR-002, every `core` case
      produced by the generator, every `protocol` case hand-written with its spec row.
- [ ] `unreachable.json` lists only reasons proven unreachable over the wire, each with a cause.
- [ ] The packaged test validates shape and coverage; mypy --strict and ruff clean.
- [ ] `generate_transition_parity.py --check` was run locally against a core worktree at
      `512567ee` (`uv sync --frozen` in it) and exited 0; the per-area case counts and core's
      Python version are in the final commit message (ruling R3).
- [ ] `tests/unit/test_fixtures.py` stays green (the corpus is outside `fixtures/`).
- [ ] The C901 check (max 15) is clean on the new Python files.
- [ ] The final commit message lists the commands and counts (and the case total per area).

## Risks

- **A second authority**: if you find yourself writing `if lane == ...` in the generator to
  decide an outcome, stop: call core.
- **Nondeterminism**: ULIDs and clocks; two runs must be byte-identical before you commit.
- **Skipping the core run**: a corpus that was never regenerated against core is not an oracle;
  the local `--check` is mandatory, and WP10's CI job repeats it on every PR.

## Reviewer guidance

- Pick three `core` cases and replay their `core_call` by hand against core at the pin.
- Check the reason mapping fails loudly on an unknown message.
- Check the coverage numbers in the packaged test are asserted, and that `unreachable.json` causes
  are real.
- Check the commit message carries the local `--check` result, per-area counts and Python version.
- Check the decision-15 cases of T024 step 5 are all present.

## Orchestrator ruling (2026-11-07): lifecycle operations and the review re-claim

This ruling is binding (research.md §11, decision 15). For `start_implementation` and `start_review`, core's lifecycle logic runs against the current lane in the fold before the stale-`from_lane` check. It gives one of three outcomes:

- an incompatible holder → `WP_ALREADY_CLAIMED` / `claim_held_by_other_actor`;
- a lane the start does not accept → `TRANSITION_REJECTED` / `start_rejected`;
- a compatible holder already in the target lane, which is core's zero-event no-op → stale `from_lane` (`TRANSITION_REJECTED`, no URN, `oracle: protocol`).

A start from `planned` is a batch of two units, both `start_implementation`. A review re-claim is a `start_review` unit with row `for_review → in_review`. A different actor holding `in_review` with role `reviewer` → `WP_ALREADY_CLAIMED`. The corpus pins each of these.
