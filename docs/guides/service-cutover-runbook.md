---
title: Service cutover runbook
description: 'Maintainer procedure to import the events-domain services from core with history, integrate the build, hand the first release to core and move the issues.'
doc_status: active
updated: 2026-10-31T00:00:00Z
type: Guide
kind: How-To
related:
  - guides/service-protocol-and-releases
  - guides/running-the-service
---

# Service cutover runbook

This is the procedure that moves the events-domain services from core
(`spec-kitty/spec-kitty`) into this repository with their git history
([ADR-0002](../adr/0002-events-domain-owns-its-services.md)). Mission
`events-domain-services-home-01M64MSB` work packages WP07 (import and build) and WP08 (first
release and handshake) follow it step by step.

> **Nothing here runs without the operator's go-signal** (spec constraint C-001): no import,
> no tag, no issue move, no layout-changing PR before it. Steps marked **[operator]** need a
> separate, explicit approval each. Every step that changes GitHub carries that marker: each
> `git push` (branch or tag), each PR opened, merged or closed, each remote branch deleted, and
> each `gh` call that writes. Local commands in scratch or in a local checkout do not. Never
> push `main`, never force-push anything, and never delete or reset the mission branch.

The commands use these names. Write their values into the import PR description as you
go.

| Name | Value |
|---|---|
| `FROZEN_SHA` | the 40-hex core commit the freeze pins (step 1) |
| `FROZEN_TREE` | `99c06d1dba366c65c1260342c3e6370c8233953e`, the git tree of core `services/` the freeze gate pins |
| `SCRATCH` | an empty scratch directory outside every checkout; nothing in it is ever pushed |
| `CORE` | a local core checkout (read-only here: nothing is fetched into it, nothing is written to it) |
| `WATCH` | `$SCRATCH/core-watch`, an unfiltered full clone of core made in step 1; it holds `FROZEN_SHA` and `origin/main` for the breach checks |
| `EVENTS` | this repository's checkout |
| `MISSION_BRANCH` | `issue-147-events-services-home`, the mission branch: WP01 to WP06 and the spec-kitty status history. This runbook never deletes, resets or force-pushes it. |
| `IMPORT_BRANCH` | `issue-147-service-import`, the dedicated branch the import runs on (step 3), and its own PR to `main`. Rollback before merge deletes only this branch and its PR. |
| `PRE_IMPORT` | the commit `IMPORT_BRANCH` is cut from, recorded before the merge (step 3) |
| `IMPORTED_PATHS` | the core paths the import takes, exactly as in the filter-repo command (step 2.3) |

```bash
IMPORTED_PATHS=(
  services/mission-status-api/
  contracts/mission-status/ contracts/mission-write/ contracts/_shared/
  contracts/tools/ contracts/gradle/ contracts/lint/
  contracts/README.md contracts/build.gradle contracts/settings.gradle
  docs/architecture/event-ledger-writers-and-readers.md
)
```

## 1. Preconditions

Stop unless every line holds.

1. **The operator's go-signal is recorded** in the mission (status history or the session),
   with its date.
2. **The active core missions that touch `services/mission-status-api/` or the service
   contracts are merged** in core.
3. **The events-side freeze checks are the binding line.** Steps 1.6 (the object-id table)
   and 1.7 (the breach log) decide whether the cutover goes ahead. Run both whatever state
   core's gate is in; if both pass, go ahead.
   Core's own gate is core PR spec-kitty/spec-kitty#6782, with
   `tests/architectural/test_events_import_frozen.py`. It pins all eleven imported paths
   (`services/` at tree `FROZEN_TREE`, and the ten others) to the same object ids as the table
   in 1.6, and it runs on every core PR. Core's `contracts/fixtures/` stays in core and is not
   frozen. Confirm with the core session that #6782 has merged, and record the answer. Core's
   session confirmed on 2026-10-29 that the gate covers every imported path. Do not hold the
   cutover for #6782 if 1.6 and 1.7 both pass: the events-side checks bind either way.
4. **The frozen SHA is chosen and written down.** Core's session sends it when #6782
   merges. Record it as `FROZEN_SHA`.
5. **Make the watch clone.** A full, unfiltered clone of core in scratch. `FROZEN_SHA` is
   fetched into it, never into `CORE`, and pinned by a branch so the extraction clone in
   step 2 receives it.

   ```bash
   FROZEN_SHA=<the sha core sent>
   FROZEN_TREE=99c06d1dba366c65c1260342c3e6370c8233953e
   mkdir -p "$SCRATCH" && cd "$SCRATCH"
   git clone --no-local --no-checkout "$CORE" core-watch
   WATCH="$SCRATCH/core-watch"
   git -C "$WATCH" remote set-url origin https://github.com/spec-kitty/spec-kitty
   if [ "$(git -C "$WATCH" rev-parse --is-shallow-repository)" = true ]; then
     git -C "$WATCH" fetch --unshallow origin main
   fi
   git -C "$WATCH" fetch origin main "$FROZEN_SHA"
   git -C "$WATCH" branch -f frozen "$FROZEN_SHA"
   git -C "$WATCH" rev-parse --is-shallow-repository                     # expected: false
   git -C "$WATCH" merge-base --is-ancestor "$FROZEN_SHA" origin/main && echo "frozen sha on main: OK"
   ```

   `filter-repo` rewrites only the history it has, so a shallow clone silently drops
   commits (a shallow local core gave 34 commits instead of 141 in the dry run). The watch
   clone is never filtered: the breach checks need core's real history.
6. **Verification 1: every imported path is the frozen object.** At `FROZEN_SHA`, each path
   must have the git object id core's freeze gate pins:

   | Object id | Path |
   |---|---|
   | `99c06d1dba366c65c1260342c3e6370c8233953e` | `services` (`FROZEN_TREE`) |
   | `a09d96532581d244b5dd0f295a23cf3705d0cd00` | `contracts/mission-status` |
   | `3f52fa137ee62aa48469c3e0cdbea4efb0f65be3` | `contracts/mission-write` |
   | `fa7cc44678491c5b0d87eb4b1441f0945a375ed4` | `contracts/_shared` |
   | `6235043e87c4ba3b77f7708e80052b843dcb14c5` | `contracts/tools` |
   | `afea11080e3f206cddf9212e472ac56b047224c7` | `contracts/lint` |
   | `4a5e2c489b274718b7c0bdd9bc2a2ef009474e93` | `contracts/gradle` |
   | `79436249bde4a84af4467d0b2fb180104e6417c4` | `contracts/README.md` |
   | `2ebf317f01cdce199eaef571c7a6c1febb3be3be` | `contracts/build.gradle` |
   | `c4b8720e271103c957b5a02253b99f6e815f155b` | `contracts/settings.gradle` |
   | `c2943d4997a19d10ffd1289e70220ec602f4e3ac` | `docs/architecture/event-ledger-writers-and-readers.md` |

   ```bash
   bad=0
   while read -r id path; do
     got="$(git -C "$WATCH" rev-parse "$FROZEN_SHA:$path")"
     if [ "$got" = "$id" ]; then echo "OK   $path"; else echo "DIFF $path ($got)"; bad=1; fi
   done <<'EOF'
   99c06d1dba366c65c1260342c3e6370c8233953e services
   a09d96532581d244b5dd0f295a23cf3705d0cd00 contracts/mission-status
   3f52fa137ee62aa48469c3e0cdbea4efb0f65be3 contracts/mission-write
   fa7cc44678491c5b0d87eb4b1441f0945a375ed4 contracts/_shared
   6235043e87c4ba3b77f7708e80052b843dcb14c5 contracts/tools
   afea11080e3f206cddf9212e472ac56b047224c7 contracts/lint
   4a5e2c489b274718b7c0bdd9bc2a2ef009474e93 contracts/gradle
   79436249bde4a84af4467d0b2fb180104e6417c4 contracts/README.md
   2ebf317f01cdce199eaef571c7a6c1febb3be3be contracts/build.gradle
   c4b8720e271103c957b5a02253b99f6e815f155b contracts/settings.gradle
   c2943d4997a19d10ffd1289e70220ec602f4e3ac docs/architecture/event-ledger-writers-and-readers.md
   EOF
   test "$bad" = 0 && echo "frozen ids: OK"
   ```

   Expected: eleven `OK` lines, then `frozen ids: OK`. **Any `DIFF`: stop.** Core's paths at
   that SHA are not what the freeze pinned; ask core for the right SHA. (At the dry-run
   commit `4eecb124` all eleven match.)
7. **Verification 2: no freeze breach since `FROZEN_SHA`.** No core commit on `main` after
   `FROZEN_SHA` may touch an imported path. A commit to, say, `contracts/mission-write/`
   would otherwise be lost without anyone noticing.

   ```bash
   git -C "$WATCH" fetch origin main
   git -C "$WATCH" log --oneline "$FROZEN_SHA"..origin/main -- "${IMPORTED_PATHS[@]}"
   ```

   Expected: **no output**. Any line is a breach: stop and go to
   [section 9](#9-if-the-freeze-is-breached). This check runs again as the last gate before
   the operator merges the import PR ([step 5](#5-verification)). (At `4eecb124` against core
   `main` `d68c95a4` it printed nothing; with the range moved to start just before
   `0d8c32ba`, it printed that commit.)

## 2. Extraction (scratch)

1. **The extraction clone**, from the watch clone, so it is full and holds `FROZEN_SHA`
   (branch `frozen`):

   ```bash
   cd "$SCRATCH"
   git clone --no-local --no-checkout "$WATCH" core-extract
   cd core-extract
   git rev-parse --is-shallow-repository      # expected: false
   git checkout -q -B extract "$FROZEN_SHA"
   ```

   Do not delete other branches: whatever core's default branch is called, filter-repo
   rewrites every ref and drops the `origin` remote, and only `extract` is fetched and merged
   in step 3. The clone also carries core's tags; step 3 fetches with `--no-tags` so none of
   them reaches this repository.

2. **Install filter-repo** (2.47.0 was used in the dry run):

   ```bash
   python3 -m pip install --user git-filter-repo==2.47.0
   git filter-repo --version                   # 2.47.0 prints a40bce548d2c
   ```

3. **Filter** (research R-2). The `--path` arguments are `IMPORTED_PATHS`. `--force` is
   needed because the clone is no longer fresh after the checkout; it is a throwaway scratch
   clone.

   ```bash
   git filter-repo \
     --path services/mission-status-api/ \
     --path contracts/mission-status/ --path contracts/mission-write/ --path contracts/_shared/ \
     --path contracts/tools/ --path contracts/gradle/ --path contracts/lint/ \
     --path contracts/README.md --path contracts/build.gradle --path contracts/settings.gradle \
     --path docs/architecture/event-ledger-writers-and-readers.md \
     --path-rename services/mission-status-api/:service/ \
     --force
   ```

   Expected: `Completely finished after <n> seconds.`

4. **Verify the history.**

   ```bash
   git rev-list --count extract            # 141 at 4eecb124; at least 141 at a later FROZEN_SHA
   git rev-list --merges --count extract   # 0
   git ls-tree -r --name-only extract | wc -l   # 1904 at 4eecb124
   git log -1 --format='%s' extract        # the last core commit that touched an imported path
   ```

5. **Verify the content** against core at the frozen SHA, read from the watch clone:

   ```bash
   mkdir -p "$SCRATCH/src"
   git -C "$WATCH" archive "$FROZEN_SHA" services/mission-status-api contracts \
     docs/architecture/event-ledger-writers-and-readers.md | tar -x -C "$SCRATCH/src"
   diff -r service "$SCRATCH/src/services/mission-status-api" && echo "service: clean"
   for p in mission-status mission-write _shared tools gradle lint README.md build.gradle settings.gradle; do
     diff -r "contracts/$p" "$SCRATCH/src/contracts/$p" >/dev/null && echo "contracts/$p: clean" || echo "contracts/$p: DIFF"
   done
   diff docs/architecture/event-ledger-writers-and-readers.md \
     "$SCRATCH/src/docs/architecture/event-ledger-writers-and-readers.md" && echo "doc: clean"
   ```

   Expected: every line ends in `clean`. Core's `contracts/fixtures/` is not imported, on
   purpose, so compare per path, not the whole `contracts/` directory. Any `DIFF`: stop.

## 3. Merge (on the dedicated import branch)

The import never runs on `MISSION_BRANCH`. Cut `IMPORT_BRANCH` from `main` once the mission
PR with WP01 to WP06 has merged (preferred), or, if it has not, from the tip of
`MISSION_BRANCH`; either way `MISSION_BRANCH` itself is left as it is. Record the base as
`PRE_IMPORT`:

```bash
cd "$EVENTS"
git status --porcelain                         # expected: no output (a clean tree)
git fetch origin
git switch --no-track -c issue-147-service-import origin/main   # or: origin/issue-147-events-services-home
PRE_IMPORT="$(git rev-parse HEAD)"             # write it into the import PR description
git remote add core-extract "$SCRATCH/core-extract"
git fetch --no-tags core-extract extract
git merge --allow-unrelated-histories --no-ff --no-commit core-extract/extract
```

`--no-track` keeps `IMPORT_BRANCH` from tracking its base. With tracking set, a bare
`git push` could target `main` under some `push.default` settings. Nothing is pushed in this
step; [step 5](#5-verification) pushes the branch with an explicit
`git push -u origin issue-147-service-import` **[operator]**.

Expected: exactly one conflict, `CONFLICT (add/add): Merge conflict in contracts/README.md`.
Any other conflict: `git merge --abort` and stop.

1. **Resolve `contracts/README.md`.** Keep this repository's prose as it is (`git show
   :2:contracts/README.md`), then add a `## Service contracts` section that holds core's
   README (`git show :3:contracts/README.md`) without its title and with every heading one
   level down. Fix its relative links if they now point elsewhere. `git add
   contracts/README.md`.
2. **Commit** with the frozen SHA in the subject and the command in the body. Write the
   message to a file in scratch (the shell fills in `$FROZEN_SHA`), then commit with it:

   ```bash
   cat > "$SCRATCH/import-msg.txt" <<EOF
   Import events-domain services from spec-kitty/spec-kitty@$FROZEN_SHA (#147)

   History extracted with git filter-repo 2.47.0 from a full clone at $FROZEN_SHA:

     git filter-repo --path services/mission-status-api/ --path contracts/mission-status/ \\
       --path contracts/mission-write/ --path contracts/_shared/ --path contracts/tools/ \\
       --path contracts/gradle/ --path contracts/lint/ --path contracts/README.md \\
       --path contracts/build.gradle --path contracts/settings.gradle \\
       --path docs/architecture/event-ledger-writers-and-readers.md \\
       --path-rename services/mission-status-api/:service/ --force

   contracts/README.md: kept the events prose, added a "Service contracts" section.
   EOF
   git commit -F "$SCRATCH/import-msg.txt"
   git log -1 --format='%s | parents %p'      # the subject, then PRE_IMPORT and the extract tip
   ```

3. **Verify the merge and drop the remote.**

   ```bash
   git rev-list --count HEAD^2                          # same count as step 2.4
   diff -r service "$SCRATCH/src/services/mission-status-api" && echo "merged service/: clean"
   git log --follow --format='%ad' --date=short service/build.gradle.kts | tail -1   # a date in 2026-10
   git remote remove core-extract
   git tag --points-at HEAD^2                           # expected: no output (no core tag came along)
   ```

The imported commits stay as they are: no rebase, squash or amend across them. **[operator]**
**The operator merges the import PR with a merge commit**, never squash or rebase, or the
history is lost.

## 4. Post-import edits (WP07)

Each edit is its own commit after the merge commit.

1. **Path fixes** (research R-1):
   - `service/build.gradle.kts:72`: the `contractRoot` default `../../contracts` becomes
     `../contracts`.
   - `service/src/test/java/org/speckitty/missionstatus/adapter/in/http/contractdoc/ContractDocumentTest.java:38`:
     `../../contracts` becomes `../contracts`.
   - `ArchitectureControlsTest.java:174` builds a synthetic `file:/w/services/mission-status-api/...`
     URL; check it still passes and leave it.
   - `grep -rn 'services/mission-status-api' service contracts`: fix real paths, leave
     historical comments.
2. **Root Gradle integration** (WP07 T032): the root `settings.gradle.kts` includes
   `:mission-status-api` (`service/`), `:domain-model` and `:event-log`; the service's own
   settings file and wrapper go; its catalog merges into the root catalog and its
   verification metadata into the root `gradle/verification-metadata.xml`. `contracts/` joins
   the same root build as the project `:contracts` (its Groovy `build.gradle` stays), so
   `./gradlew verifyAll` runs its checks too; `contracts/tools/pins.json` and
   `gradle_pin_check.py` move from Gradle 8.14.5 to the root wrapper's 9.8.1 (ADR 0003).
3. **The core pin.** Add `service/core-pin.json`:

   ```json
   {"repository": "spec-kitty/spec-kitty", "sha": "<FROZEN_SHA>", "purpose": "CI-only peers: lock interop, corpus oracle, golden regeneration (ADR 0002 D4)"}
   ```

   With `service/build.gradle.kts` and the pin present, `service.yml`'s `detect` job turns
   the guarded jobs on (`contract-bundle`, `native`, `lock-interop`, `nightly-corpus`) with
   no edit to the workflow.
4. **The lock-interop peer.** `service.yml`'s `lock-interop` job already sets
   `SPEC_KITTY_LOCK_INTEROP_PEER` (the peer script in the pinned core checkout,
   `core/scripts/ci/lock_interop_peer.py`) and `SPEC_KITTY_LOCK_INTEROP_PYTHON`. Make
   `PythonPeer` (`service/event-log/src/test/java/org/speckitty/missionstatus/spike/lockinterop/PythonPeer.java`)
   read `SPEC_KITTY_LOCK_INTEROP_PEER` first and fall back to its old relative walk for
   `scripts/ci/lock_interop_peer.py`. Without it the test throws `IllegalStateException`
   here, because the walk never reaches the core checkout.
5. **The launcher contract.** Add the launcher-to-write-service process contract to
   `contracts/mission-write` as a launcher section. Its text is the one the operator
   accepted on 2026-10-29, in
   [write-service protocol, "Process lifecycle"](../architecture/services/write-service-protocol.md#process-lifecycle-launcher-contract);
   copy it from there, and keep `/shutdown` marked provisional.
6. **The writers-and-readers page.** Keep its history:
   `git mv docs/architecture/event-ledger-writers-and-readers.md docs/architecture/services/event-ledger-writers-and-readers.md`,
   then add DocKitty frontmatter and link it from `docs/architecture/services/README.md`.
7. **CI follow-ups.** Decide each and record the decision in the PR:
   - Core's cross-leg native digest comparison (`scripts/ci/mission_status_gate_checks.py
     digests`) and `nativePerf`: wire them through the core-pin checkout, or record them as
     deliberately omitted. The `digest-<target>` artifacts are already uploaded by the
     `native` job.
   - Lock interop: it runs on PRs and `main` pushes but not inside a release run, because it
     executes core's Python (`service.yml` guards it with `inputs.release-version == ''`).
     Decided 2026-10-31: it stays a hard need of `service-gate` (blocking on PRs and `main`).
   - Reachability metadata: upload the metadata the native tests generate as a `native`
     job artifact for review (ADR 0003, Risks).
   - `make test-full` (what the CI agent runs) reaches the Java build only through
     `test-java` (`:java-binding:check`). Point it at `./gradlew verifyAll`, with the
     contract-bundle exports from section 5, so the CI agent's suite covers the services.

   **Decisions recorded for the import PR (WP07, 2026-10-30):**

   - **Reachability metadata: done.** The `native` job uploads
     `reachability-metadata-<target>` (Spring AOT's `META-INF/native-image` hints for the
     image and the test image, and any the Native Build Tools copied from the GraalVM
     metadata repository) with `if-no-files-found: warn`, for review only.
   - **Cross-leg digest comparison and `nativePerf`: deliberately omitted for
     `service-v0.1.0-rc.1`; follow-up.** Neither is wired through the core-pin checkout. The
     `digest-<target>` artifacts are still uploaded, so the comparison can be run by hand and
     wired in a later PR without a workflow-shape change. Core's third native guard, the
     `native-tag-floor` check (at least 3 executed native tests per leg), **is** ported:
     `scripts/ci/mission_status_gate_checks.py` (verbatim from core) runs in each `native` leg.
   - **Lock interop: blocking on PRs and `main` (operator decision, 2026-10-31).** It is a
     hard need of `service-gate`, so a red run fails the gate; it never runs inside a release
     run (it executes core's Python). Core treated it as evidence only; this repository keeps it
     blocking, and a tag is cut only from a `main` commit whose `service-gate` is green.
   - **`make test-full`: done.** It runs `scripts/verify_all.sh` through `make test-gradle`
     (the bundle exports, then `./gradlew verifyAll`), so the CI agent's suite covers the
     services and `contracts/`.
8. **The guides.** Flip `doc_status` of this page, [running the service](./running-the-service.md)
   and [service protocol and releases](./service-protocol-and-releases.md) from `draft` to
   `active`, and their citations from core `4eecb124` to the paths here. Do the same for the
   [services architecture pages](../architecture/services/README.md), whose banners now say the
   code lives here, and point `service/README.md` at the `service-v*` line (ADR 0003).

## 5. Verification

Record each command and its counts in the PR. `verifyAll` runs `:mission-status-api:check`,
whose `bundleContract` task needs the contract bundle, so build it first, outside the
repository, exactly as `service.yml`'s `contract-bundle` and `gradle` jobs do:

```bash
export LC_ALL=C.UTF-8
out="$(mktemp -d)"
uv run --frozen python contracts/tools/bundle.py --root contracts --out "$out" --bundle-only
export CONTRACT_BUNDLE="$out/bundle/mission-status/openapi.yaml"
export CONTRACT_BUNDLE_SHA256="$(sha256sum "$CONTRACT_BUNDLE" | cut -d' ' -f1)"

./gradlew --no-daemon verifyAll      # per module; about 883 @Test + 66 parameterized + 6 factories, more at run time
make test-fast
uv run pytest --pyargs spec_kitty_events.conformance
ruff check . && ruff format --check .
node ../doc-kitty/src/scripts/validate-frontmatter.mjs docs
node ../doc-kitty/src/scripts/check-links.mjs docs
```

Without `CONTRACT_BUNDLE` and `CONTRACT_BUNDLE_SHA256` (or `-PcontractBundle` and
`-PcontractBundleSha256`), `bundleContract` fails and names what is missing.

Expected: all green, with the ArchUnit and contract conformance tests passing.

**[operator]** Push `IMPORT_BRANCH` only (no tag), with an explicit upstream, and open its PR to
`main`:

```bash
git push -u origin issue-147-service-import
```

`service.yml`'s guarded jobs must run green on the PR.

**Last gate, just before the operator merges the import PR:** re-run
[Verification 2](#1-preconditions) (step 1.7) in the watch clone:

```bash
git -C "$WATCH" fetch origin main
git -C "$WATCH" log --oneline "$FROZEN_SHA"..origin/main -- "${IMPORTED_PATHS[@]}"
```

Expected: no output. Any line: do not merge; go to [section 9](#9-if-the-freeze-is-breached).
No line: **[operator]** the operator merges the import PR with a merge commit
([step 3](#3-merge-on-the-dedicated-import-branch)).

## 6. First release and core handshake (WP08)

After the import PR is merged:

1. **[operator]** Approve the version, then cut `service-v0.1.0-rc.1` (or the version the
   operator names) as [service protocol and releases](./service-protocol-and-releases.md#cut-a-release)
   describes.
2. Verify every asset (`sha256sum --check --strict SHA256SUMS`,
   `gh attestation verify <asset> --repo spec-kitty/spec-kitty-events --signer-workflow spec-kitty/spec-kitty-events/.github/workflows/service-release.yml --source-ref refs/tags/<tag>`) and record the
   native binary sizes.
3. Send core's session the tag, the asset names and their sha256 sums. Core pins them.

## 7. Issue moves

**[operator]** Every move below needs the operator's approval first. Transferred issues get
new numbers here; record the old-to-new map in the PR.

| Core # | Method | Events side | Core side keeps |
|---|---|---|---|
| 6117 (epic) | transfer | epic | — |
| 6194, 6196, 6197, 6198, 6199 | transfer, together with 6117 | children | — |
| 6409, 6268 | transfer | issues | — |
| 6195 | split | Java cut-over half | binding the CLI client, deleting the Python appenders |
| 6676 | split | native build of the `command-cli` binary the launcher starts | CLI launcher, fail-closed codes |
| 5528 | split | read-API service/contract work | dashboard consumers |
| 957 | split | Java route | Python oracle |
| 6609 | split | Java reducer ordering | Python CLI reducer |
| 6692 | close as done, link ADR 0002 | — | — |
| 6608 | stays; link the moved issues | — | epic |

1. **[operator]** **Create the labels first.** For each issue to transfer, list its labels and create any
   that this repository lacks, with the same name and colour:

   ```bash
   gh api repos/spec-kitty/spec-kitty/issues/6117 --jq '.labels[] | [.name, .color] | @tsv'
   gh api repos/spec-kitty/spec-kitty-events/labels -f name='<name>' -f color='<color>'
   ```

2. **[operator]** **Transfer the epic and its children together**, in one sitting, so the sub-issue links
   survive. On exe.dev VMs use `gh api`:

   ```bash
   repo_id="$(gh api repos/spec-kitty/spec-kitty-events --jq .node_id)"
   for n in 6117 6194 6196 6197 6198 6199 6409 6268; do
     issue_id="$(gh api "repos/spec-kitty/spec-kitty/issues/$n" --jq .node_id)"
     gh api graphql -f query='mutation($i:ID!,$r:ID!){transferIssue(input:{issueId:$i,repositoryId:$r}){issue{number url}}}' \
       -f i="$issue_id" -f r="$repo_id"
   done
   ```

   Then check the epic's sub-issues here (`gh api repos/spec-kitty/spec-kitty-events/issues/<new epic>/sub_issues`)
   list the five children.
3. **[operator]** **Split the rest.** For each split row, file an events issue and leave the
   core issue open with the core half:
   - **Title:** `[from spec-kitty#<n>] <the events half, as in the table>`
   - **Body:** first line `Split from spec-kitty/spec-kitty#<n> at the services cutover
     (ADR 0002).`, then the events half of the work, and what stays in core.
   - **Forward link:** comment on the core issue: `The <events half> moved to
     spec-kitty/spec-kitty-events#<new>; this issue keeps <core half>.`
4. **[operator]** **#6692** is closed by the core session, as completed, with a link to
   [ADR-0002](../adr/0002-events-domain-owns-its-services.md).
5. **[operator]** **#6608** stays in core; comment on it with the list of moved and split issues.

## 8. Rollback

Rollback never touches `MISSION_BRANCH` (`issue-147-events-services-home`): it is not
deleted, reset or force-pushed. Nothing is force-pushed anywhere.

- **Before the import PR merges:** **[operator]** close the import PR and delete only
  `IMPORT_BRANCH`. Closing the PR and deleting the remote branch change GitHub, and after
  step 4 the branch also holds the WP07 commits, so the operator approves this first:

  ```bash
  git switch issue-147-events-services-home            # or main; anything but the import branch
  git branch -D issue-147-service-import
  git push origin --delete issue-147-service-import    # [operator]; only if it was pushed; a delete, not a force-push
  ```

  Nothing reached `main`. To try again, cut `IMPORT_BRANCH` afresh from `PRE_IMPORT`
  (`git switch -c issue-147-service-import "$PRE_IMPORT"`) and redo step 3.
- **After it merges:** revert the merge commit on a new branch
  (`git revert -m 1 <merge sha>`). **[operator]** Pushing that branch and opening its PR need
  the operator's approval, and **the operator merges the revert PR** after review. **Never
  force-push `main`.** A published `service-v*` release is not deleted. **[operator]** Editing its
  notes to mark it withdrawn is a GitHub write; then tell core to keep its old pin.
- **Re-importing after that revert:** merging the same extraction again is a no-op
  (`Already up to date.`): its commits are already in `main`'s history, so `service/` stays
  deleted. A fresh filter-repo run gives the same rewritten commits (it is deterministic: the
  dry runs both ended at `95e201f`), so it does not help on its own either. Instead **revert
  the revert** (`git revert <revert sha>`) on a new branch. **[operator]** Pushing it and
  opening its PR need the operator's approval, and the operator merges that PR after review. If core has moved
  since, then merge a fresh extraction from the new `FROZEN_SHA` on top: only its newer commits
  come in.

## 9. If the freeze is breached

If core commits to any of `IMPORTED_PATHS` after `FROZEN_SHA` (step 1.7 or the last gate in
step 5 prints a line), the import is stale.

- **Before the import PR merges:** take the new SHA from core and repeat from step 1 (both
  verifications, with the object ids core's gate now pins). Then **[operator]** close the import PR and delete
  `IMPORT_BRANCH` as in [section 8](#8-rollback) (the operator approves both), cut it afresh from `PRE_IMPORT` and redo steps 2 and 3 on it.
  `MISSION_BRANCH` is untouched. **Never cherry-pick** core commits across: they would carry no
  link to their history.
- **After it merges:** this repository is canonical. Ask core for the change and re-make it
  here on a new branch. **[operator]** Pushing it and opening its PR need the operator's
  approval, and the operator merges it after review. Again, no cherry-pick.
