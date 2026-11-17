---
section: Added
---

- A black-box BDD suite for the event contract, first slice (#197, epic #121): Gherkin
  scenarios under `tests/scenarios/<area>/<behaviour>/<behaviour>.feature`, bound by
  pytest-bdd (new in the `dev` dependency group) and run on every PR in `make test-fast` and
  the CI suite. The `envelope/` area (Python binding only) describes the
  `conformance/fixtures/class_taxonomy/` fixtures without copying them: canonical envelopes
  are accepted, invalid ones are refused with the code their fixture pins, an unregistered
  event type is refused, and legacy lanes resolve as pinned. Nothing changes in the package
  or its public API.
  - `scripts/ci/scenario_contract.py` (stdlib only, no JDK) checks the layout, the closed tag
    vocabulary (`@promise:PR-EVENTS-<AREA>-NNN`, unique across the suite; `@wip` with owner
    and start date; the `@jvm` binding tag), that every step binds exactly once, each area's
    `README.md` steps table and its `PROMISES.md` binding declarations. Step definitions are
    read through a registry keyed by binding name; until D3 (#199) or D4 (#270) adds a `jvm`
    source, any `jvm` claim is a `BAD_BINDING_DECLARATION`. Every rule has a broken fixture
    under `tests/fixtures/scenario_contract/`.
  - The class-taxonomy fixture lister and the eight-class envelope verdict are now shared,
    test-only helpers in `tests/support/`, used by the conformance class, determinism and
    strict-journal tests as well as the scenarios.
