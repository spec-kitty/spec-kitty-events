---
title: 'ADR-0007: the write service commits coordination status onto the branch ref by compare-and-swap'
description: 'Proposed: the write service commits coordination status straight onto refs/heads/<coord> from git objects with a compare-and-swap ref update; no coordination worktree.'
doc_status: draft
updated: 2026-11-06T00:00:00Z
type: ADR
kind: ADR
authors:
  - human:stijn
related:
  - adr/0002-events-domain-owns-its-services
  - adr/0003-gradle-build-and-service-release-line
  - adr/0008-read-service-reads-coordination-ledger-from-git-objects
---

# ADR-0007: the write service commits coordination status onto the branch ref by compare-and-swap

**Status:** Proposed. Written for slice S9
([spec-kitty/spec-kitty#7284](https://github.com/spec-kitty/spec-kitty/issues/7284)) of the
epic "Lane and worktree model hardening"
([spec-kitty/spec-kitty#7276](https://github.com/spec-kitty/spec-kitty/issues/7276)), on the
operator rulings of 2026-11-06 recorded there. It becomes Accepted when the operator accepts it
with the core records it pairs with (below).

**Date:** 2026-11-06

**Deciders:** operator stijn-dejongh.

**Reader:** anyone building the `command-cli` write path for coordination Missions (migration
step 4 of core ADR 2026-10-22-1), changing the `mission-write` contract, or reviewing how
consolidation and the write service share a coordination branch.

**Technical Story:** [spec-kitty/spec-kitty#7284](https://github.com/spec-kitty/spec-kitty/issues/7284);
the `mission-write` contract change is `1.0.1-SNAPSHOT`
([`contracts/mission-write/CHANGELOG.md`](../../contracts/mission-write/CHANGELOG.md)).

Related records:

- [ADR-0002](./0002-events-domain-owns-its-services.md) and core
  [ADR 2026-10-29-1](https://github.com/spec-kitty/spec-kitty/blob/main/docs/adr/4.x/2026-10-29-1-the-events-domain-and-its-java-services-live-in-spec-kitty-events.md)
  D-1: Java service internals and the `mission-write` contract are decided here; the CLI's
  placement, topology, gates and launcher stay core decisions.
- Core
  [ADR 2026-10-22-1](https://github.com/spec-kitty/spec-kitty/blob/main/docs/adr/4.x/2026-10-22-1-the-java-write-service-is-the-canonical-event-writer.md)
  (the write service is the canonical writer; D-4 one unit of work; D-6 storage is a port; D-7
  byte-exact rows; migration step 4) and
  [ADR 2026-10-27-1](https://github.com/spec-kitty/spec-kitty/blob/main/docs/adr/4.x/2026-10-27-1-the-write-service-protocol-is-loopback-http-with-a-bearer-token.md)
  (the protocol; a closed-response change after `1.0.0` is a major).
- The core records of the same slice, landing in the Mission
  `lane-worktree-model-hardening-01M6QY1M` core pull request: **C-1** (a coordination branch
  is written by commit, not through a coordination worktree), **C-2** (consolidation gates
  over a worktree-less coordination branch), **C-3** (the committed ledger of a coordination
  Mission is the blob on its coordination branch) and the **D-6 amendment** of 2026-10-22-1
  that names this set. This record does not restate them.
- [ADR-0008](./0008-read-service-reads-coordination-ledger-from-git-objects.md): the read side
  of the same change.

## Context and Problem Statement

A coordination Mission (topology `coord` or `lanes_with_coord`) records its status on a
coordination branch. Today the CLI writes it through a **coordination worktree**: a checkout of
that branch under `.worktrees/<mission>-coord/`. `BookkeepingTransaction`
(`src/specify_cli/coordination/transaction.py` in core) appends to the event log file in that
checkout, commits it with `safe_commit`, and cuts the append when the commit fails. The checkout
brings its own failure modes, each of which became a refusal code: the working-tree log can
diverge from what `HEAD` committed (`COORD_STATUS_SURFACE_DIVERGED`), it can be unreadable
(`COORD_STATUS_SURFACE_UNREADABLE`), and the worktree can be missing or not materializable
(`BOOKKEEPING_WORKTREE_MISSING`, `FALLBACK_COORD_WORKTREE_UNRESOLVED`,
`COORDINATION_WORKTREE_UNMATERIALIZED`). The late projection window of consolidation reads the
same working tree (core #7198).

The operator ruled on 2026-11-06 (#7276): the coordination branch stays the lifecycle home; the
resolved status surface is the only source of truth; lane branches carry no status copies; and
the Java write service writes coordination status **without a coordination worktree**.
Implementation follows migration step 4 of 2026-10-22-1 (write-service migration #6117).

The `mission-write` contract, still a snapshot, encoded the worktree: `WriteSurface`'s
`coordination_transaction_append` is "an append inside the coordination transaction, in the
coordination worktree", and five refusal codes presuppose one. After `1.0.0` a change to them is a
major version. This record decides how the service commits instead, and changes the contract
before `1.0.0`.

## Decision Drivers

- One source of truth: the committed tree of the coordination branch. A working tree is a
  second copy that can diverge.
- The unit of work of 2026-10-22-1 D-4: append, commit and cut-on-failure are one step inside
  the service; a failure leaves nothing behind.
- Byte-exact rows (2026-10-22-1 D-7) and durability before the acknowledgement (2026-10-27-1).
- The service is the one writer, but the CLI (during the strangler), consolidation and the user
  can still move the same ref: a lost update must be impossible, not unlikely.
- Interoperability with the git the CLI and the user run: the same ref locks, ref backends and
  reflog.

## Considered Options

1. **Commit from git objects with a compare-and-swap ref update** (plumbing; chosen).
2. **Keep a coordination worktree owned by the service** (the service appends and commits in a
   checkout it materializes itself).
3. **A per-unit throwaway worktree** (`git worktree add` at the tip, commit, remove).

## Decision Outcome

Chosen option 1. Each decision below is marked *(ruled)* where it restates an operator ruling and
*(derived)* where this record derives it.

### D-1: no coordination worktree; the commit is built from git objects *(ruled)*

For `writeSurface: coordination_ref_commit`, the service writes a unit like this, and touches no
index, working tree or worktree of the repository:

1. Read the tip `T` of `refs/heads/<coord>` (`destinationRef`).
2. Read the Mission's event log blob at `<missionDirectory>/status.events.jsonl` in `T`'s tree;
   an absent path is an empty log.
3. Write the new blob: the old bytes followed by the unit's rows, as one
   `git hash-object -w --no-filters --stdin` (no clean or `eol` filter, so the bytes are exact).
4. Build the new tree from `T`'s tree with only that path changed, through a temporary index
   (`GIT_INDEX_FILE` in a private directory of the git common dir: `read-tree T`,
   `update-index --cacheinfo 100644,<blob>,<path>`, `write-tree`). The repository's own index
   is never read or written.
5. `commit-tree <tree> -p T` with the message of D-6.
6. `update-ref -m <message> refs/heads/<coord> <new> T`: the compare-and-swap (D-3). It writes a
   reflog entry. No hook runs at any step.

A batch of units (`/work-package-transitions/batch`) is **one** commit, as it is one write and
one `fsync` today. *(derived)*

### D-2: byte and durability contract *(derived)*

- The appended bytes are exactly the rows of 2026-10-22-1 D-7 as amended by 2026-10-27-1 D-5:
  full JSON lines, nothing re-encoded.
- An old blob that is not empty and does not end in `\n` (a torn tail) is not appended to: the
  unit is refused with `COORDINATION_LEDGER_UNREADABLE` (500), as is a blob that cannot be read
  or parsed.
- "One `O_APPEND` write and `fsync`" becomes, for this surface: every object and the moved ref
  are durable before the acknowledgement. The service runs git with
  `-c core.fsync=objects,reference` (and `core.fsyncMethod=fsync`), so the loose objects and the
  ref file are synced. `DurabilityAck.fsynced` keeps its meaning (durable before the answer).
- `DurabilityAck.statusCommit` names the commit `update-ref` moved the ref to. The field stays
  `x-provisional` until migration step 4 ships, because until then no unit commits.

### D-3: a lost compare-and-swap is a refusal, never a retry *(derived)*

- The service serializes its own writes per repository in process (2026-10-22-1 D-4), so when
  `update-ref <new> <T>` finds the ref no longer at `T`, another writer moved it: the CLI during
  the strangler, consolidation, or the user. The unit is refused with
  **`COORDINATION_REF_MOVED`** (409) and nothing is acknowledged. The objects written in steps
  3 to 5 are unreachable and left to `git gc`.
- No blind retry: a retry would append on top of a tip the caller has not seen, and would hide a
  concurrent consolidation. The caller re-reads and decides. Whether consolidation fences writes
  out (a refusal while a consolidation record holds the Mission) is core **C-2**'s decision; the
  compare-and-swap is the service's half of that exclusion either way.
- A coordination branch that is checked out in any worktree of the repository (the root
  checkout's `HEAD` or a `git worktree list` entry) is not written: moving a checked-out ref
  makes that checkout look dirty and invites a lost update on its next commit. The unit is
  refused with **`COORDINATION_REF_CHECKED_OUT`** (409). This is how an existing coordination
  worktree is kept out of the way until core **C-1**'s migration drains and removes it.

### D-4: placement checks run in the service *(derived)*

- `destinationRef` is required for this surface (enforced by `RequestContext`'s schema). It must
  be the `coordination_branch` the Mission records; while write-location resolution is still the
  CLI's (2026-10-22-1 open question 2), a mismatch is `WRITE_LOCATION_UNVERIFIED`.
- The existing ref checks apply unchanged: `DESTINATION_REF_INVALID_SHAPE`,
  `DESTINATION_REF_NOT_LOCAL`, `PROTECTED_BRANCH_REFUSED`, and `COORDINATION_BRANCH_DELETED`
  when the ref does not exist.
- The service stamps `policy_metadata.lane_head` from `refs/heads/<lane>` in the command form
  (2026-10-27-1 D-3 at step 4); the stamp's meaning is core **C-2**'s, unchanged.

### D-5: git is a subprocess of the service through its `GitPort` *(derived)*

- The write service drives `git` plumbing as a subprocess through the `GitPort` that
  2026-10-22-1 D-4 already places in the service. It does not embed JGit for writes: the CLI and
  the user run C git, and only C git is guaranteed to share their ref locks, ref backends
  (`files`, packed refs, `reftable`), reflog and `core.fsync` behaviour. A pure-Java writer would
  be a second implementation of ref transactions.
- Minimum git: the core's floor (2.38, already required for `merge-tree --write-tree`), which
  also has `core.fsync` (2.36).
- Windows ref-lock semantics follow the lock-interop spike
  ([lock-interop-spike.md](../architecture/services/lock-interop-spike.md), #6193).

### D-6: commit identity and message *(derived)*

- Author and committer are the repository's configured git identity, as `safe_commit` uses
  today; the actor of the rows is in a `Spec-Kitty-Actor:` trailer.
- The message names the Mission and the work packages of the unit, for example
  `status(<mission-slug>): WP01 planned -> claimed`, followed by one `Spec-Kitty-Event-Id:`
  trailer per row.
- Signing follows core's signing policy (DIRECTIVE_029 and the signing toolguide); until that
  policy names status commits, they are unsigned, as plumbing commits are by default.

### D-7: the contract changes before `mission-write` `1.0.0` *(ruled: version)*

The operator ruled on 2026-11-06 that `1.0.0-SNAPSHOT`, which shipped inside the
`service-v0.1.0-rc.1` and `rc.2` pre-releases, is not rewritten: the change opens
`1.0.1-SNAPSHOT`, and a new release candidate can carry it.

- `WriteSurface`: `coordination_transaction_append` is replaced by `coordination_ref_commit`;
  `checkout_append` names the repository root checkout only, since a lane worktree is never a
  status surface (ruling: lane branches carry no status copies).
- `RequestContext`: `checkout` names the caller; for `coordination_ref_commit`,
  `missionDirectory` is the path in the tree of `destinationRef`, and `destinationRef` is a
  required non-empty string.
- Removed codes: `COORD_STATUS_SURFACE_DIVERGED`, `COORD_STATUS_SURFACE_UNREADABLE`,
  `BOOKKEEPING_WORKTREE_MISSING`, `FALLBACK_COORD_WORKTREE_UNRESOLVED`,
  `COORDINATION_WORKTREE_UNMATERIALIZED`. With no working tree, divergence cannot occur and there
  is nothing to materialize.
- Added protocol-only codes: `COORDINATION_REF_MOVED` (409), `COORDINATION_REF_CHECKED_OUT`
  (409), `COORDINATION_LEDGER_UNREADABLE` (500).

### Scope

This record covers the coordination **status** event log, which is what the rulings name. The
other things the coordination transaction writes today (notes, trace, issue matrix, decision
artefacts, subprocess byproducts) need their own rule before the coordination worktree can be
retired; that is core **C-1**'s decision (an artefact-write operation of the service, or a
partition move, #7266). Whether `status.json` is committed on the coordination branch is also
C-1's. Nothing here builds the service: implementation follows migration step 4 (#6117).

## Consequences

### Positive

- The divergence and materialization failures disappear by construction, with their five codes.
- A unit is atomic: the ref moves to a commit that holds all its rows, or it does not move.
- Concurrent movers of the coordination branch are detected, never overwritten.
- The service never needs a checkout, so one service serves every worktree without touching any.

### Negative

- Plumbing runs no hooks: a repository hook on the coordination branch no longer sees status
  commits.
- Building the tree through a temporary index reads the whole tree of `T`; for very large trees a
  path-rewrite of the nested trees (`mktree`) is cheaper and may replace it without changing
  this record.
- A refused `COORDINATION_REF_MOVED` puts the retry decision on the caller.

### Neutral

- Unreachable objects of a refused unit wait for `git gc`.

## Confirmation

When the step-4 implementation lands, `command-cli` tests show, in a temporary repository:

- a unit and a batch each produce exactly one commit whose parent is the prior tip, whose tree
  differs only at the Mission's event log, and whose blob equals the prior bytes plus the rows
  byte for byte (the `status_append` fixture family);
- the repository's index, `HEAD` and working tree are unchanged, and no hook ran;
- a ref moved between read and update gives `COORDINATION_REF_MOVED` and leaves the ref where the
  other writer put it; a checked-out coordination branch gives `COORDINATION_REF_CHECKED_OUT`; a
  torn or unreadable log gives `COORDINATION_LEDGER_UNREADABLE`;
- every refusal body validates against `WriteRefusal`.

Today, `OperationsTest` checks that `coordination_ref_commit` is well-formed, that the retired
`coordination_transaction_append` is malformed, and that the service's surfaces equal the
contract's; `enum_pins.json` pins the new `WriteSurface`.

## Pros and Cons of the Options

### Commit from git objects with compare-and-swap (chosen)

- Good: one source of truth; atomic; lost updates detected; no checkout lifecycle.
- Bad: no hooks; a subprocess per git step.

### A coordination worktree owned by the service

- Good: closest to today's code.
- Bad: keeps the second copy and every failure mode the rulings retire; the service would own a
  checkout's lifecycle.

### A per-unit throwaway worktree

- Good: plain porcelain commits.
- Bad: a checkout per write is slow, leaves debris on a crash, and still checks the ref out
  while it writes, which other worktrees then cannot.

## More Information

- Mission discovery map: `kitty-specs/lane-worktree-model-hardening-01M6QY1M/upstream/doctrine-and-write-service-adr-map.md`
  on branch `issue-7276-lane-worktree-hardening` of spec-kitty/spec-kitty.
- Open questions, each with a default: a path-rewrite instead of the temporary index (default:
  temporary index); signing of status commits (default: unsigned until core's policy names
  them).
