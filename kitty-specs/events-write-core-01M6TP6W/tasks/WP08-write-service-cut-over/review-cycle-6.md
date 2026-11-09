---
affected_files: []
cycle_number: 6
mission_slug: events-write-core-01M6TP6W
reproduction_command:
reviewed_at: '2026-11-08T17:16:57Z'
reviewer_agent: claude
wp_id: WP08
---

# WP08 review cycle 6: REJECT (Opus; differential vs core 512567ee)

Cycle-5 items 1–5 are all verified. R20 caught every trigger in 137 positions, nothing escaped, and the floors are exact at 57/38. The digit-set SHA was reproduced. The independent re-classification of 6,521 inputs found 0 fail-open outside the out-of-scope rendering. The plain-subset hunt (699 targeted, 63 boundary and 4,000 fuzz inputs) found four fail-open classes, with 19 end-to-end probes giving Java 201 where core refuses. Probes: `/tmp/claude-1000/wp08-diff/cycle6/review/` (`c6_fo.py` e2e; `gen_plain.py`, `gen_bound.py`, `fuzz_plain.py`, `gen_r20.py`; `harness/Facts2.java`, `harness/Trig.java`, `core2_facts.py`, `run.sh`, `cmp.py`, `classify6.py`; `out/c6_fo.log`; `REVIEW.md`).

## Orchestrator ruling R21 (fail closed; recorded in research §11 decision 18)
- A `:` value indicator inside a flow collection (flow depth > 0) that is not followed by a space or a line break makes the frontmatter unresolvable. This refuses some inputs core reads (`{'a':b}`, `{a:'b'}`); none of the 8,392 real frontmatters uses a flow mapping or a flow-context `:` without a space.
- The reviewer's fail-closed residue (Java refuses, core reads) is recorded as deliberate:
  - a block of just `:`;
  - `<<` as a scalar value;
  - `"\/"`;
  - flow plains `[:x]`, `[a?]`, `[-?]`, `[::]`;
  - complex map keys that differ only in order;
  - map nesting at depth 49 (SnakeYAML's limit is 50).

## MAJOR (must fix; exact fixes, not restrictions)
1. **Duplicate keys under Python equality.** `Frontmatter.java:63`, `:85`. After construction, walk every mapping, including the elements of complex keys. Normalise the keys: `Boolean` → 0/1; `Integer`/`Long`/`BigInteger` → `BigInteger`; an integral finite `Double` → `BigInteger`; `-0.0` == `0`. A collision (Python `==` and hash equality) → Unresolvable. Also check sequence keys element-wise (`? [1]` vs `? [1.0]`). Probes: `a-dep-*` → `dependencies_unsatisfied`; `a-roster-*` → R15. The `8` vs `0o10` pair stays out-of-scope octal residue.
2. **Duplicate `<<` merge keys.** A `MappingNode` with more than one plain key resolved to `Tag.MERGE` (including `? <<`) → Unresolvable; a single `<<` keeps its current behaviour. Probes: `b-dep-two-merges`, `b-roster-two-merges`, `b-roster-hist-two-merges`.
3. **R21 in the token pass.** A `ValueToken` at flow depth > 0 whose next character in the block is not a space, tab or line break → Unresolvable. Track flow depth from the `Flow*StartToken`/`Flow*EndToken` tokens. Probes: `c-dep-flow-root-nospace`, `c-dep-hist-quoted-colon`, `c-roster-hist-quoted-colon`, `c-roster-hist-dq-colon`, `c-roster-hist-map-flowval`, `e-roster-sub-a-colon-done-map`.
4. **Block scalar with a short spaces-only first line.** For a `|` or `>` scalar with no indentation indicator, when the first content line after the header is non-empty, made only of spaces, and shorter than the indentation of the first non-blank line → Unresolvable, matching ruamel's "more indented follow up line than first in a block scalar". `|\n\n \n  x` and `|\n \n x` must still read. Real files use block scalars (`>-`, 14 uses), so do NOT refuse block scalars in general. Probes: `d-dep-blk-leading-blank-less`, `d-roster-blk-leading-blank-less`.

## Tests and corpus
- Add unit tests for each of 1–4: a refusing case and a still-accepted control for each.
- Add protocol corpus cases:
  - a Python-equal duplicate dependencies key on WP02 → `dependencies_unsatisfied`;
  - a short-blank-first-line block scalar in WP01's description on `for_review` → R15.
- Any existing core-oracle case that now contradicts the service moves to `oracle: protocol` (R21 notes).
- Generator `--check` must show drift 0.

## MINOR (process)
6. Your probe classifier must also flag a Java value that differs from core's where core reads (not just core ERR vs Java non-ERR), so a pair like `[a:]` → `"a:"` vs `{a: null}` is caught.

## Out of scope (issues)
- Untagged plain-scalar YAML 1.2 resolver residue: sexagesimal, octal forms, nesting > 50.
- Python `str()` rendering of dates, lists and maps in ids.
- RootCheckout reading only the first `[core]` section, or a quoted `bare`.
