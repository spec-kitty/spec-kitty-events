---
affected_files: []
cycle_number: 1
mission_slug: events-domain-services-home-01M64MSB
reproduction_command:
reviewed_at: '2026-10-29T22:12:02Z'
reviewer_agent: claude
wp_id: WP06
---

# WP06 review feedback 1 (reviewer-renata)

Verdict: **changes requested**. The guides, the CHANGELOG and the indexes are accurate, and the
extraction re-run is exact. Three runbook defects block approval, because the runbook is executed
at cutover.

What was re-verified:
- Steps 2.1 to 2.5 run as written in a fresh scratch dir at core `4eecb124`: shallow, then unshallowed, then
  141 commits, 0 merges, 1904 files, tip `95e201f`. `service`, all nine contracts paths and the doc are clean.
- Step 3 run against a scratch clone of this branch: exactly one conflict
  (`CONFLICT (add/add)` in `contracts/README.md`). `HEAD^2` has 141 commits, the merged `service/` is clean, and
  `--follow` reaches 2026-10-13.
- The `FROZEN_TREE` check is correct: `4eecb124:services` is `99c06d1d…`, which matches
  `_FROZEN_TREE_ID` in core PR #6782's `tests/ci/test_services_frozen.py`.
- Facts checked against their sources: the R-1 line numbers, the `PythonPeer` path, the service
  `settings.gradle.kts` includes, the Gradle 9.7.1 and 8.14.5 pins, the jobs and env vars in `service.yml`, the gates
  in `service-release.yml`, the ADR 0003 asset table, the fixture staging in
  `service-release-assets.sh`, the `mission-status.*` keys and defaults, the routes, the 29
  `WriteRefusalCode` values, the four `WRITE_SERVICE_ENDPOINT_*` codes, the check order, the
  `install_tools.py` arguments and the `transferIssue` mutation shape.
- validate-frontmatter: 0 errors. check-links: 147 references resolve. ruff passes. Only owned files changed.

## [MAJOR] 1. Nothing detects a freeze breach on the imported paths outside `services/`

Core's freeze gate pins only `services/` (`_FROZEN_PATH = "services"`). ADR-0002 line 188 says
`services/mission-status-api/` **and the service contracts** are frozen. The runbook imports
`contracts/{mission-status,mission-write,_shared,tools,gradle,lint,README.md,build.gradle,settings.gradle}`
and `docs/architecture/event-ledger-writers-and-readers.md`. No gate in core covers those paths, and no step in
the runbook checks them. Section 9 tells the operator what to do after a breach, but no step
would ever detect one. A core commit to `contracts/mission-write/` after `FROZEN_SHA` would
be lost without anyone noticing.

Fix: add a check that a core commit touching any imported path since `FROZEN_SHA` is a breach.
Run it in step 1 and again as the last gate before the operator merges the cutover PR, for example:
```bash
git -C "$CORE" fetch origin main
git -C "$CORE" log --oneline "$FROZEN_SHA"..origin/main -- services/mission-status-api \
  contracts/mission-status contracts/mission-write contracts/_shared contracts/tools contracts/gradle \
  contracts/lint contracts/README.md contracts/build.gradle contracts/settings.gradle \
  docs/architecture/event-ledger-writers-and-readers.md      # expected: no output; any line → section 9
```

## [MAJOR] 2. Rollback and freeze-breach handling delete the mission branch

The names table makes `EVENTS` "the cutover branch cut from `main` (`issue-147-events-services-home`)".
Sections 8 and 9 then say to "close the PR and delete the cutover branch" and to "start again… on a
new branch cut from `main`". `issue-147-events-services-home` is this mission's branch. It carries
WP01 to WP06 and the spec-kitty status log, and WP07 says the import may be a separate PR. If
the pre-import work is not on `main` yet, following the rollback literally destroys the work
and the status history. The runbook also forbids force-pushing anything shared, so it cannot
reset the pushed branch either.

Fix: before step 3, record `PRE_IMPORT=$(git rev-parse HEAD)` and do the import on a dedicated
branch (for example `issue-147-services-import`, cut from the mission branch, or from `main` once the mission PR
merges). Rollback before merge then means deleting only that dedicated branch, and redoing the
work from `PRE_IMPORT`. Alternatively, add a precondition that the mission's pre-import work is
already merged to `main`. Fix the names table to match.

## [MAJOR] 3. Step 5 `./gradlew --no-daemon verifyAll` fails without the contract bundle

After the root integration, `verifyAll` runs `:mission-status-api:check`, which depends on
`bundleContract`. That task needs `CONTRACT_BUNDLE` and `CONTRACT_BUNDLE_SHA256`, or the matching
`-P` properties. `service/build.gradle.kts` lines 132 to 141, core's README ("The build needs the contract document"),
your own running-the-service.md ("Without either, `bundleContract` fails"), and `service.yml`'s
gradle job, which exports both variables, all say so. As written, the step 5 command fails. Add the
bundle step (`uv run python contracts/tools/bundle.py --root contracts --out "$out" --bundle-only`
and the two exports) and `LC_ALL=C.UTF-8` before `verifyAll`. Alternatively, state that WP07 T032 wires
the bundle into the root build and say how.

## Minor (do not block; fix while here or file)

- Step 1.5 runs `git -C "$CORE" fetch origin "$FROZEN_SHA"`, although the table says `CORE` is read-only.
  That fetch only lands in `FETCH_HEAD`, so the `--no-local` clone in step 2.1 does not receive
  `FROZEN_SHA` unless a `CORE` branch already contains it. When `CORE` is not shallow, `git checkout -B extract
  "$FROZEN_SHA"` then fails. Fetch the SHA into the scratch clone instead
  (`git fetch https://github.com/spec-kitty/spec-kitty "$FROZEN_SHA"`).
- Step 2.1 `git branch -D main` assumes `CORE`'s HEAD is `main`. filter-repo recreates `main`
  from `origin/main` anyway; my re-run shows `* extract` and `main` afterwards. The step is
  harmless but does not do what its comment says.
- Step 3.2 gives the message but no command (`git commit -F <file>`).
- After a post-merge revert (section 8), re-running the import merges the same filter-repo commits
  again, which is a no-op, so `service/` stays deleted. Say "revert the revert", or note it.
- service-protocol-and-releases.md: the client also refuses the endpoint **directory** when it has
  group or other bits (`_refuse_exposed` checks `directory_stat`). Mention it.
- Changelog page: "`service-release.yml` stay[s] inert". It fails loudly on a tag, as CHANGELOG.md
  says correctly. Align the wording.
- CHANGELOG omits `tests/test_service_workflows.py` and the AGENTS.md, .gitattributes and .dockerignore edits.
  This is optional.

## Anti-pattern checklist
1 Dead code: N/A (docs). 2 Synthetic fixture: N/A. 3 Silent empty return: N/A. 4 FR coverage:
FR-007 and FR-009 are covered by docs; FR-009's runbook is incomplete (see the MAJORs). 5 Frozen surface: PASS.
6 Locked decision: PASS (no `main` push, tag and issue moves gated on the operator). 7 Shared-file
ownership: PASS (only the 7 owned files). 8 Production fragility: N/A.
