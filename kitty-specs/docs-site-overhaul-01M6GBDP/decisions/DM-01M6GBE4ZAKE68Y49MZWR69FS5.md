# Decision Moment `01M6GBE4ZAKE68Y49MZWR69FS5`

- **Mission:** `docs-site-overhaul-01M6GBDP`
- **Origin flow:** `specify`
- **Slot key:** `specify.schemas.page-granularity`
- **Input key:** `schema_page_granularity`
- **Status:** `resolved`
- **Created:** `2026-11-03T09:21:55.178280+00:00`
- **Resolved:** `2026-11-03T09:24:47.769767+00:00`
- **Opened by:** `cli`
- **Other answer:** `false`

## Question

Which event schemas get their own Schematic page (diagram + property table)?

## Options

- Every schema file, generated (about 132 payload/sub-model pages + 36 producer rows + 2 envelope pages)
- Top-level event payloads and envelopes only; shared sub-models reached through the property tables of the pages that use them
- Other

## Final answer

Every schema file gets a generated page (all payload and sub-model schemas in src/spec_kitty_events/schemas/, the 36 producer_contract/data row schemas, and the two envelope pages); a drift test fails on a schema without a page or a page without a schema.

## Rationale

_(none)_

## Change log

- `2026-11-03T09:21:55.178280+00:00` - opened
- `2026-11-03T09:24:47.769767+00:00` - resolved (final_answer="Every schema file gets a generated page (all payload and sub-model schemas in src/spec_kitty_events/schemas/, the 36 producer_contract/data row schemas, and the two envelope pages); a drift test fails on a schema without a page or a page without a schema.")
