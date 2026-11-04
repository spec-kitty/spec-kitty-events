# Data Model: docs-site-overhaul-01M6GBDP

Docs/content entities only — this mission defines no API contracts (see `meta.json` → `contracts: "none"`). Names follow the spec's Domain Language table.

## Entity: Schema page (generated, committed)

| Field | Rule |
|---|---|
| identity | one page per in-scope schema file; 168 total (130 `schemas/**.schema.json` incl. `event.schema.json`; 36 `producer_contract/data/*.schema.json`; 2 `producer_contract/envelope*.schema.json`) |
| frontmatter | `title`, `description` (≤ 180 chars), `doc_status`, `updated`, `type` (from section), `kind: Schematic`, `schematic_path` (site-root-relative path of the staged copy) |
| body | generator-owned prose + links; toolkit appends `## Diagram` (PlantUML `@startjson`, build mode only), `## Properties`, `## Source`; an authored `##` of the same name wins (doc-kitty ADR-0067) |
| family membership | exactly one hub: its event family, `shared`, producer rows, or the envelope placement (A2 in plan.md) |
| invariants | C-001: never hand-edited; bound file exists at build time; linked from its family hub (drift check, FR-007) |

## Entity: Family hub (authored, generator-extended)

| Field | Rule |
|---|---|
| files | `docs/events/<family>.md` (6 existing), new `docs/events/shared/` hub, `docs/architecture/producer-row-schemas/README.md` (converted from the single page), `docs/api/envelope/README.md` |
| member list | inside `<!-- BEGIN GENERATED: schemas -->`/`END` markers, written by `scripts/schema_pages.py` (same convention as `scripts/docs_index.py`) |
| invariants | every member page linked exactly once; no member outside markers |

## Entity: Service API reference (published)

| Field | Rule |
|---|---|
| identity | one reference per contract module: `mission-status` (`contracts/mission-status/`), `mission-write` (`contracts/mission-write/`) |
| input | single-file bundle from `bundle.py --bundle-only`, output OUTSIDE the repo tree (`$SKE_CONTRACT_BUNDLES_DIR`; the tool refuses in-tree output — `OUT_INSIDE_REPOSITORY`) (+ sha256) |
| config | `openapi.specs[]`: `{ path: 'openapi/<module>.yaml', label: '<module>', released: false }` until the first final `service-v*` tag; flip rule documented in `docs/guides/service-protocol-and-releases.md` (FR-009) |
| addresses | `<base>/reference/api/<label>/` (+ raw `openapi.yaml` and `.sha256` beside it) |
| hub | `docs/api/services.md` (FR-010), linked by `docs/api/README.md` |

## Entity: Staged input (build-time copy, git-ignored)

| Field | Rule |
|---|---|
| producer | `site/scripts/stage-docs-inputs.mjs` (predev/prebuild) |
| targets | `site/schemas/` (mirrors repo-relative layout of the 130 + 36 + 2 files), `site/openapi/` |
| parity check | fail-closed: staged set ⇋ `schematic_path` set; hub link parity asserted by `schema_pages.py --check` |

## Drift check (FR-007, US4)

- Shape: set-equality between {in-scope schema files} and {pages with `schematic_path`} + hub-link membership; failure names the offending file (SC-005).
- Run at: `make lint` (`scripts/schema_pages.py --check`) and the docs workflow (same check); staging script parity as backstop.
