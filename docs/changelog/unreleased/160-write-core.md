---
section: Added
---

- The write core of the events domain is built in the `command-cli` service (#160, #161, #155):
  a Mission's `status.events.jsonl` is now written durably and byte-exactly under the same
  per-Mission lock core's Python writer takes.
  - `checkout_append` serves the three write operations — one transition unit, a batch of 1 to
    64 units, and a standalone annotation — in one `O_APPEND` write of complete JSON lines plus
    `fsync`, rolled back on a failed write, with a torn tail refused and never repaired.
    `coordination_ref_commit` still answers `422 WRITE_LOCATION_UNVERIFIED` until migration
    step 4, so `statusCommit` is null in the acknowledgements.
  - A row is acknowledged only when core can read it back on both of its read paths: the
    service refuses, as `400 REQUEST_MALFORMED` naming the field and (in a batch) the unit,
    every row shape that `read_events` or `materialize` (the `status.json` path through the
    package's `parse_diary`) raises on at the pinned core SHA, and every row core would skip
    as a non-lane event. So one accepted row never makes a Mission's log unreadable to the
    CLI, to its status snapshot or to the service's own next write. Core's own sort is
    reproduced exactly: a log whose event ids core would compare as a string with a
    non-string is unreducible (`500 STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED`, or
    `400` when the new row causes it). A log that already holds a row core's `materialize`
    refuses on the only guarded shape, the five-key `profile_source` actor, is still written
    to, as core writes to it.
  - The lane authority ports the lane machine, the guards, dependency gating, the lifecycle
    claim and start rules, and replay/conflict classification. The optional
    `TransitionUnit.operation` selects core's `transition`, `start_implementation` or
    `start_review` path. Lifecycle-start dependency gating, append-order implementer claims,
    operator-note handling and the refusal messages match pinned core, with transition-parity
    coverage.
  - The deliberate divergences from core are documented with their rulings (R15–R31) in
    [`service/command-cli/README.md`](https://github.com/spec-kitty/spec-kitty-events/blob/main/service/command-cli/README.md)
    and linked to the core follow-ups spec-kitty/spec-kitty#7439, #7440, #7441, #7442, #7443,
    #7444 and #7445, and to the events follow-ups #222 (torn-tail repair), #223 and #224
    (wire-unreachable guard reasons).
- The public `spec_kitty_events.status_log` module on the package line — `sanitize_status_row`,
  `encode_status_row` and their refusals — together with the `status_append` conformance
  family, so any binding can produce and check the exact status-row bytes (#160).
- The status-transition parity corpus,
  `src/spec_kitty_events/conformance/status_transition_parity/`: a language-neutral
  `(prior log, tasks files, request) -> outcome` set generated from core's real entry points,
  with the `write-core-parity` CI job keeping it free of drift against the pinned core SHA
  (#155).
