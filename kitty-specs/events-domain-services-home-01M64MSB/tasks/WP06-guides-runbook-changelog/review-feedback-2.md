# WP06 review feedback 2 (reviewer-renata)

Verdict: **changes requested**, for one small [MAJOR] item. Everything else from cycle 1 is resolved and
re-verified. The fix is a few words in sections 8 and 9 of the runbook.

## What was re-verified

- **MAJOR 1 (freeze breach detection): resolved.** `IMPORTED_PATHS` matches the filter-repo `--path` list.
  Step 1.7 and the last gate in step 5 run the check. Run in scratch against a local stand-in for GitHub: with
  `FROZEN_SHA` = `4eecb124`, steps 1.5 to 1.7 print `false`, `frozen sha on main: OK`, eleven `OK` lines,
  `frozen ids: OK`, and 0 breach lines. After a synthetic commit to `contracts/mission-write/` on the fake
  `main`, plus an unrelated commit, the check prints exactly the breach commit and not the unrelated one.
- **The 11-row object-id table**: every id equals `git -C core rev-parse 4eecb124:<path>`, and the table and the
  heredoc agree.
- **MAJOR 2 (mission branch): resolved.** `MISSION_BRANCH` is never deleted, reset or force-pushed, and
  the banner, the names table and section 8 all say so. The import runs on `issue-147-service-import`.
  `PRE_IMPORT` is recorded before the merge. Rollback and the section 9 restart delete only `IMPORT_BRANCH`.
- **MAJOR 3 (contract bundle): resolved.** The commands match `service.yml` (`contract-bundle` job, line 132;
  `gradle` job, lines 172 to 176) and core `build.gradle.kts` lines 131 to 134: `CONTRACT_BUNDLE` /
  `CONTRACT_BUNDLE_SHA256`, `-PcontractBundle` / `-PcontractBundleSha256`, and
  `bundle/mission-status/openapi.yaml`. `LC_ALL=C.UTF-8` is set.
- **Minors:** all fixed. The watch clone replaces the fetch into `CORE`. `git branch -D main` is gone.
  `git commit -F` was added. Revert-the-revert is explained correctly: re-merging is
  `Already up to date.`, filter-repo is deterministic, and a newer extraction brings only newer commits.
  The endpoint-directory text matches core `_refuse_exposed`: the file and directory share the `0o077` mask,
  and the mode and uid checks return early on Windows. The changelog page's wording about inert jobs is
  fixed. CHANGELOG lists the test file, AGENTS.md, .gitattributes and .dockerignore.
- **`--no-tags` is correct and needed.** Filter-repo keeps core tags `v4.0.0rc6` and `v4.0.0rc7` in
  `core-extract`, and both are ancestors of `extract`. A plain `git fetch core-extract` pulls both into the
  events clone. `git fetch --no-tags core-extract extract` pulls none. Step 3 still gives exactly one conflict,
  `CONFLICT (add/add)` in `contracts/README.md`. The extract tip is `95e201f`: 141 commits, 0 merges, 1904 files.
- validate-frontmatter: 56 files valid, 0 errors. check-links: 148 references resolve. `ruff check` and
  `ruff format --check` pass. Scope since `10319f0`: only the 6 owned docs, plus the status files.

## [MAJOR] 1. The rollback's GitHub mutations are not marked [operator]

The banner says only steps marked **[operator]** need a separate approval, so unmarked steps may run
under the general go-signal. Section 8, "Before the import PR merges", has two GitHub mutations with no
marker: closing the import PR and `git push origin --delete issue-147-service-import`. Section 9 runs both
again ("delete `IMPORT_BRANCH` as in section 8"). After step 4, `IMPORT_BRANCH` also holds the WP07
post-import commits, so an agent following the runbook could close the PR and delete those commits on
GitHub without approval. The tag (6.1) and the issue moves and labels (section 7) are marked. Branch
deletion is the one GitHub mutation left unmarked.

Fix: mark the pre-merge rollback bullet (closing the PR and deleting the remote branch) **[operator]**,
and the "delete `IMPORT_BRANCH`" step in section 9. Also mark the post-merge revert PR **[operator]**, or
say plainly that the operator merges it.

## Minor (do not block; fix while here or file)

- Step 3 `git switch -c issue-147-service-import origin/main` sets upstream to `origin/main`.
  Confirmed in scratch: `@{u}` = `origin/main`. Under the default `push.default=simple`, a bare `git push`
  refuses. Under `upstream` or `tracking`, it would push the import to `main`. Add `--no-track`, and in
  step 5 write the push as `git push -u origin issue-147-service-import`.
- Precondition 1.3 says core's freeze gate is "extended for this cutover" to every imported path. The
  local `pr/6782/head` (`087883c8d`) still pins only `_FROZEN_PATH = "services"`. Under "Stop unless every
  line holds", that blocks the cutover until core lands the extension. Make sure core's session knows this,
  or reword 1.3 so the events-side checks 1.6 and 1.7 are the binding line.
- CHANGELOG does not mention the `.gitignore` change (Gradle build output in place of `java/target/`).
  This is optional.
