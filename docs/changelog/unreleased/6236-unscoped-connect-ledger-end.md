---
section: Fixed
---

- A connect to the Mission Status stream that creates a watch no longer reads that Mission's whole
  event log (spec-kitty/spec-kitty#6236). A new watch starts from the ledger end, read backward from
  the last terminated line, so the cost of a connect to a cold feed no longer grows with history: on
  the 588-Mission corpus, best of four cold connects, 163 ms at 4 MiB of history, 118 ms at 32 MiB
  and 109 ms at 128 MiB, against 153 ms, 639 ms and 2251 ms before.
  - This does not make a connect cheap: it still does per-Mission file I/O (resolving the read
    directory, a stat and a backward scan) under the feed's single lock. A connect onto existing
    watches costs what it did, so the many-connections case that #6236 measured is not expected to
    improve and that issue stays open.
  - A new connection still delivers none of the history, and a fault from before the connect still
    reaches only the subscribers that were already there.
  - One difference a consumer can observe: a ledger that opens but then cannot be read is no longer
    found by the connect that creates its watch. The next poll tick reports it, once, with the same
    diagnostic. An unscoped stream then drops that Mission for the rest of the connection, where a
    connect that skipped it used to retry it at the next listing.
