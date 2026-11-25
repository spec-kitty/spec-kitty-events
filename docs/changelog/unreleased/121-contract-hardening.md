---
section: Changed
---

- The BDD scenario contract check (`scripts/ci/scenario_contract.py`) is stricter, closing six
  review minors of #121 in one pull request (#281, #285, #288, #290, #291, #295).
  - Java step expressions are read by a port of cucumber-expressions 17.1.0 (the version the
    Gradle build pins), so the check accepts and refuses what Cucumber does: the `{float}`
    regex is Cucumber's (`2.` and `1e5` no longer match; `1,000.5` does), only whitespace and
    `\ / { } ( )` can be escaped, an alternative made only of optionals is refused, and
    whitespace may stand inside an optional (#285).
  - `check_tree()` called without step sources reads what the CLI reads: `python`, plus the
    JVM runners of `tests/scenarios/jvm-runners.json` for the real tree only. The CLI no longer
    loads the real registry for a `--features` fixture tree. The acceptance no-JDK workflow
    test also forbids `java ` and `sdk` (#288).
  - `@wip`, `@wip-owner:` and `@wip-since:` on an `Examples:` block are refused
    (`WIP_ON_EXAMPLES`): pytest-bdd turns them into raw marks, so the row failed instead of
    running as an xfail (#290). `@manual` with `@jvm` or `@jvm-only` is refused
    (`MANUAL_WITH_JVM`) instead of silently dropping the JVM claim (#291).
  - Each `jvm-runners.json` entry is checked against the Gradle build: its project is included
    in `settings.gradle.kts`, its `testSources` lie in that project's directory, and the
    project's build file applies `id("events.scenarios")` (#295).
  - `tests/scenarios/README.md` states that a shared step wording may be bound per area, with
    the area README saying what it means there (#281).
