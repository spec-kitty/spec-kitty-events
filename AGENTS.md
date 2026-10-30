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
a push to `main` that touches `docs/**` or `site/**`), and nothing on GitHub enforces
anything (no branch protection, no required reviews). The deterministic CI suite is
`.github/workflows/ci.yml` (Blacksmith, per `planning#274`); the other workflows run the
acceptance and cross-repo harness checks (`acceptance.yml`, `cross-repo-harness-tests.yml`)
and the daily CI canary (`ci-pr-lane-canary.yml`), build and deploy the docs site, and
publish to PyPI on `v*` tags (`publish-pypi.yml`) or to TestPyPI on manual dispatch
(`publish-testpypi.yml`). `service.yml` runs the Gradle build (`./gradlew verifyAll`,
currently the Java binding) on pull requests and `main` pushes that touch the Java, Gradle,
service, contract or producer-contract paths; its service jobs stay inert until the services
are imported at cutover, and `service-release.yml` (`service-v*` tags) refuses to release
before then ([ADR-0003](./docs/adr/0003-gradle-build-and-service-release-line.md)).
The binding process is the programme constitution —
[`EXPERIMENTAL-spec-kitty-planning/PROGRAM.md`](https://github.com/spec-kitty/EXPERIMENTAL-spec-kitty-planning/blob/main/PROGRAM.md) §5–§9:

- One issue → one branch (`issue-<n>-<slug>`, cut from `main`) → one PR targeting `main`.
  Never push to `main`; implementers never merge (§9).
- Run every test you write or change plus your blast radius, and record the commands and
  counts in the PR (§6). Do **not** run a whole-repo suite before opening the PR — that is
  the CI agent's job (`make test-full`).
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
| Java build and tests (every Gradle module: the binding; at cutover also the services) | `./gradlew verifyAll` |
| Java binding check through the JDK 25 helper (also part of `make test-full`) | `make test-java` |

The conformance gate is the packaged contract other repos consume; the in-repo suite does
not collect it, so run it explicitly on every PR.

## Documentation

`docs/` follows the DocKitty convention; start at [`docs/README.md`](./docs/README.md).
Each section's `README.md` is its index and carries frontmatter (`title`, `description`
≤ 180 chars, `doc_status`, `updated`, plus `type`/`kind`). With the sibling `doc-kitty`
checkout, validate with `node ../doc-kitty/src/scripts/validate-frontmatter.mjs docs`
and `node ../doc-kitty/src/scripts/check-links.mjs docs`.


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
