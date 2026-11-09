# Quickstart: verifying the write core locally

```bash
# Python: the encoder and the packaged conformance (status_append + the parity corpus shape check)
uv run pytest --pyargs spec_kitty_events.conformance
uv run mypy --strict src/spec_kitty_events
uv run ruff check . && uv run ruff format --check .

# Java: every module, with the contract bundle (runbook §5)
export CONTRACT_BUNDLE=... CONTRACT_BUNDLE_SHA256=...   # see docs/guides/service-cutover-runbook.md §5
./gradlew verifyAll        # or: make test-gradle
# Focused:
./gradlew :event-log:test :command-cli:test

# Regenerate the oracles against core at the pin (test-only; needs a core checkout at service/core-pin.json)
SPEC_KITTY_CORE_CHECKOUT=/path/to/core uv run python scripts/conformance/generate_status_append.py --check
SPEC_KITTY_CORE_CHECKOUT=/path/to/core uv run python scripts/conformance/generate_transition_parity.py --check

# Try it by hand: start the service against a repository and post one unit
java -jar service/command-cli/build/libs/command-cli-*.jar serve --git-common-dir "$(git rev-parse --git-common-dir)"
# Read port and token from <gcd>/spec-kitty-write-service/endpoint.json, then:
curl -s -H "Authorization: Bearer $TOKEN" -H 'Spec-Kitty-Write-Protocol: 1' -H 'Content-Type: application/json' \
  --data @contracts/mission-write/examples/TransitionUnit.claim.yaml.json \
  http://127.0.0.1:$PORT/api/v1/missions/$MISSION_ID/work-package-transitions
```

Known quirk: `:mission-status-api:perfTest` (`Nfr001OverviewLatencyTest`) fails on a loaded machine and passes in CI. It is not a regression.
