---
section: Added
---

- An optional `TransitionUnit.implementationEvidencePresent` (boolean or null, #236; #224). For an
  unforced `in_progress` to `for_review` transition, a boolean is used as given, and the guard infers
  only when the field is absent or null. The field is request-only and is never persisted. It does not
  change the subtask half of the guard. See `contracts/mission-write/CHANGELOG.md`.
- Rolling upgrade: `TransitionUnit` is closed, so a service that predates this field answers
  `400 REQUEST_MALFORMED` to a unit that carries it. A client should send the field only to a service
  that serves it (#236; #224; D3).
