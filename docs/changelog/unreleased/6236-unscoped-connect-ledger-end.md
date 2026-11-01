---
section: Fixed
---

- An unscoped `GET /api/v1/events` connect no longer reads every Mission's whole event log
  (spec-kitty/spec-kitty#6236). Each Mission's floor is now its ledger end, read backward from the
  last terminated line, so a connect costs one stat and one short read per Mission instead of a
  full read and decode of its history, and it no longer grows with that history: on the 588-Mission
  corpus, best of four cold connects, 163 ms at 4 MiB of history, 118 ms at 32 MiB and 109 ms at
  128 MiB, against 153 ms, 639 ms and 2251 ms before.
  - The connect holds the shared poller's single lock while it works, so 100 connects over that
    corpus read and decoded about 400 MiB of history while the poll tick waited behind them; they
    now read none of it.
  - Nothing a consumer sees changes: a new connection still delivers none of the history (FR-003,
    AC-3m), and a row written between the last poll and the connect still reaches the subscribers
    that were already there, on the next tick, with the same cursor.
  - The one difference a consumer can observe: a Mission discovered between two listings whose
    ledger cannot be read is reported by the poll tick that catches it up rather than by the
    connect, once, with the same diagnostic.
