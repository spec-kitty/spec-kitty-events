---
title: 'ADR-0008: the read service reads a coordination ledger from git objects'
description: 'Proposed: the read service reads a coordination Mission''s event log from the blob at its coordination branch tip, in process, never from a coordination worktree.'
doc_status: draft
updated: 2026-11-06T00:00:00Z
type: ADR
kind: ADR
authors:
  - human:stijn
related:
  - adr/0002-events-domain-owns-its-services
  - adr/0007-write-service-commits-coordination-status-by-ref
---

# ADR-0008: the read service reads a coordination ledger from git objects

**Status:** Proposed. Written for slice S9
([spec-kitty/spec-kitty#7284](https://github.com/spec-kitty/spec-kitty/issues/7284)) of epic
[spec-kitty/spec-kitty#7276](https://github.com/spec-kitty/spec-kitty/issues/7276), with
[ADR-0007](./0007-write-service-commits-coordination-status-by-ref.md). It becomes Accepted with
ADR-0007 and core **C-3**.

**Date:** 2026-11-06

**Deciders:** operator stijn-dejongh.

**Reader:** anyone changing where the Mission Status Read API (`service/`, the
`mission-status-api` deployable) reads a Mission's ledger, or its conformance against the
Python oracle.

**Technical Story:** [spec-kitty/spec-kitty#7284](https://github.com/spec-kitty/spec-kitty/issues/7284).
This is a new record, not an amendment of [ADR-0002](./0002-events-domain-owns-its-services.md):
ADR-0002 places the services here and decides nothing about where the read service reads.

Related records:

- [ADR-0007](./0007-write-service-commits-coordination-status-by-ref.md): the write side. Once the
  write service commits straight onto `refs/heads/<coord>`, there is no coordination worktree
  for the read service to read.
- Core **C-3** [2026-11-06-6](https://github.com/spec-kitty/spec-kitty/blob/main/docs/adr/4.x/2026-11-06-6-committed-ledger-is-the-coordination-blob.md) (Mission `lane-worktree-model-hardening-01M6QY1M`; the link
  resolves once the core pull request merges): for coordination topologies
  the committed ledger, and the publication seam of core ADR 2026-10-13-2 D-2, is the
  `status.events.jsonl` blob at `refs/heads/<coord>`; it amends core ADR 2026-10-01-2 D-3/D-6
  so the read API's pull reads git objects. C-3 also decides the `events tail` cursor. This
  record decides only how the Java read service does it.
- Core
  [ADR 2026-10-22-1](https://github.com/spec-kitty/spec-kitty/blob/main/docs/adr/4.x/2026-10-22-1-the-java-write-service-is-the-canonical-event-writer.md)
  D-8 (the read service stays read-only and separate) and
  [ADR 2026-10-29-1](https://github.com/spec-kitty/spec-kitty/blob/main/docs/adr/4.x/2026-10-29-1-the-events-domain-and-its-java-services-live-in-spec-kitty-events.md)
  D-6/D-7 (the Python-oracle conformance and corpus comparisons).

## Context and Problem Statement

`ReadDirResolver`
([`service/src/main/java/org/speckitty/missionstatus/adapter/out/filesystem/ReadDirResolver.java`](https://github.com/spec-kitty/spec-kitty-events/blob/main/service/src/main/java/org/speckitty/missionstatus/adapter/out/filesystem/ReadDirResolver.java))
picks the directory a Mission's ledger is read from. For a stored `coord` or `lanes_with_coord`
topology it reads the coordination Mission directory
`.worktrees/<slug>-<mid8>-coord/kitty-specs/<slug>-<mid8>/` when it exists (DD-105), and
otherwise falls back to the primary directory. It reads no git: a husk is told from a
coordination worktree by the stored topology alone.

After ADR-0007 there is no coordination worktree. The committed tree of the coordination branch
is the only state. A resolver that reads a directory would read either nothing (and fall back to
the primary directory, which holds no coordination status) or a stale husk. The read service has
guarantees it must keep (core 2026-10-01-2 D-6, 2026-10-22-1 D-8, and architecture rule 10 in
`ArchitectureRules`): it never writes, takes no lock and **starts no process**.

## Decision Drivers

- Read what the writer committed, and nothing else (the resolved status surface is the only
  source of truth; operator ruling of 2026-11-06).
- Keep the read service's guarantees: read-only, no lock, no process.
- Keep the Python oracle parity: the same Mission yields the same answer from the Java service
  and the Python reducer.

## Considered Options

1. **An in-process reader of git objects** behind the existing source port (chosen).
2. **A `git cat-file --batch` subprocess.**
3. **Keep reading a directory**, and require a coordination worktree for reads.

## Decision Outcome

Chosen option 1. *(derived)* throughout.

### D-1: a git-object adapter of the event source port

- A new adapter of `MissionEventSource` (beside `FilesystemMissionEventSource`) reads a
  coordination Mission's ledger from git objects: resolve `refs/heads/<coord>` (the
  `coordination_branch` of the Mission's `meta.json`, read from the primary directory as today),
  take its commit's tree, and read the blob at `kitty-specs/<slug>-<mid8>/status.events.jsonl`.
- `ReadDirResolver`'s coordination case (rule 2, DD-105) answers "read the ref", not a directory.
  The other rules (own primary directory for `single_branch` and `lanes`, classification from
  `meta.json` and `lanes.json`) are unchanged.
- A ref that does not exist, or a tree without the path, is the same answer the oracle gives for
  a missing coordination ledger; the adapter does not fall back to a working tree.
- One read pins one tip: every file of one answer that lives on the coordination branch (the
  event log and any sibling the answer needs) comes from the same commit, so a concurrent write
  by ADR-0007 is seen whole or not at all. `meta.json` and `lanes.json` still come from the
  primary directory's working tree, as today, and are not part of that snapshot.
- **Which source, when.** Until migration step 4, `ReadDirResolver` is unchanged. From the
  release that ships ADR-0007's `coordination_ref_commit` (together with core
  [C-1](https://github.com/spec-kitty/spec-kitty/blob/main/docs/adr/4.x/2026-11-06-4-coordination-branch-written-by-commit.md)'s drain of the coordination worktrees, ADR-0007's precondition), every
  coordination Mission is read from the ref, whether or not a husk directory is left on disk;
  there is no period in which a worktree holds status the ref lacks.
- Parity with the oracle on a missing ref or path depends on core C-1/C-3 changing the oracle
  too: today's oracle falls back to the primary directory (`probe_coord_state`, UNMATERIALIZED).
  The Java side follows whatever answer that change gives; it does not keep the fallback alone.

### D-2: in process, read-only, no process

- The adapter reads the object database in process: loose objects, pack files and their
  indexes, packed refs and loose refs. It never takes a ref lock, writes nothing and starts no
  process.
- JGit is acceptable only pinned down, because its defaults break those guarantees: its
  `FS`/`SystemReader` starts processes to find the system config and the `git` executable, and
  `FS.FileStoreAttributes` writes probe files into the repository and saves them to
  `$XDG_CONFIG_HOME/jgit/config`. The adapter installs a `SystemReader` that reads no system or
  user config and starts nothing, and presets the file-store attributes so nothing is probed.
  If that cannot be guaranteed, the adapter is a minimal reader of loose objects, pack index v2
  and packs, and loose and packed refs, written here. Architecture rule 10 scans only this
  project's classes, so it cannot detect a library that breaks the guarantee; the runtime check
  in Confirmation does.
- A ref backend the reader does not support (for example `reftable`, if the library lacks it)
  is a named source fault, never a silent fallback to a directory.
- The library must build into the native image of the read service; that is checked by the
  native smoke tests before the adapter ships. If it cannot, this record is revisited, not
  bypassed with a subprocess.

### D-3: conformance

- The Python oracle and the corpus comparison (2026-10-29-1 D-6/D-7) gain a fixture with a
  coordination branch and **no** coordination worktree: both sides must read the ledger from the
  ref and agree.
- The existing coordination-worktree fixtures stay until core **C-1**'s migration retires the
  worktrees, so both shapes are compared during the transition.

### D-4: cursor and contract

- The `mission-status` contract's stream cursor is a byte offset with a content invariant.
  Whether it gains the commit it was taken at, and how a cursor taken on a coordination branch
  behaves after consolidation rewrites the bytes onto the target, is core **C-3**'s decision.
  If C-3 changes the cursor, the change lands in `mission-status` before its `1.0.0`; this record
  changes no contract.

### Scope

Nothing here builds the adapter: it follows ADR-0007's implementation at migration step 4.

## Consequences

### Positive

- The read service and the write service see one source of truth, the committed tree.
- The husk ambiguity of DD-105 disappears for new Missions.

### Negative

- The read service grows a dependency that parses git's on-disk formats, and its native-image
  build must accept it.
- "Reads no git state beyond the one `HEAD` file"
  ([read-write split](../architecture/services/read-write-split.md)) no longer holds once the
  adapter ships; the guarantee becomes "reads git objects and refs, never writes or locks them",
  and that page changes when this record is Accepted.

### Neutral

- Reads of `single_branch` and `lanes` Missions are unchanged.

## Confirmation

When the adapter lands: the oracle parity and corpus comparison pass on the no-worktree fixture;
architecture rule 10 still passes; the native image, run against a read-only copy of a
repository with no `git` on `PATH` and an empty `HOME`/`XDG_CONFIG_HOME`, answers a coordination
Mission, starts no process and writes no file anywhere; a read during a concurrent ref move
returns the answer of exactly one of the two tips; a missing ref and an unsupported ref backend
each give their named answer.

## Pros and Cons of the Options

### An in-process reader of git objects (chosen)

- Good: keeps no-process and no-lock; one consistent snapshot per read.
- Bad: a second reader of git's formats, beside C git.

### A `git cat-file --batch` subprocess

- Good: C git reads its own formats, every ref backend included.
- Bad: breaks "starts no process" and rule 10, which the read service has held since
  2026-10-01-2.

### Keep reading a directory

- Bad: contradicts ADR-0007 and the rulings; brings back the worktree that was retired.

## More Information

- Open question, with a default: if the library cannot read `reftable` repositories, option 2
  for those repositories only (default: a named source fault, pending an operator ruling).
