# Agent Guide: spec-kitty/spec-kitty-events

This repo is `spec-kitty/spec-kitty-events`, the only development line for the package.
The former `EXPERIMENTAL-spec-kitty-events` fork is fully merged here and archived
read-only; its issue and PR numbers cited in the changelog, docs, and docstrings still
refer to that archive. The repo also hosts the events-domain Java services
(`service/` and `contracts/`, from cutover), released on a `service-v*` line separate
from the PyPI `v*` line ([ADR-0002](./docs/adr/0002-events-domain-owns-its-services.md)).
The repo follows the `EXPERIMENTAL-spec-kitty-*` programme
process. There is no pre-programme
branch ladder here: **`main` IS the integration branch** — every PR targets `main`,
nothing deploys on merge except the docs site (`docs-site.yml` publishes GitHub Pages on
a push to `main` that touches `docs/**`, `site/**`, `contracts/**`,
`src/spec_kitty_events/schemas/**`, `src/spec_kitty_events/producer_contract/**` or
`scripts/schema_pages.py`), and nothing on GitHub enforces
anything beyond the operator's ruleset on `main`: required checks only (`acceptance`,
`cross-repo-harness-tests`, `service-gate`, `docs-build`), "up to date before merging" off,
no merge queue. `ci.yml`'s `suite` is not required, and a `pr:pause-ci` label pauses a PR's CI
(`pr-pause-ci.yml`; ADR 0005 D3, amended 2026-11-02, #177). The deterministic CI suite is
`.github/workflows/ci.yml` (Blacksmith, per `planning#274`); the other workflows run the
acceptance and cross-repo harness checks (`acceptance.yml`, `cross-repo-harness-tests.yml`)
and the daily CI canary (`ci-pr-lane-canary.yml`), build and deploy the docs site, and
publish to PyPI on `v*` tags (`publish-pypi.yml`) or to TestPyPI on manual dispatch
(`publish-testpypi.yml`). `service.yml` runs the Gradle build (`./gradlew verifyAll`: the Java
binding, the services under `service/` and the contracts) on `main` pushes that touch the Java,
Gradle, service, contract or
producer-contract paths, and on every pull request, where its `detect` job
skips the build unless the change touches those paths; through the same workflow,
`service-release.yml` (`service-v*` tags) builds, attests and publishes the service releases
([ADR-0003](./docs/adr/0003-gradle-build-and-service-release-line.md)).
The binding process is the programme constitution —
[`EXPERIMENTAL-spec-kitty-planning/PROGRAM.md`](https://github.com/spec-kitty/EXPERIMENTAL-spec-kitty-planning/blob/main/PROGRAM.md) §5–§9:

- One issue → one branch (`issue-<n>-<slug>`, cut from `main`) → one PR targeting `main`.
  Never push to `main`; implementers never merge (§9).
- A mission covering several issues may land as one branch and one PR that closes them all
  (a maintainer ruling relaxes the one-issue-one-PR rule; the ruling is recorded in the
  mission's spec or research record).
- Run every test you write or change plus your blast radius, and record the commands and
  counts in the PR (§6). Do **not** run a whole-repo suite before opening the PR — that is
  the CI agent's job (`make test-full`).
- Record the change as a `docs/changelog/unreleased/<issue>-<slug>.md` fragment; never add entries to
  `[Unreleased]` in `CHANGELOG.md` by hand — only the release PR's `scripts/changelog.py
  collect` writes its subsections
  ([ADR 0005](./docs/adr/0005-parallel-landing.md), `docs/changelog/unreleased/README.md`).
- Exactly one adversarial-squad review per PR; `[MAJOR]` findings block, minors become
  issues (§7). On exe.dev VMs use `gh api repos/<owner>/<repo>/...`, not `gh pr` /
  `gh issue` (`bin/GH-API.md` in the planning repo).

## Commands

| Purpose | Command |
|---|---|
| Implementer blast-radius baseline | `make test-fast` |
| Whole suite with coverage — CI agent only | `make test-full` |
| Packaged conformance gate — every PR | `uv run pytest --pyargs spec_kitty_events.conformance` |
| Lint (whole repo, part of "Tests run") | `ruff check . && ruff format --check .` |
| Changelog fragments well formed, `[Unreleased]` not hand-edited (both in `make lint`) | `uv run python scripts/changelog.py check && uv run python scripts/changelog.py guard` |
| Java build and tests, every Gradle module (binding, services, contracts), with the contract bundle — part of `make test-full` | `make test-gradle` |
| The same by hand: export `CONTRACT_BUNDLE` and `CONTRACT_BUNDLE_SHA256` first ([runbook §5](./docs/guides/service-cutover-runbook.md#5-verification)) | `./gradlew verifyAll` |
| Java binding check alone, through the JDK 25 helper | `make test-java` |
| Docs frontmatter and source links (doc-toolkit 0.6.0, natively excluding the changelog fragments) | `cd site && npm run validate` |

The conformance gate is the packaged contract other repos consume; the in-repo suite does
not collect it, so run it explicitly on every PR.

## Documentation

`docs/` follows the DocKitty convention; start at [`docs/README.md`](./docs/README.md).
Each section's `README.md` is its index and carries frontmatter (`title`, `description`
≤ 180 chars, `doc_status`, `updated`, plus `type`/`kind`). Validate locally from `site/`
with `npm run validate`, which runs the published doc-toolkit 0.6.0's
`validate-frontmatter` and `check-links` against `../docs` with
`--exclude ../docs/changelog/unreleased`; the site build excludes the same folder through
`exclude:` in `site/astro.config.mjs`. `docs/changelog/unreleased/` holds changelog
fragments, not pages, so no docs gate flags it (ADR 0005, amended 2026-11-03;
toolkit ADR-0070's native `exclude`).


<!-- spec-kitty:orientation -->
**Spec Kitty v4.0.0rc8** — project: spec-kitty-events (healthy)

Two usage patterns:
- **Full mission** (spec → plan → tasks → implement → review → merge):
  trigger: "spec out", "create a mission", "write a spec", "plan this"
  → run `/spec-kitty.specify`
- **Lightweight dispatch** (ad-hoc fix, question, or advice — no mission created):
  trigger: "hey spec kitty", "use spec kitty to", "spec kitty <anything>"
  → **ALWAYS run `spec-kitty dispatch "<request verbatim>"` — do NOT answer directly.**
  If you know the right profile, pass it to skip routing:
  `spec-kitty dispatch "<request verbatim>" --profile <profile-id>`
  Reason: `spec-kitty dispatch` loads governance context, routes the request,
  and opens the Op. Skipping it produces ungoverned, untracked responses.
  After finishing the work, close the Op with the command printed in the capsule
  (`spec-kitty profile-invocation complete --invocation-id <id> --outcome <done|failed|abandoned>`).
<!-- /spec-kitty:orientation -->
