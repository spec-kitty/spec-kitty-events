# Quickstart: verifying M2

```bash
# Python: encoder, edge graph, differential expectations
uv run pytest tests/test_status_log.py tests/test_iso8601_fromisoformat.py tests/test_status_transition*.py tests/test_fold_differential.py -q
uv run --python 3.10 pytest tests/test_status_log.py -q && uv run --python 3.13 pytest tests/test_status_log.py -q
uv run pytest --pyargs spec_kitty_events.conformance
uv run pytest tests/test_service_workflows.py -q
ruff check . && ruff format --check . && uv run mypy --strict src
uv run python scripts/changelog.py check && uv run python scripts/changelog.py guard

# Java: fold, clock, ack bytes, architecture
./gradlew :command-cli:test :event-log:test   # then ./gradlew verifyAll with CONTRACT_BUNDLE exported

# Docs
(cd site && npm run validate); python3 scripts/docs_index.py --clean
```
