---
affected_files: []
cycle_number: 5
mission_slug: events-write-core-01M6TP6W
reproduction_command:
reviewed_at: '2026-11-08T16:38:23Z'
reviewer_agent: claude
wp_id: WP08
---

# WP08 review cycle 5: REJECT (Opus; differential vs core 512567ee)

R19 is verified for every tagged form: 425 probes, a 4000-input fuzz with nothing thrown, the 946 R19 labels independently confirmed, and every cycle-4 fail-open now matches. Three blockers remain. Probes: `/tmp/claude-1000/wp08-diff/cycle5/` (`c5_fo.py`, `c5_tab.py` e2e; `gen_r19.py`, `cmp_r19.py`, `classify.py`, `fuzz_r19.py`, `JDigits.java`, `harness/Dump.java`; `REVIEW.md`).

## Orchestrator ruling R20 (extends R19 to SnakeYAML 1.1 vs ruamel 1.2 scanner gaps; fail closed)
A work package's frontmatter block is unresolvable (dependencies refuse `dependencies_unsatisfied`, the roster refuses under R15) when it contains any of:
- a tab character (anywhere in the block);
- U+0085, U+2028 or U+2029 (anywhere in the block);
- any directive line or directive token, with or without a following document (this closes review finding 1);
- an anchor or alias token (`&x`, `*x`), whatever follows it;
- an explicit document-end marker `...` or a further document.

Together with R19 (tags), this restricts frontmatter to a plain YAML subset on which SnakeYAML and ruamel agree. None of the 8,392 unique real WP frontmatters on this machine contains any of these: the only regex hits were `*` inside file paths in plain scalars, which are not alias tokens, so implement this with scanner tokens, not regexes. Recorded in research §11 decision 18.

## MAJOR (must fix)
1. Implement R20 in `Frontmatter.java`. Use a character pre-check for tab, U+0085, U+2028 and U+2029, plus a scanner-token pass in the same parse for `DirectiveToken`, `AnchorToken`, `AliasToken`, `TagToken` (R19), `DocumentEndToken` and a second `DocumentStartToken`. Any hit → Unresolvable; scanner/parser errors → Unresolvable; nothing escapes. You may replace the R19 event walk with this token pass if it covers R19 completely; keep every R19 test green. Review finding 1 (a directive with no document, e.g. a frontmatter of just `%YAML 1.2`, or a valid WP01 followed by `...` and `%YAML 1.2`) must refuse.
2. Corpus and tests: unit tests for each R20 trigger (tab in a plain value and before a colon, U+2028 between keys and inside a value, U+0085, a lone directive, a directive after `...`, an anchor without a tag, an alias, `&a:` style, `...`). Any existing **core-oracle** corpus case whose input uses an anchor or alias (e.g. a dependency-form "anchor" case), a tab or a directive, and that core accepts, now contradicts the service by design: move it to `oracle: protocol` expecting the R20 refusal, with notes citing R20, as was done for R19. Add protocol cases for a lone `%YAML 1.2` on WP02 → `dependencies_unsatisfied`, and for a tab in WP01's description on `for_review` → R15. Generator `--check` drift 0.
3. False DoD item: `service/command-cli/src/test/resources/jacoco-class-floor.properties` `domain=54`, but the report has 57 domain classes. Set the exact counts the report shows for every floor after your change.

## MINOR (fix in this cycle)
4. `FactValuesTest.java:20-24`: the digit test filters through the production predicate, so a future JDK adding digits stays green. Assert that the full set of code points `shell_pid` accepts as digits equals Python 3.11's 660 (`int()` = `isdecimal()` = Nd on the core-pin Python). Check the list in (or its count plus a SHA-256 of the sorted code points), generated once with the core-pin Python and documented in the test. Fix the comment.
5. `Frontmatter.java:29`: remove the unused import.

## Out of scope (issues)
Untagged YAML 1.2 resolver residue for plain scalars (sexagesimal, octal forms, nesting > 50); Python `str()` rendering of dates, lists and maps in ids; RootCheckout reading only the first `[core]` / a quoted `bare`. Review minor 6 (U+2028 inside a value, `&x: y`) is covered by R20.
