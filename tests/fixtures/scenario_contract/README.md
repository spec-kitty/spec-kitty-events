# Scenario contract fixtures

Deliberately broken inputs for `scripts/ci/scenario_contract.py`, one per rule, so
`tests/test_scenario_contract.py` can prove every rule fails. Never run as scenarios.

- `steps/`: pytest-bdd step definitions the fixtures bind against (read with `ast`, never imported).
- `steps_unsupported/`: step expressions the reader refuses (`UNSUPPORTED_EXPRESSION`).
- `features/`: one `.feature` per feature-level rule, checked file by file; `valid.feature` is clean.
- `areas/<rule>/`: a features root per area- or tree-level rule; `areas/valid/` is clean.
- `steps_jvm/`: Cucumber-JVM step definitions the `jvm` fixtures bind against (read as text, never
  compiled); the commented-out definitions in it must bind nothing.
- `steps_jvm_unsupported/`: Java step expressions the `jvm` reader refuses (`UNSUPPORTED_EXPRESSION`).
- `areas/jvm_step_missing/`: an `@jvm` scenario with a step the Java steps do not define, checked
  with the real `jvm` reader (`UNBOUND_STEP`); `areas/jvm_bound/` is clean with it.
