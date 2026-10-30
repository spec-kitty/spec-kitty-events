# Quickstart: events domain services home

## Prerequisites
- JDK 25. The Temurin recipe is in core's `acceptance-jvm/README.md`; `scripts/ensure_jdk25.sh` checks for it. GraalVM CE 25 is needed only to build native images locally.
- `uv sync --frozen --all-extras`
- Node 22 for the docsite (`site/`)

## Build and test (pre-cutover)
```bash
./gradlew check                                        # Java binding (UpcastCorpusTest)
make test-java                                         # same, via the Makefile
make test-fast
uv run pytest --pyargs spec_kitty_events.conformance
ruff check . && ruff format --check .
node ../doc-kitty/src/scripts/validate-frontmatter.mjs docs
node ../doc-kitty/src/scripts/check-links.mjs docs
```

## After cutover
```bash
./gradlew check                    # + service, domain-model, event-log (JUnit, ArchUnit, contract tests)
./gradlew :mission-status-api:nativeCompile   # GraalVM required
```

## Cutover
Follow `docs/guides/service-cutover-runbook.md`. It needs the operator's go-signal; tags additionally need operator approval.
