---
section: Changed
---

- The write service's guard context carries its real verified root, not a placeholder (#236; #223).
  `workspace_context_required` is documented as unreachable over the wire: core keeps that guard as a
  fail-closed batch-API check (operator decision 2026-09-07, #946, DRIFT-3).
- The evidence half of `implementation_evidence_required` is reachable now, through
  `implementationEvidencePresent: false` (#236; #224).
- A write or repair still waiting for the in-process turn or the Mission lock answers
  `503 SERVICE_DRAINING` and writes nothing once draining starts. A request already holding the lock
  finishes. The 5 s drain and the launcher's 10 s stop are unchanged (#236; #231 item 4.7; D10).
- An unexpected failure on the write or repair path logs one `WARNING` record with the operation, the
  refusal code and the exception class names only, never a message, a path or row content (#236;
  #231 item 4.14).
- `WriteOperation` is provisional: its three values are settled, but whether the vocabulary stays closed
  as core adds operation doors is not (#236; #231 item 4.12).
- Before anything is appended, each new row is read back with the log's codec and the log is folded
  with the next write's fold. A row that cannot be read back, nests JSON beyond 256, carries an integer
  beyond 4300 digits, or has an actor object the fold cannot decode, is refused as
  `400 REQUEST_MALFORMED`, with `instance` naming the unit of a batch (#236; #231 item 4.15).
