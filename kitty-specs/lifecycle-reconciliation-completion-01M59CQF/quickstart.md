# Quickstart: verifying this mission

```bash
uv run --frozen python -m spec_kitty_events.schemas.generate   # regenerate (WP01); then:
uv run --frozen pytest tests/integration/test_schema_drift.py -q
uv run --frozen pytest --pyargs spec_kitty_events.conformance -q   # >= 727 passed, 0 failed
uv run --frozen pytest tests/unit/test_decision_significance.py tests/test_decision_significance_producer_row.py tests/test_lifecycle_conformance.py tests/unit/test_zeitgeist_attrs.py -q
make test-fast
uv run --frozen mypy --strict src
uv run --frozen ruff check . && uv run --frozen ruff format --check .
git diff main --stat -- src/spec_kitty_events/schemas tests  # additions only (NFR-004)
```

Check the exact generate invocation against `src/spec_kitty_events/schemas/generate.py` before use.
