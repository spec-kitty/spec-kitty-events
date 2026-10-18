// @ts-check
import { defineConfig } from 'astro/config';
import { defineDocKittyIntegrations } from '@spec-kitty/doc-toolkit/config';
import { specKittyTheme } from '@spec-kitty/doc-toolkit/themes/spec-kitty/index.ts';

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
    // Mermaid fences render in the browser (doc-kitty's client renderer); no PlantUML
    // or Chromium is needed at build time.
    diagrams: true,
    // MUST match the loader in src/content.config.ts.
    indexBasename: ['README'],
  }),
});
