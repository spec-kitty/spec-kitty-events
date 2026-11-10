---
section: Changed
---

- `contracts/tools/breaking_check.py` no longer deletes a stable property that only references a
  provisional schema (maintainer ruling D11, 2026-11-10; #236). The bundler inlines the schema, marker
  included, so marking `WriteOperation` `x-provisional` made `TransitionUnit.operation` look provisional
  and reported `removed the request property operation` twice, with no real break. The check now tells
  the two cases apart by comparing the property's `x-provisional` and `title` with the schema file's
  own; such a property keeps its presence and its requiredness under comparison, and only the schema's
  own type and enum are replaced by the same neutral stub on both sides. A property carrying its own
  `x-provisional` is still dropped. Four negative cases cover it
  (`breaking-provisional-ref-*` in `contracts/tools/negative_cases.json`). The stub is used only where
  both sides reference the same schema at that path. A property re-pointed to a different provisional
  schema is compared with its real bodies, so a narrowing is still `BREAKING_WITHOUT_MAJOR`
  (`breaking-provisional-ref-repointed-property`).
- `contracts/tools/breaking_check.py` treats a `--baseline-root` that carries the same unreleased
  `-SNAPSHOT` as the candidate as work in progress, as it already does for tag baselines. A change
  inside it is no longer `BUNDLE_CHANGED_VERSION_SAME`; a breaking change against it is still reported
  (negative case `breaking-snapshot-baseline-same-version`; #236, D11).
