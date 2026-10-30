---
affected_files: []
cycle_number: 1
mission_slug: events-domain-services-home-01M64MSB
reproduction_command:
reviewed_at: '2026-10-29T21:37:00Z'
reviewer_agent: claude
wp_id: WP05
---

# WP05 review, cycle 1 (reviewer-renata)

Verdict: **changes requested**. There is one [MAJOR] finding, and it takes a two-line fix. Everything
else checks out against core at `4eecb124`. Please also address the minors in the same pass.

## [MAJOR] 1. Unsourced claim about a future charter service (write-service-protocol.md)

The last block of `docs/architecture/services/write-service-protocol.md` says:

> **Note.** A future charter service would use the same shape, with `--worktree` in place of `--git-common-dir`.

No source supports this. It is not in the launcher contract the operator accepted on 2026-10-29
(serve/--git-common-dir/--idle-timeout, service.lock, endpoint.json by rename, exit 0 when the lock is
held, service.log, the three ready checks, POST /shutdown with a SIGTERM/TerminateProcess fallback after 10 s).
It is also not in any core ADR, in `contracts/mission-write`, in this mission's spec/plan/research, or in
`spec-kitty-charter` (a grep for `--worktree` and `serve --` finds nothing). The WP's DoD says that every
behavioural claim cites a core path or contract, and the review bar says that nothing is invented. Remove the
note, or cite the record where the operator ruled it.

## [minor] 2. "Its stdout and stderr go to service.log"

The accepted contract names `<gcd>/spec-kitty-write-service/service.log`, but it does not say what goes there.
Either say "it logs to `<gcd>/spec-kitty-write-service/service.log`", or confirm the stdout/stderr redirect
with the operator.

## [minor] 3. The 64-unit batch cap cited as D-5

"a batch of up to 64 units is one write and one `fsync` … (D-5)": the 64-unit cap comes from ADR
2026-10-27-1 open question 7 (provisional) and from the `REQUEST_MALFORMED` description in
`WriteRefusalCode.yaml`, not from D-5. Cite it as "open question 7, provisional".

## [minor] 4. Core ADR 2026-10-29-1 marked "accepted"

`overview.md` says core ADR 2026-10-29-1 is "(accepted; the link resolves once core PR #6782 merges)". The file
is not on core `main` at `4eecb124`, and from this sandbox I could check neither its status nor PR #6782. If
the status or the PR number cannot be confirmed, write "pending" (see the WP02 review, minor 3).

## [minor] 5. Link style in docs/architecture/README.md

The new hub entry links `./services/README.md`, while its peers in the same list use site routes
(`/architecture/durability-and-relay/`). check-links accepts both, but use `/architecture/services/`
for consistency.

## Verified, no action needed

- Lockfile: the `node_modules/@spec-kitty/doc-toolkit` entry (version, resolved, integrity, and the whole
  dependency/peer block) is identical to `spec-kitty-charter/site/package-lock.json`. The diff touches only
  the root pin and those three fields. doc-kitty `src/package.json` dependencies and peerDependencies are
  identical between `v0.4.0` and `v0.5.0`. Migration 0.4→0.5 (`kind` on every page) is satisfied.
- `validate-frontmatter`: 52 files valid, 0 errors (2 warnings in pre-existing changelog pages).
  `check-links`: 114 references resolve, 0 errors.
- Frontmatter: Hub plus Explanation with type Architecture match the peers in `docs/architecture/`.
  `okf_version` appears only on the hub, as for the peers.
- Scope: 847a324 touches only owned files.
- Facts checked against core `4eecb124`:
  - The 14 ArchUnit rules match `ArchitectureRules.all`. So do the rule-count guard and the controls.
  - `DomainModelBoundaryTest` (18 extracted types) and `EventLogBoundaryTest` (3 packages) are as described.
  - The 29 refusal codes (20 CLI and 9 protocol-only, names exact), the order of checks, and the 6 operationIds
    with their protocol-header requirement all match.
  - The handshake fields and the `repositoryId` rule, the `DurabilityAck` fields, the endpoint-file fields and
    permissions, and `SUPPORTED_PROTOCOL_VERSIONS = (1,)` all match.
  - The lock-interop table (1730/0, 172/495, run 40557031791) matches.
  - The `TornTailRepair` behaviour, `ledger_torn_tail`/L4, the two granularities (D-2), and the D-6 drain
    independence all match.
  - The writers/readers summary matches: fifteen implementations across more than twelve modules and six
    shapes, and G-1, G-4, G-13.
  - The 2026-10-22-1 D-1/D-2/D-3/D-5/D-8 claims and the stale "does not exist yet" in core's C4 pages
    both check out.
  - The write core is consistently presented as not built, and the Java append as spike-only.
- The launcher contract is documented as "agreed 2026-10-29; recorded in contracts/mission-write at cutover",
  and all five operator points are present.
- Mermaid: the context, container, onion and sequence diagrams are minimal and syntactically plausible.
