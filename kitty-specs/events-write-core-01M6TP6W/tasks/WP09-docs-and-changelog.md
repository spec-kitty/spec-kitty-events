---
work_package_id: WP09
title: Documentation, contract CHANGELOG pass and changelog fragment
dependencies:
- WP08
- WP10
requirement_refs:
- FR-021
- C-005
- C-007
planning_base_branch: kitty/mission-events-write-core-01M6TP6W
merge_target_branch: main
branch_strategy: Planning artifacts for this mission were generated on kitty/mission-events-write-core-01M6TP6W. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into main unless the human explicitly redirects the landing branch.
subtasks:
- T052
- T053
- T054
- T055
- T056
history:
- timestamp: '2026-11-07T10:30:00Z'
  lane: planned
  agent: system
  action: Prompt generated via /spec-kitty.tasks
agent_profile: scribe-sally
authoritative_surface: docs/architecture/services/
create_intent:
- docs/changelog/unreleased/160-write-core.md
execution_mode: code_change
owned_files:
- service/command-cli/README.md
- service/README.md
- docs/architecture/services/write-service-protocol.md
- docs/architecture/services/event-log-lock-and-append.md
- docs/architecture/services/event-ledger-writers-and-readers.md
- docs/architecture/services/command-cli-research.md
- docs/architecture/services/lock-interop-spike.md
- docs/architecture/services/README.md
- docs/guides/service-protocol-and-releases.md
- docs/changelog/unreleased/160-write-core*.md
- src/spec_kitty_events/conformance/README.md
- contracts/mission-write/CHANGELOG.md
role: implementer
tags: []
tracker_refs: []
---
> **Analyze folds (A4, A5):** WP09 also owns the conformance README, where it documents the `status_append` family, the parity corpus and the public `spec_kitty_events.status_log` module. It owns the contract CHANGELOG's final pass too, which WP03 writes first.

# Work Package Prompt: WP09 - Documentation, contract CHANGELOG pass and changelog fragment

## ⚡ Do This First: Load Agent Profile

Load your assigned profile before reading anything else: `/spk-charter-profile-load scribe-sally`
(or `spec-kitty agent profile show scribe-sally`). Adopt its identity, scope and boundaries.

## Objective

Make the documentation describe the write core **as built** (FR-021, charter directive 2):
the service README, the services architecture pages, the protocol guide, a final pass over the
`mission-write` CHANGELOG, and a changelog fragment. These are code docs: they describe shipped
behaviour and cite files, not the mission's planning artifacts (do not link `kitty-specs/`).

## Context

- Spec: FR-021, C-002 (`statusCommit` null; `coordination_ref_commit` not served until migration
  step 4), C-003 (the protocol-only outcomes), C-004 (what stays client-side), C-005, C-007 (a
  fragment, never a hand edit of `[Unreleased]`), and the operator's flagged defaults (the
  `operation` field, the stale-`from_lane` refusal, the torn-tail recovery follow-up).
- What was built (read the code and the merged WP commits, not this prompt, for the final facts):
  - WP01: `src/spec_kitty_events/status_log.py` (public), the `status_append` fixtures, the
    generator.
  - WP02: `service/event-log/.../adapter/out/statuslog/` (lock path, locked session, torn-tail
    refusal, one `O_APPEND` write plus sync, parent-dir sync, rollback), its ArchUnit rule, the
    native downcall metadata, the status-log interop in `lock-interop`.
  - WP03: the contract delta (`WriteOperation`, `TransitionUnit.operation`, widened descriptions),
    the refusal vocabulary, the typed edge and its row rules.
  - WP04: the parity corpus (`src/spec_kitty_events/conformance/status_transition_parity/`) and its
    generator; WP10: the `write-core-parity` CI job.
  - WP05: the Java encoder and sanitizer.
  - WP06/WP07: `command-cli`'s pure `domain.authority` (fold, machine, guards, dependency verdict,
    claim rules, batches) and the reason ledger (which reasons are unreachable over the wire, and
    why).
  - WP08: `WriteService` and the FR-026 order, the adapters, the acks, the turn and 10 s budget,
    the FR-010 rules, the benchmark numbers, native access and the native locked write.
- Docs conventions: `AGENTS.md` "Documentation" (DocKitty frontmatter: `title`, `description`
  ≤ 180 characters, `doc_status`, `updated`, `type`, `kind`); `docs/changelog/unreleased/README.md`
  (fragment format: `section:` frontmatter, `- ` bullets, links relative to the repository root,
  never `../`, one section per fragment).
- Pages that still say writes are refused fail-closed (grep `STATUS_EVENT_PERSISTENCE` and
  `fail-closed` under `docs/` and `service/`): `docs/architecture/services/write-service-protocol.md`,
  `docs/guides/service-protocol-and-releases.md`, `service/command-cli/README.md`; and
  `docs/architecture/services/event-log-lock-and-append.md:73-97` lists the appender, the lock path
  and native registration as remaining work. `service/README.md:193-205` is the package guide
  that says the write append adapter goes into event-log under its own rule.
- Historical records stay as they are: `docs/changelog/2026-11-01-service-v0-1-0-rc-2.md` and
  other dated changelog files describe what shipped then; do not rewrite them.

## Branch Strategy

Planning happens on `kitty/mission-events-write-core-01M6TP6W` in an owned checkout
(`single_branch` topology). The mission lands as one PR to `main` from `issue-160-write-core`;
the planning base and merge target recorded here are `main`.
`spec-kitty agent action implement WP09 --agent <name> --mission events-write-core-01M6TP6W`

## Ground rules

- Red first for docs means: run the two gates (`cd site && npm run validate`;
  `uv run python scripts/changelog.py check && uv run python scripts/changelog.py guard`) before you
  edit, note their state, and finish with both green.
- Describe what the code does, with file paths; no forward-looking claims beyond the recorded
  follow-ups (migration step 4, core #6195, the torn-tail repair follow-up).
- Bump `updated` in the frontmatter of every page you change (2026-11-07 or the day you land).
- Never hand-edit `## [Unreleased]` in `CHANGELOG.md`.
- Declared out-of-map edit: `contracts/mission-write/CHANGELOG.md` (WP03's file) for the final pass
  (T055).
- Do not run `make test-full`.
- Record the commands you ran and their results in your final commit message.

## Subtasks

### T052 `service/command-cli/README.md`

**Steps**:
1. Replace the "writes refuse fail-closed" text with the write path: the three operations, the
   check order (FR-026, as a numbered list), durability (one write, one sync, parent sync on
   create, rollback), idempotency (byte equality of the encoded row; replay; `EVENT_ID_CONFLICT`;
   `BOOKKEEPING_DOUBLE_EVENT_ID`), the 10 s budget shared by the turn and the Mission lock, the
   `operation` field and when core's client sets it, `statusCommit` null and
   `coordination_ref_commit` refused until migration step 4.
2. A short "binding notes for core's client" section: do not call the service while holding the
   Mission's status lock (every write would answer `STATUS_LOCK_HELD`); set `operation` for the
   lifecycle starts; send a start from `planned` as a batch of two units; the checks that stay
   client-side (C-004).
3. Operations: how to run the benchmark (`:command-cli:benchmarkTest`) and where its numbers land;
   native access (`Enable-Native-Access`, the build arg).

### T053 Architecture pages

**Files**: `docs/architecture/services/write-service-protocol.md`,
`event-log-lock-and-append.md`, `event-ledger-writers-and-readers.md`, `command-cli-research.md`,
`lock-interop-spike.md`, `README.md` (index; only if a description or link changes).

**Steps**:
1. `write-service-protocol.md`: writes are served; the refusal table gains the domain codes with
   their statuses and when each answers (including the protocol-only ones of C-003); the order of
   checks; the acks.
2. `event-log-lock-and-append.md`: the "remaining" list becomes "built": the lock path, the
   appender package and its rule, the native metadata, the status-log interop test in
   `lock-interop`, the torn-tail policy (refused, never repaired; the recovery follow-up).
3. `event-ledger-writers-and-readers.md`: the service is now a status writer beside core's Python
   writers during the strangler; both take the same per-Mission lock; the write fold is separate
   from the read service's `StatusReducer` (C-010).
4. `command-cli-research.md`: an outcome note at the top (what the research led to, with the
   module paths), leaving the research body as written.
5. `lock-interop-spike.md`: one line pointing at the production appender and the new interop test.

### T054 `service/README.md` and the guide

**Steps**:
1. `service/README.md`: the package guide lists event-log's fourth package
   (`adapter.out.statuslog`, its boundary rule) and command-cli's `domain.authority`,
   `application.write`, `adapter.out.mission`, `adapter.out.statuslog`.
2. `docs/guides/service-protocol-and-releases.md`: retire the fail-closed wording; say a release
   that carries this write core is what core #6195 binds to (no tag is cut by this mission;
   operator ruling 3).

### T055 Contract CHANGELOG final pass

**Steps**: read `contracts/mission-write/CHANGELOG.md`'s `1.0.1-SNAPSHOT` section against the
schemas as merged (`git diff main -- contracts/mission-write`): every added schema, field, example
and enum pin and every widened description has one bullet, nothing claims more than the schemas
say, and wording matches the house style. Fix in place (declared out-of-map edit). Run
`uv run pytest tests -k "contract or mission_write" -q` afterwards.

### T056 Changelog fragment and the gates

**Files**: `docs/changelog/unreleased/160-write-core.md` (new); add
`160-write-core-contract.md` only if a second section is needed (one section per fragment).

**Steps**:
1. `section: Added`. Bullets (links relative to the repository root): the write core in
   `command-cli` (durable, byte-exact status appends; the lane authority; the cut-over), closing
   #160, #161 and #155; the public `spec_kitty_events.status_log` module and the `status_append`
   conformance family (a package-line addition); the parity corpus; the `mission-write`
   `1.0.1-SNAPSHOT` additions. Nested bullets for detail.
2. If the contract widening deserves its own `Changed` entry, put it in the second fragment.
3. Gates: `uv run python scripts/changelog.py check && uv run python scripts/changelog.py guard`;
   `cd site && npm run validate` (doc-toolkit frontmatter and links; it excludes the fragments
   folder natively). Fix every finding.

## Test strategy

```bash
cd site && npm run validate
uv run python scripts/changelog.py check && uv run python scripts/changelog.py guard
uv run pytest tests/test_changelog_fragments.py tests/test_docs_service_reference.py -q
uv run pytest tests -k "contract or mission_write" -q
uv run ruff check . && uv run ruff format --check .
```

## Definition of Done

- [ ] No page under `docs/` or `service/` (outside dated changelog records) still says writes are
      refused fail-closed.
- [ ] The README, the architecture pages and the guide describe the shipped write path with file
      paths; frontmatter `updated` bumped; descriptions ≤ 180 characters.
- [ ] The contract CHANGELOG matches the merged schemas.
- [ ] `docs/changelog/unreleased/160-write-core.md` exists and passes `changelog.py check` and
      `guard`; `npm run validate` is green.
- [ ] The final commit message lists the commands and results.

## Risks

- **Describing intent instead of code**: verify each claim against the merged code.
- **Link checker**: a link into `kitty-specs/` or a `../` link in a fragment fails a gate.
- **Description length**: the frontmatter validator refuses more than 180 characters.

## Reviewer guidance

- Grep the tree for `fail-closed` and `STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED` in docs and
  check every remaining hit is intended.
- Read the FR-026 list in the README against `WriteService`.
- Check the fragment's section and link style against `docs/changelog/unreleased/README.md`.
