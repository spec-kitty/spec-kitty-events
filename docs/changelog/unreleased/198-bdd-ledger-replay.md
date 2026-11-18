---
section: Added
---

- The BDD suite's second slice (#198, epic #121): two new Python-bound scenario areas under
  `tests/scenarios/`, run by the required `acceptance` check with no workflow change. They
  describe the packaged fixtures without copying them, and reuse the `envelope/` step wording
  where it fits. Nothing changes in the package or its public API.
  - `ledger/` holds `persistence.append_event` to the `conformance/fixtures/ledger_append/`
    write contract (#118, #120): an accepted append adds exactly the pinned `expected_line`, a
    refused append leaves the ledger byte-identical (or absent), a torn tail is refused, and
    NaN is refused.
  - `replay/` folds every `lifecycle/replay` and `decisionpoint/replay` stream to the
    `<stem>_output.json` it pins (#119, #120), in file order and with its rows reversed or
    shuffled.
  - The append runner and the stream-to-output pairing are test-only helpers in
    `tests/support/`.
