#!/usr/bin/env bash
# Run the whole Gradle build (`./gradlew verifyAll`: the Java binding, the services and the contracts) the way
# service.yml's contract-bundle and gradle jobs do (docs/guides/service-cutover-runbook.md section 5).
#
# `:mission-status-api:check` serves a contract document that contracts/tools/bundle.py writes once, outside the
# repository, and hands to the build with its sha256 (CONTRACT_BUNDLE, CONTRACT_BUNDLE_SHA256). This script writes
# that bundle into a temporary directory, exports both variables, and runs verifyAll with a JDK 25 from
# scripts/ensure_jdk25.sh as the only toolchain, as `make test-java` does. Extra arguments go to Gradle.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

export LC_ALL=C.UTF-8
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT

uv run --frozen python contracts/tools/bundle.py --root contracts --out "$out" --bundle-only
export CONTRACT_BUNDLE="$out/bundle/mission-status/openapi.yaml"
CONTRACT_BUNDLE_SHA256="$(sha256sum "$CONTRACT_BUNDLE" | cut -d' ' -f1)"
export CONTRACT_BUNDLE_SHA256

# ContractDocumentTest shells out to `python3 contracts/tools/bundle.py`, which needs PyYAML: use the project venv
# that `uv run` just synced, not whatever python3 the machine has.
export PATH="$PWD/.venv/bin:$PATH"

jh="$(scripts/ensure_jdk25.sh)"
JAVA_HOME="$jh" ./gradlew --no-daemon -Dorg.gradle.java.installations.paths="$jh" verifyAll "$@"
