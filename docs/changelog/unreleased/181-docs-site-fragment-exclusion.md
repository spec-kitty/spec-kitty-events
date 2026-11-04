---
section: Changed
---

- The docs site now runs on doc-kitty 0.6.0 (#181), whose native exclusion keeps
  `docs/changelog/unreleased/` out of the site: the loader `exclude:` in
  `site/astro.config.mjs` drops the folder from routes, sidebar, search, sitemap, rss,
  llms.txt and the ADR hub, and the local gates skip it through `--exclude` in
  `npm run validate` (toolkit ADR-0070). The docs-site workflow's stopgap that removed
  the folder from its checkout is gone; fragments are now excluded natively everywhere.
  - ADR 0005 carries a dated amendment recording that the fragments folder holds entries,
    not pages, and that no docs gate flags it any more.
