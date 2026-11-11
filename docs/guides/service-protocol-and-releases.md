---
title: Service protocol and releases
description: 'The CLI-to-write-service protocol in practice, the two release lines, the service-v* assets and tarball layouts, how core pins a release, and how to cut one.'
doc_status: active
updated: 2026-11-11T00:00:00Z
type: Guide
kind: How-To
related:
  - guides/running-the-service
  - guides/service-cutover-runbook
---

# Service protocol and releases

> **Where the services live.** The services and their contracts live in this repository,
> under `service/` and `contracts/`, imported from core at `512567eee71e31bb39f580c4f7acf8b865e9d1b2` with their history
> ([ADR-0002](../adr/0002-events-domain-owns-its-services.md)).
> - The write service, `command-cli`, is built (#156) and ships on the `service-v*` line since
>   `service-v0.1.0-rc.2`. Its write core (#160, #161) is built: `checkout_append` is served
>   (durable, byte-exact status appends), while `coordination_ref_commit` waits for migration
>   step 4. A release that carries this write core is what core's binding (#6195) pins; this
>   work cuts no tag. See
>   [`service/command-cli/README.md`](https://github.com/spec-kitty/spec-kitty-events/blob/main/service/command-cli/README.md).
> - The rc line exists: `service-v0.1.0-rc.1` carries the read service only;
>   `service-v0.1.0-rc.2` adds the `command-cli` assets
>   ([the release notes](../changelog/2026-11-01-service-v0-1-0-rc-2.md)).

This page is the how-to. The reasons are in the architecture pages and the ADRs it links.

## The CLI-to-write-service protocol in practice

The protocol is decided by core
[ADR 2026-10-27-1](https://github.com/spec-kitty/spec-kitty/blob/main/docs/adr/4.x/2026-10-27-1-the-write-service-protocol-is-loopback-http-with-a-bearer-token.md)
and written down in
[`contracts/mission-write`](https://github.com/spec-kitty/spec-kitty-events/tree/main/contracts/mission-write),
whose [launcher contract](https://github.com/spec-kitty/spec-kitty-events/blob/main/contracts/mission-write/LAUNCHER.md)
records the process lifecycle. The
[write-service protocol](../architecture/services/write-service-protocol.md) page explains
it, including the launcher process contract the operator accepted on 2026-10-29 (start,
single instance, ready, stop). That contract is not restated here.

### Find and check the endpoint file

The service publishes one file per git common dir, so every worktree of a clone reaches the
same service:

```bash
gcd="$(cd "$(git rev-parse --git-common-dir)" && pwd -P)"
ep="$gcd/spec-kitty-write-service/endpoint.json"
ls -l "$ep"              # POSIX: -rw------- , owned by you, a regular file, not a link
ls -ld "$(dirname "$ep")"  # POSIX: drwx------ , no group or other bits
jq '{schema_version, host, port, instance_id, protocol_versions}' "$ep"
```

The client refuses a file with any group or other bit, a link, a non-regular file, another
owner or a host other than `127.0.0.1`. On POSIX it also refuses when the directory that
holds the file (`spec-kitty-write-service/`) has any group or other bit (mode mask `0o077`,
the same mask as the file; core `_refuse_exposed` in
`src/specify_cli/status/write_service_protocol.py`). On Windows the mode and owner checks
are skipped: an owner-only ACL is the service's duty. The refusal codes name the case:
`WRITE_SERVICE_ENDPOINT_EXPOSED`, `WRITE_SERVICE_ENDPOINT_NOT_LOOPBACK`,
`WRITE_SERVICE_ENDPOINT_MALFORMED`, `WRITE_SERVICE_ENDPOINT_MISSING`.

### Handshake, then write

Every request carries `Authorization: Bearer <token>`. Never print the token or paste it
into an issue.

```bash
port="$(jq -r .port "$ep")"; token="$(jq -r .token "$ep")"
curl -s -H "Authorization: Bearer $token" "http://127.0.0.1:$port/api/v1/handshake"
curl -s -H "Authorization: Bearer $token" "http://127.0.0.1:$port/api/v1/health"
```

Check the handshake the way the CLI does:

1. `instanceId` equals the endpoint file's `instance_id`; otherwise the file is stale.
2. `repositoryId` equals the lowercase hex SHA-256 of the git common dir's real path in
   forward-slash form.
3. Pick the highest major in both `protocolVersions` and the client's list (the CLI speaks
   `1`) and send it as `Spec-Kitty-Write-Protocol: <major>` on every request except
   `/handshake` and `/health`.

### Read a refusal

A refusal is a `Problem` whose `code` is one of the 27 in `WriteRefusalCode.yaml`. The checks
run in a fixed order, so the status tells you which one failed:

| Status | Check | Typical code |
|---|---|---|
| 403 | Host and Origin guard | `REQUEST_REFUSED` |
| 401 | bearer token | `WRITE_TOKEN_INVALID` |
| 400 | canonical request target | `REQUEST_MALFORMED` |
| 400 | protocol header | `PROTOCOL_VERSION_UNSUPPORTED` |
| 413 | size cap | `REQUEST_TOO_LARGE` |
| 503 | the service drains before it exits (after `/shutdown` or its idle timeout); a write or repair still waiting for the turn or the Mission lock stops waiting and writes nothing | `SERVICE_DRAINING` |
| 400 | body shape (a batch over 64 units included) | `REQUEST_MALFORMED` |
| 404, 409, 422, 500 | the domain | the CLI's codes and the protocol-only domain codes, such as `TRANSITION_REJECTED`, `WP_ALREADY_CLAIMED` or `EVENT_ID_CONFLICT` |

There are two `503` codes:
- `SERVICE_DRAINING`, settled with `command-cli` (#156): re-read the endpoint file and retry
  against the service it names. It carries no `Retry-After`.
- `SERVICE_BUSY`, still provisional: retry the same service after `Retry-After` seconds.

The service is authoritative. A write that passes every check is appended and synced before the
`201`, or replayed when every row is already persisted with identical bytes. A `201` therefore
means durable: for `checkout_append`, one `O_APPEND` write and one `fsync` (plus a parent
directory sync when the log is created), rolled back and refused `500 STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED`
on a failed write. A refused write wrote nothing. A torn tail is refused by every write, and it is
repaired only by the explicit repair operation (`POST /api/v1/missions/{missionId}/status-log-repairs`,
[#222](https://github.com/spec-kitty/spec-kitty-events/issues/222)). The repair is never part of a write.
`coordination_ref_commit` answers `422 WRITE_LOCATION_UNVERIFIED` until migration step 4, and
`statusCommit` stays null.

The client-side checks that stay advisory (C-004) are `move-task`'s agent-mismatch guard and the
lane-specific for-review commit gate; they run above core's emit pipeline and do not replace the
service's checks. The full check order, the refusals and the deliberate divergences from core
are in [write-service protocol](../architecture/services/write-service-protocol.md) and
[`service/command-cli/README.md`](https://github.com/spec-kitty/spec-kitty-events/blob/main/service/command-cli/README.md).

## Releases

### Two release lines

[ADR-0004](../adr/0004-dual-artifact-line.md) keeps two independent lines:

| Line | Tag | Workflow | Carries |
|---|---|---|---|
| Package | `vX.Y.Z` | `publish-pypi.yml` | the Python package; the Java binding is versioned with it |
| Services | `service-vX.Y.Z[-pre]` | `service-release.yml` | the service binaries and jar, the contract bundles and tools, the conformance fixtures |

The patterns are disjoint: a `v*` tag never starts the service workflow, and the reverse.
Each service release's notes name the package version (`pyproject.toml` at the tag) it was
built and tested against. A breaking change to `mission-status`, `mission-write` or the
asset contract is a service major; a breaking write-protocol change also needs a new
protocol major in core's handshake.

**The docs site flips on the first final tag.** The services' OpenAPI reference pages on
the docs site ([mission-status](/reference/api/mission-status/),
[mission-write](/reference/api/mission-write/)) show `unreleased` until the first final `service-v*` tag exists (see
[the services hub](../api/services.md)); the release PR that cuts that first final tag
flips them by setting `released: true` for both specs in `site/astro.config.mjs` (#194).

### The assets

[ADR-0003](../adr/0003-gradle-build-and-service-release-line.md) is the contract for these
names; only `<ver>` (the tag without `service-v`) changes between releases.

| Asset | Content |
|---|---|
| `mission-status-api-<ver>-linux-x64` | GraalVM native executable |
| `mission-status-api-<ver>-linux-arm64` | GraalVM native executable |
| `mission-status-api-<ver>-macos-arm64` | GraalVM native executable |
| `mission-status-api-<ver>-windows-x64.exe` | GraalVM native executable |
| `mission-status-api-<ver>.jar` | Spring Boot executable jar, the JVM fallback |
| `command-cli-<ver>-linux-x64` | the write service: GraalVM native executable |
| `command-cli-<ver>-linux-arm64` | the write service: GraalVM native executable |
| `command-cli-<ver>-macos-arm64` | the write service: GraalVM native executable |
| `command-cli-<ver>-windows-x64.exe` | the write service: GraalVM native executable |
| `command-cli-<ver>.jar` | the write service: Spring Boot executable jar, the JVM fallback on Java 25 |
| `contract-bundles-<ver>.tar.gz` | `bundle/mission-status/openapi.yaml`, `bundle/mission-write/openapi.yaml` |
| `contract-tools-<ver>.tar.gz` | `contracts/tools/*.py`, `contracts/tools/pins.json`, `contracts/tools/enum_pins.json` |
| `conformance-fixtures-<ver>.tar.gz` | see the layout below |
| `<asset>.sha256` | one per asset above: `<64-hex>  <asset>` |
| `SHA256SUMS` | every asset above, sorted by name |

Every asset except the checksum files has a build-provenance attestation. The tarballs are
deterministic (sorted names, owner 0, mtime of the tagged commit, `gzip -n`), so rebuilding
the same commit gives the same sha256. `scripts/release/service-release-assets.sh` builds
the set and fails when it differs from the table by one file.

### The conformance-fixtures layout

Core codes against these entry names. Each is staged from the `service/` path on the right:

| Entry in the tarball | Staged from |
|---|---|
| `conformance/**` | `service/src/test/resources/conformance/**` |
| `artifact-conformance/cases.json` | `service/src/test/resources/artifact-conformance/cases.json` |
| `artifact-conformance/golden.json` | `service/src/test/resources/artifact-conformance/golden.json` |
| `nativesmoke/requests.json` | `service/src/test/resources/nativesmoke/requests.json` |
| `leak-patterns.json` | `service/src/main/resources/leak-patterns.json` |
| `event-log/torn-tail/repair-cases.json` | `service/event-log/src/test/resources/torn-tail/repair-cases.json` |

There is no top-level directory: entries start at the names above. The two other tarballs
also have fixed roots: `contract-bundles` starts at `bundle/`, `contract-tools` at
`contracts/tools/`.

### How core pins a release

Core pins a `service-vX.Y.Z` tag and the sha256 of each asset it uses, and moves the pin
only by a reviewed PR. It never fetches by a moving reference (no `latest`, no branch).

- **The binary.** On first run core downloads the native binary for the host (the jar is
  the JVM fallback), checks its sha256 against the pin before it runs it, and refuses on a
  mismatch. The PyPI wheel carries no binary. This fetch is core's launcher work (#6676);
  until it lands, the read service is the only one core pins.
- **CI.** The read service is pinned for core's CI only.
- **`acceptance-jvm`.** Core's acceptance runner uses `contracts/tools/install_tools.py` and
  `gradle_pin_check.py`. After cutover it takes them from the pinned
  `contract-tools-<ver>.tar.gz` instead of a source checkout:

  ```bash
  gh release download "service-v${ver}" --repo spec-kitty/spec-kitty-events \
    --pattern "contract-tools-${ver}.tar.gz" --dir "$RUNNER_TEMP"
  echo "${PINNED_SHA256}  $RUNNER_TEMP/contract-tools-${ver}.tar.gz" | sha256sum -c -
  tar -xzf "$RUNNER_TEMP/contract-tools-${ver}.tar.gz" -C "$RUNNER_TEMP"
  python "$RUNNER_TEMP/contracts/tools/install_tools.py" --pins acceptance-jvm/tools/pins.json \
    --dest "$RUNNER_TEMP/tools" --only gradle
  ```

A core change that needs a new package and a new service release bumps both pins in one PR.

### Cut a release

**Pushing a `service-v*` tag needs the operator's approval.** Never push `main`.

1. **Choose the version.** `X.Y.Z` by the service SemVer rules above; add `-rc.N` (or another
   `-` suffix) for a pre-release. The first one is a `service-v0.x` pre-release.
2. **Rehearse.** Run `service-release` by hand (Actions → service-release → Run workflow,
   or `gh workflow run service-release.yml -f tag=service-v<ver>`). It builds and assembles
   every asset and uploads them as the `service-release` artifact; it publishes nothing.
   Check the artifact holds the table's files and that `sha256sum --check --strict SHA256SUMS`
   passes.
3. **Tag the merged commit on `main`** and push the tag only:

   ```bash
   git fetch origin main
   git tag -a "service-v<ver>" origin/main -m "service-v<ver>"
   git push origin "service-v<ver>"
   ```

4. **Watch the gates.** `service-release.yml` refuses a tag that does not match
   `^service-v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$`, a commit that is not an ancestor of
   `origin/main`, or a checkout without `service/`. It builds through `service.yml` (natives
   on four targets, golden replay, the jar) in read-only jobs; lock-interop is skipped in a
   release run (see [What CI does not gate](#what-ci-does-not-gate)). Only `publish`
   holds a write token, and it runs in the `service-release` environment: the run waits until
   a required reviewer approves it under Actions → the run → Review deployments (see
   [Release protection](#release-protection)). Then it checks every asset against `SHA256SUMS`, attests provenance,
   re-checks that the tag still names the built commit, and runs
   `gh release create --verify-tag`, with `--prerelease` for a `-` suffix and never as
   latest. An existing release of the tag fails the step; a release is never replaced.
5. **Verify** the published release: download every asset, run
   `sha256sum --check --strict SHA256SUMS` and
   `gh attestation verify <asset> --repo spec-kitty/spec-kitty-events --signer-workflow spec-kitty/spec-kitty-events/.github/workflows/service-release.yml --source-ref refs/tags/<tag>`
   for each non-checksum asset. The signer and ref flags matter: `--repo` alone accepts an
   attestation from any workflow or branch in the repository.
   After publishing, run the release-protection audit by hand; it must stay green (see
   [Run and read the audit](#run-and-read-the-audit)).
6. **Hand the pins to core**: the tag, the asset names and their sha256 sums.

A failed run publishes nothing. Fix on `main` by a reviewed PR, then tag a new version; do
not move or re-push a tag.

### Release protection

The workflow side is in `service-release.yml`: `publish` declares `environment: service-release`
(#152). The protection itself is repository settings, which only an administrator can change. All
three were applied on 2026-11-07. Keep the environment in place: a run that names a missing
environment makes GitHub create it with no protection.

1. **The `service-release` environment** (Settings → Environments → New environment):
   - *Required reviewers*: `stijn-dejongh` and the `admins` team, with *Prevent self-review* on,
     so whoever starts a release cannot approve it. Administrators cannot bypass the rule.
   - *Deployment branches and tags*: *Selected branches and tags*, with one tag rule, `service-v*`.
     A run from any other ref cannot enter the environment, so `publish` never runs there. The
     environment guards only `publish`: another workflow that asks for `contents: write` (for
     example on a branch with an edited workflow) is not held by it (#221). See [Release-protection posture](#release-protection-posture).
   - No environment secrets are needed: `publish` uses the job's `GITHUB_TOKEN` and OIDC.
2. **A tag ruleset for `service-v*`** (Settings → Rules → Rulesets → New tag ruleset): `service-release-tags`,
   target `refs/tags/service-v*`, enforcement *Active*, with *Restrict creations*, *Restrict updates*,
   *Restrict deletions* and *Block force pushes*; only `stijn-dejongh` and the `admins` team bypass it. A published tag can then
   be neither moved nor deleted, which the release notes and core's pins rely on.
3. **The `main` ruleset** (`26558116`), *Active* since 2026-11-05, requiring
   `service-gate`, `acceptance`, `cross-repo-harness-tests` and `docs-build`. The `gate` job's
   ancestor-of-`main` check is only as strong as what reaches `main` (#208, #111). The ruleset
   requires no approving review, and `stijn-dejongh` can bypass it.

Check the result with read-only calls:

```bash
gh api repos/spec-kitty/spec-kitty-events/environments/service-release \
  --jq '{reviewers: [.protection_rules[] | select(.type=="required_reviewers") | .reviewers[].reviewer.login], policy: .deployment_branch_policy}'
gh api repos/spec-kitty/spec-kitty-events/environments/service-release/deployment-branch-policies --jq '.branch_policies[] | {name, type}'
gh api repos/spec-kitty/spec-kitty-events/rulesets --jq '.[] | {id, name, target, enforcement}'
```

### Release-protection posture

The environment guards only `publish`. This section lists every other path to a `service-v*` release
and what covers it. The audit (`release-protection-audit.yml`, daily and read-only) is the compensating control.

| Vector | Control | Status | Check it |
|---|---|---|---|
| A workflow on any pushed branch requests `contents: write` and runs `gh release create` on an existing `service-v*` tag (#152, #221). | The audit checks every `service-v*` release's author and verifies every non-checksum asset's attestation: it must be signed by exactly `service-release.yml` running at `refs/tags/<tag>`. A final release that fails turns the run red. A pass proves that workflow file signed the asset at that tag, not that the `service-release` environment approved it. Consumers verify downloads with the step 5 command ([Cut a release](#cut-a-release)) and core pins by sha256. That command's `--signer-workflow` is a prefix match; the audit pins the exact identity with `--cert-identity https://github.com/spec-kitty/spec-kitty-events/.github/workflows/service-release.yml@refs/tags/<tag>`, which consumers can use too (#253). | **Detected, not prevented.** GitHub has no rule that restricts who creates a release. | The audit run; the step 5 command for each asset. |
| Settings drift: someone loosens the `service-release` environment, or changes, disables, adds or deletes a ruleset. | The audit compares the live settings with `scripts/release/release-protection-expected.json`. | **Detected daily, not prevented.** It does not see the bypass lists (below), branch protection, the Actions default token permissions, or any environment other than `service-release` beyond its name. | The audit run. |
| A `service-v*` tag is moved or deleted after release. | The `service-release-tags` ruleset (item 2 above) plus `publish`'s re-check that the tag still names the built commit. | Controlled. | The ruleset list in the check commands above. |
| The `main` ruleset needs 0 approving reviews and `stijn-dejongh` can bypass it, so the `gate`'s ancestor-of-`main` check is only as strong as what reaches `main`. | Compensating control: the audit. | **Accepted risk, D5.** Signed off by `stijn-dejongh` on 2026-11-09 (#239). Revisit when a second maintainer exists. | `gh api repos/spec-kitty/spec-kitty-events/rulesets/26558116 --jq '.rules'` (the `pull_request` rule's `required_approving_review_count`), plus the bypass check in the last row. |
| The PR-lane canary (`ci-pr-lane-canary.yml`) mints a `spec-kitty-factory-ci` App token for every repository the org-wide installation reaches. | None in this repository. The workflow stays as is. | **Accepted risk, D7.** Signed off by `stijn-dejongh` on 2026-11-11 (#239; decision record `DM-01M755JB9N018XSX5VJ39HQRXP`). Revisit when the App installation is narrowed or the canary moves to the planning repo. | The App installation's scope, by hand. |
| The rulesets' bypass lists. The audit's read-only token cannot read them (`bypass_actors` comes back null), so adding a bypass actor is not detected. | None in the audit. | **Accepted risk, D6.** Signed off by `stijn-dejongh` on 2026-11-11 (#239; decision record `DM-01M755J5E9J11CFV1JEA9T2X1A`). Revisit if GitHub exposes the lists to read tokens. | Run by hand with an admin login: `gh api repos/spec-kitty/spec-kitty-events/rulesets/<id> --jq '.bypass_actors'` for `26558116` and `26752412`. Expected: `main` → the user `stijn-dejongh` (always); `service-release-tags` → the `admins` team and `stijn-dejongh` (always). |
| A published release's assets are replaced, or assets are added to it later. | The audit re-verifies every asset's digest and attestation each day. | **Detected daily, not prevented.** *Immutable releases* (Settings → General → Releases) would prevent it; it is off today. | The audit run. |
| The audit itself is silenced: the workflow is disabled, its scheduled run is cancelled, or someone dispatches it from a branch with an edited script. | Trust only `schedule` runs and dispatch runs on `main`. A missing or cancelled daily run on `main` is itself a red signal. | **Residual risk, not yet signed off** (anyone with write access can do this; the audit cannot stop it; #254). | Actions → release-protection-audit, filtered to `main`. |

A final release flagged by the audit is untrusted. Do not pin it, and investigate how it was created.
A prerelease is checked the same way but only warns (D8). A rehearsal publishes nothing.

#### Run and read the audit

Run the audit by hand from Actions → release-protection-audit → Run workflow, or with
`gh workflow run release-protection-audit.yml`. Run it from `main` after changing a setting and after publishing a release.
To check the settings locally (read-only, with a `gh` login that can read the repository):

```bash
python3 scripts/release/release_protection_audit.py --repo spec-kitty/spec-kitty-events \
  --expected scripts/release/release-protection-expected.json --skip-releases
```

The audit exits with one of three codes:

- `0`: no failures. Warnings are allowed.
- `1`: at least one failure. Each failure is a `FAIL` line in the run summary; any `ERROR` lines are listed too.
- `2`: no failure, but something could not be checked: an API, network or verification-infrastructure fault, each an `ERROR` line. A fault on one asset does not stop the others from being checked. Run it again; an `ERROR` that persists on the same asset is a finding to investigate, not noise.

A `FAIL settings …` line means a live setting differs from the expected file. A `FAIL release <tag> …` line
means a final release not authored by `github-actions[bot]`, or an asset whose attestation was not signed by `service-release.yml` at its tag. Treat that release as untrusted.
Prerelease verification faults appear as `WARN` lines and do not fail the run; an `ERROR` on a prerelease asset still exits 2.

The audit records only the environment settings it knows: reviewers, self-review, admin bypass, wait timer
and deployment policy. A new environment-level setting that GitHub adds later is not compared until the script learns it.
A new rule parameter in a ruleset shows up as drift.

A deliberate settings change lands with a reviewed PR that updates `scripts/release/release-protection-expected.json`. Regenerate the file from fresh API responses as `tests/fixtures/release_protection/README.md` describes; do not edit it by hand.
The audit is red until that PR merges, by design.

### What CI does not gate

Recorded at the import (#147); the [cutover runbook](./service-cutover-runbook.md#4-post-import-edits-wp07),
section 4 item 7, holds the same record.

- **Cross-leg native digest comparison and `nativePerf`: deliberately omitted so far (as of
  `service-v0.1.0-rc.2`); follow-up.** Core compared the four `native` legs' replay digests
  (`scripts/ci/mission_status_gate_checks.py digests`) and ran `nativePerf`; neither is wired
  here yet. Each leg still uploads its `digest-<target>` artifact, so compare them by hand
  before you tag.
- **Lock interop is a gate on pull requests and `main`, not on releases** (operator decision,
  2026-10-31). It runs on pull requests and `main` pushes, where `service-gate` needs it, so a
  red lock-interop fails the gate. It never runs inside a release run, because it executes
  core's Python at the SHA in `service/core-pin.json`; cut a tag only from a `main` commit
  whose `service-gate` is green. (Core treated it as evidence only; this repository keeps it
  blocking.)
- **Reachability metadata** is uploaded by each `native` leg as
  `reachability-metadata-<target>` for review; nothing checks it.
