---
section: Changed
---
- `contracts/mission-write` moves to `1.0.1-SNAPSHOT`: the coordination write commits onto the
  coordination branch ref, with no coordination worktree (spec-kitty/spec-kitty#7284,
  `docs/adr/0007-write-service-commits-coordination-status-by-ref.md`).
  - `WriteSurface` `coordination_transaction_append` is replaced by `coordination_ref_commit`;
    `checkout_append` names the repository root checkout only.
  - Removed the refusal codes `COORD_STATUS_SURFACE_DIVERGED`, `COORD_STATUS_SURFACE_UNREADABLE`,
    `BOOKKEEPING_WORKTREE_MISSING`, `FALLBACK_COORD_WORKTREE_UNRESOLVED` and
    `COORDINATION_WORKTREE_UNMATERIALIZED`; added `COORDINATION_REF_MOVED`,
    `COORDINATION_REF_CHECKED_OUT` and `COORDINATION_LEDGER_UNREADABLE`.
  - `command-cli` accepts `coordination_ref_commit` as a well-formed surface and refuses the
    retired value as malformed; writes stay fail-closed until migration step 4.
  - Core follows when it moves its pin past `service-v0.1.0-rc.2`.
