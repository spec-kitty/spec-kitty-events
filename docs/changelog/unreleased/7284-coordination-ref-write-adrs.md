---
section: Added
---
- ADR-0007 (Accepted): the write service commits coordination status onto the branch ref by
  compare-and-swap, from git objects, with no coordination worktree; and ADR-0008 (Accepted): the
  read service reads a coordination ledger from git objects, in process
  (`docs/adr/0007-write-service-commits-coordination-status-by-ref.md`,
  `docs/adr/0008-read-service-reads-coordination-ledger-from-git-objects.md`;
  spec-kitty/spec-kitty#7284, epic spec-kitty/spec-kitty#7276).
