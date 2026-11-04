---
work_package_id: WP06
title: 'Post-cutover truth pass: architecture, guides, contracts docs, glossary, AGENTS.md, ADR-0005'
dependencies:
- WP04
requirement_refs:
- FR-014
- FR-015
- FR-016
- FR-017
- FR-018
- FR-019
- SC-003
planning_base_branch: issue-122-docs-site-overhaul
merge_target_branch: issue-122-docs-site-overhaul
branch_strategy: Planning artifacts for this mission were generated on issue-122-docs-site-overhaul. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into issue-122-docs-site-overhaul unless the human explicitly redirects the landing branch.
base_branch: kitty/mission-docs-site-overhaul-01M6GBDP
base_commit: b08bf5754e30614ed2c183d0b7cb73b548ea64d1
created_at: '2026-11-03T15:50:13.128959+00:00'
subtasks:
- T029
- T030
- T031
- T031b
- T032
- T033
- T034
- T035
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
- docs/architecture/overview.md
- docs/architecture/services/event-log-lock-and-append.md
- docs/architecture/services/command-cli-research.md
- docs/architecture/services/event-ledger-writers-and-readers.md
- docs/guides/README.md
- docs/guides/service-protocol-and-releases.md
- docs/guides/running-the-service.md
- docs/guides/service-cutover-runbook.md
- contracts/README.md
- contracts/mission-status/CHANGELOG.md
- contracts/mission-write/CHANGELOG.md
- docs/glossary/README.md
- AGENTS.md
- .github/workflows/service.yml
- .github/workflows/service-release.yml
- docs/adr/0005-parallel-landing.md
role: implementer
tags: []
task_type: implement
tracker_refs: []
---

# Work Package Prompt: WP06 – Post-cutover truth pass

## ⚡ Do This First: Load Agent Profile

Use the `/spk-charter-profile-load` skill to load the agent profile specified in the frontmatter (or any user-defined profile), and behave according to its guidance before parsing the rest of this prompt.

- **Profile**: `implementer-ivan`
- **Role**: `implementer`
- **Agent/tool**: `claude`

---

## ⚠️ IMPORTANT: Review Feedback

Check the `review_ref` field (`spec-kitty agent tasks status`); address all feedback; log fixes via `add-history WP06`.

---

## Review Feedback

*[If returned from review, the reviewer feedback reference appears in the status event log.]*

---

## Markdown Formatting

Wrap HTML/XML tags in backticks. Use language identifiers in code blocks.

---

## Objectives & Success Criteria

Closes the truth-pass of #193 and FR-014..FR-019. Ground truth file: `kitty-specs/…/research.md` findings
11–19 — the precise stale lines are enumerated there with dispositions; the subagent survey (session notes,
2026-11-03) recorded current line numbers. Re-grep each one BEFORE editing (files may have moved).

Done means:
- SC-003: zero published pages call the repo package-only or the cutover pending; each listed passage is
  either corrected or explicitly marked historical.
- FR-015: guides tell post-cutover truth; cutover runbook marked executed-kept-as-record; command-cli
  research marked historical.
- FR-016: contracts README + both module changelogs state the ADR-0003 rule; the retired
  `contract-<module>-v*` tag line is gone everywhere in scope (keep the historical record out of scope —
  merged kitty-specs and old changelogs sections stay untouched; only the operative "how releases work now"
  passages change).
- FR-017: glossary defines all six terms.
- FR-018: AGENTS.md documents 0.6.0 behaviour (no 0.5.0 caveat; `--exclude` in the docs commands;
  docs-site trigger paths list; service-v* shipped wording) and gains the multi-issue one-branch-one-PR
  rule; `CLAUDE.md` symlink untouched; same "no longer inert" wording lands in service.yml/service-release.yml
  comments.
- FR-019: ADR-0005 gains a dated amendment note (frontmatter `updated:` bumped; body "**Amended
  <landing date>:** …"); ADR text otherwise immutable (C-007).

## Context & Constraints

- The services shipped: `service/` + `contracts/` imported (ADR-0002), `service-v0.1.0-rc.1` and `-rc.2`
  released (see `docs/changelog/2026-11-01-service-v0-1-0-rc-2.md`); service.yml runs the full Gradle gate.
- Current rc for docs is **0.1.0-rc.2** (running-the-service.md says rc.1 — fix it).
- contracts/: contracts are versioned WITH the service line (contract bundles ride on `service-v*` releases,
  ADR-0003 D4); contracts are at 1.0.0-SNAPSHOT pre-final.

## Branch Strategy

- Planning base / merge target: `issue-122-docs-site-overhaul` (parallel with WP05 after WP04).

Implement with: `spec-kitty agent action implement WP06 --agent claude --mission docs-site-overhaul-01M6GBDP`

## Subtasks & Detailed Guidance

### Subtask T029 – Architecture pages [P]

- `docs/architecture/overview.md` :16 (and description frontmatter): the repo is a contract package AND the
  home of the two services; list the repo parts including `service/` and `contracts/`; link
  `/guides/running-the-service/` and `/api/services/`.
- `docs/architecture/services/event-log-lock-and-append.md` :3 description: drop the "appends atomically"
  overstatement — the truth (per the page body itself): `os.write` on `O_APPEND` + `fsync`, with the
  Windows cross-process caveat. Description like: "How command-cli appends to the CLI-shared event log under
  its lock; the write ordering and the Windows atomicity caveat."
- `docs/architecture/services/command-cli-research.md`: mark historical — frontmatter description prefix
  "(historical — the operator ruled on 2026-10-31; see the outcome block)" and add a one-line banner after
  the H1. Do NOT change `doc_status` values the section schema doesn't allow; ACTIVE + historical banner is
  the accepted pattern per research finding 16 ("marked historical").

### Subtask T030 – Guides truth pass [P]

- `docs/guides/README.md` :18-19: remove "**draft until the services are imported at cutover**".
- `docs/guides/service-protocol-and-releases.md`:
  - :18-23 style intro: "#160/#161 land" language → shipped; "first release cut after #156" → the rc line
    exists (0.1.0-rc.1/2).
  - :101 "Until #160 and #161 land, a write that passes every check is refused" → now-operative truth:
    by design the write gate is fail-closed; a write is refused unless the gate contract holds
    (#160/#161 landed; see the glossary's fail-closed write gate term).
  - :236's rc-era wording — read and correct to current rc (0.1.0-rc.2).
  - ADD the FR-009 flip rule paragraph: "The docs site's API reference pages show `unreleased` until the first
    final `service-v*` tag; flipping means setting `released: true` for both specs in `site/astro.config.mjs`
    in the release PR that cuts that tag."
- `docs/guides/running-the-service.md`: rc.1 examples → rc.2 (substitute `0.1.0-rc.2`); remove
  "until the first service-v* release" phrasing; note the images/binaries published by `service-release.yml`.
- `docs/guides/service-cutover-runbook.md`: banner "**Executed 2026-10-31; kept as the record of the
  cutover.** Do not re-run." and flip the pending tense of the intro paragraph only (steps keep imperative
  voice as a record).

### Subtask T031 – Contracts docs release rule (FR-016) [P]

- `contracts/README.md` :581-585 (the operative release blurb; squad S14): replace the release description
  with ADR-0003's rule — contract bundles are assets of `service-v*` releases; the standalone
  `contract-<module>-v*` line is retired. Keep the semver pinning language for consumers.
  At :675-682 (the preview-namespace paragraph) only the tag-scheme mention gains a "(retired)" qualifier —
  read it first; leave everything else.
- `contracts/mission-status/CHANGELOG.md:9` and `contracts/mission-write/CHANGELOG.md:9`: same correction,
  minimal diff: one or two lines updated, history sections untouched.

### Subtask T031b – Ledger architecture page (squad S5) [P]

- `docs/architecture/services/event-ledger-writers-and-readers.md` :142-144 (operative text, missed by
  research findings 11–19): the sentence "…is `1.0.0-SNAPSHOT` (unreleased until the
  `contract-mission-status-v1.0.0` tag)" → ADR-0003's rule (the bundle ships as an asset of `service-v*`
  releases; the contract tag line is retired); the `services/mission-status-api/` path → this repo's
  `service/` layout (read the real paths under `service/` first). Keep the page's research voice; these are
  surgical corrections, and anything post-cutover about core-repo paths stays framed as history.

### Subtask T032 – Glossary (FR-017) [P]

- `docs/glossary/README.md` — append six entries. The existing style is `### Term` heading + short prose
  (+ an optional "See" link), NOT a table. Terms, written to the spec's Domain Language table and service
  docs: **read service** (mission-status-api…), **write service** (command-cli…), **service-v* line**,
  **launcher**, **endpoint.json** (file that binds a loopback service instance),
  **fail-closed write gate** (writes refuse unless the gate contract holds). Check each definition against
  `service/` sources or the spec glossary; link host pages where useful (`/guides/running-the-service/`).

### Subtask T033 – AGENTS.md (FR-018) + workflow comments

- Replace the AGENTS.md paragraph "The sibling toolkit (0.5.0) cannot exclude a folder yet…" with the 0.6.0
  truth: toolkit 0.6.0 natively excludes `docs/changelog/unreleased/` via `exclude:` in
  `site/astro.config.mjs`, and the local gates skip it via `--exclude`; the CI docs build gains contracts/
  and schema-folder triggers (list the four push paths exactly as docs-site.yml has them); the service jobs
  are no longer "inert until cutover" — state that service.yml builds/tests everything and service-release.yml
  publishes on `service-v*` tags.
- Insert the maintainer-ruled process line into the binding-process bullet list: **"A mission covering several
  issues may land as one branch and one PR that closes them all (the one-issue-one-PR rule relaxes by
  maintainer ruling, recorded in the mission's spec)."** (FR-018; this mission is the first exercise.)
- `.github/workflows/service.yml` and `service-release.yml`: update the inert-until-cutover COMMENTS
  ("the service jobs stay inert until…", "refuses to release before then") AND the now-dead run-block
  strings (squad S15): `service.yml:94` ("service/ is absent (before the cutover import): …") and
  `service-release.yml:54` ("::error::service-v* releases need the cutover import…"). These branches are
  unreachable post-cutover (service/ exists); reword them to drop the cutover frame without changing branch
  behaviour (e.g. "service/ does not exist at $GITHUB_SHA" stays a hard error, minus the import framing).
  BEFORE touching any string, grep `tests/` and `contracts/tools/` for the quoted text and keep every
  pinned-assertion green (if a test pins an exact string, update the test in the same commit).
  Never reorder steps or touch `uses:`/`image:` pins.
- `CLAUDE.md` is a symlink to AGENTS.md — confirm, change nothing.

### Subtask T034 – ADR-0005 amendment (FR-019) [P]

- Edit `docs/adr/0005-parallel-landing.md`: frontmatter description gains "; amended <landing date>: fragment
  stopgap replaced by toolkit exclusion" + `updated:` bump; body adds after the existing amendment banner:
  "**Amended <landing date>:** D1's CI-only `rm -rf` stopgap is gone; doc-kitty 0.6.0 excludes
  `docs/changelog/unreleased/` natively (toolkit ADR-0070) — nothing about fragments' format or the
  collect flow changes." Do not touch D1–D3 bodies.

### Subtask T035 – Verification + stale sweep

- Gates: `uv run pytest --no-cov -q tests/test_ci_gate_workflows.py tests/test_docs_index.py tests/test_changelog_fragments.py tests/test_docs_index.py`,
  conformance, ruff, changelog check+guard, `make lint`; site validate + full build + built-links.
- Sweep proof — NEGATIVE (paste grep output lines into history): `grep -rinE 'package only|not a service|first release cut after #156|draft until|until the services are imported|until #160 and #161 land|contract-mission-(status|write)-v' docs/ contracts/README.md contracts/*/CHANGELOG.md README.md` → only kitty-specs-historical or explicitly-historical hits
  (SC-003); every research finding 11–19 line plus T031b (squad S5) and the :101/:236 family (S16) re-grepped
  and accounted for in the history note.
- Sweep proof — POSITIVE (squad S11): `grep -c '^### ' docs/glossary/README.md` grew by the six FR-017 terms;
  grep the ADR-0003 rule sentence in `contracts/README.md` and both module changelogs; grep the flip rule in
  `docs/guides/service-protocol-and-releases.md` and `docs/api/services.md`.

## Test Strategy

Comment/yaml parse safety is proven by the existing workflow tests; no new tests. Content diffs reviewed
against research.md findings 11–19 line by line.

## Risks & Mitigations

- **Over-editing history**: merged kitty-specs missions (C-003) and past changelog entries stay byte-identical;
  the sweep distinction "operative text vs historical record" is the reviewer's checklist.
- **ADRs immutable except amendments** (C-007): only a banner + dated note.
- **`contracts/**` docs edits trigger docs build now (WP02) and contract tools scans** — keep the edits
  surgical so verify_all (Gradle, in CI) stays green; comments/docs only.

## Review Guidance (for reviewer-renata)

- Every FR-014..019 item maps to a diff; SC-003 sweep output is in history.
- contracts changelogs: ONLY the operative lines changed.
- AGENTS.md commands table still matches the real scripts (`make lint`, `uv run pytest --pyargs …`).

## Progress & Status

Event log only — `add-history`, `move-task`, `status`.
