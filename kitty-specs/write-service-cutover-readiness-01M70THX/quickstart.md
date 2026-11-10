# Quickstart: verify the mission

```bash
# Java: event-log repair primitive, command-cli operation, drain, logging, evidence, root
./gradlew :event-log:check :command-cli:check :contracts:check

# Python: corpus, generator, packaged conformance gate
uv run pytest tests/test_generate_transition_parity.py tests/test_service_workflows.py tests/test_docs_service_reference.py
uv run pytest --pyargs spec_kitty_events.conformance
uv run python scripts/conformance/generate_transition_parity.py --check   # needs the core pin checkout (CI write-core-parity job)

# Contract tools (not CI gates, #151): record the output
for t in breaking_check citation_check provisional_check layout_check structure_check; do
  uv run python contracts/tools/$t.py --root contracts || echo "FAIL $t"; done

# Lint, changelog, docs
ruff check . && ruff format --check .
uv run python scripts/changelog.py check && uv run python scripts/changelog.py guard
(cd site && npm run validate) && python3 scripts/docs_index.py --clean
```

## Manual repair against a running service (the runbook stopgap)

```bash
EP="$(git rev-parse --git-common-dir)/spec-kitty-write-service/endpoint.json"   # see LAUNCHER.md for the real location
curl -sS -X POST "http://127.0.0.1:$(jq -r .port "$EP")/api/v1/missions/<missionId>/status-log-repairs" \
  -H "Authorization: Bearer $(jq -r .token "$EP")" -H "Spec-Kitty-Write-Protocol: 1" \
  -H 'Content-Type: application/json' \
  -d '{"context":{"actor":"operator","checkout":".","missionDirectory":"kitty-specs/<mission>","writeSurface":"checkout_append","destinationRef":null}}'
```

WP08 confirms the exact paths and the field names of the endpoint file in the runbook.
