# WP01 review feedback (cycle 1) — changes requested

## MAJOR

1. **New conformance fixtures are not shipped in the wheel.** `tests/test_wheel_contents.py::test_every_tracked_conformance_file_is_packaged` is red on the lane and green on the base. All 16 files under `src/spec_kitty_events/conformance/fixtures/decision_significance/{valid,invalid}/` are unmatched by `[tool.setuptools.package-data]` in `pyproject.toml` (lines ~47-80). A consumer running `pytest --pyargs spec_kitty_events.conformance` from an installed wheel would find manifest entries pointing at missing files.
   **Fix:** add `"conformance/fixtures/decision_significance/valid/*.json"` and `"conformance/fixtures/decision_significance/invalid/*.json"` to that list. `pyproject.toml` belongs to WP03 for the version bump, so record this two-line package-data addition as an ownership extension in the WP01 commit message. Re-run `tests/test_wheel_contents.py`.

## MINOR

2. The `significance_score` fixtures (`{"score": 7, "factors": {...}}`) do not match what core actually serializes: a `SignificanceScore` with `dimensions[6]`, `composite`, `band`, `hard_trigger_classes`, `effective_band` (core significance.py:298). The open-object contract accepts both, but use at least one valid fixture with the real core shape so the fixtures show what is actually emitted.

## Verified OK (no change needed)

- Field-by-field fidelity to core `SignificanceEvaluatedPayload` / `TimeoutExpiredPayload` / `ResolvedRACIBinding` (all three provenance rules), `frozen` + `extra="forbid"`.
- R1 honoured (new `RaciRoleBinding`, `RuntimeActorIdentity` untouched). R2 honoured (`actor_id` required, `min_length=1`).
- The change is additive (32 files, insertions only, no existing expectation changed). There is no core import. Schemas are generated (`generate --check`: 130 up to date).
- Each invalid fixture fails for exactly one reason, the one it is meant to test.
- mypy --strict clean, ruff check/format clean, conformance 747 passed, WP tests 49 passed.
