---
title: Building the docsite
description: How the docs/ tree becomes a doc-kitty site — building and checking it locally, the Build Kitty theme, writing links, and what the CI workflow runs.
doc_status: active
updated: 2026-11-01T00:00:00Z
type: Guide
kind: How-To
related:
  - guides/contributing
---

# Building the docsite

The `docs/` tree is rendered by [doc-kitty](https://github.com/spec-kitty/doc-kitty)'s
published toolkit, `@spec-kitty/doc-toolkit` (pinned to the latest release, `0.4.0`, on Astro 7 and Starlight 0.42). The
Astro project that renders it lives in `site/`; it owns no content and reads `../docs`
directly. The docs tree lives at the **repository root**, not under `site/`.

## Build locally

You need Node 22.22 or later and a GitHub token with the `read:packages` scope, because the
toolkit is published to GitHub Packages.

1. Put the token in your **user-level** `~/.npmrc`, never in the project file:

   ```ini
   //npm.pkg.github.com/:_authToken=${GITHUB_TOKEN}
   ```

2. Install, check and build from `site/`:

   ```sh
   cd site
   npm ci                      # installs exactly what package-lock.json records
   npm run validate            # generates the section indexes, then checks frontmatter and source links in ../docs
   DK_DIAGRAM_BUILD_RENDER=client npm run build   # writes site/dist
   npm run check:built-links   # every link in the built site resolves under the base
   npm run preview
   ```

`npm run validate`, `npm run build` and `npm run dev` first fill the generated lists in
`docs/changelog/README.md` and `docs/adr/README.md` from the pages themselves
(`scripts/docs_index.py --write`, as npm's `pre` scripts). Those lists are never committed:
run `python3 scripts/docs_index.py --clean` from the repository root before you commit or
run the tests, or `make lint` (`--check`) and
`tests/test_docs_index.py::test_the_repository_indexes_are_committed_empty_and_generate`
fail. A new changelog page or ADR therefore needs no README edit,
and parallel pull requests never collide on an index
([#176](https://github.com/spec-kitty/spec-kitty-events/issues/176)).

Mermaid diagrams render in the browser. `DK_DIAGRAM_BUILD_RENDER=client` pins that mode;
without it the toolkit switches to build-time rendering whenever it finds a Playwright
Chromium, which this site does not use.

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

`.github/workflows/docs-site.yml` builds the site on every PR that touches `docs/**` or
`site/**` and publishes it to GitHub Pages from `main`. It runs `npm run validate`, then
`npm run build`, then `npm run check:built-links` — so a frontmatter or link mistake fails the
PR before it can publish. `npm run validate` generates the section indexes first, so the
built site lists every page and their links are checked too.
