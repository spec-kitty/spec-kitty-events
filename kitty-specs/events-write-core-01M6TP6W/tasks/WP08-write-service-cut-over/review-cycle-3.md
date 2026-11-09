---
affected_files: []
cycle_number: 3
mission_slug: events-write-core-01M6TP6W
reproduction_command:
reviewed_at: '2026-11-08T15:16:29Z'
reviewer_agent: claude
wp_id: WP08
---

# WP08 review cycle 3: REJECT (Opus; differential vs core 512567ee)

All 8 cycle-2 findings verified (1, 3–8 fully; 2 for its named probes). Probes and outputs: `/tmp/claude-1000/wp08-diff/cycle3/` (`c3_tags.py`, `c3_glob.py`, `gen_cases.py` + `harness/Facts.java` = direct core `WPMetadata` vs `FactValues` differential over 1,803 frontmatters, `gen_cases2.py`, `ruamel_types.py`, `out/`, `REVIEW.md`).

## MAJOR (must fix)
1. **Explicit-tag detection reads the source character, not the composed node.** `Frontmatter.java:85-90` (`buffer[pointer] == '!'` in `RoundTripSafeConstructor`). ruamel 0.19.1 `YAML()` (round-trip) builds a `TaggedScalar` for: a plain `=` resolved implicitly; an explicit `!!str` in shorthand; a `%TAG`-handle or custom (non-yaml.org) tag. It builds ordinary values for `!`, `!!int`, `!!bool`, `!!float`, `!!null` and the verbatim `!<tag:yaml.org,2002:str>` (see `cycle3/ruamel_types.py`).
   - Fail open (core R15 / `dependencies_unsatisfied`, Java 201): an anchor before the tag (the mark points at `&`) and aliases to such nodes — `t-desc-anchor-tagstr` (`description: &a !!str 5`), `t-title-anchor-tagstr`, `t-desc-alias-to-tagstr`, `t-deps-el-anchor-tagstr`, and on the dependency guard `t-dep-anchor-tagstr` (`dependencies: &a !!str WP01`), `t-dep-el-anchor-tagstr` (`[&a !!str WP01]`).
   - Fail closed (core 201, Java refuses): `t-desc-nonspec` (`! foo`), `t-desc-verbatim`, `t-desc-tagnull` (`!!null ''`), `t-pid-tagint` (`shell_pid: !!int 12`), `t-dep-nonspec`, `t-dep-tagnull`, subtask ids rendered as `"TaggedScalar[value=T001]"` (`t-sub-tagstr-done`, `t-sub-tagint-done`, `t-sub-eq-done`).
   - Fix: decide from the composed node (tag + whether it was implicitly resolved), not source text. TaggedScalar only for an implicitly resolved plain `=`, or an explicit `tag:yaml.org,2002:str` written in shorthand, or a non-yaml.org tag; `!`, `!!int`, `!!bool`, `!!float`, `!!null` build native values; verbatim `!<…str>` gives a plain string (mirror it). Anchors/aliases then follow the anchored node. `pythonString` renders a TaggedScalar as its value (core's `str()`).
   - Re-run `gen_cases.py`: the only remaining mismatches may be the out-of-scope groups (custom tags on sequences, `!!set`, `!!binary`, date/list rendering in ids). Report the count.
   - Tests: R15 protocol cases for the anchored `!!str` field and for the anchored dependency (`dependencies_unsatisfied`); core-oracle 201 cases for `shell_pid: !!int 12` and `subtasks: [!!str T001]` with T001 done.

## MINOR (fix in this cycle)
2. `TasksFiles.java:17-20` `exists`: mirror core's `_resolve_wp_file` (`orchestrator_api/wp_lifecycle.py:75`: `exists()` + `<wp_id>-*.md` glob). A dangling symlink `WP04-x.md` alone → core finds the WP; Java 404. (Where core crashes on a directory, fail closed.)
3. `DiskMissionFacts.java:20,33` / `TasksFiles.java:44-50`: catch `UncheckedIOException` from directory-stream iteration in `paths()` → Unresolvable.
4. `FactValues.java:165-175`: non-BMP digits (`shell_pid: '𝟙'`, U+1D7D9) — use code points / `Character.digit(int, 10)` so core's `int()` result (1) is matched.
5. `TasksFiles.java:45`: once exactly one match is found, a non-regular file (FIFO etc.) is Unresolvable instead of being opened (avoid blocking under the Mission lock). Directories/dangling symlinks still count toward ambiguity as now.
6. `Frontmatter.java:28-38`: keep the 16 MiB bound (the orchestrator records it as a deliberate divergence); optionally make the read cheaper (find the closing fence within the first 64 KiB, strict-decode the rest without splitting it into lines). Behaviour must not change.

## Out of scope (issues): YAML 1.2 resolver residue (sexagesimal, octal forms, `!!binary`, `!!set`, custom tags on sequences, nesting > 50), Python `str()` rendering of dates/lists/small floats in ids; RootCheckout first `[core]` only / quoted bare.
