---
title: 'ADR-0007: the write service commits coordination status onto the branch ref by compare-and-swap'
description: 'Accepted: the write service commits coordination status straight onto refs/heads/<coord> from git objects with a compare-and-swap ref update; no coordination worktree.'
doc_status: active
updated: 2026-11-08T00:00:00Z
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

**Status:** Accepted (2026-11-08; accepted by the maintainer on 2026-11-07). Written for slice S9
([spec-kitty/spec-kitty#7284](https://github.com/spec-kitty/spec-kitty/issues/7284)) of the
epic "Lane and worktree model hardening"
([spec-kitty/spec-kitty#7276](https://github.com/spec-kitty/spec-kitty/issues/7276)), on the
operator rulings of 2026-11-06 recorded there.

**Date:** 2026-11-06

**Deciders:** operator stijn-dejongh.

**Reader:** anyone building the `command-cli` write path for coordination Missions (migration
step 4 of core ADR 2026-10-22-1), changing the `mission-write` contract, or reviewing how
consolidation and the write service share a coordination branch.

**Technical Story:** [spec-kitty/spec-kitty#7284](https://github.com/spec-kitty/spec-kitty/issues/7284);
the `mission-write` contract change is `1.0.1-SNAPSHOT`
([`contracts/mission-write/CHANGELOG.md`](https://github.com/spec-kitty/spec-kitty-events/blob/main/contracts/mission-write/CHANGELOG.md)).

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
- The core records of the same slice, landing with the Mission
  `lane-worktree-model-hardening-01M6QY1M` core pull request (the links resolve once it
  merges; until then the files are on core branch `issue-7276-lane-worktree-hardening`):
  - **C-1** [2026-11-06-4](https://github.com/spec-kitty/spec-kitty/blob/main/docs/adr/4.x/2026-11-06-4-coordination-branch-written-by-commit.md): a coordination branch is written by commit, not through a
    coordination worktree (the non-status artefacts, the drain and removal of existing
    coordination worktrees, `status.json`);
  - **C-2** [2026-11-06-5](https://github.com/spec-kitty/spec-kitty/blob/main/docs/adr/4.x/2026-11-06-5-gates-over-worktree-less-coordination.md): consolidation gates over a worktree-less coordination branch
    (the write fence);
  - **C-3** [2026-11-06-6](https://github.com/spec-kitty/spec-kitty/blob/main/docs/adr/4.x/2026-11-06-6-committed-ledger-is-the-coordination-blob.md): the committed ledger of a coordination Mission is the blob on
    its coordination branch (publication seam, `events tail` cursor);
  - the **D-6 amendment** of 2026-10-22-1 that names this set.

  This record does not restate them.
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
index, working tree or worktree of the repository. Every git call runs with
`-c core.hooksPath=<an empty private directory>`, so no repository hook runs: plumbing alone
would still run `reference-transaction` (on `update-ref`) and `post-index-change` (on the
temporary index), and the service never runs code from the repository it writes.

1. Read the tip `T` of `refs/heads/<coord>` (`destinationRef`).
2. Read the Mission's event log blob at `<missionDirectory>/status.events.jsonl` in `T`'s tree;
   an absent path is an empty log.
3. Write the new blob: the old bytes followed by the unit's rows, as one
   `git hash-object -w --no-filters --stdin` (no clean or `eol` filter, so the bytes are exact).
4. Build the new tree from `T`'s tree with only that path changed, through a temporary index
   (`GIT_INDEX_FILE` in a private directory of the git common dir: `read-tree T`,
   `update-index --add --replace --cacheinfo 100644,<blob>,<path>`, `write-tree`). `--add` is
   needed for a Mission's first status write, when the path is not yet in `T`'s tree. The
   repository's own index is never read or written.
5. `commit-tree <tree> -p T` with the message of D-6.
6. `update-ref --create-reflog -m <message> refs/heads/<coord> <new> T`: the compare-and-swap
   (D-3). `--create-reflog` writes a reflog entry even where `core.logAllRefUpdates` is off (a
   bare repository).

A batch of units (`/work-package-transitions/batch`) is **one** commit, as it is one write and
one `fsync` today; so every unit of a batch names the same `missionDirectory`, `writeSurface`
and `destinationRef`, and a batch that does not is `REQUEST_MALFORMED` (the contract's
`TransitionBatch`, and `command-cli`'s request-shape check today). *(derived)*

Plumbing never runs `git gc --auto`, which a porcelain commit does today. The service runs it
after a successful unit, off the request's critical path, so the loose blobs a unit leaves (each
append writes the whole log as a new blob) are packed and unreachable objects of refused units
are pruned. *(derived)*

### D-2: byte and durability contract *(derived)*

- The appended bytes are exactly the rows of 2026-10-22-1 D-7 as amended by 2026-10-27-1 D-5:
  full JSON lines, nothing re-encoded.
- An old blob that is not empty and does not end in `\n` (a torn tail) is not appended to: the
  unit is refused with `COORDINATION_LEDGER_UNREADABLE` (500), as is a blob that cannot be read
  or parsed.
- "One `O_APPEND` write and `fsync`" becomes, for this surface: every object and the moved ref
  are durable before the acknowledgement. The service runs git with
  `-c core.fsync=objects,reference`, so the contents of the loose objects, the ref and its log
  are synced; it then syncs the directories that received them (the object fan-out
  directories, the ref's and the log's parent directories), as the CLI's writer syncs the
  parent directory after its rename today (`status/store.py`, `_fsync_directory`).
  `DurabilityAck.fsynced` keeps its meaning (durable before the answer).
- `DurabilityAck.statusCommit` names the commit `update-ref` moved the ref to. The field stays
  `x-provisional` until migration step 4 ships, because until then no unit commits.

### D-2a: replay on this surface *(derived)*

Idempotent retry (2026-10-27-1 D-5, still provisional in the contract) is judged against the
event log in the tree of the tip `T` the unit reads:

- every row of the unit is already in that log with identical content: `replayed: true`,
  `appendedLines: 0`, no commit, and `statusCommit` names the commit that first wrote the rows,
  found by its `Spec-Kitty-Event-Id:` trailers (D-6). This covers a crash after `update-ref` and
  before the acknowledgement;
- only some of the unit's rows are there, or one is there with different content:
  `EVENT_ID_CONFLICT` (409), nothing written.

### D-3: a lost compare-and-swap is a refusal, never a retry *(derived)*

- The service serializes its own writes per repository in process (2026-10-22-1 D-4), so when
  `update-ref <new> <T>` finds the ref no longer at `T`, another writer moved it: the CLI during
  the strangler, consolidation, or the user. The unit is refused with
  **`COORDINATION_REF_MOVED`** (409) and nothing is acknowledged. The objects written in steps
  3 to 5 are unreachable and left to `git gc` (D-1).
- `update-ref` reports a lost compare-and-swap, a held ref lock (`core.filesRefLockTimeout`) and
  a deleted ref alike ("cannot lock ref"). The service re-reads the ref after any failure:
  still at `T` is transient contention, `SERVICE_BUSY` (503, retry the same service); missing is
  `COORDINATION_BRANCH_DELETED` (422); any other value is `COORDINATION_REF_MOVED`.
- No blind retry: a retry would append on top of a tip the caller has not seen, and would hide a
  concurrent consolidation. The caller re-reads and decides. Whether consolidation fences writes
  out (a refusal while a consolidation record holds the Mission) is core **C-2**'s decision; the
  compare-and-swap is the service's half of that exclusion either way.
- A coordination branch that is checked out in any worktree of the repository is not written:
  moving a checked-out ref makes that checkout look dirty and invites a lost update on its next
  commit. The unit is refused with **`COORDINATION_REF_CHECKED_OUT`** (409). "Checked out"
  follows git's own in-use rule: the `HEAD` of a non-bare root checkout, every
  `git worktree list --porcelain` entry, and a worktree in the middle of a rebase or bisect on
  that branch (which `worktree list` shows as detached). A prunable entry whose directory is gone
  is not a checkout. A bare repository's `HEAD` is not a checkout.
- **Residual window.** A `git checkout <coord>` or `git worktree add` between this check and
  `update-ref` is not covered by the compare-and-swap, which guards the ref's value, not its
  checkouts. Nothing in Spec Kitty checks a coordination branch out once core **C-1**'s
  migration has removed the coordination worktrees; a user who does it by hand in that window
  sees a checkout that is behind its branch. This risk is accepted.

### D-4: placement checks run in the service *(derived)*

- `destinationRef` is required for this surface (enforced by `RequestContext`'s schema and by
  `command-cli`'s request-shape check). The surface is served from migration step 4, when
  write-location resolution moves into the service (2026-10-22-1 open question 2): the service
  resolves the Mission's `coordination_branch` itself, and a request whose `destinationRef`
  differs is `WRITE_LOCATION_UNVERIFIED`. This changes core ADR 2026-10-27-1 D-3, which keeps
  `destinationRef` null "until placement moves into the service": for this surface it is never
  null. Core's client (`write_service_protocol.py`, a core file per 2026-10-29-1 D-7) follows;
  core **C-1** records the change.
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
- The service decides nothing from the user's git configuration beyond identity (D-6): it passes
  the `-c` settings this record names on every call.
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
  is nothing to materialize. The CLI's own codes of the same names retire with the worktree
  (core **C-1**).
- Added protocol-only codes: `COORDINATION_REF_MOVED` (409), `COORDINATION_REF_CHECKED_OUT`
  (409), `COORDINATION_LEDGER_UNREADABLE` (500), marked provisional until migration step 4
  serves the surface.
- The protocol major stays 1. A client built on the `rc.1`/`rc.2` bundle that sends
  `coordination_transaction_append` gets `REQUEST_MALFORMED`, not `PROTOCOL_VERSION_UNSUPPORTED`
  at the handshake. That is accepted because no client transport exists yet (2026-10-27-1 D-8)
  and every write still fails closed; a protocol major is the tool once a client speaks to a
  serving writer.
- `breaking_check.py` reads `1.0.Z` and `1.0.Z-SNAPSHOT` as the initial line while no release
  exists, and `contracts/README.md`'s versioning rule records the ruling.

### Scope and rollout precondition

This record covers the coordination **status** event log, which is what the rulings name. The
other things the coordination transaction writes today (notes, trace, issue matrix, decision
artefacts, subprocess byproducts) need their own rule before the coordination worktree can be
retired; that is core **C-1**'s decision (an artefact-write operation of the service, or a
partition move, #7266). Whether `status.json` is committed on the coordination branch is also
C-1's. Nothing here builds the service: implementation follows migration step 4 (#6117).

**Precondition.** `coordination_ref_commit` goes live only in the release that also ships C-1's
rule for those artefacts and its drain and removal of existing coordination worktrees (steps 4
and 4a of 2026-10-22-1 land together). Otherwise, from step 4 on, the CLI has no status writer
(2026-10-22-1 D-4, D-5: no Python fallback writer) and every existing coordination Mission is
refused with `COORDINATION_REF_CHECKED_OUT` on every transition, because its worktree still
checks the branch out. *(derived; the ordering itself is C-1's to record)*

## Consequences

### Positive

- The divergence and materialization failures disappear by construction, with their five codes.
- A unit is atomic: the ref moves to a commit that holds all its rows, or it does not move.
- Concurrent movers of the coordination branch are detected, never overwritten.
- The service never needs a checkout, so one service serves every worktree without touching any.

### Negative

- No repository hook runs on a status commit (`core.hooksPath` is emptied): a hook that watched
  the coordination branch no longer sees status commits.
- Building the tree through a temporary index reads the whole tree of `T`; for very large trees a
  path-rewrite of the nested trees (`mktree`) is cheaper and may replace it without changing
  this record.
- Each append writes the whole log as a new blob; `git gc --auto` after the unit (D-1) bounds
  the store, but a very large log costs its size per append until it is packed.
- A refused `COORDINATION_REF_MOVED` puts the retry decision on the caller.

### Neutral

- Unreachable objects of a refused unit wait for the next `git gc --auto`.

## Confirmation

When the step-4 implementation lands, `command-cli` tests show, in a temporary repository:

- a unit and a batch each produce exactly one commit whose parent is the prior tip, whose tree
  differs only at the Mission's event log, and whose blob equals the prior bytes plus the rows
  byte for byte (the `status_append` fixture family);
- a Mission's first status write (no log in `T`'s tree) commits;
- the repository's index, `HEAD` and working tree are unchanged, and a `reference-transaction`
  and a `post-index-change` hook planted in the repository never ran;
- a ref moved between read and update gives `COORDINATION_REF_MOVED` and leaves the ref where the
  other writer put it; a held ref lock gives `SERVICE_BUSY`; a deleted ref gives
  `COORDINATION_BRANCH_DELETED`; a branch checked out in the root checkout, a linked worktree or
  a worktree mid-rebase gives `COORDINATION_REF_CHECKED_OUT`, and a bare repository's `HEAD` does
  not; a torn or unreadable log gives `COORDINATION_LEDGER_UNREADABLE`;
- a retry after a crash between `update-ref` and the answer is `replayed: true` naming the first
  commit; a partial replay is `EVENT_ID_CONFLICT`;
- every refusal body validates against `WriteRefusal`.

Today, `OperationsTest` checks that `coordination_ref_commit` with a branch is well-formed, that
it is malformed without one, that the retired `coordination_transaction_append` and a batch whose
units name different locations are malformed, and that the service's surfaces equal the
contract's; `enum_pins.json` pins the new `WriteSurface`.

## Pros and Cons of the Options

### Commit from git objects with compare-and-swap (chosen)

- Good: one source of truth; atomic; lost updates detected; no checkout lifecycle.
- Bad: no hooks; a subprocess per git step; a whole-log blob per append.

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
