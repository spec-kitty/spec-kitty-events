---
section: Changed
---

- The page lists in `docs/changelog/README.md` and `docs/adr/README.md` are generated from
  the pages when the docsite builds (`scripts/docs_index.py`, #176, ADR 0005), so a pull
  request that adds a changelog page or an ADR no longer edits either index. `make lint`
  runs `--check`, which keeps the committed index free of a hand list.
