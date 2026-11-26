# Event-contract scenarios

Black-box Gherkin scenarios for what the event contract promises every language binding
(epic #121, scope D of #117). They describe the committed fixtures and schemas; they never copy
them. pytest-bdd is the reference binding: it binds every step and runs on every PR, as part
of `make test-fast` and the CI suite. Cucumber-JVM binds the `@jvm` subset on the same feature
files (D3 #199; D4 #270 adds more). Nothing under this folder ships in the wheel.

Run them alone with `uv run pytest --no-cov tests/scenarios`, and the JVM-bound subset with each
runner's `scenarios` task (see [The JVM binding](#the-jvm-binding); JDK 25; all part of
`verifyAll`, so of `make test-gradle`).

## Layout

One folder per area; one folder per behaviour, holding `<behaviour>.feature`:

```text
tests/scenarios/<area>/<behaviour>/<behaviour>.feature
tests/scenarios/<area>/README.md     # index of the area and its available-steps table
tests/scenarios/<area>/PROMISES.md   # promise id -> guarantee, authority and binding
tests/scenarios/<area>/conftest.py   # the area's pytest-bdd step definitions
tests/scenarios/<area>/test_*.py     # binds the area's features (pytest_bdd.scenarios)
```

| Area | Covers | Binding |
|------|--------|---------|
| [`envelope/`](envelope/README.md) | Accepting canonical envelopes, refusing invalid ones with the code their fixture pins, the strict journal profile failing closed on an unknown event type, resolving legacy lanes | python |
| [`ledger/`](ledger/README.md) | Appending to a local JSONL ledger: the exact line an accepted append adds, a refused append leaving the ledger byte-identical, refusing a torn tail and NaN | python |
| [`replay/`](replay/README.md) | Folding lifecycle and decision point replay streams to the reducer output they pin, whatever order their rows arrive in | python |
| [`upcast/`](upcast/README.md) | Reading legacy log rows into the exact CloudEvent bytes their corpus case pins, passing through what cannot be mapped, idempotence, strict use | python, jvm |
| [`compatibility/`](compatibility/README.md) | The 8.0.0 cutover boundary: `validate_event` accepts what the removed gate refused, the strict profile refuses it with the pinned codes | python |
| [`status/`](status/README.md) | Appending a status row as its pinned bytes or refusing it, replaying a status diary to its recorded output, the transition parity corpus and `validate_transition`'s edge graph, the structured status actor | python; `APPEND-003` also jvm, `TRANSITION-007` jvm only |

## Writing a scenario

- **Describe the fixtures, do not copy them.** A step names a fixture class or a manifest entry,
  and the outcome is checked against what that fixture pins (`expected`, `expected_error_code`,
  `expected_error_codes`). A new fixture in a covered class is picked up without editing a
  feature file. No inline envelope tables.
- Steps follow Arrange, Assume, Act, Assert. Reuse an area's available steps before adding one,
  and add every new step to that area's table.
- A shared step text may be bound per area, with an area-specific meaning. When an area reuses
  a wording such as "the class" or "the error code", its `README.md` says what that wording means
  there (for example, in `ledger/` "the class" is the manifest `fixture_type` and "the error
  code" is the `expected_error` refusal class name), so a binder in another language, or a
  later area, does not have to discover it (#281).
- Step-binding helpers stay test-only, in `tests/support/` or the area's `conftest.py`. A new
  public symbol in `spec_kitty_events` needs maintainer approval first (C-007).

## Tags

The vocabulary is closed: any other tag is an `UNKNOWN_TAG` error.

| Tag | Rule |
|-----|------|
| `@promise:<id>` | Required on every scenario. `<id>` matches `PR-EVENTS-<AREA>-NNN`, for example `PR-EVENTS-ENVELOPE-001`, and tags one scenario in the whole suite (an outline counts once). |
| `@wip` | The scenario is a draft. Needs `@wip-owner:<handle>` and `@wip-since:<YYYY-MM-DD>`. It runs as a non-strict xfail and does not gate; its unbound steps are reported as `info`. |
| `@wip-owner:<handle>` | Who owns the draft. `<handle>` matches `[A-Za-z0-9][A-Za-z0-9._-]*`. |
| `@wip-since:<YYYY-MM-DD>` | The date the scenario was marked `@wip` (a valid date). Like `@wip` and `@wip-owner:`, never on an `Examples:` block (`WIP_ON_EXAMPLES`): tag the Scenario Outline instead. |
| `@manual` | A real, specified promise that this repo's CI cannot run automatically: it needs a host, an OS or a peer CI lacks, or an operator-run step. Not for unfinished work (that is `@wip`), and never combined with `@wip`. Needs `@manual-reason:<text>`. It does not gate: pytest-bdd skips it with its reason, its unbound steps are reported as `info`, and its promise still counts in `PROMISES.md` with Binding `manual`. It stays until the environment changes. Never combined with `@jvm` or `@jvm-only` (`MANUAL_WITH_JVM`): a `@manual` scenario runs in no binding. A Feature- or Rule-level `@jvm` has no opt-out, so to make one scenario under it `@manual`, move `@jvm` down to its other scenarios. |
| `@manual-reason:<text>` | Why CI cannot run the `@manual` scenario. Only with `@manual`, and like it never on an `Examples:` block. `<text>` is non-empty and, as a Gherkin tag, has no spaces (for example `@manual-reason:needs-a-macos-host`). |
| `@defect:<issue>` | Links a known defect. `<issue>` is a positive integer. |
| `@jvm` | The binding tag: the scenario is bound by Cucumber-JVM as well, and every step must match exactly one Java step definition. Without a `jvm` step folder it is a `BAD_BINDING_DECLARATION`. |
| `@jvm-only` | The scenario is bound by Cucumber-JVM **alone**: a promise only the JVM services implement (the mirror of an area bound in Python only; operator ruling on #289). Python needs no step for it, pytest-bdd skips it, and its row's Binding is `jvm`. It never combines with `@jvm` (`JVM_ONLY_WITH_JVM`), and like `@jvm` it is a `BAD_BINDING_DECLARATION` without a `jvm` step folder. |

Tags on a `Feature` or `Rule` apply to every scenario under it; tags on an `Examples:` block
apply to that block's rows only. pytest-bdd turns an `Examples:` block's tags into raw pytest
marks without passing them to `conftest.py`, so the tags with a runtime effect (`@wip` and its
owner and date, `@manual` and its reason, `@jvm-only`) are refused there.

Scenario, Feature and Rule tags never become pytest marks: `tests/scenarios/conftest.py` consumes them, so the closed
vocabulary above, enforced by the contract check's `UNKNOWN_TAG` rule, is what stands in for
`--strict-markers`. `tests/test_scenario_contract.py` also runs this tree under
`-W error::pytest.PytestUnknownMarkWarning`.

## The JVM binding

Cucumber-JVM (the version core's `acceptance-jvm/` uses, pinned in `gradle/libs.versions.toml`)
runs through Cucumber's own CLI, once per runner in [`jvm-runners.json`](jvm-runners.json) (#284):

| Runner | Areas | Glue (the step classes) |
|--------|-------|-------------------------|
| `:java-binding` | `upcast/` | `ai.speckitty.events.cloudevents` in `java/src/test/java/` |
| `:event-log` | `status/append/` | `org.speckitty.missionstatus.adapter.out.filesystem.codec` in `service/event-log/src/test/java/` |
| `:command-cli` | `status/transition/` | `org.speckitty.commandcli.domain.authority.scenarios` in `service/command-cli/src/test/java/` |

The build-logic plugin `events.scenarios` gives each runner's project a `scenarios` task (part of
`check`, so `verifyAll` and `service-gate` run it). It reads only its areas' features, in place,
and runs the scenarios tagged `@jvm` or `@jvm-only` and neither `@wip` nor `@manual` against its glue. The task
fails when a scenario fails, a step is undefined, or the number of scenarios that ran differs from
what the tags select: `python3 scripts/ci/scenario_contract.py --jvm-count <project>`, the contract
check's own parser, with outline rows counted one each (#286). The count is derived on every run,
never kept by hand, so parallel PRs that add `@jvm` scenarios cannot race on it;
`-Pscenarios.python=<exe>` names another interpreter. The task drops every `CUCUMBER_*` (and `cucumber.*`) environment
variable before it starts; its `--tags`, feature paths and `--glue` are CLI arguments, which
Cucumber's CLI applies over the environment, system properties and `cucumber.properties`, and the
count check fails any run another setting (such as `cucumber.execution.limit`) narrows. An area belongs to at most
one runner, and a new JVM-bound area gets a runner entry before its first `@jvm` tag. Step code
stays in test sources, never in a service's production sources. A JVM step has the same
text as its Python twin. Cucumber-JVM ignores the keyword when it matches a step, so two Java
definitions with the same text are ambiguous even under different annotations.

## Contract check

`scripts/ci/scenario_contract.py` checks this tree on every PR, without running it and without
a JDK, through `tests/test_scenario_contract.py` and the required `acceptance` check. By hand:
`uv run python scripts/ci/scenario_contract.py`, which reads the `python` steps from this folder and
each runner's `jvm` steps (Cucumber annotations, as source text) from its glue package's folder, as
`jvm-runners.json` names it; a runner's steps serve only its areas, as its Cucumber run does.
`check_tree()` called without step sources reads the same. It fails when:

- a feature is outside `<area>/<behaviour>/<behaviour>.feature`, fails to parse, or has no
  scenarios, or a scenario has no steps;
- a step of a gating scenario matches no step definition, or more than one, in a binding it
  claims (an area's `conftest.py` serves only that area, as in pytest-bdd; an `@jvm` scenario is
  matched against the Java step definitions too), or a scenario's first step is not Given, When
  or Then;
- a gating scenario claims the JVM (`@jvm`, `@jvm-only`) in an area no runner runs
  (`JVM_SCENARIO_NOT_RUN`), or `jvm-runners.json` is malformed, names a glue package with no
  folder, gives two runners overlapping areas, or keeps a runner with no gating JVM scenario
  to run, or names a project `settings.gradle.kts` does not include, `testSources` outside
  that project's directory, or a project whose build file does not apply
  `id("events.scenarios")` (`BAD_RUNNER`); `@jvm-only` tags an `Examples:` block
  (`JVM_ONLY_ON_EXAMPLES`);
- a Java step expression is one Cucumber (cucumber-expressions 17.1.0) refuses, or is not a
  single-line string literal (`UNSUPPORTED_EXPRESSION`);
- a promise id is missing, malformed or duplicated, or a tag is outside the vocabulary;
- a `@wip` scenario lacks its owner or start date, or `@wip`, `@wip-owner:` or `@wip-since:`
  tags an `Examples:` block (`WIP_ON_EXAMPLES`: the row would fail instead of running as an
  xfail);
- a `@manual` scenario lacks a non-empty `@manual-reason:`, or is also `@wip`, `@jvm` or
  `@jvm-only` (`MANUAL_WITH_JVM`); a
  `@manual-reason:` appears without `@manual`; or either tags an `Examples:` block (pytest-bdd
  turns those into raw marks, so the rows would still run);
- an area has no `README.md` steps table, or a row's keyword does not match its Group;
- an area's `PROMISES.md` does not declare `JVM binding: none|partial|full`, gives a promise a
  Binding other than `python`, `python, jvm`, `jvm`, `@wip` or `manual`, claims `jvm` while no `jvm` step source
  is registered, does not index exactly the promises its scenarios carry (once each, with
  well-formed ids), or has binding claims that disagree (`BINDING_MISMATCH`: a row's Binding
  must follow its scenario's `@wip`, `@manual`, `@jvm` and `@jvm-only` tags, and `JVM binding` must be `none`, `partial`
  or `full` as the gating rows claim `jvm`).

Findings point at the offending line (for a tag, the tag's own line) and are sorted by file and
line. Each rule has a broken fixture under `tests/fixtures/scenario_contract/` that proves it can
fail.
