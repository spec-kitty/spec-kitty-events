---
title: Building the docsite
description: How the docs/ tree becomes a doc-kitty site — building it locally with OpenAPI and schema staging and build-time diagrams, writing links, and what CI runs.
doc_status: active
updated: 2026-11-03T00:00:00Z
type: Guide
kind: How-To
related:
  - guides/contributing
---

# Building the docsite

The `docs/` tree is rendered by [doc-kitty](https://github.com/spec-kitty/doc-kitty)'s
published toolkit, `@spec-kitty/doc-toolkit` (pinned to the latest release, `0.6.0`, on Astro 7 and Starlight 0.42). The
Astro project that renders it lives in `site/`; it owns no content and reads `../docs`
directly. The docs tree lives at the **repository root**, not under `site/`.

## Build locally

You need Node 22.22 or later and a GitHub token with the `read:packages` scope, because the
toolkit is published to GitHub Packages.

1. Put the token in your **user-level** `~/.npmrc`, never in the project file:

   ```ini
   //npm.pkg.github.com/:_authToken=${GITHUB_TOKEN}
   ```

2. Install, check and build from `site/`. For full parity with CI, first start the
   pinned PlantUML server, install the pinned Chromium locally (the workflow runs
   in the Playwright container instead, so CI has no install step), and produce the
   contract bundles — they are staged **from outside the repository** (the bundler
   refuses an output directory inside the working tree, `OUT_INSIDE_REPOSITORY`), so
   point `SKE_CONTRACT_BUNDLES_DIR` at a scratch directory:

   ```sh
   docker run -d -p 8080:8080 \
     plantuml/plantuml-server:jetty-v1.2026.9@sha256:065c66adc6728ba6dd353a29d4ada73f8358b68549a0f710c4bf46b43c103618
   export SKE_CONTRACT_BUNDLES_DIR="$(mktemp -d)/bundle-out/bundle"
   uv run --frozen --no-sync python contracts/tools/bundle.py --root contracts --out "$(dirname "$SKE_CONTRACT_BUNDLES_DIR")" --bundle-only
   cd site
   npm ci                      # installs exactly what package-lock.json records
   npx playwright install chromium   # once per machine; CI gets Chromium from the container image
   npm run validate            # generates the section indexes, then checks frontmatter and source links in ../docs
   DK_DIAGRAM_BUILD_RENDER=build \
   DK_PLANTUML_SERVER_URL=http://localhost:8080/svg/ \
   DK_PUBLISH_DRAFTS=on \
     npm run build             # writes site/dist, with diagrams rendered to static SVG
   npm run check:built-links   # every link in the built site resolves under the base
   npm run preview
   ```

`npm run validate` natively excludes the fragment folder (`../docs/changelog/unreleased`,
via `--exclude`; the unreleased changelog holds fragments, not pages — ADR 0005).

Without the PlantUML server the build still succeeds in client mode
(`DK_DIAGRAM_BUILD_RENDER=client`), but that mode is a local fallback only: CI never
uses it (the workflow guards against it), and schematic pages omit their diagrams
without the server, so the local site no longer matches the published one.

`npm run validate`, `npm run build` and `npm run dev` first fill the generated lists in
`docs/changelog/README.md` and `docs/adr/README.md` from the pages themselves
(`scripts/docs_index.py --write`, as npm's `pre` scripts). Those lists are never committed:
run `python3 scripts/docs_index.py --clean` from the repository root before you commit or
run the tests, or `make lint` (`--check`) and
`tests/test_docs_index.py::test_the_repository_indexes_are_committed_empty_and_generate`
fail. A new changelog page or ADR therefore needs no README edit,
and parallel pull requests never collide on an index
([#176](https://github.com/spec-kitty/spec-kitty-events/issues/176)).

Diagrams render at **build time**: Mermaid through a Playwright Chromium,
schematic/PlantUML diagrams through the PlantUML server named by
`DK_PLANTUML_SERVER_URL` (ADR-0006). The mode is site-wide and auto-detected — the
toolkit upgrades to build mode whenever it resolves a Chromium, so `astro.config.mjs`
sets `diagrams: true` without pinning a mode; CI pins and guards `build`
(`docs-site.yml`), and a local build without a Chromium falls back to client mode
with the consequences noted above.

Every build also writes the agent API (`api/index.json`, version 3), a content graph
(`api/content-graph.json`) and an OKF bundle (`okf/`).

`site/.npmrc` maps the `@spec-kitty` scope to GitHub Packages and everything else to the
public npm registry; it holds no secret.

## The Build Kitty theme

The site uses the Spec Kitty theme with the Build Kitty brand mark (the Aegean-blue bow-tie
cat, `site/src/assets/build-kitty.png`) and a thin consumer layer,
`site/src/styles/build-kitty.css`, that re-tints the shared yellow accent to Build Kitty's
Aegean blue. Both are wired in `site/astro.config.mjs` via the theme's `assets.logo` and
`customCss`. The consumer sheet declares `--dk-*` tokens only and loads after the brand sheet,
so it wins by source order — the documented default → brand → consumer layering.

## Writing links

Link between pages with **root-absolute routes**, such as `/events/work-and-status/` or
`/api/envelope/`. The toolkit prefixes them with the site's base path
(`/spec-kitty-events`). Links to files outside `docs/` (code, the repository root,
`contracts/`) use full GitHub URLs. The `npm run validate` step checks relative source links;
the built-link check catches a broken route after the build.

## Sections and the navbar

The sidebar order, labels and section types come from
[`docs/_meta/sections.yaml`](https://github.com/spec-kitty/spec-kitty-events/blob/main/docs/_meta/sections.yaml),
not the folder layout — edit that file to add or reorder a section. Each section's `README.md`
is its index and carries frontmatter; the required fields are `title`, `description`,
`doc_status` and `updated`.

## What CI runs

`.github/workflows/docs-site.yml` builds the site on every PR that touches `docs/**`,
`site/**`, the contracts and schemas that feed the generated pages
(`contracts/**`, `src/spec_kitty_events/schemas/**`,
`src/spec_kitty_events/producer_contract/**`, `scripts/schema_pages.py`) or the
workflow itself, and publishes it to GitHub Pages from `main` (the push trigger lists
the same paths). The `build` job runs in the digest-pinned Playwright container with a
digest-pinned, SANDBOX-profiled PlantUML service, waits for that service (bounded at
120 s, failing with the service's name), then runs `npm run validate`, then
`npm run build` with `DK_DIAGRAM_BUILD_RENDER=build` — guarded in-step so a drift back
to client mode fails the job — then `npm run check:built-links`, so a frontmatter or
link mistake fails the PR before it can publish. `npm run validate` generates the
section indexes first, so the built site lists every page and their links are checked
too.

The pipeline runs two more stages on top of this:

- **OpenAPI staging and the checksum gate**: the contract bundle is
  produced out-of-repo and staged by the prebuild script; after the build, the published
  OpenAPI document is compared against the bundle by SHA-256.
- **Schema pages and the drift gate**: the schema reference pages are
  generated from the schemas and checked for drift, and every built schema page must
  carry a rendered diagram.

Local builds read the bundles via `SKE_CONTRACT_BUNDLES_DIR` (see step 2 of
[Build locally](#build-locally)).
