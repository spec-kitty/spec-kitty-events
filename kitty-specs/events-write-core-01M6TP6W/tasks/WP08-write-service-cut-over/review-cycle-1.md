---
affected_files: []
cycle_number: 1
mission_slug: events-write-core-01M6TP6W
reproduction_command:
reviewed_at: '2026-11-08T10:19:13Z'
reviewer_agent: claude
wp_id: WP08
---

# WP08 review cycle 1: REJECT (two Opus lenses)

Lens A (spec fidelity) and lens B (independent differential against core at `512567ee` over HTTP; probe harness in `/tmp/claude-1000/wp08-diff/`, `rig.py` + `p*.py`, run with the core-pin `.venv/bin/python`). Both confirm the FR-026 order, lock lifetime, shared deadline, replay, appended bytes, acks, FFM metadata and benchmark. The rejection is for build-gate regressions and core-parity divergences in the disk adapters, several fail-open.

## Orchestrator rulings for this cycle (record in research §11 later; protocol-only outcomes)
- **R15 (roster unresolvable):** when the subtask roster cannot be resolved for an unforced `in_progress -> for_review` (core raises `SubtaskRosterMissingError` / `SubtaskRosterResolutionError` and writes nothing), the service refuses `409 TRANSITION_REJECTED` with reason `implementation_evidence_required` (fail closed, as an unresolvable dependency declaration refuses with `dependencies_unsatisfied`). Corpus cases where core raises are `oracle: protocol`; cases where core answers 409 are core-oracle cases.
- **R16 (coordination-routed Mission):** a `checkout_append` to a Mission whose primary `meta.json` records a non-blank `coordination_branch` answers `422 WRITE_LOCATION_UNVERIFIED` (FR-026 step 4), before any lock. Such Missions are served by `coordination_ref_commit` from migration step 4 (decision 6); core forbids a primary append there and keys its lock differently (`mission_lock_key` includes mid8).
- **R17:** `--separate-git-dir` and submodule repositories keep refusing with 422 (FR-009 intends it). Deliberate divergence; no code change.

## MAJOR (must fix)
1. [A1] `service/command-cli/build.gradle.kts:234-249`: the `domain.*` CLASS BRANCH ≥ 0.80 rule was replaced by the complexity rule. Restore it as its own rule alongside the METHOD rule; fix the "three rules" header near `:200`.
2. [A2] `build.gradle.kts:241-242`: METHOD/COMPLEXITY includes for `WriteBodies` and `AckBody` match no method. Use `"org.speckitty.commandcli.adapter.in.http.operations.WriteBodies*"` and `"...AckBody*"` (check every other include in the rule for the same mistake). Proof: with max 5, the exact name passed; the wildcard failed with 8 violations.
3. [B1] `adapter/out/mission/DiskMissionFacts.java:34-42`: subtask roster fails open (Java 201, core writes nothing) for: no `subtasks:` key; extra frontmatter key (core's `WPMetadata` forbids extras); no `work_package_id`; `subtasks: null` / scalar; duplicate `subtasks` key; `WP01.md` + `WP01-extra.md`; BOM; closing fence `--- `; `subtasks: [1]` / `[1.10]`; a `WP01.md.orig` sibling. Opposite: `subtasks: [" T001 "]` with T001 done — core trims and accepts, Java refuses. Fix: port core's `authored_subtask_roster` (core `src/specify_cli/status/subtask_rows.py`, `_locate_wp_file`, `WPMetadata` in core; read them in the core-pin): locate `*.md` only, `^WPxx(?:[-_.]|\.md$)`, README excluded, zero or several → unresolvable; require the `subtasks` key; validate key set/types as `WPMetadata` does; ids `str(x).strip()`, de-duplicated. `MissionFacts.subtaskRoster` returns resolved-or-unresolvable; the guard refuses when unresolvable (R15). Keep WP06's `CorpusMissionFacts` in agreement (do not fork the rules: reuse or align).
4. [B2, A8] `adapter/out/mission/Frontmatter.java:23-29` vs core's `FrontmatterManager.read`: strip a UTF-8 BOM; universal newlines (`\r\n`, `\r`); opening fence `startswith("---")`, closing fence `rstrip() == "---"`; empty frontmatter `---\n---` is `{}`; `LoaderOptions.setAllowDuplicateKeys(false)` so a duplicate key makes the file unresolvable (today `dependencies: [WP01]` then `dependencies: []` is fail-open 201; core refuses `dependencies_unsatisfied`). Check core's splitter in the core-pin and match it exactly.
5. [B3] `adapter/out/mission/TasksFiles.java:25-35`: count only `*.md` files (case-sensitive, as on POSIX): `WP02.md.orig`, `WP02.md~`, `WP02.MD` must not count.
6. [B4, A3] `adapter/out/mission/FilesystemMissionCatalog.java:38-46` (and :219-226): one bad sibling `meta.json` (`{not json`, or a BOM-prefixed valid one) makes every Mission 404. Catch per directory and skip an unreadable one; read a BOM-prefixed `meta.json` as core does (`utf-8-sig` if core does — check `resolve_handle_to_read_path`). Test at service layer.
7. [B5] `adapter/out/statuslog/EventLogStatusLog.java:25`, `FilesystemMissionCatalog.java:50`: coordination-routed Missions take a different lock than core → implement R16 (422 before the lock). Add a protocol case and an integration test (and a lock-interop note if `lock-interop` has such tests; do not change the lock key for plain Missions).
8. [B6 + B7] `adapter/out/statuslog/EventLogRowCodec.java:35-49`: decode strictly (`CodingErrorAction.REPORT`; invalid UTF-8 → the log is unreducible → 500, nothing written). Split lines exactly as Python's `str.splitlines()` does (`\n`, `\r\n`, `\r`, `\x0b`, `\x0c`, `\x1c`, `\x1d`, `\x1e`, `\x85`, ` `, ` `), and decide blank lines with Python's `str.strip()` whitespace set — check core's `read_events` in `store.py` for the exact reading. Results to match: raw U+2028/U+0085 inside a string → 500; NBSP-only line, form feed after a row, lone CR between rows → accepted as core does. Raise Jackson's `StreamReadConstraints` to Python's limits (nesting ≥ 1000 or core's effective limit; integer length 4300 digits). Keep replay comparison on each line's raw bytes (trailing CR ignored). Add invalid/odd-log fixtures for each.

## MINOR (fix in this cycle; cheap)
9. [A7, B8] `RootCheckout.java:12,41`: bare check — case-insensitive `[core]` and key, git booleans (`true/yes/on/1`, any case). Or `git rev-parse --is-bare-repository` once at startup if a git binary is acceptable (prefer the parser; no new dependency).
10. [B9] `DiskMissionFacts.java:44-55`: strip dependency strings with Python's whitespace set (NBSP etc.).
11. [A4] `ProtocolCorpusTest.java:178-182` silently mutates `replay-new-then-replayed-conflict.json`. Fix the corpus case itself (remove the row so the log matches the intent; keep the generator `--check` green) and delete the special case.
12. [A5] `WriteServiceTest.java:35-102`: add neighbouring double-failure tests (malformed row + `coordination_ref_commit`; unknown WP + duplicate id; location mismatch + unknown WP; surface + unknown Mission) asserting the earlier step answers; count `locationMatches` calls.
13. [A6] `ArchitectureRulesTest`: forbid `application..` → `org.speckitty.missionstatus..`; declare `adapter.out.mission` and `adapter.out.statuslog` as separate onion adapters.
14. [A9] `ProtocolCorpusTest.java:88-91`: assert the `detail` of each invalid `status_append` row over HTTP.
15. [A10] add one `processTest` (boot jar) that posts a legal unit to a Mission-holding repo → 201, and asserts the service output has no "restricted method" warning.

## Corpus cases to add (WP04 corpus, through the generator, `--check` against core green)
`guard-subtasks-roster-key-missing`, `-extra-frontmatter-key`, `-ambiguous-files`, `-nonstring-ids`, `-padded-id-complete`, `-nonmd-sibling`; `dependency-frontmatter-bom`, `-cr-only`, `-fence-trailing-space`, `-empty`, `-duplicate-key`; a coordination-routed Mission service case (protocol); a broken-sibling-meta service case. `DiskMissionFactsParityTest` must pass all of them through the production reader.

## Becomes an issue (not this cycle)
- [B11] `ProtocolCorpusTest` never compares accepted writes' appended bytes with core's `serialize_event_line`.
