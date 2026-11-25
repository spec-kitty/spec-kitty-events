---
section: Fixed
---

- `spec_kitty_events.diary.reduce_parsed` no longer erases a re-claim's `agent`, `shell_pid` and `shell_pid_created_at` when an earlier `release_runtime_claim` annotation folds (spec-kitty/spec-kitty#7794): claim, release, then a re-claim by another agent used to reduce to `agent=None`, `shell_pid=None`, and now keeps the later claim's triple. A release ordered after the claim by `(at, event_id)`, or with no later claim, clears as before. The write service's core-parity fold (`command-cli`, `AnnotationFold`) makes the same change.
