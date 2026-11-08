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
  - The lane authority ports the lane machine, the guards, dependency gating, the lifecycle
    claim and start rules, and replay/conflict classification. The optional
    `TransitionUnit.operation` selects core's `transition`, `start_implementation` or
    `start_review` path. Lifecycle-start dependency gating, append-order implementer claims,
    operator-note handling and the refusal messages match pinned core, with transition-parity
    coverage.
  - The deliberate divergences from core are documented with their rulings (R15–R31) and
    linked to the core follow-ups spec-kitty/spec-kitty#7439, #7440, #7441, #7442, #7443,
    #7444 and #7445, and to the events follow-ups #222 (torn-tail repair), #223 and #224
    (wire-unreachable guard reasons).
- The public `spec_kitty_events.status_log` module on the package line — `sanitize_status_row`,
  `encode_status_row` and their refusals — together with the `status_append` conformance
  family, so any binding can produce and check the exact status-row bytes (#160).
- The status-transition parity corpus, `conformance/status_transition_parity/`: a
  language-neutral `(prior log, tasks files, request) -> outcome` set generated from core's
  real entry points, with the `write-core-parity` CI job keeping it in drift with the pinned
  core SHA (#155).
