---
section: Changed
---

- BDD suite coverage hardening (#121):
  - `PR-EVENTS-LEDGER-002` is now proven over a non-empty ledger for a validation refusal, and
    the packaged `ledger_append` runner checks each document's keys and that a
    `LedgerValidationError` document over a well-formed ledger exists (#279).
  - Every document pinning `LedgerTornTailError` must carry a non-empty `existing_ledger` that
    does not end in LF, checked in the packaged runner and in the `ledger/` selection step (#280).
  - `tests/support/ledger_append.py` types its built input as `persistence.LedgerInput` instead
    of silencing mypy (#282).
  - Each Cucumber-JVM `scenarios` task fails unless Cucumber ran exactly as many scenarios as the
    `@jvm`/`@jvm-only` tags select, asked of `scripts/ci/scenario_contract.py --jvm-count <project>`
    (outline rows counted one each), and drops every `CUCUMBER_*` environment variable before it
    starts. The task now needs `python3`, or `-Pscenarios.python=<exe>` (#286).
  - The `upcast/` area binds the `edge` and `transport` corpus groups, in Python and on the JVM:
    new promises `PR-EVENTS-UPCAST-008` to `010`, and `edge`/`transport` rows for `006` and `007`,
    so the strict-use `ALREADY_CLOUDEVENT` exemption runs; a guard fails if a corpus group goes
    unbound or no strict-use case pins that code (#287).
