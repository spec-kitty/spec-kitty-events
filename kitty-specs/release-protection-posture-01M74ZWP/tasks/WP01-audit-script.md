---
work_package_id: WP01
title: 'Audit script: settings drift and release provenance'
dependencies: []
requirement_refs:
- FR-004
- FR-005
- FR-006
- FR-007
- FR-008
- FR-009
- FR-010
- FR-011
- NFR-002
- NFR-004
- C-004
- C-001
- SC-001
- SC-002
- SC-003
planning_base_branch: issue-239-release-protection
merge_target_branch: issue-239-release-protection
branch_strategy: Planning artifacts for this mission were generated on issue-239-release-protection. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into issue-239-release-protection unless the human explicitly redirects the landing branch.
subtasks:
- T001
- T002
- T003
- T004
- T005
- T006
history:
- timestamp: '2026-11-11T11:35:00Z'
  lane: planned
  agent: system
  action: Prompt generated via /spec-kitty.tasks
agent_profile: python-pedro
authoritative_surface: scripts/release/
create_intent:
- scripts/release/release_protection_audit.py
- scripts/release/release-protection-expected.json
- tests/test_release_protection_audit.py
- tests/fixtures/release_protection/README.md
execution_mode: code_change
model: sonnet
owned_files:
- scripts/release/release_protection_audit.py
- scripts/release/release-protection-expected.json
- tests/test_release_protection_audit.py
- tests/fixtures/release_protection/**
role: implementer
tags: []
tracker_refs: []
---
# Work Package Prompt: WP01 — Audit script: settings drift and release provenance

## ⚡ Do This First: Load Agent Profile

Load your assigned profile before reading anything else: `/spk-charter-profile-load python-pedro`
(or `spec-kitty agent profile show python-pedro`). Adopt its identity, scope and boundaries.

## Objective

Write the engine of the release-protection audit (#239, closes #221):

- `scripts/release/release_protection_audit.py`: a **stdlib-only**, Python 3.10-compatible, `mypy --strict`-clean script that
  1. reads the live release-protection settings through `gh api`, normalises them and diffs them against
  2. `scripts/release/release-protection-expected.json` (committed), and
  3. checks every `service-v*` release's provenance (author + per-asset attestation identity);
- `tests/test_release_protection_audit.py` with fixtures under `tests/fixtures/release_protection/`.

The workflow that runs it is WP02. The docs are WP03. Do **not** create or edit any workflow, docs or other test file.

Read first: `kitty-specs/release-protection-posture-01M74ZWP/spec.md` (FR-004–FR-011, Edge Cases), `research.md` §1–§4, `data-model.md`, `contracts/audit-cli-and-expected-settings.md`.

## Branch Strategy

Planning base: `issue-239-release-protection`. Merge target: `issue-239-release-protection` (single_branch topology). The branch goes to `main` as one PR. The execution workspace is the one `lanes.json` assigns. Run `spec-kitty agent action implement WP01 --agent <name> --mission release-protection-posture-01M74ZWP`.

## Subtasks

### T001 — Capture the live settings and releases as test fixtures

**Purpose:** tests must run offline against what GitHub actually returns.

Raw responses captured on 2026-11-11 are in `/tmp/claude-0/cap/` (outside the repository; read-only source):

| File | Endpoint |
|---|---|
| `environments.json` | `GET /repos/{r}/environments` |
| `env_service_release.json` | `GET /repos/{r}/environments/service-release` (reviewer objects lightly trimmed) |
| `deployment_branch_policies.json` | `GET /repos/{r}/environments/service-release/deployment-branch-policies` |
| `rulesets_list.json` | `GET /repos/{r}/rulesets` |
| `rs_main.json`, `rs_tags.json` | `GET /repos/{r}/rulesets/{id}` as a **read-only token** sees them (`bypass_actors: null`) |
| `releases.json` | `GET /repos/{r}/releases?per_page=100` |

Steps:
1. Copy the settings files into `tests/fixtures/release_protection/live/` with the same names.
2. Reduce `releases.json` to `tests/fixtures/release_protection/live/releases.json`: keep `tag_name`, `prerelease`, `draft`, `author.login`, and per asset `name`, `size`, `digest`, `uploader.login`. Drop `body`, URLs, ids and every other field (the script must not depend on them). Keep all four releases (two `service-v0.1.0-rc.*`, two package `v11.*`).
3. Write `tests/fixtures/release_protection/README.md`: where each file came from, the date, that the settings are what a `contents: read` `GITHUB_TOKEN` sees (spike runs 42997528378 / 42997732131), and how to refresh them (`gh api …` commands, removing `bypass_actors` if an admin token returns it).

**Validation:** every fixture is valid JSON; no token or secret in any file.

### T002 — Normalise live settings; diff; commit the expected file

**Purpose:** FR-004, FR-005, NFR-002.

Implement pure functions (no I/O) in the script:

```python
def normalise_settings(
    repository: str,
    environments: dict[str, Any],            # GET /environments
    service_release: dict[str, Any],         # GET /environments/service-release
    branch_policies: dict[str, Any],         # GET …/deployment-branch-policies
    rulesets: list[dict[str, Any]],          # one GET /rulesets/{id} per ruleset in the list
) -> dict[str, Any]: ...

def diff_settings(expected: dict[str, Any], live: dict[str, Any]) -> list[Finding]: ...
```

Normalised shape: exactly as `data-model.md` shows. Rules:
- `environments`: sorted list of names.
- `service_release.reviewers`: sorted `"User:<login>"` / `"Team:<slug>"`; `prevent_self_review` from the `required_reviewers` rule (false if absent); `protection_rules`: sorted rule `type`s; `wait_timer`: the `wait_timer` rule's `wait_timer` value, else 0; `can_admins_bypass`; `deployment_branch_policy` as returned (null stays null); `deployment_policies`: sorted `[{name, type}]`.
- `rulesets`: dict keyed by `name`; each value `{target, enforcement, conditions, rules}`. Rules sorted by `type` then canonical JSON; every list nested in `conditions` and rule `parameters` sorted by canonical JSON text (`json.dumps(x, sort_keys=True)`). **Never** record `bypass_actors`, `current_user_can_bypass`, `id`, `node_id`, `source`, `source_type`, `_links`, timestamps (D6).
- Unknown extra fields inside a rule's `parameters` are kept (fail-closed drift; spec Edge Cases).

`diff_settings` walks both trees:
- dict: a key only in expected → failure `"<path>: missing live"`; only in live → failure `"<path>: unexpected live value <v>"`; both → recurse.
- list or scalar: compare whole values; unequal → failure `"<path>: expected <e>, live <l>"`.
- paths are dotted (`service_release.reviewers`, `rulesets.main.rules`); output sorted by path. Same input ⇒ same output (NFR-002).

`Finding` (a frozen dataclass): `severity` (`"failure"`/`"warning"`), `kind` (`"settings"`/`"release"`), `subject`, `message`.

Create `scripts/release/release-protection-expected.json` by running `normalise_settings` on the T001 fixtures and writing it with `json.dumps(…, indent=2, sort_keys=True) + "\n"`. Do **not** hand-edit values. Read it: it must say `required_approving_review_count: 0`, the four required checks, `can_admins_bypass: false`, reviewers `Team:admins` and `User:stijn-dejongh`, `prevent_self_review: true`, tag policy `service-v*`.

### T003 — Release classification and provenance checks

**Purpose:** FR-007, FR-008, FR-009 (pure logic, injected verifier).

```python
SERVICE_TAG = re.compile(r"^service-v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$")   # same as service-release.yml gate
BOT = "github-actions[bot]"

class AssetVerdict(NamedTuple):
    ok: bool
    reason: str          # empty when ok

AssetVerifier = Callable[[str, dict[str, Any]], AssetVerdict]   # (tag, asset) -> verdict; raises AuditError on infrastructure faults

def audit_releases(releases: list[dict[str, Any]], verify: AssetVerifier) -> list[Finding]: ...
```

Rules (research.md §3, data-model.md "Release classification"):
- Tag not starting with `service-v` → ignored entirely (do not call `verify`).
- Starts with `service-v` but does not match `SERVICE_TAG` → **failure** (outside the release line), regardless of anything else.
- Prerelease **iff** the tag's version has a `-` suffix (the `gate` rule), not the `prerelease` flag. Severity for every fault below: `failure` for a final, `warning` for a prerelease.
- `author.login != BOT` → fault `"<tag>: authored by <login>, not github-actions[bot]"`.
- no assets → fault `"<tag>: no assets"`.
- for each asset whose name is not `SHA256SUMS` and does not end in `.sha256`: `verify(tag, asset)`; `not ok` → fault `"<tag>/<asset>: <reason>"`. Checksum assets are never passed to `verify`.
- Process releases sorted by tag, assets by name (deterministic).

### T004 — `gh` adapter, errors, CLI, step summary

**Purpose:** FR-005 (live read), FR-007 (production verifier), FR-010, FR-011, NFR-004.

- `class AuditError(Exception)`: infrastructure faults. `main()` catches it, prints `ERROR <message>`, exits **2**.
- A runner seam so tests never spawn `gh`: `Runner = Callable[[list[str]], subprocess.CompletedProcess[str]]`, default runs `subprocess.run(args, capture_output=True, text=True, check=False)`.
- `gh_json(runner, path)`: `gh api --paginate --slurp <path>` for list endpoints (flatten the slurped pages: for `/releases` and `/rulesets` each page is a list; for `/environments` and `…/deployment-branch-policies` each page is an object, so merge their `environments` / `branch_policies` arrays), plain `gh api <path>` for single objects. Non-zero exit or invalid JSON → `AuditError` with the path and stderr (never the token). Check `gh api --paginate --slurp` exists in the runner's gh (≥ 2.48); it does in gh 2.89.
- `read_live_settings(runner, repo)`: list rulesets, then GET each by id; GET environments, `environments/service-release`, `…/deployment-branch-policies`; return `normalise_settings(...)`.
- `gh_asset_verifier(runner, repo, workdir) -> AssetVerifier`: for one asset:
  1. `gh release download <tag> --repo <repo> --pattern <name> --dir <workdir> --clobber`; failure → `AuditError` (infrastructure, even for a prerelease);
  2. sha256 of the file (streamed, 1 MiB chunks); if the API `digest` (`sha256:<hex>`) is present and differs → `AssetVerdict(False, "digest mismatch: api <a>, file <b>")`;
  3. `gh attestation verify <file> --repo <repo> --signer-workflow <repo>/.github/workflows/service-release.yml --source-ref refs/tags/<tag> --deny-self-hosted-runners`; non-zero → `AssetVerdict(False, "attestation verification failed: <last stderr line>")`;
  4. **always** delete the file (`finally`), so peak disk is one asset (NFR-004).
- CLI (`argparse`): `--repo` (required), `--expected` (required path), `--summary` (optional path; append Markdown), `--skip-settings`, `--skip-releases`. Flow: load expected → (settings) diff → (releases) list releases → `audit_releases` with the gh verifier in a `tempfile.TemporaryDirectory()` → print each finding as `FAIL|WARN <kind> <subject>: <message>` → summary → exit 1 if any failure else 0.
- Summary (FR-011): a `## Release-protection audit` heading, counts, then a bullet per finding; "No findings." when empty.
- `main(argv: list[str] | None = None, runner: Runner = _run) -> int`; `if __name__ == "__main__": sys.exit(main())`.
- Module docstring: purpose, D6 (no bypass lists), D8 (prerelease warnings), exit codes, "read-only: never writes to GitHub".

### T005 — Tests (`tests/test_release_protection_audit.py`)

Import the script like `tests/test_sonar_java_modules.py` does (`sys.path.insert(0, str(ROOT / "scripts" / "release"))`). Required tests (name them for what they prove):

1. **Snapshot matches**: `normalise_settings` on the fixtures == the committed expected file (positive control for 2).
2. **AC 2, every leaf**: enumerate every leaf path of the expected file (dict keys recursively; a list counts as one leaf, and also each scalar inside a list of scalars). For each, mutate a deep copy (flip a bool, change a string/number, drop one list element or append `"x"`), diff against the normalised fixtures and assert **exactly one** failure whose subject starts with that path. Parametrise or loop with clear failure messages; there are a few dozen leaves.
3. Live drift cases on a fixture copy: one reviewer removed; `prevent_self_review` false; `can_admins_bypass` true; tag policy changed to `*`; a ruleset's enforcement `evaluate`; a third ruleset added; a ruleset removed; a new environment; each gives ≥ 1 failure naming the right path.
4. Volatile fields ignored: change protection-rule ids, ruleset ids, node ids, reviewer avatar → no findings. Add `bypass_actors: [...]` (admin token) → no findings (D6).
5. **AC 3** with a fake verifier (records calls; returns ok unless told otherwise), on the `releases.json` fixture and on synthetic copies:
   - the two rcs as published → no findings; verifier called for every non-checksum asset and never for a checksum asset; package releases never reach the verifier;
   - final `service-v1.0.0` (rename an rc copy and strip its suffix) with author `someone` → one failure;
   - final with one asset verdict `not ok` → one failure naming the asset;
   - final with no assets → failure;
   - the same three on a prerelease → warnings, zero failures;
   - `service-vfoo` → failure;
   - the `prerelease` flag set on a final tag → still treated as final.
6. **Errors (FR-010)**: a runner whose `gh api` exits 1 → `main` returns 2 and prints `ERROR`; a download failure inside the verifier on a **prerelease** → `AuditError` → exit 2 (not a warning).
7. **Production verifier** with a fake runner and a temp dir: digest mismatch → not ok; attest exit 1 → not ok with reason; success → ok; the file is deleted in all three cases; the exact `gh attestation verify` argv includes `--signer-workflow spec-kitty/spec-kitty-events/.github/workflows/service-release.yml`, `--source-ref refs/tags/<tag>` and `--deny-self-hosted-runners`.
8. **CLI end-to-end through `main()`** with a fake runner that serves the fixtures by path (and `--paginate --slurp` shapes): exit 0, no `FAIL`; summary file written. Then the same with one fixture mutated → exit 1.
9. **Determinism**: two runs of `diff_settings` and `audit_releases` on the same input give identical lists.

Pair every "no findings" assertion with a positive control on the same fixture (tactic acceptance-criteria-non-vacuity).

### T006 — Lint and types

```bash
uv run ruff check scripts/release/release_protection_audit.py tests/test_release_protection_audit.py
uv run ruff format --check scripts/release/release_protection_audit.py tests/test_release_protection_audit.py
uv run mypy --strict scripts/release/release_protection_audit.py
uv run pytest tests/test_release_protection_audit.py -q
```

Record the commands and pass counts in your handoff note.

## Definition of Done

- All four owned paths exist; the expected file is generated, not hand-written.
- Tests 1–9 pass; ruff, ruff format and mypy --strict are clean.
- No network access in tests; no subprocess `gh` call in tests.
- Nothing outside `owned_files` changed.
- Subtasks marked: `spec-kitty agent tasks mark-status T001 T002 T003 T004 T005 T006 --status done --mission release-protection-posture-01M74ZWP`.

## Risks

- Turning an infrastructure fault into a prerelease warning (FR-010): only verification outcomes are verdicts; download/API faults raise.
- Normalisation that silently drops a setting: test 2 is the guard. If a leaf cannot be mutated into exactly one finding, the shape is wrong.
- `gh api --paginate --slurp` shapes: lists of pages; flatten correctly or a second page escapes the audit.

## Reviewer guidance

Check FR-007's identity flags verbatim, that checksum assets are skipped by name, that prerelease is decided by the tag (not the flag), that `bypass_actors` can never reach the expected file, and that each negative test has its positive control.
