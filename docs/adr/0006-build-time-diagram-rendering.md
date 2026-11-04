---
title: 'ADR-0006: the docs site renders diagrams at build time in a pinned container'
description: 'Accepted: docs-site.yml builds in a digest-pinned Playwright container with a SANDBOX PlantUML service; Mermaid and PlantUML render to static SVG at build time.'
doc_status: active
updated: 2026-11-03T00:00:00Z
type: ADR
kind: ADR
authors:
  - human:stijn
related:
  - adr/0002-events-domain-owns-its-services
  - adr/0005-parallel-landing
---

# ADR-0006: the docs site renders diagrams at build time in a pinned container

**Status:** Accepted. The maintainer ruled on 2026-11-03 (mission
`docs-site-overhaul-01M6GBDP`, research.md "Maintainer rulings"): the build-mode
PlantUML trade-off is accepted.

**Date:** 2026-11-03

**Deciders:** maintainer stijn.

**Reader:** anyone editing `.github/workflows/docs-site.yml`, bumping the diagram
toolchain pins, or building the docs site locally and wondering why client mode is no
longer the recommended path.

**Technical Story:** [#195](https://github.com/spec-kitty/spec-kitty-events/issues/195),
mission `docs-site-overhaul-01M6GBDP`. Client mode was never an ADR decision, so this
is a new ADR, not an amendment of an earlier one.

Related records: [ADR-0002](./0002-events-domain-owns-its-services.md) (the services
whose contracts and schemas the generated pages come from) and
[ADR-0005](./0005-parallel-landing.md) (the landing machinery this workflow keeps:
path-filterless required check, generated indexes).

## Context

Until `@spec-kitty/doc-toolkit` 0.6.0 the site set `DK_DIAGRAM_BUILD_RENDER=client`:
Mermaid rendered in the reader's browser and nothing was needed at build time. That
choice appeared only in the workflow comment "never switch to build-time rendering";
no ADR had ever taken the decision. Toolkit 0.6.0 made the render mode site-wide: the
same switch, `DK_DIAGRAM_BUILD_RENDER`, now drives **both** Mermaid (through a
Playwright Chromium) and schematic/PlantUML diagrams (through `DK_PLANTUML_SERVER_URL`),
and the site auto-detects — whenever the toolkit resolves a Chromium it upgrades to
build mode on its own. The upcoming schema and OpenAPI reference pages (WP03/WP04 of
the mission) carry diagrams the client mode cannot produce for the published site.

## Decision

`docs-site.yml` renders diagrams at **build time**. The `build` job:

- runs in the digest-pinned Playwright container
  `mcr.microsoft.com/playwright:v1.62.1-noble@sha256:dcc5531e97840b9b5e794f2814476b21571c5124a3fca2267d73041f56e7580e`
  (multi-arch index digest, resolved 2026-11-03), which carries Chromium and its OS
  libraries — no `playwright install` step;
- runs a digest-pinned PlantUML service
  `plantuml/plantuml-server:jetty-v1.2026.9@sha256:065c66adc6728ba6dd353a29d4ada73f8358b68549a0f710c4bf46b43c103618`
  with `PLANTUML_SECURITY_PROFILE: SANDBOX`, so a diagram cannot make the renderer
  read a URL;
- waits for that service with a bounded probe — 24 × (3 s probe + 2 s sleep) = 120 s
  worst case (NFR-002) — and on timeout fails with a message naming the service;
- builds with `DK_DIAGRAM_BUILD_RENDER: build` and `DK_PLANTUML_SERVER_URL`, and the
  Build step re-checks the mode in its `run:` block and hard-fails — a silent drift
  back to client mode would still publish, so the guard turns it into an error;
- restores the diagram render cache (`.dk-diagram-cache`) under the key prefix
  `dk-diagram-0.6.0-` so warm builds stay inside the cold-build budget of NFR-001.

Pin table and bump rule:

| Pin | Where | Bumped with |
|---|---|---|
| Playwright container *tag* | container image, `site/package.json` (`playwright`) | tag, digest and pin together |
| Playwright container *digest* | container image | re-resolve `docker buildx imagetools inspect` on every tag bump |
| PlantUML image *digest* | service image | with the DocKitty release that adopts a new server |
| Diagram cache key prefix `dk-diagram-0.6.0-` | restore step | with the `@spec-kitty/doc-toolkit` pin in `site/package.json` |

Local builds follow the same mode: the site auto-detect upgrading to build mode
whenever it finds a Chromium is now *intentional behaviour*, and
`docs/guides/docsite.md` documents the full-parity recipe (pinned local PlantUML
server, `npx playwright install chromium` outside the container path).

## Consequences

- CI needs containers: the job runs in the Playwright image with a service container,
  so its steps run in bash there and steps that touch the repository root override the
  `site/` default working directory.
- Local builds need either the pinned PlantUML server and a Chromium (full parity) or
  accept the client-mode difference — schematic pages then omit their diagrams, and
  the published site never matches what the local build showed.
- The in-step guard prevents silent fallback: an edit that drops `build` mode fails
  CI instead of quietly shipping client-rendered pages.
- Cold builds pay Chromium startup and PlantUML rendering; the diagram cache keyed on
  `docs/**/*.md` keeps warm builds well inside the NFR-001 budget (15 minutes warm,
  30 minutes cold).

## Trade-offs against client mode

Client mode builds faster and needs no containers, but pushes rendering into the
reader's browser (flash of unrendered diagram, JavaScript required) and, decisively,
cannot render the schematic pages the contract and schema references need. The
maintainer accepted the heavier pipeline for the deterministic, searchable output and
the fail-closed build.
