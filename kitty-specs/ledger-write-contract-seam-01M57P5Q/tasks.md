# Tasks: Ledger-write contract seam (#118)

## WP01: persistence module and unit tests (red-first)
Prompt: [tasks/WP01-persistence-module.md](tasks/WP01-persistence-module.md)

T001 Red-first: issue-pinned `@pytest.mark.regression` repro asserting `from spec_kitty_events.persistence import append_event` resolves (WP01)
T002 Error hierarchy: `LedgerAppendError`, `LedgerValidationError`, `LedgerTornTailError` (WP01)
T003 `build_record`: three-kind normalisation, payload-class → type map, envelope/payload validation, JSON-native walk (WP01)
T004 `serialize_record` (pinned byte rule) and `append_event` (single O_APPEND write, torn-tail refusal) (WP01)
T005 Unit tests per branch, hypothesis no-partial-write property, reported benchmark; convert the T001 repro into a focused unit test (WP01)

## WP02: `ledger_append` fixtures and packaged runner
Prompt: [tasks/WP02-ledger-append-fixtures.md](tasks/WP02-ledger-append-fixtures.md)

T006 Register category/fixture_type `ledger_append` in `conformance/loader.py` (WP02)
T007 Author valid fixtures: event, core-shaped glossary dict, MissionReopened, FollowUpRecorded, GlossarySenseUpdated, model, non-ASCII, float, multi-append (WP02)
T008 Author invalid fixtures: unknown type, missing type, invalid payload, unregistered model, NaN, torn tail (WP02)
T009 Manifest entries (`min_version` 10.8.0) and the packaged runner `conformance/test_ledger_append.py`; keep the generic event gate excluding the category (WP02)

## WP03: deprecation, 10.8.0 release surfaces and docs
Prompt: [tasks/WP03-release-and-docs.md](tasks/WP03-release-and-docs.md)

T010 `LOCAL_ONLY_EVENT_TYPES` deprecation comment, plus a no-warning test under `-W error` (WP03)
T011 Version bump to 10.8.0: `__version__`, pyproject, README masthead/pip, COMPATIBILITY, CHANGELOG, `uv.lock` (WP03)
T012 `docs/api/ledger-write-contract.md` plus the `docs/api/README.md` link (WP03)
T013 Run the full blast radius plus the version guard; draft the core follow-up issue text (WP03)
