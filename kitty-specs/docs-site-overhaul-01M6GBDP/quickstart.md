# Quickstart: docs-site-overhaul-01M6GBDP

How to build and verify the docs site like CI does, after this mission lands. (The user-facing version ships in `docs/guides/docsite.md`, FR-004; this is the implementation recipe.)

## Prerequisites

1. **Node 22.22+** and npm (verified locally: node 22.23.4 / npm 10.9.9).
2. **GitHub Packages token with `read:packages`** in the user-level `~/.npmrc` (DM-01M6GC6ZQ7ATZR6WBA49C03CXV):
   ```sh
   gh auth refresh -s read:packages            # browser dance
   printf '//npm.pkg.github.com/:_authToken=%s\n' "$(gh auth token)" >> ~/.npmrc
   ```
   Verify: `cd site && npm view @spec-kitty/doc-toolkit@0.6.0 version` prints `0.6.0`.
3. **PlantUML server** for diagram/schematic rendering parity with CI:
   ```sh
   docker run -d -p 8080:8080 \
     plantuml/plantuml-server:jetty-v1.2026.9@sha256:065c66adc6728ba6dd353a29d4ada73f8358b68549a0f710c4bf46b43c103618
   ```
4. Contract bundles (only needed for the API reference pages) — **the output must live OUTSIDE the repo** (`bundle.py` refuses in-tree output with `OUT_INSIDE_REPOSITORY`):
   ```sh
   export SKE_CONTRACT_BUNDLES_DIR="$(mktemp -d)/bundle-out/bundle"
   uv run --frozen --no-sync python contracts/tools/bundle.py --root contracts --out "$(dirname "$SKE_CONTRACT_BUNDLES_DIR")" --bundle-only
   ```
   In CI this is `$RUNNER_TEMP/bundle-out` implicitly; the staging script reads `SKE_CONTRACT_BUNDLES_DIR`.

## Build like CI (with `docs/changelog/unreleased/` present)

```sh
cd site
npm ci                                            # exact lockfile install
npm run validate                                  # docs_index --write + frontmatter/links with --exclude ../docs/changelog/unreleased
DK_DIAGRAM_BUILD_RENDER=build \
DK_PLANTUML_SERVER_URL=http://localhost:8080/svg/ \
DK_PUBLISH_DRAFTS=on \
  npm run build                                   # staging runs as prebuild; dist/ written
npm run check:built-links
```

Without the PlantUML server, `npm run build` still succeeds in client mode (`DK_DIAGRAM_BUILD_RENDER=client`) but schematic pages omit their diagrams — CI never runs that way (guarded).

## Gates every WP runs

```sh
uv run pytest --no-cov -q <changed tests + blast radius>
uv run pytest --pyargs spec_kitty_events.conformance
ruff check . && ruff format --check .
uv run python scripts/changelog.py check && uv run python scripts/changelog.py guard
make lint                       # includes scripts/schema_pages.py --check after WP04
python3 scripts/docs_index.py --clean   # before committing index-carrying pages
```

PlantUML local port note: readiness is `<120 s`; CI fails naming the service on timeout (NFR-002).
