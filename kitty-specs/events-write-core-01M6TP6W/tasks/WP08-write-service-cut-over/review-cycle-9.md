---
affected_files: []
cycle_number: 9
mission_slug: events-write-core-01M6TP6W
reproduction_command:
reviewed_at: '2026-11-08T19:58:36Z'
reviewer_agent: claude
wp_id: WP08
---

# WP08 review cycle 9: REJECT (Opus; git 2.43.0)

All six cycle-8 items are fixed, and both departures are judged right: continuation lines parsed as git does, and the decimal-length check kept. R29/R30 show 0 fail-open over 100,531 static rows plus 16 e2e probes. The DoD holds on paper; benchmark p95 is 104.8 ms. The rejection is `GitConfig`. It is a faithful parser port, but git refuses, or calls the repository bare, in five classes the port never sees. That gives 70 fail-open configs, 8 confirmed e2e, plus one realistic layout git creates itself. Review: `/tmp/claude-1000/wp08-diff/cycle9/review/REVIEW.md`. Probes beside it: `gc_targeted.py`, `gc_defaults.py`, `gc_fuzz.py` (config differentials vs git), `e2e/c9r_root.py`, `e2e/c8r_merged.py`, `gen_hunt9.py`, `rev/`.

## Orchestrator ruling R31 (research §11 decision 21, commit `0ed44af1`): RootCheckout asks git
Replace the config-parser port with a git subprocess, once at startup, exactly as core resolves its root.
- **Keep the structural checks:** the gcd's real path is named `.git`, and the parent's `.git` resolves to the same real path.
- **Run git with a cleared environment:** run `git rev-parse --git-common-dir --is-bare-repository --show-toplevel` (or separate calls) with the working directory set to the gcd's parent, every `GIT_*` variable removed from the child environment, a bounded timeout, and stdout/stderr captured (never logged with paths into a response).
- **Verified only when ALL hold:**
  - git exits 0;
  - `--is-bare-repository` prints `false`;
  - `--show-toplevel`'s real path equals the gcd's parent's real path;
  - `--git-common-dir`, resolved against the parent, has the same real path as the gcd.
- **Fail closed on anything else:** a missing git binary, a non-zero exit, a timeout, unparseable output, an interrupted or killed process, or any exception or Error. Every write then answers `422 WRITE_LOCATION_UNVERIFIED`. Never guess.
- **Delete the port:** `GitConfig` and the config parsing in `RootCheckout`, plus their tests that only exercised the port. Keep tests that pin outcomes, rewritten to build real repositories with `git` (`git init`, `git init --bare`, `git worktree add`, `git sparse-checkout init` in a worktree of a bare clone, `git config core.bare true`, an include to a broken file, a bad extension value, `filemode = flase`, `core.worktree` empty) and to assert the outcome git implies.
- **Native image:** `ProcessBuilder` works in a native image. Check `CommandCliHints` or reachability for anything the native legs would need (nothing is expected). Make sure `NativeSmokeTest` still performs a locked write in a git-initialized repository.
- Real git is already required by the tests (`git init`). CI legs have git.

## Findings to address
1. [MAJOR] `config.worktree` is never read. This is closed by R31; add a test: a bare clone at `proj/.git`, a worktree, `git sparse-checkout init` in it → 422.
2. [MAJOR] Configs git refuses still verify. This is closed by R31; add tests for each class: a bad include, an include cycle, a bad `objectFormat`, `worktreeConfig = maybe`, an empty `core.worktree`, an unknown extension, and `filemode = flase`.
3. [MINOR] Residue wording (absent or negative `repositoryformatversion`; no `[core]` section). This becomes moot under R31, since git decides. Make sure the outcome matches git in tests.
4. [MINOR] Environment-level refusals: recorded in R31.

Re-run the reviewer's `gc_targeted.py`, `gc_defaults.py`, `gc_fuzz.py` and `e2e/c9r_root.py` against the rebuilt service. Expect 0 fail-open, and every fail-closed row explained: structural checks, or a timeout or missing git.
