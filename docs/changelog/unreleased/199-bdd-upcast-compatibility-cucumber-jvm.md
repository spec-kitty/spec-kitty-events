---
section: Added
---

- BDD scenarios for two more contract areas, and the first Cucumber-JVM binding of them (#199,
  scope D3 of #117). `tests/scenarios/upcast/` states how a legacy log row is read into a
  CloudEvent (mapped to the exact golden bytes its corpus case pins, passed through with its
  pinned codes, idempotent, refused in strict use with the first pinned code), driven by the
  upcast corpus groups `cli`, `conformance` and `diary`. `tests/scenarios/compatibility/` states
  the 8.0.0 cutover boundary: `validate_event` accepts what the removed gate refused, and the
  strict journal profile refuses it with the codes each `cutover_boundary` fixture pins.
  - Every `upcast/` scenario is tagged `@jvm` and runs twice on the same feature files: under
    pytest-bdd and under Cucumber-JVM 7.20.1 (the version core's `acceptance-jvm/` uses) against
    the Java `Upcaster`. The new `:java-binding:scenarios` task runs it through Cucumber's CLI as
    part of `check`, so of `verifyAll` and the required `service-gate`; `service.yml` now also
    builds when `tests/scenarios/` changes.
  - `scripts/ci/scenario_contract.py` registers a `jvm` step source: it reads Cucumber-JVM step
    annotations from Java source as text (no JDK), so an `@jvm` step without exactly one Java
    definition fails the required `acceptance` check. A binding may now take several step
    folders (`--steps jvm=DIR` repeated), for D4 (#270).
  - The step helpers stay test-only (`tests/support/upcast_corpus.py`,
    `tests/support/cutover_boundary.py`, C-007); no package API changes.
