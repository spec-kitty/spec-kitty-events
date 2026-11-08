---
affected_files: []
cycle_number: 7
mission_slug: events-write-core-01M6TP6W
reproduction_command:
reviewed_at: '2026-11-08T17:53:56Z'
reviewer_agent: claude
wp_id: WP08
---

# WP08 review cycle 7: REJECT (Opus; differential vs core 512567ee)

Cycle-6 items 2, 3 and 6 are fixed; items 1 and 4 are partial. The independent re-classification of 11,434 inputs found 0 in-scope fail-open, and nothing escapes (0 `EXC` rows across 17,877 inputs). The bounded hunt (6,423 inputs) found three fail-open classes, 9 of them confirmed end to end (Java 201 where core refuses). Probes: `/tmp/claude-1000/wp08-diff/cycle7/rev7/` (generators `gen_t1.py`, `gen_h7.py`, `fuzz7.py`, `gen_t2.py`; end-to-end `c7_fo.py`, `c7_fo2.py`, `c6_fo_rerun.py`; classifiers `classify7.py`, `cat7.py`, `why7.py`; runner `run7.sh` with `harness/` and `jarx/`; `out/*.log`; `REVIEW.md`).

Maintainer cut-off (2026-11-08): only fail-open (Java accepts what core refuses) blocks. Fail-closed residue (Java refuses what core accepts) becomes an issue and does not block.

## MAJOR (must fix; exact fixes)
1. **NaN keys.** `Frontmatter.java:242-245`. ruamel returns ONE shared `nan_value` object for every `.nan` spelling (`constructor.py:480,1128`), so in core every NaN key equals every other NaN key. Repro: `history: [{? [.nan, 1]: a, ? [.nan, 1.0]: b}]` on WP02 → core `dependencies_unsatisfied`, Java 201. The same with `? {k: .nan, j: 1}` vs `? {k: .NaN, j: 1.0}`. Probes: `f-dep-hist-nan-seqkey`, `f-dep-hist-nan-mapkey`, `f-roster-hist-nan-seqkey`. Fix: delete the NaN carve-out; `Objects.equals(Double.NaN, Double.NaN)` is already true. Also confirm two plain `.nan` top-level keys behave as in core (check core: likely a DuplicateKeyError → refuse). Add tests: both repros refuse; `{.nan: a}` alone still reads.
2. **Block-scalar indentation: copy ruamel's rule exactly.** `Frontmatter.java:150-163`, against ruamel's `scan_block_scalar_indentation` (`scanner.py:1299-1322` in the core-pin venv; read it). `first` = length of the first line after the header if it is spaces-only. `max = first`; for each following empty or spaces-only line, `max = max(max, len)`; at the first content line, `max = max(max, leadingSpaces)` and stop; the end of the block also stops. Refuse if `max > first`. Mirror ruamel's exact conditions when the first line is empty, or when an indentation indicator is present (then this rule does not apply). Repro: `description: |\n \n   \nagent: x` (also `>-`, and a blank line at the end of the block). Probes: `g-dep-blk-mid-blank-longer`, `g-dep-blk-mid-blank-longer-folded`, `g-roster-blk-mid-blank-longer`. Tests: `|\n \n   \nx: 1` and `|\n \n   ` at the end of the block refuse; `|\n  \n  \nx: 1` and the existing controls still read. Real files use `>-` block scalars (14 uses), so keep ordinary block scalars readable.
3. **Complex keys core cannot hash.** `Frontmatter.java:188` (`validateNodeTree`). A `SequenceNode` key with any `SequenceNode`/`MappingNode` element, or a `MappingNode` key with any tuple whose VALUE is a `SequenceNode`/`MappingNode` (empty collections included) → Unresolvable. Core raises `unhashable type: 'CommentedSeq'` or `'CommentedMap'`. Still readable in core: `? {[1]: a}`, `? {{a: 1}: b}`, `? []`, `? {}`. Verify against core with the review's `gen_t2.py` shapes (12 shapes × 4 positions: flow, block, top level, merge source). Repro: `history: [{? [[1]]: a}]` on WP02 → core `dependencies_unsatisfied`, Java 201. Probes: `h-dep-hist-seqkey-nested-seq`, `h-dep-hist-mapkey-list-value`, `h-roster-hist-seqkey-map-elem`. Tests: refusals plus readable controls.

## Corpus
Add protocol cases for one NaN-key repro and one block-scalar repro (`dependencies_unsatisfied` / R15). Generator `--check` drift 0.

## Not to fix (fail-closed residue; the orchestrator records it and files an issue)
Timestamp keys equal as Java `Date` but different in Python; `=` vs `'='` inside complex keys; Python-equal keys spread across merge sources or overridden by an explicit key.
