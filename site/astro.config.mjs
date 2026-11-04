// @ts-check
import { defineConfig } from 'astro/config';
import { defineDocKittyIntegrations } from '@spec-kitty/doc-toolkit/config';
import { specKittyTheme } from '@spec-kitty/doc-toolkit/themes/spec-kitty/index.ts';
import starlightOpenAPI from 'starlight-openapi';

// The docs tree lives at the repository root (../docs); this site/ project only
// renders it. GitHub Pages serves a project site under /<repo>, so BASE is that
// path. SITE and BASE can be overridden for a local preview at the domain root.
const SITE = process.env.DK_SITE ?? 'https://spec-kitty.github.io';
const BASE = process.env.DK_BASE ?? '/spec-kitty-events';

export default defineConfig({
  site: SITE,
  base: BASE,
  integrations: defineDocKittyIntegrations({
    title: 'spec-kitty-events',
    description: 'The canonical TeamSpace event vocabulary: CloudEvents envelopes, the status-diary reducer, producer row schemas and the total order.',
    // MUST equal the site `base` above.
    base: BASE,
    // MUST equal the loader `base` in src/content.config.ts. Without it the toolkit
    // looks for site/docs and lists drafts in the sitemap.
    docsDir: '../docs',
    // docs/changelog/unreleased/ holds changelog fragments, not pages (ADR 0005): excluded from the
    // loader, routes, sidebar, search, sitemap, rss, llms.txt, agent API and ADR hub (toolkit ADR-0070).
    // The local gates skip it via --exclude in the validate script. Replaces the docs-site.yml rm stopgap (#181).
    exclude: ['changelog/unreleased'],
    // The two service contracts as API reference pages at <base>/reference/api/<label>/, each from the
    // single-file bundle staged by scripts/stage-docs-inputs.mjs (#194). Both are unreleased until the
    // first final service-v* tag; the flip rule lives in docs/api/services.md (FR-009).
    openapi: {
      renderer: starlightOpenAPI,
      specs: [
        { path: 'openapi/mission-status.yaml', label: 'mission-status', released: false },
        { path: 'openapi/mission-write.yaml', label: 'mission-write', released: false },
      ],
    },
    // Build Kitty brand mark (Aegean-blue bow tie) over the Spec Kitty theme, with a
    // thin consumer layer re-tinting the accent from the shared yellow to Build Kitty's
    // Aegean blue. spec-kitty-events is the event-contract surface of the Build Kitty
    // product line.
    theme: {
      extends: specKittyTheme,
      assets: { logo: './src/assets/build-kitty.png' },
      customCss: ['./src/styles/build-kitty.css'],
    },
    markua: true,
    // Diagrams render at build time (doc-toolkit 0.6.0): Mermaid through a Playwright
    // Chromium, PlantUML through DK_PLANTUML_SERVER_URL — the mode CI builds in
    // (docs-site.yml guards it). Lacking a Chromium the toolkit falls back to client
    // rendering; see docs/guides/docsite.md for the local full-parity recipe.
    diagrams: true,
    // MUST match the loader in src/content.config.ts.
    indexBasename: ['README'],
  }),
});
