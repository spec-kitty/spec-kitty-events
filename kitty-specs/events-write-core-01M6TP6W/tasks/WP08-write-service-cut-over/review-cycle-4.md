---
affected_files: []
cycle_number: 4
mission_slug: events-write-core-01M6TP6W
reproduction_command:
reviewed_at: '2026-11-08T15:58:37Z'
reviewer_agent: claude
wp_id: WP08
---

# WP08 review cycle 4: REJECT (Opus; differential vs core 512567ee)

All six cycle-3 findings verified. The scanner-token hook is thread-safe and associates tags correctly, and nothing escapes the adapters. But two classes of explicit-tag input still fail open (Java 201, core refuses): `! =` / `! '='` / `!<!> =` (a regression from cycle 3), and yaml.org tags that ruamel builds differently: `!!bool y|n|maybe`, and `!!map|!!seq|!!omap|!!pairs` on a scalar. Through any field of WP02 (`agent: !!bool maybe`) they reach the dependency guard. Probes: `/tmp/claude-1000/wp08-diff/cycle4/review/` (`c4_adv.py` e2e, `adv_cases.py` direct, `core_facts.py`, `REVIEW.md`).

## Orchestrator ruling R19 (ends the tag-semantics chase; fail closed)
**Any explicit YAML tag** (any tag token: `!`, `!!x`, `!<…>`, `!handle!x`, `!custom`, on a scalar or a collection, anywhere in the frontmatter block) **and any `%YAML` or `%TAG` directive** in a work package's frontmatter make that file's facts unresolvable. The dependency declaration then refuses `dependencies_unsatisfied` (as an unreadable declaration does today), and the subtask roster refuses under R15. This is a deliberate fail-closed divergence: core accepts some tagged forms (`shell_pid: !!int 12`, `subtasks: [!!str T001]`, `! foo`, a verbatim `!<…str>`). It is recorded in research §11 decision 18 as R19. Untagged `=` keeps today's rule (implicitly resolved plain `=` → core's TaggedScalar → not a str).

## MAJOR (must fix)
1. Implement R19 in `Frontmatter.java`: detect any tag token or directive. The simplest robust way is a SnakeYAML parse-event walk: any `ScalarEvent`/`CollectionStartEvent` with a non-null tag, or a `DocumentStartEvent` that carries directives (explicit version or tag handles). Any such event → Unresolvable. **Remove the scanner-token hook and the TaggedScalar construction for explicit tags**; they are no longer needed. Keep the untagged-`=` rule. No exception may escape (scanner/parser errors → Unresolvable, as now).
2. Corpus: the core-oracle cases `guard-shell-pid-tagged-int` and `guard-subtasks-roster-tagged-id-complete` now contradict the service by design. Move them out of the generator's core intents and re-add them as `oracle: protocol` cases expecting the R15 refusal, with notes citing R19. Keep the existing R15 protocol cases `guard-subtasks-roster-description-anchor-tagged-value` and `dependency-anchored-tagged-declaration` (still refused). Add protocol cases for `agent: !!bool maybe` on WP02 → `dependencies_unsatisfied`, and for `description: ! '='` → R15. Generator `--check` drift 0.
3. Tests: unit tests for each tag form (scalar, flow and block collection, anchored, aliased, verbatim, non-specific `!`, `%TAG` handle) and both directives → Unresolvable; and the untagged forms still parse as before.

## MINOR (fix in this cycle)
4. `FactValues.java:18,149-153`: `shell_pid` digits must be exactly the set Python 3.11 (Unicode 14) treats as decimal for `int()`. Kawi U+11F51 is valid in Java and invalid in core. Generate the set with the core-pin python (`[c for c in range(0x110000) if chr(c).isdecimal()]`, or whatever `int()` really accepts; check it). Exclude, in Java, the decimal digits added after Unicode 14. Add a test that compares Java's accepted set with a checked-in list of the code points that differ (Java's `Character.isDigit` set minus Python's), so a JDK Unicode upgrade turns the build red.

## Superseded or out of scope
- Review minors 3 (`%YAML 1.1`) and 4 (`!!value`, `!!str [a]`) are covered by R19.
- Out of scope (issues): YAML 1.2 resolver residue for untagged scalars (sexagesimal, octal forms, nesting > 50), Python `str()` rendering of dates, lists and maps in ids, and RootCheckout reading only the first `[core]` / a quoted `bare`. `!!binary`, `!!set` and custom tags on sequences are now covered by R19.
