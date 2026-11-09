---
affected_files: []
cycle_number: 2
mission_slug: events-write-core-01M6TP6W
reproduction_command:
reviewed_at: '2026-11-08T13:35:13Z'
reviewer_agent: claude
wp_id: WP08
---

# WP08 review cycle 2: REJECT (Opus; differential vs core 512567ee)

All 15 cycle-1 findings verified fixed (bite tests for both JaCoCo rules; R16 and lock interop confirmed; codec matches core on 29/34 odd logs, rest deliberate). The fixes introduced five new MAJOR divergences in the disk adapters. Probe scripts and outputs: `/tmp/claude-1000/wp08-diff/cycle2/` (`c2_facts.py`, `c2_extra.py`, `c2_log.py`, `re_p*.py`, `out/`, `REVIEW.md`); probe names below refer to them. Core oracle: core-pin `.venv/bin/python`. Core reads WP frontmatter with ruamel.yaml 0.19.1 `YAML()` (round-trip, YAML 1.2).

## MAJOR (must fix)
1. **Regression: the Mission's own BOM-prefixed `meta.json`.** `FilesystemMissionCatalog.java:84`; `FilesystemMissionCatalogTest.java:52` asserts the wrong behaviour. Core's `resolve_handle_to_read_path` decodes strict UTF-8 (`kernel/meta_decode.py:79-85`); the BOM makes `json.loads` fail → Mission absent. Java strips the BOM → 201. Fix: no BOM strip; an unreadable `meta.json` is skipped (as for a broken sibling). Flip the test; add a service corpus case (`own-meta-bom` → 404 `MISSION_NOT_FOUND`). Probe `re_p2_core_resolve own-meta-bom`.
2. **`WPMetadata` port fails open (Java 201, core R15).** `FactValues.java:111`: `dependencies:` null must make the roster unresolvable (core `dependencies: list[str]` refuses None); the dependency path keeps None = "none declared". `FactValues.java:83`: validate `work_package_title` as `str|None` always, not only when `title` is absent. Also fail-open: `description: =` (ruamel gives a `TaggedScalar`, not str) and `description: !!str 5` — check how core's `WPMetadata` validates these and match. Probes `r-deps-null`, `r-deps-null-empty-roster`, `r-wp-title-int-with-title`, `y-desc-equals`, `r-tagged-str`. Add core corpus cases.
3. **Strict decode of a truncated 64 KiB prefix.** `Frontmatter.java:31` with `:90`. A valid WP file > 64 KiB whose multi-byte char straddles byte 65535/65536 → Java refuses, core 201 (`c-cut-multibyte-roster`, `-at-65536`, `c-cut-multibyte-deps`). Reverse: invalid UTF-8 after 64 KiB → Java 201, core refuses (`r-invalid-utf8-after-64k`). Fix: read the whole file (with a sane overall bound — pick one and justify it, e.g. a few MiB, failing closed beyond) and decode strictly, then apply the 64 KiB cap to the frontmatter block only. Add `DiskMissionFactsTest` cases for both directions (the corpus `readText` path cannot catch this).
4. **Exception escapes the adapter → non-contract 500.** `FactValues.java:73` `Set.of(...).containsAll(keySet)` NPEs on a null key (`~: x`, `null: x`); `DiskMissionFacts.java:31` `map(FactValues::roster)` is outside `Frontmatter.read`'s try. Response is `{"status":500,"title":"Internal Server Error"}` with no code (DoD item 6: never an exception out of the adapter). Fix: `k instanceof String && ALLOWED.contains(k)`; catch `RuntimeException` around the value rules in `DiskMissionFacts` and `CorpusMissionFacts` → Unresolvable. Probes `r-null-key-tilde`, `r-null-key-word`. Add a test.
5. **Non-regular task entries dropped; core counts them.** `TasksFiles.java:45` `filter(Files::isRegularFile)`. Core's `tasks_dir.glob("*.md")` yields directories and dangling symlinks too, so `WP01.md` + dir `WP01-notes.md/` or dangling symlink `WP01-old.md` → ambiguous → core refuses (R15 / `dependencies_unsatisfied`); Java 201. Fix: list names without the regular-file filter (match core's glob, including its hidden-file behaviour); reading a non-file then fails → Unresolvable. Probes `r-dir-named-md`, `r-broken-symlink-md`, `d-dir-named-md`, `d-broken-symlink`. Add tests.

## MINOR (fix in this cycle)
6. [review minor 7] `FactValues.java:120` `shell_pid` coercion: match Python `int(v)` (strips whitespace, accepts `1_000`, bool, truncates float; arbitrary size). Probes `' 12 '`, `12.5`, `true`, `'99999999999'`, `'1_000'`.
7. [review minor 8] Add a corpus variant of `guard-subtasks-roster-nonstring-ids` with `"1"` and `"1.1"` done → core 201 (probes `roster-int-id-done`, `y-subtask-float-done`), so coercion is distinguishable from Unresolvable.
8. [review minor 9] `EventLogRowCodec.java:26-27`: `maxNumberLength(4300)` must not cap floats (`bigfloat-5000`: core 201, Java 500). Keep the integer limit.

## Becomes an issue (do NOT do now)
- YAML 1.2 core-schema resolver residue (sexagesimal, leading-zero / `0o` ints, `!!binary`, custom tags, nesting > 50) and Python `str()` rendering of floats/dates/nested lists in subtask ids.
- `RootCheckout` reads only the first `[core]` section; quoted `bare = "true"`.
