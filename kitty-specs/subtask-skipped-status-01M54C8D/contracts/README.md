# Contracts

This mission introduces **no new contract**. The canonical contract for the
subtask vocabulary is the already-shipped producer-contract JSON schema:

- `src/spec_kitty_events/producer_contract/data/wp.innerstate.changed.v1.schema.json`

Its `subtasks` property is a string enum that **already lists `skipped`** (plus
the active `Lane` values). The on-wire envelope version stays `3.0.0`.

This mission brings the Python binding (`diary.py`) into line with that existing
schema — a binding-catches-schema change, not a schema change. Because the
schema is unchanged, the committed schema-generation / drift checks stay green,
and no new `.schema.json` is produced.

The conformance obligation is a new fixture under
`src/spec_kitty_events/conformance/fixtures/status_diary/` containing a
`skipped` subtask, exercised by the packaged conformance gate
(`uv run pytest --pyargs spec_kitty_events.conformance`).
