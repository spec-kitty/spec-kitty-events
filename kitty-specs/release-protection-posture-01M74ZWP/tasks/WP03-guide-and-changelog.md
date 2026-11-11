---
work_package_id: WP03
title: 'Release guide: vector table, sign-offs, operator how-to; changelog'
dependencies:
- WP02
requirement_refs:
- FR-012
- FR-013
- FR-014
- SC-004
- C-002
- NFR-003
planning_base_branch: issue-239-release-protection
merge_target_branch: issue-239-release-protection
branch_strategy: Planning artifacts for this mission were generated on issue-239-release-protection. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into issue-239-release-protection unless the human explicitly redirects the landing branch.
subtasks:
- T010
- T011
- T012
- T013
history:
- timestamp: '2026-11-11T11:35:00Z'
  lane: planned
  agent: system
  action: Prompt generated via /spec-kitty.tasks
agent_profile: scribe-sally
authoritative_surface: docs/guides/service-protocol-and-releases.md
create_intent:
- docs/changelog/unreleased/239-release-protection-audit.md
execution_mode: planning_artifact
model: haiku
owned_files:
- docs/guides/service-protocol-and-releases.md
- docs/changelog/unreleased/239-release-protection-audit.md
role: implementer
tags: []
tracker_refs: []
---
# Work Package Prompt: WP03 — Release guide: vector table, sign-offs, operator how-to; changelog

## ⚡ Do This First: Load Agent Profile

Load your assigned profile before reading anything else: `/spk-charter-profile-load scribe-sally`
(or `spec-kitty agent profile show scribe-sally`). Adopt its identity, scope and boundaries.

## Objective

Record the release-protection posture in `docs/guides/service-protocol-and-releases.md` (#239 AC 4; FR-012–FR-014, SC-004) and add the changelog fragment. Plain, short sentences; the guide's existing voice (read the whole "Releases" part first, lines 121–304).

Read first: `spec.md` (Maintainer Rulings D5–D8, FR-012–FR-014), `research.md` §1, §5, §8, `quickstart.md`, the WP01 script's docstring and the WP02 workflow header.

## Branch Strategy

Planning base and merge target: `issue-239-release-protection` (single_branch). One PR to `main`. Run `spec-kitty agent action implement WP03 --agent <name> --mission release-protection-posture-01M74ZWP`.

## Subtasks

### T010 — "Release-protection posture" section with the vector table

Add `### Release-protection posture` directly **after** `### Release protection` (ends with the three read-only `gh api` commands) and before `### What CI does not gate`.

Open with two sentences: the environment guards only `publish`; this section lists every other path to a `service-v*` release and what covers it, and the audit (`release-protection-audit.yml`, daily, read-only) is the compensating control.

Then one table, columns **Vector · Control · Status · Check it**, with exactly these rows (one per vector, SC-004):

1. A workflow on any pushed branch requests `contents: write` and runs `gh release create` on an existing `service-v*` tag (#152, #221). Control: the audit checks every `service-v*` release's author and verifies every non-checksum asset's attestation against `service-release.yml` at `refs/tags/<tag>`; a final release that fails turns the run red. Consumers: verify downloads with the step 5 command ([Cut a release](#cut-a-release)), and core pins by sha256. Status: **detected, not prevented** (GitHub has no rule that restricts who creates a release).
2. Settings drift: someone loosens the `service-release` environment or changes, disables, adds or deletes a ruleset. Control: the audit compares the live settings with `scripts/release/release-protection-expected.json`. Status: controlled.
3. A `service-v*` tag moved or deleted after release. Control: the `service-release-tags` ruleset (item 2 above) plus `publish`'s re-check that the tag still names the built commit. Status: controlled.
4. The `main` ruleset needs 0 approving reviews and `stijn-dejongh` can bypass it, so the `gate`'s ancestor-of-`main` check is only as strong as what reaches `main`. Status: **accepted risk, D5**: signed off by `stijn-dejongh` on 2026-11-09 (#239); compensating control: the audit; revisit when a second maintainer exists.
5. The PR-lane canary (`ci-pr-lane-canary.yml`) mints a `spec-kitty-factory-ci` App token for every repository the org-wide installation reaches. Status: **accepted risk, D7**: signed off by `stijn-dejongh` on 2026-11-11 (#239); the workflow stays as is; revisit when the App installation is narrowed or the canary moves to the planning repo.
6. The rulesets' bypass lists: the audit's read-only token cannot read them (`bypass_actors` comes back null), so adding a bypass actor is not detected. Status: **accepted risk, D6**: signed off by `stijn-dejongh` on 2026-11-11 (#239). Check it by hand with an admin login: `gh api repos/spec-kitty/spec-kitty-events/rulesets/<id> --jq '.bypass_actors'` for `26558116` and `26752412`; expected: `main` → the user `stijn-dejongh` (always); `service-release-tags` → the `admins` team and `stijn-dejongh` (always). Revisit if GitHub exposes the lists to read tokens.

Below the table, one short paragraph: a final release flagged by the audit is untrusted: do not pin it, and investigate how it was created. A prerelease is checked the same way but only warns (D8). A rehearsal publishes nothing.

### T011 — Run, read and update the audit; cross-references; frontmatter

Under the table add `#### Run and read the audit` with, from `quickstart.md`:
- how to run it by hand (Actions → release-protection-audit → Run workflow, or `gh workflow run release-protection-audit.yml`), and when: after changing a setting, after publishing a release;
- the exit codes (0 / 1 / 2) and what a `FAIL settings …` line and a `FAIL release …` line mean;
- **a deliberate settings change lands with a reviewed PR that updates `scripts/release/release-protection-expected.json`**; the audit is red until it merges, by design.

Edit the existing text too:
- In `### Release protection`, after "The environment guards only `publish`: another workflow … is not held by it (#221).", add: "See [Release-protection posture](#release-protection-posture)."
- In step 5 of `### Cut a release`, add a short sentence: run the audit by hand after publishing (it must stay green).
- Frontmatter `updated:` → `2026-11-11T00:00:00Z`. Keep `description` ≤ 180 characters (unchanged is fine).

### T012 — Changelog fragment [P]

`docs/changelog/unreleased/239-release-protection-audit.md` (format: `docs/changelog/unreleased/README.md`):

```markdown
---
section: Security
---

- A daily, read-only release-protection audit (`.github/workflows/release-protection-audit.yml`, #239, closes #221) compares the live `service-release` environment and rulesets with `scripts/release/release-protection-expected.json` and checks that every `service-v*` release was authored by `github-actions[bot]` and that each asset's provenance attestation was signed by `service-release.yml` at its own tag. A final release that fails turns the run red; a prerelease only warns.
  - The release guide (`docs/guides/service-protocol-and-releases.md`, "Release-protection posture") maps each release vector to its control or to an accepted risk signed off by the maintainer: the `main` ruleset's approval and bypass policy (D5), the rulesets' bypass lists the read-only token cannot see (D6), and the PR-lane canary's org-wide App token (D7).
```

Links relative to the repository root, no `../`.

### T013 — Gates

```bash
uv run python scripts/changelog.py check && uv run python scripts/changelog.py guard
cd site && npm ci && npm run validate; cd ..
python3 scripts/docs_index.py --clean     # ALWAYS afterwards; generated index edits must not be committed
git status --short                        # only your two owned files may be changed
```

If `npm ci` cannot reach the registry, say so in the handoff and the reviewer runs it. Record commands and outcomes.

## Definition of Done

- The section, the six rows, three accepted risks each with name, date, ruling and revisit condition.
- Cross-references and `updated` done; changelog fragment passes `check` and `guard`; docs validate passes (or the blocker is recorded).
- No generated index changes left in the working tree.
- `spec-kitty agent tasks mark-status T010 T011 T012 T013 --status done --mission release-protection-posture-01M74ZWP`.

## Reviewer guidance

Every #221 bullet maps to a row (branch workflow → 1; canary → 5; `main` ruleset → 4; live drift → 2), plus the bypass gap (6) and the tag row (3). No setting values are copied that the expected file already holds, except the bypass lists, which the file cannot hold.
