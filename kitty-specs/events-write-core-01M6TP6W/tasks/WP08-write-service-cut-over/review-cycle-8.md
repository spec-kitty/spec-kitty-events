---
affected_files: []
cycle_number: 8
mission_slug: events-write-core-01M6TP6W
reproduction_command:
reviewed_at: '2026-11-08T19:22:44Z'
reviewer_agent: claude
wp_id: WP08
---

# WP08 review cycle 8: REJECT (Opus; judged by the maintainer's "why core" principle)

The audit rulings hold. Across 46,757 + 2,912 + 2,000 inputs, the YAML 1.2 resolver, R22–R29 and the deleted ports are verified. The audit markdown reformat changed no meaning. Every DoD item holds; benchmark p95 is 96.7 ms. There are four MAJORs and two MINORs. Review probes: `/tmp/claude-1000/wp08-diff/cycle8/review/`:
- `gen_hunt.py`, `run_static.sh`, `cmp.py`, `myclass.py`, `fuzz_r.py`;
- `h/Facts2.java`, `h/RC.java`;
- `e2e8/c8r.py`, `e2e8/c8r_log.py`;
- `rc/` (git-vs-service config cases and serve logs).

The new rulings are recorded in `research.md` §11 decision 20 (commit `b17242bf`).

## MAJOR (must fix)
1. **`RootCheckout` crashes the service at startup on a valid git config (a regression from cycle 8).**
   - The `SECTION` regex `(?:[^"\\]|\\.)*` at `RootCheckout.java:15` overflows the stack on a long subsection name: `[branch "<2000×a>"]` throws a `StackOverflowError` while the `writeService` bean is built (`review/rc/serve_sub2000.log`).
   - The catch at `:32` only covers `IOException | RuntimeException`.
   - Fix: a linear pattern (possessive `"[^"\\]*+(?:\\.[^"\\]*+)*+"`) or a hand scanner, and never let an `Error` from parsing escape. Add a test with a subsection of about 100 kB.
2. **R29, widened (fail-open: `phase` holding a hex or octal integer past 4300 decimal digits).**
   - Rule: an integer scalar of any base (decimal, `0o`, `0x`) whose decimal form exceeds 4300 digits, anywhere in the frontmatter (any field, nested in lists or maps, keys included), makes the facts unresolvable.
   - Implement it as ONE shared check over the constructed tree, replacing the separate decimal-only and roster-only checks, so `phase` is covered.
   - Repro: `phase: 0x<hex(10**4300)>` on WP01 then `→ for_review` → core R15, service 201. The same with `0o…`, `[0x…]` and `{a: 0x…}`.
   - Add tests and a protocol case.
3. **R30 (fail-open: a timestamp core cannot build).**
   - Rule: a timestamp scalar whose fractional seconds have more than 6 digits makes the facts unresolvable.
   - Repro: `agent: 9999-12-31 23:59:59.9999995` on WP02 → core `dependencies_unsatisfied`; service 201.
   - Implement it in `Yaml12Schema`'s timestamp construction (`:114-133`). Correct the research note's claim that timestamps are "built as strictly as Python's datetime" in your notes.
   - Add tests and a protocol case.
4. **`RootCheckout` must fail closed on config syntax it does not fully understand** (`RootCheckout.java:40-52`). Core asks git, and git refuses these configs or reads them as bare. Treat as unverifiable (→ 422):
   - a value ending in an unescaped `\` (continuation);
   - an unterminated quote;
   - a malformed section header (`[core`);
   - a dotted key outside a section (`core.bare = true`);
   - any other non-blank, non-comment line that is neither a section header nor a `key = value` / bare `key` entry.

   Use the review's `rc/` cases (git `none` vs service `ROOT`) as tests, and keep the 23 previously matching configs matching.

## MINOR (fix)
5. `.5e5` residue: recorded by the orchestrator in decision 20; no code change. Make sure your classifier labels it.
6. Two older protocol cases name no ruling. Update their notes:
   - `own-meta-bom.json` (cite the matched core guard and spec-kitty/spec-kitty#7445);
   - `catalog-broken-sibling-meta.json` (say why it is a protocol case).
